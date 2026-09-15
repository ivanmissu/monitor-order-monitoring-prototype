package com.monitor.server.api;

import com.monitor.server.common.ApiResponse;
import com.monitor.server.config.MonitorProperties;
import com.monitor.server.domain.BizLine;
import com.monitor.server.domain.TimeRange;
import com.monitor.server.query.Metrics;
import com.monitor.server.security.TokenAuthFilter;
import com.monitor.server.service.SentinelService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * §05 值班哨 · 链路健康与总览。
 */
@RestController
@RequestMapping("/api/v1")
public class SentinelController {

    private final SentinelService sentinel;
    private final MonitorProperties props;

    public SentinelController(SentinelService sentinel, MonitorProperties props) {
        this.sentinel = sentinel;
        this.props = props;
    }

    /**
     * 首屏聚合 BFF：一次返回链路红绿灯 + KPI + 业务线卡片 + 活动告警。
     * 目标 P99 ≤ 300ms，ETag 20s。
     */
    @GetMapping("/sentinel/bootstrap")
    public ApiResponse<SentinelService.Bootstrap> bootstrap(
            @RequestParam(name = "biz_line", required = false) String bizLine,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "1d") String grain) {
        TokenAuthFilter.current().assertScope("agg");
        BizLine biz = BizLine.of(bizLine);
        TimeRange range = TimeRange.parse(from, to, grain,
                props.getQuery().getMinuteGrainMaxSpanDays());
        return ApiResponse.ok(sentinel.bootstrap(biz, range));
    }

    /** KPI 指标带。率值强制返回分子与分母（设计纪律）。 */
    @GetMapping("/overview/summary")
    public ApiResponse<List<Metrics.Value>> summary(
            @RequestParam String metrics,
            @RequestParam(name = "biz_line", required = false) String bizLine,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "1d") String grain,
            @RequestParam(required = false, defaultValue = "yesterday") String compare) {
        TokenAuthFilter.current().assertScope("agg");
        List<String> ids = Arrays.stream(metrics.split(",")).map(String::trim).limit(12).toList();
        TimeRange range = TimeRange.parse(from, to, grain,
                props.getQuery().getMinuteGrainMaxSpanDays());
        return ApiResponse.ok(sentinel.summary(ids, BizLine.of(bizLine), range, compare));
    }

    /** 主链路趋势，附告警与口径变更 annotation。 */
    @GetMapping("/overview/timeseries")
    public ApiResponse<Map<String, Object>> timeseries(
            @RequestParam(required = false) String metrics,
            @RequestParam(name = "biz_line", required = false) String bizLine,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "2h") String grain,
            @RequestParam(name = "split_by", required = false, defaultValue = "none") String splitBy) {
        TokenAuthFilter.current().assertScope("agg");
        List<String> ids = metrics == null || metrics.isBlank() ? List.of()
                : Arrays.stream(metrics.split(",")).map(String::trim).toList();
        TimeRange range = TimeRange.parse(from, to, grain,
                props.getQuery().getMinuteGrainMaxSpanDays());
        return ApiResponse.ok(sentinel.timeseries(ids, BizLine.of(bizLine), range, splitBy));
    }

    /** 链路健康节点：断流判定与「数据失明」提示的数据源。 */
    @GetMapping("/overview/link-health")
    public ApiResponse<SentinelService.LinkHealth> linkHealth() {
        TokenAuthFilter.current().assertScope("agg");
        return ApiResponse.ok(sentinel.linkHealth());
    }
}
