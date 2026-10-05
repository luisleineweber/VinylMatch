# VinylMatch

[![CI](https://github.com/hctamlyniv/VinylMatch/actions/workflows/ci.yml/badge.svg)](https://github.com/hctamlyniv/VinylMatch/actions/workflows/ci.yml)
[![Deploy](https://github.com/hctamlyniv/VinylMatch/actions/workflows/deploy.yml/badge.svg)](https://github.com/hctamlyniv/VinylMatch/actions/workflows/deploy.yml)
[![Coverage](https://img.shields.io/badge/coverage-gated%20at%2050%25-green.svg)](./pom.xml)
[![Java 21](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.org/projects/jdk/21/)
[![Runtime](https://img.shields.io/badge/runtime-java--jar-success.svg)](./pom.xml)

VinylMatch is a lightweight web app + API that matches Spotify playlist tracks to their corresponding vinyl releases on Discogs (and optionally links out to other vinyl webstores).

It's built for collectors, DJs, archivists, and anyone who wants to connect a digital playlist to physical releases without manually searching every record.

## Features

- Spotify OAuth login (hosted multi-user, session-cookie based)
- Playlist import + track metadata extraction
- Progressive Discogs matching with caching and safe fallbacks
- Optional Discogs user token/OAuth login (wishlist + library status)
- Custom vendor links via `config/vendors.json`
- Built-in rate limiting and structured JSON error responses

## How It Works

1. Log in with Spotify
2. Paste a Spotify playlist URL (or 22-char playlist ID) and load it
3. For each track, compute a match candidate (Discogs master/release)
4. Display results and vendor links in the web UI

### Official Spotify Playlists

- VinylMatch supports official/curated Spotify playlists when you paste a direct playlist URL (for example `https://open.spotify.com/playlist/...`).
- Spotify does not provide a public API endpoint to browse/search all official playlists globally.
- Because of this API limitation, official playlists must be opened via direct URL/ID instead of in-app browsing.

## Matching Strategy (Discogs)

The matching engine (`DiscogsService`) uses progressive passes:

- Free-text search (artist + album)
- Light/strict normalization to reduce noise (punctuation, formatting, etc.)
- Structured search (master/release + optional year/track hints)
- Fallback to a Discogs web search URL if API matching is unavailable or inconclusive

## Run Locally

### Requirements

- Java 21+
- Maven 3.8+ (or use the bundled Maven in `./.tools/maven/...` on Windows)
- Spotify API credentials (`SPOTIFY_CLIENT_ID`, `SPOTIFY_CLIENT_SECRET`)
- Optional: Discogs token (`DISCOGS_TOKEN`) for API matching; without it, the app falls back to Discogs web search links

### Setup

1) Clone

```bash
git clone https://github.com/Loues000/VinylMatch.git
cd VinylMatch
```

2) Configure environment

```bash
cp config/env.example .env
```

3) Build and test

```bash
mvn test
mvn package
```

The frontend is served as static files from `src/main/frontend/`.

Windows (PowerShell, with bundled Maven):

```powershell
.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd test
.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd package
```

4) Run

```bash
java -jar target/VinylMatch.jar
```

Open `http://localhost:8888/`.

### Windows scripts

```powershell
.\scripts\setup.ps1
.\scripts\run.ps1 -Build
```

## Hosted Deployment

VinylMatch is designed to run as a hosted multi-user app:

- Build and ship `target/VinylMatch.jar`
- Run under `systemd` (or similar process manager)
- Store runtime config only in environment variables
- Use Redis for persistent sessions in staging/production

### Railway Hosting

Railway is a good fit for VinylMatch because the app already runs as a single Java JAR and exposes a health endpoint on `/api/health/simple`.

#### Railway Setup

1. Create a new Railway project from this GitHub repository.
2. Add a Redis service in the same Railway project.
3. Deploy the app service from the repo root.
4. Add a custom domain in Railway once the first deploy succeeds.
5. Update the Spotify app's redirect URI to match the final HTTPS domain exactly.

#### Railway Config In Repo

- `railway.json` starts the app with `java -jar target/VinylMatch.jar`.
- Railway should use `/api/health/simple` as the health check path.
- Railway injects `PORT`; the app already reads this environment variable.
- The Maven build path for Railway should be `mvn -B -DskipTests clean package`.
- Avoid legacy dashboard overrides like `dependency:list install -Pproduction`; they add work without changing the packaged JAR.

#### Railway Environment Variables

Set these in the Railway app service:

| Variable | Required | Railway note |
|----------|----------|-------------|
| `NVD_API_KEY` | Recommended | Free NVD API key for OWASP Dependency-Check; prevents shared-CI rate-limit failures |
| `SPOTIFY_CLIENT_ID` | Yes | From Spotify Developer Dashboard |
| `SPOTIFY_CLIENT_SECRET` | Yes | From Spotify Developer Dashboard |
| `SPOTIFY_REDIRECT_URI` | Yes | Set to `https://your-domain/api/auth/callback` |
| `PUBLIC_BASE_URL` | Yes | Set to `https://your-domain` |
| `DISCOGS_TOKEN` | Optional | Enables Discogs API matching |
| `DISCOGS_USER_AGENT` | Recommended | Required by Discogs API terms |
| `VINYLMATCH_MASTER_KEY` | Yes | Use a long random secret; keep stable across restarts |
| `REDIS_HOST` | Required in production | Map from Railway Redis host; production fails fast instead of silently using per-instance memory |
| `REDIS_PORT` | Recommended | Map from Railway Redis port |
| `REDIS_PASSWORD` | Recommended | Map from Railway Redis password |

If Railway exposes Redis through reference variables instead of fixed names, map them into the app's expected variables `REDIS_HOST`, `REDIS_PORT`, and `REDIS_PASSWORD`.

`pom.xml` wires `NVD_API_KEY` into `dependency-check-maven`. GitHub CI runs the scanner during
`mvn -B clean verify`; configure the repository secret `NVD_API_KEY` to avoid shared NVD rate limits.
Confirmed vulnerabilities at CVSS 7 or higher fail verification. Feed/network failures are treated as
transient (`failOnError=false`), so they warn instead of masking otherwise valid builds; CI always attempts
to upload the HTML/JSON scan reports, and a missing report is visible as an artifact warning.

#### Domain and Spotify OAuth

After Railway gives you either a generated domain or a custom domain:

1. Set `PUBLIC_BASE_URL=https://your-domain`
2. Set `SPOTIFY_REDIRECT_URI=https://your-domain/api/auth/callback`
3. In Spotify Developer Dashboard, add that exact callback URL
4. Redeploy the Railway service

Spotify redirect URIs must match exactly, including protocol, host, and path.

#### Release Flow On Railway

1. Push code to GitHub.
2. Railway redeploys the service from the connected repo.
3. Open `https://your-domain/api/health/ready` to confirm required production dependencies are ready.
4. Test Spotify login with the hosted callback URL.

### Release Flow

- Push to `develop` to run tests, build `VinylMatch.jar`, and deploy staging via `.github/workflows/deploy.yml`.
- Create and push a version tag like `v0.1.1` to run the production release path.
- Tagged releases publish `VinylMatch.jar` to a GitHub Release and then deploy production.
- Do not use branch pushes to `main` as the production release trigger.

### Release Checklist

1. Run `mvn -B clean verify` locally before cutting a release. This is the same lifecycle used by CI.
2. Bump the version in `pom.xml` if you are shipping a new public version.
3. Merge the release candidate to `develop` and verify staging.
4. Create an annotated tag such as `git tag -a v0.1.1 -m "VinylMatch v0.1.1"` on the commit you want to ship.
5. Push the tag with `git push origin v0.1.1`.
6. Confirm the GitHub Release contains `VinylMatch.jar` and the production environment deploy completes successfully.

### GitHub Environment Requirements

- `staging` should hold `STAGING_SSH_HOST`, `STAGING_SSH_USER`, and `STAGING_SSH_KEY`.
- `production` should hold `PRODUCTION_SSH_HOST`, `PRODUCTION_SSH_USER`, and `PRODUCTION_SSH_KEY`.
- Configure GitHub Environment protection rules if you want manual approval before production deploys.

### Configuration (Environment Variables)

See `config/env.example` for a complete template.

Security note:
- Never paste real credentials into docs or scripts.
- If credentials were ever exposed in plaintext, revoke/rotate them in Spotify/Discogs dashboards immediately.
- You can run `./scripts/check-doc-secrets.ps1` to scan markdown docs for secret-like values.

| Variable | Required | Description |
|----------|----------|-------------|
| `SPOTIFY_CLIENT_ID` | Yes | Spotify OAuth client ID |
| `SPOTIFY_CLIENT_SECRET` | Yes | Spotify OAuth client secret |
| `SPOTIFY_REDIRECT_URI` | No | OAuth callback URL (defaults to `http://127.0.0.1:PORT/api/auth/callback`) |
| `PUBLIC_BASE_URL` | Recommended | Public base URL (e.g. `https://vinylmatch.example.com`) |
| `DISCOGS_TOKEN` | Optional | Default Discogs token used for API matching |
| `DISCOGS_USER_AGENT` | Recommended | User-Agent for Discogs API (required by Discogs TOS) |
| `PORT` | No | Server port (default `8888`) |
| `CORS_ALLOWED_ORIGINS` | No | Comma-separated allowed origins |
| `RATE_LIMIT_PER_MINUTE` | No | Requests/minute per client+path (default `240`) |
| `RATE_LIMIT_BURST` | No | Burst capacity (default `max(30, perMinute/4)`) |
| `RATE_LIMIT_MAX_BUCKETS` | No | Maximum local client/route buckets (default `10000`) |
| `RATE_LIMIT_IDLE_SECONDS` | No | Idle bucket eviction age (default `600`) |
| `TRUSTED_PROXY_CIDRS` | Production | Comma-separated immediate proxy CIDRs allowed to set forwarded client/proto headers |
| `REQUEST_THREADS` | No | Inbound HTTP request capacity (default `32`) |
| `PROVIDER_THREADS` | No | Bounded Spotify/provider worker count (default `8`) |
| `PROVIDER_QUEUE_CAPACITY` | No | Maximum queued provider calls before `503` (default `32`) |
| `PROVIDER_TIMEOUT_SECONDS` | No | Provider-call deadline (default `20`) |
| `DISCOGS_PROVIDER_CONCURRENCY` | No | Maximum concurrent Discogs match operations (default `6`) |

API responses expose `X-RateLimit-Limit` and `X-RateLimit-Remaining`; rejected requests also include `Retry-After`. Development uses a bounded in-process token bucket. Production uses Redis-backed fixed windows so limits remain consistent across instances and fails closed with `503` if the shared limiter is unavailable.
| `PLAYLIST_CACHE_TTL_SECONDS` | No | Local playlist-cache TTL in seconds (default `300`) |
| `PLAYLIST_CACHE_MAX_MEMORY_ENTRIES` | No | Maximum in-memory playlist entries per app instance (default `100`) |
| `PLAYLIST_CACHE_MAX_DISK_ENTRIES` | No | Maximum local playlist snapshot files (default `200`) |
| `PLAYLIST_CACHE_MAX_DISK_BYTES` | No | Maximum total bytes for local playlist snapshots (default `104857600`) |
| `PLAYLIST_CACHE_CLEANUP_SECONDS` | No | Cleanup and bound-enforcement interval in seconds (default `60`) |

The playlist cache is deliberately local to one application instance. Memory entries and files under
`cache/playlists` are not shared or invalidated across replicas, and local snapshots may disappear when
an instance is replaced. Treat it only as a performance cache; a shared cache would be a separate design.

### Spotify Developer Setup

1. Create an app in the Spotify Developer Dashboard
2. Add the redirect URI:
   - Local: `http://127.0.0.1:8888/api/auth/callback`
   - Local (IPv6): `http://[::1]:8888/api/auth/callback`
   - Hosted: `https://your-domain.com/api/auth/callback`
3. Set `SPOTIFY_CLIENT_ID` and `SPOTIFY_CLIENT_SECRET`

Note: Spotify does not allow `localhost` as a redirect URI. Use `127.0.0.1` (or `[::1]`) and open the app via that same host.

## Custom Vendor Links

Create `config/vendors.json` to add/override vendor links:

```json
{
  "vendors": [
    {
      "id": "discogs-market",
      "label": "D",
      "name": "Discogs Marketplace",
      "urlTemplate": "https://www.discogs.com/sell/list?q={query}&format=Vinyl",
      "enabled": true
    }
  ]
}
```

See `config/vendors.json.example` for more examples.

## API

The server exposes REST endpoints under `/api/*`.

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/auth/status` | GET | Check Spotify login state |
| `/api/auth/login` | POST | Start Spotify OAuth flow |
| `/api/auth/callback` | GET | OAuth callback handler |
| `/api/auth/logout` | POST | End session |
| `/api/playlist?id={playlist_id}` | GET | Playlist details + Discogs matches |
| `/api/user/playlists` | GET | Current user's playlists |
| `/api/discogs/search` | POST | Search Discogs by artist/album/year/track |
| `/api/discogs/batch` | POST | Batch search for multiple tracks |
| `/api/discogs/status` | GET | Discogs session status |
| `/api/discogs/login` | POST | Discogs token login (per user) |
| `/api/discogs/logout` | POST | Discogs logout |
| `/api/discogs/wishlist` | GET | Discogs wishlist (requires Discogs login) |
| `/api/discogs/wishlist/add` | POST | Add release to wantlist |
| `/api/discogs/library-status` | POST | Wishlist/collection flags for URLs |
| `/api/discogs/curation/candidates` | POST | Candidate matches (admin/curation) |
| `/api/discogs/curation/save` | POST | Save curated match |
| `/api/config/vendors` | GET | Vendor config for frontend |

## Project Structure (high level)

- `src/main/java/Server/` — HTTP server, routes, sessions, rate limiting
- `src/main/java/com/hctamlyniv/discogs/` — Discogs client, cache, normalization, models
- `src/main/java/com/hctamlyniv/DiscogsService.java` — Discogs matching facade
- `src/main/frontend/` — static frontend (HTML/CSS/JS)

## Notes

- No Discogs token: matching falls back to Discogs web search URLs.
- Sessions require Redis in production and use in-memory storage only in development/test.

Health endpoints have separate contracts: `/api/health/simple` and `/api/health/live` are non-blocking liveness probes, `/api/health/ready` checks required Redis/system readiness, and `/api/health/dependencies` returns a cached background snapshot of optional Spotify/Discogs reachability.
- The app only uses metadata; it does not download or store any audio.

## License

See `License.txt`.
