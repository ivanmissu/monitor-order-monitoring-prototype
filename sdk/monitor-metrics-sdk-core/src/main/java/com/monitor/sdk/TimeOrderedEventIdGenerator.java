package com.monitor.sdk;

import java.security.SecureRandom;

/**
 * 轻量 64 位、时间有序的 Snowflake 风格 ID 生成器。
 *
 * <p>布局为 41 位毫秒时间戳（自 2024-01-01 起）、10 位节点号、12 位序列号。
 * 多实例生产环境建议改用业务方已有的全局雪花 ID 实现，以获得确定的节点号分配。</p>
 */
public final class TimeOrderedEventIdGenerator implements EventIdGenerator {

    private static final long EPOCH_MILLIS = 1704067200000L;
    private static final int SEQUENCE_BITS = 12;
    private static final int NODE_BITS = 10;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
    private static final long MAX_NODE_ID = (1L << NODE_BITS) - 1;

    private final long nodeId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    /** 使用随机节点号，适合本地或未接入节点号服务的场景。 */
    public TimeOrderedEventIdGenerator() {
        this(new SecureRandom().nextInt((int) MAX_NODE_ID + 1));
    }

    public TimeOrderedEventIdGenerator(long nodeId) {
        if (nodeId < 0 || nodeId > MAX_NODE_ID) {
            throw new IllegalArgumentException("nodeId must be between 0 and " + MAX_NODE_ID);
        }
        this.nodeId = nodeId;
    }

    @Override
    public synchronized String nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            // 时钟回拨时保持单调性，避免产生重复的幂等键。
            timestamp = lastTimestamp;
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1L) & MAX_SEQUENCE;
            if (sequence == 0L) {
                // 单毫秒超过 4096 条时使用逻辑毫秒，保持非阻塞且仍然有序。
                timestamp = ++lastTimestamp;
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;

        long elapsed = timestamp - EPOCH_MILLIS;
        if (elapsed < 0L || elapsed >= (1L << 41)) {
            throw new IllegalStateException("timestamp is outside the supported event ID range");
        }
        long id = (elapsed << (NODE_BITS + SEQUENCE_BITS)) | (nodeId << SEQUENCE_BITS) | sequence;
        return Long.toString(id);
    }
}
