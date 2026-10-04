package com.hctamlyniv.discogs;

import com.fasterxml.jackson.databind.JsonNode;
import com.hctamlyniv.discogs.model.CatalogData.Album;
import com.hctamlyniv.discogs.model.CatalogData.Artist;
import com.hctamlyniv.discogs.model.CatalogData.ArtistLink;
import com.hctamlyniv.discogs.model.CatalogData.Item;
import com.hctamlyniv.discogs.model.CatalogData.Search;
import com.hctamlyniv.discogs.model.CatalogData.Track;
import com.hctamlyniv.discogs.model.CatalogResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class DiscogsCatalog {
    private final DiscogsApiClient api;
    private final Map<String, CachedSearch> searches = new LinkedHashMap<>();
    private record CachedSearch(long time, Search data) {}

    public DiscogsCatalog(DiscogsApiClient api) {
        this.api = api;
    }

    public CatalogResult<Search> search(String query, String type) throws IOException, InterruptedException {
        String key = type + ":" + query;
        synchronized (searches) {
            var cached = searches.get(key);
            if (cached != null && System.nanoTime() - cached.time() < 60_000_000_000L) {
                return CatalogResult.success(cached.data());
            }
        }
        String encoded = DiscogsUrlUtils.urlEncode(query);
        String filter = switch (type) {
            case "artists" -> "q=" + encoded + "&type=artist";
            case "albums" -> "q=" + encoded + "&type=master";
            case "songs" -> "track=" + encoded + "&type=master";
            default -> "q=" + encoded;
        };
        var response = api.fetchCatalogResource("/database/search?" + filter + "&per_page=40&page=1");
        if (response.status() != 200) return response.failure();
        List<Item> direct = searchItems(response.data(), "songs".equals(type) ? query : null);
        if (!"all".equals(type)) return cacheSearch(key, direct.stream().limit(20).toList());

        var songs = api.fetchCatalogResource("/database/search?track=" + encoded + "&type=master&per_page=20&page=1");
        if (songs.status() != 200) return songs.failure();
        List<Item> songItems = searchItems(songs.data(), query);
        Map<String, Item> merged = new LinkedHashMap<>();
        for (int i = 0; i < Math.max(direct.size(), songItems.size()) && merged.size() < 20; i++) {
            if (i < direct.size()) putItem(merged, direct.get(i));
            if (i < songItems.size() && merged.size() < 20) putItem(merged, songItems.get(i));
        }
        return cacheSearch(key, List.copyOf(merged.values()));
    }

    private CatalogResult<Search> cacheSearch(String key, List<Item> items) {
        var search = new Search(items);
        synchronized (searches) {
            if (searches.size() >= 64) searches.remove(searches.keySet().iterator().next());
            searches.put(key, new CachedSearch(System.nanoTime(), search));
        }
        return CatalogResult.success(search);
    }

    public CatalogResult<Artist> artist(int id, int page) throws IOException, InterruptedException {
        var profile = api.fetchCatalogResource("/artists/" + id);
        if (profile.status() != 200) return profile.failure();
        var releases = api.fetchCatalogResource("/artists/" + id
                + "/releases?sort=year&sort_order=desc&per_page=100&page=" + page);
        if (releases.status() != 200) return releases.failure();
        JsonNode root = releases.data();
        String name = text(profile.data(), "name");
        if (name.isBlank() || !root.path("releases").isArray()) throw new IOException("Invalid Discogs artist response");
        Map<String, Item> albums = new LinkedHashMap<>();
        for (JsonNode row : array(root, "releases")) {
            // Main releases include albums, EPs and singles. Guest credits are excluded.
            if (!"Main".equals(row.path("role").asText("Main"))) continue;
            String kind = row.path("type").asText();
            int itemId = row.path("id").asInt();
            if (itemId < 1 || !("master".equals(kind) || "release".equals(kind))) continue;
            if ("release".equals(kind) && row.path("master_id").asInt() > 0) continue;
            putItem(albums, new Item(itemId, kind, text(row, "title"), name, year(row),
                    image(row), webUrl(kind, itemId), null));
        }
        int pages = Math.max(1, root.path("pagination").path("pages").asInt(1));
        return CatalogResult.success(new Artist(id, name, image(profile.data()), webUrl("artist", id),
                List.copyOf(albums.values()), page, pages, root.path("pagination").path("items").asInt()));
    }

    public CatalogResult<Album> album(int id, String kind) throws IOException, InterruptedException {
        var response = api.fetchCatalogResource("/" + ("master".equals(kind) ? "masters/" : "releases/") + id);
        if (response.status() != 200) return response.failure();
        JsonNode root = response.data();
        if (text(root, "title").isBlank()) throw new IOException("Invalid Discogs album response");
        List<ArtistLink> artists = new ArrayList<>();
        for (JsonNode row : array(root, "artists")) {
            if (row.path("id").asInt() > 0) artists.add(new ArtistLink(row.path("id").asInt(), text(row, "name")));
        }
        List<Track> tracks = new ArrayList<>();
        collectTracks(root, tracks);
        String artist = String.join(", ", artists.stream().map(ArtistLink::name).toList());
        Integer offers = root.path("num_for_sale").isNumber() ? Math.max(0, root.path("num_for_sale").asInt()) : null;
        Boolean vinyl = null;
        if (root.path("formats").isArray()) {
            vinyl = false;
            for (JsonNode format : root.path("formats")) {
                if ("Vinyl".equalsIgnoreCase(format.path("name").asText())) vinyl = true;
            }
        }
        String marketplace = "https://www.discogs.com/sell/list?" + ("master".equals(kind) ? "master_id=" : "release_id=")
                + id + "&format=Vinyl";
        return CatalogResult.success(new Album(id, kind, text(root, "title"), artist, year(root), image(root),
                webUrl(kind, id), List.copyOf(artists), List.copyOf(tracks), offers, vinyl, marketplace));
    }

    private static void collectTracks(JsonNode root, List<Track> tracks) {
        for (JsonNode row : array(root, "tracklist")) {
            if (!"heading".equals(row.path("type_").asText())) {
                tracks.add(new Track(text(row, "position"), text(row, "title"), text(row, "duration")));
            }
            for (JsonNode sub : array(row, "sub_tracks")) {
                tracks.add(new Track(text(sub, "position"), text(sub, "title"), text(sub, "duration")));
            }
        }
    }

    private static List<Item> searchItems(JsonNode root, String song) throws IOException {
        if (!root.path("results").isArray()) throw new IOException("Invalid Discogs search response");
        Map<String, Item> items = new LinkedHashMap<>();
        for (JsonNode row : array(root, "results")) {
            String kind = row.path("type").asText();
            int id = row.path("id").asInt();
            if (id < 1 || !("artist".equals(kind) || "master".equals(kind) || "release".equals(kind))) continue;
            if ("release".equals(kind) && row.path("master_id").asInt() > 0) {
                kind = "master";
                id = row.path("master_id").asInt();
            }
            String title = text(row, "title");
            String artist = "";
            int separator = title.indexOf(" - ");
            if (!"artist".equals(kind) && separator > 0) {
                artist = title.substring(0, separator);
                title = title.substring(separator + 3);
            }
            putItem(items, new Item(id, kind, title, artist, year(row), image(row), webUrl(kind, id), song));
        }
        return List.copyOf(items.values());
    }

    private static void putItem(Map<String, Item> items, Item item) {
        items.putIfAbsent(item.kind() + ":" + item.id(), item);
    }

    private static Iterable<JsonNode> array(JsonNode node, String key) {
        return node.path(key).isArray() ? node.path(key) : List.of();
    }

    private static String text(JsonNode node, String key) {
        return node.path(key).asText("");
    }

    private static Integer year(JsonNode node) {
        int year = node.path("year").asInt();
        return year > 0 ? year : null;
    }

    private static String image(JsonNode node) {
        String candidate = text(node, "thumb");
        if (candidate.isBlank() && node.path("images").isArray() && !node.path("images").isEmpty()) {
            candidate = text(node.path("images").get(0), "uri");
        }
        return DiscogsUrlUtils.sanitizeDiscogsWebUrl(candidate);
    }

    private static String webUrl(String kind, int id) {
        return "https://www.discogs.com/" + kind + "/" + id;
    }
}
