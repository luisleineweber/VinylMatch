package Server.http;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    @Test
    void tokenBucketAllowsBurstThenLimits() {
        RateLimiter limiter = new RateLimiter(2, 60); // 2 burst, 1 token/sec refill

        assertTrue(limiter.tryAcquire("k").allowed());
        assertTrue(limiter.tryAcquire("k").allowed());

        RateLimiter.Result third = limiter.tryAcquire("k");
        assertFalse(third.allowed());
        assertTrue(third.retryAfterSeconds() >= 1);
    }

    @Test
    void boundsAndEvictsIdleBuckets() {
        RateLimiter limiter = new RateLimiter(1, 60, 2, Duration.ofMinutes(1));

        assertTrue(limiter.tryAcquire("a").allowed());
        assertTrue(limiter.tryAcquire("b").allowed());
        assertFalse(limiter.tryAcquire("c").allowed());
        assertEquals(2, limiter.bucketCount());

        limiter.sweep(Long.MAX_VALUE);
        assertEquals(0, limiter.bucketCount());
        assertTrue(limiter.tryAcquire("c").allowed());
    }
}
