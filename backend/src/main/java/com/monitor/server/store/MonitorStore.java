package com.monitor.server.store;

import com.monitor.server.domain.BizLine;
import com.monitor.server.domain.Dims;
import com.monitor.server.domain.TimeRange;
import com.monitor.server.ingest.EventEnvelope;
import com.monitor.server.query.Metrics;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 存储访问契约。两套实现：
 * <ul>
 *   <li>{@code ClickHouseStore} —— 生产实现，聚合层与明细层分账号、分 profile；</li>
 *   <li>{@code DemoStore} —— 无外部依赖的内存实现，与前端原型同源，用于联调与本地启动。</li>
 * </ul>
 */
public interface MonitorStore {

    // ── 元信息 ────────────────────────────────────────────────────────────

    /** 本存储当前覆盖到的最新事件时间（写入响应头 X-Monitor-Freshness）。 */
    Instant freshness();

    /** T+1 回补进行中的分区暂不对外（50302）。 */
    boolean backfillRunning(LocalDate dt);

    // ── 聚合查询（agg_1m / 5m / 1h / 1d） ──────────────────────────────────

    /** 执行字典中的求和表达式，返回标量。 */
    long atomicSum(String sumExpr, Metrics.Query query);

    /** 执行字典中的求和表达式，返回时间序列。 */
    List<Metrics.Point> points(String sumExpr, Metrics.Query query);

    /** 该维度组合的事件样本量，用于小样本门槛判定。 */
    long sampleSize(Metrics.Query query);

    // ── 链路健康 / 业务线概览 ──────────────────────────────────────────────

    record LinkNode(String key, String label, String status, String detail, String metric,
                    Long alertId, Map<String, Object> metrics, OffsetDateTime since) {
    }

    List<LinkNode> linkNodes();

    record BizAgg(long orders, long delivered, long gtvFen, double delta, boolean rateGood) {
    }

    Map<BizLine, BizAgg> bizAggregates(TimeRange range);

    // ── 经营大盘 ──────────────────────────────────────────────────────────

    record CityRow(long cityId, String name, long delivered, double deliveryRate, long gtvFen,
                   double yoy, int firingAlerts, String priority) {
    }

    List<CityRow> cityRank(TimeRange range, BizLine biz, String sort, int limit);

    record HeatCell(long cityId, String cityName, int hour, double value, long sample, boolean peak) {
    }

    List<HeatCell> heatmap(TimeRange range, BizLine biz, String metric, int rows);

    // ── 履约质量 ──────────────────────────────────────────────────────────

    record ReasonCell(String code, String name, long value) {
    }

    record CancelCell(String cancelBy, String stage, String fault, long value, List<ReasonCell> reasonTop) {
    }

    record CancelMatrix(long cancelCnt, long denominator, List<CancelCell> cells) {
    }

    CancelMatrix cancelMatrix(TimeRange range, BizLine biz);

    record RiskCityRow(long cityId, String name, int score, String level, String mainMetric,
                       String mainLabel, double mainValue, String suggestedAction) {
    }

    List<RiskCityRow> riskCities(String window);

    // ── 客服明细（ods_order_event） ────────────────────────────────────────

    record OrderSnapshot(String orderId, String bizLine, String status, String statusLabel,
                         long cityId, String cityName, String seatType, long amountFen,
                         String driverHashMasked, String tripId, LocalDate dt, int eventCount,
                         String completeness, int expectedNodes, List<String> missing) {
    }

    Optional<OrderSnapshot> order(String orderId);

    record OrderEventRow(int seq, OffsetDateTime eventTime, String eventType, String label,
                         String note, Long amountFen, Map<String, Object> props,
                         Long gapFromPrevSec, boolean abnormal) {
    }

    /** 走 bloom_filter(order_id) 索引 + 分区裁剪，独立 query profile。 */
    List<OrderEventRow> orderEvents(String orderId, TimeRange range);

    // ── 风控观测 ──────────────────────────────────────────────────────────

    record RuleHitPoint(String t, long hit, long frozenFen) {
    }

    record RuleHitSeries(String ruleId, String name, List<RuleHitPoint> points) {
    }

    List<RuleHitSeries> ruleHits(List<String> ruleIds, TimeRange range, BizLine biz);

    record RiskEntity(String driverHashMasked, String bizLine, int shortOrderCnt, int hitCnt,
                      List<String> hitRules, int riskScore, String level, long gtvFen,
                      OffsetDateTime firstSeen) {
    }

    List<RiskEntity> topEntities(String window, String sort, int limit);

    // ── 接口监控 ──────────────────────────────────────────────────────────

    record ErrCode(String code, double share) {
    }

    record ApiRow(String apiId, String path, String service, String serviceLabel, String bizLine,
                  long qps, double failRate, double warnThreshold, double badThreshold,
                  int p50Ms, int p99Ms, List<Double> trend, List<ErrCode> errTop,
                  String upstream, List<String> downstream, String linkedRule, Long linkedAlertId) {

        /** 状态分档由服务端按字典阈值判定，前端只渲染。 */
        public Dims.Health health() {
            if (failRate >= badThreshold) {
                return Dims.Health.BAD;
            }
            return failRate >= warnThreshold ? Dims.Health.WARN : Dims.Health.OK;
        }
    }

    List<ApiRow> apis(BizLine biz);

    record TopoNode(String id, String label, String sub, int x, int y, int w, int h,
                    boolean alert, String kind) {
    }

    record TopoEdge(String from, String to, long qps, double errorRate, int p99Ms,
                    String level, Long alertId, String metric) {
    }

    record Suppression(String sourceEdge, List<String> suppressedAlerts, String reason) {
    }

    record Topology(String window, OffsetDateTime generatedAt, List<TopoNode> nodes,
                    List<TopoEdge> edges, double okMax, double warnMax, List<Suppression> suppression) {
    }

    Topology topology(String window);

    record SlowCall(String path, String label, int p99Ms, int p95Ms, String bizLine, long qps) {
    }

    List<SlowCall> slowCalls(int p99MinMs);

    // ── 告警 ──────────────────────────────────────────────────────────────

    record AlertFilter(Set<Dims.AlertStatus> status, Set<Dims.AlertLevel> level, BizLine biz,
                       Long cityId, String ruleId, OffsetDateTime since) {
    }

    record AlertTimelineRow(OffsetDateTime at, String actor, String action, String note) {
    }

    record AlertRow(long alertId, String ruleId, Dims.AlertLevel level, String title,
                    BizLine bizLine, long cityId, String cityName, String seatType, String scopeText,
                    OffsetDateTime firedAt, int periods, double value, double baseline,
                    String baselineKind, double deltaPp, long numerator, long denominator,
                    Dims.AlertStatus status, String ackBy, OffsetDateTime ackAt,
                    String metricId, String metricVersion, String runbook,
                    int deduped, int aggregatedCities, String suppressedBy,
                    Dims.Judgement judgement, String rootCause, OffsetDateTime resolvedAt,
                    List<AlertTimelineRow> timeline) {

        public AlertRow claimed(String user, String note, OffsetDateTime at) {
            List<AlertTimelineRow> tl = new java.util.ArrayList<>(timeline);
            tl.add(new AlertTimelineRow(at, user, "claimed", note));
            return new AlertRow(alertId, ruleId, level, title, bizLine, cityId, cityName, seatType,
                    scopeText, firedAt, periods, value, baseline, baselineKind, deltaPp,
                    numerator, denominator, Dims.AlertStatus.CLAIMED, user, at, metricId,
                    metricVersion, runbook, deduped, aggregatedCities, suppressedBy,
                    judgement, rootCause, resolvedAt, List.copyOf(tl));
        }

        public AlertRow resolved(String user, Dims.Judgement j, String cause, String note,
                                 OffsetDateTime at) {
            List<AlertTimelineRow> tl = new java.util.ArrayList<>(timeline);
            tl.add(new AlertTimelineRow(at, user, "resolved", note));
            return new AlertRow(alertId, ruleId, level, title, bizLine, cityId, cityName, seatType,
                    scopeText, firedAt, periods, value, baseline, baselineKind, deltaPp,
                    numerator, denominator, Dims.AlertStatus.RESOLVED, ackBy, ackAt, metricId,
                    metricVersion, runbook, deduped, aggregatedCities, suppressedBy,
                    j, cause, at, List.copyOf(tl));
        }

        public long mttaSec() {
            return ackAt == null ? -1 : java.time.Duration.between(firedAt, ackAt).toSeconds();
        }
    }

    List<AlertRow> alerts(AlertFilter filter);

    Optional<AlertRow> alert(long alertId);

    AlertRow save(AlertRow row);

    long nextAlertId();

    record SilenceRow(String silenceId, Map<String, List<String>> matchers, OffsetDateTime from,
                      OffsetDateTime to, String reason, String owner, int suppressedCount, boolean auto) {
    }

    List<SilenceRow> silences();

    SilenceRow saveSilence(SilenceRow silence);

    void deleteSilence(String silenceId);

    record NoisyRule(String ruleId, int fired, int valid, double precision, String suggestion) {
    }

    record AlertStatsRow(String week, int fired, int valid, double precision, double target,
                         double noiseRate, int mttaP50Sec, int mttrP50Sec, double p0AckWithin5Min,
                         List<NoisyRule> topNoisyRules, Map<String, Integer> byLevel) {
    }

    AlertStatsRow alertStats(String week);

    // ── 元监控 ────────────────────────────────────────────────────────────

    record PipelineRow(String component, String status, String throughput,
                       OffsetDateTime heartbeatAt, String detail, String extra) {
    }

    List<PipelineRow> pipeline();

    record LagRow(double consumerLagP50Sec, double consumerLagP99Sec, String maxPartition,
                  long ingestDelayP50Sec, long ingestDelayP99Sec, long thresholdP99Sec, String status,
                  long lateCnt, double lateRate, long bufferedEvents, boolean replaying) {
    }

    LagRow lag();

    record MissingEvent(String eventType, int cnt, String likely) {
    }

    record GapSample(String orderId, String fromState, String toState, long gapSec) {
    }

    record ReconcileRow(LocalDate dt, int stateGapCnt, Map<String, Integer> byBizLine,
                        List<MissingEvent> missingEvents, List<GapSample> samples,
                        int dirtyCnt, List<String> dirtyTopReason) {
    }

    ReconcileRow reconcile(LocalDate dt);

    record EventQualityRow(String eventType, long total, long duplicated, long dirty,
                           double dirtyRate, double avgGapSec, String status) {
    }

    List<EventQualityRow> eventQuality();

    // ── 事件写入 ──────────────────────────────────────────────────────────

    /** 批量写入 ODS 明细，返回实际落库条数。 */
    int insertEvents(List<EventEnvelope> events);

    record DirtyRow(String eventType, String reason, String rawHead, String producer) {
    }

    /** 违规事件进死信表并计数告警，不污染正式数据。 */
    void insertDirty(List<DirtyRow> rows);

    record DlqRow(LocalDate dt, String eventType, String reason, long cnt,
                  List<String> samples, String producer) {
    }

    List<DlqRow> dlq(String bizLine, String eventType, String reason, LocalDate from, LocalDate to);
}
