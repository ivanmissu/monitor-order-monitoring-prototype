package com.minitor.server.common;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Redis 生产适配器；demo 构建会从编译源集中排除本类。 */
@Component
public class RedisIdempotencyStore implements IdempotencyGuard.IdempotencyStore {

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public RedisIdempotencyStore(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    @Override
    public Boolean setIfAbsent(String key, String value, Duration ttl) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        return redis == null ? null : redis.opsForValue().setIfAbsent(key, value, ttl);
    }
}
