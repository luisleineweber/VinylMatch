# Frontend source model

VinylMatch ships the files in this directory directly. The JavaScript under
`dist/` is intentionally hand-maintained ES modules and is source code, not a
generated or ignored build artifact. There is no TypeScript compilation step.

During `mvn package`, Maven copies the complete tracked frontend tree to
`target/frontend`. `node scripts/check-frontend-assets.mjs` verifies that every
local HTML `src`/`href` reference and every relative ES-module import resolves
to a tracked file. This keeps a clean checkout reproducible and prevents an
untracked local file from masking a broken deployment.

When changing the frontend:

1. Edit the files in `src/main/frontend` directly.
2. Add every new runtime asset to Git.
3. Run `node scripts/check-frontend-assets.mjs` and `mvn package`.
