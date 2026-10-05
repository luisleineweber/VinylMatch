package Server.cache;

import Server.PlaylistData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded two-level local cache for playlist data: memory plus disk snapshots.
 * Entries are instance-local and are not shared or coordinated across servers.
 */
public class PlaylistCache implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PlaylistCache.class);
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration DEFAULT_CLEANUP_INTERVAL = Duration.ofMinutes(1);
    private static final Path DEFAULT_CACHE_DIR = Path.of("cache", "playlists");
    private static final int DEFAULT_MAX_MEMORY_ENTRIES = 100;
    private static final int DEFAULT_MAX_DISK_ENTRIES = 200;
    private static final long DEFAULT_MAX_DISK_BYTES = 100L * 1024 * 1024;
    private static final HexFormat HEX_FORMAT = HexFormat.of();
    private static final ScheduledThreadPoolExecutor CLEANER = createCleaner();

    private final ObjectMapper mapper;
    private final Path cacheDir;
    private final Duration cacheTtl;
    private final int maxMemoryEntries;
    private final int maxDiskEntries;
    private final long maxDiskBytes;
    private final Clock clock;
    private final Object memoryLock = new Object();
    private final Object diskLock = new Object();
    private final Map<PlaylistCacheKey, PlaylistCacheEntry> memoryCache =
            new LinkedHashMap<>(16, 0.75f, true);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledFuture<?> cleanupFuture;
    private volatile String lastAuthSignature;

    public PlaylistCache(ObjectMapper mapper) {
        this(
                mapper,
                DEFAULT_CACHE_DIR,
                durationFromEnv("PLAYLIST_CACHE_TTL_SECONDS", DEFAULT_CACHE_TTL),
                intFromEnv("PLAYLIST_CACHE_MAX_MEMORY_ENTRIES", DEFAULT_MAX_MEMORY_ENTRIES),
                intFromEnv("PLAYLIST_CACHE_MAX_DISK_ENTRIES", DEFAULT_MAX_DISK_ENTRIES),
                longFromEnv("PLAYLIST_CACHE_MAX_DISK_BYTES", DEFAULT_MAX_DISK_BYTES),
                durationFromEnv("PLAYLIST_CACHE_CLEANUP_SECONDS", DEFAULT_CLEANUP_INTERVAL),
                Clock.systemUTC()
        );
    }

    PlaylistCache(ObjectMapper mapper, Path cacheDir, Duration cacheTtl,
                  int maxMemoryEntries, int maxDiskEntries, long maxDiskBytes,
                  Duration cleanupInterval, Clock clock) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
        this.cacheTtl = requirePositive(cacheTtl, "cacheTtl");
        this.maxMemoryEntries = requirePositive(maxMemoryEntries, "maxMemoryEntries");
        this.maxDiskEntries = requirePositive(maxDiskEntries, "maxDiskEntries");
        this.maxDiskBytes = requirePositive(maxDiskBytes, "maxDiskBytes");
        this.clock = Objects.requireNonNull(clock, "clock");
        Duration safeCleanupInterval = requirePositive(cleanupInterval, "cleanupInterval");

        ensureCacheDir();
        cleanupExpiredAndEnforceBounds();
        CleanupTask cleanupTask = new CleanupTask(this);
        this.cleanupFuture = CLEANER.scheduleWithFixedDelay(
                cleanupTask,
                safeCleanupInterval.toMillis(),
                safeCleanupInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
        cleanupTask.setFuture(cleanupFuture);
    }

    /** Looks up a playlist from memory or the local disk snapshot cache. */
    public PlaylistData lookup(PlaylistCacheKey key) {
        ensureOpen();
        Objects.requireNonNull(key, "key");
        long now = clock.millis();

        synchronized (memoryLock) {
            PlaylistCacheEntry entry = memoryCache.get(key);
            if (entry != null && !entry.isExpired(now)) {
                return entry.playlistData();
            }
            if (entry != null) {
                memoryCache.remove(key);
            }
        }

        PlaylistCacheEntry snapshotEntry = readSnapshot(key, now);
        if (snapshotEntry == null) {
            return null;
        }
        putMemory(key, snapshotEntry);
        return snapshotEntry.playlistData();
    }

    /** Stores playlist data in both bounded local cache levels. */
    public void store(PlaylistCacheKey key, PlaylistData playlistData) {
        ensureOpen();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(playlistData, "playlistData");
        PlaylistCacheEntry entry = new PlaylistCacheEntry(
                playlistData,
                clock.millis() + cacheTtl.toMillis()
        );
        putMemory(key, entry);
        writeSnapshot(key, entry);
    }

    /** Removes a specific entry from both local cache levels. */
    public void remove(PlaylistCacheKey key) {
        ensureOpen();
        Objects.requireNonNull(key, "key");
        synchronized (memoryLock) {
            memoryCache.remove(key);
        }
        deleteSnapshot(key);
    }

    /** Invalidates all local cache data when authentication changes. */
    public void invalidateForAuthChange(String newSignature) {
        ensureOpen();
        String normalized = newSignature == null ? "" : newSignature;
        synchronized (memoryLock) {
            if (Objects.equals(lastAuthSignature, normalized)) {
                return;
            }
            memoryCache.clear();
            lastAuthSignature = normalized;
        }
        purgeAllSnapshots();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            cleanupFuture.cancel(false);
        }
    }

    int memoryEntryCount() {
        synchronized (memoryLock) {
            return memoryCache.size();
        }
    }

    private void putMemory(PlaylistCacheKey key, PlaylistCacheEntry entry) {
        synchronized (memoryLock) {
            memoryCache.put(key, entry);
            while (memoryCache.size() > maxMemoryEntries) {
                PlaylistCacheKey eldest = memoryCache.keySet().iterator().next();
                memoryCache.remove(eldest);
            }
        }
    }

    private void cleanupExpiredAndEnforceBounds() {
        if (closed.get()) return;
        long now = clock.millis();
        synchronized (memoryLock) {
            memoryCache.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        }
        synchronized (diskLock) {
            cleanupDisk(now);
        }
    }

    private PlaylistCacheEntry readSnapshot(PlaylistCacheKey key, long now) {
        synchronized (diskLock) {
            Path path = snapshotPath(key);
            if (!Files.isRegularFile(path)) return null;
            try {
                PlaylistCacheSnapshot snapshot = mapper.readValue(Files.readAllBytes(path), PlaylistCacheSnapshot.class);
                if (snapshot.expiresAtMillis() <= now) {
                    Files.deleteIfExists(path);
                    return null;
                }
                Files.setLastModifiedTime(path, FileTime.fromMillis(clock.millis()));
                return new PlaylistCacheEntry(snapshot.playlistData(), snapshot.expiresAtMillis());
            } catch (IOException e) {
                deleteQuietly(path, "unreadable cache snapshot");
                log.warn("Failed to read cache snapshot {}: {}", path.getFileName(), e.getMessage());
                return null;
            }
        }
    }

    private void writeSnapshot(PlaylistCacheKey key, PlaylistCacheEntry entry) {
        synchronized (diskLock) {
            Path target = snapshotPath(key);
            Path temporary = null;
            try {
                Files.createDirectories(cacheDir);
                temporary = Files.createTempFile(cacheDir, ".playlist-", ".tmp");
                PlaylistCacheSnapshot snapshot = new PlaylistCacheSnapshot(entry.playlistData(), entry.expiresAtMillis());
                Files.write(temporary, mapper.writeValueAsBytes(snapshot));
                moveReplacing(temporary, target);
                Files.setLastModifiedTime(target, FileTime.fromMillis(clock.millis()));
                cleanupDisk(clock.millis());
            } catch (IOException e) {
                log.warn("Failed to write cache snapshot: {}", e.getMessage());
                if (temporary != null) deleteQuietly(temporary, "temporary cache snapshot");
            }
        }
    }

    private void cleanupDisk(long now) {
        List<SnapshotFile> snapshots = new ArrayList<>();
        long totalBytes = 0;
        try {
            if (!Files.isDirectory(cacheDir)) return;
            try (var files = Files.list(cacheDir)) {
                for (Path path : files.filter(this::isSnapshotFile).toList()) {
                    try {
                        long size = Files.size(path);
                        if (size > maxDiskBytes) {
                            Files.deleteIfExists(path);
                            continue;
                        }
                        PlaylistCacheSnapshot snapshot = mapper.readValue(Files.readAllBytes(path), PlaylistCacheSnapshot.class);
                        if (snapshot.expiresAtMillis() <= now) {
                            Files.deleteIfExists(path);
                            continue;
                        }
                        SnapshotFile file = new SnapshotFile(path, size, Files.getLastModifiedTime(path).toMillis());
                        snapshots.add(file);
                        totalBytes += size;
                    } catch (IOException e) {
                        deleteQuietly(path, "invalid cache snapshot");
                    }
                }
            }

            snapshots.sort(Comparator.comparingLong(SnapshotFile::lastModifiedMillis)
                    .thenComparing(file -> file.path().getFileName().toString()));
            int index = 0;
            while ((snapshots.size() - index > maxDiskEntries || totalBytes > maxDiskBytes)
                    && index < snapshots.size()) {
                SnapshotFile oldest = snapshots.get(index++);
                if (Files.deleteIfExists(oldest.path())) {
                    totalBytes -= oldest.size();
                }
            }
        } catch (IOException e) {
            log.warn("Failed to clean playlist cache directory: {}", e.getMessage());
        }
    }

    private void deleteSnapshot(PlaylistCacheKey key) {
        synchronized (diskLock) {
            deleteQuietly(snapshotPath(key), "cache snapshot");
        }
    }

    private void purgeAllSnapshots() {
        synchronized (diskLock) {
            try {
                if (!Files.isDirectory(cacheDir)) return;
                try (var files = Files.list(cacheDir)) {
                    files.filter(path -> isSnapshotFile(path) || path.getFileName().toString().endsWith(".tmp"))
                            .forEach(path -> deleteQuietly(path, "cache snapshot"));
                }
            } catch (IOException e) {
                log.warn("Failed to purge playlist cache directory: {}", e.getMessage());
            }
        }
    }

    private void ensureCacheDir() {
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            log.warn("Failed to create cache directory: {}", e.getMessage());
        }
    }

    private boolean isSnapshotFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".json");
    }

    private Path snapshotPath(PlaylistCacheKey key) {
        return cacheDir.resolve(hashCacheKey(key) + ".json");
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path, String description) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete {} {}: {}", description, path.getFileName(), e.getMessage());
        }
    }

    private static String hashCacheKey(PlaylistCacheKey key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update((key.playlistId() == null ? "" : key.playlistId()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update((key.userSignature() == null ? "" : key.userSignature()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(Integer.toString(key.offset()).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(Integer.toString(key.limit()).getBytes(StandardCharsets.UTF_8));
            return HEX_FORMAT.formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("PlaylistCache is closed");
    }

    private static ScheduledThreadPoolExecutor createCleaner() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "playlist-cache-cleaner");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private static int intFromEnv(String name, int fallback) {
        long value = longFromEnv(name, fallback);
        return value > Integer.MAX_VALUE ? fallback : (int) value;
    }

    private static long longFromEnv(String name, long fallback) {
        String raw = System.getenv(name);
        if (raw == null || raw.isBlank()) return fallback;
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException e) {
            log.warn("Ignoring invalid {} value", name);
            return fallback;
        }
    }

    private static Duration durationFromEnv(String name, Duration fallback) {
        return Duration.ofSeconds(longFromEnv(name, fallback.toSeconds()));
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private record SnapshotFile(Path path, long size, long lastModifiedMillis) {}

    private static final class CleanupTask implements Runnable {
        private final WeakReference<PlaylistCache> cacheReference;
        private volatile ScheduledFuture<?> future;

        private CleanupTask(PlaylistCache cache) {
            this.cacheReference = new WeakReference<>(cache);
        }

        private void setFuture(ScheduledFuture<?> future) {
            this.future = future;
        }

        @Override
        public void run() {
            PlaylistCache cache = cacheReference.get();
            if (cache == null) {
                ScheduledFuture<?> scheduled = future;
                if (scheduled != null) scheduled.cancel(false);
                return;
            }
            try {
                cache.cleanupExpiredAndEnforceBounds();
            } catch (RuntimeException e) {
                log.warn("Playlist cache cleanup failed: {}", e.getMessage());
            }
        }
    }
}
