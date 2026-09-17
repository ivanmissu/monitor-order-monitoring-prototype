package com.monitor.server.store;

import com.monitor.server.domain.BizLine;
import com.monitor.server.domain.Dims;
import com.monitor.server.domain.Grain;
import com.monitor.server.domain.TimeRange;
import com.monitor.server.ingest.EventEnvelope;
import com.monitor.server.query.Metrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 无外部依赖的内存实现，数值与前端原型同源。
 *
 * <p>用途：本地启动 {@code --spring.profiles.active=demo} 即可对前端提供完整契约，
 * 无需 ClickHouse / Kafka / Redis，便于联调与接口验收。
 */
@Repository
@ConditionalOnProperty(name = "monitor.store.kind", havingValue = "demo")
public class DemoStore implements MonitorStore {

    private final Map<Long, AlertRow> alerts = new ConcurrentHashMap<>();
    private final Map<String, SilenceRow> silences = new ConcurrentHashMap<>();
    private final List<EventEnvelope> ingested = java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<DirtyRow> dirty = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong seq = new AtomicLong(4480);

    /** 原子指标基线：列名 → 当日全平台量级，按业务线/粒度做确定性缩放。 */
    private static final Map<String, Long> BASE = Map.ofEntries(
            Map.entry("order_created_cnt", 557_865L),
            Map.entry("order_delivered_cnt", 440_707L),
            Map.entry("order_confirmed_cnt", 61_582L),
            Map.entry("trip_published_cnt", 128_463L),
            Map.entry("grab_submitted_cnt", 101_826L),
            Map.entry("grab_won_cnt", 69_241L),
            Map.entry("dispatch_sent_cnt", 272_091L),
            Map.entry("dispatch_ack_cnt", 261_220L),
            Map.entry("dispatch_timeout_cnt", 33_739L),
            Map.entry("transfer_submit_cnt", 38_642L),
            Map.entry("transfer_accepted_cnt", 34_926L),
            Map.entry("designated_assigned_cnt", 65_478L),
            Map.entry("designated_timeout_cnt", 12_178L),
            Map.entry("booking_created_cnt", 31_886L),
            Map.entry("arrive_pickup_cnt", 439_921L),
            Map.entry("pickup_wait_over8_cnt", 56_310L),
            Map.entry("cancel_cnt", 19_683L),
            Map.entry("no_show_cnt", 3_098L),
            Map.entry("prepay_cnt", 553_100L),
            Map.entry("prepay_fail_cnt", 4_765L),
            Map.entry("gtv_sum", 6_640_000_00L),
            Map.entry("refund_amount_sum", 90_000_00L),
            Map.entry("settle_credited_cnt", 438_900L),
            Map.entry("settle_overdue_cnt", 1_852L),
            Map.entry("commission_sum", 1_328_000_00L),
            Map.entry("withdraw_req_cnt", 28_400L),
            Map.entry("withdraw_ok_cnt", 27_832L),
            Map.entry("risk_hit_cnt", 1_493L),
            Map.entry("risk_frozen_amount_sum", 18_600_00L),
            Map.entry("complaint_cnt", 1_675L),
            Map.entry("appeal_cnt", 892L),
            Map.entry("appeal_upheld_cnt", 164L),
            Map.entry("trip_with_order_cnt", 69_241L),
            Map.entry("event_cnt", 2_500_000L),
            Map.entry("state_gap_cnt", 23L));

    public DemoStore() {
        seedAlerts();
    }

    // ── 元信息 ────────────────────────────────────────────────────────────

    @Override
    public Instant freshness() {
        return Instant.now().minusSeconds(60);
    }

    @Override
    public boolean backfillRunning(LocalDate dt) {
        return false;
    }

    // ── 聚合查询：解析字典表达式并按基线折算 ───────────────────────────────

    @Override
    public long atomicSum(String sumExpr, Metrics.Query q) {
        double scale = bizScale(q.bizLine()) * daysScale(q.range());
        return Math.round(evalExpr(sumExpr) * scale);
    }

    /** 解析形如 {@code sum(a) + sum(b) - sum(c)} / {@code sumIf(cancel_cnt, ...)} 的表达式。 */
    private double evalExpr(String expr) {
        double total = 0;
        int sign = 1;
        for (String token : expr.split("(?=[+\\-])")) {
            String t = token.trim();
            if (t.startsWith("+")) {
                sign = 1;
                t = t.substring(1).trim();
            } else if (t.startsWith("-")) {
                sign = -1;
                t = t.substring(1).trim();
            }
            total += sign * columnValue(t);
        }
        return total;
    }

    private double columnValue(String fragment) {
        String col = fragment.replaceAll(".*\\(\\s*", "").replaceAll("[,)].*", "").trim();
        double base = BASE.getOrDefault(col, 1_000L);
        // sumIf(cancel_cnt, fault = 'at-fault') → 有责取消占比约 19.4%
        if (fragment.startsWith("sumIf") && fragment.contains("at-fault")) {
            return base * 0.194;
        }
        if (fragment.startsWith("quantile")) {
            return col.contains("ingest") ? 372 : 640;
        }
        return base;
    }

    private double bizScale(BizLine biz) {
        if (biz == null || biz.isAll()) {
            return 1.0;
        }
        return switch (biz) {
            case DRIVER -> 0.513;
            case TRANSFER -> 0.076;
            case CARPOOL -> 0.230;
            case DESIGNATED -> 0.124;
            case AIRPORT -> 0.057;
            default -> 1.0;
        };
    }

    private double daysScale(TimeRange range) {
        return switch (range.grain()) {
            case M1 -> 1d / 1440;
            case M5 -> 1d / 288;
            case H1 -> 1d / 24;
            case H2 -> 1d / 12;
            case D1 -> range.days();
        };
    }

    @Override
    public List<Metrics.Point> points(String sumExpr, Metrics.Query q) {
        // 24h 内的双峰形态：08:00 / 18:00 高峰，与原型曲线一致
        double[] shape = {0.86, 0.57, 0.48, 1.15, 4.07, 3.12, 2.62, 2.38, 3.25, 4.59, 3.72, 1.89};
        double daily = evalExpr(sumExpr) * bizScale(q.bizLine());
        List<Metrics.Point> out = new ArrayList<>();
        LocalDate day = q.range().to();
        for (int i = 0; i < shape.length; i++) {
            OffsetDateTime t = day.atTime(LocalTime.of(i * 2, 0)).atZone(TimeRange.ZONE).toOffsetDateTime();
            long sample = Math.round(daily * shape[i] / 30);
            out.add(new Metrics.Point(t, Math.round(daily * shape[i] / 30d), sample, null));
        }
        return out;
    }

    @Override
    public long sampleSize(Metrics.Query q) {
        return Math.round(BASE.get("event_cnt") * bizScale(q.bizLine()) * daysScale(q.range()));
    }

    // ── 链路健康 / 业务线 ─────────────────────────────────────────────────

    @Override
    public List<LinkNode> linkNodes() {
        OffsetDateTime now = OffsetDateTime.now(TimeRange.ZONE);
        return List.of(
                new LinkNode("mq", "业务事件 MQ", "ok", "lag 1.2s", "link.consumer_lag", null,
                        Map.of("lag_sec", 1.2), now),
                new LinkNode("consumer", "Consumer", "ok", "4 / 4 实例", "link.consumer_lag", null,
                        Map.of("instances", 4), now),
                new LinkNode("clickhouse", "ClickHouse", "ok", "2 / 2 副本", null, null,
                        Map.of("write_latency_ms", 42, "replica_lag_sec", 0.3), now),
                new LinkNode("freshness", "事件新鲜度", "bad", "P99 6m12s", "link.ingest_delay_p99",
                        4471L, Map.of("p99_sec", 372), now),
                new LinkNode("evaluator", "规则求值", "ok", "最近 " + now.toLocalTime().withNano(0),
                        null, null, Map.of("rules", 20), now));
    }

    @Override
    public Map<BizLine, BizAgg> bizAggregates(TimeRange range) {
        Map<BizLine, BizAgg> m = new LinkedHashMap<>();
        m.put(BizLine.DRIVER, new BizAgg(286_412, 261_883, 3_842_000_00L, 0.042, true));
        m.put(BizLine.TRANSFER, new BizAgg(42_180, 34_526, 586_000_00L, -0.021, false));
        m.put(BizLine.CARPOOL, new BizAgg(128_463, 53_136, 486_000_00L, 0.084, true));
        m.put(BizLine.DESIGNATED, new BizAgg(68_924, 62_748, 924_000_00L, 0.038, true));
        m.put(BizLine.AIRPORT, new BizAgg(31_886, 28_414, 712_000_00L, 0.016, true));
        return m;
    }

    // ── 大盘 ──────────────────────────────────────────────────────────────

    private static final Object[][] CITIES = {
            {330100L, "杭州", 38_642L, 0.8720, 412_000_00L, 0.038, 1, "focus"},
            {440100L, "广州", 34_906L, 0.8450, 386_000_00L, -0.012, 2, "focus"},
            {510100L, "成都", 31_188L, 0.8660, 341_000_00L, 0.051, 1, "focus"},
            {310000L, "上海", 28_951L, 0.8910, 372_000_00L, 0.022, 0, "focus"},
            {320100L, "南京", 22_832L, 0.8580, 246_000_00L, 0.017, 0, "normal"},
            {440300L, "深圳", 21_640L, 0.8840, 268_000_00L, 0.009, 0, "focus"}};

    @Override
    public List<CityRow> cityRank(TimeRange range, BizLine biz, String sort, int limit) {
        double scale = bizScale(biz);
        List<CityRow> rows = new ArrayList<>();
        for (Object[] c : CITIES) {
            rows.add(new CityRow((Long) c[0], (String) c[1],
                    Math.round((Long) c[2] * scale), (Double) c[3],
                    Math.round((Long) c[4] * scale), (Double) c[5], (Integer) c[6], (String) c[7]));
        }
        Comparator<CityRow> cmp = switch (sort == null ? "delivered_desc" : sort) {
            case "gtv_desc" -> Comparator.comparingLong(CityRow::gtvFen).reversed();
            case "delivery_rate_asc" -> Comparator.comparingDouble(CityRow::deliveryRate);
            case "alarm_desc" -> Comparator.comparingInt(CityRow::firingAlerts).reversed();
            default -> Comparator.comparingLong(CityRow::delivered).reversed();
        };
        return rows.stream().sorted(cmp).limit(limit).toList();
    }

    @Override
    public List<HeatCell> heatmap(TimeRange range, BizLine biz, String metric, int rows) {
        List<HeatCell> out = new ArrayList<>();
        for (int r = 0; r < Math.min(rows, CITIES.length); r++) {
            for (int col = 0; col < 12; col++) {
                int hour = col * 2;
                double v = 0.36 + ((r * 3 + col * 5) % 8) / 14d;
                out.add(new HeatCell((Long) CITIES[r][0], (String) CITIES[r][1], hour,
                        Math.round(v * 10000) / 10000d, 2000 + col * 137L,
                        hour >= 7 && hour <= 9 || hour >= 17 && hour <= 19));
            }
        }
        return out;
    }

    // ── 质量 ──────────────────────────────────────────────────────────────

    @Override
    public CancelMatrix cancelMatrix(TimeRange range, BizLine biz) {
        double s = bizScale(biz);
        List<CancelCell> cells = List.of(
                new CancelCell("driver", "lt1h", "at-fault", Math.round(1276 * s),
                        List.of(new ReasonCell("ROUTE_MISMATCH", "路线不合", Math.round(402 * s)),
                                new ReasonCell("VEHICLE_ISSUE", "车辆故障", Math.round(214 * s)))),
                new CancelCell("driver", "enroute", "at-fault", Math.round(2548 * s), List.of()),
                new CancelCell("driver", "pre1h", "no-fault", Math.round(5175 * s), List.of()),
                new CancelCell("passenger", "pre1h", "no-fault", Math.round(6398 * s), List.of()),
                new CancelCell("passenger", "lt1h", "at-fault", Math.round(3000 * s), List.of()),
                new CancelCell("system", "pre1h", "no-fault", Math.round(1286 * s), List.of()));
        long total = cells.stream().mapToLong(CancelCell::value).sum();
        return new CancelMatrix(total, Math.round(499_667 * s), cells);
    }

    @Override
    public List<RiskCityRow> riskCities(String window) {
        return List.of(
                new RiskCityRow(510100, "成都", 82, "high", "core.wait_over8_rate", "等待超率",
                        0.1730, "检查东站/天府机场夜间运力与调度半径"),
                new RiskCityRow(440100, "广州", 78, "high", "exp.appeal_overturn_rate", "申诉推翻率",
                        0.3280, "复核判责规则，抽样 50 单人工校验"),
                new RiskCityRow(420100, "武汉", 61, "mid", "exp.complaint_rate", "客诉率",
                        0.0053, "关注雨天时段派单半径"),
                new RiskCityRow(330100, "杭州", 55, "mid", "exp.review_bad_rate", "差评率",
                        0.0186, "司机服务分低分段培训"));
    }

    // ── 客服明细 ──────────────────────────────────────────────────────────

    private static final String DEMO_ORDER = "CP20260903018462";

    @Override
    public List<RecentOrderRow> recentOrders(int limit, BizLine biz) {
        if (!biz.isAll() && biz != BizLine.CARPOOL) {
            return List.of();
        }
        return List.of(new RecentOrderRow(DEMO_ORDER, "carpool", 330100L, "杭州",
                "completed", 8, LocalDate.of(2026, 9, 3).atTime(11, 18, 14)
                .atZone(TimeRange.ZONE).toOffsetDateTime()));
    }

    @Override
    public Optional<OrderSnapshot> order(String orderId) {
        if (!DEMO_ORDER.equalsIgnoreCase(orderId)) {
            return Optional.empty();
        }
        return Optional.of(new OrderSnapshot(DEMO_ORDER, "carpool", "completed", "已完成",
                330100L, "杭州", "exclusive", 8650L, "8a7f...21de", "T8842017763",
                LocalDate.of(2026, 9, 3), 8, "complete", 8, List.of()));
    }

    @Override
    public List<OrderEventRow> orderEvents(String orderId, TimeRange range) {
        if (!DEMO_ORDER.equalsIgnoreCase(orderId)) {
            return List.of();
        }
        LocalDate d = LocalDate.of(2026, 9, 3);
        Object[][] raw = {
                {"09:12:08", "prepay_succeeded", "乘客预付成功", "¥86.50 · 杭州市", 8650L},
                {"09:13:41", "order_pool_entered", "进入推荐池", "独享 · 预计 2 人", 0L},
                {"09:16:20", "order_grab_submitted", "司机提交抢单", "候选司机 8", 0L},
                {"09:16:22", "order_grab_won", "司机抢单成功", "耗时 2.1s", 0L},
                {"09:18:04", "passenger_confirmed", "乘客确认同行", "手动确认", 0L},
                {"10:02:19", "passenger_boarded", "乘客已上车", "等待 4m 21s", 0L},
                {"11:17:52", "order_delivered", "订单送达", "履约 75m 33s", 0L},
                {"11:18:14", "settlement_credited", "结算已入账", "司机收入 ¥72.40", 7240L}};
        List<OrderEventRow> out = new ArrayList<>();
        OffsetDateTime prev = null;
        for (int i = 0; i < raw.length; i++) {
            OffsetDateTime t = d.atTime(LocalTime.parse((String) raw[i][0]))
                    .atZone(TimeRange.ZONE).toOffsetDateTime();
            Long gap = prev == null ? null : java.time.Duration.between(prev, t).toSeconds();
            out.add(new OrderEventRow(i + 1, t, (String) raw[i][1], (String) raw[i][2],
                    (String) raw[i][3], (Long) raw[i][4], Map.of(), gap, false));
            prev = t;
        }
        return out;
    }

    // ── 风控 ──────────────────────────────────────────────────────────────

    @Override
    public List<RuleHitSeries> ruleHits(List<String> ruleIds, TimeRange range, BizLine biz) {
        long[] hits = {1342, 1286, 1518, 1442, 1380, 1628, 1493};
        long[] frozen = {282, 271, 336, 305, 292, 377, 328};
        List<RuleHitPoint> pts = new ArrayList<>();
        for (int i = 0; i < hits.length; i++) {
            pts.add(new RuleHitPoint(range.to().minusDays(hits.length - 1L - i).toString(),
                    hits[i], frozen[i] * 100_00L));
        }
        return List.of(new RuleHitSeries("R-1142", "高频短单刷单", pts));
    }

    @Override
    public List<RiskEntity> topEntities(String window, String sort, int limit) {
        OffsetDateTime now = OffsetDateTime.now(TimeRange.ZONE);
        return List.of(
                new RiskEntity("91ad...0fe2", "carpool", 18, 12, List.of("R-1142"), 92, "high",
                        124_000L, now.minusHours(17)),
                new RiskEntity("4bc2...821a", "driver", 15, 9, List.of("R-1142", "R-2201"), 87,
                        "high", 98_400L, now.minusHours(12)),
                new RiskEntity("72ef...d191", "carpool", 12, 7, List.of("R-2201"), 64, "mid",
                        76_200L, now.minusHours(9)),
                new RiskEntity("0a84...31dd", "designated", 11, 6, List.of("R-3310"), 58, "mid",
                        61_800L, now.minusHours(6)))
                .stream().limit(limit).toList();
    }

    // ── 接口监控 ──────────────────────────────────────────────────────────

    private static final Object[][] APIS = {
            {"pay.callback", "/api/v1/pay/callback", "pay-service", "支付回调", "all", 2140L, 0.0470, 640, 180},
            {"dispatch.respond", "/api/v1/dispatch/respond", "dispatch-service", "派单响应", "driver", 860L, 0.0231, 890, 210},
            {"transfer.submit", "/api/v1/transfer/submit", "transfer-service", "转单提交", "transfer", 340L, 0.0162, 420, 130},
            {"flight.sync", "/api/v1/flight/sync", "flight-service", "航班状态同步", "airport", 120L, 0.0090, 1800, 640},
            {"order.create", "/api/v1/order/create", "order-service", "创建订单", "driver", 1240L, 0.0034, 240, 88},
            {"withdraw.apply", "/api/v1/withdraw/apply", "withdraw-service", "提现申请", "carpool", 280L, 0.0012, 320, 96},
            {"settle.credit", "/api/v1/settle/credit", "settle-service", "结算入账", "carpool", 620L, 0.0008, 210, 74},
            {"risk.evaluate", "/api/v1/risk/evaluate", "risk-engine", "规则判定", "all", 3200L, 0.0001, 45, 12}};

    private static final Map<String, List<Double>> TRENDS = Map.of(
            "pay.callback", List.of(0.31, 0.29, 0.38, 0.52, 1.24, 3.16, 4.70),
            "dispatch.respond", List.of(0.42, 0.55, 0.61, 0.88, 1.42, 2.05, 2.31),
            "transfer.submit", List.of(0.22, 0.31, 0.28, 0.36, 0.74, 1.38, 1.62),
            "flight.sync", List.of(0.10, 0.14, 0.18, 0.31, 0.52, 0.74, 0.90),
            "order.create", List.of(0.08, 0.06, 0.09, 0.11, 0.14, 0.29, 0.34));

    @Override
    public List<ApiRow> apis(BizLine biz) {
        List<ApiRow> rows = new ArrayList<>();
        for (Object[] a : APIS) {
            String bizId = (String) a[4];
            if (biz != null && !biz.isAll() && !bizId.equals(biz.id()) && !"all".equals(bizId)) {
                continue;
            }
            rows.add(new ApiRow((String) a[0], (String) a[1], (String) a[2], (String) a[3], bizId,
                    (Long) a[5], (Double) a[6], 0.005, 0.02, (Integer) a[8], (Integer) a[7],
                    TRENDS.getOrDefault((String) a[0], List.of()),
                    "pay.callback".equals(a[0])
                            ? List.of(new ErrCode("PAY_CHANNEL_TIMEOUT", 0.62),
                            new ErrCode("WALLET_LOCK_BUSY", 0.18))
                            : List.of(),
                    "order-service", List.of(), "pay.callback".equals(a[0]) ? "R09" : null,
                    "pay.callback".equals(a[0]) ? 4479L : null));
        }
        return rows;
    }

    @Override
    public Topology topology(String window) {
        List<TopoNode> nodes = List.of(
                new TopoNode("client", "客户端 App", "5 条业务线", 20, 182, 110, 40, false, "edge"),
                new TopoNode("gw", "API 网关", "8,420 QPS", 200, 182, 110, 40, false, "gateway"),
                new TopoNode("order", "订单中心", "6,180 QPS", 380, 182, 110, 40, false, "core"),
                new TopoNode("pay", "支付中心", "2,140 QPS", 680, 34, 150, 40, true, "core"),
                new TopoNode("dispatch", "调度派单", "860 QPS", 680, 118, 150, 40, true, "core"),
                new TopoNode("settle", "结算中心", "620 QPS", 680, 202, 150, 40, false, "core"),
                new TopoNode("risk", "风控引擎", "3,200 QPS", 680, 286, 150, 40, false, "core"),
                new TopoNode("notify", "消息通知", "1,050 QPS", 680, 370, 150, 40, false, "core"));
        List<TopoEdge> edges = List.of(
                new TopoEdge("client", "gw", 8420, 0.0002, 42, "ok", null, "api.dependency_error_rate"),
                new TopoEdge("gw", "order", 6180, 0.0034, 88, "warn", null, "api.dependency_error_rate"),
                new TopoEdge("order", "pay", 2140, 0.0470, 640, "bad", 4479L, "api.dependency_error_rate"),
                new TopoEdge("order", "dispatch", 860, 0.0231, 890, "bad", 4472L, "api.dependency_error_rate"),
                new TopoEdge("order", "settle", 620, 0.0008, 210, "ok", null, "api.dependency_error_rate"),
                new TopoEdge("order", "risk", 3200, 0.0001, 45, "ok", null, "api.dependency_error_rate"),
                new TopoEdge("order", "notify", 1050, 0.0052, 130, "warn", null, "api.dependency_error_rate"));
        List<Suppression> sup = List.of(new Suppression("order→pay", List.of("R10", "R13"),
                "上游指标异常压制派生指标告警"));
        return new Topology(window, OffsetDateTime.now(TimeRange.ZONE), nodes, edges, 0.001, 0.02, sup);
    }

    @Override
    public List<SlowCall> slowCalls(int p99MinMs) {
        return List.of(
                new SlowCall("/api/v1/flight/sync", "航班同步", 1800, 1204, "airport", 120),
                new SlowCall("/api/v1/dispatch/respond", "派单响应", 890, 612, "driver", 860),
                new SlowCall("/api/v1/pay/callback", "支付回调", 640, 388, "all", 2140),
                new SlowCall("/api/v1/transfer/submit", "转单提交", 420, 266, "transfer", 340))
                .stream().filter(s -> s.p99Ms() >= p99MinMs).toList();
    }

    // ── 告警 ──────────────────────────────────────────────────────────────

    private void seedAlerts() {
        OffsetDateTime now = OffsetDateTime.now(TimeRange.ZONE);
        add(4471, "R02", Dims.AlertLevel.P0, "预付成功率持续低于阈值", BizLine.CARPOOL, 0, "全国",
                "全国 · 全部座型", now.minusMinutes(4), 4, 0.918, 0.95, "threshold", -3.2,
                9420, 10261, "fund.prepay_success_rate", "v3", Dims.AlertStatus.FIRING);
        add(4472, "R05", Dims.AlertLevel.P0, "派单响应超时激增", BizLine.DRIVER, 0, "全国",
                "全国 · 快车", now.minusMinutes(3), 3, 0.124, 0.05, "threshold", 7.4,
                10664, 86000, "driver.dispatch_timeout_rate", "v2", Dims.AlertStatus.FIRING);
        add(4473, "R10", Dims.AlertLevel.P1, "完单率低于 30 日基线", BizLine.CARPOOL, 330100, "杭州",
                "杭州市 · 独享", now.minusMinutes(12), 2, 0.724, 0.831, "baseline30d", -10.7,
                5021, 6935, "core.delivery_rate", "v2", Dims.AlertStatus.FIRING);
        add(4474, "R04", Dims.AlertLevel.P1, "转单成功率下降", BizLine.TRANSFER, 440100, "广州",
                "广州市", now.minusMinutes(9), 3, 0.682, 0.82, "baseline30d", -13.8,
                2380, 3490, "transfer.transfer_success_rate", "v1", Dims.AlertStatus.FIRING);
        add(4475, "R13", Dims.AlertLevel.P1, "结算逾期单量超阈值", BizLine.CARPOOL, 440100, "广州",
                "广州市 · 全部座型", now.minusMinutes(12), 2, 68, 50, "threshold", 36.0,
                68, 0, "fund.settle_overdue_cnt", "v1", Dims.AlertStatus.CLAIMED);
        add(4476, "R06", Dims.AlertLevel.P1, "代驾接单超时", BizLine.DESIGNATED, 510100, "成都",
                "成都市", now.minusMinutes(8), 2, 0.186, 0.10, "threshold", 8.6,
                2264, 12172, "designated.accept_timeout_rate", "v1", Dims.AlertStatus.FIRING);
        add(4477, "R19", Dims.AlertLevel.P2, "上车等待超率偏高", BizLine.CARPOOL, 510100, "成都",
                "成都市 · 2座", now.minusMinutes(19), 2, 0.173, 0.15, "threshold", 2.3,
                1210, 6994, "core.wait_over8_rate", "v1", Dims.AlertStatus.FIRING);
        add(4478, "R16", Dims.AlertLevel.P2, "航班延误取消量上升", BizLine.AIRPORT, 110000, "北京",
                "首都 T3", now.minusMinutes(25), 2, 47, 28, "yoy", 67.9,
                47, 0, "core.order_cancelled_cnt", "v2", Dims.AlertStatus.CLAIMED);
        add(4479, "R09", Dims.AlertLevel.P0, "支付回调接口失败率越界", BizLine.ALL, 0, "全国",
                "支付中心 · 全平台", now.minusMinutes(3), 3, 0.047, 0.02, "threshold", 2.7,
                6032, 128340, "api.fail_rate", "v1", Dims.AlertStatus.FIRING);
        add(4480, "R21", Dims.AlertLevel.P1, "航班状态同步接口异常率上升", BizLine.AIRPORT, 0, "全国",
                "接送机 · flight-service", now.minusMinutes(14), 2, 0.009, 0.005, "threshold", 0.4,
                65, 7200, "api.dependency_error_rate", "v1", Dims.AlertStatus.FIRING);
    }

    private void add(long id, String rule, Dims.AlertLevel level, String title, BizLine biz,
                     long cityId, String cityName, String scope, OffsetDateTime firedAt, int periods,
                     double value, double baseline, String baselineKind, double deltaPp,
                     long num, long den, String metric, String version, Dims.AlertStatus status) {
        alerts.put(id, new AlertRow(id, rule, level, title, biz, cityId, cityName, "all", scope,
                firedAt, periods, value, baseline, baselineKind, deltaPp, num, den, status,
                status == Dims.AlertStatus.CLAIMED ? "linzhou" : null,
                status == Dims.AlertStatus.CLAIMED ? firedAt.plusMinutes(3) : null,
                metric, version, "https://wiki.internal/rb/" + rule.toLowerCase(),
                level == Dims.AlertLevel.P0 ? 3 : 0, cityId == 0 ? 12 : 1, null, null, null, null,
                new ArrayList<>(List.of(new AlertTimelineRow(firedAt, "system", "fired",
                        level == Dims.AlertLevel.P0 ? "IM 强提醒 + 短信已送达" : "IM @值班")))));
    }

    @Override
    public List<AlertRow> alerts(AlertFilter f) {
        return alerts.values().stream()
                .filter(a -> f.status() == null || f.status().isEmpty() || f.status().contains(a.status()))
                .filter(a -> f.level() == null || f.level().isEmpty() || f.level().contains(a.level()))
                .filter(a -> f.biz() == null || f.biz().isAll() || a.bizLine() == f.biz()
                        || a.bizLine() == BizLine.ALL)
                .filter(a -> f.ruleId() == null || f.ruleId().equals(a.ruleId()))
                .filter(a -> f.cityId() == null || f.cityId() == 0 || f.cityId() == a.cityId())
                .filter(a -> f.since() == null || !a.firedAt().isBefore(f.since()))
                .sorted(Comparator.comparing((AlertRow a) -> a.level().ordinal())
                        .thenComparing(AlertRow::firedAt, Comparator.reverseOrder()))
                .toList();
    }

    @Override
    public Optional<AlertRow> alert(long alertId) {
        return Optional.ofNullable(alerts.get(alertId));
    }

    @Override
    public AlertRow save(AlertRow row) {
        alerts.put(row.alertId(), row);
        return row;
    }

    @Override
    public long nextAlertId() {
        return seq.incrementAndGet();
    }

    @Override
    public List<SilenceRow> silences() {
        return List.copyOf(silences.values());
    }

    @Override
    public SilenceRow saveSilence(SilenceRow s) {
        silences.put(s.silenceId(), s);
        return s;
    }

    @Override
    public void deleteSilence(String silenceId) {
        silences.remove(silenceId);
    }

    @Override
    public AlertStatsRow alertStats(String week) {
        return new AlertStatsRow(week, 318, 269, 0.846, 0.80, 0.091, 168, 940, 0.96,
                List.of(new NoisyRule("R19", 41, 12, 0.29, "建议阈值由 15% 调至 18% 或降为 P3"),
                        new NoisyRule("R14", 28, 11, 0.39, "小城市样本不足，建议提高分母门槛")),
                new LinkedHashMap<>(Map.of("P0", 12, "P1", 118, "P2", 168, "P3", 20)));
    }

    // ── 元监控 ────────────────────────────────────────────────────────────

    @Override
    public List<PipelineRow> pipeline() {
        OffsetDateTime now = OffsetDateTime.now(TimeRange.ZONE);
        return List.of(
                new PipelineRow("order-domain-topic (5 线)", "ok", "8,420 msg/s", now, "lag 2,210", "retention 7d"),
                new PipelineRow("monitor-consumer ×4", "ok", "8,406 msg/s", now, "本地缓冲 0", "无状态"),
                new PipelineRow("ClickHouse replica-01", "ok", "写入 42ms", now, "8C / 32G", "healthy"),
                new PipelineRow("ClickHouse replica-02", "ok", "复制延迟 0.3s", now, "8C / 32G", "healthy"),
                new PipelineRow("RuleEvaluator", "ok", "48 条 / min", now, "求值 328ms", "20 规则启用"),
                new PipelineRow("T+1 reconciler", "warn", "缺口 23 笔",
                        now.withHour(6).withMinute(18), "等待重算", "job rc-20260903-07"));
    }

    @Override
    public LagRow lag() {
        return new LagRow(0.4, 1.2, "carpool-3", 2, 372, 300, "bad", 412, 0.00018, 0, false);
    }

    @Override
    public ReconcileRow reconcile(LocalDate dt) {
        return new ReconcileRow(dt, 23,
                new LinkedHashMap<>(Map.of("carpool", 11, "driver", 8, "transfer", 4)),
                List.of(new MissingEvent("order_delivered", 9, "送达打卡未发事件（客户端离线）"),
                        new MissingEvent("settlement_credited", 7, "结算服务重试期间事件丢失"),
                        new MissingEvent("passenger_boarded", 7, "上车打卡事件未发")),
                List.of(new GapSample("CP20260902007741", "boarded", "completed", 812)),
                6, List.of("EVENT_TYPE_UNKNOWN:2", "REQUIRED_DIM_MISSING:3", "PII_DETECTED:1"));
    }

    @Override
    public List<EventQualityRow> eventQuality() {
        return List.of(
                new EventQualityRow("passenger_boarded", 61_308, 42, 3, 0.00005, 1.8, "ok"),
                new EventQualityRow("order_delivered", 440_707, 118, 9, 0.00002, 1.2, "ok"),
                new EventQualityRow("prepay_succeeded", 553_100, 96, 2, 0.000004, 0.9, "ok"),
                new EventQualityRow("risk_hit", 1_493, 0, 1, 0.00067, 2.4, "warn"));
    }

    // ── 写入 ──────────────────────────────────────────────────────────────

    @Override
    public int insertEvents(List<EventEnvelope> events) {
        ingested.addAll(events);
        return events.size();
    }

    @Override
    public void insertDirty(List<DirtyRow> rows) {
        dirty.addAll(rows);
    }

    @Override
    public List<DlqRow> dlq(String bizLine, String eventType, String reason,
                            LocalDate from, LocalDate to) {
        Map<String, List<DirtyRow>> grouped = new LinkedHashMap<>();
        for (DirtyRow r : List.copyOf(dirty)) {
            if (eventType != null && !eventType.equals(r.eventType())) {
                continue;
            }
            if (reason != null && !reason.equals(r.reason())) {
                continue;
            }
            grouped.computeIfAbsent(r.eventType() + "|" + r.reason(), k -> new ArrayList<>()).add(r);
        }
        List<DlqRow> out = new ArrayList<>();
        grouped.forEach((k, v) -> out.add(new DlqRow(LocalDate.now(TimeRange.ZONE),
                v.getFirst().eventType(), v.getFirst().reason(), v.size(),
                v.stream().map(DirtyRow::rawHead).limit(3).toList(), v.getFirst().producer())));
        return out;
    }

    /** 联调辅助：查看已落库事件数。 */
    public int ingestedCount() {
        return ingested.size();
    }

    /** 供 Grain 引用避免未使用告警。 */
    static Grain defaultGrain() {
        return Grain.H1;
    }
}
