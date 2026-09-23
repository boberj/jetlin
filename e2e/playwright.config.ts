// Runs the browser tests against a demo that's already running. Set JETLIN_URL if it isn't on
// localhost:8080.
import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: ".",
  timeout: 30_000,
  expect: { timeout: 10_000 },
  reporter: [["list"]],
  use: {
    baseURL: process.env.JETLIN_URL ?? "http://localhost:8080",
    // Use a preinstalled Chromium when one is provided, so CI images that already include a
    // browser don't have to download a second copy pinned to this Playwright version.
    launchOptions: process.env.CHROMIUM_PATH
      ? { executablePath: process.env.CHROMIUM_PATH }
      : {},
  },
});
