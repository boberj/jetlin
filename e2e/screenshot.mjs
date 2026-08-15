import { chromium } from "@playwright/test";

const browser = await chromium.launch({
  executablePath: process.env.CHROMIUM_PATH || undefined,
});
const page = await browser.newPage({ viewport: { width: 900, height: 1000 } });
await page.goto(process.env.JETLIN_URL ?? "http://localhost:8080");

// Interact so the screenshot shows live state, not just first paint.
await page.getByRole("button", { name: "+" }).click();
await page.getByRole("button", { name: "+" }).click();
await page.locator("[data-test=draft]").fill("Ship the walking skeleton");
await page.locator("[data-test=add]").click();
await page.waitForTimeout(2500);

await page.screenshot({ path: process.argv[2] ?? "jetlin-demo.png", fullPage: true });
await browser.close();
