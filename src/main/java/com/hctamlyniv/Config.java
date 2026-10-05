package com.hctamlyniv;

import java.io.BufferedReader;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Centralized configuration management.
 *
 * VinylMatch reads config from (highest precedence first):
 * - Process environment variables
 * - Optional local ".env" file (project root, not committed)
 * - Optional config properties file (recommended for local installs)
 * - Legacy properties files for backwards compatibility
 *
 * Required keys:
 * - SPOTIFY_CLIENT_ID
 * - SPOTIFY_CLIENT_SECRET
 *
 * Optional keys:
 * - SPOTIFY_REDIRECT_URI
 * - DISCOGS_TOKEN
 * - DISCOGS_USER_AGENT
 * - PUBLIC_BASE_URL
 * - PORT
 * - CORS_ALLOWED_ORIGINS
 *
 * Template: see "config/env.example"
 */
public final class Config {

    private static final Logger log = LoggerFactory.getLogger(Config.class);

    private static final Set<String> KNOWN_KEYS = Set.of(
            "SPOTIFY_CLIENT_ID",
            "SPOTIFY_CLIENT_SECRET",
            "SPOTIFY_REDIRECT_URI",
            "DISCOGS_TOKEN",
            "DISCOGS_USER_AGENT",
            "DISCOGS_CONSUMER_KEY",
            "DISCOGS_CONSUMER_SECRET",
            "DISCOGS_REDIRECT_URI",
            "PUBLIC_BASE_URL",
            "PORT",
            "CORS_ALLOWED_ORIGINS",
            "VINYLMATCH_CONFIG",
            "REDIS_HOST",
            "REDIS_PORT",
            "REDIS_PASSWORD",
            "SESSION_TTL_DAYS",
            "VINYLMATCH_MASTER_KEY",
            "LOG_LEVEL",
            "ENVIRONMENT",
            "ADMIN_USER_IDS"
    );

    // Cache resolved values to avoid repeated IO/env lookups
    private static final Map<String, String> cache = new ConcurrentHashMap<>();
    private static volatile boolean initialized = false;

    // Debug-ish info for printStatus()
    private static final List<String> loadedSources = new ArrayList<>();

    private Config() {}

    private static synchronized void initIfNeeded() {
        if (initialized) return;

        // 1) Load from optional files (lowest precedence)
        loadFromDotEnvIfPresent(Paths.get(".env"));
        for (Path p : candidatePropertiesFiles()) {
            loadFromPropertiesIfPresent(p);
        }

        // 2) Environment variables override everything
        for (String key : KNOWN_KEYS) {
            if ("VINYLMATCH_CONFIG".equals(key)) continue;
            loadEnvOverride(key);
        }

        initialized = true;
    }

    private static void loadEnvOverride(String key) {
        String value = System.getenv(key);
        if (value != null && !value.isBlank()) {
            cache.put(key, value.trim());
        }
    }

    private static void loadFromDotEnvIfPresent(Path dotEnv) {
        if (!Files.exists(dotEnv) || !Files.isRegularFile(dotEnv)) return;

        try (BufferedReader reader = Files.newBufferedReader(dotEnv, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                int idx = line.indexOf('=');
                if (idx <= 0) continue;

                String key = line.substring(0, idx).trim();
                if (!KNOWN_KEYS.contains(key)) continue;

                String value = line.substring(idx + 1).trim();
                value = stripOptionalQuotes(value);
                if (!value.isBlank()) {
                    // only set if not already set by a higher precedence source (env override happens later)
                    cache.putIfAbsent(key, value);
                }
            }

            loadedSources.add(".env");
        } catch (Exception e) {
            log.warn("Failed to read .env: {}", e.getMessage());
        }
    }

    private static void loadFromPropertiesIfPresent(Path p) {
        if (p == null) return;
        if (!Files.exists(p) || !Files.isRegularFile(p)) return;

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(p)) {
            props.load(in);
            for (String key : KNOWN_KEYS) {
                if ("VINYLMATCH_CONFIG".equals(key)) continue;
                String v = props.getProperty(key);
                if (v != null && !v.isBlank()) {
                    cache.putIfAbsent(key, v.trim());
                }
            }
            loadedSources.add(p.toAbsolutePath().normalize().toString());
        } catch (Exception e) {
            log.warn("Failed to read properties from {}: {}", p, e.getMessage());
        }
    }

    private static List<Path> candidatePropertiesFiles() {
        List<Path> candidates = new ArrayList<>();

        // Explicit override path
        String explicit = System.getenv("VINYLMATCH_CONFIG");
        if (explicit != null && !explicit.isBlank()) {
            candidates.add(Paths.get(explicit.trim()));
            return candidates;
        }

        // Recommended OS-specific config location
        Path osConfig = defaultOsConfigFile();
        if (osConfig != null) {
            candidates.add(osConfig);
        }

        // Legacy locations (backwards compatibility)
        candidates.add(Path.of(System.getProperty("user.home"), "VinylMatch", "spotify.properties"));
        candidates.add(Paths.get("spotify.properties"));

        return candidates;
    }

    private static Path defaultOsConfigFile() {
        String os = System.getProperty("os.name", "").toLowerCase();

        // Windows: %APPDATA%\VinylMatch\config.properties
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isBlank()) {
                return Path.of(appData, "VinylMatch", "config.properties");
            }
        }

        // Linux/macOS: $XDG_CONFIG_HOME/vinylmatch/config.properties or ~/.config/vinylmatch/config.properties
        String xdg = System.getenv("XDG_CONFIG_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, "vinylmatch", "config.properties");
        }

        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return null;
        return Path.of(home, ".config", "vinylmatch", "config.properties");
    }

    private static String stripOptionalQuotes(String value) {
        if (value == null) return null;
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    private static String get(String key) {
        initIfNeeded();
        return cache.get(key);
    }

    // =========================================================================
    // Spotify Configuration
    // =========================================================================

    public static String getSpotifyClientId() {
        return get("SPOTIFY_CLIENT_ID");
    }

    public static String getSpotifyClientSecret() {
        return get("SPOTIFY_CLIENT_SECRET");
    }

    public static String getSpotifyRedirectUri() {
        return get("SPOTIFY_REDIRECT_URI");
    }

    // =========================================================================
    // Discogs Configuration
    // =========================================================================

    public static String getDiscogsToken() {
        return get("DISCOGS_TOKEN");
    }

    public static String getDiscogsUserAgent() {
        return get("DISCOGS_USER_AGENT");
    }

    public static String getDiscogsConsumerKey() {
        return get("DISCOGS_CONSUMER_KEY");
    }

    public static String getDiscogsConsumerSecret() {
        return get("DISCOGS_CONSUMER_SECRET");
    }

    public static String getDiscogsRedirectUri() {
        return get("DISCOGS_REDIRECT_URI");
    }

    // =========================================================================
    // Server Configuration
    // =========================================================================

    public static String getPublicBaseUrl() {
        return get("PUBLIC_BASE_URL");
    }

    public static int getPort() {
        String port = get("PORT");
        if (port == null || port.isBlank()) {
            return 8888;
        }
        try {
            return Integer.parseInt(port);
        } catch (NumberFormatException e) {
            return 8888;
        }
    }

    public static String getCorsAllowedOrigins() {
        return get("CORS_ALLOWED_ORIGINS");
    }

    // =========================================================================
    // Redis Configuration
    // =========================================================================

    public static String getRedisHost() {
        return get("REDIS_HOST");
    }

    public static int getRedisPort() {
        String port = get("REDIS_PORT");
        if (port == null || port.isBlank()) {
            return 6379;
        }
        try {
            return Integer.parseInt(port);
        } catch (NumberFormatException e) {
            return 6379;
        }
    }

    public static String getRedisPassword() {
        return get("REDIS_PASSWORD");
    }

    // =========================================================================
    // Session Configuration
    // =========================================================================

    public static int getSessionTtlDays() {
        String days = get("SESSION_TTL_DAYS");
        if (days == null || days.isBlank()) {
            return 30;
        }
        try {
            return Integer.parseInt(days);
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    public static String getMasterKey() {
        return get("VINYLMATCH_MASTER_KEY");
    }

    // =========================================================================
    // Logging & Monitoring
    // =========================================================================

    public static String getLogLevel() {
        String level = get("LOG_LEVEL");
        return level != null && !level.isBlank() ? level : "INFO";
    }

    public static String getEnvironment() {
        String env = get("ENVIRONMENT");
        return env != null && !env.isBlank() ? env : "development";
    }

    public static java.util.Set<String> getAdminUserIds() {
        String value = get("ADMIN_USER_IDS");
        if (value == null || value.isBlank()) {
            return java.util.Collections.emptySet();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // =========================================================================
    // Validation / Debugging
    // =========================================================================

    public static boolean hasSpotifyCredentials() {
        String clientId = getSpotifyClientId();
        String clientSecret = getSpotifyClientSecret();
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }

    public static boolean hasDiscogsToken() {
        String token = getDiscogsToken();
        return token != null && !token.isBlank();
    }

    /**
     * Prints configuration status (for debugging, hides secrets).
     */
    public static void printStatus() {
        initIfNeeded();
        log.info("[Config] Status:");
        log.info("  Sources: {}", (loadedSources.isEmpty() ? "[env only]" : String.join(", ", loadedSources) + " (+ env overrides)"));
        log.info("  SPOTIFY_CLIENT_ID: {}", (getSpotifyClientId() != null ? "[set]" : "[NOT SET - REQUIRED]"));
        log.info("  SPOTIFY_CLIENT_SECRET: {}", (getSpotifyClientSecret() != null ? "[set]" : "[NOT SET - REQUIRED]"));
        log.info("  SPOTIFY_REDIRECT_URI: {}", (getSpotifyRedirectUri() != null ? getSpotifyRedirectUri() : "[using default]"));
        log.info("  DISCOGS_TOKEN: {}", (getDiscogsToken() != null ? "[set]" : "[not set - recommended]"));
        log.info("  DISCOGS_USER_AGENT: {}", (getDiscogsUserAgent() != null ? getDiscogsUserAgent() : "[using default]"));
        log.info("  DISCOGS_CONSUMER_KEY: {}", (getDiscogsConsumerKey() != null ? "[set]" : "[not set]"));
        log.info("  DISCOGS_CONSUMER_SECRET: {}", (getDiscogsConsumerSecret() != null ? "[set]" : "[not set]"));
        log.info("  DISCOGS_REDIRECT_URI: {}", (getDiscogsRedirectUri() != null ? getDiscogsRedirectUri() : "[using default]"));
        log.info("  PUBLIC_BASE_URL: {}", (getPublicBaseUrl() != null ? getPublicBaseUrl() : "[not set]"));
        log.info("  PORT: {}", getPort());

        if (!hasSpotifyCredentials()) {
            log.warn("[Config] WARNING: Spotify credentials not configured!");
            log.warn("[Config] Set SPOTIFY_CLIENT_ID and SPOTIFY_CLIENT_SECRET as environment variables, or create a .env file.");
            log.warn("[Config] Template: config/env.example");
        }
    }
}
