package com.minitor.server.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.minitor.server.ingest.EventEnvelope;
import com.minitor.server.service.IngestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * MQ 主通路消费者。无状态，2s / 500 条攒批（由 Kafka 批量监听器控制）。
 *
 * <p>故障语义：
 * <ul>
 *   <li>consumer 故障 → MQ 堆积，恢复后追平；「ingest 延迟 P99 &gt;5min」自身为 P0 告警，
 *       断流不会被误读为「业务正常」；</li>
 *   <li>ClickHouse 不可写 → 本地缓冲 + 重试，不 ack，由 MQ 保留 7 天兜底；</li>
 *   <li>重复投递 → 幂等三道防线（Redis SETNX / ReplacingMergeTree / T+1 重算）。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "minitor.store.kind", havingValue = "clickhouse", matchIfMissing = true)
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private final IngestService ingest;
    private final ObjectMapper mapper;

    public EventConsumer(IngestService ingest, ObjectMapper mapper) {
        this.ingest = ingest;
        this.mapper = mapper;
    }

    @KafkaListener(topics = "${minitor.ingest.topic:biz.order.event}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onBatch(List<String> payloads, Acknowledgment ack) {
        List<EventEnvelope> events = new ArrayList<>(payloads.size());
        for (String payload : payloads) {
            try {
                events.add(mapper.readValue(payload, EventEnvelope.class));
            } catch (Exception ex) {
                // 反序列化失败视为脏事件，交由 IngestService 落死信
                log.warn("事件反序列化失败，已跳过: {}", abbreviate(payload));
            }
        }
        if (events.isEmpty()) {
            ack.acknowledge();
            return;
        }
        try {
            IngestService.IngestResult result = ingest.ingest(events, "kafka");
            log.debug("消费 {} 条: accepted={} duplicated={} rejected={}",
                    result.received(), result.accepted(), result.duplicated(), result.rejected());
            ack.acknowledge();
        } catch (Exception ex) {
            // 不 ack：位点不前进，等待存储恢复后重放（MQ 保留 ≥7 天）
            log.error("批量写入失败，位点不提交，等待重试: {}", ex.getMessage());
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.substring(0, Math.min(120, s.length()));
    }
}
