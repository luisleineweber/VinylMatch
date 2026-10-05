import { expect, test } from "@playwright/test";

for (const provider of ["spotify", "discogs"]) {
  test(`${provider} login callback works under the hardened CSP`, async ({ page, context }) => {
    const callbackPath = provider === "spotify" ? "/api/auth/callback" : "/api/discogs/oauth/callback";
    const callbackUrl = `${callbackPath}?error=access_denied`;
    const errors = [];
    page.on("pageerror", (error) => errors.push(error.message));
    page.on("console", (message) => {
      if (message.type() === "error") errors.push(message.text());
    });
    const response = await page.goto(callbackUrl);
    const csp = response.headers()["content-security-policy"];
    expect(csp).toContain("script-src 'self'");
    expect(csp).not.toContain("unsafe-inline");
    await expect(page.getByRole("heading", { level: 1 })).toContainText(/connection failed/);
    await expect(page.getByRole("link", { name: /return to vinylmatch/i })).toBeVisible();
    await expect(page.locator("script:not([src])")).toHaveCount(0);
    expect(errors).toEqual([]);

    const message = await page.locator("html").getAttribute("data-callback-message");
    const successHtml = (await response.text())
      .replace('data-callback-success="false"', 'data-callback-success="true"')
      .replace(/data-callback-code="[^"]*"/u, `data-callback-code="${provider}_connected"`);
    await context.route(new URL(callbackUrl, page.url()).href, (route) => route.fulfill({
      contentType: "text/html", body: successHtml, headers: { "Content-Security-Policy": csp },
    }));
    await page.evaluate(() => {
      window.callbackResult = null;
      window.addEventListener("message", (event) => {
        if (event.origin === window.location.origin) window.callbackResult = event.data;
      });
    });
    const popupPromise = page.waitForEvent("popup");
    await page.evaluate((url) => { window.open(url, "oauth-callback"); }, callbackUrl);
    const popup = await popupPromise;
    await expect.poll(() => page.evaluate(() => window.callbackResult)).toEqual({
      type: `${provider}-auth-callback`, success: true, code: `${provider}_connected`, message,
    });
    await expect.poll(() => popup.isClosed()).toBe(true);
  });
}
