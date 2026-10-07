import { expect, test } from "@playwright/test";

function failOnConsoleErrors(page) {
  const errors = [];
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(message.text());
  });
  return () => expect(errors, "browser console errors").toEqual([]);
}

test("logged-out home renders with hardened CSP", async ({ page }) => {
  const assertNoConsoleErrors = failOnConsoleErrors(page);
  await page.route("**/api/auth/status", (route) => route.fulfill({ json: { loggedIn: false } }));
  await page.route("**/api/config/vendors", (route) => route.fulfill({ json: { vendors: [] } }));

  const response = await page.goto("/");
  expect(response?.headers()["content-security-policy"]).not.toContain("unsafe-inline");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  await expect(page.getByRole("link", { name: /spotify/i })).toBeVisible();
  assertNoConsoleErrors();
});

test("playlist renders server-provided match provenance", async ({ page }) => {
  const assertNoConsoleErrors = failOnConsoleErrors(page);
  await page.route("**/api/auth/status", (route) => route.fulfill({ json: { loggedIn: false } }));
  await page.route("**/api/config/vendors", (route) => route.fulfill({ json: { vendors: [] } }));
  await page.route("**/api/albums/extract", (route) => route.fulfill({ json: { albums: [] } }));
  await page.route("**/api/playlist?**", (route) => route.fulfill({ json: {
    playlistName: "Fixture Playlist",
    playlistCoverUrl: null,
    playlistUrl: "https://open.spotify.com/playlist/fixture",
    totalTracks: 1,
    offset: 0,
    nextOffset: 1,
    hasMore: false,
    tracks: [{
      spotifyTrackId: "track-1",
      trackName: "Fixture Track",
      artist: "Fixture Artist",
      album: "Fixture Album",
      releaseYear: 2024,
      albumUrl: null,
      discogsAlbumUrl: "https://www.discogs.com/master/123-fixture",
      discogsMatch: {
        url: "https://www.discogs.com/master/123-fixture",
        matchType: "EXACT_MASTER",
        confidence: "HIGH",
        source: "DISCOGS_CATALOG",
        reason: "Matched artist and album metadata; choose a pressing.",
        vinylFormatConfirmed: false,
      },
      barcode: "123456789",
      coverUrl: null,
    }],
  } }));

  await page.goto("/playlist.html?id=fixture");
  await expect(page.getByText("Fixture Playlist")).toBeVisible();
  await expect(page.getByText("Master match")).toBeVisible();

  const matchInfo = page.locator(".match-evidence-toggle");
  const matchEvidence = page.locator(".match-evidence");
  await expect(matchInfo).toBeVisible();
  await expect(matchInfo).toHaveAttribute("aria-expanded", "false");
  await expect(matchEvidence).toBeHidden();

  await matchInfo.click();
  await expect(matchInfo).toHaveAttribute("aria-expanded", "true");
  await expect(matchEvidence).toBeVisible();
  await expect(matchEvidence).toContainText(/Discogs catalog · high confidence/i);
  await expect(matchEvidence).toContainText(/choose a pressing/i);

  await matchInfo.click();
  await expect(matchEvidence).toBeHidden();
  assertNoConsoleErrors();
});
