package com.monitor.server.api;

import com.monitor.server.common.ApiResponse;
import com.monitor.server.config.MonitorProperties;
import com.monitor.server.dict.DictSeed;
import com.monitor.server.dict.MetricDef;
import com.monitor.server.dict.MetricDictionary;
import com.monitor.server.domain.BizLine;
import com.monitor.server.domain.Dims;
import com.monitor.server.security.TokenAuthFilter;
import com.monitor.server.security.TokenRole;
import com.monitor.server.common.ApiException;
import com.monitor.server.common.ErrorCode;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §04 字典与元数据。指标字典是口径唯一真源，前端所有指标名 / 单位 / 维度 / 告警关联都从此读取。
 */
@RestController
@RequestMapping("/api/v1/dict")
public class DictController {

    private final MetricDictionary dict;
    private final MonitorProperties props;

    public DictController(MetricDictionary dict, MonitorProperties props) {
        this.dict = dict;
        this.props = props;
    }

    // ── 业务线清单 ────────────────────────────────────────────────────────

    public record BizLineView(String id, String label, String shortName, String color, String icon,
                              long dailyOrders, String owner, List<String> boards) {
    }

    @GetMapping("/biz-lines")
    public ApiResponse<List<BizLineView>> bizLines() {
        Map<String, long[]> volume = Map.of(
                "driver", new long[]{286_412}, "transfer", new long[]{42_180},
                "carpool", new long[]{128_463}, "designated", new long[]{68_924},
                "airport", new long[]{31_886}, "all", new long[]{557_865});
        Map<String, String> owners = Map.of("driver", "韩青", "transfer", "邵可",
                "carpool", "程一帆", "designated", "金路", "airport", "纪南", "all", "林舟");
        List<BizLineView> out = Arrays.stream(BizLine.values())
                .map(b -> new BizLineView(b.id(), b.label(), b.shortName(), b.color(), b.icon(),
                        volume.getOrDefault(b.id(), new long[]{0})[0],
                        owners.getOrDefault(b.id(), "-"), b.boards()))
                .toList();
        return ApiResponse.ok(out);
    }

    // ── 指标字典 ──────────────────────────────────────────────────────────

    public record MetricListView(String id, String name, String domain, String type,
                                 List<String> bizLines, String formula, List<String> sourceEvents,
                                 List<String> grains, String unit, String version, String status,
                                 String owner, LocalDate updatedAt, List<String> usedIn, int alarmCount) {
    }

    public record MetricPage(int total, List<MetricListView> items, String nextCursor,
                             Map<String, Integer> stats) {
    }

    @GetMapping("/metrics")
    public ApiResponse<MetricPage> metrics(@RequestParam(required = false) String domain,
                                           @RequestParam(required = false) String type,
                                           @RequestParam(name = "biz_line", required = false) String bizLine,
                                           @RequestParam(required = false) String q,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(defaultValue = "50") int limit) {
        List<Dims.MetricDomain> domains = domain == null || domain.isBlank() ? List.of()
                : Arrays.stream(domain.split(",")).map(String::trim)
                .map(d -> Dims.MetricDomain.valueOf(d.toUpperCase())).toList();
        Dims.MetricType mt = type == null || type.isBlank() ? null
                : Dims.MetricType.valueOf(type.toUpperCase());
        List<MetricDef> all = dict.search(domains, mt, BizLine.of(bizLine), q, status);
        List<MetricListView> items = all.stream().limit(limit).map(this::toListView).toList();
        return ApiResponse.ok(new MetricPage(all.size(), items, null, dict.stats()));
    }

    private MetricListView toListView(MetricDef m) {
        return new MetricListView(m.id(), m.name(), m.domain().id(), m.type().name().toLowerCase(),
                m.bizLines(), m.formula(), m.sourceEvents(), m.grains(), m.unit().id(),
                m.version(), m.status(), m.owner(), m.updatedAt(), m.usedIn(),
                (int) m.usedIn().stream().filter(u -> u.startsWith("alert#")).count());
    }

    public record MetricDetail(String id, String name, String version, String numeratorSql,
                               String denominatorSql, String boundary, List<String> applicableDims,
                               Map<String, Object> alarmExample, List<MetricDef.History> history,
                               Map<String, Object> supersedes, MetricListView summary) {
    }

    @GetMapping("/metrics/{metricId}")
    public ApiResponse<MetricDetail> metric(@PathVariable String metricId) {
        MetricDef m = dict.require(metricId);
        Map<String, Object> alarm = m.alarmExample() == null ? Map.of()
                : Map.of("expr", m.alarmExample());
        Map<String, Object> supersedes = m.history().isEmpty() ? Map.of()
                : Map.of("from", m.history().getFirst().version(),
                "reset_baseline", m.history().getFirst().resetBaseline());
        return ApiResponse.ok(new MetricDetail(m.id(), m.name(), m.version(), m.numeratorSql(),
                m.denominatorSql(), m.boundary(),
                List.of("city_id", "biz_line", "seat_type", "dt", "hour"),
                alarm, m.history(), supersedes, toListView(m)));
    }

    /** 口径变更的唯一入口：评审通过后登记新版本，禁止口头改口径。 */
    public record VersionCmd(@NotBlank String baseVersion, @NotBlank String numeratorSql,
                             String denominatorSql, LocalDate effectiveFrom, String change,
                             String backfillWindow, Boolean alarmRecalc) {
    }

    public record VersionResult(String version, List<String> affectedRules, String backfillJobId,
                                LocalDate annotationAt, boolean resetBaseline) {
    }

    @PutMapping("/metrics/{metricId}")
    public ApiResponse<VersionResult> putMetric(@PathVariable String metricId,
                                                @RequestBody VersionCmd cmd) {
        requireRole(TokenRole.ADMIN);
        MetricDef updated = dict.registerVersion(metricId, cmd.baseVersion(), cmd.numeratorSql(),
                cmd.denominatorSql(), cmd.effectiveFrom(), cmd.change(), cmd.backfillWindow());
        List<String> rules = dict.referencingRules(metricId);
        boolean reset = !updated.history().isEmpty() && updated.history().getFirst().resetBaseline();
        String job = "none".equals(cmd.backfillWindow()) || cmd.backfillWindow() == null
                ? null : "bf-" + LocalDate.now() + "-" + metricId.hashCode();
        return ApiResponse.ok(new VersionResult(updated.version(), rules, job,
                updated.updatedAt(), reset));
    }

    // ── 城市 / 常量 / 事件契约 ────────────────────────────────────────────

    @GetMapping("/cities")
    public ApiResponse<Map<String, Object>> cities() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cities", DictSeed.cities());
        data.put("seat_types", List.of(Map.of("id", "exclusive", "name", "独享"),
                Map.of("id", "two_seat", "name", "2 座"),
                Map.of("id", "three_seat", "name", "3 座")));
        data.put("constants", DictSeed.constants(props.getAlert().getPeakWindows(),
                props.getQuery().getSmallSampleFloor()));
        return ApiResponse.ok(data);
    }

    @GetMapping("/event-types")
    public ApiResponse<List<DictSeed.EventTypeDef>> eventTypes(
            @RequestParam(name = "biz_line", required = false) String bizLine) {
        return ApiResponse.ok(DictSeed.eventTypes());
    }

    private void requireRole(TokenRole required) {
        TokenAuthFilter.Principal p = TokenAuthFilter.current();
        if (p.role() != required && p.role() != TokenRole.ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN_DIMENSION,
                    "需要 " + required + " 角色，当前为 " + p.role());
        }
    }
}
