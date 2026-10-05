import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./tests/e2e",
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: [["line"], ["html", { outputFolder: "playwright-report", open: "never" }]],
  use: {
    baseURL: "http://127.0.0.1:8888",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
  },
  webServer: {
    command: "java -jar target/VinylMatch.jar",
    url: "http://127.0.0.1:8888/api/health/simple",
    reuseExistingServer: !process.env.CI,
    timeout: 30_000,
    env: { ...process.env, ENVIRONMENT: "development", PORT: "8888" },
  },
  projects: [
    { name: "desktop", use: { browserName: "chromium", viewport: { width: 1280, height: 900 } } },
    { name: "mobile", use: { browserName: "chromium", viewport: { width: 390, height: 844 } } },
  ],
});
