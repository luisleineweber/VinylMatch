package com.hctamlyniv.spotify;

import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.junit.jupiter.api.Test;
import se.michaelthelin.spotify.IHttpManager;
import se.michaelthelin.spotify.SpotifyApi;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SpotifyPlaylistFlowTest {
    @Test
    void capsPlaylistRequestsAtFiftyItems() throws Exception {
        var http = new FixtureHttpManager(uri -> {
            assertTrue(uri.getQuery().contains("limit=50"));
            return "{\"items\":[],\"total\":0,\"limit\":50,\"offset\":0,\"next\":null}";
        });
        new SpotifyPlaylistReader(api(http)).getPlaylistItems("playlist-id", 0, 100);
        assertEquals(1, http.requests.size());
    }

    @Test
    void currentUserPlaylistsReadCountsFromItems() throws Exception {
        var http = new FixtureHttpManager(uri -> {
            assertEquals("/v1/me/playlists", uri.getPath());
            return """
                    {"items":[{"id":"playlist-id","name":"Playlist","items":{"total":42}}],
                    "total":1,"limit":50,"offset":0,"next":null}
                    """;
        });
        var page = api(http).getCurrentUsersPlaylists().limit(50).offset(0).build().execute();
        assertEquals(1, http.requests.size());
        assertEquals(42, page.getItems()[0].getItems().getTotal());
    }

    @Test
    void readsNewPlaylistItemsAcrossPagesAndSkipsEpisodesAndUnavailableItems() throws Exception {
        var http = new FixtureHttpManager(uri -> {
            assertEquals("/v1/playlists/playlist-id/items", uri.getPath());
            assertTrue(uri.getQuery().contains("limit=50"));
            boolean first = uri.getQuery().contains("offset=0");
            String items = first
                    ? track("track-1", "album-1") + ",{\"item\":{\"type\":\"episode\",\"id\":\"episode-1\",\"name\":\"Podcast\"}}"
                    : "{\"item\":null}," + track("track-2", "album-2");
            return "{\"items\":[" + items + "],\"total\":4,\"offset\":" + (first ? 0 : 2)
                    + ",\"limit\":50,\"next\":" + (first ? "\"https://api.spotify.com/v1/playlists/playlist-id/items?offset=2\"" : "null") + "}";
        });
        var result = new SpotifyPlaylistReader(api(http)).getAllPlaylistItems("playlist-id", 0, -1);

        assertEquals(2, http.requests.size());
        assertTrue(http.requests.get(1).getQuery().contains("offset=2"));
        assertEquals(4, result.total());
        assertEquals(4, result.nextOffset());
        assertFalse(result.hasMore());
        assertEquals(Set.of("album-1", "album-2"), new SpotifyAlbumBatchLoader(api(http)).extractAlbumIds(result.items()));
        var tracks = new PlaylistAssembler(null).assembleTrackData(result.items(), Map.of());
        assertEquals(2, tracks.size());
        assertEquals("track-1", tracks.getFirst().getSpotifyTrackId());
        assertEquals("Album album-1", tracks.getFirst().getAlbum());
        assertEquals(2001, tracks.getFirst().getReleaseYear());
    }

    @Test
    void pagedReadStopsAtTheRequestedLimitAndKeepsNextOffset() throws Exception {
        var http = new FixtureHttpManager(uri -> {
            assertEquals("/v1/playlists/playlist-id/items", uri.getPath());
            assertTrue(uri.getQuery().contains("offset=5"));
            assertTrue(uri.getQuery().contains("limit=1"));
            return "{\"items\":[" + track("track-1", "album-1")
                    + "],\"total\":10,\"offset\":5,\"limit\":1,\"next\":\"https://api.spotify.com/next\"}";
        });
        var result = new SpotifyPlaylistReader(api(http)).getAllPlaylistItems("playlist-id", 5, 1);
        assertEquals(1, http.requests.size());
        assertEquals(5, result.offset());
        assertEquals(6, result.nextOffset());
        assertTrue(result.hasMore());
    }

    @Test
    void loadsAlbumIdsInBatchesAndKeepsBarcodes() {
        var http = new FixtureHttpManager(uri -> {
            assertEquals("/v1/albums", uri.getPath());
            String[] ids = uri.getQuery().replaceFirst("^ids=", "").split(",");
            assertTrue(ids.length > 0 && ids.length <= 20);
            String albums = java.util.Arrays.stream(ids).map(id -> "{\"id\":\"" + id
                    + "\",\"name\":\"Album\",\"external_ids\":{\"upc\":\"123456789012\"}}")
                    .collect(Collectors.joining(","));
            return "{\"albums\":[" + albums + "]}";
        });
        Set<String> ids = IntStream.range(0, 21).mapToObj(i -> "album-" + i)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        var albums = new SpotifyAlbumBatchLoader(api(http)).loadAlbumDetails(ids);
        assertEquals(2, http.requests.size());
        assertEquals(ids, albums.keySet());
        assertEquals("123456789012", new BarcodeExtractor().extractBarcode(albums.get("album-0")));
    }

    @Test
    void emptyAlbumSetDoesNotCallSpotify() {
        var http = new FixtureHttpManager(uri -> { throw new AssertionError("Unexpected Spotify request"); });
        assertTrue(new SpotifyAlbumBatchLoader(api(http)).loadAlbumDetails(Set.of()).isEmpty());
        assertTrue(http.requests.isEmpty());
    }

    private static SpotifyApi api(IHttpManager http) {
        return new SpotifyApi.Builder().setAccessToken("test-token").setHttpManager(http).build();
    }

    private static String track(String id, String albumId) {
        return """
                {"item":{"type":"track","id":"%s","name":"Song","artists":[{"name":"Artist"}],
                "album":{"id":"%s","name":"Album %s","release_date":"2001-01-01"}}}
                """.formatted(id, albumId, albumId);
    }

    private static final class FixtureHttpManager implements IHttpManager {
        private final Function<URI, String> response;
        private final List<URI> requests = new ArrayList<>();

        private FixtureHttpManager(Function<URI, String> response) { this.response = response; }

        @Override public String get(URI uri, Header[] headers) {
            requests.add(uri);
            return response.apply(uri);
        }
        @Override public String post(URI uri, Header[] headers, HttpEntity body) { throw new AssertionError("Unexpected POST"); }
        @Override public String put(URI uri, Header[] headers, HttpEntity body) { throw new AssertionError("Unexpected PUT"); }
        @Override public String delete(URI uri, Header[] headers, HttpEntity body) { throw new AssertionError("Unexpected DELETE"); }
    }
}
