package com.hctamlyniv.discogs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DiscogsCatalogTest {
    private HttpServer server;
    private ExecutorService executor;
    private DiscogsCatalog catalog;
    private final AtomicReference<String> query = new AtomicReference<>();
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            assertEquals("Discogs token=test", exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, status.get(), body.get());
        });
        server.start();
        catalog = new DiscogsCatalog(new DiscogsApiClient(HttpClient.newHttpClient(), new ObjectMapper(),
                "test", "VinylMatch/Test", "http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void teardown() {
        server.stop(0);
        executor.close();
    }

    @Test
    void capsSuggestionsAtTwentyAndGroupsEditionsByMaster() throws Exception {
        StringBuilder rows = new StringBuilder("{\"results\":[");
        rows.append("{\"id\":99,\"master_id\":1,\"type\":\"release\",\"title\":\"Artist - Album 1\"},");
        for (int i = 1; i <= 30; i++) {
            if (i > 1) rows.append(',');
            rows.append("{\"id\":").append(i).append(",\"type\":\"master\",\"title\":\"Artist - Album ")
                    .append(i).append("\",\"year\":2001,\"thumb\":\"https://evil.example/cover.png\"}");
        }
        body.set(rows.append("]}").toString());
        var result = catalog.search("Artist & Album", "albums");
        assertEquals(200, result.status());
        assertEquals(20, result.data().items().size());
        var first = result.data().items().getFirst();
        assertEquals(1, first.id());
        assertEquals("master", first.kind());
        assertEquals("Album 1", first.title());
        assertEquals("Artist", first.artist());
        assertNull(result.data().items().get(1).image());
        assertEquals("https://www.discogs.com/master/1", first.url());
        assertTrue(query.get().contains("q=Artist+%26+Album&type=release"));
    }

    @Test
    void songSearchUsesTrackFilterAndKeepsSongContext() throws Exception {
        body.set("{\"results\":[{\"id\":7,\"type\":\"master\",\"title\":\"Daft Punk - Discovery\"}]}");
        var result = catalog.search("One More Time", "songs");
        assertTrue(query.get().contains("track=One+More+Time&type=release"));
        assertFalse(query.get().contains("q="));
        assertEquals("One More Time", result.data().items().getFirst().songQuery());
    }

    @Test
    void allSearchIncludesArtistsAlbumsAndSongMatchesWithoutDuplicates() throws Exception {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            boolean songs = exchange.getRequestURI().getRawQuery().contains("track=");
            respond(exchange, 200, songs
                    ? "{\"results\":[{\"id\":7,\"type\":\"master\",\"title\":\"Artist - Song Album\"}]}"
                    : "{\"results\":[{\"id\":4,\"type\":\"artist\",\"title\":\"Artist\"},{\"id\":8,\"type\":\"master\",\"title\":\"Artist - Album\"},{\"id\":9,\"type\":\"label\",\"title\":\"Label\"}]}");
        });
        var result = catalog.search("Artist", "all");
        assertEquals(3, result.data().items().size());
        assertEquals("artist", result.data().items().getFirst().kind());
        assertEquals("Artist", result.data().items().get(1).songQuery());
    }

    @Test
    void artistPageKeepsMainReleasesAndSupportsAllPages() throws Exception {
        server.removeContext("/");
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/releases")) {
                assertTrue(exchange.getRequestURI().getRawQuery().contains("page=2"));
                respond(exchange, 200, """
                        {"pagination":{"pages":3,"items":250},"releases":[
                          {"id":7,"type":"master","title":"Album","role":"Main"},
                          {"id":8,"type":"release","title":"Guest","role":"Appearance"},
                          {"id":9,"type":"release","master_id":7,"title":"Edition","role":"Main"},
                          {"id":10,"type":"release","title":"EP","role":"Main"}]}
                        """);
            } else respond(exchange, 200, "{\"id\":4,\"name\":\"Artist\"}");
        });
        var artist = catalog.artist(4, 2).data();
        assertEquals("Artist", artist.name());
        assertEquals(2, artist.page());
        assertEquals(3, artist.pages());
        assertEquals(2, artist.albums().size());
        assertEquals("EP", artist.albums().get(1).title());
    }

    @Test
    void albumUsesSelectedIdAndPreservesZeroAndUnknownOffers() throws Exception {
        body.set("""
                {"title":"Discovery","year":2001,"artists":[{"id":4,"name":"Daft Punk"}],
                 "num_for_sale":0,"formats":[{"name":"CD"}],"tracklist":[
                 {"type_":"heading","title":"Side A"},
                 {"type_":"track","position":"A1","title":"One More Time","duration":"5:20"}]}
                """);
        var album = catalog.album(7, "release").data();
        assertEquals(7, album.id());
        assertEquals(0, album.offers());
        assertEquals(false, album.vinyl());
        assertEquals("Daft Punk", album.artist());
        assertEquals(4, album.artists().getFirst().id());
        assertEquals(1, album.tracks().size());
        assertTrue(album.marketplaceUrl().contains("release_id=7&format=Vinyl"));
        body.set("{\"title\":\"Album\"}");
        assertNull(catalog.album(7, "master").data().offers());
    }

    @Test
    void expectedDiscogsFailuresRemainVisible() throws Exception {
        body.set("{}");
        status.set(429);
        assertEquals("discogs_rate_limited", catalog.search("Album", "albums").code());
        status.set(403);
        assertEquals("discogs_access_denied", catalog.artist(4, 1).code());
        status.set(404);
        assertEquals(404, catalog.album(7, "master").status());
        status.set(500);
        assertEquals(502, catalog.search("Album", "albums").status());
        var unconfigured = new DiscogsCatalog(new DiscogsApiClient(HttpClient.newHttpClient(), new ObjectMapper(), null, "Test"));
        assertEquals("discogs_not_configured", unconfigured.search("Album", "albums").code());
    }

    @Test
    void cachesSuccessfulSearchesButNotFailures() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server.removeContext("/");
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            respond(exchange, status.get(), "{\"results\":[]}");
        });
        status.set(429);
        assertEquals(429, catalog.search("Album", "albums").status());
        status.set(200);
        assertEquals(200, catalog.search("Album", "albums").status());
        assertEquals(200, catalog.search("Album", "albums").status());
        assertEquals(2, requests.get());
    }

    @Test
    void malformedDiscogsDataDoesNotLookLikeAnEmptyCatalog() {
        body.set("{}");
        assertThrows(java.io.IOException.class, () -> catalog.search("Album", "albums"));
        assertThrows(java.io.IOException.class, () -> catalog.artist(4, 1));
        assertThrows(java.io.IOException.class, () -> catalog.album(7, "master"));
    }

    @Test
    void albumAndSongSearchesIncludeReleasesWithoutMasters() throws Exception {
        server.removeContext("/");
        server.createContext("/", exchange -> respond(exchange, 200,
                exchange.getRequestURI().getRawQuery().contains("type=master")
                        ? "{\"results\":[]}"
                        : "{\"results\":[{\"id\":77,\"type\":\"release\",\"title\":\"Artist - Standalone\"}]}"));
        for (String type : new String[]{"albums", "songs"}) {
            var items = catalog.search("Standalone", type).data().items();
            assertEquals(1, items.size(), type);
            assertEquals("release", items.getFirst().kind());
            assertEquals(77, items.getFirst().id());
        }
    }

    @Test
    void allSearchKeepsSongContextWhenBothSearchesFindTheSameAlbum() throws Exception {
        body.set("{\"results\":[{\"id\":7,\"type\":\"master\",\"title\":\"Artist - Same Song\"}]}");
        var items = catalog.search("Same Song", "all").data().items();
        assertEquals(1, items.size());
        assertEquals("Same Song", items.getFirst().songQuery());
    }

    @Test
    void allSearchKeepsSongContextForDuplicatesBeyondTheResultLimit() throws Exception {
        StringBuilder direct = new StringBuilder("{\"results\":[");
        StringBuilder songs = new StringBuilder("{\"results\":[");
        for (int i = 1; i <= 20; i++) {
            if (i > 1) { direct.append(','); songs.append(','); }
            direct.append("{\"id\":").append(i).append(",\"type\":\"master\",\"title\":\"Artist - Album\"}");
            songs.append("{\"id\":").append(i == 20 ? 1 : i + 1)
                    .append(",\"type\":\"master\",\"title\":\"Artist - Album\"}");
        }
        String directBody = direct.append("]}").toString();
        String songBody = songs.append("]}").toString();
        server.removeContext("/");
        server.createContext("/", exchange -> respond(exchange, 200,
                exchange.getRequestURI().getRawQuery().contains("track=") ? songBody : directBody));
        var items = catalog.search("Song", "all").data().items();
        assertEquals(20, items.size());
        assertEquals(1, items.getFirst().id());
        assertTrue(items.stream().allMatch(item -> "Song".equals(item.songQuery())));
    }

    @Test
    void allSearchStartsBothRequestsBeforeEitherCompletes() throws Exception {
        var started = new CountDownLatch(2);
        server.removeContext("/");
        server.createContext("/", exchange -> {
            started.countDown();
            try {
                respond(exchange, started.await(2, TimeUnit.SECONDS) ? 200 : 503, "{\"results\":[]}");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        assertEquals(200, catalog.search("Album", "all").status());
        assertEquals(0, started.getCount());
    }

    @Test
    void artistPagesReuseTheProfileButFetchEachRequestedPage() throws Exception {
        var profiles = new AtomicInteger();
        var pages = new AtomicInteger();
        server.removeContext("/");
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/releases")) {
                pages.incrementAndGet();
                respond(exchange, 200, "{\"pagination\":{\"pages\":3},\"releases\":[]}");
            } else {
                profiles.incrementAndGet();
                respond(exchange, 200, "{\"name\":\"Artist\"}");
            }
        });
        assertEquals(1, catalog.artist(4, 1).data().page());
        assertEquals(2, catalog.artist(4, 2).data().page());
        assertEquals(1, profiles.get());
        assertEquals(2, pages.get());
    }

    @Test
    void artistProfileFailuresAreNotCached() throws Exception {
        var profiles = new AtomicInteger();
        server.removeContext("/");
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().endsWith("/releases")) {
                respond(exchange, 200, "{\"releases\":[]}");
            } else {
                int attempt = profiles.incrementAndGet();
                respond(exchange, attempt == 1 ? 429 : 200, "{\"name\":\"Artist\"}");
            }
        });
        assertEquals(429, catalog.artist(4, 1).status());
        assertEquals(200, catalog.artist(4, 1).status());
        assertEquals(200, catalog.artist(4, 2).status());
        assertEquals(2, profiles.get());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
