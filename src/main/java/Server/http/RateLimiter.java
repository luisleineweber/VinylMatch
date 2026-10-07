package Server.http;

import Server.session.RedisConfig;
import redis.clients.jedis.Jedis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple in-memory token bucket rate limiter (per key).
 */
public final class RateLimiter {

    private final int burst;
    private final double refillTokensPerMillis;
    private final int requestsPerMinute;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int maxBuckets;
    private final long idleTtlMillis;
    private final AtomicLong operations = new AtomicLong();

    public RateLimiter(int burst, int requestsPerMinute) {
        this(burst, requestsPerMinute, 10_000, Duration.ofMinutes(10));
    }

    RateLimiter(int burst, int requestsPerMinute, int maxBuckets, Duration idleTtl) {
        this.burst = Math.max(1, burst);
        int rpm = Math.max(1, requestsPerMinute);
        this.requestsPerMinute = rpm;
        this.refillTokensPerMillis = (double) rpm / Duration.ofMinutes(1).toMillis();
        this.maxBuckets = Math.max(1, maxBuckets);
        this.idleTtlMillis = Math.max(1, idleTtl.toMillis());
    }

    public Result tryAcquire(String key) {
        if (RedisConfig.isRequired()) return tryAcquireDistributed(key);
        long now = System.currentTimeMillis();
        if ((operations.incrementAndGet() & 63) == 0 || buckets.size() >= maxBuckets) sweep(now);
        if (buckets.size() >= maxBuckets && !buckets.containsKey(key)) {
            return new Result(false, 1, 0);
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(burst));
        return bucket.tryConsume(refillTokensPerMillis, burst);
    }

    public int limit() { return RedisConfig.isRequired() ? requestsPerMinute : burst; }

    int bucketCount() { return buckets.size(); }

    void sweep(long now) {
        buckets.entrySet().removeIf(entry -> now - entry.getValue().lastAccessMillis() >= idleTtlMillis);
    }

    private Result tryAcquireDistributed(String key) {
        String redisKey = "rate:v1:" + sha256(key);
        String script = "local n=redis.call('INCR',KEYS[1]); "
            + "if n==1 then redis.call('PEXPIRE',KEYS[1],ARGV[1]) end; "
            + "return {n,redis.call('PTTL',KEYS[1])}";
        try (Jedis jedis = RedisConfig.getJedis()) {
            if (jedis == null) throw new IllegalStateException("Redis rate limiter unavailable");
            Object raw = jedis.eval(script, List.of(redisKey), List.of("60000"));
            List<?> values = (List<?>) raw;
            long count = ((Number) values.get(0)).longValue();
            long ttl = Math.max(1, ((Number) values.get(1)).longValue());
            boolean allowed = count <= requestsPerMinute;
            int remaining = (int) Math.max(0, requestsPerMinute - count);
            int retryAfter = allowed ? 0 : (int) Math.max(1, Math.ceil(ttl / 1000.0));
            return new Result(allowed, retryAfter, remaining);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static RateLimiter fromEnv() {
        int perMinute = parseIntEnv("RATE_LIMIT_PER_MINUTE", 240);
        int burst = parseIntEnv("RATE_LIMIT_BURST", Math.max(30, perMinute / 4));
        int maxBuckets = parseIntEnv("RATE_LIMIT_MAX_BUCKETS", 10_000);
        int idleSeconds = parseIntEnv("RATE_LIMIT_IDLE_SECONDS", 600);
        return new RateLimiter(burst, perMinute, maxBuckets, Duration.ofSeconds(idleSeconds));
    }

    private static int parseIntEnv(String name, int def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public record Result(boolean allowed, int retryAfterSeconds, int remainingTokens) {}

    private static final class Bucket {
        private double tokens;
        private long lastRefillMillis;
        private long lastAccessMillis;

        private Bucket(int initialTokens) {
            this.tokens = initialTokens;
            this.lastRefillMillis = System.currentTimeMillis();
            this.lastAccessMillis = lastRefillMillis;
        }

        private synchronized Result tryConsume(double refillTokensPerMillis, int capacity) {
            long now = System.currentTimeMillis();
            lastAccessMillis = now;
            long elapsed = Math.max(0, now - lastRefillMillis);
            if (elapsed > 0) {
                tokens = Math.min(capacity, tokens + elapsed * refillTokensPerMillis);
                lastRefillMillis = now;
            }

            if (tokens >= 1.0) {
                tokens -= 1.0;
                return new Result(true, 0, (int) Math.floor(tokens));
            }

            double missing = 1.0 - tokens;
            long waitMillis = (long) Math.ceil(missing / refillTokensPerMillis);
            int retryAfterSeconds = (int) Math.max(1, Math.ceil(waitMillis / 1000.0));
            return new Result(false, retryAfterSeconds, 0);
        }

        private synchronized long lastAccessMillis() { return lastAccessMillis; }
    }
}
