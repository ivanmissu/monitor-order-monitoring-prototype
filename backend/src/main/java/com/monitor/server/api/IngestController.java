package com.monitor.server.api;

import com.monitor.server.common.ApiException;
import com.monitor.server.common.ApiResponse;
import com.monitor.server.common.ErrorCode;
import com.monitor.server.common.IdempotencyGuard;
import com.monitor.server.common.RequestContext;
import com.monitor.server.config.MonitorProperties;
import com.monitor.server.ingest.EventEnvelope;
import com.monitor.server.security.TokenAuthFilter;
import com.monitor.server.service.IngestService;
import com.monitor.server.store.MonitorStore;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * §13 事件接入。Kafka（topic {@code biz.order.event}）为主通路，
 * 本 HTTP 接口面向低流量 / 无 Kafka 环境的业务线；两者落库与幂等逻辑完全一致。
 */
@RestController
@RequestMapping("/api/v1/ingest")
public class IngestController {

    private final IngestService ingest;
    private final IdempotencyGuard idempotency;
    private final MonitorProperties props;

    public IngestController(IngestService ingest, IdempotencyGuard idempotency,
                            MonitorProperties props) {
        this.ingest = ingest;
        this.idempotency = idempotency;
        this.props = props;
    }

    public record IngestBody(@Valid List<EventEnvelope> events) {
    }

    @PostMapping("/events")
    public ApiResponse<IngestService.IngestResult> events(
            @RequestBody IngestBody body,
            @RequestHeader(name = "Idempotency-Key", required = false) String idemKey,
            @RequestHeader(name = "X-Monitor-Batch-Seq", required = false) String batchSeq,
            @RequestHeader(name = "User-Agent", required = false) String userAgent,
            HttpServletResponse response) {
        TokenAuthFilter.Principal p = TokenAuthFilter.current();
        p.assertScope("ingest");

        if (body == null || body.events() == null || body.events().isEmpty()) {
            throw ApiException.invalidParam("events 不能为空");
        }
        if (body.events().size() > props.getIngest().getMaxBatchSize()) {
            throw new ApiException(ErrorCode.BATCH_TOO_LARGE,
                    "单批上限 " + props.getIngest().getMaxBatchSize() + " 条 / 2MB");
        }
        if (!idempotency.firstSeenCommand(idemKey, Duration.ofHours(1))) {
            response.setHeader(RequestContext.H_IDEMPOTENT_REPLAY, "1");
        }
        String producer = userAgent == null ? p.subject() : userAgent;
        return ApiResponse.ok(ingest.ingest(body.events(), producer));
    }

    public record ValidateBody(List<EventEnvelope> events, Boolean strict) {
    }

    /** 接入联调用：只校验不落库，逐条返回问题。 */
    @PostMapping("/validate")
    public ApiResponse<Map<String, Object>> validate(@RequestBody ValidateBody body) {
        TokenAuthFilter.current().assertScope("ingest");
        return ApiResponse.ok(ingest.validate(body.events(),
                body.strict() != null && body.strict()));
    }

    /** 死信事件查询，供接入方自查与事件质量告警下钻。 */
    @GetMapping("/dlq")
    public ApiResponse<List<MonitorStore.DlqRow>> dlq(
            @RequestParam(name = "biz_line", required = false) String bizLine,
            @RequestParam(name = "event_type", required = false) String eventType,
            @RequestParam(required = false) String reason,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        TokenAuthFilter.current().assertScope("admin");
        return ApiResponse.ok(ingest.dlq(bizLine, eventType, reason,
                from == null ? null : LocalDate.parse(from),
                to == null ? null : LocalDate.parse(to)));
    }

    public record ReplayCmd(String bizLine, OffsetDateTime from, OffsetDateTime to,
                            String target, String mode, Boolean autoSilenceAlerts) {
    }

    /** 按时间窗回放（运维触发，回放期间自动静默新鲜度告警）。 */
    @PostMapping("/replay")
    public ApiResponse<IngestService.ReplayJob> replay(@RequestBody ReplayCmd cmd) {
        TokenAuthFilter.current().assertScope("admin");
        return ApiResponse.ok(ingest.replay(cmd.bizLine(), cmd.from(), cmd.to(),
                cmd.autoSilenceAlerts() == null || cmd.autoSilenceAlerts()));
    }

    @GetMapping("/replay/{jobId}")
    public ApiResponse<IngestService.ReplayJob> replayStatus(@PathVariable String jobId) {
        TokenAuthFilter.current().assertScope("admin");
        return ApiResponse.ok(ingest.replayStatus(jobId));
    }
}
