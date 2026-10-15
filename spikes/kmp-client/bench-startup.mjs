// Measures how long each client takes to come alive: from starting the navigation to the client
// sending its `hello` frame. Everything is served from localhost, so download time is ~0 and the
// difference between variants is parse, compile and startup cost.
//
// The CPU is throttled 4x, roughly a mid-range phone, because that's where script cost shows.
//
// Needs the demo on :8080, and resolves Playwright from the e2e package.
// Usage: node bench-startup.mjs [runs=15]
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(here, "../../e2e/package.json"));
const { chromium } = require("playwright-core");

const runs = Number(process.argv[2] ?? 15);
const variants = ["ts", "kjs-dynamic", "kjs-typed", "wasm"];
const browser = await chromium.launch({ executablePath: process.env.CHROMIUM_PATH ?? "/opt/pw-browsers/chromium" });

async function timeOnce(url) {
  const context = await browser.newContext();
  const page = await context.newPage();
  const cdp = await context.newCDPSession(page);
  await cdp.send("Network.setCacheDisabled", { cacheDisabled: true });
  await cdp.send("Emulation.setCPUThrottlingRate", { rate: 4 });
  const hello = new Promise((resolve) =>
    page.on("websocket", (ws) => ws.on("framesent", (frame) => {
      if (String(frame.payload).includes('"hello"')) resolve(performance.now());
    })),
  );
  const start = performance.now();
  await page.goto(url);
  const elapsed = (await hello) - start;
  await context.close();
  return elapsed;
}

const median = (xs) => [...xs].sort((a, b) => a - b)[Math.floor(xs.length / 2)];

console.log("| variant | median ms to hello (4x CPU throttle) | min | max |");
console.log("| --- | ---: | ---: | ---: |");
for (const [i, variant] of variants.entries()) {
  const port = 8090 + i;
  const proxy = spawn("node", [join(here, "swap-proxy.mjs"), variant, String(port), "8080"], { stdio: "ignore" });
  await new Promise((resolve) => setTimeout(resolve, 500));
  const url = `http://localhost:${port}/`;
  await timeOnce(url); // warm up the server and the proxy
  const times = [];
  for (let run = 0; run < runs; run++) times.push(await timeOnce(url));
  proxy.kill();
  const f = (n) => n.toFixed(0);
  console.log(`| ${variant} | ${f(median(times))} | ${f(Math.min(...times))} | ${f(Math.max(...times))} |`);
}
await browser.close();
