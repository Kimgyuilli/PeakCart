package com.peekcart.user.infrastructure.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Repository
@RequiredArgsConstructor
public class TokenBlacklistRepository {

    private static final String BLACKLIST_PREFIX = "bl:";
    private static final String GRACE_PERIOD_PREFIX = "gp:";

    private final RedisTemplate<String, String> redisTemplate;

    public void addToBlacklist(String token, long ttlSeconds) {
        redisTemplate.opsForValue().set(BLACKLIST_PREFIX + token, "1", ttlSeconds, TimeUnit.SECONDS);
    }

    public boolean isBlacklisted(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + token));
    }

    public void addGracePeriod(String token, long userId, long ttlSeconds) {
        redisTemplate.opsForValue().set(GRACE_PERIOD_PREFIX + token, String.valueOf(userId), ttlSeconds, TimeUnit.SECONDS);
    }

    public Optional<Long> getGracePeriodUserId(String token) {
        String value = redisTemplate.opsForValue().get(GRACE_PERIOD_PREFIX + token);
        return Optional.ofNullable(value).map(Long::parseLong);
    }
}
