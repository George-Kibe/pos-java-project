package com.pos.gateway.ratelimit;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * A token bucket held in Redis, evaluated atomically by a Lua script.
 *
 * <p>A token bucket rather than a fixed window: a fixed window lets a caller spend its whole
 * allowance at the end of one window and again at the start of the next, so a "10 per minute" limit
 * actually permits 20 back to back - which is exactly the burst that matters when the endpoint
 * being hammered is login.
 *
 * <p>The script runs as a single Redis call, so read-modify-write cannot interleave between gateway
 * replicas. Doing this with GET then SET from Java would let two replicas both see nine tokens and
 * both allow.
 */
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    /**
     * Refills continuously, spends one token, and reports how long until the next token is
     * available. Returns {allowed, tokensRemaining, retryAfterMillis}.
     */
    private static final String SCRIPT =
            """
            local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local capacity = tonumber(ARGV[1])
            local ratePerMs = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])

            local tokens = tonumber(state[1])
            local ts = tonumber(state[2])
            if tokens == nil or ts == nil then
                tokens = capacity
                ts = now
            end

            local elapsed = now - ts
            if elapsed < 0 then elapsed = 0 end
            tokens = math.min(capacity, tokens + (elapsed * ratePerMs))

            local allowed = 0
            local retryAfter = 0
            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            else
                retryAfter = math.ceil((1 - tokens) / ratePerMs)
            end

            redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
            redis.call('PEXPIRE', KEYS[1], ttl)

            return {allowed, math.floor(tokens), retryAfter}
            """;

    private final StringRedisTemplate redis;
    private final RedisScript<List> script;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
        DefaultRedisScript<List> redisScript = new DefaultRedisScript<>();
        redisScript.setScriptText(SCRIPT);
        redisScript.setResultType(List.class);
        this.script = redisScript;
    }

    public record Decision(boolean allowed, long remaining, Duration retryAfter) {

        static Decision allowedWith(long remaining) {
            return new Decision(true, remaining, Duration.ZERO);
        }
    }

    /**
     * Spends one token from {@code key}'s bucket.
     *
     * <p>Fails open. Rate limiting protects the system but is not part of serving a sale, and a
     * Redis blip must not stop a supermarket trading. A refused request would be the wrong trade
     * here, so the outage is logged and traffic is allowed through.
     */
    public Decision tryConsume(String key, int capacity, Duration window) {
        double ratePerMs = (double) capacity / window.toMillis();
        long ttl = window.toMillis() * 2;

        try {
            @SuppressWarnings("unchecked")
            List<Long> result =
                    script.getResultType() == null
                            ? null
                            : (List<Long>)
                                    redis.execute(
                                            script,
                                            List.of(key),
                                            String.valueOf(capacity),
                                            String.valueOf(ratePerMs),
                                            String.valueOf(System.currentTimeMillis()),
                                            String.valueOf(ttl));

            if (result == null || result.size() < 3) {
                return Decision.allowedWith(capacity);
            }

            boolean allowed = result.get(0) == 1L;
            long remaining = result.get(1);
            Duration retryAfter = Duration.ofMillis(result.get(2));
            return new Decision(allowed, remaining, retryAfter);

        } catch (Exception e) {
            log.warn(
                    "Rate limiting unavailable ({}); allowing the request. Redis may be down.",
                    e.getMessage());
            return Decision.allowedWith(capacity);
        }
    }
}
