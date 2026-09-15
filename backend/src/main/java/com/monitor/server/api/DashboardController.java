package com.monitor.server.api;

import com.monitor.server.common.ApiResponse;
import com.monitor.server.config.MonitorProperties;
import com.monitor.server.domain.BizLine;
import com.monitor.server.domain.Grain;
import com.monitor.server.domain.TimeRange;
import com.monitor.server.security.TokenAuthFilter;
import com.monitor.server.service.DashboardService;
import com.monitor.server.store.MonitorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * §06 经营大盘。
 */
@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboard;
    private final MonitorProperties props;

    public DashboardController(DashboardService dashboard, MonitorProperties props) {
        this.dashboard = dashboard;
        this.props = props;
    }

    private TimeRange range(String from, String to, String grain) {
        return TimeRange.parse(from, to, grain == null ? "1d" : grain,
                props.getQuery().getMinuteGrainMaxSpanDays());
    }

    @GetMapping("/funnel")
    public ApiResponse<DashboardService.Funnel> funnel(
            @RequestParam(name = "biz_line", required = false) String bizLine,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(name = "compare_date", required = false) String compareDate) {
        TokenAuthFilter.current().assertScope("agg");
        LocalDate cmp = compareDate == null || compareDate.isBlank() ? null
                : LocalDate.parse(compareDate);
        return ApiResponse.ok(dashboard.funnel(BizLine.of(bizLine),
                range(from, to, "1d"), cmp));
    }

    @GetMapping("/composition")
    public ApiResponse<DashboardService.Composition> composition(
            @RequestParam(required = false) String metric,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        TokenAuthFilter.current().assertScope("agg");
        return ApiResponse.ok(dashboard.composition(range(from, to, "1d"), metric));
    }

    @GetMapping("/city-rank")
    public ApiResponse<List<MonitorStore.CityRow>> cityRank(
            @RequestParam(name = "biz_line", required = false) String bizLine,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "delivered_desc") String sort,
            @RequestParam(required = false, defaultValue = "10") int limit) {
        TokenAuthFilter.current().assertScope("agg");
        return ApiResponse.ok(dashboard.cityRank(range(from, to, "1d"),
                BizLine.of(bizLine), sort, Math.min(limit, 100)));
    }

    @GetMapping("/heatmap")
    public ApiResponse<DashboardService.Heatmap> heatmap(
            @RequestParam(name = "biz_line", required = false) String bizLine,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false, defaultValue = "accept_rate") String metric,
            @RequestParam(required = false, defaultValue = "5") int rows,
            @RequestParam(name = "hour_step", required = false, defaultValue = "2") int hourStep) {
        TokenAuthFilter.current().assertScope("agg");
        TimeRange r = range(from, to, "1h").withGrain(Grain.H1);
        return ApiResponse.ok(dashboard.heatmap(r, BizLine.of(bizLine), metric, rows, hourStep));
    }
}
