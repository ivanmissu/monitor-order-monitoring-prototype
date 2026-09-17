package com.monitor.server.store;

import com.monitor.server.common.ApiException;
import com.monitor.server.common.ErrorCode;
import com.monitor.server.config.MonitorProperties;
import com.monitor.server.domain.BizLine;
import com.monitor.server.domain.Dims;
import com.monitor.server.domain.Grain;
import com.monitor.server.domain.TimeRange;
import com.monitor.server.ingest.EventEnvelope;
import com.monitor.server.query.Metrics;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * ClickHouse 生产实现。
 *
 * <p>两个 JdbcTemplate 对应两个账号与两套 profile：
 * <ul>
 *   <li>{@code aggJdbc}（grafana_dash）—— 只查 agg_*，限内存 2G / 10s；</li>
 *   <li>{@code odsJdbc}（cs_detail）—— 查 ods_order_event，限内存 6G / 60s，
 *       避免客服慢查询拖垮集群（设计文档 §9 风险项）。</li>
 * </ul>
 *
 * <p>安全说明：SQL 中的聚合表达式来自<b>指标字典</b>而非用户输入；
 * 所有用户可控值（时间、城市、业务线、订单号）一律走占位符绑定。
 */
@Repository
@ConditionalOnProperty(name = "monitor.store.kind", havingValue = "clickhouse", matchIfMissing = true)
public class ClickHouseStore implements MonitorStore {

    private static final Logger log = LoggerFactory.getLogger(ClickHouseStore.class);

    private final JdbcTemplate aggJdbc;
    private final JdbcTemplate odsJdbc;
    private final NamedParameterJdbcTemplate aggNamed;
    private final MonitorProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public ClickHouseStore(DataSource aggDataSource, DataSource odsDataSource, MonitorProperties props) {
        this.aggJdbc = new JdbcTemplate(aggDataSource);
        this.odsJdbc = new JdbcTemplate(odsDataSource);
        this.aggNamed = new NamedParameterJdbcTemplate(aggDataSource);
        this.props = props;
    }

    // ── 维度过滤构造 ──────────────────────────────────────────────────────

    private record Where(String sql, List<Object> args) {
    }

    /**
     * 绑定用时间戳统一截断到整秒：agg/ods 表的时间列均为秒级 DateTime。
     * clickhouse-jdbc V2 对带纳秒的 Timestamp（经 setObject 内联）会渲染成
     * 'yyyy-MM-dd HH:mm:ss.SSSSSSSSS' 字符串字面量，而 ClickHouse 26.7 的
     * 字符串→DateTime 比较拒绝任何小数秒（Code 53），故必须在应用侧截断。
     */
    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant.truncatedTo(ChronoUnit.SECONDS));
    }

    /** 时间列表达式：2h 由 1h 上卷。 */
    private static String timeSelect(Grain g) {
        return switch (g) {
            case M1, M5 -> "minute";
            case H1 -> "hour";
            case H2 -> "toStartOfInterval(hour, INTERVAL 2 HOUR)";
            case D1 -> "dt";
        };
    }

    private static String timeColumn(Grain g) {
        return g == Grain.D1 ? "dt" : (g.minuteLevel() ? "minute" : "hour");
    }

    private Where where(Metrics.Query q) {
        Grain g = q.grain();
        String col = timeColumn(g);
        StringBuilder sb = new StringBuilder();
        List<Object> args = new ArrayList<>();

        if (g == Grain.D1) {
            sb.append(" WHERE ").append(col).append(" >= toDate(?) AND ").append(col).append(" <= toDate(?)");
            args.add(q.range().from().toString());
            args.add(q.range().to().toString());
        } else {
            sb.append(" WHERE ").append(col).append(" >= ? AND ").append(col).append(" < ?");
            args.add(ts(q.range().from().atStartOfDay(TimeRange.ZONE).toInstant()));
            args.add(ts(q.range().to().plusDays(1).atStartOfDay(TimeRange.ZONE).toInstant()));
        }

        if (q.bizLine() != null && !q.bizLine().isAll()) {
            sb.append(" AND biz_line = ?");
            args.add(q.bizLine().id());
        }
        if (q.cityIds() != null && !q.cityIds().isEmpty()) {
            sb.append(" AND city_id IN (")
                    .append("?,".repeat(q.cityIds().size() - 1)).append("?)");
            args.addAll(q.cityIds());
        }
        if (q.seatType() != null && !q.seatType().isBlank() && !"all".equals(q.seatType())) {
            sb.append(" AND seat_type = ?");
            args.add(q.seatType());
        }
        return new Where(sb.toString(), args);
    }

    // ── 元信息 ────────────────────────────────────────────────────────────

    @Override
    public Instant freshness() {
        Timestamp ts = aggJdbc.queryForObject(
                "SELECT max(updated_at) FROM agg_1m WHERE minute >= now() - INTERVAL 2 HOUR",
                Timestamp.class);
        return ts == null ? Instant.now() : ts.toInstant();
    }

    @Override
    public boolean backfillRunning(LocalDate dt) {
        Integer n = aggJdbc.queryForObject("""
                SELECT count() FROM system_backfill_job
                WHERE target_dt = toDate(?) AND status = 'running'
                """, Integer.class, dt.toString());
        return n != null && n > 0;
    }

    // ── 聚合查询 ──────────────────────────────────────────────────────────

    @Override
    public long atomicSum(String sumExpr, Metrics.Query q) {
        Where w = where(q);
        String sql = "SELECT toInt64(ifNull(" + sumExpr + ", 0)) FROM " + q.grain().table() + w.sql();
        Long v = aggJdbc.queryForObject(sql, Long.class, w.args().toArray());
        return v == null ? 0L : v;
    }

    @Override
    public List<Metrics.Point> points(String sumExpr, Metrics.Query q) {
        Where w = where(q);
        String tsel = timeSelect(q.grain());
        String sql = "SELECT " + tsel + " AS t, toFloat64(ifNull(" + sumExpr + ", 0)) AS v, "
                + "toInt64(ifNull(sum(event_cnt), 0)) AS sample FROM " + q.grain().table()
                + w.sql() + " GROUP BY t ORDER BY t";
        int floor = props.getQuery().getSmallSampleFloor();
        return aggJdbc.query(sql, (rs, i) -> {
            OffsetDateTime t = rs.getTimestamp("t").toInstant().atZone(TimeRange.ZONE).toOffsetDateTime();
            long sample = rs.getLong("sample");
            return new Metrics.Point(t, rs.getDouble("v"), sample, sample < floor ? Boolean.TRUE : null);
        }, w.args().toArray());
    }

    @Override
    public long sampleSize(Metrics.Query q) {
        Where w = where(q);
        Long v = aggJdbc.queryForObject(
                "SELECT toInt64(ifNull(sum(event_cnt),0)) FROM " + q.grain().table() + w.sql(),
                Long.class, w.args().toArray());
        return v == null ? 0L : v;
    }

    // ── 链路健康 ──────────────────────────────────────────────────────────

    @Override
    public List<LinkNode> linkNodes() {
        return aggJdbc.query("""
                SELECT node_key, node_label, status, detail, metric_id, alert_id, heartbeat_at
                FROM system_link_health FINAL
                ORDER BY sort_order
                """, (rs, i) -> new LinkNode(
                rs.getString("node_key"),
                rs.getString("node_label"),
                rs.getString("status"),
                rs.getString("detail"),
                rs.getString("metric_id"),
                rs.getObject("alert_id") == null ? null : rs.getLong("alert_id"),
                Map.of(),
                rs.getTimestamp("heartbeat_at").toInstant().atZone(TimeRange.ZONE).toOffsetDateTime()));
    }

    @Override
    public Map<BizLine, BizAgg> bizAggregates(TimeRange range) {
        String sql = """
                SELECT biz_line,
                       toInt64(sum(order_created_cnt))    AS orders,
                       toInt64(sum(order_delivered_cnt))  AS delivered,
                       toInt64(sum(gtv_sum) - sum(refund_amount_sum)) AS gtv_fen
                FROM agg_1d
                WHERE dt >= toDate(:from) AND dt <= toDate(:to)
                GROUP BY biz_line
                """;
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("from", range.from().toString())
                .addValue("to", range.to().toString());
        Map<BizLine, BizAgg> out = new LinkedHashMap<>();
        aggNamed.query(sql, p, rs -> {
            BizLine b = BizLine.of(rs.getString("biz_line"));
            long orders = rs.getLong("orders");
            long delivered = rs.getLong("delivered");
            double rate = orders == 0 ? 0 : (double) delivered / orders;
            out.put(b, new BizAgg(orders, delivered, rs.getLong("gtv_fen"), 0d, rate >= 0.85));
        });
        return out;
    }

    // ── 经营大盘 ──────────────────────────────────────────────────────────

    @Override
    public List<CityRow> cityRank(TimeRange range, BizLine biz, String sort, int limit) {
        String order = switch (sort == null ? "delivered_desc" : sort) {
            case "gtv_desc" -> "gtv_fen DESC";
            case "delivery_rate_asc" -> "delivery_rate ASC";
            case "alarm_desc" -> "firing_alerts DESC";
            default -> "delivered DESC";
        };
        String bizFilter = biz != null && !biz.isAll() ? " AND a.biz_line = ? " : " ";
        List<Object> args = new ArrayList<>(List.of(range.from().toString(), range.to().toString()));
        if (biz != null && !biz.isAll()) {
            args.add(biz.id());
        }
        args.add(limit);
        String sql = """
                SELECT a.city_id                                  AS city_id,
                       dictGet('dim_city', 'name', a.city_id)     AS name,
                       dictGet('dim_city', 'priority', a.city_id) AS priority,
                       toInt64(sum(a.order_delivered_cnt))        AS delivered,
                       sum(a.order_delivered_cnt) / nullIf(sum(a.order_created_cnt) + sum(a.order_confirmed_cnt), 0) AS delivery_rate,
                       toInt64(sum(a.gtv_sum) - sum(a.refund_amount_sum)) AS gtv_fen,
                       0.0                                        AS yoy,
                       0                                          AS firing_alerts
                FROM agg_1d a
                WHERE a.dt >= toDate(?) AND a.dt <= toDate(?)
                """ + bizFilter + """
                GROUP BY a.city_id
                ORDER BY """ + " " + order + " LIMIT ?";
        return aggJdbc.query(sql, (rs, i) -> new CityRow(
                rs.getLong("city_id"), rs.getString("name"), rs.getLong("delivered"),
                rs.getDouble("delivery_rate"), rs.getLong("gtv_fen"), rs.getDouble("yoy"),
                rs.getInt("firing_alerts"), rs.getString("priority")), args.toArray());
    }

    @Override
    public List<HeatCell> heatmap(TimeRange range, BizLine biz, String metric, int rows) {
        String expr = switch (metric == null ? "accept_rate" : metric) {
            case "order_created_cnt" -> "toFloat64(sum(order_created_cnt))";
            case "delivery_rate" -> "sum(order_delivered_cnt) / nullIf(sum(order_created_cnt), 0)";
            default -> "sum(trip_with_order_cnt) / nullIf(sum(trip_published_cnt), 0)";
        };
        List<Object> args = new ArrayList<>(List.of(range.from().toString(), range.to().toString()));
        String bizFilter = "";
        if (biz != null && !biz.isAll()) {
            bizFilter = " AND biz_line = ? ";
            args.add(biz.id());
        }
        args.add(rows);
        String sql = """
                SELECT city_id,
                       dictGet('dim_city', 'name', city_id) AS name,
                       toHour(hour)                         AS h,
                       """ + expr + """
                        AS v,
                       toInt64(sum(event_cnt))              AS sample
                FROM agg_1h
                WHERE toDate(hour) >= toDate(?) AND toDate(hour) <= toDate(?)
                """ + bizFilter + """
                GROUP BY city_id, name, h
                ORDER BY city_id, h
                LIMIT ? BY city_id
                """;
        Set<Integer> peaks = Set.of(7, 8, 9, 17, 18, 19);
        return aggJdbc.query(sql, (rs, i) -> {
            int h = rs.getInt("h");
            return new HeatCell(rs.getLong("city_id"), rs.getString("name"), h,
                    rs.getDouble("v"), rs.getLong("sample"), peaks.contains(h));
        }, args.toArray());
    }

    // ── 履约质量 ──────────────────────────────────────────────────────────

    @Override
    public CancelMatrix cancelMatrix(TimeRange range, BizLine biz) {
        List<Object> args = new ArrayList<>(List.of(range.from().toString(), range.to().toString()));
        String bizFilter = "";
        if (biz != null && !biz.isAll()) {
            bizFilter = " AND biz_line = ? ";
            args.add(biz.id());
        }
        String sql = """
                SELECT cancel_by, cancel_stage, fault, toInt64(sum(cancel_cnt)) AS v
                FROM agg_1d
                WHERE dt >= toDate(?) AND dt <= toDate(?) AND cancel_by != '-'
                """ + bizFilter + """
                GROUP BY cancel_by, cancel_stage, fault
                ORDER BY v DESC
                """;
        List<CancelCell> cells = aggJdbc.query(sql, (rs, i) -> new CancelCell(
                rs.getString("cancel_by"), rs.getString("cancel_stage"),
                rs.getString("fault"), rs.getLong("v"), List.of()), args.toArray());
        long total = cells.stream().mapToLong(CancelCell::value).sum();
        Metrics.Query q = Metrics.Query.of(biz, range.withGrain(Grain.D1));
        long denominator = atomicSum("sum(order_confirmed_cnt) + sum(order_created_cnt)", q);
        return new CancelMatrix(total, denominator, cells);
    }

    @Override
    public List<RiskCityRow> riskCities(String window) {
        int days = "30d".equals(window) ? 30 : "14d".equals(window) ? 14 : 7;
        return aggJdbc.query("""
                SELECT city_id,
                       dictGet('dim_city','name',city_id) AS name,
                       toInt32(round(100 * greatest(
                            sum(pickup_wait_over8_cnt) / nullIf(sum(arrive_pickup_cnt),0),
                            sum(complaint_cnt) / nullIf(sum(order_delivered_cnt),0) * 20
                       ))) AS score,
                       sum(pickup_wait_over8_cnt) / nullIf(sum(arrive_pickup_cnt),0) AS wait_rate
                FROM agg_1d
                WHERE dt >= today() - ?
                GROUP BY city_id
                ORDER BY score DESC
                LIMIT 8
                """, (rs, i) -> {
            int score = rs.getInt("score");
            return new RiskCityRow(rs.getLong("city_id"), rs.getString("name"), score,
                    score >= 70 ? "high" : "mid", "core.wait_over8_rate", "等待超率",
                    rs.getDouble("wait_rate"), null);
        }, days);
    }

    // ── 客服明细 ──────────────────────────────────────────────────────────

    @Override
    public Optional<OrderSnapshot> order(String orderId) {
        List<OrderSnapshot> rows = odsJdbc.query("""
                SELECT order_id, biz_line, city_id,
                       dictGet('dim_city', 'name', toUInt64(city_id)) AS city_name,
                       seat_type, amount, driver_hash, trip_id, order_dt, event_cnt, delivered
                FROM (
                    SELECT order_id, any(biz_line) AS biz_line, any(city_id) AS city_id,
                           any(seat_type) AS seat_type, max(amount) AS amount,
                           any(driver_id_hash) AS driver_hash, any(trip_id) AS trip_id,
                           min(dt) AS order_dt, count() AS event_cnt,
                           countIf(event_type = 'order_delivered') AS delivered
                    FROM ods_order_event
                    WHERE order_id = ? AND ods_order_event.dt >= today() - 90
                    GROUP BY order_id
                )
                """, (rs, i) -> {
            int cnt = rs.getInt("event_cnt");
            boolean delivered = rs.getInt("delivered") > 0;
            return new OrderSnapshot(rs.getString("order_id"), rs.getString("biz_line"),
                    delivered ? "completed" : "in_progress", delivered ? "已完成" : "进行中",
                    rs.getLong("city_id"), rs.getString("city_name"), rs.getString("seat_type"), rs.getLong("amount"),
                    mask(rs.getString("driver_hash")), rs.getString("trip_id"),
                    rs.getDate("order_dt").toLocalDate(), cnt,
                    cnt >= 8 ? "complete" : "gap", 8, List.of());
        }, orderId);
        return rows.stream().findFirst();
    }

    @Override
    public List<OrderEventRow> orderEvents(String orderId, TimeRange range) {
        // 走 bloom_filter(order_id) 索引 + 分区裁剪；强制时间窗由 Service 层校验
        List<OrderEventRow> raw = odsJdbc.query("""
                SELECT event_time, event_type, amount, props
                FROM ods_order_event FINAL
                WHERE order_id = ? AND dt >= toDate(?) AND dt <= toDate(?)
                ORDER BY event_time, event_id
                """, (rs, i) -> new OrderEventRow(0,
                rs.getTimestamp("event_time").toInstant().atZone(TimeRange.ZONE).toOffsetDateTime(),
                rs.getString("event_type"), null, null,
                rs.getLong("amount"), parseProps(rs.getString("props")), null, false),
                orderId, range.from().toString(), range.to().toString());

        List<OrderEventRow> out = new ArrayList<>(raw.size());
        OffsetDateTime prev = null;
        int seq = 1;
        for (OrderEventRow r : raw) {
            Long gap = prev == null ? null : java.time.Duration.between(prev, r.eventTime()).toSeconds();
            out.add(new OrderEventRow(seq++, r.eventTime(), r.eventType(), null, null,
                    r.amountFen(), r.props(), gap, gap != null && gap > 3600));
            prev = r.eventTime();
        }
        return out;
    }

    // ── 风控 ──────────────────────────────────────────────────────────────

    @Override
    public List<RuleHitSeries> ruleHits(List<String> ruleIds, TimeRange range, BizLine biz) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("from", range.from().toString())
                .addValue("to", range.to().toString())
                .addValue("rules", ruleIds == null || ruleIds.isEmpty() ? List.of("") : ruleIds);
        String filter = (ruleIds == null || ruleIds.isEmpty()) ? "" : " AND rule_id IN (:rules) ";
        String sql = """
                SELECT rule_id, toString(dt) AS t,
                       toInt64(sum(risk_hit_cnt)) AS hit,
                       toInt64(sum(risk_frozen_amount_sum)) AS frozen
                FROM agg_1d
                WHERE dt >= toDate(:from) AND dt <= toDate(:to) AND rule_id != '-'
                """ + filter + """
                GROUP BY rule_id, dt ORDER BY rule_id, dt
                """;
        Map<String, List<RuleHitPoint>> grouped = new LinkedHashMap<>();
        // 块状 lambda：明确匹配 RowCallbackHandler，避免与 ResultSetExtractor 的重载歧义
        aggNamed.query(sql, p, rs -> {
            grouped.computeIfAbsent(rs.getString("rule_id"), k -> new ArrayList<>())
                    .add(new RuleHitPoint(rs.getString("t"), rs.getLong("hit"),
                            rs.getLong("frozen")));
        });
        return grouped.entrySet().stream()
                .map(e -> new RuleHitSeries(e.getKey(), e.getKey(), e.getValue()))
                .toList();
    }

    @Override
    public List<RiskEntity> topEntities(String window, String sort, int limit) {
        int hours = "7d".equals(window) ? 168 : 24;
        String order = switch (sort == null ? "risk_score_desc" : sort) {
            case "short_order_desc" -> "short_orders DESC";
            case "hit_desc" -> "hits DESC";
            default -> "risk_score DESC";
        };
        // ODS 重查询：受限 profile，服务层做 5min 缓存并禁止前端轮询
        return odsJdbc.query("""
                SELECT driver_id_hash,
                       any(biz_line) AS biz_line,
                       countIf(event_type='order_delivered' AND toInt32(JSONExtractInt(props,'trip_duration_sec')) < 300) AS short_orders,
                       countIf(event_type='risk_hit') AS hits,
                       groupUniqArray(JSONExtractString(props,'rule_id')) AS rules,
                       toInt64(sum(amount)) AS gtv,
                       min(event_time) AS first_seen,
                       toInt32(least(100, countIf(event_type='risk_hit') * 6
                               + countIf(event_type='order_delivered') * 2)) AS risk_score
                FROM ods_order_event
                WHERE event_time >= now() - INTERVAL ? HOUR AND driver_id_hash != ''
                GROUP BY driver_id_hash
                HAVING hits > 0
                ORDER BY """ + " " + order + " LIMIT ?",
                (rs, i) -> {
                    int score = rs.getInt("risk_score");
                    java.sql.Array arr = rs.getArray("rules");
                    List<String> rules = arr == null ? List.of()
                            : List.of((String[]) arr.getArray());
                    return new RiskEntity(mask(rs.getString("driver_id_hash")), rs.getString("biz_line"),
                            rs.getInt("short_orders"), rs.getInt("hits"), rules, score,
                            score >= 80 ? "high" : "mid", rs.getLong("gtv"),
                            rs.getTimestamp("first_seen").toInstant().atZone(TimeRange.ZONE).toOffsetDateTime());
                }, hours, limit);
    }

    // ── 接口监控 ──────────────────────────────────────────────────────────

    @Override
    public List<ApiRow> apis(BizLine biz) {
        List<Object> args = new ArrayList<>();
        String filter = "";
        if (biz != null && !biz.isAll()) {
            filter = " AND (biz_line = ? OR biz_line = 'all') ";
            args.add(biz.id());
        }
        return aggJdbc.query("""
                SELECT api_id, path, service, service_label, biz_line,
                       toInt64(sum(request_cnt) / 60)                       AS qps,
                       sum(node_fail_cnt) / nullIf(sum(request_cnt), 0)     AS fail_rate,
                       toInt32(quantileTDigestMerge(0.50)(latency_p))       AS p50,
                       toInt32(quantileTDigestMerge(0.99)(latency_p))       AS p99,
                       warn_threshold, bad_threshold, upstream
                FROM agg_1m
                WHERE minute >= now() - INTERVAL 5 MINUTE AND api_id != '-'
                """ + filter + """
                GROUP BY api_id, path, service, service_label, biz_line,
                         warn_threshold, bad_threshold, upstream
                ORDER BY fail_rate DESC
                """, (rs, i) -> new ApiRow(
                rs.getString("api_id"), rs.getString("path"), rs.getString("service"),
                rs.getString("service_label"), rs.getString("biz_line"), rs.getLong("qps"),
                rs.getDouble("fail_rate"), rs.getDouble("warn_threshold"), rs.getDouble("bad_threshold"),
                rs.getInt("p50"), rs.getInt("p99"), List.of(), List.of(),
                rs.getString("upstream"), List.of(), null, null), args.toArray());
    }

    @Override
    public Topology topology(String window) {
        int minutes = "1h".equals(window) ? 60 : 5;
        List<TopoEdge> edges = aggJdbc.query("""
                SELECT caller AS f, callee AS t,
                       toInt64(sum(dep_call_cnt) / (? * 60))            AS qps,
                       sum(dep_fail_cnt) / nullIf(sum(dep_call_cnt), 0) AS err,
                       toInt32(quantileTDigestMerge(0.99)(latency_p))   AS p99
                FROM agg_1m
                WHERE minute >= now() - INTERVAL ? MINUTE AND caller != '-'
                GROUP BY caller, callee
                """, (rs, i) -> {
            double err = rs.getDouble("err");
            String level = err > 0.02 ? "bad" : err > 0.001 ? "warn" : "ok";
            return new TopoEdge(rs.getString("f"), rs.getString("t"), rs.getLong("qps"),
                    err, rs.getInt("p99"), level, null, "api.dependency_error_rate");
        }, minutes, minutes);
        List<TopoNode> nodes = aggJdbc.query("""
                SELECT node_id, label, sub, x, y, w, h, kind FROM system_topology_layout ORDER BY sort_order
                """, (rs, i) -> new TopoNode(rs.getString("node_id"), rs.getString("label"),
                rs.getString("sub"), rs.getInt("x"), rs.getInt("y"), rs.getInt("w"), rs.getInt("h"),
                false, rs.getString("kind")));
        return new Topology(window, OffsetDateTime.now(TimeRange.ZONE), nodes, edges,
                0.001, 0.02, List.of());
    }

    @Override
    public List<SlowCall> slowCalls(int p99MinMs) {
        return aggJdbc.query("""
                SELECT path, service_label, biz_line,
                       toInt32(quantileTDigestMerge(0.99)(latency_p)) AS p99,
                       toInt32(quantileTDigestMerge(0.95)(latency_p)) AS p95,
                       toInt64(sum(request_cnt) / 300)                AS qps
                FROM agg_1m
                WHERE minute >= now() - INTERVAL 5 MINUTE AND api_id != '-'
                GROUP BY path, service_label, biz_line
                HAVING p99 >= ?
                ORDER BY p99 DESC LIMIT 10
                """, (rs, i) -> new SlowCall(rs.getString("path"), rs.getString("service_label"),
                rs.getInt("p99"), rs.getInt("p95"), rs.getString("biz_line"), rs.getLong("qps")),
                p99MinMs);
    }

    // ── 告警（事件表在 MySQL/CH 均可，此处走 agg 账号的告警库） ─────────────

    @Override
    public List<AlertRow> alerts(AlertFilter f) {
        StringBuilder sb = new StringBuilder("SELECT * FROM alert_event WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (f.status() != null && !f.status().isEmpty()) {
            sb.append(" AND status IN (").append("?,".repeat(f.status().size() - 1)).append("?)");
            f.status().forEach(s -> args.add(s.id()));
        }
        if (f.biz() != null && !f.biz().isAll()) {
            sb.append(" AND biz_line = ?");
            args.add(f.biz().id());
        }
        if (f.ruleId() != null) {
            sb.append(" AND rule_id = ?");
            args.add(f.ruleId());
        }
        if (f.since() != null) {
            sb.append(" AND fired_at >= ?");
            args.add(ts(f.since().toInstant()));
        }
        sb.append(" ORDER BY level ASC, fired_at DESC LIMIT 500");
        return aggJdbc.query(sb.toString(), (rs, i) -> mapAlert(rs), args.toArray());
    }

    private AlertRow mapAlert(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AlertRow(rs.getLong("alert_id"), rs.getString("rule_id"),
                Dims.AlertLevel.valueOf(rs.getString("level")), rs.getString("title"),
                BizLine.of(rs.getString("biz_line")), rs.getLong("city_id"), rs.getString("city_name"),
                rs.getString("seat_type"), rs.getString("scope_text"),
                rs.getTimestamp("fired_at").toInstant().atZone(TimeRange.ZONE).toOffsetDateTime(),
                rs.getInt("periods"), rs.getDouble("value"), rs.getDouble("baseline"),
                rs.getString("baseline_kind"), rs.getDouble("delta_pp"),
                rs.getLong("numerator"), rs.getLong("denominator"),
                Dims.AlertStatus.valueOf(rs.getString("status").toUpperCase()),
                rs.getString("ack_by"), null, rs.getString("metric_id"),
                rs.getString("metric_version"), rs.getString("runbook"),
                rs.getInt("deduped"), rs.getInt("aggregated_cities"), rs.getString("suppressed_by"),
                null, null, null, List.of());
    }

    @Override
    public Optional<AlertRow> alert(long alertId) {
        return aggJdbc.query("SELECT * FROM alert_event WHERE alert_id = ? LIMIT 1",
                (rs, i) -> mapAlert(rs), alertId).stream().findFirst();
    }

    @Override
    public AlertRow save(AlertRow row) {
        aggJdbc.update("""
                INSERT INTO alert_event (alert_id, rule_id, level, title, biz_line, city_id, status,
                                         ack_by, judgement, root_cause, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?, now())
                """, row.alertId(), row.ruleId(), row.level().name(), row.title(),
                row.bizLine().id(), row.cityId(), row.status().id(), row.ackBy(),
                row.judgement() == null ? null : row.judgement().name(), row.rootCause());
        return row;
    }

    @Override
    public long nextAlertId() {
        Long v = aggJdbc.queryForObject("SELECT toInt64(max(alert_id) + 1) FROM alert_event", Long.class);
        return v == null ? 1L : v;
    }

    @Override
    public List<SilenceRow> silences() {
        return aggJdbc.query("""
                SELECT silence_id, matchers, from_at, to_at, reason, owner, suppressed_count, auto
                FROM alert_silence WHERE to_at >= now() ORDER BY from_at DESC
                """, (rs, i) -> new SilenceRow(rs.getString("silence_id"),
                parseMatchers(rs.getString("matchers")),
                rs.getTimestamp("from_at").toInstant().atZone(TimeRange.ZONE).toOffsetDateTime(),
                rs.getTimestamp("to_at").toInstant().atZone(TimeRange.ZONE).toOffsetDateTime(),
                rs.getString("reason"), rs.getString("owner"),
                rs.getInt("suppressed_count"), rs.getBoolean("auto")));
    }

    @Override
    public SilenceRow saveSilence(SilenceRow s) {
        aggJdbc.update("""
                INSERT INTO alert_silence (silence_id, matchers, from_at, to_at, reason, owner, auto)
                VALUES (?,?,?,?,?,?,?)
                """, s.silenceId(), writeJson(s.matchers()), ts(s.from().toInstant()),
                ts(s.to().toInstant()), s.reason(), s.owner(), s.auto());
        return s;
    }

    @Override
    public void deleteSilence(String silenceId) {
        aggJdbc.update("ALTER TABLE alert_silence DELETE WHERE silence_id = ?", silenceId);
    }

    @Override
    public AlertStatsRow alertStats(String week) {
        // 周报参数为 ISO 周格式（2026-W38）；ClickHouse 无原生 ISO 周解析，
        // 在 Java 侧拆解为 (年, 周) 两个可绑定整数，避免字符串日期解析失败
        int year;
        int isoWeek;
        try {
            String[] parts = (week == null
                    ? java.time.LocalDate.now(TimeRange.ZONE).getYear() + "-W"
                            + String.format("%02d", java.time.LocalDate.now(TimeRange.ZONE)
                            .get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()))
                    : week).split("-W");
            year = Integer.parseInt(parts[0].trim());
            isoWeek = Integer.parseInt(parts[1].trim());
        } catch (RuntimeException ex) {
            throw ApiException.invalidParam("week 需为 ISO 周格式，如 2026-W38");
        }
        return aggJdbc.query("""
                SELECT toInt32(count())                                        AS fired,
                       toInt32(countIf(judgement = 'VALID'))                   AS valid,
                       toInt32(quantile(0.5)(mtta_sec))                        AS mtta,
                       toInt32(quantile(0.5)(mttr_sec))                        AS mttr,
                       avgIf(mtta_sec <= 300, level = 'P0')                    AS p0_ack
                FROM alert_event
                WHERE toYear(fired_at) = ? AND toISOWeek(fired_at) = ?
                """, (rs, i) -> {
            int fired = rs.getInt("fired");
            int valid = rs.getInt("valid");
            double precision = fired == 0 ? 0 : (double) valid / fired;
            return new AlertStatsRow(week, fired, valid, precision, 0.80,
                    fired == 0 ? 0 : 1 - precision, rs.getInt("mtta"), rs.getInt("mttr"),
                    rs.getDouble("p0_ack"), List.of(), Map.of());
        }, year, isoWeek).stream().findFirst().orElseThrow(() -> ApiException.notFound("周报 " + week));
    }

    // ── 元监控 ────────────────────────────────────────────────────────────

    @Override
    public List<PipelineRow> pipeline() {
        return aggJdbc.query("""
                SELECT component, status, throughput, heartbeat_at, detail, extra
                FROM system_pipeline_health FINAL ORDER BY sort_order
                """, (rs, i) -> new PipelineRow(rs.getString("component"), rs.getString("status"),
                rs.getString("throughput"),
                rs.getTimestamp("heartbeat_at").toInstant().atZone(TimeRange.ZONE).toOffsetDateTime(),
                rs.getString("detail"), rs.getString("extra")));
    }

    @Override
    public LagRow lag() {
        return aggJdbc.query("""
                SELECT quantile(0.5)(consumer_lag_sec)                        AS lag_p50,
                       quantile(0.99)(consumer_lag_sec)                       AS lag_p99,
                       argMax(partition, consumer_lag_sec)                    AS max_partition,
                       toInt64(quantileTDigestMerge(0.50)(ingest_delay_p))    AS ing_p50,
                       toInt64(quantileTDigestMerge(0.99)(ingest_delay_p))    AS ing_p99,
                       toInt64(sum(late_event_cnt))                           AS late_cnt,
                       sum(late_event_cnt) / nullIf(sum(event_cnt), 0)        AS late_rate
                FROM agg_1m WHERE minute >= now() - INTERVAL 10 MINUTE
                """, (rs, i) -> {
            long p99 = rs.getLong("ing_p99");
            return new LagRow(rs.getDouble("lag_p50"), rs.getDouble("lag_p99"),
                    rs.getString("max_partition"), rs.getLong("ing_p50"), p99, 300L,
                    p99 > 300 ? "bad" : "ok", rs.getLong("late_cnt"), rs.getDouble("late_rate"),
                    0L, false);
        }).stream().findFirst().orElseThrow();
    }

    @Override
    public ReconcileRow reconcile(LocalDate dt) {
        Integer gap = aggJdbc.queryForObject(
                "SELECT toInt32(ifNull(sum(state_gap_cnt),0)) FROM agg_1d WHERE dt = toDate(?)",
                Integer.class, dt.toString());
        List<MissingEvent> missing = aggJdbc.query("""
                SELECT missing_event_type AS et, toInt32(count()) AS cnt, any(likely_reason) AS likely
                FROM ods_state_gap WHERE dt = toDate(?) GROUP BY et ORDER BY cnt DESC
                """, (rs, i) -> new MissingEvent(rs.getString("et"), rs.getInt("cnt"),
                rs.getString("likely")), dt.toString());
        return new ReconcileRow(dt, gap == null ? 0 : gap, Map.of(), missing, List.of(), 0, List.of());
    }

    @Override
    public List<EventQualityRow> eventQuality() {
        return aggJdbc.query("""
                SELECT event_type, toInt64(sum(event_cnt)) AS total,
                       toInt64(sum(dup_cnt)) AS dup, toInt64(sum(dirty_cnt)) AS dirty,
                       sum(dirty_cnt) / nullIf(sum(event_cnt),0) AS dirty_rate,
                       avg(ingest_delay_sec) AS avg_gap
                FROM agg_1h WHERE hour >= now() - INTERVAL 24 HOUR
                GROUP BY event_type ORDER BY dirty_rate DESC
                """, (rs, i) -> {
            double dirtyRate = rs.getDouble("dirty_rate");
            return new EventQualityRow(rs.getString("event_type"), rs.getLong("total"),
                    rs.getLong("dup"), rs.getLong("dirty"), dirtyRate, rs.getDouble("avg_gap"),
                    dirtyRate > 0.001 ? "warn" : "ok");
        });
    }

    // ── 写入 ──────────────────────────────────────────────────────────────

    @Override
    public int insertEvents(List<EventEnvelope> events) {
        if (events.isEmpty()) {
            return 0;
        }
        String sql = """
                INSERT INTO ods_order_event
                    (event_id, event_type, event_time, ingest_time, order_id, trip_id,
                     city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        int[][] r = odsJdbc.batchUpdate(sql, events, events.size(), (ps, e) -> {
            ps.setString(1, e.eventId());
            ps.setString(2, e.eventType());
            ps.setTimestamp(3, ts(e.eventTime().toInstant()));
            ps.setTimestamp(4, ts(e.ingestTime().toInstant()));
            ps.setString(5, e.orderId());
            ps.setString(6, e.tripId() == null ? "" : e.tripId());
            ps.setLong(7, e.cityId() == null ? 0L : e.cityId());
            ps.setString(8, e.seatType() == null ? "-" : e.seatType());
            ps.setString(9, e.bizLine());
            ps.setString(10, e.driverIdHash() == null ? "" : e.driverIdHash());
            ps.setLong(11, e.amount() == null ? 0L : e.amount());
            ps.setString(12, writeJson(e.props()));
            ps.setLong(13, e.version() == null ? 1L : e.version());
        });
        // JDBC 批量约定：行计数 < 0 表示 SUCCESS_NO_INFO(-2)（clickhouse-jdbc V2 对
        // 批量 INSERT 恒返回 -2），此时按提交行数计，避免 accepted 显示为负数。
        return java.util.Arrays.stream(r).flatMapToInt(java.util.Arrays::stream)
                .map(n -> n < 0 ? 1 : n).sum();
    }

    @Override
    public void insertDirty(List<DirtyRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        odsJdbc.batchUpdate("""
                INSERT INTO ods_dirty_event (dt, event_type, reason, raw_head, producer)
                VALUES (today(), ?, ?, ?, ?)
                """, rows, rows.size(), (ps, r) -> {
            ps.setString(1, r.eventType());
            ps.setString(2, r.reason());
            ps.setString(3, r.rawHead());
            ps.setString(4, r.producer());
        });
    }

    @Override
    public List<DlqRow> dlq(String bizLine, String eventType, String reason,
                            LocalDate from, LocalDate to) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("from", from.toString()).addValue("to", to.toString())
                .addValue("et", eventType).addValue("reason", reason);
        StringBuilder sb = new StringBuilder("""
                SELECT dt, event_type, reason, toInt64(count()) AS cnt,
                       groupArray(3)(substring(raw_head, 1, 60)) AS samples, any(producer) AS producer
                FROM ods_dirty_event
                WHERE dt >= toDate(:from) AND dt <= toDate(:to)
                """);
        if (eventType != null) {
            sb.append(" AND event_type = :et");
        }
        if (reason != null) {
            sb.append(" AND reason = :reason");
        }
        sb.append(" GROUP BY dt, event_type, reason ORDER BY cnt DESC LIMIT 200");
        return aggNamed.query(sb.toString(), p, (rs, i) -> {
            java.sql.Array arr = rs.getArray("samples");
            List<String> samples = arr == null ? List.of() : List.of((String[]) arr.getArray());
            return new DlqRow(rs.getDate("dt").toLocalDate(), rs.getString("event_type"),
                    rs.getString("reason"), rs.getLong("cnt"), samples, rs.getString("producer"));
        });
    }

    // ── 工具 ──────────────────────────────────────────────────────────────

    private static String mask(String hash) {
        if (hash == null || hash.length() < 8) {
            return hash;
        }
        return hash.substring(0, 4) + "..." + hash.substring(hash.length() - 4);
    }

    private Map<String, Object> parseProps(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new tools.jackson.core.type.TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, List<String>> parseMatchers(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception ex) {
            log.warn("props 序列化失败", ex);
            throw new ApiException(ErrorCode.INTERNAL, "props 序列化失败");
        }
    }
}
