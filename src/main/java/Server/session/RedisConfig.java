package Server.session;

import com.hctamlyniv.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;

/**
 * Redis connection configuration and pool management.
 * Provides thread-safe Redis connections with connection pooling.
 */
public class RedisConfig {
    
    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);
    
    private static JedisPool jedisPool;
    private static volatile boolean initialized = false;
    
    // Configuration defaults
    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 6379;
    private static final int DEFAULT_TIMEOUT_MS = 2000;
    private static final int DEFAULT_MAX_POOL_SIZE = 10;
    
    /**
     * Initialize the Redis connection pool.
     * Safe to call multiple times - only initializes once.
     */
    public static synchronized void initialize() {
        if (initialized) {
            return;
        }
        
        String host = Config.getRedisHost();
        int port = Config.getRedisPort();
        String password = Config.getRedisPassword();
        
        // If Redis host is not set and not localhost, skip Redis initialization
        if (host == null || host.isBlank()) {
            if (isRequired()) {
                throw new IllegalStateException("Redis is required in production but REDIS_HOST is not configured");
            }
            log.info("Redis host not configured; development will use in-memory session storage");
            initialized = true;
            return;
        }
        
        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(DEFAULT_MAX_POOL_SIZE);
            poolConfig.setMaxIdle(5);
            poolConfig.setMinIdle(1);
            poolConfig.setMaxWait(Duration.ofMillis(DEFAULT_TIMEOUT_MS));
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestWhileIdle(true);
            
            if (password != null && !password.isBlank()) {
                jedisPool = new JedisPool(poolConfig, host, port, DEFAULT_TIMEOUT_MS, password);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port);
            }
            
            // Test connection
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
                log.info("Redis connection pool initialized: {}:{}", host, port);
            }
            
            initialized = true;
        } catch (Exception e) {
            log.warn("Failed to initialize Redis connection pool: {}", e.getMessage());
            jedisPool = null;
            initialized = true;
            if (isRequired()) {
                throw new IllegalStateException("Redis is required in production and is unavailable", e);
            }
        }
    }
    
    /**
     * Get a Jedis connection from the pool.
     * Returns null if Redis is not available.
     */
    public static Jedis getJedis() {
        if (!initialized) {
            initialize();
        }
        
        if (jedisPool == null) {
            return null;
        }
        
        try {
            return jedisPool.getResource();
        } catch (Exception e) {
            log.warn("Failed to get Redis connection: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Check if Redis is available and connected.
     */
    public static boolean isAvailable() {
        if (!initialized) {
            initialize();
        }
        
        if (jedisPool == null) {
            return false;
        }
        
        try (Jedis jedis = jedisPool.getResource()) {
            return "PONG".equals(jedis.ping());
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isRequired() {
        return "production".equalsIgnoreCase(Config.getEnvironment());
    }

    /** Prevents a local fallback from silently splitting production session state. */
    public static void assertFallbackAllowed() {
        if (isRequired()) {
            throw new IllegalStateException("Redis unavailable; in-memory fallback is disabled in production");
        }
    }
    
    /**
     * Close the connection pool on shutdown.
     */
    public static void shutdown() {
        if (jedisPool != null) {
            jedisPool.close();
            jedisPool = null;
            initialized = false;
            log.info("Redis connection pool closed");
        }
    }
    
    /**
     * Get session TTL in seconds from configuration.
     */
    public static int getSessionTtlSeconds() {
        return Config.getSessionTtlDays() * 24 * 60 * 60;
    }
}
