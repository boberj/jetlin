import { expect, test } from "@playwright/test";

/**
 * End-to-end tests for what unit tests can't reach: the client applies ops to a real DOM
 * correctly, updates that start on the server arrive without being asked for, an update never
 * overwrites what the user is typing, and navigation moves between views without reloading the
 * page.
 */

/**
 * The demo's todo store is shared by the whole process. That's deliberate, because two browser
 * windows showing each other's edits is one of the things the demo shows. It also means the tests
 * share it, so each test starts by resetting it to its seeded state, instead of assuming whatever
 * the previous test left behind.
 */
test.beforeEach(async ({ page }) => {
  await page.goto("/");
  await page.locator("[data-test=reset]").click();
  await expect(page.locator("[data-test=todo]")).toHaveCount(3);
});

test("first paint is server-rendered HTML, before any script runs", async ({ page }) => {
  await page.route("**/jetlin.js", (route) => route.abort());
  await page.goto("/");
  await expect(page.locator("[data-test=todo]")).toHaveCount(3);
  await expect(page.locator(".todo-text").first()).toHaveText("Read the architecture doc");
});

test("a deep link renders its own view server-side", async ({ page }) => {
  await page.route("**/jetlin.js", (route) => route.abort());
  await page.goto("/todo/2");
  // The server resolved the path parameter, with no JavaScript involved at all.
  await expect(page.locator("[data-test=title]")).toHaveValue("Run the tests");
});

test("checking a box updates state on the server and patches the DOM", async ({ page }) => {
  const first = page.locator("[data-test=todo]").first();
  await expect(first.locator(".todo-text")).not.toHaveClass(/done/);

  await first.locator("input[type=checkbox]").check();
  await expect(first.locator(".todo-text")).toHaveClass(/done/);
});

test("the patch only touches the node that changed", async ({ page }) => {
  // Wait for a server-pushed tick before tagging anything. It can arrive only once the socket is
  // live and its opening message has been handled, so the DOM being tagged has settled.
  await expect(page.locator("[data-test=ticks]")).toHaveText("1", { timeout: 8000 });

  // Tag the surrounding DOM. The markers exist only on the JavaScript objects, so they survive
  // only if the update leaves those exact nodes in place instead of replacing them.
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
  await page.locator("[data-test=draft]").fill("Third thing");
  await page.locator("[data-test=add]").click();
  await expect(page.locator("[data-test=todo]")).toHaveCount(4);
  await expect(page.locator(".todo-text").last()).toHaveText("Third thing");
  // The server cleared it, which means the round trip completed.
  await expect(page.locator("[data-test=draft]")).toHaveValue("");

  await page.locator("li", { hasText: "Third thing" }).getByText("up").click();
  await expect(page.locator(".todo-text").nth(2)).toHaveText("Third thing");

  await page.locator("li", { hasText: "Third thing" }).getByText("remove").click();
  await expect(page.locator("[data-test=todo]")).toHaveCount(3);
});

test("resetting is itself a server-driven update", async ({ page }) => {
  await page.locator("[data-test=draft]").fill("Something extra");
  await page.locator("[data-test=add]").click();
  await expect(page.locator("[data-test=todo]")).toHaveCount(4);

  await page.locator("[data-test=reset]").click();
  await expect(page.locator("[data-test=todo]")).toHaveCount(3);
  await expect(page.locator(".todo-text").first()).toHaveText("Read the architecture doc");
});

test("server pushes updates with no client interaction", async ({ page }) => {
  const ticks = page.locator("[data-test=ticks]");
  await expect(ticks).toHaveText("0");
  await expect(ticks).toHaveText("2", { timeout: 8000 });
});

test("a server push does not clobber text the user is typing", async ({ page }) => {
  const draft = page.locator("[data-test=draft]");

  await draft.click();
  await draft.type("a slow sentence typed while the clock ticks", { delay: 60 });

  await expect(page.locator("[data-test=ticks]")).not.toHaveText("0");
  await expect(draft).toHaveValue("a slow sentence typed while the clock ticks");
});

test("navigation swaps the view without reloading the page", async ({ page }) => {
  // It survives a client-side navigation. A real page load would clear it.
  await page.evaluate(() => ((window as unknown as { sentinel?: string }).sentinel = "alive"));

  await page.locator(".todo-text").first().click();
  await expect(page).toHaveURL("/todo/1");
  await expect(page.locator("[data-test=title]")).toHaveValue("Read the architecture doc");
  await expect(page).toHaveTitle(/Edit/);

  expect(await page.evaluate(() => (window as unknown as { sentinel?: string }).sentinel)).toBe("alive");
});

test("the back button returns to the previous view", async ({ page }) => {
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
  await page.locator("[data-test=draft]").fill("Typed before the drop");

  await page.evaluate(() => (window as unknown as { jetlin: { disconnect(): void } }).jetlin.disconnect());
  await expect(page.locator("body")).toHaveClass(/jl-disconnected/);

  // The client reconnects with the same token. The server still holds the composition, so the
  // half-finished draft is still there.
  await expect(page.locator("body")).not.toHaveClass(/jl-disconnected/, { timeout: 15_000 });
  await expect(page.locator("[data-test=draft]")).toHaveValue("Typed before the drop");

  await page.locator("[data-test=add]").click();
  await expect(page.locator(".todo-text").last()).toHaveText("Typed before the drop");
});

/**
 * Client-only behavior: what the browser is trusted to do on its own.
 *
 * The point is that there's no round trip, which is hard to assert directly. So these tests assert
 * something stronger: the same interaction works with no server on the other end of the socket at
 * all.
 */

test("a disclosure opens without involving the server", async ({ page }) => {
  await page.goto("/about");
  const panel = page.locator("[data-test=disclosure-panel]");

  await expect(panel).toBeHidden();
  await page.locator("[data-test=disclosure-toggle]").click();
  await expect(panel).toBeVisible();
  await page.locator("[data-test=disclosure-toggle]").click();
  await expect(panel).toBeHidden();
});

test("it still works with the socket disconnected", async ({ page }) => {
  await page.goto("/about");
  // Wait for the connection, so that disconnecting means something.
  await expect(page.locator("body")).not.toHaveClass(/jl-disconnected/);

  await page.evaluate(() => (window as unknown as { jetlin: { disconnect(): void } }).jetlin.disconnect());
  await expect(page.locator("body")).toHaveClass(/jl-disconnected/);

  // Nothing can reach the server now, and the panel opens anyway.
  await page.locator("[data-test=disclosure-toggle]").click();
  await expect(page.locator("[data-test=disclosure-panel]")).toBeVisible();
});

test("it works before any script has connected, from the server-rendered markup", async ({ page }) => {
  // The commands travel in data-jl-on, which is in the HTML, so the button works as soon as the
  // runtime parses the page, not only once a socket is established.
  await page.goto("/about");
  await page.locator("[data-test=disclosure-toggle]").click();
  await expect(page.locator("[data-test=disclosure-panel]")).toBeVisible();
});

/**
 * Failures, seen from the browser.
 *
 * Two exceptions reach the transport the same way and mean completely different things. These
 * tests check that the difference survives all the way to the page.
 */

test("a handler that throws costs one interaction and nothing else", async ({ page }) => {
  await page.goto("/errors");

  await page.locator("[data-test=still-works]").click();
  await expect(page.locator("[data-test=clicks]")).toHaveText("1");

  await page.locator("[data-test=fail-handler]").click();

  // The application is told through the jetlin:error event, and shows what it likes.
  await expect(page.locator("[data-test=toast]")).toBeVisible();
  await expect(page.locator("[data-test=toast]")).toContainText("could not be completed");
  // Nothing about the exception itself crosses the wire.
  await expect(page.locator("[data-test=toast]")).not.toContainText("always going to");

  // And the session is intact: the count is where it was, and clicking still works.
  await expect(page.locator("[data-test=clicks]")).toHaveText("1");
  await page.locator("[data-test=still-works]").click();
  await expect(page.locator("[data-test=clicks]")).toHaveText("2");
});

test("a view that throws ends the session and the page starts over", async ({ page }) => {
  await page.goto("/errors");

  await page.locator("[data-test=still-works]").click();
  await page.locator("[data-test=still-works]").click();
  await expect(page.locator("[data-test=clicks]")).toHaveText("2");

  await page.locator("[data-test=fail-view]").click();

  // The client is told that this one can't be recovered, and reloads into a new session. That
  // shows up as the counter going back to zero on a page that works again.
  await expect(page.locator("[data-test=clicks]")).toHaveText("0", { timeout: 10_000 });
  await page.locator("[data-test=still-works]").click();
  await expect(page.locator("[data-test=clicks]")).toHaveText("1");
});

test("a page can take over a fatal error instead of being reloaded", async ({ page }) => {
  await page.goto("/errors?handle");

  await page.locator("[data-test=still-works]").click();
  await expect(page.locator("[data-test=clicks]")).toHaveText("1");

  await page.locator("[data-test=fail-view]").click();

  // preventDefault() on the fatal event: no reload, and the page says what state it's in.
  await expect(page.locator("[data-test=dead-banner]")).toBeVisible();
  await expect(page.locator("body")).toHaveClass(/jl-dead/);
  // The count survives, which proves the page wasn't reloaded.
  await expect(page.locator("[data-test=clicks]")).toHaveText("1");

  // And it really is dead: nothing on it can change again, whatever is clicked.
  await page.locator("[data-test=still-works]").click({ force: true });
  await expect(page.locator("[data-test=clicks]")).toHaveText("1");

  // The way out is the one the page offered.
  await page.locator("[data-test=dead-reload]").click();
  await expect(page.locator("[data-test=dead-banner]")).toHaveCount(0);
  await page.locator("[data-test=still-works]").click();
  await expect(page.locator("[data-test=clicks]")).toHaveText("1");
});

test("listening alone does not suppress the reload", async ({ page }) => {
  // The demo listens on every page but cancels only when the URL says to, which is exactly the
  // distinction under test: an application that forwards errors to telemetry still gets recovery.
  await page.goto("/errors");

  await page.locator("[data-test=still-works]").click();
  await expect(page.locator("[data-test=clicks]")).toHaveText("1");

  await page.locator("[data-test=fail-view]").click();

  await expect(page.locator("[data-test=clicks]")).toHaveText("0", { timeout: 10_000 });
  await expect(page.locator("[data-test=dead-banner]")).toHaveCount(0);
});

/**
 * Client components: an element that the composition creates and then stops owning.
 *
 * The server sends props down and receives events up, and what's drawn in between belongs to the
 * browser. These tests cover both directions and the teardown, which is the part that leaks if it's
 * wrong.
 */

test("a client component renders what the server cannot", async ({ page }) => {
  await page.goto("/about");

  // Bars exist only because JavaScript drew them. The served markup is an empty element.
  await expect(page.locator("[data-test=sparkline] .bar")).toHaveCount(5);
  await expect(page.locator("[data-test=sparkline-values]")).toHaveText("3,7,4,9,6");
});

test("new props from the server reach the component", async ({ page }) => {
  await page.goto("/about");
  await expect(page.locator("[data-test=sparkline] .bar")).toHaveCount(5);

  const before = await page.locator("[data-test=sparkline] .bar").first().evaluate((b) => b.style.height);
  await page.locator("[data-test=sparkline-shuffle]").click();

  await expect(page.locator("[data-test=sparkline-values]")).not.toHaveText("3,7,4,9,6");
  const after = await page.locator("[data-test=sparkline] .bar").first().evaluate((b) => b.style.height);
  expect(after).not.toBe(before);
});

test("an event from the component reaches the server, which decides what it means", async ({ page }) => {
  await page.goto("/about");
  await expect(page.locator("[data-test=sparkline-values]")).toHaveText("3,7,4,9,6");

  // The component only reports which bar was clicked. The server changes the number, and it
  // comes back down as new props.
  await page.locator("[data-test=sparkline] .bar").first().click();

  await expect(page.locator("[data-test=sparkline-values]")).toHaveText("4,7,4,9,6");
  await expect(page.locator("[data-test=sparkline] .bar")).toHaveCount(5);
});

test("a component is torn down when the server removes it", async ({ page }) => {
  const warnings: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "warning") warnings.push(message.text());
  });

  await page.goto("/about");
  await expect(page.locator("[data-test=sparkline] .bar")).toHaveCount(5);

  // Navigating away drops the whole view, which must take the component with it, instead of
  // leaving it attached to a detached element.
  await page.locator("nav a", { hasText: "Todos" }).click();
  await expect(page).toHaveURL("/");
  await expect(page.locator("[data-test=sparkline]")).toHaveCount(0);

  // And back again, mounted anew from props the server still holds.
  await page.locator("nav a", { hasText: "About" }).click();
  await expect(page.locator("[data-test=sparkline] .bar")).toHaveCount(5);

  // Mounted twice and torn down once, and the second mount is still live. The counts balance,
  // which is what "no leak" means here.
  const counts = await page.evaluate(() => ({
    mounts: (window as unknown as { sparklineMounts: number }).sparklineMounts,
    unmounts: (window as unknown as { sparklineUnmounts: number }).sparklineUnmounts,
  }));
  expect(counts).toEqual({ mounts: 2, unmounts: 1 });
  expect(warnings.filter((w) => w.includes("component"))).toEqual([]);
});

/**
 * Adoption: keeping the server-rendered markup instead of receiving the tree a second time.
 *
 * The DOM that the browser parsed and painted is what's under test, so these tests check node
 * identity instead of content. An assertion only on text would pass just as well against a page
 * that was thrown away and rebuilt.
 */

test("the server-rendered DOM is kept rather than rebuilt", async ({ page }) => {
  await page.goto("/");

  // Mark the nodes immediately, before the socket has had time to deliver anything. A reset would
  // replace every node and take the markers with it, and adoption leaves these exact objects in
  // place. Tag and count in one pass, so a rebuild that lands between two calls can't be mistaken
  // for a page that never had any nodes to tag.
  const before = await page.evaluate(() => {
    const nodes = document.querySelectorAll("#jetlin-root *");
    nodes.forEach((node, index) => ((node as HTMLElement).dataset.survivor = String(index)));
    return nodes.length;
  });
  expect(before).toBeGreaterThan(10);

  // A server-pushed tick can arrive only after the connection is established and its opening
  // message applied, so this proves the socket is live, instead of guessing at timing.
  await expect(page.locator("[data-test=ticks]")).toHaveText("1", { timeout: 8000 });

  const after = await page.evaluate(() => document.querySelectorAll("[data-survivor]").length);
  expect(after).toBe(before);
});

test("changes made before the socket connected are caught up", async ({ page }) => {
  await page.goto("/");
  // The clock starts ticking when the page is rendered, not when the socket opens, so the markup
  // the browser holds is already behind when it connects. Adoption keeps that markup, which works
  // only if the difference arrives as an ordinary patch.
  await expect(page.locator("[data-test=ticks]")).toHaveText("2", { timeout: 8000 });
});

test("adoption is silent when it succeeds", async ({ page }) => {
  const warnings: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "warning") warnings.push(message.text());
  });

  await page.goto("/shapes");
  await expect(page.locator("[data-test=adjacent]")).toHaveText("alphabeta");

  expect(warnings.filter((w) => w.includes("adopt"))).toEqual([]);
});

test("awkward markup shapes update the right node after adoption", async ({ page }) => {
  await page.goto("/shapes");

  // Two text nodes that the HTML parser would have merged into one.
  const adjacent = page.locator("[data-test=adjacent]");
  await expect(adjacent).toHaveText("alphabeta");
  await page.locator("[data-test=edit-first]").click();
  await expect(adjacent).toHaveText("ALPHAbeta");
  await page.locator("[data-test=edit-second]").click();
  await expect(adjacent).toHaveText("ALPHABETA");

  // A text node that rendered to nothing at all, then gained content.
  const empty = page.locator("[data-test=empty]");
  await expect(empty).toHaveText("[]");
  await page.locator("[data-test=fill]").click();
  await expect(empty).toHaveText("[filled]");

  // Text on either side of an element, where a miscounted index would put the update in the wrong place.
  await expect(page.locator("[data-test=interleaved]")).toHaveText("before ALPHA after");

  // Markup the composition doesn't own: replaced whole, never walked into.
  const raw = page.locator("[data-test=raw]");
  await expect(raw.locator("b")).toHaveText("bold");
  await page.locator("[data-test=swap-raw]").click();
  await expect(raw.locator("i")).toHaveText("italic");
});

test("markup that cannot be adopted falls back to a full render", async ({ page }) => {
  const warnings: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "warning") warnings.push(message.text());
  });

  // Remove a marker on the way through, as a rewriting proxy might. The client should notice that
  // the markup and the IDs it was given disagree, and ask for the tree instead of guessing.
  await page.route(
    (url) => url.pathname === "/shapes",
    async (route) => {
      const response = await route.fetch();
      const body = (await response.text()).replace(/ data-jl-t="[^"]*"/, "");
      await route.fulfill({ response, body });
    },
  );

  await page.goto("/shapes");

  expect(warnings.some((w) => w.includes("could not adopt"))).toBe(true);
  // And the page works fully, because a full render is what the client falls back to.
  await expect(page.locator("[data-test=adjacent]")).toHaveText("alphabeta");
  await page.locator("[data-test=edit-first]").click();
  await expect(page.locator("[data-test=adjacent]")).toHaveText("ALPHAbeta");
});

/**
 * SVG: the other language that a browser parses, and the one where a mistake makes no noise.
 *
 * `createElement("circle")` isn't an error. It's an HTMLUnknownElement: no warning, no pixels, just
 * a chart-shaped hole. So these tests check the namespace itself as well as what ends up on screen,
 * on both paths a node can arrive by: parsed from the markup the server served, and built by the
 * client from an insert op.
 */

const SVG_NS = "http://www.w3.org/2000/svg";
const HTML_NS = "http://www.w3.org/1999/xhtml";

test("the drawing is in the served markup, before any script runs", async ({ page }) => {
  await page.route("**/jetlin.js", (route) => route.abort());
  await page.goto("/shapes");

  await expect(page.locator("[data-test=chart] circle")).toHaveCount(5);
  // The parser switched language at <svg> by itself, and back again inside <foreignObject>.
  const languages = await page.evaluate(() =>
    Array.from(document.querySelectorAll("[data-test=chart] *")).map((n) => n.namespaceURI),
  );
  expect(new Set(languages)).toEqual(new Set([SVG_NS, HTML_NS]));
});

test("a drawing the client took up is real SVG and takes up space", async ({ page }) => {
  await page.goto("/shapes");

  const languages = await page.evaluate(() =>
    Array.from(document.querySelectorAll("[data-test=chart], [data-test=chart-line]")).map(
      (n) => n.namespaceURI,
    ),
  );
  expect(languages).toEqual([SVG_NS, SVG_NS]);

  // An unknown element would be here too, and would be nothing. A drawing has a box.
  const box = await page.locator("[data-test=chart]").boundingBox();
  expect(box!.width).toBeGreaterThan(100);
  expect(box!.height).toBeGreaterThan(20);

  // The attribute's case survived the serializer and the parser. "viewbox" is a different
  // attribute, and reading it back through the SVG DOM proves that the browser understood this one.
  const scaled = await page.evaluate(
    () => (document.querySelector("[data-test=chart]") as SVGSVGElement).viewBox.baseVal.width,
  );
  expect(scaled).toBe(220);
});

test("choosing a series redraws the line without rebuilding it", async ({ page }) => {
  await page.goto("/shapes");
  const line = page.locator("[data-test=chart-line]");
  const before = await line.getAttribute("points");

  // Mark the element itself. A rebuilt drawing would take the marker with it.
  await page.evaluate(() => {
    (document.querySelector("[data-test=chart-line]") as SVGElement).dataset.marker = "kept";
  });

  await page.locator("[data-test=chart-series]").selectOption("fuel");
  await expect(page.locator("[data-test=chart-values]")).toHaveText("11,7,13,6,9");
  await expect(line).not.toHaveAttribute("points", before!);
  await expect(line).toHaveAttribute("data-marker", "kept");
});

test("a shape the client builds is created in the SVG language too", async ({ page }) => {
  await page.goto("/shapes");
  const points = page.locator("[data-test=chart] circle");
  await expect(points).toHaveCount(5);

  await page.evaluate(() => {
    document
      .querySelectorAll("[data-test=chart] circle")
      .forEach((circle) => ((circle as SVGElement).dataset.served = "1"));
  });

  await page.locator("[data-test=chart-add]").click();
  await expect(points).toHaveCount(6);

  // The one without the marker never went near the HTML parser. The client made it from an op.
  const built = await page.evaluate(() =>
    Array.from(document.querySelectorAll("[data-test=chart] circle"))
      .filter((circle) => !(circle as SVGElement).dataset.served)
      .map((circle) => circle.namespaceURI),
  );
  expect(built).toEqual([SVG_NS]);
});

test("a foreign object hands the browser back to HTML mid-drawing", async ({ page }) => {
  await page.goto("/shapes");

  const caption = page.locator("[data-test=chart-caption]");
  await expect(caption).toContainText("HTML again");

  const languages = await page.evaluate(() => {
    const node = document.querySelector("[data-test=chart-caption]")!;
    return [node.namespaceURI, node.parentElement!.namespaceURI];
  });
  expect(languages).toEqual([HTML_NS, SVG_NS]);
});

/**
 * State that outlives a navigation.
 *
 * Three lifetimes have to be distinguishable, and the browser is where the difference shows. State
 * in the chrome lasts as long as the session, a view's saved state comes back when the view does,
 * and a view's remembered state doesn't. A headless test can't cover the back button, because the
 * browser's own history drives the session, instead of the session driving it.
 */

test("state in the chrome survives navigating away and back", async ({ page }) => {
  await page.locator("[data-test=filter]").fill("architecture");
  // The list narrowing shows that the server has the filter, not only that the input holds text.
  await expect(page.locator("[data-test=todo]")).toHaveCount(1);

  await page.locator("[data-test=todo] .todo-text").first().click();
  await expect(page).toHaveURL(/\/todo\/\d+$/);
  // The filter is composed above the route, so it's still there on a page that knows nothing about it.
  await expect(page.locator("[data-test=filter]")).toHaveValue("architecture");

  await page.goBack();
  await expect(page).toHaveURL("/");
  await expect(page.locator("[data-test=filter]")).toHaveValue("architecture");
  await expect(page.locator("[data-test=todo]")).toHaveCount(1);
});

test("a half-typed todo comes back when its page does", async ({ page }) => {
  await page.locator("[data-test=draft]").fill("half-typed todo");
  // Add becomes enabled once the server has a valid draft, so this waits for the round trip.
  await expect(page.locator("[data-test=add]")).toBeEnabled();

  await page.locator('a[href="/about"]').click();
  await expect(page).toHaveURL("/about");
  await expect(page.locator("[data-test=draft]")).toHaveCount(0);

  await page.goBack();
  await expect(page).toHaveURL("/");
  // The view was torn down and rebuilt, and what it declared as saved was handed back to it.
  await expect(page.locator("[data-test=draft]")).toHaveValue("half-typed todo");
});
