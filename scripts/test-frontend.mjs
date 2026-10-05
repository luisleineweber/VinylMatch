import { createServer } from "node:http";
import { readFile } from "node:fs/promises";
import { dirname, extname, isAbsolute, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const frontend = resolve(root, "src/main/frontend");
const tests = resolve(root, "src/test/frontend");
const port = Number(process.env.FRONTEND_TEST_PORT || 8782);
const types = { ".html": "text/html", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml" };

const server = createServer(async (request, response) => {
    try {
        if (request.method !== "GET") {
            response.writeHead(405).end();
            return;
        }
        const path = decodeURIComponent(new URL(request.url, "http://127.0.0.1").pathname);
        const isTest = path.startsWith("/tests/");
        const base = isTest ? tests : frontend;
        const file = resolve(base, "." + (isTest ? path.slice(6) : path));
        const within = relative(base, file);
        if (within.startsWith("..") || isAbsolute(within)) {
            response.writeHead(403).end();
            return;
        }
        const body = await readFile(file);
        response.writeHead(200, { "Content-Type": types[extname(file)] || "application/octet-stream", "Cache-Control": "no-store" });
        response.end(body);
    } catch (error) {
        if (error.code === "ENOENT" || error.code === "EISDIR") {
            response.writeHead(404).end();
        } else if (error instanceof URIError) {
            response.writeHead(400).end();
        } else {
            console.error("Frontend test server failed", error);
            response.writeHead(500).end();
        }
    }
});

server.listen(port, "127.0.0.1", () => console.log(`Open http://127.0.0.1:${port}/tests/quicksearch.html`));
