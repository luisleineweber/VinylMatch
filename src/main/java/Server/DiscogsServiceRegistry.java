package Server;

import com.hctamlyniv.DiscogsService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Bounded, expiring Discogs client registry whose keys never retain raw credentials. */
public final class DiscogsServiceRegistry {

    private final int maxEntries;
    private final long ttlMillis;
    private final Clock clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);

    public DiscogsServiceRegistry() {
        this(128, Duration.ofMinutes(15), Clock.systemUTC());
    }

    DiscogsServiceRegistry(int maxEntries, Duration ttl, Clock clock) {
        this.maxEntries = Math.max(1, maxEntries);
        this.ttlMillis = Math.max(1, ttl.toMillis());
        this.clock = clock;
    }

    public synchronized DiscogsService get(String token, String tokenSecret, String userAgent,
                                           Supplier<DiscogsService> factory) {
        long now = clock.millis();
        sweep(now);
        String key = fingerprint(token, tokenSecret, userAgent);
        Entry current = entries.get(key);
        if (current != null) return current.service();
        DiscogsService created = factory.get();
        entries.put(key, new Entry(created, now + ttlMillis));
        while (entries.size() > maxEntries) {
            String eldest = entries.keySet().iterator().next();
            entries.remove(eldest);
        }
        return created;
    }

    public synchronized void invalidate(String token, String tokenSecret, String userAgent) {
        entries.remove(fingerprint(token, tokenSecret, userAgent));
    }

    synchronized int size() { return entries.size(); }

    private void sweep(long now) {
        entries.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
    }

    private static String fingerprint(String token, String secret, String userAgent) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, token);
            update(digest, secret);
            update(digest, userAgent);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private record Entry(DiscogsService service, long expiresAt) {}
}
