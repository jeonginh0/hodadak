package com.hodadak.backend.auth.infra;

import com.hodadak.backend.common.domain.BusinessException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@Profile("api")
public class RefreshTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<String> ROTATE = new DefaultRedisScript<>("""
        local owner = redis.call('GET', KEYS[1])
        if not owner then return nil end
        if redis.call('EXISTS', KEYS[2]) == 1 then return nil end
        redis.call('SET', KEYS[2], owner, 'EX', ARGV[1])
        redis.call('DEL', KEYS[1])
        return owner
        """, String.class);
    private static final DefaultRedisScript<Long> REVOKE = new DefaultRedisScript<>("""
        if redis.call('GET', KEYS[1]) == ARGV[1] then
          return redis.call('DEL', KEYS[1])
        end
        return 0
        """, Long.class);
    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RefreshTokens(StringRedisTemplate redis, @Value("${hodadak.jwt.refresh-ttl-days}") long days) {
        if (days <= 0) throw new IllegalArgumentException("Invalid refresh TTL");
        this.redis = redis;
        this.ttl = Duration.ofDays(days);
    }

    public record Rotation(long userId, String token) {}

    public String issue(long userId) {
        for (;;) {
            String token = randomToken();
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(token), Long.toString(userId), ttl)))
                return token;
        }
    }

    public Rotation rotate(String token) {
        if (!valid(token)) throw BusinessException.unauthorized();
        String replacement = randomToken();
        String owner = redis.execute(ROTATE, List.of(key(token), key(replacement)), Long.toString(ttl.toSeconds()));
        if (owner == null) throw BusinessException.unauthorized();
        return new Rotation(Long.parseLong(owner), replacement);
    }

    public void revoke(String token, long userId) {
        if (valid(token)) redis.execute(REVOKE, List.of(key(token)), Long.toString(userId));
    }

    private static String key(String token) { return "auth:refresh:" + token; }
    private static boolean valid(String token) { return token != null && token.matches("[A-Za-z0-9_-]{43}"); }
    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
