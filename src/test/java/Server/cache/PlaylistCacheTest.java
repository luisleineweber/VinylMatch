package Server.cache;

import Server.PlaylistData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistCacheTest {

    @TempDir
    Path cacheDir;

    @Test
    void storesLooksUpAndRemovesEntries() {
        try (PlaylistCache cache = cache(new MutableClock(), 10, 10, 1_000_000, Duration.ofHours(1))) {
            PlaylistCacheKey key = key("stored");

            assertNull(cache.lookup(key));
            cache.store(key, playlist("Stored"));
            assertEquals("Stored", cache.lookup(key).getPlaylistName());

            cache.remove(key);
            assertNull(cache.lookup(key));
        }
    }

    @Test
    void authenticationChangeInvalidatesBothCacheLevels() throws Exception {
        try (PlaylistCache cache = cache(new MutableClock(), 10, 10, 1_000_000, Duration.ofHours(1))) {
            PlaylistCacheKey key = key("authenticated");
            cache.invalidateForAuthChange("user-a");
            cache.store(key, playlist("Private"));

            cache.invalidateForAuthChange("user-a");
            assertNotNull(cache.lookup(key), "same authentication signature must retain cache data");

            cache.invalidateForAuthChange("user-b");
            assertNull(cache.lookup(key));
            assertEquals(0, snapshotCount());
        }
    }

    @Test
    void boundsMemoryAndDiskByEntryCount() throws Exception {
        MutableClock clock = new MutableClock();
        try (PlaylistCache cache = cache(clock, 1, 2, 1_000_000, Duration.ofHours(1))) {
            PlaylistCacheKey first = key("first");
            PlaylistCacheKey second = key("second");
            PlaylistCacheKey third = key("third");

            cache.store(first, playlist("First"));
            clock.advance(Duration.ofSeconds(1));
            cache.store(second, playlist("Second"));
            clock.advance(Duration.ofSeconds(1));
            cache.store(third, playlist("Third"));

            assertEquals(1, cache.memoryEntryCount());
            assertEquals(2, snapshotCount());
            assertNull(cache.lookup(first), "oldest snapshot must be evicted from the bounded disk cache");
            assertNotNull(cache.lookup(third));
        }
    }

    @Test
    void boundsDiskByTotalBytes() throws Exception {
        try (PlaylistCache cache = cache(new MutableClock(), 1, 10, 1, Duration.ofHours(1))) {
            PlaylistCacheKey key = key("oversized");

            cache.store(key, playlist("A snapshot larger than one byte"));

            assertEquals(0, snapshotCount());
        }
    }

    @Test
    void scheduledCleanupRemovesExpiredMemoryAndDiskEntriesWithoutLookup() throws Exception {
        MutableClock clock = new MutableClock();
        try (PlaylistCache cache = cache(clock, 10, 10, 1_000_000, Duration.ofMillis(20))) {
            cache.store(key("expired"), playlist("Expired"));
            assertEquals(1, cache.memoryEntryCount());
            assertEquals(1, snapshotCount());

            clock.advance(Duration.ofMinutes(2));

            await(() -> cache.memoryEntryCount() == 0 && snapshotCount() == 0);
        }
    }

    @Test
    void closeStopsLifecycleAndRejectsFurtherWrites() throws Exception {
        MutableClock clock = new MutableClock();
        PlaylistCache cache = cache(clock, 10, 10, 1_000_000, Duration.ofMillis(20));
        cache.store(key("retained"), playlist("Retained"));

        cache.close();
        clock.advance(Duration.ofMinutes(2));
        Thread.sleep(80);

        assertEquals(1, snapshotCount(), "closed cache must no longer run scheduled cleanup");
        assertThrows(IllegalStateException.class, () -> cache.store(key("new"), playlist("New")));
    }

    private PlaylistCache cache(Clock clock, int maxMemoryEntries, int maxDiskEntries,
                                long maxDiskBytes, Duration cleanupInterval) {
        return new PlaylistCache(
                new ObjectMapper(),
                cacheDir,
                Duration.ofMinutes(1),
                maxMemoryEntries,
                maxDiskEntries,
                maxDiskBytes,
                cleanupInterval,
                clock
        );
    }

    private long snapshotCount() throws Exception {
        if (!Files.exists(cacheDir)) return 0;
        try (var files = Files.list(cacheDir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json")).count();
        }
    }

    private static PlaylistCacheKey key(String id) {
        return new PlaylistCacheKey(id, "user", 0, 50);
    }

    private static PlaylistData playlist(String name) {
        return new PlaylistData(name, null, null, List.of());
    }

    private static void await(CheckedCondition condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.evaluate()) return;
            Thread.sleep(10);
        }
        assertTrue(condition.evaluate(), "condition was not met before timeout");
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean evaluate() throws Exception;
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant = Instant.parse("2026-07-17T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
