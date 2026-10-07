# Lessons Learned

## 2026-07-20
- A loading overlay is not a timeout: an unresolved browser `fetch()` can leave the page blocked forever, and cleanup in `finally` is unreachable until the promise settles.
- Add one shared abort deadline to user-triggered provider requests and test it with a fetch that never resolves; keep the timeout error user-facing so existing UI cleanup can run.

## 2026-07-20
- When adding a manual save form, preserve the entered URL after a failed request so users can correct or retry without retyping it.
- Make the save helper return success explicitly before clearing form state; a `finally` block resets loading flags even when persistence failed.

## 2026-07-20
- When a page combines primary data loading with slower third-party enrichment, show a persistent primary-load confirmation and explain that enrichment state is separate; otherwise honest review badges can make a successfully loaded page look broken.

## 2026-07-17
- When testing HTTP-header sanitization with JDK `Headers`, use a syntactically valid but policy-invalid value (for example an oversized ID); CR/LF is rejected by the JDK fixture itself and never reaches the application filter.
- Browser fixtures must navigate to the app's real static route (`/playlist.html`) and assert the element's actual semantic role (the Spotify action is a link); a visually correct screenshot can still expose a wrong test locator or shortcut route assumption.

## 2026-07-17
- When match quality depends on the path that produced a third-party URL, persist provenance and confidence with the URL and migrate URL-only cache entries to an explicit manual-review state; reconstructing quality from URL shape silently overstates certainty.
- Deployment jobs must fail explicitly when required secrets are absent and verify the same checksum before and after transport; conditional secret-based step skipping can otherwise report success without installing any artifact.

## 2026-05-07
- When changing paginated API fixture metadata, update all tests that assert pagination totals and add focused assertions for multi-page ID aggregation; otherwise a product fix can look broken because the fixture and expectation describe different totals.
- When caching freshly fetched third-party ID sets, return the fetched set immediately after storing it; otherwise the happy path can silently fall through to an empty fallback even though HTTP pagination and parsing succeeded.

## 2026-05-15
- GitHub Actions workflows must not reference `secrets.*` directly in `if:` expressions; copy needed secrets into job-level `env` values and gate steps on `env.*` so the workflow validates before jobs start.

## 2026-04-16
- When adding focused regression tests during a multi-file fix pass, run the compile/test phase immediately after each test file lands; small misses like a missing `StandardCharsets` import are cheaper to catch before more patches stack on top.
- When hardening server-rendered callback HTML against injection, verify both the rendered markup path and any inline script path; escaping visible text alone is insufficient if the same value can still flow into script construction.

## 2026-03-28
- When a user points to a specific responsive CSS snippet, verify the exact live block in the active stylesheet before claiming the breakpoint is fixed; base and variant files can both exist, and a stale read can miss the real override still in use.
- For fixed headers that can grow taller at mobile breakpoints, shift the wrap breakpoint earlier than the last barely-fitting width and update page top padding in the same change; otherwise exact widths like `640px` will land in a cramped in-between state.
- When a shared page has a base stylesheet plus a visual variant, recheck variant-only breakpoints after mobile tuning; an older variant `@media` block can reintroduce an unintended tablet layout even when the base CSS was already fixed.
- For fixed-height side drawers that mix static controls with paged result cards, do not let the drawer itself own vertical scrolling; make the active panel fill the remaining height and cap page size to the viewport, or normal desktop layouts will show dead bottom space in sparse states and nested scrollbars in full states.
- For mobile pages with fixed headers and floating utility buttons, always verify the narrowest breakpoint in a real browser screenshot; fixed-position controls should either reserve layout space or the header must wrap, otherwise they will overlap hero content/logo text even when desktop looks correct.
- When a frontend boot regression appears to affect all JS-driven interactions, verify it once in a fresh browser profile before changing app code; if the source tree works there, suspect stale cached static assets and harden cache headers or asset versioning instead of chasing a nonexistent runtime bug.
- For checked-in frontend `dist/` assets, verify every imported module is actually tracked with `git ls-files`; local builds can mask missing Git assets because untracked files still get copied into `target/frontend`, while clean CI/deploy checkouts will 404 those modules at runtime.
- For clipped sidebar list hovers, avoid moving cards toward a drawer edge when the container intentionally hides overflow; keep the item in place and express hover emphasis with inset borders, color, or shadow so edge borders stay visible.

## 2026-03-27
- For CI-hosted OWASP Dependency-Check runs, always wire the NVD API key from environment config and set a non-fatal update fallback; shared builder IPs get rate-limited often enough that unauthenticated NVD refreshes are not a reliable deploy gate.
- For Java HTTP services deployed behind Railway or similar platforms, bind the listener explicitly to `0.0.0.0` instead of relying on the runtime default address family; a build can pass while the platform still returns `502 connection refused` if the process is not reachable on the expected interface.
- Before adding a new ignore rule, verify the path is not tracked with `git ls-files` or `git status`; broad patterns like `.github/` can silently hide real project files if they are already committed.

## 2026-03-06
- Before declaring a frontend diff commit-ready, run `git diff --check` and remove stray backup artifacts like `.bak` files so review cleanup is handled before commit prep.
- If a secret/config value is documented for `.env` or config-file use, route the consuming code through centralized `Config` loading rather than raw `System.getenv`, or local configuration silently breaks.
- For encrypted Redis-backed sessions, never use a per-process random/time-based fallback key; the fallback must be stable across restarts or persisted sessions will force unnecessary re-logins.
- For per-item async match UI, never use nearly identical labels like `Search` and `Searching...`; badge copy must distinguish waiting, ready-to-open results, and manual-search-needed states, and action buttons should expose the same state in their tooltips/labels.
- When a third-party integration can fail for policy reasons (not just auth/network), preserve the upstream exception details through the backend boundary and map them to a specific API error code; otherwise the frontend can only show a misleading generic failure or a bad "log in" hint.

## 2026-02-11
- When writing setup docs, never include real credentials/tokens in command examples.
- Prefer secure placeholders and explicit rotation instructions if a leak is found.
- Replace blocking browser alerts with inline status messages on core flows to avoid interrupting user actions.
- Add an automated docs secret scan script so secret hygiene is verifiable and repeatable.
- For slide-in drawers, always pair visibility changes with keyboard focus management and inert/aria-hidden state.
- When JaCoCo gates block verification, run explicit test/build commands with documented flags and record why.

## 2026-02-12
- For paginated third-party lists, never drop entries during normalization just because optional link metadata is missing; preserve the row and derive a fallback link from stable IDs when possible, or pagination totals become misleading and pages appear "empty".
- For homepage route variants like `/1|/2|/3`, normalize trailing slashes server-side before file resolution to avoid hidden 404 edge cases.
- If legacy encoding characters make patch context unstable, rewrite the file in one pass and then re-run focused diff checks to verify no behavior was dropped.
- During UI redesigns, run a targeted guideline pass (forms, image sizing, motion/accessibility hooks) before finalizing to avoid avoidable compliance regressions.
- If variant switching is meant to be URL-driven, do not add in-page switch controls even if they are convenient for previewing.
- For external match APIs, treat `429`/`5xx` as transient: retry with backoff and avoid caching fallback search URLs from transient failures, or low-quality matches will persist across pages.
- For public read access without user login, resolve tokens in layered order (user token first, app token fallback) so private resources remain protected while anonymous public flows keep working.
- In PowerShell, quote Maven system properties containing dots (for example `"-Djacoco.skip=true"`) to avoid argument parsing issues.
- When promoting one UI variant to default, keep legacy variants on deep static URLs and remove shortcut route switching in both frontend boot code and server path mapping.
- For special CTA links inside shared nav bars (like Spotify auth), exclude them from broad `.navigation a` selectors and style them via dedicated selectors to prevent theme/variant color bleed.
- If an icon is expected to be reusable across components/themes, store it as a dedicated file in `design/` and reference it by path instead of embedding long inline SVG strings.
- When editing JS with PowerShell `-replace`, avoid literal `` `r`n `` insertion in single-quoted replacement strings; use `apply_patch` for multiline blocks or verify output lines immediately.
- When UI state uses a filtered `tracks` array, do not overwrite `totalTracks` with `tracks.length`; keep the API-provided total for headers and recents.
- For optional action icons, render icons only when both light/dark assets exist and keep a visible text fallback for incomplete vendor setups.
- For token-based third-party features (like Discogs wishlist), persist the user token locally (opt-in), auto-restore the server session on page load, and provide in-page status messages so users can recover without guessing.
- For popup-based OAuth flows, always close the loop with both `postMessage` callback signaling and a status-poll fallback so auth completion is detected even if popup messaging is blocked.
- For OAuth callback HTML pages, escape all dynamic query/error text before rendering to avoid reflected script injection in popup status views.
- Keep JaCoCo thresholds explicit and realistic for the active baseline; otherwise all tests can pass while CI/build still fails on `jacoco:check`.
- For paged side panels, keep pagination state and controls in the same module as data fetching; otherwise UI arrows disappear when layout-only refactors happen.
- For idempotent add actions backed by third-party APIs (like Discogs wantlist), treat duplicate responses as success and reserve frontend-visible errors for true failures to avoid noisy 409 UX.
- Keep static fallback markup asset paths aligned with actual files (especially icons loaded before JS hydration), or users will see persistent 404 console errors even when dynamic rendering later corrects the UI.
- For paginated third-party panels, treat a sudden `total=0` + empty page on `page>1` as suspicious if prior total was non-zero; auto-fallback one page instead of resetting UI to an unusable empty `1/1` state.
- For OAuth request-token starts against third-party APIs, treat `429`/`5xx` and IO errors as transient with short retry/backoff; otherwise users see avoidable intermittent 500s on repeated connect attempts.
- During bulk selector cleanup, avoid global text replacement of `:root ` without block scoping; it can leave orphaned `{` blocks and break CSS syntax.
- For slow third-party enrichment flows (like Discogs matching), never `await` the full queue in primary UI actions (`load`, `load more`); render content first and run lookups in the background to keep interactions responsive.

## 2026-02-13
- When a page already loads a base stylesheet plus a variant stylesheet, keep the variant file focused on real overrides only; repeated base declarations should be removed first to reduce CSS safely.
- Treat `transition: all` as a guideline violation in UI CSS; explicitly list transitioned properties to avoid accidental motion/layout regressions.
- For mobile form fields that users type into (`input`, `textarea`), enforce `16px` font-size on narrow breakpoints to prevent iOS zoom and input friction.
- When closing a todo item that was implemented earlier, add explicit verification notes with file references so the checklist reflects shipped behavior instead of stale status.
- For frequent badge sync on large track lists, prefer a normalized `artist+album` local cache and treat network status checks as cache-miss fallback only.
- For cache-backed ownership badges, never let wishlist hydration overwrite an existing `owned` state, and keep cache data across transient logged-out checks so auto-login flows can still reuse local ownership state.
- If list/grid toggles rerender track cards, reapply local badge state immediately after render; otherwise ownership indicators appear to flicker/disappear until the next scheduled refresh.

## 2026-02-19
- When replacing text badges with icon-only indicators, always keep explicit `role="img"` and meaningful `aria-label`/`title` strings so the state remains accessible and debuggable.
- In PowerShell, avoid over-escaped quote patterns for `rg`; prefer `setAttribute(...)`/simple token searches when checking JS attributes to prevent false "file not found" lookup errors.

## 2026-07-17
- Quote Maven `-D` arguments that contain dotted fully-qualified test names in PowerShell (for example `'-Dtest=Server.cache.PlaylistCacheTest'`); otherwise PowerShell can split the value and Maven reports a misleading unknown lifecycle phase.
- After adding a route helper that names a session/model type explicitly, run main compilation immediately and add the missing import before expanding tests; route-file wildcard assumptions do not apply in Java.
- Open OAuth popup windows synchronously inside the user click before awaiting the login-start request; navigate the blank popup only after the server returns the authorization URL, or browsers can classify the delayed `window.open` as a blocked popup.
- Accept OAuth callback messages only when origin, source window, and message type all match; keep status polling as a fallback and give closed-popup, timeout, malformed-status, and callback failures distinct actionable codes.
- Bind coverage reporting/gating and dependency scanning explicitly to Maven `verify`, then let CI run one `clean verify`; separate `test`, report, and later `clean package -DskipTests` steps can produce a green build that never executes the security gate and deletes earlier evidence.
- Keep JaCoCo report and check on the same unexcluded class scope, and upload coverage/security reports with `if: always()` so a failed gate leaves reviewable evidence.
- When a strict CSP removes `unsafe-inline`, server-rendered pages must move both inline styles and behavior scripts to same-origin assets; otherwise the browser can show unstyled default HTML while the backend response still looks correct.

## 2026-07-20
- When extending a serialized Java model with a new optional field, keep an overload for the prior constructor signature; otherwise unrelated fixture/unit tests fail at compile time even when the new JSON contract is correct.
- For server-provenance UI, pass the evidence object through the initial payload before rendering; normalizing a URL-only legacy field in the browser necessarily downgrades it to an honest but noisy low-confidence state.
