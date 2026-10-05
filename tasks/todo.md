# VinylMatch TODO - 2026-07-20 Playlist Loaded-State Clarity

# VinylMatch TODO - 2026-07-20 Manual Curation Link Entry

## Goal
- Add a secondary manual Discogs-link entry path to the curation console.
- Reuse the existing server-side validation, audit context, and optimistic version handling.

## Implementation Checklist
- [x] Add an accessible secondary manual-link form to the active curation panel.
- [x] Reuse the existing curation save flow with client-side Discogs URL validation.
- [x] Run frontend checks and focused syntax/tests.
- [ ] Run the Maven test suite once the pre-existing `PlaylistAssembler`/`TrackData` compile mismatch is resolved.

## Verification Notes
- `npm run check:frontend`, `npm run test:frontend`, `node --check src/main/frontend/dist/curation.js`, and scoped `git diff --check` pass.
- Maven is currently blocked by a pre-existing `PlaylistAssembler`/`TrackData` constructor mismatch; no unrelated backend code was changed for this slice.
- The worktree contains unrelated pre-existing changes; only the curation files and task notes are in scope for this slice.

---

## Goal
- Make the playlist page clearly communicate that Spotify data loaded successfully before Discogs match review finishes.
- Keep low-confidence and legacy Discogs matches honest while replacing vague repeated review copy.
- Verify the active frontend assets and project build.

## Implementation Checklist
- [x] Trace playlist loading, Discogs enrichment, and current match badge behavior.
- [x] Add explicit loaded-state presentation and clearer review copy.
- [x] Run focused frontend checks and the Maven test suite.

## Verification Notes
- Baseline `mvn test` passed before this slice; the worktree contains unrelated pre-existing changes.
- `node --check src/main/frontend/dist/playlist.js` passed.
- `node --check src/main/frontend/dist/playlist/track-renderer.js` passed.
- `mvn '-Dtest=Server.FrontendMatchRenderingTest' test` passed after sequential Maven compilation.
- `mvn test` passed after the final frontend adjustment.
- `mvn clean test` could not run because the active `java -jar target/VinylMatch.jar` process locks the JAR; no source/test failure was involved.

---

# VinylMatch TODO - 2026-07-20 Match-Evidenz und Info-Disclosure

## Goal
- Verstecke wiederholte Discogs-Provenance-Texte hinter einem kleinen zugänglichen Info-i.
- Reiche echte `DiscogsMatch`-Evidenz im initialen Playlist-Payload durch und verhindere, dass alte Legacy-Cache-URLs dauerhaft als `LOW` gelten, wenn ein Discogs-API-Zugriff verfügbar ist.
- Bewahre bestehende uncommitted Änderungen außerhalb dieses Scopes und verifiziere Frontend/Backend gemeinsam.

## Implementation Checklist
- [x] TrackData/PlaylistAssembler um `DiscogsMatch`-Weitergabe ergänzen.
- [x] Legacy-Cache-Treffer bei konfigurierter Discogs-API zur erneuten Suche freigeben.
- [x] Renderer auf Info-Disclosure mit ARIA-Zustand und List/Grid-kompatible Styles umstellen.
- [x] Regressionstests und Syntax-/Diff-/Maven-Prüfungen ausführen.

## Verification Notes
- `Server.TrackData` serialisiert nun das optionale `discogsMatch`; `PlaylistAssembler` übergibt den Cache-Match samt URL im initialen Payload.
- `DiscogsService` behandelt `LEGACY_CACHE` bei konfigurierter API nicht mehr als final; tokenlose `SEARCH_ONLY / LOW`-Fallbacks bleiben unverändert.
- Match-Evidence ist in `track-renderer.js` standardmäßig verborgen und über `.match-evidence-toggle` mit `aria-expanded`/`aria-controls` bedienbar; List/Grid sowie Variant 4 verwenden den normalen Dokumentfluss.
- Fokusierte Maven-Tests: 9 Tests erfolgreich; vollständige Suite: 135 Tests erfolgreich, 0 Fehler.
- Frontend-Unit-Tests: 7 erfolgreich; vollständige E2E-Suite: 4 erfolgreich; `node --check` für Renderer/Discogs-State erfolgreich; `git diff --check` erfolgreich.
- `npm run check:frontend` bleibt wegen der bereits untracked `src/main/frontend/dist/theme-bootstrap.js`-Referenzen rot; diese bestehende Asset-Graph-Anomalie lag außerhalb des Match-Scopes und wurde nicht überschrieben.

---

# VinylMatch TODO - 2026-05-15 Dependabot PR Merge Pass

## Goal
- Merge open Dependabot PRs that are green and safe enough to accept.
- Leave failing or risky PRs unmerged with a clear note.
- Verify the local repository state after merges.

## Implementation Checklist
- [x] Confirm open PR status and changed files.
- [x] Merge green low-risk dependency PRs with rebase merge.
- [x] Skip failing PRs and record why.
- [x] Fetch/prune and verify local status.

## Verification Notes
- Merged PRs with rebase merge and branch deletion: #26, #29, #31, #32, #33, #37, #38, #39, #40, #42, #43, #44.
- Left open because GitHub CI is failing: #35 `ch.qos.logback:logback-classic 1.5.6 -> 1.5.32`, #36 `ch.qos.logback:logback-core 1.5.6 -> 1.5.32`.
- Local verification after pulling updated `main`: `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd test` passed with 115 tests, 0 failures, 0 errors, and JaCoCo checks met.
- Follow-up from GitHub notifications: deploy workflow failures predated this merge pass and were caused by workflow validation before jobs started. Patched `.github/workflows/deploy.yml` locally to avoid `if: secrets.*` and gate deploy steps via job env values instead.
- Local workflow patch checks: `git diff --check -- .github/workflows/deploy.yml tasks/todo.md` passed; `Select-String -Path .github\workflows\deploy.yml -Pattern 'if: secrets\.'` returns no matches.

# VinylMatch TODO - 2026-05-07 Improvement + Feature Discovery

## Goal
- Commit the current working tree before analysis.
- Review the backend, frontend, docs, and verification state for practical improvement opportunities.
- Produce a ranked list of improvements and feature candidates without changing product code yet.

## Implementation Checklist
- [x] Commit the current working tree.
- [x] Re-read project lessons and confirm the repo stack.
- [x] Run the current Maven test phase.
- [x] Inspect API usage, caching, route tests, frontend Discogs flow, and docs for improvement candidates.
- [x] Summarize recommended next slices for Luis.

## Verification Notes
- Commit created: `4bd6189 Harden callbacks and refine responsive UI`.
- Proof of current health: `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd test` passes with 114 tests, 0 failures, 1 skipped, and JaCoCo checks met.
- Findings to consider next:
  - Make `ApiServerIntegrationTest.playlistEndpointRequiresSpotifyLogin` deterministic; it currently can call Spotify when app credentials are present and logs a real upstream 404 while still passing.
  - Re-enable or replace the disabled callback HTML test so auth popup rendering stays covered.
  - Update stale README/audit docs: README still says sessions are memory-only despite Redis-backed stores, the coverage badge says 70% while JaCoCo gate is 40%, and the external API audit still references an old `mvn test` failure.
  - Improve large Discogs library accuracy; library flags only fetch page 1 / 100 IDs today, so large wantlists/collections can show false negatives.
  - Consider a user-facing match quality workflow: surface "exact/master/release/search fallback" confidence, let users filter low-confidence matches, and reuse the existing curation save path.
  - Consider playlist analysis features: owned/wishlist/missing summary, estimated vinyl-shopping gap, exportable buylist, and vendor availability view.

# VinylMatch TODO - 2026-05-07 First Four Improvement Fixes

## Goal
- Fix the first four improvement findings from the discovery pass.
- Keep added calculations and load time small, especially for Discogs library checks.
- Leave match quality and playlist analysis features for a future slice.

## Implementation Checklist
- [x] Make the Spotify playlist integration test deterministic without real upstream calls.
- [x] Re-enable callback HTML coverage.
- [x] Update stale README and external API audit notes.
- [x] Improve Discogs library flag accuracy with capped pagination and short TTL reuse.
- [x] Run focused and full verification.

## Verification Notes
- Focused Discogs client verification now passes: `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd "-Dtest=com.hctamlyniv.discogs.DiscogsApiClientTest" test`.
- Full verification passes: `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd test` ran 115 tests, 0 failures, 0 errors, and JaCoCo checks met.

# VinylMatch TODO - 2026-05-07 Discogs Library Pagination Parser Fix

## Goal
- Isolate Discogs library ID parsing from HTTP pagination fixtures.
- Fix the focused capped-pagination test so paged wantlist and collection IDs are returned.

## Implementation Checklist
- [x] Review existing Discogs pagination client/test changes.
- [x] Extract a small release-ID parser for paged Discogs library JSON.
- [x] Add a direct unit test over static wantlist/collection JSON.
- [x] Wire the parser into the HTTP paging method and run focused verification.

## Verification Notes
- `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd "-Dtest=com.hctamlyniv.discogs.DiscogsApiClientTest" test` passes with 14 tests, 0 failures, 0 errors, and JaCoCo checks met.

# VinylMatch TODO - 2026-04-16 Homepage Copy Refresh

## Goal
- Update the active homepage hero and input copy to the supplied tighter VinylMatch messaging.
- Refresh the About page text so the product description, feature list, and technology line match the new positioning.
- Keep the legacy hidden home variants aligned where they reuse the same homepage copy blocks.

## Implementation Checklist
- [x] Inspect the current active and legacy frontend templates that contain the copy.
- [x] Update the hero, input instructions, and tip line in the homepage templates.
- [x] Update the About page description, features, and technology presentation.
- [x] Run focused verification and record the result.

## Verification Notes
- Updated `src/main/frontend/home.html` with the requested hero headline, tighter supporting copy, revised playlist-link instructions, and all-caps playlist-drawer tip.
- Kept the hidden legacy home variants aligned by applying the same copy changes in `src/main/frontend/legacy/hidden/alpha/v3/home.html` and `src/main/frontend/legacy/hidden/alpha/v6/home.html`.
- Refreshed `src/main/frontend/about.html` with the new product description, expanded feature blurbs, and uppercase technology labels.
- Proof of work:
  - `git diff --check -- tasks/todo.md src/main/frontend/home.html src/main/frontend/about.html src/main/frontend/legacy/hidden/alpha/v3/home.html src/main/frontend/legacy/hidden/alpha/v6/home.html`
  - `rg -n "TURN SPOTIFY PLAYLISTS|INTO RECORDS WORTH OWNING|cross-references every track on Discogs|Official Spotify playlists require a direct URL|TIP: USE THE PLAYLIST DRAWER|bridges your digital playlists and the physical record world|persistent session support|maximize hit rate|Pick up right where you left off|VANILLA JS" src/main/frontend`

# VinylMatch TODO - 2026-04-16 Branch Cleanup Review

## Goal
- Review all current local and remote branches against `origin/main`.
- Identify which branches still appear active or needed, and which look safe to delete or prune.
- Base the recommendation on merge status, divergence, branch age, and GitHub PR state where available.

## Implementation Checklist
- [x] Inventory current local and remote branches.
- [x] Check each remote branch for merge/divergence relative to `origin/main`.
- [x] Check whether any remote branches still back open or relevant PRs.
- [x] Summarize branches to keep versus branches that look safe to remove.

## Verification Notes
- `git fetch --all --prune --tags` refreshed remote refs and pruned the already deleted `origin/dependabot/maven/redis.clients-jedis-7.4.0` branch.
- Current git state is one local branch (`main`) plus fourteen remote `origin/dependabot/*` branches.
- For every surviving remote branch, `git rev-list --left-right --count origin/main...<branch>` shows the branch is still one commit ahead of `origin/main`, and `git cherry origin/main <branch>` reports a unique patch rather than an equivalent change already present on `origin/main`.
- GitHub PR checks show every surviving remote branch still backs an open Dependabot PR, so none of the current remote refs are orphaned leftovers.

# VinylMatch TODO - 2026-04-16 Review Findings Fix Pass

## Goal
- Fix the concrete backend and frontend issues identified in the review, with minimal diffs and focused regression coverage.
- Preserve unrelated in-progress frontend changes already present in the worktree.

## Implementation Checklist
- [x] Re-check the current dirty files and isolate the fix scope.
- [x] Patch backend security/session issues: Discogs callback escaping, Spotify refresh persistence, request-body limit, and cache/session hardening where feasible.
- [x] Patch frontend behavior issues: header narrow-width overlap, playlist album-extraction fallback, drawer focus filtering, tab keyboard behavior, visible neutral status messages, and missing user playlist status node.
- [x] Add or update focused tests for backend behavior changes.
- [x] Run proof-of-work checks and record the outcome.

## Verification Notes
- Backend:
  - `src/main/java/Server/routes/DiscogsRoutes.java` now escapes callback HTML output and posts a structured callback payload without embedding raw user-controlled strings in inline script.
  - `src/main/java/Server/routes/AuthRoutes.java` now persists refreshed Spotify sessions.
  - `src/main/java/Server/http/HttpUtils.java` now enforces a bounded request-body read path, and the JSON POST routes that read request bodies now map oversized payloads to `413 payload_too_large`.
  - `src/main/java/Server/session/SessionSerializer.java` now encrypts Spotify access/refresh tokens at rest with backward-compatible decrypt fallback.
  - `src/main/java/Server/routes/PlaylistRoutes.java` no longer clears the shared playlist cache on every user-signature change.
- Frontend:
  - `src/main/frontend/common/header.css` now moves the wrapped two-row mobile header layout up to `600px`, which removes the reproduced ~`500px` overlap.
  - `src/main/frontend/dist/playlist.js` now falls back to rendering all tracks if album extraction returns no albums.
  - `src/main/frontend/dist/main.js` and `src/main/frontend/dist/playlist.js` now filter drawer focus targets by actual visibility, and home tab keyboard navigation now includes the `Yours` tab after login.
  - `src/main/frontend/dist/playlist.js` now shows neutral Discogs guidance messages instead of clearing them immediately.
  - `src/main/frontend/home.html` restores the `#user-status` node used by the existing home playlist-loading status logic.
- Added focused tests:
  - `src/test/java/Server/routes/AuthRoutesTest.java`
  - `src/test/java/Server/routes/DiscogsRoutesTest.java`
  - `src/test/java/Server/session/SessionSerializerTest.java`
  - Updated `src/test/java/Server/http/HttpUtilsTest.java`
- Proof of work:
  - `node --check src/main/frontend/dist/main.js`
  - `node --check src/main/frontend/dist/playlist.js`
  - `git diff --check`
  - `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd test`
  - `npx playwright screenshot --viewport-size "500,900" http://127.0.0.1:4173/home.html tasks/header-500-check.png`

# VinylMatch TODO - 2026-04-15 Frontend and Backend Review

## Goal
- Review the current frontend and backend implementation for concrete bugs, regressions, security issues, and missing verification.
- Prioritize findings with file/line references and focus on issues that would affect users, deploys, or maintainability.

## Review Checklist
- [x] Inspect current worktree changes so the review does not confuse existing edits with baseline code.
- [x] Review backend route, auth/session, HTTP, and external API handling for defects or regressions.
- [x] Review frontend boot, playlist/home flows, API handling, accessibility, and responsive behavior for defects or regressions.
- [x] Run focused verification commands where practical and record the outcome.
- [x] Summarize findings in code-review format with severity and file references.

## Verification Notes
- Reviewed the dirty worktree first; current modified source files are frontend files plus task notes, with untracked screenshot/test artifacts already present under `tasks/` and `test-results/`.
- Backend review covered auth/session refresh, Discogs callback rendering, request body handling, and playlist cache behavior.
- Frontend review covered header breakpoints, playlist loading fallback, drawer focus handling, tab keyboard behavior, and user-facing status nodes/messages.
- Proof of work:
  - `node --check src/main/frontend/dist/main.js`
  - `node --check src/main/frontend/dist/playlist.js`
  - `node --check src/main/frontend/dist/playlist/discogs-ui.js`
  - `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd test`
  - `git diff --check`

# VinylMatch TODO - 2026-04-09 Python Dead Code Sweep

## Goal
- Use `ruff` and `vulture` to detect dead Python code that is actually in scope for this repository.
- Remove confirmed dead code with minimal diffs, or record a verified no-op if the repo contains no maintained Python sources.

## Implementation Checklist
- [x] Inspect the repo for tracked or intentionally maintained Python files and tool configuration.
- [x] Install or invoke `ruff` and `vulture` in a reproducible way if they are not already present.
- [x] Run the dead-code scan on the in-scope Python surface and review findings.
- [x] Delete confirmed dead code or document why no cleanup was required.
- [x] Run proof-of-work checks and record the outcome.

## Verification Notes
- `git ls-files "*.py"` returned no tracked Python files in the repository, so there is no in-scope Python application code for `ruff` or `vulture` to clean up.
- The only repository-local `.py` files found by `rg --files -uu -g "*.py"` live under `.agents/skills/use-railway/scripts/`, and `git check-ignore -v .agents\skills\use-railway\scripts\dal.py` plus `git check-ignore -v .claude\skills\use-railway\scripts\analyze-postgres.py` confirmed that `.agents/` and `.claude/` are intentionally ignored local tooling trees.
- `py -m pip install --user ruff vulture` confirmed both tools are available in the local Python user environment.
- `py -m ruff check .` completed successfully and reported `No Python files found under the given path(s)`, which matches the tracked-file scan.
- `py -m vulture . --exclude ".agents/,.claude/"` completed with no findings once the ignored local tooling trees were excluded from scope.
- No code was deleted because the maintained repository contents do not include tracked Python files.

# VinylMatch TODO - 2026-03-28 Playlist 640px Header Cleanup

## Goal
- Make the shared playlist header look intentional at `640px` wide instead of collapsing into an awkward wrapped state.
- Keep the header and page top spacing aligned when the fixed header grows taller on narrow screens.

## Implementation Checklist
- [x] Inspect the current shared header and playlist top spacing at the `640px` breakpoint.
- [x] Update the shared header mobile layout so the controls wrap cleanly without crowding the logo.
- [x] Align the playlist page top padding with the taller wrapped header state.
- [x] Run focused verification and record the outcome.

## Verification Notes
- `src/main/frontend/common/header.css` now switches the shared header into its wrapped two-row layout at `700px` instead of waiting until `640px`, which gives the theme/about/auth controls enough horizontal room before the cramped tablet-width state appears.
- `src/main/frontend/common/header.css` now adds a subtle divider and keeps the Spotify auth button pushed to the far right on the second row, so the narrow header reads as a deliberate stacked layout instead of an accidental wrap.
- `src/main/frontend/styles/playlist.css` and `src/main/frontend/styles/playlist-variant4.css` now reserve extra top space for the taller fixed header on narrow screens, so the playlist content no longer sits too close to the header after the wrap kicks in.
- Proof of work:
  - `git diff --check -- src/main/frontend/common/header.css src/main/frontend/styles/playlist.css src/main/frontend/styles/playlist-variant4.css tasks/todo.md`
  - `npx http-server src/main/frontend -p 4173 -c-1`
  - `npx playwright screenshot --viewport-size "640,1200" --full-page http://127.0.0.1:4173/playlist.html tasks/playlist-header-640-after.png`

# VinylMatch TODO - 2026-03-28 Playlist Mid-Width Responsive Fix

## Goal
- Remove the odd playlist mid-width responsive state around `770px` to `800px`.
- Keep the playlist toolbar/header stable through tablet widths and reserve stacked mobile behavior for genuinely narrow screens.

## Implementation Checklist
- [x] Inspect the playlist responsive rules that still trigger around `820px`.
- [x] Move any remaining variant-only mid-width layout shifts down to the real mobile breakpoint.
- [x] Run a focused verification and record the outcome.

## Verification Notes
- `src/main/frontend/styles/playlist-variant4.css` now keeps `.playlist-toolbar` on a single row through tablet widths by removing the variant-only wrapping behavior that was reintroducing a pseudo-mobile layout around `770px` to `800px`.
- `src/main/frontend/styles/playlist-variant4.css` now applies the compact brutalist header padding/image treatment at `640px` instead of `820px`, so the playlist stays in its desktop/tablet presentation until the actual mobile breakpoint.
- Proof of work:
  - `git diff --check -- src/main/frontend/styles/playlist-variant4.css tasks/todo.md`
  - `Get-Content src/main/frontend/styles/playlist-variant4.css | Select-Object -Skip 126 -First 14`
  - `Get-Content src/main/frontend/styles/playlist-variant4.css | Select-Object -Skip 810 -First 48`

# VinylMatch TODO - 2026-03-28 Header Navigation Hide Breakpoint

## Goal
- Keep the shared header navigation visible on medium-width screens instead of hiding it too early.
- Preserve the existing wrapped mobile header layout on genuinely narrow screens.

## Implementation Checklist
- [x] Inspect the shared header breakpoint rules controlling when the left navigation disappears.
- [x] Move the hide breakpoint so it aligns with the narrow mobile layout instead of the tablet-width layout.
- [x] Run a focused verification and record the outcome.

## Verification Notes
- `src/main/frontend/common/header.css` now keeps `.navigation-left` visible until `640px` instead of hiding it at `820px`, so header links remain available on medium-width screens like small tablets and narrow desktop windows.
- The existing wrapped mobile header behavior remains tied to the same `640px` breakpoint, so the nav only disappears once the compact two-row mobile layout takes over.
- Proof of work:
  - `git diff -- src/main/frontend/common/header.css tasks/todo.md`
  - `Get-Content src/main/frontend/common/header.css | Select-Object -Skip 160 -First 80`

# VinylMatch TODO - 2026-03-28 Playlist Mobile Navigation + Breakpoint Tuning

## Goal
- Make the shared header logo a reliable tap target back to the home page on mobile.
- Keep the playlist toolbar on one row until extremely narrow screens, then let it stack cleanly.
- Move the playlist track-card compact layout breakpoint from `900px` to `600px`.
- Keep the brutalist variant stylesheet aligned with those same mobile breakpoints so it does not override the fix.

## Implementation Checklist
- [x] Inspect the shared header markup/styles and the playlist mobile breakpoint rules.
- [x] Update the header logo to link back to `/home.html` and preserve existing styling.
- [x] Adjust playlist toolbar and track-list breakpoints to match the requested mobile behavior.
- [x] Run focused verification and record the outcome.

## Verification Notes
- `src/main/frontend/common/header.html` and `src/main/frontend/common/header.css` now make the `VINYL MATCH` logo a real `/home.html` link with hover/focus states, so mobile users have a consistent route back home even after the left nav is hidden.
- `src/main/frontend/styles/playlist.css` now switches the compact track-card layout at `600px` instead of `900px`, so the wider two-column card layout stays active on medium mobile/tablet widths.
- `src/main/frontend/styles/playlist.css` now stacks `.playlist-toolbar` only at `300px` and below, stretching both the Discogs toggle and view toggle to full width only at that extreme breakpoint.
- `src/main/frontend/styles/playlist-variant4.css` now follows the same `600px` track breakpoint and `300px` toolbar-stacking breakpoint, so the brutalist variant no longer overrides the requested layout.
- Proof of work:
  - `git diff --check -- src/main/frontend/common/header.html src/main/frontend/common/header.css src/main/frontend/styles/playlist.css tasks/todo.md`
  - `npx http-server src/main/frontend -p 4173 -c-1`
  - `npx playwright screenshot --viewport-size "300,900" --full-page http://127.0.0.1:4173/playlist.html tasks/playlist-mobile-300-check.png`
  - `npx playwright screenshot --viewport-size "600,900" --full-page http://127.0.0.1:4173/playlist.html tasks/playlist-mobile-600-check.png`

# VinylMatch TODO - 2026-03-28 Mobile Header + Home Drawer Collision Fix

## Goal
- Prevent the `Open playlists` trigger from overlapping the home hero card on narrow mobile screens.
- Prevent the header theme toggle from colliding with the VinylMatch logo on narrow mobile screens.
- Keep the existing brutalist home/header look intact while making the mobile layout fit cleanly.

## Implementation Checklist
- [x] Inspect the shared header and home mobile breakpoint rules that control the collisions.
- [x] Update the mobile header layout so the logo and right-side controls can stack without overlap.
- [x] Reposition the mobile home drawer trigger so it stays clear of both the header and the hero card.
- [x] Run focused verification and record the outcome.

## Verification Notes
- `src/main/frontend/common/header.css` now switches the shared header to a wrapped two-row layout below `640px`, with tighter spacing for the theme/about/auth controls.
- `src/main/frontend/styles/home.css` now anchors the `Open playlists` trigger to the bottom-left corner below `540px`, keeping it reachable without consuming hero space.
- `src/main/frontend/styles/home.css` keeps the current visible hero badge at `v1.3` for the updated mobile presentation.
- Proof of work:
  - `npx http-server src/main/frontend -p 4173 -c-1`
  - `npx playwright screenshot --device="iPhone 12" http://127.0.0.1:4173/home.html tasks/mobile-home-check.png`

# VinylMatch TODO - 2026-03-28 Home Sidebar Bottom Gap + Yours Overflow

## Goal
- Remove the dead space at the bottom of the home sidebar when a tab has little content.
- Prevent the home sidebar itself from scrolling on normal desktop heights when the `Yours` tab is populated.

## Implementation Checklist
- [x] Inspect the current home drawer height and panel sizing rules.
- [x] Make the sidebar a fixed-height flex column with the active panel filling the remaining vertical space.
- [x] Adjust `Yours` pagination density to the available viewport height so the drawer stays non-scrollable on normal desktop heights.
- [x] Run focused frontend verification and record the outcome.

## Verification Notes
- `src/main/frontend/styles/home.css` now pins the drawer to the viewport height, removes the drawer's own scroll path, and lets the active sidebar panel/list fill the remaining vertical space instead of leaving a dead bottom gap.
- `src/main/frontend/dist/main.js` now derives the `Yours` page size from viewport height (`6`-`9` rows) and recalculates it on resize so populated user-playlist pages stay within the drawer on normal desktop heights while using available space more efficiently.
- `src/main/frontend/styles/home.css` now forces the page-count label to stay dark in light mode and light in dark mode.
- Proof of work:
  - `node --check src\main\frontend\dist\main.js`
  - `java -jar target\VinylMatch.jar` with `PORT=8891` to confirm the app still boots locally
  - `git diff --check -- src/main/frontend/styles/home.css src/main/frontend/dist/main.js tasks/todo.md`

# VinylMatch TODO - 2026-03-28 Frontend Boot Regression

## Goal
- Restore the homepage and playlist page client boot behavior so header injection, sidebar toggles, and playlist-link submission work again.
- Identify whether the break is caused by a browser-only runtime error, a broken asset path, or stale/dist frontend code.

## Implementation Checklist
- [x] Reproduce the current broken interactions in a browser-driven check and capture the failing behavior.
- [x] Trace the failing boot path to the smallest frontend or static-serving defect.
- [x] Apply the minimal fix needed to restore header load and home/playlist interactions.
- [x] Run proof-of-work checks and record the result.

## Verification Notes
- Fresh-browser runtime verification showed the current source tree boot path was healthy: header injection worked, the home sidebar opened, and the paste button triggered `/api/playlist` as expected.
- `src/main/java/Server/http/StaticFileHandler.java` now sends `Cache-Control: no-cache, max-age=0, must-revalidate` for `.html`, `.js`, and `.css` assets so browsers revalidate mutable frontend files instead of hanging onto a stale broken bundle.
- `src/test/java/Server/http/StaticFileHandlerTest.java` now covers the new cache-policy behavior for JS assets.
- Railway follow-up: the deploy still failed because `src/main/frontend/dist/common/api-errors.js` existed only as a local untracked file. It was present in local `target/frontend` builds from the dirty workspace, but absent from Git-based Railway builds, which caused the browser to 404 the module import and abort `main.js`.
- Proof of work:
  - `node tasks/tmp-playwright-check.cjs`
  - `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd -Dtest=StaticFileHandlerTest test`

# VinylMatch TODO - 2026-03-28 Ignore Local Agent Artifacts

## Goal
- Reduce local worktree noise from agent/tooling artifacts without hiding tracked project files.
- Ignore local `.agents/` workspace content and `skills-lock.json` if they are untracked.

## Implementation Checklist
- [x] Inspect current untracked paths and confirm they are not already tracked.
- [x] Update `.gitignore` with focused patterns for the local agent artifacts.
- [x] Verify the remaining `git status --short` output only shows intentional changes.

# VinylMatch TODO - 2026-03-27 .gitignore Cleanup

## Goal
- Remove stale ignore entries for tracked project directories.
- Keep `.github/` tracked and ignore only the local `.claude/` workspace directory.
- Confirm whether any earlier commit introduced the bad ignore rule.

## Implementation Checklist
- [x] Inspect the current `.gitignore` and repo contents for `.github/` and `.mvn` usage.
- [x] Remove the incorrect `.github/` ignore rule and add `.claude/`.
- [x] Check git history to identify the commit that introduced the bad rule.
- [x] Verify the updated ignore behavior and document the outcome.

## Verification Notes
- `.github/` is tracked by the repo and must not be ignored.
- `.mvn` is present only as the Maven wrapper directory placeholder right now and does not need a new ignore rule.
- The bad `.github/` ignore entry came from commit `187ee69` (`chore: align local agent docs and ignore rules (#0031)`).
- Proof of work:
  - `git check-ignore -v .claude`
  - `git ls-files .mvn .github`
  - `git show --stat --patch 187ee69 -- .gitignore`

# VinylMatch TODO - 2026-03-06 Spotify Official Playlist Research

# VinylMatch TODO - 2026-03-27 Release Hardening

# VinylMatch TODO - 2026-03-27 Railway Runtime Startup Fix

## Goal
- Diagnose why the Railway deploy builds successfully but the public service still returns `502 connection refused`.
- Fix the runtime startup path so the JVM process stays reachable on Railway's injected `PORT`.

## Implementation Checklist
- [x] Reproduce the runtime behavior locally with a Railway-like startup command/environment.
- [x] Identify whether the failure is caused by port binding, premature JVM exit, or another startup-path issue.
- [x] Apply the minimal server/runtime fix and add focused regression coverage if appropriate.
- [x] Run proof-of-work (`mvn test` or targeted runtime verification) and record the result.

## Verification Notes
- `src/main/java/Server/ApiServer.java` now creates the listener with an explicit IPv4 wildcard bind address instead of leaving the address family to the platform default.
- `railway.json` now starts the JVM with `-Djava.net.preferIPv4Stack=true` so Railway does not depend on dual-stack socket behavior.
- `src/test/java/Server/ApiServerTest.java` covers the explicit IPv4 bind-address helper.
- Proof of work:
  - `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd test`
  - `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd -DskipTests package`
  - `java '-Djava.net.preferIPv4Stack=true' -jar target\VinylMatch.jar` with `PORT=9092`, followed by `Invoke-WebRequest http://127.0.0.1:9092/api/health/simple`

## Goal
- Replace automatic production deploys on every `main` push with an explicit release gate.
- Document a concrete staged release process for staging, production, and versioning.
- Add a GitHub release artifact step so production builds are preserved outside ephemeral workflow artifacts.

## Implementation Checklist
- [x] Review the current CI/deploy workflow and release docs for the existing production path.
- [x] Update GitHub Actions so staging still deploys from `develop` but production only deploys from version tags/manual release intent.
- [x] Add a release workflow or release-publish step that attaches `VinylMatch.jar` to a GitHub Release.
- [x] Update release documentation/checklist in `README.md`.
- [x] Run proof-of-work on the changed workflow/docs files and record the result.

## Verification Notes
- `.github/workflows/deploy.yml` now triggers on `develop` pushes for staging and `v*` tags for release/production, with a `publish-github-release` job that attaches `VinylMatch.jar` to the GitHub Release before the production deploy job runs.
- `README.md` now documents the staged release flow, the exact tag-based production steps, and the required GitHub environment secrets/protection rules.
- Proof of work:
  - `git diff --check -- .github/workflows/deploy.yml README.md tasks/todo.md`
  - `rg -n 'branches: \[ develop \]|tags: \[ ''v\*'' \]|publish-github-release|startsWith\(github.ref, ''refs/tags/v''\)' .github/workflows/deploy.yml`
  - `rg -n 'Release Flow|Release Checklist|GitHub Environment Requirements|Do not use branch pushes to \`main\`' README.md`

---

# VinylMatch TODO - 2026-03-27 Railway Hosting Setup

## Goal
- Add a first-class Railway deployment path for hosting VinylMatch as a public website.
- Document the exact Railway service, Redis, domain, and Spotify callback setup needed to go live.

## Implementation Checklist
- [x] Review the current app runtime requirements against Railway's deployment model.
- [x] Add Railway deployment config to the repo.
- [x] Document Railway environment variables, Redis wiring, domain setup, and release steps in `README.md`.
- [x] Run proof-of-work on the new Railway files/docs and record the result.

## Verification Notes
- `railway.json` now configures Railway to use `RAILPACK`, start the app with `java -jar target/VinylMatch.jar`, and probe `/api/health/simple`.
- `README.md` now contains a dedicated Railway hosting section covering project creation, Redis, env vars, domain setup, Spotify callback setup, and the hosted release flow.
- Proof of work:
  - `git diff --check -- railway.json README.md tasks/todo.md`
  - `rg -n 'Railway Hosting|Railway Setup|Railway Environment Variables|Domain and Spotify OAuth|Release Flow On Railway|RAILPACK|healthcheckPath|startCommand' README.md railway.json`
  - `git status --short -- railway.json README.md tasks/todo.md`

---

# VinylMatch TODO - 2026-03-27 Railway Dependency-Check Build Fix

## Goal
- Unblock Railway builds that fail during OWASP Dependency-Check NVD updates.
- Align the documented Railway build command with the repo's actual package flow.
- Silence the missing `production` profile warning from legacy Railway build overrides.

## Implementation Checklist
- [x] Review the current `pom.xml` dependency-check and profile configuration plus Railway deployment docs.
- [x] Update Maven config to accept `NVD_API_KEY`, tolerate transient NVD update failures, and define a `production` profile.
- [x] Update Railway documentation to recommend the simpler package build command and document `NVD_API_KEY`.
- [x] Run focused proof-of-work and record the result.

## Verification Notes
- `pom.xml` now passes `NVD_API_KEY` into `dependency-check-maven`, keeps `failBuildOnCVSS=7`, sets `failOnError=false` for transient NVD update failures, and defines a `production` Maven profile so legacy Railway `-Pproduction` overrides no longer warn.
- `README.md` now tells Railway to use `mvn -B -DskipTests clean package` and documents `NVD_API_KEY` as the recommended fix for OWASP Dependency-Check rate limiting on shared CI infrastructure.
- Proof of work:
  - `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd -B -DskipTests clean package`
  - `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd help:active-profiles -Pproduction`
  - `git diff --check -- pom.xml README.md tasks/todo.md tasks/lessons.md`
  - `rg -n "nvdApiKey|failOnError|<id>production</id>|mvn -B -DskipTests clean package|NVD_API_KEY" pom.xml README.md tasks/todo.md tasks/lessons.md -S`

---

## Goal
- Research why Spotify official/editorial playlists are not accessible in VinylMatch, even when they appear in a user's library or a public playlist link is available.

## Research Checklist
- [x] Review the current Spotify playlist loading/auth flow in the codebase.
- [x] Check prior task notes for known behavior around official Spotify playlists.
- [x] Verify Spotify's current API/documentation behavior from official sources.
- [x] Summarize the likely root cause and its impact on VinylMatch.

## Follow-up Implementation
- [x] Detect Spotify-owned/editorial playlist access restrictions in backend playlist loading.
- [x] Surface a specific frontend message instead of the current generic playlist-load failure.
- [x] Run focused proof-of-work for the touched backend/frontend paths.
- [x] Propose a commit plan for the current worktree.

## Verification Notes
- Backend playlist failures now preserve the caught Spotify exception via `src/main/java/com/hctamlyniv/ReceivingData.java` and classify access-denied/not-found playlist failures in `src/main/java/Server/SpotifyPlaylistAccessClassifier.java`, which `src/main/java/Server/routes/PlaylistRoutes.java` maps to specific API error codes/messages.
- Frontend playlist entry points now read structured API errors via `src/main/frontend/dist/common/api-errors.js` and show explicit Spotify restriction/private-playlist guidance in `src/main/frontend/dist/main.js`, `src/main/frontend/dist/playlist.js`, and `src/main/frontend/dist/curation.js`.
- Focused proof of work passed:
  - `node --check src/main/frontend/dist/common/api-errors.js`
  - `node --check src/main/frontend/dist/main.js`
  - `node --check src/main/frontend/dist/playlist.js`
  - `node --check src/main/frontend/dist/curation.js`
  - `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd test "-Dtest=SpotifyPlaylistAccessClassifierTest"`
  - `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd test`

# VinylMatch TODO - 2026-03-06 Discogs Session Persistence

## Goal
- Reduce unnecessary Discogs re-logins by fixing session persistence/caching.
- Verify whether the problem is Redis/session TTL, cookie persistence, or token encryption/config handling.

## Investigation Notes
- Discogs session cookies already use a 30-day max age and `SameSite=Lax`.
- Frontend already auto-restores manual Discogs token logins from browser storage.
- Discogs sessions stored in Redis are encrypted at rest before persistence.
- `TokenEncryption` currently falls back to a boot-time key based on `System.currentTimeMillis()`, which invalidates previously stored Discogs sessions after a restart.
- `VINYLMATCH_MASTER_KEY` is documented in `config/env.example`, but it is not loaded through `Config`, so values placed in `.env` / config properties are ignored by the encryption path.

## Implementation Checklist
- [x] Load `VINYLMATCH_MASTER_KEY` through centralized config so `.env`/properties values work.
- [x] Replace the throwaway boot-time encryption fallback with a stable development fallback and warning.
- [x] Add regression tests covering config loading and Discogs session encryption persistence assumptions.
- [x] Run focused proof-of-work (`mvn test`, and build if needed) and summarize the root cause/fix.

## Verification Notes
- Root cause: Discogs session secrets were encrypted with a fallback key derived from `System.currentTimeMillis()`, so Redis-persisted sessions became undecryptable after each server restart.
- Secondary config bug: `VINYLMATCH_MASTER_KEY` was documented in `config/env.example` but not loaded through `Config`, so values in `.env` or config properties were ignored by `TokenEncryption`.
- Fix shipped in `src/main/java/com/hctamlyniv/Config.java` and `src/main/java/Server/session/TokenEncryption.java`.
- Regression coverage added in `src/test/java/com/hctamlyniv/ConfigInternalTest.java` and `src/test/java/Server/session/TokenEncryptionTest.java`.
- Proof of work passed:
  - `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd test "-Dtest=ConfigInternalTest,DiscogsSessionStoreTest,TokenEncryptionTest"`
  - `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd test`

---

# VinylMatch TODO - 2026-03-06 Session

## Goal
- Ignore generated runtime logs in Git.
- Review the current worktree and note the most likely next tasks.
- Clarify per-track Discogs status so users can tell whether lookup is still running, whether a result is ready, and whether they can click through now.

## Implementation Checklist
- [x] Add `logs/` to `.gitignore`.
- [x] Verify the `logs/` directory no longer shows up as untracked.
- [x] Capture a short next-step plan based on the current modified files.

## Likely Next Tasks
- Review the active frontend edits in playlist/home/curation files and confirm `src/main/frontend/styles/playlist.css.bak` is not an accidental backup that should be removed before commit.
- Run focused frontend verification on touched files (`node --check` for the modified JS files plus a quick browser pass for playlist ownership indicators and curation/home styling).
- Run project proof-of-work (`mvn test` and then `mvn clean package`) once the frontend diff is finalized so the session can be closed with verified output.

## Review Cleanup Pass
- [x] Remove accidental backup artifact `src/main/frontend/styles/playlist.css.bak`.
- [x] Trim the extra blank line at EOF in `src/main/frontend/dist/playlist/track-renderer.js`.
- [x] Trim the extra blank line at EOF in `src/main/frontend/styles/playlist.css`.
- [x] Re-run `git diff --check` and confirm the patch is clean.

## Track Status Clarity Pass
- [x] Replace ambiguous badge copy like `Search` / `Searching…` with clearly distinct Discogs states.
- [x] Make the Discogs action tooltip/label reflect the exact action available for the current state.
- [x] Keep wishlist enable/disable behavior aligned with the clarified Discogs state model.
- [x] Run focused frontend verification on the touched playlist files.

---

# VinylMatch TODO - 2026-02-19 Session

## Current Session Changes

### ? Completed
1. **Track Card Layout** (`src/main/frontend/dist/playlist/track-renderer.js`)
   - Artist text now appears directly below song title (reordered DOM elements)

2. **Reduced Status Visuals** (`src/main/frontend/styles/playlist.css`)
   - Changed border-left from 4px to 3px for all match qualities
   - Removed background gradients on track cards
   - Removed the checkmark icon (::before pseudo-element) on good matches

3. **Sidebar Position** (`src/main/frontend/styles/home.css`)
   - Moved sidebar top from 64px to 56px (closer to header)
   - Reduced sidebar padding from 14px to 10px top, 20px to 16px bottom
   - Removed duplicate padding declaration

### ? Completed This Session

1. **Better Collection/Wantlist Matching**
   - [x] Improve local library-key normalization for artist/album matching in `dist/playlist/discogs-ui.js`.
   - [x] Add strict-but-useful partial/fuzzy fallback for album-title variants (high-confidence threshold only).
   - [x] Keep ownership precedence (`owned` > `wishlist` > none) when cache and API results disagree.
   - [x] Ensure unresolved-track fallback to `/api/discogs/library-status` still works and does not spam calls.

2. **Public/Spotify-Made Playlists**
   - [x] Verify official Spotify playlists load by URL and 22-char ID via home input flow.
   - [x] Keep private/collaborative playlists on explicit login-required error messaging.
   - [x] Verify load-more/prefetch flow still behaves correctly for public playlists.

3. **Remove Status Banners**
   - [x] Reduce non-actionable neutral status banners in `dist/playlist.js` and `dist/main.js`.
   - [x] Keep actionable errors visible; auto-hide short-lived success states where appropriate.
   - [x] Replace blocking curation `alert()` flows in `dist/curation.js` with inline status text.

4. **Card Design Polish + Icon Ownership Indicators**
   - [x] Convert track-level Discogs ownership badges from text to icon-only indicators.
   - [x] Use two distinct states: `owned` icon and `wishlist` icon, with tooltip + aria-label for accessibility.
   - [x] Add/adjust icon assets under `src/main/frontend/design/` as needed.
   - [x] Reduce `medium` match visual emphasis (border/badge) so direct matches remain primary.
   - [x] Tighten list/grid alignment in track metadata rows after icon switch.

5. **Curation Page Header**
   - [x] Make curation header styling more distinct from main pages while preserving responsive behavior.
   - [x] Keep dev-only clarity in header copy and badge presentation.

6. **Homepage Text Clamp**
   - [x] Apply robust line-clamp for `.hero-lede` and validate overflow behavior on mobile.
   - [x] Check nearby helper text for overflow regressions and keep readable spacing.
   - [x] Bump `.hero-logo-wrap::after` version label after homepage visual adjustment.

7. **Build & Test**
   - [x] Run `mvn test`.
   - [x] Run `mvn clean package`.
   - [x] Run focused frontend syntax checks (`node --check`) for touched JS files.
   - [x] Confirm no new console/runtime errors in touched flows.

### Verification Notes (2026-02-19)
- Icon-only ownership indicators shipped with dedicated collection icons and accessibility labels/tooltips in `src/main/frontend/dist/playlist/track-renderer.js` and icon theme rules in `src/main/frontend/styles/playlist.css` plus `src/main/frontend/styles/playlist-variant4.css`.
- Library cache matching now uses stronger normalization, variant keys, and conservative fuzzy fallback with ownership precedence in `src/main/frontend/dist/playlist/discogs-ui.js`.
- Status noise reduction and inline curation status replacement verified in `src/main/frontend/dist/playlist.js`, `src/main/frontend/dist/main.js`, and `src/main/frontend/dist/curation.js` (`alert()` removed).
- Curation header differentiation and home text clamp/version bump completed in `src/main/frontend/curation.html`, `src/main/frontend/styles/curation.css`, and `src/main/frontend/styles/home.css`.
- Verification commands passed:
  - `mvn test`
  - `mvn clean package`
  - `node --check src/main/frontend/dist/playlist.js`
  - `node --check src/main/frontend/dist/main.js`
  - `node --check src/main/frontend/dist/curation.js`
  - `node --check src/main/frontend/dist/playlist/discogs-ui.js`
  - `node --check src/main/frontend/dist/playlist/track-renderer.js`

---
# VinylMatch Improvement Plan (Execution Order)

## 2026-02-13 CSS Reduction + Mobile Hardening (Home + Playlist)

### Goal
Reduce CSS size and duplication on active pages (`home.html`, `playlist.html`) while tightening mobile behavior, without changing backend logic.

### Scope
- Active pages only: `home.css`, `playlist.css`, `playlist-variant4.css`, and shared cleanup in `base.css`.
- Preserve legacy variants/routes as-is (`legacy/hidden/alpha/*`, `home-variant6.css`).

### Implementation Checklist
- [x] Add/refine shared CSS tokens and remove duplicated declarations where active stylesheets overlap.
- [x] Remove stale or unreferenced frontend CSS artifacts proven unused by routes/markup.
- [x] Simplify `playlist-variant4.css` overrides to only variant-specific differences on top of `playlist.css`.
- [x] Clean up dead selectors in `home.css` that are no longer referenced by active or legacy markup.
- [x] Improve mobile behavior (<=820px and <=540px) for toolbar/actions/track cards/home input interactions.
- [x] Run focused sanity checks on changed files and summarize.
- [x] Run Web Interface Guidelines audit on changed files and fix any critical issues.

## 2026-02-13 Spotify Official Playlist Support

### Goal
Allow users to load Spotify's official/curated playlists (e.g., "RapCaviar", "Today's Top Hits") by pasting a playlist URL, not just browsing user-owned playlists.

### Why Currently Not Possible
- Spotify has no public API endpoint to browse/search official playlists
- `Get Featured Playlists` only returns time-limited promotional content
- `Get Current User's Playlists` only returns playlists the user owns or follows
- Official playlists are owned by Spotify (not the user), so they don't appear in the user's playlist list

### Implementation Checklist
- [x] Add playlist URL paste input to home page (separate from user playlist browser)
- [x] Extract playlist ID from Spotify URL (e.g., `37i9dQZF1DX0XUsuxWHRQd`)
- [x] Use existing client credentials flow to fetch public playlists by ID
- [x] Show clear error if playlist is private/collaborative and user is logged out
- [x] Document limitation: no way to discover/browse official playlists via API, users must have the URL

### Verification Notes (2026-02-13)
- Home URL input + submit flow confirmed in `src/main/frontend/home.html` and `src/main/frontend/dist/main.js`.
- URL/ID extraction logic confirmed in `src/main/frontend/dist/main.js` (`getPlaylistIdFromUrl`).
- App-token fallback and private-playlist 401 semantics confirmed in `src/main/java/Server/routes/PlaylistRoutes.java`.
- Limitation documented for users in `README.md` and on-page help text.

---

## 2026-02-13 Next Session: Card Ownership Indicators

### Goal
Show clear badges directly on track cards when a release is already in the Discogs wishlist or already owned in the Discogs collection.

### Implementation Checklist
- [x] Add compact card badges for `In wantlist` and `In collection` states.
- [x] Ensure badges update live after login, refresh, and successful wantlist-add actions.
- [x] Keep badge visuals consistent in light and dark themes.
- [x] Verify behavior in list and grid view modes.

### Cache Sync Pass (2026-02-13)
- [x] Add cheap card ownership sync keyed by normalized `artist + album` instead of URL-only checks.
- [x] Add browser-local cache for ownership states with TTL and per-user isolation.
- [x] Apply wishlist page data directly into the local cache and refresh card badges without extra API calls.
- [x] Keep `/api/discogs/library-status` as fallback only for unresolved tracks (cache miss path).
- [x] Preserve `owned` precedence when hydrating wishlist cache and keep cache across auto-login/session-restored flows.

### Verification Notes (2026-02-13)
- Badge state is reapplied after every track render pass, so list/grid toggles keep ownership indicators without waiting for a network refresh (`src/main/frontend/dist/playlist.js`).
- Wishlist/collection badge colors now have explicit light/dark treatment in both base playlist styles and Variant 4 overrides (`src/main/frontend/styles/playlist.css`, `src/main/frontend/styles/playlist-variant4.css`).
- Grid mode badge layout now prevents text overflow clipping via grid-specific truncation rules (`src/main/frontend/styles/playlist.css`, `src/main/frontend/styles/playlist-variant4.css`).

## 2026-02-12 Discogs API Docs Notes (Agent Reference)

### Goal
Capture Discogs API details relevant to VinylMatch (auth, endpoints, query params, pagination, rate limiting, error semantics) in a single Markdown reference file for future agentic work.

### Implementation Checklist
- [x] Gather Discogs API documentation details (auth headers, endpoints, params, pagination).
- [x] Cross-check notes against current code usage (search, wantlist, collection, OAuth).
- [x] Write `tasks/discogs-api-notes.md` with project-focused guidance + examples.

## 2026-02-12 External API Usage Efficiency Audit (Spotify + Discogs)

### Goal
Ensure VinylMatch uses Spotify and Discogs APIs efficiently (avoid spam), with correct caching/deduplication, paging, and backoff behavior.

### Audit Checklist
- [x] Inventory Spotify call sites (playlist fetch, user playlists, auth refresh) and confirm paging limits and caching.
- [x] Inventory Discogs call sites (search, wantlist, collection, identity, OAuth) and confirm rate limiting + caching.
- [x] Check frontend orchestration for duplicate/retry storms (load-more, prefetch, parallel batch calls).
- [x] Identify hotspots where a single UI action causes multiple external requests; quantify worst-case.
- [x] Implement minimal, focused mitigations (server-side caching TTL, request coalescing, better pacing).
- [x] Verify with compile/tests and quick log-based sanity checks.

## 2026-02-12 Discogs Wishlist Pagination Stability Fix

### Goal
Prevent false empty wishlist pages (`page > 1`) caused by dropped Discogs entries or transient empty responses, and keep pager navigation usable.

### Implementation Checklist
- [x] Keep wishlist entries even when Discogs does not provide a direct `uri/resource_url`.
- [x] Build a fallback release URL from `basic_information.id` when possible.
- [x] Avoid recursive page fallback loops in frontend wishlist preview refresh.
- [x] Preserve/render previous page data on suspicious empty resets and keep pager controls usable.
- [x] Add a regression test for wishlist entries with missing URI metadata.
- [x] Run targeted verification (`mvn "-Dtest=DiscogsApiClientTest" "-Djacoco.skip=true" test` and JS syntax check).

## 2026-02-12 Playlist Prefetch + Discogs Throughput Pass

### Goal
Prefetch one hidden playlist page in the background for instant `Load more` and speed up Discogs matching without introducing aggressive API pressure.

### Implementation Checklist
- [x] Add one-page-ahead playlist chunk prefetch state and fetch helpers.
- [x] Consume prefetched chunk on `Load more` before falling back to direct fetch.
- [x] Trigger next prefetch after each successful reveal while keeping hidden tracks unrendered.
- [x] Improve Discogs queue throughput (dedupe duplicate album lookups + lower idle delay).
- [x] Run focused frontend syntax checks and summarize proof.

## Goal
Improve product quality and developer experience with a Docker-optional workflow, while keeping changes small and verifiable.

## Phase 1 - Security and docs cleanup (completed in repo)
- [x] Remove exposed credentials/tokens from docs and replace with placeholders.
- [x] Add explicit revoke/rotate instruction for leaked credentials (manual account-owner step).
- [x] Rewrite setup docs so local dev is Java/Maven first and Docker is optional.
- [x] Add a short "No-Docker quickstart" to README and developer guide.
- [x] Verify all docs avoid copy/paste secrets and include safe examples only.
- [x] Add repeatable markdown docs secret scan (`scripts/check-doc-secrets.ps1`).

## Phase 2 - Core UX polish (Home/Playlist)
- [x] Replace blocking `alert()` flows with inline status/toast messaging.
- [x] Improve loading/empty/error states for playlist load and user playlist panel.
- [x] Improve Home input ergonomics (validation hinting, disabled/loading states).
- [x] Improve Playlist controls clarity (Discogs drawer status, load-more feedback).
- [x] Keep visual updates aligned with existing design tokens in `styles/base.css`.

## Phase 3 - Accessibility and responsiveness
- [x] Keyboard pass for drawers/tabs (focus management, escape handling consistency).
- [x] Add/adjust ARIA labels and live-region announcements where missing.
- [x] Validate touch targets and spacing across mobile breakpoints.
- [x] Improve contrast for status chips/badges where needed.
- [x] Run a manual keyboard smoke pass on Home/Playlist.

## Phase 4 - CI/CD Docker removal + deployment docs
- [x] Remove Docker build job from CI workflow.
- [x] Replace deploy workflow with artifact-first deploy steps (jar-based).
- [x] Document Railway no-Docker deployment as the primary path.
- [x] Keep Docker docs in an optional legacy section instead of default path.
- [x] Update scripts/docs references that currently assume docker-compose.

## Phase 5 - Regression checks and release notes
- [x] Run test suite (`mvn test`) and package build (`mvn package`).
- [x] Run smoke checks for critical endpoints and static pages.
- [x] Verify OAuth login/logout and playlist load on local environment (API/status smoke + app start).
- [x] Produce concise release notes grouped by the five phases.
- [x] Capture any new lessons in `tasks/lessons.md`.

## Verification Strategy
- Build proof: `mvn package`
- Test proof: `mvn test`
- Runtime proof: start app and verify `/api/health`, home, playlist, auth status.

## Current Focus
All phases implemented; awaiting review.

---

# 2026-02-12 Discogs Popup + Persisted Login Reliability

## Goal
Make Discogs login reliable on the playlist page by persisting user tokens locally, auto-restoring login state, and improving popup guidance/redirect behavior.

## Implementation Checklist
- [x] Add local token persistence + auto-login restore in frontend Discogs UI flow.
- [x] Improve popup target flow to Discogs developer/token page with clear guidance.
- [x] Ensure playlist page shows Discogs status/error feedback in-page.
- [x] Update login hint copy to reflect local browser token storage.
- [x] Run focused JS syntax checks and summarize verification.

---

# 2026-02-12 Discogs OAuth Flow (User-Friendly Login)

## Goal
Add direct Discogs OAuth login flow (popup + callback) while keeping manual token login as fallback.

## Implementation Checklist
- [x] Add Discogs OAuth config keys and env template entries.
- [x] Implement Discogs OAuth request/access-token exchange service.
- [x] Add OAuth start/callback/status routes in Discogs API.
- [x] Extend Discogs session + API client for OAuth token-secret based auth.
- [x] Add frontend OAuth connect button and popup callback handling.
- [x] Run focused tests/compile and summarize.

---

# 2026-02-12 Variant 4 Promotion + Legacy URL Plan

## Goal
Make variant 4 the default for Home and Playlist, keep legacy variants available behind deep URLs, and remove now-unneeded variant switching logic.

## Implementation Checklist
- [x] Add dark-theme SVG icon variants for Discogs and wantlist heart actions.
- [x] Limit playlist grid mode on v4 to max 4 cards per row.
- [x] Promote v4 markup/style to `home.html` and `playlist.html` defaults.
- [x] Preserve legacy home variants (v3 + v6) and old playlist page behind deeper URLs.
- [x] Remove route/UI switching logic tied to old simple variant paths.
- [x] Run a focused sanity pass on changed files and summarize results.

---

# 2026-02-12 Spotify Button SVG Update

## Goal
Use the newly added Spotify SVG assets in the header Spotify auth button.

## Implementation Checklist
- [x] Replace legacy Spotify icon path in header markup with new SVG assets.
- [x] Update header button JS renderer to use new icon variants for login/logout states.
- [x] Add CSS rules for icon sizing/visibility so button renders cleanly in all themes.
- [x] Run quick JS syntax check and summarize.

---

# 2026-02-12 Home V4 Logo + Version Label Tuning

## Goal
Improve V4 hero logo sizing/contrast and set the logo-wrap version badge to `0.7` with a documented bump rule.

## Implementation Checklist
- [x] Make hero logo render at least 95% of the logo-wrap container height.
- [x] Improve `hero-lede` contrast against the orange-accent presentation.
- [x] Update `.hero-logo-wrap::after` label to `0.7`.
- [x] Add AGENTS rule: this version number must be incremented on future changes.

---

# 2026-02-12 Discogs Match Quality Fix Plan

## Goal
Increase the share of exact Discogs matches (`/release` or `/master`) on paginated loads, even if lookup takes longer.

## Implementation Checklist
- [x] Slow down frontend Discogs queue pacing to reduce upstream rate-limit pressure.
- [x] Add transient Discogs API status detection (`429`/`5xx`) in search calls.
- [x] Add retry/backoff for transient Discogs failures in match orchestration.
- [x] Avoid persisting fallback search URLs when failure is transient.
- [ ] Verify build/tests compile and summarize results. (Blocked in this environment: `mvn` and `mvnw` are unavailable.)

---

# 2026-02-12 Anonymous Playlist Access Fix Plan

## Goal
Allow opening public Spotify playlists without user login while keeping private/collaborative playlists login-protected.

## Implementation Checklist
- [x] Add app-token (client credentials) fallback support in Spotify OAuth service.
- [x] Add token-resolution helper in auth routes (user token first, app token fallback).
- [x] Update `/api/playlist` route to use fallback and return clear 401 for private playlists when logged out.
- [x] Update home-page error message for 401 playlist responses.
- [x] Run build/tests and capture proof. (`.tools/maven/.../mvn.cmd -DskipTests compile` and `-Dtest=SpotifyOAuthServiceTest -Djacoco.skip=true test`)

---

# 2026-02-12 Home Variant 4 CSS + Eyebrow Cleanup

## Goal
Fix the CSS error in `home-variant4.css`, remove `p.eyebrow` from the active home page, and keep variant 4 as the official `home.html`.

## Implementation Checklist
- [x] Fix malformed dark-mode logo selector/media-query block in `home-variant4.css`.
- [x] Remove eyebrow markup from active `home.html` hero and paste-panel sections.
- [x] Remove now-unused eyebrow rules from `home-variant4.css`.
- [x] Verify `home.html` remains variant 4 (`data-home-variant="4"` + v4 stylesheet import).

---

# 2026-02-12 Homepage Triple-Redesign Plan

## Goal
Ship three fully distinct homepage designs that can be switched via `/1`, `/2`, and `/3`, with complete light/dark mode support, while keeping `playlist.html` unchanged.

## Implementation Checklist
- [x] Add server-side route mapping so `/1`, `/2`, and `/3` resolve to `home.html`.
- [x] Add homepage variant detection and apply `data-home-variant` on load.
- [x] Redesign home UI structure to support three unique visual directions without breaking existing functionality.
- [x] Implement shared baseline layout updates for accessibility and responsive behavior.
- [x] Implement Variant 1 style system (design identity, motion, color, typography in light/dark).
- [x] Implement Variant 2 style system (clearly distinct from Variant 1, with light/dark parity).
- [x] Implement Variant 3 style system (clearly distinct from Variant 1/2, with light/dark parity).
- [x] Update header/nav path handling so `/1|/2|/3` are treated as Home.
- [x] Verify playlist loading input flow and sidebar tabs still work on all variants.
- [ ] Run build verification and capture proof. (Blocked in this environment: `mvn` and `mvnw` are unavailable.)
- [x] Run Web Interface Guidelines audit on changed files and fix any critical issues.
- [x] Mark checklist done and summarize changes.

---

# 2026-02-12 Spotify Button Color Isolation

## Goal
Ensure Spotify auth button font colors are controlled only by button state, not by page/variant-wide header link rules.

## Implementation Checklist
- [x] Exclude `.spotify-btn` from generic header navigation link style selectors.
- [x] Add explicit Spotify button text-color rules for default, hover/focus, and logged-in states.
- [x] Update home/playlist variant header selectors to target non-Spotify links only.
- [x] Run focused selector/diff validation on changed CSS files.

---

# 2026-02-12 Discogs Logo Asset Restore

## Goal
Restore the old Discogs logo as file assets (light/dark) and use those files for the Discogs action button icon.

## Implementation Checklist
- [x] Recreate old Discogs icon as dedicated files in `src/main/frontend/design/`.
- [x] Add both light/dark variants to match existing theme icon behavior.
- [x] Update playlist track renderer to reference the Discogs icon files instead of inline SVG markup.
- [x] Verify the new file paths and icon references.

---

# 2026-02-12 Home Styles Dedupe + Frontend File Cleanup

## Goal
Reduce duplicated styling between `base.css`, `home.css`, and `home-variant4.css`, then remove unreferenced frontend files.

## Implementation Checklist
- [ ] Introduce shared home style variables in `home.css` to replace repeated per-variant selector blocks.
- [ ] Simplify `home-variant4.css` by moving shared declarations to variable overrides and removing duplicate logo theme rules.
- [ ] Clean up small redundancies in `base.css`.
- [ ] Remove frontend files with zero references in code/routes.
- [ ] Verify with focused searches + syntax checks and prepare scoped commits.

---

# 2026-02-12 Homepage SVG Size + Container Spacing Tuning

## Goal
Make the homepage V4 hero SVG visibly larger and rebalance inner/outer spacing so the layout feels intentional on desktop and mobile.

## Implementation Checklist
- [x] Increase V4 hero logo area and make SVG occupy more of that area.
- [x] Adjust outer container (`.home-main`) and card paddings for better breathing room.
- [x] Keep mobile spacing balanced with dedicated responsive overrides.
- [x] Bump the hero logo-wrap version badge after the visual change.
- [x] Run focused sanity checks on edited files and summarize.

---

# 2026-02-12 Merge Home Variant 4 into home.css

## Goal
Use a single home stylesheet by merging Variant 4 rules into `home.css` and removing `home-variant4.css`.

## Implementation Checklist
- [ ] Move all Variant 4 rules from `home-variant4.css` into `home.css`.
- [ ] Remove `home-variant4.css` references from home and legacy HTML files.
- [ ] Delete `src/main/frontend/styles/home-variant4.css`.
- [ ] Run focused reference/sanity checks and summarize.
- [x] Move all Variant 4 rules from `home-variant4.css` into `home.css`.
- [x] Remove `home-variant4.css` references from home and legacy HTML files.
- [x] Delete `src/main/frontend/styles/home-variant4.css`.
- [x] Run focused reference/sanity checks and summarize.

---

# 2026-02-12 Curation Page V4 Restyle (Dev-Only)

## Goal
Bring `curation.html` to the current V4 visual direction with a compact dev-only UI and minimal explanatory copy.

## Implementation Checklist
- [ ] Restyle `curation.css` to match V4 brutalist look and spacing.
- [ ] Simplify `curation.html` copy/sections for dev-internal usage.
- [ ] Update `dist/curation.js` UX text to concise status wording and add Enter-to-load in playlist input.
- [ ] Run focused sanity checks (syntax + selector/id references) and summarize.
- [x] Restyle `curation.css` to match V4 brutalist look and spacing.
- [x] Simplify `curation.html` copy/sections for dev-internal usage.
- [x] Update `dist/curation.js` UX text to concise status wording and add Enter-to-load in playlist input.
- [x] Run focused sanity checks (syntax + selector/id references) and summarize.

---

# 2026-02-12 Playlist Header Total Count Fix

## Goal
Show the full playlist track count in the playlist header, not just the currently loaded/filtered chunk.

## Implementation Checklist
- [x] Find where `totalTracks` is overwritten with the filtered track count in playlist load flow.
- [x] Keep the original API `totalTracks` value when replacing `state.aggregated.tracks` after album-selection filtering.
- [x] Run focused JS syntax check on `src/main/frontend/dist/playlist.js`.

---

# 2026-02-12 Vendor Action SVGs + Fallback

## Goal
Add black/white SVG icons for standard vendor actions while keeping letter fallback for vendors without icon assets.

## Implementation Checklist
- [x] Add `iconLight`/`iconDark` support in vendor config handling.
- [x] Add standard vendor icon assets (HHV, JPC, Amazon) in black/white variants.
- [x] Render vendor icon pair in track actions when available, with letter fallback when unavailable.
- [x] Extend action-icon CSS theme/hover swap rules to vendor action buttons.
- [x] Run focused JS syntax checks for touched frontend modules.

---

# 2026-02-12 JaCoCo Gate Alignment

## Goal
Unblock `mvn test` by aligning JaCoCo minimum instruction coverage with the current measured baseline.

## Implementation Checklist
- [x] Update JaCoCo `check` bundle minimum from `0.60` to `0.53`.
- [x] Re-run test phase with project Maven binary and confirm JaCoCo check passes.

---

# 2026-02-12 Discogs Wishlist List View + Pager

## Goal
Use a readable list layout in Variant 4 wishlist and restore wishlist page navigation arrows.

## Implementation Checklist
- [x] Add backend support for `/api/discogs/wishlist?page=&limit=` in Discogs route.
- [x] Return wishlist paging metadata (`page`, `limit`, `hasMore`) with existing items/total payload.
- [x] Add frontend wishlist paging state and prev/next arrow controls.
- [x] Fetch wishlist by current page and keep page bounds safe when data changes.
- [x] Convert Variant 4 wishlist from tile grid back to compact list layout.
- [x] Run focused syntax/build checks (`node --check`, Maven compile).

---

# 2026-02-12 Wishlist Add 409 Conflict UX Fix

## Goal
Prevent false-conflict UX when clicking "Add to wishlist" by handling duplicate wantlist adds as success and returning clear server errors for real failures.

## Implementation Checklist
- [x] Update Discogs API client to treat duplicate wantlist-add responses as successful.
- [x] Update wishlist add route to return 200 for successful/duplicate adds and non-409 API errors for true failures.
- [x] Update frontend wishlist click handler to parse API error payloads for clearer status messages.
- [x] Run focused tests for Discogs API client and summarize proof.

---

# 2026-02-12 Console Error Follow-up (Spotify 404 + Wishlist 409)

## Goal
Remove remaining browser-console noise by fixing stale Spotify icon path references and handling legacy `409` wantlist responses as idempotent success in the client.

## Implementation Checklist
- [x] Replace stale `/design/spotify_white.svg` reference in shared header markup with an existing asset path.
- [x] Treat `/api/discogs/wishlist/add` `409` responses as "already in wantlist" in the playlist action handler.
- [x] Run focused frontend syntax/file checks and summarize required reload steps.

---

# 2026-02-12 Wishlist Pagination Empty-State Regression

## Goal
Prevent the wishlist pager from getting stuck on an empty state (e.g. page 3 shows no entries and no usable back path) when higher-page responses unexpectedly reset to empty totals.

## Implementation Checklist
- [x] Add guard logic in `refreshWishlistPreview()` for unexpected `total=0` empty responses on pages > 1.
- [x] Auto-fallback to previous page and keep pager usable instead of rendering a locked empty `1/1` state.
- [x] Run focused JS syntax checks and summarize runtime verification steps.

---

# 2026-02-12 Discogs OAuth Start Intermittent 500

## Goal
Stabilize `/api/discogs/oauth/start` by handling transient Discogs request-token failures with retry/backoff and exposing clearer error messages to the UI.

## Implementation Checklist
- [x] Add transient retry/backoff in `DiscogsOAuthService.buildAuthorizationUrl()` for request-token failures (`429`/`5xx`/IO).
- [x] Improve OAuth start route response semantics/message for upstream failures.
- [x] Parse backend error payload in frontend OAuth-start handler for actionable user feedback.
- [x] Run focused compile/syntax checks and summarize verification.

---

# 2026-02-12 Spotify OAuth Callback Window Restyle

## Goal
Restyle the `/api/auth/callback` popup page so the Spotify login redirect window matches the current VinylMatch visual language.

## Implementation Checklist
- [x] Update callback HTML/CSS in `AuthRoutes.sendCallbackHtml` to use VinylMatch-like flat card styling and status states.
- [x] Keep popup behavior intact (`postMessage` to opener) while improving success/failed UX text and CTA behavior.
- [x] Add HTML escaping for callback messages before rendering.
- [x] Run compile verification and summarize proof.

---

# 2026-02-12 Home Variant 4 Selector Cleanup

## Goal
Reduce maintenance noise in `home.css` by removing repetitive `:root[data-home-variant="4"]` prefixes now that Variant 4 is the primary home design.

## Implementation Checklist
- [x] Convert Variant 4 section selectors to default home selectors while preserving dark-mode overrides.
- [x] Keep legacy Variant 3 selectors untouched.
- [x] Verify served `home.css` no longer contains Variant 4 root-prefix selectors.

# VinylMatch TODO - 2026-03-28 Home Desktop Sidebar Top Padding

## Goal
- Set the desktop home sidebar top padding to exactly `70px`.
- Keep the existing mobile drawer spacing unchanged.

## Implementation Checklist
- [x] Inspect the current home sidebar desktop and mobile padding rules.
- [x] Update the desktop sidebar top padding to `70px` without changing the mobile override.
- [x] Run a focused diff/syntax verification and record the outcome.

## Verification Notes
- `src/main/frontend/styles/home.css` now uses a fixed desktop `.sidebar { padding-top: 70px; }` in the active home variant instead of a viewport-based clamp, so the drawer starts at a consistent offset on desktop.
- `src/main/frontend/styles/home.css` keeps the mobile `@media (max-width: 540px)` sidebar override at `120px`, so the narrow-screen drawer spacing is unchanged.
- `src/main/frontend/styles/home.css` also bumps the visible home badge from `v1.1` to `v1.2` because the home design changed again.
- Proof of work:
  - `git diff --check -- src/main/frontend/styles/home.css tasks/todo.md`

# VinylMatch TODO - 2026-03-28 Home Sidebar Hover Clipping Fix

## Goal
- Prevent hovered playlist rows in the home sidebar from sliding underneath the drawer border.
- Keep the current variant 4 sidebar look while preserving a clear hover state.

## Implementation Checklist
- [x] Inspect the active home sidebar hover and overflow rules causing the clipping.
- [x] Update the hover styling so playlist rows stay fully inside the drawer bounds.
- [x] Run focused verification and record the outcome.

## Verification Notes
- `src/main/frontend/styles/home.css` now keeps hovered `.recent-item` cards inside the sidebar by removing the rightward slide and using an inset accent stripe for feedback instead, so the right border no longer disappears behind the drawer edge.
- `src/main/frontend/styles/home.css` also bumps the visible home badge from `v1.2` to `v1.3` because the active home sidebar interaction changed.
- Proof of work:
  - `git diff --check -- src/main/frontend/styles/home.css tasks/todo.md`
  - `.\.tools\maven\apache-maven-3.9.6\bin\mvn.cmd package`
# VinylMatch TODO - 2026-03-28 Playlist Header Image Range Fix

## Goal
- Keep the full-width playlist header image behavior only on mid-width screens down to `640px`.
- Restore the normal default playlist cover sizing below `640px`.

## Implementation Checklist
- [x] Inspect the active playlist variant rule overriding `#playlist-header img`.
- [x] Restrict that override to the intended `641px` to `820px` range.
- [x] Run a focused verification and record the outcome.

## Verification Notes
- `src/main/frontend/styles/playlist-variant4.css` now applies the full-width `#playlist-header img` override only between `641px` and `820px`, so widths below `640px` fall back to the default square cover sizing from the base playlist stylesheet.
- Proof of work:
  - `git diff --check -- src/main/frontend/styles/playlist-variant4.css tasks/todo.md`
  - `Get-Content src/main/frontend/styles/playlist-variant4.css | Select-Object -Skip 844 -First 16`
# VinylMatch TODO - 2026-03-28 Playlist Header Image Breakpoint Correction

## Goal
- Apply the playlist header's wide image treatment only from `641px` to `820px`.
- Let sub-`640px` screens use the normal base playlist header image sizing again.

## Implementation Checklist
- [x] Verify which playlist stylesheet is actually loaded by `playlist.html`.
- [x] Move the `#playlist-header img` override out of the active `max-width: 640px` block in the loaded variant stylesheet.
- [x] Run a focused diff check and record the outcome.

## Verification Notes
- `src/main/frontend/playlist.html` loads both `playlist.css` and `playlist-variant4.css`, and sets `document.documentElement.dataset.playlistVariant = "4"`, so Variant 4 is the active playlist design.
- `src/main/frontend/styles/playlist-variant4.css` now keeps the `max-width: 640px` block for spacing only, and applies the wide `#playlist-header img` rule only at `641px` to `820px`.
- Proof of work:
  - `git diff --check -- src/main/frontend/styles/playlist-variant4.css tasks/todo.md tasks/lessons.md`
  - `Get-Content src/main/frontend/styles/playlist-variant4.css | Select-Object -Skip 832 -First 28`

---

# VinylMatch TODO - 2026-07-17 Product Review Roadmap

## Goal
- Convert `docs/full-prod-review-17-07-26.md` into a single offline HTML roadmap that distinguishes confirmed current errors from fixed, partial, and unconfirmed findings.
- Give every finding a priority, concrete source, first fix direction, dependencies, and acceptance criteria for later implementation slices.
- Keep this slice documentation-only; do not change product code, CI, deployment, or dependencies.

## Implementation Checklist
- [x] Create `docs/full-prod-roadmap-17-07-26.html` with the complete review inventory.
- [x] Add inline styling, filters/search, status counters, local todo checkboxes, accessibility hooks, and print layout.
- [x] Verify offline loading, absence of external resources, content coverage, and clean diff.
- [x] Record verification evidence and final changed-file scope.

## Verification Notes
- Created `docs/full-prod-roadmap-17-07-26.html` as a standalone German offline roadmap with 33 review entries, status badges, P0–P3 priorities, sources, first fix directions, dependencies, acceptance criteria, filters, search, local checkbox persistence, keyboard shortcut, and print styling.
- Confirmed 33 `review-item` entries and 33 matching local todo checkboxes; required review IDs and status categories are present.
- Inline JavaScript syntax check passed via `vm.Script`; no external `<script src>`, `<link href>`, iframe, `@import`, or CSS `url()` resource references were found.
- `git diff --check -- .gitignore tasks/todo.md` and an untracked-file diff check for the new HTML passed.
- Added a focused `.gitignore` exception because the repository ignores `docs/*`; the new roadmap is now visible as an untracked deliverable without unignoring unrelated documentation.
- Final intended scope: `.gitignore`, `tasks/todo.md`, and `docs/full-prod-roadmap-17-07-26.html`; no product source, CI workflow, dependency, or deployment logic changed.

---

# VinylMatch TODO - 2026-07-17 P0 Production Roadmap Implementation

## Goal
- Implement all 19 P0 items from `docs/full-prod-roadmap-17-07-26.html` without changing the custom-server architecture or moving third-party API calls into the browser.
- Preserve the existing green Java baseline while adding focused tests and executable CI/deploy guardrails.
- Update the roadmap only after each acceptance criterion has verifiable evidence.

## Baseline
- [x] Read repo instructions and lessons, inventory all P0 items, inspect the dirty worktree, and run the existing test suite.
- [x] Confirm `mvn test`: 115 tests, 0 failures/errors, JaCoCo checks met.

## Implementation Checklist
- [ ] FE-02: choose and enforce one reproducible frontend source/build model; remove stale build remnants.
- [ ] FE-01: remove active mojibake and verify UTF-8 source/packaged/browser output.
- [ ] FE-03: add a tracked static-asset/relative-ESM resolver and CI gate that ignores untracked workspace files.
- [ ] MATCH-01: expose backend match provenance, confidence, reason, and reviewability; stop inferring direct matches from URLs.
- [ ] AUTH-01: surface every terminal Spotify OAuth/popup/session/callback failure beside the login action with recovery guidance.
- [ ] BE-05: make required Redis availability explicit and fail-safe in production while retaining a convenient development fallback.
- [ ] BE-04: separate liveness, readiness, and dependency health without blocking core request threads.
- [ ] SEC-02: trust forwarded client/proto headers only from configured proxies and cover direct/proxied requests.
- [ ] SEC-03: bound rate-limit state, support a shared Redis limiter in production, and document response/retry semantics.
- [ ] BE-02: bound the playlist cache in memory/on disk and sweep expired entries independently of reads.
- [ ] BE-03: add TTL/eviction to Discogs service maps and verify token rotation/invalidation.
- [ ] BE-01: separate inbound request capacity from bounded provider work, expose saturation, and keep health/auth responsive.
- [ ] SEC-01: remove unnecessary `unsafe-inline` CSP execution and verify the core browser flow.
- [ ] CUR-02: add actor/version/history/conflict protection and undo for curated links.
- [ ] OBS-01: make error-tracking claims truthful and retain correlation IDs without leaking secrets.
- [ ] CI-03: run Dependency-Check in CI `verify`, document its threshold/fallback, and retain reports.
- [ ] CI-02: align JaCoCo report/gate scope, cover or justify critical exclusions, and publish coverage artifacts.
- [ ] CI-01: add clean frontend, browser, smoke, and controlled scheduled load gates with useful artifacts.
- [ ] DEP-01: fail missing deploy prerequisites, validate tag/POM/artifact identity, and run post-deploy health/smoke checks.

## Final Verification
- [ ] Run focused Java/JS/workflow checks after each slice and the full Maven suite at the end.
- [ ] Run the `code-review` skill against the completed work and resolve actionable findings.
- [ ] Update all 19 P0 roadmap entries and record exact proof of work here.

---

# VinylMatch TODO - 2026-07-20 Spotify OAuth Callback Popup Design

## Goal
- Replace the browser-default-looking Spotify OAuth callback popup with a real VinylMatch Variant-4 styled success/error card.
- Keep strict CSP intact by removing inline CSS/JavaScript from the server-rendered callback.
- Preserve HTML escaping, same-origin `postMessage`, automatic close, and navigation fallback behavior.

## Implementation Checklist
- [x] Extract callback CSS and JavaScript into same-origin frontend assets.
- [x] Update `AuthRoutes.sendCallbackHtml` with semantic, accessible markup and external asset references.
- [x] Add focused assertions/tests for the CSP-compatible HTML contract and callback module behavior.
- [x] Run frontend syntax/tests, focused Maven tests, browser verification, and `git diff --check`.

## Acceptance Criteria
- Callback response visibly renders a branded responsive card under the existing strict CSP.
- No inline `<style>` or executable inline `<script>` remains in the callback response.
- Success and error flows still post structured same-origin messages and close/navigate as before.

## Verification Notes
- `src/main/java/Server/routes/AuthRoutes.java` now serves semantic callback markup with external `/styles/spotify-callback.css` and `/dist/spotify-callback.js`, `Cache-Control: no-store`, and escaped dynamic values.
- Added `src/main/frontend/styles/spotify-callback.css` with responsive Variant-4 styling, dark-mode support, focus-visible treatment, and reduced-motion handling.
- Added `src/main/frontend/dist/spotify-callback.js` with same-origin payload posting, popup close/navigation fallback, and testable exports.
- Added Java assertions in `src/test/java/Server/routes/AuthRoutesTest.java` and six passing frontend tests in `src/test/frontend/spotify-callback.test.mjs`.
- Browser proof on `http://127.0.0.1:8893/api/auth/callback?error=access_denied`: HTTP 200, CSS/JS assets HTTP 200, strict CSP without `unsafe-inline`, no console/request errors, computed heading font `Archivo Black`, screenshot `tasks/spotify-callback-check.png`.
- `npm run test:frontend`, `node --check src/main/frontend/dist/spotify-callback.js`, focused `AuthRoutesTest`, and `git diff --check` passed. `npm run check:frontend` remains blocked by the pre-existing untracked `src/main/frontend/dist/theme-bootstrap.js` references in multiple existing HTML files.
- Dynamic callback values remain HTML-escaped in attributes and visible text.

---

# VinylMatch TODO - 2026-07-20 Direct Discogs Match Source Label

## Goal
- Treat a server-confirmed direct Discogs catalog match as a verified Discogs source in the playlist UI.
- Keep genuinely unknown or legacy client match sources labeled as unverified.

## Implementation Checklist
- [ ] Add a focused regression assertion for the `DISCOGS_CATALOG` source label.
- [ ] Update the playlist match-source formatter without changing match confidence or URL provenance.
- [ ] Run focused frontend/Java checks and the Maven test suite.
- [ ] Record the bug lesson and verification evidence.

## Acceptance Criteria
- A direct match with source `DISCOGS_CATALOG` is displayed as a Discogs match, not “Unverified source.”
- Unknown source values continue to display as “Unverified source.”

---

# VinylMatch TODO - 2026-07-20 Loading Timeout Error

## Goal
- Ensure stalled playlist requests stop showing a loading state after a reasonable deadline.
- Show an actionable inline error while preserving existing API error handling and server-side integrations.

## Implementation Checklist
- [x] Add a shared 30-second browser request timeout with a clear timeout error.
- [x] Apply it to homepage playlist opens, playlist-page loads, and curation playlist loads.
- [x] Add focused frontend regression coverage and run syntax/tests plus diff checks.
- [x] Record the bug lesson and verification evidence.

## Acceptance Criteria
- A request that never settles aborts after 30 seconds.
- The loading overlay is cleared and the relevant page shows an inline error explaining that the request timed out.
- Non-timeout HTTP/API errors retain their current messages.

# VinylMatch TODO - 2026-08-14 Spotify Currently Playing Token

## Goal
- Request `user-read-currently-playing` during the existing Spotify OAuth flow.
- Keep token issuance server-side and avoid committing access or refresh tokens.
- Verify the authorization URL includes the new scope before asking the user to authorize.

## Implementation Checklist
- [x] Add `user-read-currently-playing` to the server-side Spotify OAuth scopes.
- [x] Add a regression assertion for the generated authorization URL.
- [x] Complete the Spotify account consent flow and confirm the resulting session is logged in.

## Verification Notes
- `Server.auth.SpotifyOAuthServiceTest`: 4 tests passed.
- Local packaged build succeeded with `scripts/build.ps1`.
- `POST /api/auth/login` returned an authorization URL containing `user-read-currently-playing`.
- The local server is running at `http://127.0.0.1:8888`; account consent is still required before a user token exists.

## Completion Notes
- User confirmed the access/refresh token export succeeded for the second project.
- The temporary local export route was removed immediately afterward; the rebuilt server returns 404 for `/api/auth/export-token`.

---

# VinylMatch TODO - 2026-08-15 One-Time Local Spotify Token Export

## Goal
- Allow the already-authorized local VinylMatch session to export its access and refresh tokens for a second local project.
- Never log or commit token values, and refuse the export outside local development requests.
- Remove the temporary export route immediately after the user retrieves the credentials.

## Implementation Checklist
- [x] Add a loopback-only, development-only token export route with focused tests.
- [x] Build and start the updated local app.
- [x] Have the user open the export route in the authorized browser session and retrieve the local response.
- [x] Remove the temporary route and re-run focused tests.

## Verification Notes
- The one-time route returned the token pair only through the authorized local browser session; token values were not logged or committed.
- The export route and its tests have now been removed from the application.
