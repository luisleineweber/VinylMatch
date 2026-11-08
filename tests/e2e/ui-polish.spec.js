import { expect, test } from '@playwright/test';

for (const path of ['/', '/about.html', '/playlist.html']) {
  test(`page content clears the fixed header: ${path}`, async ({ page }) => {
    await page.route('**/api/auth/status', r => r.fulfill({ json: { loggedIn: false } }));
    await page.goto(path);
    await expect(page.locator('#header .logo')).toBeVisible();
    const header = await page.locator('#header').boundingBox();
    const content = await page.locator(path === '/' ? '.home-main' : path === '/about.html' ? '.about-hero' : '.playlist-main').boundingBox();
    expect(content.y).toBeGreaterThanOrEqual(header.y + header.height);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(page.viewportSize().width);
  });
}

test('broken artwork uses the record placeholder', async ({ page }) => {
  await page.route('**/api/auth/status', r => r.fulfill({ json: { loggedIn: false } }));
  await page.route('**/broken-cover.png', r => r.fulfill({ status: 404 }));
  await page.route('**/api/albums/extract', r => r.fulfill({ json: { albums: [] } }));
  await page.route('**/api/playlist?**', r => r.fulfill({ json: {
    playlistName: 'Artwork check', playlistCoverUrl: '/broken-cover.png', totalTracks: 1, hasMore: false,
    tracks: [{ spotifyTrackId: 'test', trackName: 'Track', album: 'Album', artist: 'Artist', coverUrl: '/broken-cover.png' }],
  } }));
  await page.goto('/playlist.html?id=artwork');
  for (const selector of ['#playlist-header img', '.track img.cover']) {
    const cover = page.locator(selector).first();
    await expect(cover).toHaveAttribute('src', '/assets/record-placeholder.svg');
    await expect.poll(() => cover.evaluate(img => img.complete && img.naturalWidth > 0)).toBe(true);
  }
});
