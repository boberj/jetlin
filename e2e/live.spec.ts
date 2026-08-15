import { expect, test } from "@playwright/test";

/**
 * End-to-end checks for the parts that unit tests cannot reach: that the client applies ops to a
 * real DOM correctly, that updates originating on the server arrive without being asked for, that
 * an update never overwrites what the user is currently typing, and that navigation moves between
 * views without reloading the page.
 */

test("first paint is server-rendered HTML, before any script runs", async ({ page }) => {
  await page.route("**/jetlin.js", (route) => route.abort());
  await page.goto("/");
  await expect(page.locator("[data-test=todo]")).toHaveCount(3);
  await expect(page.locator(".todo-text").first()).toHaveText("Read the architecture doc");
});

test("a deep link renders its own view server-side", async ({ page }) => {
  await page.route("**/jetlin.js", (route) => route.abort());
  await page.goto("/todo/2");
  // The path parameter resolved on the server, with no JavaScript involved at all.
  await expect(page.locator("[data-test=title]")).toHaveValue("Run the tests");
});

test("checking a box updates state on the server and patches the DOM", async ({ page }) => {
  await page.goto("/");
  const first = page.locator("[data-test=todo]").first();
  await expect(first.locator(".todo-text")).not.toHaveClass(/done/);

  await first.locator("input[type=checkbox]").check();
  await expect(first.locator(".todo-text")).toHaveClass(/done/);
});

test("the patch only touches the node that changed", async ({ page }) => {
  await page.goto("/");

  // Tag the surrounding DOM. The markers live only on the JavaScript objects, so they survive only
  // if the update leaves those exact nodes in place instead of replacing them.
  await page.evaluate(() => {
    document.querySelectorAll("[data-test=todo]").forEach((li, i) => {
      (li as HTMLElement).dataset.marker = `m${i}`;
    });
  });

  await page.locator("[data-test=todo]").first().locator("input[type=checkbox]").check();
  await expect(page.locator(".todo-text").first()).toHaveClass(/done/);

  const markers = await page.evaluate(() =>
    Array.from(document.querySelectorAll("[data-marker]")).map((n) => (n as HTMLElement).dataset.marker),
  );
  expect(markers).toEqual(["m0", "m1", "m2"]);
});

test("adding, reordering and removing keyed list items", async ({ page }) => {
  await page.goto("/");

  await page.locator("[data-test=draft]").fill("Third thing");
  await page.locator("[data-test=add]").click();
  await expect(page.locator(".todo-text").last()).toHaveText("Third thing");
  await expect(page.locator("[data-test=draft]")).toHaveValue("");

  await page.locator("li", { hasText: "Third thing" }).getByText("up").click();
  await expect(page.locator(".todo-text").nth(2)).toHaveText("Third thing");

  await page.locator("li", { hasText: "Third thing" }).getByText("remove").click();
  await expect(page.locator("[data-test=todo]")).toHaveCount(3);
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

  await expect(page.locator("[data-test=ticks]")).not.toHaveText("0");
  await expect(draft).toHaveValue("a slow sentence typed while the clock ticks");
});

test("navigation swaps the view without reloading the page", async ({ page }) => {
  await page.goto("/");

  // Survives a client-side navigation; would be wiped by a real page load.
  await page.evaluate(() => ((window as unknown as { sentinel?: string }).sentinel = "alive"));

  await page.locator(".todo-text").first().click();
  await expect(page).toHaveURL("/todo/1");
  await expect(page.locator("[data-test=title]")).toHaveValue("Read the architecture doc");
  await expect(page).toHaveTitle(/Edit/);

  expect(await page.evaluate(() => (window as unknown as { sentinel?: string }).sentinel)).toBe("alive");
});

test("the back button returns to the previous view", async ({ page }) => {
  await page.goto("/");
  await page.locator(".todo-text").first().click();
  await expect(page).toHaveURL("/todo/1");

  await page.goBack();
  await expect(page).toHaveURL("/");
  await expect(page.locator("[data-test=todo]")).toHaveCount(3);

  await page.goForward();
  await expect(page).toHaveURL("/todo/1");
  await expect(page.locator("[data-test=title]")).toHaveValue("Read the architecture doc");
});

test("validation runs on the server and blocks the save", async ({ page }) => {
  await page.goto("/todo/1");
  const title = page.locator("[data-test=title]");
  const save = page.locator("[data-test=save]");

  await expect(save).toBeEnabled();

  await title.fill("");
  await expect(page.locator("[data-test=title-error]")).toHaveText("A title is required");
  await expect(save).toBeDisabled();

  await title.fill("Renamed on the server");
  await expect(page.locator("[data-test=title-error]")).toHaveCount(0);
  await expect(save).toBeEnabled();

  await save.click();
  await expect(page).toHaveURL("/");
  await expect(page.locator(".todo-text").first()).toHaveText("Renamed on the server");
});

test("an unknown path renders a not-found view", async ({ page }) => {
  await page.goto("/todo/999");
  await expect(page.locator("h1")).toHaveText("No such todo");
});

test("session state survives a dropped connection", async ({ page }) => {
  await page.goto("/");
  await page.locator("[data-test=draft]").fill("Typed before the drop");

  await page.evaluate(() => (window as unknown as { jetlin: { disconnect(): void } }).jetlin.disconnect());
  await expect(page.locator("body")).toHaveClass(/jl-disconnected/);

  // The client reconnects with the same token; the server still holds the composition, so the
  // half-finished draft is still there.
  await expect(page.locator("body")).not.toHaveClass(/jl-disconnected/, { timeout: 15_000 });
  await expect(page.locator("[data-test=draft]")).toHaveValue("Typed before the drop");

  await page.locator("[data-test=add]").click();
  await expect(page.locator(".todo-text").last()).toHaveText("Typed before the drop");
});
