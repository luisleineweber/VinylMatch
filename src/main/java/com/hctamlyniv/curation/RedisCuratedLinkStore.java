package com.hctamlyniv.curation;

import Server.session.RedisConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hctamlyniv.discogs.model.CuratedLink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Transaction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RedisCuratedLinkStore implements CuratedLinkStore {

    private static final Logger log = LoggerFactory.getLogger(RedisCuratedLinkStore.class);
    private static final String KEY_PREFIX = "curated:";
    private static final String BARCODE_PREFIX = "curated:barcode:";
    private static final String AUDIT_PREFIX = "curated:audit:";
    private static final String INDEX_KEY = "curated:index";

    private final ObjectMapper mapper;
    private final Object localLock = new Object();
    private final Map<String, CuratedLink> localFallback = new ConcurrentHashMap<>();
    private final Map<String, List<CurationAuditEvent>> localHistory = new ConcurrentHashMap<>();

    public RedisCuratedLinkStore(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    private boolean isRedisAvailable() {
        return RedisConfig.isAvailable();
    }

    @Override
    public Optional<CuratedLink> find(String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isBlank()) return Optional.empty();
        if (isRedisAvailable()) {
            try (Jedis jedis = RedisConfig.getJedis()) {
                if (jedis != null) {
                    String json = jedis.get(KEY_PREFIX + normalizedKey);
                    return json == null ? Optional.empty() : Optional.of(mapper.readValue(json, CuratedLink.class));
                }
            } catch (Exception e) {
                log.warn("Redis error reading curated link: {}", e.getMessage());
            }
        }
        RedisConfig.assertFallbackAllowed();
        return Optional.ofNullable(localFallback.get(normalizedKey));
    }

    @Override
    public Optional<CuratedLink> findByBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) return Optional.empty();
        if (isRedisAvailable()) {
            try (Jedis jedis = RedisConfig.getJedis()) {
                if (jedis != null) {
                    String normalizedKey = jedis.get(BARCODE_PREFIX + barcode);
                    return normalizedKey == null ? Optional.empty() : find(normalizedKey);
                }
            } catch (Exception e) {
                log.warn("Redis error reading curated link by barcode: {}", e.getMessage());
            }
        }
        RedisConfig.assertFallbackAllowed();
        return localFallback.values().stream().filter(link -> barcode.equals(link.barcode())).findFirst();
    }

    @Override
    public void save(CuratedLink link) {
        long currentVersion = find(link.cacheKey()).map(CuratedLink::version).orElse(0L);
        save(link, currentVersion, new CurationAuditContext("system", "Legacy save operation", "legacy"));
    }

    @Override
    public CuratedLink save(CuratedLink link, long expectedVersion, CurationAuditContext context) {
        validateLink(link);
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        if (isRedisAvailable()) {
            try {
                return saveRedis(link, expectedVersion, context);
            } catch (CurationVersionConflictException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Redis error saving curated link: {}", e.getMessage());
            }
        }
        RedisConfig.assertFallbackAllowed();
        return saveLocal(link, expectedVersion, context);
    }

    @Override
    public List<CurationAuditEvent> history(String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isBlank()) return List.of();
        if (isRedisAvailable()) {
            try (Jedis jedis = RedisConfig.getJedis()) {
                if (jedis != null) {
                    List<CurationAuditEvent> events = new ArrayList<>();
                    for (String json : jedis.lrange(AUDIT_PREFIX + normalizedKey, 0, -1)) {
                        events.add(mapper.readValue(json, CurationAuditEvent.class));
                    }
                    if (events.isEmpty()) {
                        String currentJson = jedis.get(KEY_PREFIX + normalizedKey);
                        if (currentJson != null) events.add(legacyEvent(mapper.readValue(currentJson, CuratedLink.class)));
                    }
                    return List.copyOf(events);
                }
            } catch (Exception e) {
                log.warn("Redis error reading curation history: {}", e.getMessage());
            }
        }
        RedisConfig.assertFallbackAllowed();
        synchronized (localLock) {
            List<CurationAuditEvent> events = localHistory.get(normalizedKey);
            if (events != null && !events.isEmpty()) return List.copyOf(events);
            CuratedLink current = localFallback.get(normalizedKey);
            return current == null ? List.of() : List.of(legacyEvent(current));
        }
    }

    @Override
    public CuratedLink rollback(String normalizedKey, long targetVersion, long expectedVersion,
                                CurationAuditContext context) {
        if (targetVersion < 1) throw new IllegalArgumentException("targetVersion must be positive");
        CuratedLink target = history(normalizedKey).stream()
                .map(CurationAuditEvent::newValue)
                .filter(value -> value != null && value.version() == targetVersion)
                .findFirst()
                .orElseThrow(() -> new CurationVersionNotFoundException(targetVersion));
        CuratedLink rollbackValue = new CuratedLink(
                target.cacheKey(), target.artist(), target.album(), target.year(), target.trackTitle(),
                target.barcode(), target.url(), target.thumb(), target.collectedAt(), target.source(),
                target.version(), target.updatedBy(), target.reason()
        );
        return persist(rollbackValue, expectedVersion, context, "ROLLBACK", targetVersion);
    }

    private CuratedLink persist(CuratedLink link, long expectedVersion, CurationAuditContext context,
                                String action, Long restoredFromVersion) {
        validateLink(link);
        if (isRedisAvailable()) {
            try {
                return saveRedis(link, expectedVersion, context, action, restoredFromVersion);
            } catch (CurationVersionConflictException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Redis error updating curated link: {}", e.getMessage());
            }
        }
        RedisConfig.assertFallbackAllowed();
        return saveLocal(link, expectedVersion, context, action, restoredFromVersion);
    }

    private CuratedLink saveRedis(CuratedLink proposed, long expectedVersion,
                                  CurationAuditContext context) throws Exception {
        return saveRedis(proposed, expectedVersion, context, expectedVersion == 0 ? "CREATE" : "UPDATE", null);
    }

    private CuratedLink saveRedis(CuratedLink proposed, long expectedVersion, CurationAuditContext context,
                                  String action, Long restoredFromVersion) throws Exception {
        String redisKey = KEY_PREFIX + proposed.cacheKey();
        String auditKey = AUDIT_PREFIX + proposed.cacheKey();
        try (Jedis jedis = RedisConfig.getJedis()) {
            if (jedis == null) throw new IllegalStateException("Redis connection unavailable");
            jedis.watch(redisKey);
            String currentJson = jedis.get(redisKey);
            CuratedLink current = currentJson == null ? null : mapper.readValue(currentJson, CuratedLink.class);
            long actualVersion = current == null ? 0 : current.version();
            if (actualVersion != expectedVersion) {
                jedis.unwatch();
                throw new CurationVersionConflictException(expectedVersion, actualVersion);
            }

            String timestamp = Instant.now().toString();
            CuratedLink saved = proposed.asVersion(actualVersion + 1, context.actor(), context.reason(), timestamp);
            CurationAuditEvent event = new CurationAuditEvent(
                    saved.cacheKey(), saved.version(), action, context.actor(), context.reason(), timestamp,
                    context.correlationId(), current, saved, restoredFromVersion
            );
            boolean needsLegacyAudit = current != null && jedis.llen(auditKey) == 0;

            Transaction tx = jedis.multi();
            tx.set(redisKey, mapper.writeValueAsString(saved));
            tx.sadd(INDEX_KEY, saved.cacheKey());
            if (current != null && current.barcode() != null && !current.barcode().isBlank()
                    && !current.barcode().equals(saved.barcode())) {
                tx.del(BARCODE_PREFIX + current.barcode());
            }
            if (saved.barcode() != null && !saved.barcode().isBlank()) {
                tx.set(BARCODE_PREFIX + saved.barcode(), saved.cacheKey());
            }
            if (needsLegacyAudit) {
                tx.rpush(auditKey, mapper.writeValueAsString(legacyEvent(current)));
            }
            tx.rpush(auditKey, mapper.writeValueAsString(event));
            if (tx.exec() == null) {
                long latest = find(saved.cacheKey()).map(CuratedLink::version).orElse(0L);
                throw new CurationVersionConflictException(expectedVersion, latest);
            }
            return saved;
        }
    }

    private CuratedLink saveLocal(CuratedLink proposed, long expectedVersion, CurationAuditContext context) {
        return saveLocal(proposed, expectedVersion, context, expectedVersion == 0 ? "CREATE" : "UPDATE", null);
    }

    private CuratedLink saveLocal(CuratedLink proposed, long expectedVersion, CurationAuditContext context,
                                  String action, Long restoredFromVersion) {
        synchronized (localLock) {
            CuratedLink current = localFallback.get(proposed.cacheKey());
            long actualVersion = current == null ? 0 : current.version();
            if (actualVersion != expectedVersion) {
                throw new CurationVersionConflictException(expectedVersion, actualVersion);
            }
            String timestamp = Instant.now().toString();
            CuratedLink saved = proposed.asVersion(actualVersion + 1, context.actor(), context.reason(), timestamp);
            List<CurationAuditEvent> events = localHistory.computeIfAbsent(saved.cacheKey(), key -> new ArrayList<>());
            if (current != null && events.isEmpty()) events.add(legacyEvent(current));
            events.add(new CurationAuditEvent(
                    saved.cacheKey(), saved.version(), action, context.actor(), context.reason(), timestamp,
                    context.correlationId(), current, saved, restoredFromVersion
            ));
            localFallback.put(saved.cacheKey(), saved);
            return saved;
        }
    }

    private static CurationAuditEvent legacyEvent(CuratedLink link) {
        return new CurationAuditEvent(
                link.cacheKey(), link.version(), "IMPORT", link.updatedBy(), link.reason(), link.collectedAt(),
                "legacy", null, link, null
        );
    }

    @Override
    public void delete(String normalizedKey) {
        if (normalizedKey == null || normalizedKey.isBlank()) return;
        if (isRedisAvailable()) {
            try (Jedis jedis = RedisConfig.getJedis()) {
                if (jedis != null) {
                    CuratedLink current = find(normalizedKey).orElse(null);
                    jedis.del(KEY_PREFIX + normalizedKey);
                    jedis.srem(INDEX_KEY, normalizedKey);
                    if (current != null && current.barcode() != null) jedis.del(BARCODE_PREFIX + current.barcode());
                    return;
                }
            } catch (Exception e) {
                log.warn("Redis error deleting curated link: {}", e.getMessage());
            }
        }
        RedisConfig.assertFallbackAllowed();
        localFallback.remove(normalizedKey);
    }

    @Override
    public List<CuratedLink> listAll() {
        if (isRedisAvailable()) {
            try (Jedis jedis = RedisConfig.getJedis()) {
                if (jedis != null) {
                    Set<String> keys = jedis.smembers(INDEX_KEY);
                    List<CuratedLink> result = new ArrayList<>();
                    for (String key : keys) find(key).ifPresent(result::add);
                    result.sort(Comparator.comparing(CuratedLink::cacheKey));
                    return result;
                }
            } catch (Exception e) {
                log.warn("Redis error listing curated links: {}", e.getMessage());
            }
        }
        RedisConfig.assertFallbackAllowed();
        return localFallback.values().stream().sorted(Comparator.comparing(CuratedLink::cacheKey)).toList();
    }

    private static void validateLink(CuratedLink link) {
        if (link == null || link.cacheKey() == null || link.cacheKey().isBlank()) {
            throw new IllegalArgumentException("Link and cacheKey are required");
        }
    }
}
