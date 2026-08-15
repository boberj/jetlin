import { expect, test } from "@playwright/test";

/**
 * End-to-end checks for the parts of a server-driven framework that unit tests cannot reach: that
 * the client applies ops correctly, that server push arrives unprompted, and — the failure mode
 * every LiveView-style framework is judged on — that a server update never eats what the user is
 * currently typing.
 */

test("first paint is server-rendered HTML, before any script runs", async ({ page }) => {
  await page.route("**/jetlin.js", (route) => route.abort());
  await page.goto("/");
  await expect(page.locator("[data-test=count]")).toHaveText("0");
  await expect(page.locator("li")).toHaveCount(2);
});

test("clicking updates state on the server and patches the DOM", async ({ page }) => {
  await page.goto("/");
  const count = page.locator("[data-test=count]");
  await expect(count).toHaveText("0");

  await page.getByRole("button", { name: "+" }).click();
  await expect(count).toHaveText("1");

  await page.getByRole("button", { name: "+" }).click();
  await page.getByRole("button", { name: "+" }).click();
  await expect(count).toHaveText("3");

  await page.getByRole("button", { name: "−" }).click();
  await expect(count).toHaveText("2");
});

test("the patch only touches the node that changed", async ({ page }) => {
  await page.goto("/");
  const count = page.locator("[data-test=count]");
  await expect(count).toHaveText("0");

  // Tag the surrounding DOM. If the client were morphing re-rendered HTML rather than applying
  // targeted ops, these nodes would be replaced and the markers would be gone.
  await page.evaluate(() => {
    document.querySelectorAll("li").forEach((li, i) => ((li as HTMLElement).dataset.marker = `m${i}`));
    (document.querySelector("[data-test=count]") as HTMLElement).dataset.marker = "count";
  });

  await page.getByRole("button", { name: "+" }).click();
  await expect(count).toHaveText("1");

  const markers = await page.evaluate(() =>
    Array.from(document.querySelectorAll("[data-marker]")).map((n) => (n as HTMLElement).dataset.marker),
  );
  expect(markers).toEqual(["count", "m0", "m1"]);
});

test("adding, reordering and removing keyed list items", async ({ page }) => {
  await page.goto("/");

  await page.locator("[data-test=draft]").fill("Third thing");
  await page.locator("[data-test=add]").click();
  await expect(page.locator(".todo-text")).toHaveText([
    "Read the architecture doc",
    "Run the tests",
    "Third thing",
  ]);
  // The input is cleared by the server, which means the round-trip completed.
  await expect(page.locator("[data-test=draft]")).toHaveValue("");

  await page.locator("li", { hasText: "Third thing" }).getByText("up").click();
  await expect(page.locator(".todo-text")).toHaveText([
    "Read the architecture doc",
    "Third thing",
    "Run the tests",
  ]);

  await page.locator("li", { hasText: "Third thing" }).getByText("remove").click();
  await expect(page.locator(".todo-text")).toHaveText([
    "Read the architecture doc",
    "Run the tests",
  ]);
});

test("server pushes updates with no client interaction", async ({ page }) => {
  await page.goto("/");
  const ticks = page.locator("[data-test=ticks]");
  await expect(ticks).toHaveText("0");
  await expect(ticks).toHaveText("2", { timeout: 8000 });
});

test("a server push does not clobber text the user is typing", async ({ page }) => {
  await page.goto("/");
  const draft = page.locator("[data-test=draft]");

  await draft.click();
  await draft.type("a slow sentence typed while the clock ticks", { delay: 60 });

  // The server-side clock has pushed several patches during that time.
  await expect(page.locator("[data-test=ticks]")).not.toHaveText("0");
  await expect(draft).toHaveValue("a slow sentence typed while the clock ticks");
});

test("session state survives a dropped connection", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "+" }).click();
  await page.getByRole("button", { name: "+" }).click();
  await expect(page.locator("[data-test=count]")).toHaveText("2");

  await page.evaluate(() => (window as unknown as { jetlin: { disconnect(): void } }).jetlin.disconnect());
  await expect(page.locator("body")).toHaveClass(/jl-disconnected/);

  // The client reconnects with the same token; the server still holds the composition, so the
  // count is preserved rather than reset.
  await expect(page.locator("body")).not.toHaveClass(/jl-disconnected/, { timeout: 15_000 });
  await expect(page.locator("[data-test=count]")).toHaveText("2");

  // And the reattached session is still interactive.
  await page.getByRole("button", { name: "+" }).click();
  await expect(page.locator("[data-test=count]")).toHaveText("3");
});
