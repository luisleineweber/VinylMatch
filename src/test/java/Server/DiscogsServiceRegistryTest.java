package Server;

import com.hctamlyniv.DiscogsService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class DiscogsServiceRegistryTest {

    @Test
    void reusesServicesAndBoundsCredentialFingerprints() {
        DiscogsServiceRegistry registry = new DiscogsServiceRegistry(2, Duration.ofMinutes(5), Clock.systemUTC());
        DiscogsService first = registry.get("secret-one", null, "ua", () -> new DiscogsService(null, "ua"));
        assertSame(first, registry.get("secret-one", null, "ua", () -> fail("factory called twice")));

        registry.get("secret-two", null, "ua", () -> new DiscogsService(null, "ua"));
        registry.get("secret-three", null, "ua", () -> new DiscogsService(null, "ua"));
        assertEquals(2, registry.size());
    }

    @Test
    void expiresAndInvalidatesServices() {
        Clock expiredClock = Clock.fixed(Instant.ofEpochMilli(10_000), ZoneOffset.UTC);
        DiscogsServiceRegistry registry = new DiscogsServiceRegistry(2, Duration.ofMillis(1), expiredClock);
        registry.get("token", "secret", "ua", () -> new DiscogsService(null, "ua"));
        registry.invalidate("token", "secret", "ua");
        assertEquals(0, registry.size());
    }
}
