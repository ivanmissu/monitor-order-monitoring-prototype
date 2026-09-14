package com.minitor.server.service;

import com.minitor.server.domain.BizLine;
import com.minitor.server.domain.Grain;
import com.minitor.server.domain.TimeRange;
import com.minitor.server.query.AggQueryService;
import com.minitor.server.query.Metrics;
import com.minitor.server.store.MinitorStore;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §05 值班哨。只回答一个问题：<b>现在有没有事</b>。
 */
@Service
public class SentinelService {

    /** 值班哨 KPI 固定集合，口径全部来自字典。 */
    private static final List<String> KPI = List.of(
            "core.order_created_cnt",
            "core.order_delivered_cnt",
            "core.delivery_rate",
            "fund.gtv_net",
            "fund.settle_delay_rate");

    private static final List<String> TREND = List.of(
            "core.order_created_cnt",
            "core.order_delivered_cnt",
            "fund.settle_credited_cnt");

    private final AggQueryService agg;
    private final MinitorStore store;
    private final AlertService alertService;

    public SentinelService(AggQueryService agg, MinitorStore store, AlertService alertService) {
        this.agg = agg;
        this.store = store;
        this.alertService = alertService;
    }

    // ── 视图模型 ──────────────────────────────────────────────────────────

    public record LinkHealth(OffsetDateTime evaluatedAt, int windowDelaySec, int abnormal,
                             boolean blind, boolean silencedByBackfill,
                             List<MinitorStore.LinkNode> nodes, String note) {
    }

    public record BizCard(String bizLine, String label, String color, long orders, long delivered,
                          double deliveryRate, long gtvFen, double delta, boolean rateGood,
                          Map<String, Object> topAlert) {
    }

    public record Bootstrap(LinkHealth linkHealth, List<Metrics.Value> kpi, List<BizCard> bizCards,
                            List<AlertService.AlertView> alerts, Map<String, Integer> alertMeta) {
    }

    // ── 首屏聚合（BFF） ───────────────────────────────────────────────────

    public Bootstrap bootstrap(BizLine biz, TimeRange range) {
        Metrics.Query q = Metrics.Query.of(biz, range.withGrain(Grain.D1));
        List<Metrics.Value> kpi = agg.values(KPI, q, "yesterday");
        List<AlertService.AlertView> alerts = alertService.activeAlerts(biz);
        return new Bootstrap(linkHealth(), kpi,
                biz.isAll() ? bizCards(range) : List.of(),
                alerts, alertService.meta(biz));
    }

    public LinkHealth linkHealth() {
        List<MinitorStore.LinkNode> nodes = store.linkNodes();
        int abnormal = (int) nodes.stream().filter(n -> !"ok".equals(n.status())).count();
        boolean blind = nodes.stream()
                .anyMatch(n -> "clickhouse".equals(n.key()) && !"ok".equals(n.status()));
        return new LinkHealth(OffsetDateTime.now(TimeRange.ZONE), 60, abnormal, blind, false,
                nodes, "链路失明本身是 P0 规则 #4，不会把断流误读为业务正常");
    }

    public List<Metrics.Value> summary(List<String> metricIds, BizLine biz, TimeRange range,
                                       String compare) {
        return agg.values(metricIds, Metrics.Query.of(biz, range), compare);
    }

    public Map<String, Object> timeseries(List<String> metricIds, BizLine biz, TimeRange range,
                                          String splitBy) {
        List<String> ids = metricIds == null || metricIds.isEmpty() ? TREND : metricIds;
        Metrics.Query q = new Metrics.Query(biz, List.of(), null, range, splitBy);
        List<Metrics.Series> series = agg.series(ids, q);
        List<Metrics.Annotation> annotations = new ArrayList<>(agg.versionAnnotations(ids, range));
        annotations.addAll(alertService.alertAnnotations(biz, range));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("grain", range.grain().id());
        out.put("partial", range.includesToday());
        out.put("series", series);
        out.put("annotations", annotations);
        return out;
    }

    private List<BizCard> bizCards(TimeRange range) {
        Map<BizLine, MinitorStore.BizAgg> aggs = store.bizAggregates(range);
        List<BizCard> cards = new ArrayList<>();
        for (BizLine b : BizLine.concrete()) {
            MinitorStore.BizAgg a = aggs.get(b);
            if (a == null) {
                continue;
            }
            double rate = a.orders() == 0 ? 0 : (double) a.delivered() / a.orders();
            cards.add(new BizCard(b.id(), b.label(), b.color(), a.orders(), a.delivered(),
                    Math.round(rate * 10000d) / 10000d, a.gtvFen(), a.delta(), a.rateGood(),
                    alertService.topAlertOf(b)));
        }
        return cards;
    }
}
