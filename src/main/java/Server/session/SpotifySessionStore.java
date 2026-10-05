package Server.session;

import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hybrid session store for Spotify sessions with Redis support.
 * Uses Redis when available, falls back to in-memory storage for local development.
 */
public class SpotifySessionStore {
    
    private static final Logger log = LoggerFactory.getLogger(SpotifySessionStore.class);
    private static final String COOKIE_NAME = "spotify_session";
    private static final int COOKIE_MAX_AGE = 86400 * 30; // 30 days
    private static final String REDIS_KEY_PREFIX = "spotify:session:";
    
    // In-memory fallback storage
    private final Map<String, SpotifySession> localSessions = new ConcurrentHashMap<>();
    
    /**
     * Check if Redis is available for this store.
     */
    private boolean isRedisAvailable() {
        return RedisConfig.isAvailable();
    }
    
    /**
     * Retrieves the Spotify session for the current request, if any.
     */
    public SpotifySession getSession(HttpExchange exchange) {
        String sessionId = CookieUtils.getCookie(exchange, COOKIE_NAME);
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        
        if (isRedisAvailable()) {
            try (Jedis jedis = RedisConfig.getJedis()) {
                if (jedis != null) {
                    String key = REDIS_KEY_PREFIX + sessionId;
                    String json = jedis.get(key);
                    if (json != null) {
                        // Refresh TTL on access
                        jedis.expire(key, RedisConfig.getSessionTtlSeconds());
                        return SessionSerializer.deserializeSpotifySession(json);
                    }
                }
            } catch (Exception e) {
                log.warn("Redis error reading session, falling back to memory: {}", e.getMessage());
            }
        }
        
        if (RedisConfig.isRequired() && RedisConfig.isAvailable()) return null;
        RedisConfig.assertFallbackAllowed();
        return localSessions.get(sessionId);
    }
    
    /**
     * Gets or creates a session for the request.
     */
    public SpotifySession getOrCreateSession(HttpExchange exchange) {
        String sessionId = CookieUtils.getCookie(exchange, COOKIE_NAME);
        if (sessionId != null && !sessionId.isBlank()) {
            SpotifySession existing = getSessionById(sessionId);
            if (existing != null) {
                return existing;
            }
        }
        return createSession(exchange);
    }
    
    /**
     * Creates a new session and sets the session cookie.
     */
    public SpotifySession createSession(HttpExchange exchange) {
        // Remove any existing session
        String oldSessionId = CookieUtils.getCookie(exchange, COOKIE_NAME);
        if (oldSessionId != null) {
            removeSession(oldSessionId);
        }
        
        // Create new session
        String sessionId = UUID.randomUUID().toString();
        SpotifySession session = new SpotifySession(sessionId);
        storeSessionInternal(sessionId, session);
        
        CookieUtils.setCookie(exchange, COOKIE_NAME, sessionId, COOKIE_MAX_AGE, true);
        return session;
    }
    
    /**
     * Destroys the current session.
     */
    public void destroySession(HttpExchange exchange) {
        String sessionId = CookieUtils.getCookie(exchange, COOKIE_NAME);
        if (sessionId != null) {
            removeSession(sessionId);
        }
        CookieUtils.clearCookie(exchange, COOKIE_NAME);
    }
    
    /**
     * Gets a session by its ID (used for OAuth callback).
     */
    public SpotifySession getSessionById(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        
        if (isRedisAvailable()) {
            try (Jedis jedis = RedisConfig.getJedis()) {
                if (jedis != null) {
                    String key = REDIS_KEY_PREFIX + sessionId;
                    String json = jedis.get(key);
                    if (json != null) {
                        return SessionSerializer.deserializeSpotifySession(json);
                    }
                }
            } catch (Exception e) {
                log.warn("Redis error reading session by ID: {}", e.getMessage());
            }
        }
        
        if (RedisConfig.isRequired() && RedisConfig.isAvailable()) return null;
        RedisConfig.assertFallbackAllowed();
        return localSessions.get(sessionId);
    }
    
    /**
     * Stores a session directly (used during OAuth callback).
     */
    public void storeSession(SpotifySession session) {
        if (session != null && session.getSessionId() != null) {
            storeSessionInternal(session.getSessionId(), session);
        }
    }
    
    /**
     * Internal method to store session in Redis or local memory.
     */
    private void storeSessionInternal(String sessionId, SpotifySession session) {
        if (isRedisAvailable()) {
            try (Jedis jedis = RedisConfig.getJedis()) {
                if (jedis != null) {
                    String key = REDIS_KEY_PREFIX + sessionId;
                    String json = SessionSerializer.serializeSpotifySession(session);
                    if (json != null) {
                        jedis.setex(key, RedisConfig.getSessionTtlSeconds(), json);
                        return;
                    }
                }
            } catch (Exception e) {
                log.warn("Redis error storing session, falling back to memory: {}", e.getMessage());
            }
        }
        
        RedisConfig.assertFallbackAllowed();
        localSessions.put(sessionId, session);
    }
    
    /**
     * Remove a session from both Redis and local memory.
     */
    private void removeSession(String sessionId) {
        if (isRedisAvailable()) {
            try (Jedis jedis = RedisConfig.getJedis()) {
                if (jedis != null) {
                    jedis.del(REDIS_KEY_PREFIX + sessionId);
                }
            } catch (Exception e) {
                log.warn("Redis error removing session: {}", e.getMessage());
            }
        }
        
        if (!RedisConfig.isAvailable()) RedisConfig.assertFallbackAllowed();
        localSessions.remove(sessionId);
    }
}
