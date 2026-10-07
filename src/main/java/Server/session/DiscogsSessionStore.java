package Server.session;

import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hybrid session store for Discogs sessions with Redis support.
 * Uses Redis when available, falls back to in-memory storage for local development.
 * 
 * Security features:
 * - Tokens are encrypted at rest using AES-256-GCM
 * - HttpOnly, Secure, and SameSite cookie flags
 * - Session deduplication by username
 */
public class DiscogsSessionStore {
    
    private static final Logger log = LoggerFactory.getLogger(DiscogsSessionStore.class);
    private static final String COOKIE_NAME = "discogs_session";
    private static final int COOKIE_MAX_AGE = 86400 * 30; // 30 days
    private static final String REDIS_KEY_PREFIX = "discogs:session:";
    
    // In-memory fallback storage
    private final Map<String, DiscogsSession> localSessions = new ConcurrentHashMap<>();
    
    // Token encryption for secure storage
    private final TokenEncryption encryption = new TokenEncryption();
    
    /**
     * Check if Redis is available for this store.
     */
    private boolean isRedisAvailable() {
        return RedisConfig.isAvailable();
    }
    
    /**
     * Retrieves the Discogs session for the current request, if any.
     * The token is automatically decrypted when retrieved.
     */
    public DiscogsSession getSession(HttpExchange exchange) {
        String sessionId = CookieUtils.getCookie(exchange, COOKIE_NAME);
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        
        DiscogsSession encryptedSession = null;
        
        if (isRedisAvailable()) {
            try (Jedis jedis = RedisConfig.getJedis()) {
                if (jedis != null) {
                    String key = REDIS_KEY_PREFIX + sessionId;
                    String json = jedis.get(key);
                    if (json != null) {
                        // Refresh TTL on access
                        jedis.expire(key, RedisConfig.getSessionTtlSeconds());
                        encryptedSession = SessionSerializer.deserializeDiscogsSession(json);
                    }
                }
            } catch (Exception e) {
                log.warn("Redis error reading session, falling back to memory: {}", e.getMessage());
            }
        }
        
        if (encryptedSession == null) {
            if (RedisConfig.isRequired() && RedisConfig.isAvailable()) return null;
            RedisConfig.assertFallbackAllowed();
            encryptedSession = localSessions.get(sessionId);
        }
        
        if (encryptedSession == null) {
            return null;
        }
        
        // Decrypt the token
        try {
            String decryptedToken = decryptSecret(encryptedSession.token());
            String decryptedTokenSecret = decryptSecret(encryptedSession.tokenSecret());
            return new DiscogsSession(
                encryptedSession.sessionId(),
                decryptedToken,
                decryptedTokenSecret,
                encryptedSession.userAgent(),
                encryptedSession.username(),
                encryptedSession.displayName()
            );
        } catch (Exception e) {
            log.error("Failed to decrypt Discogs session secrets - session may be corrupted or key changed: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Creates a new session and sets the session cookie.
     * The token is automatically encrypted before storage.
     */
    public DiscogsSession createSession(HttpExchange exchange, String token, String userAgent, String username, String displayName) {
        return createSession(exchange, token, null, userAgent, username, displayName);
    }

    /**
     * Creates a new session and sets the session cookie.
     * Stores both user token and optional OAuth token secret encrypted at rest.
     */
    public DiscogsSession createSession(HttpExchange exchange, String token, String tokenSecret, String userAgent, String username, String displayName) {
        // Remove any existing session for this user
        String oldSessionId = CookieUtils.getCookie(exchange, COOKIE_NAME);
        if (oldSessionId != null) {
            removeSession(oldSessionId);
        }
        
        // Also remove any existing session with same username
        removeSessionByUsername(username);
        
        // Encrypt the token for storage
        String encryptedToken;
        String encryptedTokenSecret;
        try {
            encryptedToken = encryption.encrypt(token);
        } catch (Exception e) {
            log.error("Failed to encrypt token: {}", e.getMessage());
            encryptedToken = token;
        }
        try {
            encryptedTokenSecret = encryption.encrypt(tokenSecret);
        } catch (Exception e) {
            log.error("Failed to encrypt token secret: {}", e.getMessage());
            encryptedTokenSecret = tokenSecret;
        }
        
        // Create new session with encrypted token
        String sessionId = UUID.randomUUID().toString();
        DiscogsSession session = new DiscogsSession(sessionId, encryptedToken, encryptedTokenSecret, userAgent, username, displayName);
        storeSessionInternal(sessionId, session);
        
        // Set cookie with security flags
        CookieUtils.setCookie(exchange, COOKIE_NAME, sessionId, COOKIE_MAX_AGE, true);
        
        // Return session with plaintext secrets for immediate use
        return new DiscogsSession(sessionId, token, tokenSecret, userAgent, username, displayName);
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
     * Internal method to store session in Redis or local memory.
     */
    private void storeSessionInternal(String sessionId, DiscogsSession session) {
        if (isRedisAvailable()) {
            try (Jedis jedis = RedisConfig.getJedis()) {
                if (jedis != null) {
                    String key = REDIS_KEY_PREFIX + sessionId;
                    String json = SessionSerializer.serializeDiscogsSession(session);
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
    
    /**
     * Remove session by username (for preventing duplicate logins).
     * Note: This is inefficient in Redis and may iterate over many keys.
     */
    private void removeSessionByUsername(String username) {
        if (username == null) return;
        
        if (isRedisAvailable()) {
            try (Jedis jedis = RedisConfig.getJedis()) {
                if (jedis != null) {
                    // Scan for sessions with matching username
                    // Note: In production with many users, this should be optimized
                    for (String key : jedis.keys(REDIS_KEY_PREFIX + "*")) {
                        String json = jedis.get(key);
                        if (json != null) {
                            DiscogsSession session = SessionSerializer.deserializeDiscogsSession(json);
                            if (session != null && username.equals(session.username())) {
                                jedis.del(key);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Redis error removing session by username: {}", e.getMessage());
            }
        }
        
        if (!RedisConfig.isAvailable()) RedisConfig.assertFallbackAllowed();
        localSessions.entrySet().removeIf(entry -> username.equals(entry.getValue().username()));
    }

    private String decryptSecret(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return encryption.decrypt(value);
        } catch (Exception e) {
            // Backward compatibility for sessions written before encryption was active.
            return value;
        }
    }
}
