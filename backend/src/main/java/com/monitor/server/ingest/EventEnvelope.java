package com.monitor.server.ingest;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 统一事件信封（接口文档 §16 / 设计文档 §3.2）。
 *
 * <p>生产侧契约三条：
 * <ol>
 *   <li>事件在状态跃迁发生的<b>同一事务提交后</b>发出，至少一次投递，重复由消费端去重；</li>
 *   <li>{@code cityId / seatType / amount} 等维度必须是<b>发生时刻的快照值</b>，
 *       禁止查询时回连业务库补维度；</li>
 *   <li>事件命名稳定不改语义；语义变化必须换新 {@code eventType} 并在字典登记 supersedes 关系。</li>
 * </ol>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(

        /* 雪花 ID，全链路幂等键 */
        @NotBlank String eventId,

        @NotBlank String eventType,

        /* 业务发生时间（状态跃迁时刻） */
        @NotNull OffsetDateTime eventTime,

        /* 监控接收时间：由服务端写入，业务侧填写将被忽略。链路延迟 = 两列之差 */
        OffsetDateTime ingestTime,

        @NotBlank String orderId,

        String tripId,

        @NotBlank String bizLine,

        /* 维度快照，禁止查询期回查业务库 */
        Long cityId,

        String seatType,

        /* SHA256 脱敏；禁止明文手机号 / 证件号 */
        String driverIdHash,

        /* Int64 分，无金额事件为 0（不可为 null，否则聚合口径不确定） */
        Long amount,

        /* 字段白名单管理，命中 PII 规则进死信表 */
        Map<String, Object> props,

        /* 同一 event_id 的修正版本，ReplacingMergeTree 取最大 */
        Long version
) {

    /** 服务端补齐 ingest_time 与默认值。 */
    public EventEnvelope stamped(OffsetDateTime receivedAt) {
        return new EventEnvelope(eventId, eventType, eventTime, receivedAt, orderId, tripId,
                bizLine, cityId, seatType, driverIdHash,
                amount == null ? 0L : amount,
                props == null ? Map.of() : props,
                version == null ? 1L : version);
    }

    /** 链路延迟（秒），元监控 ingest_delay_p99 的原始输入。 */
    public long ingestDelaySec() {
        if (ingestTime == null || eventTime == null) {
            return 0L;
        }
        return java.time.Duration.between(eventTime, ingestTime).toSeconds();
    }

    /** 迟到定义：ingest 比 event_time 晚 > 10 分钟。 */
    public boolean late() {
        return ingestDelaySec() > 600L;
    }
}
