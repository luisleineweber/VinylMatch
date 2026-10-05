package Server.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Renders the return page for Spotify and Discogs login. */
public final class OAuthCallbackPage {
    private OAuthCallbackPage() {}

    public enum Provider {
        SPOTIFY("Spotify", "spotify-auth-callback", "/"),
        DISCOGS("Discogs", "discogs-auth-callback", "/playlist.html");

        private final String name;
        private final String eventType;
        private final String returnPath;

        Provider(String name, String eventType, String returnPath) {
            this.name = name;
            this.eventType = eventType;
            this.returnPath = returnPath;
        }
    }

    public static void send(HttpExchange exchange, Provider provider, boolean success, String code, String message) throws IOException {
        byte[] body = render(provider, success, code, message).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    public static String render(Provider provider, boolean success, String code, String message) {
        String title = provider.name + (success ? " connected" : " connection failed");
        return """
                <!DOCTYPE html>
                <html lang="en" data-callback-success="%s" data-callback-type="%s"
                      data-callback-target="%s" data-callback-code="%s" data-callback-message="%s">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>VinylMatch - %s</title>
                    <script src="/dist/theme-bootstrap.js"></script>
                    <script src="/dist/oauth-callback.js"></script>
                    <link rel="stylesheet" href="/styles/oauth-callback.css">
                    <link href="https://fonts.googleapis.com/css2?family=Archivo+Black&family=Space+Mono:wght@400;700&display=swap" rel="stylesheet">
                </head>
                <body>
                    <main class="callback-page" aria-labelledby="callback-title">
                        <p class="callback-brand">Vinyl Match</p>
                        <h1 id="callback-title">%s</h1>
                        <p class="callback-message">%s</p>
                        <p class="callback-hint">%s</p>
                        <a id="oauth-callback-action" class="callback-action" href="%s">Return to VinylMatch <span aria-hidden="true">&rarr;</span></a>
                    </main>
                </body>
                </html>
                """.formatted(success, provider.eventType, provider.returnPath, escapeHtml(code), escapeHtml(message), title,
                title, success ? "Returning to your music." : escapeHtml(message),
                success ? "If this window stays open, use the link below."
                        : "Return to the app to try again.", provider.returnPath);
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
