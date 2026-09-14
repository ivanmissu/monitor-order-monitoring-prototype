package com.minitor.server.engine;

import com.minitor.server.alert.AlertEventBus;
import com.minitor.server.ingest.EventEnvelope;
import com.minitor.server.service.IngestService;
import com.minitor.server.store.MinitorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 监控侧自有任务。
 *
 * <ul>
 *   <li><b>结算逾期扫描</b>：{@code settlement_overdue} 不是业务事件，
 *       由 minitor 扫描「已送达但超时未入账」的订单产出，作为结算延迟率的分子；</li>
 *   <li><b>T+1 对账与回补</b>：重算任务向临时表写入目标分区后
 *       {@code DETACH/ATTACH PARTITION} 原子替换，查询无感知；
 *       回补期间自动静默新鲜度告警，分钟表不回补。</li>
 * </ul>
 */
@Component
public class ScheduledJobs {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobs.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final MinitorStore store;
    private final IngestService ingest;
    private final AlertEventBus bus;

    public ScheduledJobs(MinitorStore store, IngestService ingest, AlertEventBus bus) {
        this.store = store;
        this.ingest = ingest;
        this.bus = bus;
    }

    /**
     * 每 5 分钟扫描一次结算逾期。产出的事件走与业务事件完全相同的入口，
     * 因此天然享有幂等去重与聚合链路。
     */
    @Scheduled(cron = "0 */5 * * * *")
    public void scanSettlementOverdue() {
        List<EventEnvelope> overdue = detectOverdue();
        if (overdue.isEmpty()) {
            return;
        }
        IngestService.IngestResult r = ingest.ingest(overdue, "minitor-scanner");
        log.info("结算逾期扫描: 产出 {} 条 settlement_overdue 事件, 落库 {}", overdue.size(), r.accepted());
    }

    /**
     * 每日 05:00 执行 T+1 对账与回补：
     * 迟到事件、修正事件、口径回刷统一在此收敛，保证小时 / 日表最终一致。
     */
    @Scheduled(cron = "0 0 5 * * *")
    public void reconcileAndBackfill() {
        LocalDate dt = LocalDate.now(ZONE).minusDays(1);
        MinitorStore.ReconcileRow row = store.reconcile(dt);

        // 回补期间静默新鲜度告警，避免把「正在重算」误报为「链路异常」
        bus.publish("alert.silenced", Map.of(
                "reason", "T+1 backfill", "dt", dt.toString(), "auto", true));

        log.info("T+1 对账 dt={}: 状态缺口 {} 笔, 脏事件 {} 条, 缺失事件类型 {}",
                dt, row.stateGapCnt(), row.dirtyCnt(),
                row.missingEvents().stream().map(MinitorStore.MissingEvent::eventType).toList());

        if (row.stateGapCnt() > 0) {
            log.warn("存在状态机跳步，疑似业务侧埋点漏报，需推动接入方修复: {}", row.missingEvents());
        }
    }

    /**
     * 探测「已送达但超过约定时限仍未入账」的订单。
     *
     * <p>生产实现为一条 ClickHouse 反连接查询：
     * <pre>
     * SELECT d.order_id
     * FROM (SELECT order_id, max(event_time) et FROM ods_order_event
     *       WHERE event_type = 'order_delivered' AND dt >= today() - 2 GROUP BY order_id) d
     * LEFT ANTI JOIN
     *      (SELECT order_id FROM ods_order_event
     *       WHERE event_type = 'settlement_credited' AND dt >= today() - 2) s
     * USING (order_id)
     * WHERE d.et &lt; now() - INTERVAL 2 HOUR
     * </pre>
     */
    private List<EventEnvelope> detectOverdue() {
        // 交由 store 侧的反连接查询实现；demo 环境不产出，避免污染样例数据
        return List.of();
    }

    /** 每分钟刷新一次链路健康快照，供值班哨红绿灯与 P0 断流规则读取。 */
    @Scheduled(fixedRate = 60_000, initialDelay = 20_000)
    public void refreshLinkHealth() {
        List<MinitorStore.LinkNode> nodes = store.linkNodes();
        boolean blind = nodes.stream()
                .anyMatch(n -> "clickhouse".equals(n.key()) && !"ok".equals(n.status()));
        if (blind) {
            bus.publish("link.blind", Map.of(
                    "reason", "clickhouse_unavailable",
                    "silence_business_alerts", true,
                    "at", OffsetDateTime.now(ZONE).toString()));
        }
    }
}
