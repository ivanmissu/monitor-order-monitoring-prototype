package com.monitor.server.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.monitor.server.config.MonitorProperties;
import com.monitor.server.domain.BizLine;
import com.monitor.server.domain.TimeRange;
import com.monitor.server.ingest.EventEnvelope;
import com.monitor.server.query.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Caffeine 读侧查询缓存（装饰器，{@code @Primary}）。
 *
 * <p>缓存对象：链路健康、大盘聚合、城市排行、热力图、取消矩阵、风控榜、
 * 接口监控、拓扑、元监控等<b>热点只读查询</b>；TTL 默认 15s
 * （{@code monitor.cache.query-ttl-seconds}），与前端 20–60s 的轮询节奏匹配。
 *
 * <p>刻意<b>不缓存</b>：KPI/告警求值（atomicSum/points）、订单明细与事件时间线
 * （客服工作台要看到 Kafka 实时入库的最新事件）、告警列表与静默（认领/解决必须即时可见）。
 * 写方法（insertEvents/save/saveSilence）直接穿透。
 *
 * <p>每 60s 输出一次命中率统计，作为缓存中间件真正参与链路的可观测证据。
 */
@Component
@Primary
@ConditionalOnClass(Caffeine.class)
public class CachingMonitorStore implements MonitorStore {

    private static final Logger log = LoggerFactory.getLogger(CachingMonitorStore.class);

    private final MonitorStore delegate;
    private final Cache<MethodKey, Object> cache;

    /** 方法名 + 参数构成的缓存键（record 自动 equals/hashCode）。 */
    private record MethodKey(String method, List<Object> args) {
    }

    public CachingMonitorStore(ObjectProvider<ClickHouseStore> clickHouse,
                               ObjectProvider<DemoStore> demo,
                               MonitorProperties props) {
        MonitorStore primary = clickHouse.getIfAvailable();
        if (primary == null) {
            primary = demo.getIfAvailable();
        }
        if (primary == null) {
            throw new IllegalStateException("未找到任何 MonitorStore 实现（clickhouse/demo）");
        }
        this.delegate = primary;
        int ttl = props.getCache().getQueryTtlSeconds();
        this.cache = ttl <= 0 ? null : Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttl))
                .maximumSize(props.getCache().getMaximumSize())
                .recordStats()
                .build();
        log.info("Caffeine 查询缓存已启用: 委托={} TTL={}s maxSize={}（TTL=0 表示关闭）",
                primary.getClass().getSimpleName(), ttl, props.getCache().getMaximumSize());
    }

    /** 缓存命中情况每 60s 打点一次。 */
    @Scheduled(fixedRate = 60_000, initialDelay = 60_000)
    public void reportStats() {
        if (cache == null) {
            return;
        }
        CacheStats s = cache.stats();
        log.info("CACHE query-cache hit={} miss={} hitRate={} size={} evictions={}",
                s.hitCount(), s.missCount(),
                String.format("%.1f%%", s.hitRate() * 100),
                cache.estimatedSize(), s.evictionCount());
    }

    @SuppressWarnings("unchecked")
    private <T> T cached(String method, List<Object> args, java.util.function.Supplier<T> loader) {
        if (cache == null) {
            return loader.get();
        }
        Object hit = cache.getIfPresent(new MethodKey(method, args));
        if (hit != null) {
            return (T) hit;
        }
        T value = loader.get();
        if (value != null) {
            cache.put(new MethodKey(method, args), value);
        }
        return value;
    }

    private static List<Object> key(Object... args) {
        return List.of(args);
    }

    // ── 元信息：不缓存（新鲜度告警依赖实时值） ──────────────────────────────

    @Override
    public Instant freshness() {
        return delegate.freshness();
    }

    @Override
    public boolean backfillRunning(LocalDate dt) {
        return delegate.backfillRunning(dt);
    }

    // ── 聚合查询：不缓存（告警求值与 KPI 需要实时口径） ────────────────────

    @Override
    public long atomicSum(String sumExpr, Metrics.Query query) {
        return delegate.atomicSum(sumExpr, query);
    }

    @Override
    public List<Metrics.Point> points(String sumExpr, Metrics.Query query) {
        return delegate.points(sumExpr, query);
    }

    @Override
    public long sampleSize(Metrics.Query query) {
        return delegate.sampleSize(query);
    }

    // ── 热点只读查询：缓存 ────────────────────────────────────────────────

    @Override
    public List<LinkNode> linkNodes() {
        return cached("linkNodes", key(), delegate::linkNodes);
    }

    @Override
    public Map<BizLine, BizAgg> bizAggregates(TimeRange range) {
        return cached("bizAggregates", key(range), () -> delegate.bizAggregates(range));
    }

    @Override
    public List<CityRow> cityRank(TimeRange range, BizLine biz, String sort, int limit) {
        return cached("cityRank", key(range, biz, sort, limit),
                () -> delegate.cityRank(range, biz, sort, limit));
    }

    @Override
    public List<HeatCell> heatmap(TimeRange range, BizLine biz, String metric, int rows) {
        return cached("heatmap", key(range, biz, metric, rows),
                () -> delegate.heatmap(range, biz, metric, rows));
    }

    @Override
    public CancelMatrix cancelMatrix(TimeRange range, BizLine biz) {
        return cached("cancelMatrix", key(range, biz), () -> delegate.cancelMatrix(range, biz));
    }

    @Override
    public List<RiskCityRow> riskCities(String window) {
        return cached("riskCities", key(window), () -> delegate.riskCities(window));
    }

    @Override
    public List<RuleHitSeries> ruleHits(List<String> ruleIds, TimeRange range, BizLine biz) {
        return cached("ruleHits", key(ruleIds, range, biz),
                () -> delegate.ruleHits(ruleIds, range, biz));
    }

    @Override
    public List<RiskEntity> topEntities(String window, String sort, int limit) {
        return cached("topEntities", key(window, sort, limit),
                () -> delegate.topEntities(window, sort, limit));
    }

    @Override
    public List<ApiRow> apis(BizLine biz) {
        return cached("apis", key(biz), () -> delegate.apis(biz));
    }

    @Override
    public Topology topology(String window) {
        return cached("topology", key(window), () -> delegate.topology(window));
    }

    @Override
    public List<SlowCall> slowCalls(int p99MinMs) {
        return cached("slowCalls", key(p99MinMs), () -> delegate.slowCalls(p99MinMs));
    }

    @Override
    public AlertStatsRow alertStats(String week) {
        return cached("alertStats", key(week), () -> delegate.alertStats(week));
    }

    @Override
    public List<PipelineRow> pipeline() {
        return cached("pipeline", key(), delegate::pipeline);
    }

    @Override
    public LagRow lag() {
        return cached("lag", key(), delegate::lag);
    }

    @Override
    public ReconcileRow reconcile(LocalDate dt) {
        return cached("reconcile", key(dt), () -> delegate.reconcile(dt));
    }

    @Override
    public List<EventQualityRow> eventQuality() {
        return cached("eventQuality", key(), delegate::eventQuality);
    }

    // ── 明细 / 告警 / 写入：不缓存 ────────────────────────────────────────

    @Override
    public Optional<OrderSnapshot> order(String orderId) {
        return delegate.order(orderId);
    }

    @Override
    public List<OrderEventRow> orderEvents(String orderId, TimeRange range) {
        return delegate.orderEvents(orderId, range);
    }

    @Override
    public List<AlertRow> alerts(AlertFilter filter) {
        return delegate.alerts(filter);
    }

    @Override
    public Optional<AlertRow> alert(long alertId) {
        return delegate.alert(alertId);
    }

    @Override
    public AlertRow save(AlertRow row) {
        return delegate.save(row);
    }

    @Override
    public long nextAlertId() {
        return delegate.nextAlertId();
    }

    @Override
    public List<SilenceRow> silences() {
        return delegate.silences();
    }

    @Override
    public SilenceRow saveSilence(SilenceRow silence) {
        return delegate.saveSilence(silence);
    }

    @Override
    public void deleteSilence(String silenceId) {
        delegate.deleteSilence(silenceId);
    }

    @Override
    public int insertEvents(List<EventEnvelope> events) {
        return delegate.insertEvents(events);
    }

    @Override
    public void insertDirty(List<DirtyRow> rows) {
        delegate.insertDirty(rows);
    }

    @Override
    public List<DlqRow> dlq(String bizLine, String eventType, String reason,
                            LocalDate from, LocalDate to) {
        return delegate.dlq(bizLine, eventType, reason, from, to);
    }

    /** 仅供测试/诊断：当前委托实现。 */
    public MonitorStore delegate() {
        return delegate;
    }
}
