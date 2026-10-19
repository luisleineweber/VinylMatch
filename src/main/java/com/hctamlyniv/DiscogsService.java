package com.hctamlyniv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hctamlyniv.curation.CuratedLinkStore;
import com.hctamlyniv.curation.RedisCuratedLinkStore;
import com.hctamlyniv.discogs.DiscogsApiClient;
import com.hctamlyniv.discogs.DiscogsCatalog;
import com.hctamlyniv.discogs.DiscogsCacheStore;
import com.hctamlyniv.discogs.DiscogsNormalizer;
import com.hctamlyniv.discogs.DiscogsUrlUtils;
import com.hctamlyniv.discogs.model.CurationCandidate;
import com.hctamlyniv.discogs.model.CuratedLink;
import com.hctamlyniv.discogs.model.DiscogsProfile;
import com.hctamlyniv.discogs.model.DiscogsMatch;
import com.hctamlyniv.discogs.model.LibraryFlags;
import com.hctamlyniv.discogs.model.WishlistResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Discogs service facade: caching + progressive matching + optional API features (profile/wishlist).
 */
public class DiscogsService {

    private static final Logger log = LoggerFactory.getLogger(DiscogsService.class);
    private static final int TRANSIENT_RETRY_LIMIT = 3;
    private static final long TRANSIENT_RETRY_BASE_DELAY_MS = 450L;
    private static final int MAX_PROVIDER_CONCURRENCY = envInt("DISCOGS_PROVIDER_CONCURRENCY", 6);
    private static final Semaphore PROVIDER_PERMITS = new Semaphore(MAX_PROVIDER_CONCURRENCY, true);
    private static final AtomicInteger ACTIVE_PROVIDER_CALLS = new AtomicInteger();

    private final ObjectMapper mapper;
    private final DiscogsCacheStore cacheStore;
    private final CuratedLinkStore curatedLinkStore;

    private final String userAgent;
    private final DiscogsApiClient apiClient;
    private final DiscogsCatalog catalog;

    public DiscogsService(String token, String userAgent) {
        this(token, null, userAgent, null, null, null);
    }

    public DiscogsService(String token, String userAgent, Path cacheDir) {
        this(token, null, userAgent, null, null, cacheDir);
    }

    public DiscogsService(String token, String tokenSecret, String userAgent, String consumerKey, String consumerSecret) {
        this(token, tokenSecret, userAgent, consumerKey, consumerSecret, null);
    }

    public DiscogsService(String token, String tokenSecret, String userAgent, String consumerKey, String consumerSecret, Path cacheDir) {
        this.userAgent = (userAgent == null || userAgent.isBlank()) ? "VinylMatch/1.0" : userAgent;

        this.mapper = new ObjectMapper();
        this.cacheStore = (cacheDir == null) ? new DiscogsCacheStore(mapper) : new DiscogsCacheStore(cacheDir, mapper);
        this.curatedLinkStore = new RedisCuratedLinkStore(mapper);

        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.apiClient = new DiscogsApiClient(http, mapper, token, this.userAgent, null, consumerKey, consumerSecret, tokenSecret);
        this.catalog = new DiscogsCatalog(apiClient);

        cacheStore.load();
    }

    public DiscogsCatalog catalog() {
        return catalog;
    }

    public Optional<String> peekCachedUri(String artist, String album, Integer releaseYear, String barcode) {
        return cacheStore.peekCachedUri(artist, album, releaseYear, barcode);
    }

    public Optional<DiscogsMatch> peekCachedMatch(String artist, String album, Integer releaseYear, String barcode) {
        return cacheStore.peekCachedMatch(artist, album, releaseYear, barcode);
    }

    public Optional<String> findAlbumUri(String artist, String album, Integer releaseYear) {
        return findAlbumUri(artist, album, releaseYear, null, null);
    }

    public Optional<String> findAlbumUri(String artist, String album, Integer releaseYear, String trackTitle) {
        return findAlbumUri(artist, album, releaseYear, trackTitle, null);
    }

    public Optional<String> findAlbumUri(String artist, String album, Integer releaseYear, String trackTitle, String barcode) {
        return findAlbumMatch(artist, album, releaseYear, trackTitle, barcode).map(DiscogsMatch::url);
    }

    public Optional<DiscogsMatch> findAlbumMatch(String artist, String album, Integer releaseYear, String trackTitle, String barcode) {
        boolean acquired = false;
        try {
            acquired = PROVIDER_PERMITS.tryAcquire(250, TimeUnit.MILLISECONDS);
            if (!acquired) {
                return Optional.of(searchOnlyMatch(
                    DiscogsUrlUtils.buildWebSearchUrl(artist, album, releaseYear),
                    "Discogs capacity is temporarily saturated; review the search results manually."
                ));
            }
            ACTIVE_PROVIDER_CALLS.incrementAndGet();
            return findAlbumMatchInternal(artist, album, releaseYear, trackTitle, barcode);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(searchOnlyMatch(
                DiscogsUrlUtils.buildWebSearchUrl(artist, album, releaseYear),
                "The Discogs lookup was interrupted; review the search results manually."
            ));
        } finally {
            if (acquired) {
                ACTIVE_PROVIDER_CALLS.decrementAndGet();
                PROVIDER_PERMITS.release();
            }
        }
    }

    private Optional<DiscogsMatch> findAlbumMatchInternal(String artist, String album, Integer releaseYear, String trackTitle, String barcode) {
        final String origArtist = DiscogsNormalizer.extractPrimaryArtist(artist);
        final String origAlbum = album == null ? null : album.trim();
        final String origTrack = trackTitle == null ? null : trackTitle.trim();
        final Integer year = releaseYear;
        final String cacheKey = cacheStore.buildCacheKey(origArtist, origAlbum, year);
        final String normalizedKey = CuratedLinkStore.normalizeKey(origArtist, origAlbum, year);

        Optional<CuratedLink> curatedLink = curatedLinkStore.find(normalizedKey);
        if (curatedLink.isPresent() && curatedLink.get().url() != null) {
            return Optional.of(curatedMatch(curatedLink.get().url()));
        }
        
        if (barcode != null && !barcode.isBlank()) {
            curatedLink = curatedLinkStore.findByBarcode(barcode);
            if (curatedLink.isPresent() && curatedLink.get().url() != null) {
                return Optional.of(curatedMatch(curatedLink.get().url()));
            }
        }

        Optional<String> curated = cacheStore.findCuratedLink(cacheKey, barcode);
        if (curated.isPresent() && isCacheFinalResult(curated.get())) {
            return Optional.of(curatedMatch(curated.get()));
        }

        if (barcode != null && !barcode.isBlank()) {
            Optional<DiscogsMatch> cachedByBarcode = cacheStore.peekCachedMatch(null, null, null, barcode);
            if (cachedByBarcode.isPresent() && isCacheFinalResult(cachedByBarcode.get())) {
                return cachedByBarcode;
            }
            if (apiClient.isConfigured()) {
                try {
                    Optional<String> byCode = apiClient.searchByBarcode(barcode);
                    if (byCode.isPresent()) {
                        DiscogsMatch match = new DiscogsMatch(
                                byCode.get(), "EXACT_RELEASE", "HIGH", "BARCODE",
                                "Discogs returned a release for the album barcode.", false);
                        cacheStore.rememberResult(cacheKey, match, barcode);
                        return Optional.of(match);
                    }
                } catch (Exception e) {
                    log.debug("Discogs barcode lookup failed: {}", e.getMessage());
                }
            }
        }

        Optional<DiscogsMatch> cached = cacheStore.peekCachedMatch(origArtist, origAlbum, year, null);
        if (cached.isPresent() && isCacheFinalResult(cached.get())) {
            return cached;
        }

        // No token: provide a safe Discogs web search URL as fallback.
        if (!apiClient.isConfigured()) {
            String fallback = DiscogsUrlUtils.buildWebSearchUrl(
                    DiscogsNormalizer.normalizeArtistLevel(origArtist, DiscogsNormalizer.NormLevel.HEAVY),
                    origAlbum,
                    year
            );
            DiscogsMatch match = searchOnlyMatch(fallback, "No Discogs API token is configured; open the search results and choose a release.");
            cacheStore.rememberResult(cacheKey, match, barcode);
            return Optional.of(match);
        }

        int attempt = 0;
        while (true) {
            try {
                Optional<String> result;

                String artistStrict = DiscogsNormalizer.normalizeArtistLevel(origArtist, DiscogsNormalizer.NormLevel.HEAVY);

                // Pass A: free-text q search (raw album)
                String q1 = ((artistStrict != null) ? artistStrict : "") + " " + ((origAlbum != null) ? origAlbum : "");
                result = apiClient.searchOnceQ(q1, year, artistStrict, origAlbum);
                if (result.isPresent()) {
                    DiscogsMatch match = exactCatalogMatch(result.get(), "Discogs matched the normalized artist, album, and year.");
                    cacheStore.rememberResult(cacheKey, match, barcode);
                    return Optional.of(match);
                }

                // Pass B: free-text q search (lightly normalized album)
                String lightAlbum = DiscogsNormalizer.normalizeTitleLevel(origAlbum, DiscogsNormalizer.NormLevel.LIGHT);
                String q2 = ((artistStrict != null) ? artistStrict : "") + " " + ((lightAlbum != null) ? lightAlbum : "");
                result = apiClient.searchOnceQ(q2, year, artistStrict, origAlbum);
                if (result.isPresent()) {
                    DiscogsMatch match = exactCatalogMatch(result.get(), "Discogs matched the artist and a normalized album title.");
                    cacheStore.rememberResult(cacheKey, match, barcode);
                    return Optional.of(match);
                }

                // Structured fallbacks (master preferred)
                result = apiClient.searchOnce(artistStrict, origAlbum, year, origTrack, true);
                if (result.isPresent()) {
                    DiscogsMatch match = likelyMatch(result.get(), "Discogs matched artist, album, year, and track metadata.");
                    cacheStore.rememberResult(cacheKey, match, barcode); return Optional.of(match);
                }

                result = apiClient.searchOnce(artistStrict, origAlbum, year, origTrack, false);
                if (result.isPresent()) {
                    DiscogsMatch match = likelyMatch(result.get(), "Discogs matched artist, album, year, and track metadata.");
                    cacheStore.rememberResult(cacheKey, match, barcode); return Optional.of(match);
                }

                result = apiClient.searchOnce(artistStrict, origAlbum, null, origTrack, true);
                if (result.isPresent()) {
                    DiscogsMatch match = likelyMatch(result.get(), "Discogs matched artist, album, and track metadata without a year constraint.");
                    cacheStore.rememberResult(cacheKey, match, barcode); return Optional.of(match);
                }

                result = apiClient.searchOnce(artistStrict, origAlbum, null, origTrack, false);
                if (result.isPresent()) {
                    DiscogsMatch match = likelyMatch(result.get(), "Discogs matched artist, album, and track metadata without a year constraint.");
                    cacheStore.rememberResult(cacheKey, match, barcode); return Optional.of(match);
                }

                String fallback = DiscogsUrlUtils.buildWebSearchUrl(artistStrict, origAlbum, year);
                DiscogsMatch match = searchOnlyMatch(fallback, "No reliable Discogs entry was found; review the search results manually.");
                cacheStore.rememberResult(cacheKey, match, barcode);
                return Optional.of(match);
            } catch (Exception e) {
                boolean transientError = isTransientDiscogsError(e);
                if (transientError && attempt < TRANSIENT_RETRY_LIMIT) {
                    attempt++;
                    sleepBackoff(attempt);
                    continue;
                }

                String fallback = DiscogsUrlUtils.buildWebSearchUrl(
                        DiscogsNormalizer.normalizeArtistLevel(artist, DiscogsNormalizer.NormLevel.HEAVY),
                        album,
                        releaseYear
                );

                if (!transientError) {
                    cacheStore.rememberResult(cacheKey, searchOnlyMatch(fallback,
                            "The Discogs lookup failed; review the search results manually."), barcode);
                } else {
                    log.debug("Discogs transient error for {} after {} retries; returning uncached fallback", cacheKey, attempt);
                }
                return Optional.of(searchOnlyMatch(fallback, transientError
                        ? "Discogs is temporarily unavailable; review the search results manually."
                        : "The Discogs lookup failed; review the search results manually."));
            }
        }
    }

    public static Map<String, Integer> providerStatus() {
        return Map.of(
            "active", ACTIVE_PROVIDER_CALLS.get(),
            "limit", MAX_PROVIDER_CONCURRENCY,
            "waiting", PROVIDER_PERMITS.getQueueLength()
        );
    }

    private static int envInt(String name, int fallback) {
        try {
            String raw = System.getenv(name);
            return raw == null || raw.isBlank() ? fallback : Math.max(1, Integer.parseInt(raw));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public Optional<DiscogsProfile> fetchProfile() {
        return apiClient.fetchProfile();
    }

    public WishlistResult fetchWishlist(String username, int page, int perPage) {
        return apiClient.fetchWishlist(username, page, perPage);
    }

    public Map<Integer, LibraryFlags> lookupLibraryFlags(String username, Set<Integer> releaseIds) {
        Map<Integer, LibraryFlags> result = new HashMap<>();
        if (releaseIds == null || releaseIds.isEmpty()) {
            return result;
        }
        Set<Integer> wishlistIds = apiClient.fetchWishlistReleaseIds(username, 100);
        Set<Integer> collectionIds = apiClient.fetchCollectionReleaseIds(username, 100);
        for (Integer id : releaseIds) {
            if (id == null) continue;
            result.put(id, new LibraryFlags(wishlistIds.contains(id), collectionIds.contains(id)));
        }
        return result;
    }

    public Optional<Integer> resolveReleaseIdFromUrl(String url) {
        Optional<Integer> id = DiscogsUrlUtils.resolveReleaseIdFromUrl(url);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        String normalized = DiscogsUrlUtils.sanitizeDiscogsWebUrl(url);
        if (normalized == null) {
            return Optional.empty();
        }
        if (normalized.toLowerCase().contains("/release/")) {
            return id;
        }
        // master -> resolve to main release if possible, else return master id
        if (apiClient.isConfigured()) {
            try {
                return apiClient.fetchMainReleaseId(id.get()).or(() -> id);
            } catch (Exception ignored) {
                return id;
            }
        }
        return id;
    }

    public boolean addToWantlist(String username, int releaseId) {
        return apiClient.addToWantlist(username, releaseId);
    }

    public java.util.List<CurationCandidate> fetchCurationCandidates(String artist, String album, Integer releaseYear, String trackTitle, int limit)
            throws java.io.IOException, InterruptedException {
        return apiClient.fetchCurationCandidates(artist, album, releaseYear, trackTitle, limit);
    }

    public CuratedLink saveCuratedLink(String artist, String album, Integer releaseYear, String trackTitle, String barcode, String url, String thumb) {
        String cacheKey = cacheStore.buildCacheKey(
                DiscogsNormalizer.extractPrimaryArtist(artist),
                album == null ? null : album.trim(),
                releaseYear
        );
        return cacheStore.saveCuratedLink(cacheKey, artist, album, releaseYear, trackTitle, barcode, url, thumb);
    }

    private static boolean isTransientDiscogsError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.startsWith("discogs_transient_status:")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void sleepBackoff(int attempt) {
        long delay = TRANSIENT_RETRY_BASE_DELAY_MS * Math.max(1, attempt);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isCacheFinalResult(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        if (!apiClient.isConfigured()) {
            return true;
        }
        return !isSearchFallbackUrl(url);
    }

    boolean isCacheFinalResult(DiscogsMatch match) {
        if (match == null || !isCacheFinalResult(match.url())) {
            return false;
        }
        // URL-only cache entries predate provenance and must be rechecked when the API is available.
        return !apiClient.isConfigured() || !"LEGACY_CACHE".equalsIgnoreCase(match.source());
    }

    private static boolean isSearchFallbackUrl(String url) {
        return url != null && url.toLowerCase().contains("/search");
    }

    private static DiscogsMatch curatedMatch(String url) {
        return new DiscogsMatch(
                url,
                url != null && url.toLowerCase().contains("/master/") ? "EXACT_MASTER" : "EXACT_RELEASE",
                "HIGH",
                "MANUAL_CURATION",
                "A VinylMatch curator selected this Discogs entry.",
                false
        );
    }

    private static DiscogsMatch exactCatalogMatch(String url, String reason) {
        return new DiscogsMatch(
                url,
                url != null && url.toLowerCase().contains("/master/") ? "EXACT_MASTER" : "EXACT_RELEASE",
                "HIGH",
                "DISCOGS_CATALOG",
                reason,
                false
        );
    }

    private static DiscogsMatch likelyMatch(String url, String reason) {
        return new DiscogsMatch(url, "LIKELY_MATCH", "MEDIUM", "DISCOGS_CATALOG", reason, false);
    }

    private static DiscogsMatch searchOnlyMatch(String url, String reason) {
        return new DiscogsMatch(url, "SEARCH_ONLY", "LOW", "DISCOGS_SEARCH", reason, false);
    }
}
