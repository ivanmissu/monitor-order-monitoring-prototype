package com.minitor.server.common;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 幂等防线一：Redis SETNX。
 *
 * <p>用于两处：
 * <ul>
 *   <li>事件去重（key = {@code event_id}，TTL 48h）—— 配合 ODS ReplacingMergeTree 与 T+1 重算共三道防线；</li>
 *   <li>写接口 {@code Idempotency-Key} —— 重复请求返回首次结果并置 {@code X-Idempotent-Replay: 1}。</li>
 * </ul>
 *
 * <p>Redis 不可用（demo profile）时退化为本地 Map，保证服务可独立启动。
 */
@Component
public class IdempotencyGuard {

    private static final String EVENT_PREFIX = "minitor:evt:";
    private static final String CMD_PREFIX = "minitor:cmd:";

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final Map<String, String> fallback = new ConcurrentHashMap<>();

    public IdempotencyGuard(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    /**
     * 首次出现返回 true；重复投递返回 false（调用方计入 duplicated，不报错）。
     */
    public boolean firstSeenEvent(String eventId, Duration ttl) {
        return setIfAbsent(EVENT_PREFIX + eventId, "1", ttl);
    }

    /**
     * 写命令幂等。返回 true 表示首次执行；false 表示重放。
     */
    public boolean firstSeenCommand(String idempotencyKey, Duration ttl) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return true;
        }
        return setIfAbsent(CMD_PREFIX + idempotencyKey, "1", ttl);
    }

    private boolean setIfAbsent(String key, String value, Duration ttl) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return fallback.putIfAbsent(key, value) == null;
        }
        Boolean ok = redis.opsForValue().setIfAbsent(key, value, ttl);
        return Boolean.TRUE.equals(ok);
    }
}
