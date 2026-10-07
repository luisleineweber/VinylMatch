package Server.routes;

import Server.http.HttpUtils;
import com.hctamlyniv.DiscogsService;
import com.hctamlyniv.discogs.DiscogsApiClient;
import com.hctamlyniv.discogs.DiscogsCatalog;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class QuickSearchRoutesTest {
    @TempDir Path cache;
    private HttpServer server;
    private String base;
    private final AtomicInteger resolutions = new AtomicInteger();
    private final AtomicReference<DiscogsService> service = new AtomicReference<>();

    @BeforeEach
    void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        service.set(new DiscogsService(null, "Test", cache));
        new QuickSearchRoutes(exchange -> {
            resolutions.incrementAndGet();
            return service.get();
        }).register(server);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void teardown() { server.stop(0); }

    @Test
    void rejectsInvalidInputsBeforeResolvingCredentials() throws Exception {
        for (String path : new String[]{
                "/api/quicksearch?q=a", "/api/quicksearch?q=Album&type=labels",
                "/api/quicksearch?q=" + "a".repeat(161),
                "/api/quicksearch/album?id=0", "/api/quicksearch/album?id=1&kind=artist",
                "/api/quicksearch/artist?id=1&page=-1", "/api/quicksearch/artist?id=1&page=10001",
                "/api/quicksearch/artist?id=9999999999", "/api/quicksearch?q=%25invalid&type=no"}) {
            assertEquals(400, get(path).statusCode(), path);
        }
        assertEquals(0, resolutions.get());
    }

    @Test
    void missingDiscogsCredentialsReturnAnErrorInsteadOfAnEmptyList() throws Exception {
        var response = get("/api/quicksearch?q=Discovery&type=albums");
        assertEquals(503, response.statusCode());
        var json = HttpUtils.getMapper().readTree(response.body());
        assertEquals("discogs_not_configured", json.path("error").path("code").asText());
        assertFalse(json.has("items"));
    }

    @Test
    void rejectsUnknownPathsAndSupportsOnlyGetAndPreflight() throws Exception {
        assertEquals(404, get("/api/quicksearch/unrelated?q=Album").statusCode());
        var client = HttpClient.newHttpClient();
        var uri = URI.create(base + "/api/quicksearch");
        var post = HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(405, client.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
        var options = HttpRequest.newBuilder(uri).method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build();
        assertEquals(204, client.send(options, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(0, resolutions.get());
    }

    @Test
    void servesSearchArtistAndSelectedAlbumThroughTheRealCatalog() throws Exception {
        var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = switch (path) {
                case "/database/search" -> "{\"results\":[{\"id\":4,\"type\":\"artist\",\"title\":\"Daft Punk\"}]}";
                case "/artists/4" -> "{\"id\":4,\"name\":\"Daft Punk\"}";
                case "/artists/4/releases" -> "{\"pagination\":{\"pages\":1},\"releases\":[{\"id\":7,\"type\":\"master\",\"title\":\"Discovery\",\"role\":\"Main\"}]}";
                case "/masters/7" -> "{\"id\":7,\"title\":\"Discovery\",\"num_for_sale\":42,\"artists\":[{\"id\":4,\"name\":\"Daft Punk\"}]}";
                default -> "{}";
            };
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        upstream.start();
        try {
            var catalog = new DiscogsCatalog(new DiscogsApiClient(HttpClient.newHttpClient(), HttpUtils.getMapper(),
                    "test", "Test", "http://127.0.0.1:" + upstream.getAddress().getPort()));
            service.set(new DiscogsService(null, "Test", cache) {
                @Override public DiscogsCatalog catalog() { return catalog; }
            });
            var search = get("/api/quicksearch?q=Daft+Punk&type=artists");
            assertEquals(200, search.statusCode());
            assertEquals(4, HttpUtils.getMapper().readTree(search.body()).path("items").get(0).path("id").asInt());
            var artist = get("/api/quicksearch/artist?id=4");
            assertEquals(200, artist.statusCode());
            assertEquals(7, HttpUtils.getMapper().readTree(artist.body()).path("albums").get(0).path("id").asInt());
            var album = get("/api/quicksearch/album?id=7&kind=master");
            assertEquals(200, album.statusCode());
            assertEquals(42, HttpUtils.getMapper().readTree(album.body()).path("offers").asInt());
            // An artist request has no album kind, even if a client supplies an extra parameter.
            assertEquals(200, get("/api/quicksearch/artist?id=4&kind=artist").statusCode());
        } finally {
            upstream.stop(0);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
