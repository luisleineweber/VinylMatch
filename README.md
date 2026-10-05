# VinylMatch

[![CI](https://github.com/hctamlyniv/VinylMatch/actions/workflows/ci.yml/badge.svg)](https://github.com/hctamlyniv/VinylMatch/actions/workflows/ci.yml)
[![Deploy](https://github.com/hctamlyniv/VinylMatch/actions/workflows/deploy.yml/badge.svg)](https://github.com/hctamlyniv/VinylMatch/actions/workflows/deploy.yml)
[![Coverage](https://img.shields.io/badge/coverage-gated%20at%2040%25-green.svg)](./pom.xml)
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
- Quicksearch popup for artists, albums and songs, with album details and artist releases
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

### Quicksearch

Open Quicksearch from the header. Enter at least two characters and choose All, Artists, Albums or Songs.
The popup shows up to 20 results. The list shows 5½ rows on desktop and scrolls inside the popup.
Small windows show fewer rows so the search field and close button remain in view.

Select an album to see its track list, Discogs offer count and shop links. Select an artist to browse
their main releases, including albums, EPs and singles. Use **Load more releases** to browse all pages.
Song searches show releases that contain the song. All views stay in the popup, and Back restores the result list.
Albums and Songs include releases without a master. Editions with a master are grouped into one result.
Album details load while custom shop settings load. Artist profiles stay in memory for one minute when browsing release pages.

Quicksearch needs `DISCOGS_TOKEN` or a connected Discogs session. Users can connect Discogs on the Playlist page.
Spotify login is not required for Quicksearch. Discogs offer counts include all formats; marketplace links filter for vinyl.
HHV, JPC, Amazon and custom vendors provide search links. The app does not check their stock.

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

To run the Quicksearch browser tests with Node.js:

```bash
node scripts/test-frontend.mjs
```

Open `http://127.0.0.1:8782/tests/quicksearch.html`. The page runs tests with controlled API responses
and shows each result. It checks retry, Back, loading more, late responses, and slow or failed shop settings.
These tests do not need Spotify or Discogs credentials.

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
| `REDIS_HOST` | Recommended | Map from Railway Redis host |
| `REDIS_PORT` | Recommended | Map from Railway Redis port |
| `REDIS_PASSWORD` | Recommended | Map from Railway Redis password |

If Railway exposes Redis through reference variables instead of fixed names, map them into the app's expected variables `REDIS_HOST`, `REDIS_PORT`, and `REDIS_PASSWORD`.

`pom.xml` already wires `NVD_API_KEY` into `dependency-check-maven`. If the NVD feed still returns a transient error, the build now continues instead of failing the whole deploy, but Railway should still set the API key for reliable vulnerability updates.

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
3. Open `https://your-domain/api/health` to confirm the deployment is healthy.
4. Test Spotify login with the hosted callback URL.

### Release Flow

- Push to `develop` to run tests, build `VinylMatch.jar`, and deploy staging via `.github/workflows/deploy.yml`.
- Create and push a version tag like `v0.1.1` to run the production release path.
- Tagged releases publish `VinylMatch.jar` to a GitHub Release and then deploy production.
- Do not use branch pushes to `main` as the production release trigger.

### Release Checklist

1. Run `mvn test` and `mvn package` locally before cutting a release.
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
| `/api/quicksearch?q={query}&type={all,artists,albums,songs}` | GET | Up to 20 Discogs search suggestions |
| `/api/quicksearch/artist?id={artist_id}&page={page}` | GET | Artist details and paged main releases |
| `/api/quicksearch/album?id={id}&kind={master,release}` | GET | Selected album, track list and Discogs offers |
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
- Sessions use Redis when configured and fall back to in-memory storage for local development.
- The app only uses metadata; it does not download or store any audio.

## License

See `License.txt`.
