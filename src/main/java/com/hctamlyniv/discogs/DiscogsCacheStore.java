package com.hctamlyniv.discogs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hctamlyniv.discogs.model.CuratedLink;
import com.hctamlyniv.discogs.model.DiscogsMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class DiscogsCacheStore {

    private static final Logger log = LoggerFactory.getLogger(DiscogsCacheStore.class);

    private final Path cacheDir;
    private final Path cacheFile;
    private final Path curatedLinksFile;
    private final ObjectMapper mapper;

    private final Map<String, DiscogsMatch> albumCache = new ConcurrentHashMap<>();
    private final Map<String, DiscogsMatch> barcodeCache = new ConcurrentHashMap<>();
    private final Map<String, CuratedLink> curatedLinks = new ConcurrentHashMap<>();
    private final ReentrantLock persistenceLock = new ReentrantLock();

    public DiscogsCacheStore(ObjectMapper mapper) {
        this(Paths.get("cache", "discogs"), mapper);
    }

    public DiscogsCacheStore(Path cacheDir, ObjectMapper mapper) {
        this.cacheDir = cacheDir;
        this.cacheFile = cacheDir.resolve("albums.json");
        this.curatedLinksFile = cacheDir.resolve("curated-links.json");
        this.mapper = mapper;
    }

    public void load() {
        loadAlbumCache();
        loadCuratedLinks();
    }

    public String buildCacheKey(String artist, String album, Integer releaseYear) {
        return (artist == null ? "" : artist) + "|" + (album == null ? "" : album) + "|" + (releaseYear == null ? "" : releaseYear);
    }

    public Optional<String> peekCachedUri(String artist, String album, Integer releaseYear, String barcode) {
        return peekCachedMatch(artist, album, releaseYear, barcode).map(DiscogsMatch::url);
    }

    public Optional<DiscogsMatch> peekCachedMatch(String artist, String album, Integer releaseYear, String barcode) {
        if (barcode != null && !barcode.isBlank()) {
            DiscogsMatch byBarcode = barcodeCache.get(barcode);
            if (byBarcode != null) {
                return Optional.of(byBarcode);
            }
        }
        String key = buildCacheKey(artist != null ? artist.trim() : null, album != null ? album.trim() : null, releaseYear);
        DiscogsMatch cached = albumCache.get(key);
        return cached != null ? Optional.of(cached) : Optional.empty();
    }

    public Optional<String> findCuratedLink(String cacheKey, String barcode) {
        if (barcode != null && !barcode.isBlank()) {
            for (CuratedLink candidate : curatedLinks.values()) {
                if (candidate != null && barcode.equals(candidate.barcode())
                        && candidate.url() != null && !candidate.url().isBlank()) {
                    return Optional.of(candidate.url());
                }
            }
        }
        CuratedLink link = curatedLinks.get(cacheKey);
        if (link != null && link.url() != null && !link.url().isBlank()) {
            return Optional.of(link.url());
        }
        return Optional.empty();
    }

    public void rememberResult(String cacheKey, String url, String barcode) {
        rememberResult(cacheKey, DiscogsMatch.legacyCache(url), barcode);
    }

    public void rememberResult(String cacheKey, DiscogsMatch match, String barcode) {
        if (match == null) {
            return;
        }
        String url = match.url();
        String safeUrl = DiscogsUrlUtils.sanitizeDiscogsWebUrl(url);
        if (safeUrl == null) {
            return;
        }
        DiscogsMatch safeMatch = new DiscogsMatch(
                safeUrl,
                match.matchType(),
                match.confidence(),
                match.source(),
                match.reason(),
                match.vinylFormatConfirmed()
        );
        if (cacheKey != null && !cacheKey.isBlank()) {
            albumCache.put(cacheKey, safeMatch);
        }
        if (barcode != null && !barcode.isBlank()) {
            barcodeCache.put(barcode, safeMatch);
        }
        persistAlbumCache();
    }

    public CuratedLink saveCuratedLink(String cacheKey, String artist, String album, Integer releaseYear, String trackTitle, String barcode, String url, String thumb) {
        String safeUrl = DiscogsUrlUtils.sanitizeDiscogsWebUrl(url);
        if (safeUrl == null) {
            throw new IllegalArgumentException("Invalid Discogs URL");
        }
        String safeThumb = DiscogsUrlUtils.sanitizeDiscogsWebUrl(thumb);

        CuratedLink link = new CuratedLink(
                cacheKey,
                artist,
                album,
                releaseYear,
                trackTitle,
                barcode,
                safeUrl,
                safeThumb,
                Instant.now().toString(),
                "manual"
        );
        curatedLinks.put(cacheKey, link);
        rememberResult(cacheKey, new DiscogsMatch(
                safeUrl,
                safeUrl.toLowerCase().contains("/master/") ? "EXACT_MASTER" : "EXACT_RELEASE",
                "HIGH",
                "MANUAL_CURATION",
                "A VinylMatch curator selected this Discogs entry.",
                false
        ), barcode);
        persistCuratedLinks();
        return link;
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    private void loadAlbumCache() {
        try {
            if (!Files.exists(cacheFile)) {
                return;
            }
            JsonNode root = mapper.readTree(cacheFile.toFile());
            JsonNode entries = root.get("entries");
            if (entries != null && entries.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = entries.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    if (entry.getValue() != null && !entry.getValue().isNull()) {
                        albumCache.put(entry.getKey(), readMatch(entry.getValue()));
                    }
                }
            }
            JsonNode barcodes = root.get("barcodes");
            if (barcodes != null && barcodes.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = barcodes.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    if (entry.getValue() != null && !entry.getValue().isNull()) {
                        barcodeCache.put(entry.getKey(), readMatch(entry.getValue()));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load Discogs cache: {}", e.getMessage());
        }
    }

    private void loadCuratedLinks() {
        try {
            if (!Files.exists(curatedLinksFile)) {
                return;
            }
            JsonNode root = mapper.readTree(curatedLinksFile.toFile());
            JsonNode links = root.get("links");
            if (links != null && links.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = links.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    String cacheKey = entry.getKey();
                    JsonNode v = entry.getValue();
                    if (v == null || v.isNull() || !v.isObject()) continue;

                    String artist = optText(v, "artist");
                    String album = optText(v, "album");
                    Integer year = v.hasNonNull("year") && v.get("year").isInt() ? v.get("year").asInt() : null;
                    String trackTitle = optText(v, "trackTitle");
                    String barcode = optText(v, "barcode");
                    String url = optText(v, "url");
                    String thumb = optText(v, "thumb");
                    String collectedAt = optText(v, "collectedAt");
                    String source = optText(v, "source");

                    CuratedLink link = new CuratedLink(cacheKey, artist, album, year, trackTitle, barcode, url, thumb, collectedAt, source);
                    curatedLinks.put(cacheKey, link);
                    if (cacheKey != null && url != null && !url.isBlank()) {
                        albumCache.put(cacheKey, new DiscogsMatch(
                                url,
                                url.toLowerCase().contains("/master/") ? "EXACT_MASTER" : "EXACT_RELEASE",
                                "HIGH",
                                "MANUAL_CURATION",
                                "A VinylMatch curator selected this Discogs entry.",
                                false
                        ));
                    }
                    if (barcode != null && !barcode.isBlank() && url != null && !url.isBlank()) {
                        barcodeCache.put(barcode, albumCache.get(cacheKey));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load curated Discogs links: {}", e.getMessage());
        }
    }

    private void persistAlbumCache() {
        persistenceLock.lock();
        try {
            Files.createDirectories(cacheDir);
            Map<String, Object> payload = new HashMap<>();
            payload.put("updatedAt", Instant.now().toString());
            payload.put("entries", new HashMap<>(albumCache));
            payload.put("barcodes", new HashMap<>(barcodeCache));
            mapper.writerWithDefaultPrettyPrinter().writeValue(cacheFile.toFile(), payload);
        } catch (IOException e) {
            log.warn("Failed to persist Discogs cache: {}", e.getMessage());
        } finally {
            persistenceLock.unlock();
        }
    }

    private void persistCuratedLinks() {
        persistenceLock.lock();
        try {
            Files.createDirectories(cacheDir);
            Map<String, Object> payload = new HashMap<>();
            payload.put("updatedAt", Instant.now().toString());
            payload.put("links", new HashMap<>(curatedLinks));
            mapper.writerWithDefaultPrettyPrinter().writeValue(curatedLinksFile.toFile(), payload);
        } catch (IOException e) {
            log.warn("Failed to persist curated Discogs links: {}", e.getMessage());
        } finally {
            persistenceLock.unlock();
        }
    }

    private static String optText(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.asText();
    }

    private DiscogsMatch readMatch(JsonNode value) throws IOException {
        if (value.isTextual()) {
            return DiscogsMatch.legacyCache(value.asText());
        }
        DiscogsMatch match = mapper.treeToValue(value, DiscogsMatch.class);
        if (match == null || match.url() == null || match.url().isBlank()) {
            throw new IOException("Invalid Discogs match cache entry");
        }
        return match;
    }
}
