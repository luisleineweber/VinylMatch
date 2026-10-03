# Render Free hosting

The root `render.yaml` defines one Java web service and one Redis-compatible
Key Value instance. Both use the Free plan in Frankfurt. Deploys are manual.

## Deploy

1. Commit and push the Render files to the GitHub branch that Render will use.
2. Open [New Blueprint](https://dashboard.render.com/blueprint/new?repo=https://github.com/luisleineweber/VinylMatch).
3. Connect GitHub and select the branch with the Render files.
4. Enter `SPOTIFY_CLIENT_ID` and `SPOTIFY_CLIENT_SECRET` in Render.
5. Enter `DISCOGS_TOKEN` to use Discogs API matching. The Discogs consumer key,
   consumer secret, and `ADMIN_USER_IDS` are optional.
6. Check that both services use **Free**, then apply the Blueprint.
   Set the Blueprint's **Auto Sync** to **No** for manual deployment updates.
7. Open the web service's HTTPS URL. Add `<service-url>/api/auth/callback` to
   the Spotify app's redirect URIs. Save the Spotify settings.
8. For Discogs OAuth, set the callback to
   `<service-url>/api/discogs/oauth/callback` in the Discogs app settings.

Render generates `VINYLMATCH_MASTER_KEY` and supplies the internal Redis host
and port. Redis has no public access. Do not enable internal Redis authentication
without also setting `REDIS_PASSWORD` on the web service.

The startup script sets `PUBLIC_BASE_URL` from `RENDER_EXTERNAL_URL`.
The existing app code then builds the Spotify and Discogs callback URLs.
For a custom domain, set `PUBLIC_BASE_URL` to its HTTPS URL and update both
provider settings. An explicit callback variable overrides the base URL.

## Check the service

- `/` must return the home page and load its CSS and JavaScript.
- `/api/health/simple` must return HTTP 200.
- The startup log must confirm the Redis connection.
- Spotify login must return to the service. Its session cookie must have `Secure`.

## Free plan limits

The web service has 512 MB RAM. The image limits the Java heap to 256 MB.
After 15 idle minutes, Render stops the service. The next request starts it again,
which can take about one minute. Local cache and log files are temporary.

Free Key Value has 25 MB memory and 50 connections. It has no disk persistence.
A restart loses sessions, curated links, and audit history stored in Redis.
`noeviction` prevents memory pressure from deleting keys; writes fail when full.
Use this setup only when these limits and data loss are acceptable.

Sources: [Free limits](https://render.com/docs/free),
[pricing](https://render.com/pricing),
[Blueprint fields](https://render.com/docs/blueprint-spec).
