// Takes two screenshots of the running demo: the todo list after a few interactions, and a detail
// page.
//
// Usage: node screenshot.mjs [LIST_PNG] [DETAIL_PNG]
//
// Start the demo first. Set JETLIN_URL if the demo isn't on localhost:8080, and CHROMIUM_PATH to use
// a Chromium that's already installed.
import { chromium } from "@playwright/test";

const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM_PATH || undefined,
});
const page = await browser.newPage({ viewport: { width: 900, height: 900 } });
await page.goto(process.env.JETLIN_URL ?? "http://localhost:8080");

// Interact, so the screenshot shows live state, not only the first paint.
await page.locator("[data-test=todo]").first().locator("input[type=checkbox]").check();
await page.locator("[data-test=draft]").fill("Ship routing and forms");
await page.locator("[data-test=add]").click();
await page.waitForTimeout(2500);

await page.screenshot({ path: process.argv[2] ?? "jetlin-list.png", fullPage: true });

// And the detail view, reached by navigation instead of a page load.
await page.locator(".todo-text").nth(1).click();
await page.waitForTimeout(400);
await page.screenshot({ path: process.argv[3] ?? "jetlin-detail.png", fullPage: true });

await browser.close();
