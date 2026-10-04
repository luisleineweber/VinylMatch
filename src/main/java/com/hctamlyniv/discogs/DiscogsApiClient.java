package com.hctamlyniv.discogs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hctamlyniv.discogs.model.CurationCandidate;
import com.hctamlyniv.discogs.model.CatalogResult;
import com.hctamlyniv.discogs.model.DiscogsProfile;
import com.hctamlyniv.discogs.model.WishlistEntry;
import com.hctamlyniv.discogs.model.WishlistResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class DiscogsApiClient {

    private static final Logger log = LoggerFactory.getLogger(DiscogsApiClient.class);
    private static final String DEFAULT_API_BASE = "https://api.discogs.com";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String token;
    private final String tokenSecret;
    private final String consumerKey;
    private final String consumerSecret;
    private final String userAgent;
    private final String apiBase;

    // Library-status caching: these endpoints are expensive and often called repeatedly
    // (playlist load, focus events, drawer refreshes). Keep a short TTL to reduce API load.
    private static final long LIBRARY_IDS_CACHE_TTL_MS = 20_000L;
    private static final int LIBRARY_IDS_MAX_PAGES = 3;
    private final Object wishlistIdsCacheLock = new Object();
    private volatile String wishlistIdsCacheUsername;
    private volatile int wishlistIdsCachePerPage;
    private volatile Set<Integer> wishlistIdsCache;
    private volatile long wishlistIdsCacheAtMillis;

    private final Object collectionIdsCacheLock = new Object();
    private volatile String collectionIdsCacheUsername;
    private volatile int collectionIdsCachePerPage;
    private volatile Set<Integer> collectionIdsCache;
    private volatile long collectionIdsCacheAtMillis;

    public DiscogsApiClient(HttpClient http, ObjectMapper mapper, String token, String userAgent) {
        this(http, mapper, token, userAgent, DEFAULT_API_BASE, null, null, null);
    }

    public DiscogsApiClient(HttpClient http, ObjectMapper mapper, String token, String userAgent, String apiBase) {
        this(http, mapper, token, userAgent, apiBase, null, null, null);
    }

    public DiscogsApiClient(
            HttpClient http,
            ObjectMapper mapper,
            String token,
            String userAgent,
            String apiBase,
            String consumerKey,
            String consumerSecret,
            String tokenSecret
    ) {
        this.http = http;
        this.mapper = mapper;
        this.token = token;
        this.tokenSecret = tokenSecret;
        this.consumerKey = consumerKey;
        this.consumerSecret = consumerSecret;
        this.userAgent = userAgent;
        this.apiBase = (apiBase == null || apiBase.isBlank()) ? DEFAULT_API_BASE : apiBase.trim();
    }

    public boolean isConfigured() {
        return hasUserTokenAuth() || hasOAuthCredentials();
    }

    CatalogResult<JsonNode> fetchCatalogResource(String path) throws IOException, InterruptedException {
        if (!isConfigured()) {
            return CatalogResult.failure(503, "discogs_not_configured",
                    "Connect Discogs on the Playlist page, or configure a server Discogs token.");
        }
        HttpRequest request = baseRequest(URI.create(apiBase + path))
                .timeout(Duration.ofSeconds(12)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return switch (response.statusCode()) {
            case 200 -> {
                JsonNode body = mapper.readTree(response.body());
                if (body == null || !body.isObject()) throw new IOException("Invalid Discogs catalog response");
                yield CatalogResult.success(body);
            }
            case 404 -> CatalogResult.failure(404, "catalog_not_found", "This Discogs item is no longer available.");
            case 401, 403 -> CatalogResult.failure(503, "discogs_access_denied",
                    "Discogs denied access. Reconnect Discogs or check the server token.");
            case 429 -> CatalogResult.failure(429, "discogs_rate_limited", "Discogs is busy. Please wait before trying again.");
            default -> CatalogResult.failure(502, "discogs_unavailable", "Discogs is unavailable. Please try again.");
        };
    }

    private boolean hasUserTokenAuth() {
        return token != null && !token.isBlank() && !hasOAuthCredentials();
    }

    private boolean hasOAuthCredentials() {
        return token != null && !token.isBlank()
                && tokenSecret != null && !tokenSecret.isBlank()
                && consumerKey != null && !consumerKey.isBlank()
                && consumerSecret != null && !consumerSecret.isBlank();
    }

    public Optional<DiscogsProfile> fetchProfile() {
        if (!isConfigured()) {
            return Optional.empty();
        }
        try {
            HttpRequest req = baseRequest(URI.create(apiBase + "/oauth/identity"))
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                return Optional.empty();
            }
            JsonNode root = mapper.readTree(resp.body());
            String username = root.path("username").asText(null);
            String name = root.path("name").asText(null);
            if (username == null || username.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new DiscogsProfile(username, name));
        } catch (Exception e) {
            log.debug("Discogs profile fetch failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public WishlistResult fetchWishlist(String username, int page, int perPage) {
        List<WishlistEntry> entries = new ArrayList<>();
        if (!isConfigured() || username == null || username.isBlank()) {
            return new WishlistResult(entries, 0);
        }
        try {
            String qs = "?sort=added&sort_order=desc&page=" + Math.max(1, page) + "&per_page=" + Math.max(1, Math.min(perPage, 50));
            URI uri = URI.create(apiBase + "/users/" + DiscogsUrlUtils.urlEncode(username) + "/wants" + qs);
            HttpRequest req = baseRequest(uri)
                    .timeout(Duration.ofSeconds(12))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                return new WishlistResult(entries, 0);
            }
            JsonNode root = mapper.readTree(resp.body());
            int total = root.path("pagination").path("items").asInt(0);
            JsonNode wants = root.get("wants");
            if (wants != null && wants.isArray()) {
                for (JsonNode item : wants) {
                    JsonNode basic = item.get("basic_information");
                    if (basic == null) continue;

                    String title = basic.path("title").asText(null);
                    String artist = null;
                    JsonNode artists = basic.get("artists");
                    if (artists != null && artists.isArray() && artists.size() > 0) {
                        artist = artists.get(0).path("name").asText(null);
                    }
                    Integer year = basic.hasNonNull("year") ? basic.get("year").asInt() : null;
                    String thumb = DiscogsUrlUtils.sanitizeDiscogsWebUrl(basic.path("thumb").asText(null));
                    String uriStr = basic.path("resource_url").asText(null);
                    String webUrl = basic.path("uri").asText(null);
                    Integer releaseId = basic.hasNonNull("id") ? basic.get("id").asInt() : null;
                    String targetUrl = (webUrl != null && !webUrl.isBlank()) ? webUrl : uriStr;
                    if (targetUrl != null && targetUrl.startsWith("/")) {
                        targetUrl = "https://www.discogs.com" + targetUrl;
                    }
                    String safeTarget = DiscogsUrlUtils.sanitizeDiscogsWebUrl(targetUrl);
                    if (safeTarget == null && releaseId != null) {
                        safeTarget = DiscogsUrlUtils.sanitizeDiscogsWebUrl("https://www.discogs.com/release/" + releaseId);
                    }
                    entries.add(new WishlistEntry(title, artist, year, thumb, safeTarget, releaseId));
                }
            }
            return new WishlistResult(entries, total);
        } catch (Exception e) {
            log.debug("Discogs wishlist fetch failed: {}", e.getMessage());
            return new WishlistResult(entries, 0);
        }
    }

    public boolean addToWantlist(String username, int releaseId) {
        if (!isConfigured() || username == null || username.isBlank()) {
            return false;
        }
        try {
            Map<String, String> formParams = java.util.Map.of(
                    "release_id", String.valueOf(releaseId),
                    "notes", "Added via VinylMatch"
            );
            String body = DiscogsOAuth1.toFormEncoded(formParams);
            URI uri = URI.create(apiBase + "/users/" + DiscogsUrlUtils.urlEncode(username) + "/wants");
            HttpRequest req = baseRequest(uri, "POST", formParams)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int statusCode = resp.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                invalidateWishlistIdsCache();
                return true;
            }
            if (statusCode == 409) {
                // Duplicate add on Discogs: the item is already in the wantlist.
                invalidateWishlistIdsCache();
                return true;
            }
            if (statusCode >= 400 && statusCode < 500 && looksLikeDuplicateWantlist(resp.body())) {
                invalidateWishlistIdsCache();
                return true;
            }
            log.debug("Discogs wantlist add rejected with status {}", statusCode);
            return false;
        } catch (Exception e) {
            log.debug("Discogs wantlist add failed: {}", e.getMessage());
            return false;
        }
    }

    private static boolean looksLikeDuplicateWantlist(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return false;
        }
        String normalized = responseBody.toLowerCase();
        return normalized.contains("already")
                && (normalized.contains("wantlist") || normalized.contains("wants"));
    }

    public List<CurationCandidate> fetchCurationCandidates(String artist, String album, Integer releaseYear, String trackTitle, int limit)
            throws IOException, InterruptedException {
        if (!isConfigured()) {
            return List.of();
        }

        int max = Math.max(1, Math.min(limit, 10));

        String qArtist = DiscogsNormalizer.normalizeArtistLevel(artist, DiscogsNormalizer.NormLevel.HEAVY);
        String qAlbum = DiscogsNormalizer.normalizeTitleLevel(album, DiscogsNormalizer.NormLevel.LIGHT);
        String qTrack = DiscogsNormalizer.normalizeTitleLevel(trackTitle, DiscogsNormalizer.NormLevel.LIGHT);

        StringBuilder qs = new StringBuilder();
        if (qArtist != null && !qArtist.isBlank()) qs.append("artist=").append(DiscogsUrlUtils.urlEncode(qArtist)).append("&");
        if (qAlbum != null && !qAlbum.isBlank()) qs.append("release_title=").append(DiscogsUrlUtils.urlEncode(qAlbum)).append("&");
        if (qTrack != null && !qTrack.isBlank()) qs.append("track=").append(DiscogsUrlUtils.urlEncode(qTrack)).append("&");
        if (releaseYear != null && releaseYear > 1900 && releaseYear < 2100) qs.append("year=").append(releaseYear).append("&");
        qs.append("type=release&per_page=10&sort=relevance");

        URI uri = URI.create(apiBase + "/database/search?" + qs);
        HttpRequest req = baseRequest(uri)
                .timeout(Duration.ofSeconds(12))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            return List.of();
        }

        JsonNode root = mapper.readTree(resp.body());
        JsonNode results = root.get("results");
        if (results == null || !results.isArray() || results.size() == 0) {
            return List.of();
        }

        List<CurationCandidate> candidates = new ArrayList<>();
        for (JsonNode node : results) {
            if (candidates.size() >= max) break;
            Integer id = node.has("id") && node.get("id").isInt() ? node.get("id").asInt() : null;
            String title = optText(node, "title");
            String thumb = optText(node, "thumb");
            Integer year = node.has("year") && node.get("year").isInt() ? node.get("year").asInt() : null;
            String country = optText(node, "country");
            String format = parseFormats(node.get("format"));
            String uriSuffix = optText(node, "uri");
            String url = uriSuffix != null ? DiscogsUrlUtils.sanitizeDiscogsWebUrl("https://www.discogs.com" + uriSuffix) : null;
            String safeThumb = DiscogsUrlUtils.sanitizeDiscogsWebUrl(thumb);
            if (url == null) {
                continue;
            }
            String artistName = optText(node, "artist");
            candidates.add(new CurationCandidate(id, title, artistName, year, country, format, safeThumb, url));
        }
        return candidates;
    }

    public Optional<String> searchByBarcode(String code) throws IOException, InterruptedException {
        if (!isConfigured() || code == null || code.isBlank()) {
            return Optional.empty();
        }
        StringBuilder qs = new StringBuilder();
        qs.append("barcode=").append(DiscogsUrlUtils.urlEncode(code));
        qs.append("&type=release");
        qs.append("&per_page=5&sort=relevance");
        URI uri = URI.create(apiBase + "/database/search?" + qs);
        HttpRequest req = baseRequest(uri)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (!isSearchStatusSuccessful(resp.statusCode())) {
            throwTransientIfNeeded(resp.statusCode());
            return Optional.empty();
        }
        JsonNode root = mapper.readTree(resp.body());
        JsonNode results = root.get("results");
        if (results == null || !results.isArray() || results.size() == 0) {
            return Optional.empty();
        }
        for (JsonNode item : results) {
            JsonNode uriNode = item.get("uri");
            if (uriNode != null && !uriNode.isNull()) {
                String uriStr = uriNode.asText();
                if (uriStr != null && !uriStr.isBlank()) {
                    if (uriStr.startsWith("/")) uriStr = "https://www.discogs.com" + uriStr;
                    return Optional.ofNullable(DiscogsUrlUtils.sanitizeDiscogsWebUrl(uriStr));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Integer> fetchMainReleaseId(int masterId) throws IOException, InterruptedException {
        if (!isConfigured()) {
            return Optional.empty();
        }
        URI uri = URI.create(apiBase + "/masters/" + masterId);
        HttpRequest req = baseRequest(uri)
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != 200) {
            return Optional.empty();
        }
        JsonNode root = mapper.readTree(resp.body());
        if (root.hasNonNull("main_release")) {
            return Optional.of(root.get("main_release").asInt());
        }
        return Optional.empty();
    }

    public Set<Integer> fetchWishlistReleaseIds(String username, int perPage) {
        if (!isConfigured() || username == null || username.isBlank()) {
            return Set.of();
        }

        int safePerPage = Math.max(1, Math.min(perPage, 100));
        long now = System.currentTimeMillis();

        Set<Integer> cached = wishlistIdsCache;
        if (cached != null
                && username.equals(wishlistIdsCacheUsername)
                && safePerPage == wishlistIdsCachePerPage
                && (now - wishlistIdsCacheAtMillis) < LIBRARY_IDS_CACHE_TTL_MS) {
            return new HashSet<>(cached);
        }

        synchronized (wishlistIdsCacheLock) {
            cached = wishlistIdsCache;
            now = System.currentTimeMillis();
            if (cached != null
                    && username.equals(wishlistIdsCacheUsername)
                    && safePerPage == wishlistIdsCachePerPage
                    && (now - wishlistIdsCacheAtMillis) < LIBRARY_IDS_CACHE_TTL_MS) {
                return new HashSet<>(cached);
            }

            try {
                Set<Integer> ids = fetchPagedReleaseIds(
                        safePerPage,
                        "/users/" + DiscogsUrlUtils.urlEncode(username) + "/wants",
                        "wants"
                );
                wishlistIdsCacheUsername = username;
                wishlistIdsCachePerPage = safePerPage;
                wishlistIdsCache = Set.copyOf(ids);
                wishlistIdsCacheAtMillis = System.currentTimeMillis();
                return ids;
            } catch (Exception e) {
                log.debug("Discogs wishlist ID fetch failed: {}", e.getMessage());
                // If Discogs is unavailable, prefer returning the last known snapshot.
                if (cached != null
                        && username.equals(wishlistIdsCacheUsername)
                        && safePerPage == wishlistIdsCachePerPage) {
                    return new HashSet<>(cached);
                }
            }

            return Set.of();
        }
    }

    public Set<Integer> fetchCollectionReleaseIds(String username, int perPage) {
        if (!isConfigured() || username == null || username.isBlank()) {
            return Set.of();
        }

        int safePerPage = Math.max(1, Math.min(perPage, 100));
        long now = System.currentTimeMillis();

        Set<Integer> cached = collectionIdsCache;
        if (cached != null
                && username.equals(collectionIdsCacheUsername)
                && safePerPage == collectionIdsCachePerPage
                && (now - collectionIdsCacheAtMillis) < LIBRARY_IDS_CACHE_TTL_MS) {
            return new HashSet<>(cached);
        }

        synchronized (collectionIdsCacheLock) {
            cached = collectionIdsCache;
            now = System.currentTimeMillis();
            if (cached != null
                    && username.equals(collectionIdsCacheUsername)
                    && safePerPage == collectionIdsCachePerPage
                    && (now - collectionIdsCacheAtMillis) < LIBRARY_IDS_CACHE_TTL_MS) {
                return new HashSet<>(cached);
            }

            try {
                Set<Integer> ids = fetchPagedReleaseIds(
                        safePerPage,
                        "/users/" + DiscogsUrlUtils.urlEncode(username) + "/collection/folders/0/releases",
                        "releases"
                );
                collectionIdsCacheUsername = username;
                collectionIdsCachePerPage = safePerPage;
                collectionIdsCache = Set.copyOf(ids);
                collectionIdsCacheAtMillis = System.currentTimeMillis();
                return ids;
            } catch (Exception e) {
                log.debug("Discogs collection ID fetch failed: {}", e.getMessage());
                if (cached != null
                        && username.equals(collectionIdsCacheUsername)
                        && safePerPage == collectionIdsCachePerPage) {
                    return new HashSet<>(cached);
                }
            }

            return Set.of();
        }
    }

    private Set<Integer> fetchPagedReleaseIds(int safePerPage, String path, String arrayField)
            throws IOException, InterruptedException {
        Set<Integer> ids = new HashSet<>();
        int pagesToFetch = 1;
        for (int page = 1; page <= pagesToFetch && page <= LIBRARY_IDS_MAX_PAGES; page++) {
            String qs = "?sort=added&sort_order=desc&page=" + page + "&per_page=" + safePerPage;
            URI uri = URI.create(apiBase + path + qs);
            HttpRequest req = baseRequest(uri)
                    .timeout(Duration.ofSeconds(12))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = resp.statusCode();
            if (status != 200) {
                throwTransientIfNeeded(status);
                break;
            }

            JsonNode root = mapper.readTree(resp.body());
            if (page == 1) {
                pagesToFetch = Math.max(1, Math.min(LIBRARY_IDS_MAX_PAGES, root.path("pagination").path("pages").asInt(1)));
            }
            Set<Integer> pageIds = parseLibraryReleaseIds(root, arrayField);
            if (pageIds.isEmpty()) {
                break;
            }
            ids.addAll(pageIds);
        }
        return ids;
    }

    private Set<Integer> parseLibraryReleaseIds(JsonNode root, String arrayField) {
        JsonNode rows = root.get(arrayField);
        if (rows == null || !rows.isArray()) {
            return Set.of();
        }

        Set<Integer> ids = new HashSet<>();
        for (JsonNode item : rows) {
            JsonNode idNode = item.findValue("id");
            if (idNode != null && idNode.isNumber()) {
                ids.add(idNode.asInt());
            }
        }
        return ids;
    }

    private void invalidateWishlistIdsCache() {
        synchronized (wishlistIdsCacheLock) {
            wishlistIdsCacheUsername = null;
            wishlistIdsCachePerPage = 0;
            wishlistIdsCache = null;
            wishlistIdsCacheAtMillis = 0L;
        }
    }

    public Optional<String> searchOnce(String artist, String album, Integer year, String trackTitle, boolean master)
            throws IOException, InterruptedException {
        if (!isConfigured()) {
            return Optional.empty();
        }
        StringBuilder qs = new StringBuilder();
        qs.append("type=").append(master ? "master" : "release");
        qs.append("&per_page=5&sort=relevance");
        if (artist != null && !artist.isBlank()) {
            qs.append("&artist=").append(DiscogsUrlUtils.urlEncode(artist));
        }
        if (album != null && !album.isBlank()) {
            qs.append("&release_title=").append(DiscogsUrlUtils.urlEncode(album));
        }
        if (trackTitle != null && !trackTitle.isBlank()) {
            qs.append("&track=").append(DiscogsUrlUtils.urlEncode(trackTitle));
        }
        if (year != null && year > 1900 && year < 2100) {
            qs.append("&year=").append(year);
        }

        URI uri = URI.create(apiBase + "/database/search?" + qs);

        HttpRequest req = baseRequest(uri)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (!isSearchStatusSuccessful(resp.statusCode())) {
            throwTransientIfNeeded(resp.statusCode());
            return Optional.empty();
        }

        JsonNode root = mapper.readTree(resp.body());
        JsonNode results = root.get("results");
        if (results == null || !results.isArray() || results.size() == 0) {
            return Optional.empty();
        }

        for (JsonNode item : results) {
            JsonNode uriNode = item.get("uri");
            if (uriNode != null && !uriNode.isNull()) {
                String uriStr = uriNode.asText();
                if (uriStr != null && !uriStr.isBlank()) {
                    if (uriStr.startsWith("/")) {
                        uriStr = "https://www.discogs.com" + uriStr;
                    }
                    return Optional.ofNullable(DiscogsUrlUtils.sanitizeDiscogsWebUrl(uriStr));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<String> searchOnceQ(String q, Integer year, String expectedArtist, String expectedAlbum)
            throws IOException, InterruptedException {
        if (!isConfigured()) {
            return Optional.empty();
        }
        if ((q == null || q.isBlank()) && (expectedArtist == null || expectedAlbum == null)) {
            return Optional.empty();
        }
        StringBuilder qs = new StringBuilder();
        if (q != null && !q.isBlank()) {
            qs.append("q=").append(DiscogsUrlUtils.urlEncode(q));
        } else {
            qs.append("q=").append(DiscogsUrlUtils.urlEncode((expectedArtist == null ? "" : expectedArtist) + " " + (expectedAlbum == null ? "" : expectedAlbum)));
        }
        qs.append("&per_page=10&sort=relevance");
        if (year != null && year > 1900 && year < 2100) {
            qs.append("&year=").append(year);
        }

        URI uri = URI.create(apiBase + "/database/search?" + qs);
        HttpRequest req = baseRequest(uri)
                .timeout(Duration.ofSeconds(12))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (!isSearchStatusSuccessful(resp.statusCode())) {
            throwTransientIfNeeded(resp.statusCode());
            return Optional.empty();
        }

        JsonNode root = mapper.readTree(resp.body());
        JsonNode results = root.get("results");
        if (results == null || !results.isArray() || results.size() == 0) {
            return Optional.empty();
        }

        String expArtist = expectedArtist == null ? null : DiscogsNormalizer.canonicalizeWhitespace(DiscogsNormalizer.stripDiacritics(expectedArtist)).toLowerCase();
        String expAlbumRaw = expectedAlbum == null ? null : DiscogsNormalizer.canonicalizeWhitespace(DiscogsNormalizer.stripDiacritics(expectedAlbum)).toLowerCase();
        String expAlbumLight = expectedAlbum == null ? null : DiscogsNormalizer.canonicalizeWhitespace(
                DiscogsNormalizer.stripDiacritics(DiscogsNormalizer.normalizeTitleLevel(expectedAlbum, DiscogsNormalizer.NormLevel.LIGHT))
        ).toLowerCase();

        String masterCandidate = null;
        String releaseCandidate = null;
        String topAnyCandidate = null;

        for (JsonNode item : results) {
            JsonNode uriNode = item.get("uri");
            if (uriNode == null || uriNode.isNull()) continue;
            String uriStr = uriNode.asText();
            if (uriStr == null || uriStr.isBlank()) continue;
            if (uriStr.startsWith("/")) uriStr = "https://www.discogs.com" + uriStr;
            uriStr = DiscogsUrlUtils.sanitizeDiscogsWebUrl(uriStr);
            if (uriStr == null) continue;

            String type = item.hasNonNull("type") ? item.get("type").asText() : "";
            String title = item.hasNonNull("title") ? item.get("title").asText() : "";
            String titleNorm = DiscogsNormalizer.canonicalizeWhitespace(DiscogsNormalizer.stripDiacritics(title)).toLowerCase();

            if (("master".equalsIgnoreCase(type) || "release".equalsIgnoreCase(type)) && topAnyCandidate == null) {
                topAnyCandidate = uriStr;
            }

            int sep = title.indexOf(" - ");
            if (sep > 0 && expArtist != null && expAlbumRaw != null) {
                String tArtist = DiscogsNormalizer.canonicalizeWhitespace(DiscogsNormalizer.stripDiacritics(title.substring(0, sep))).toLowerCase();
                String tAlbum = DiscogsNormalizer.canonicalizeWhitespace(DiscogsNormalizer.stripDiacritics(title.substring(sep + 3))).toLowerCase();
                if (tArtist.equals(expArtist) && tAlbum.equals(expAlbumRaw)) {
                    return Optional.of(uriStr);
                }
            }

            boolean artistOk = (expArtist == null) || titleNorm.contains(expArtist);
            boolean albumOk = (expAlbumLight == null) || titleNorm.contains(expAlbumLight);
            if (artistOk && albumOk) {
                if ("master".equalsIgnoreCase(type) && masterCandidate == null) masterCandidate = uriStr;
                if ("release".equalsIgnoreCase(type) && releaseCandidate == null) releaseCandidate = uriStr;
            }
        }

        if (masterCandidate != null) return Optional.of(masterCandidate);
        if (releaseCandidate != null) return Optional.of(releaseCandidate);
        if (topAnyCandidate != null) return Optional.of(topAnyCandidate);
        return Optional.empty();
    }

    private HttpRequest.Builder baseRequest(URI uri) {
        return baseRequest(uri, "GET", null);
    }

    private HttpRequest.Builder baseRequest(URI uri, String method, Map<String, String> formParams) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent);

        if (hasOAuthCredentials()) {
            Map<String, String> oauthParams = DiscogsOAuth1.newOAuthParameters(consumerKey, token);
            String header = DiscogsOAuth1.buildAuthorizationHeader(
                    method,
                    uri,
                    oauthParams,
                    consumerSecret,
                    tokenSecret,
                    formParams
            );
            builder.header("Authorization", header);
            return builder;
        }

        builder.header("Authorization", "Discogs token=" + token);
        return builder;
    }

    private static boolean isSearchStatusSuccessful(int statusCode) {
        return statusCode == 200;
    }

    private static void throwTransientIfNeeded(int statusCode) throws IOException {
        if (statusCode == 429 || statusCode >= 500) {
            throw new IOException("discogs_transient_status:" + statusCode);
        }
    }

    private static String parseFormats(JsonNode formatsNode) {
        if (formatsNode == null || !formatsNode.isArray()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode node : formatsNode) {
            if (node != null && node.isTextual()) {
                parts.add(node.asText());
            }
        }
        return parts.isEmpty() ? null : String.join(" • ", parts);
    }

    private static String optText(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.asText();
    }
}
