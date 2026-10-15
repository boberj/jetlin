// Runs the e2e suite against a different client without touching the server or the tests.
//
// It listens on one port and forwards everything, WebSocket upgrades included, to the demo, except
// requests for the client script, which it answers from `dist/`. Point Playwright at it with
// JETLIN_URL.
//
// Usage: node swap-proxy.mjs <ts|kjs-dynamic|kjs-typed|wasm> [listenPort=8081] [demoPort=8080]
import { readFileSync } from "node:fs";
import http from "node:http";
import net from "node:net";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const [variant, listenPort = "8081", demoPort = "8080"] = process.argv.slice(2);
const dist = join(dirname(fileURLToPath(import.meta.url)), "dist");

// What to serve under /jetlin/ for each variant: request path → [file in dist, content type].
const js = "text/javascript";
const variants = {
  ts: { "jetlin.js": ["jetlin-ts.js", js] },
  "kjs-dynamic": { "jetlin.js": ["jetlin-kjs-dynamic.js", js] },
  "kjs-typed": { "jetlin.js": ["jetlin-kjs-typed.js", js] },
  wasm: {
    "jetlin.js": ["jetlin-wasm-loader.js", js],
    "jetlin-wasm.mjs": ["jetlin-wasm.mjs", js],
    // `instantiateStreaming` refuses a response that isn't served as application/wasm.
    "kmp-client-spike-typed.wasm": ["kmp-client-spike-typed.wasm", "application/wasm"],
  },
};
const files = variants[variant];
if (!files) throw new Error(`unknown variant ${variant}; expected one of ${Object.keys(variants)}`);

const server = http.createServer((req, res) => {
  const name = req.url.startsWith("/jetlin/") ? req.url.slice("/jetlin/".length) : null;
  const file = name && files[name];
  if (file) {
    res.writeHead(200, { "content-type": file[1] });
    res.end(readFileSync(join(dist, file[0])));
    return;
  }
  const upstream = http.request(
    { host: "127.0.0.1", port: demoPort, path: req.url, method: req.method, headers: req.headers },
    (response) => {
      res.writeHead(response.statusCode, response.headers);
      response.pipe(res);
    },
  );
  upstream.on("error", () => res.destroy());
  req.pipe(upstream);
});

// A WebSocket starts as an HTTP request and then becomes a raw byte stream, so replay the request
// head to the demo and splice the two sockets together.
server.on("upgrade", (req, socket, head) => {
  const upstream = net.connect(Number(demoPort), "127.0.0.1", () => {
    const lines = [`${req.method} ${req.url} HTTP/1.1`];
    for (let i = 0; i < req.rawHeaders.length; i += 2) lines.push(`${req.rawHeaders[i]}: ${req.rawHeaders[i + 1]}`);
    upstream.write(lines.join("\r\n") + "\r\n\r\n");
    upstream.write(head);
    socket.pipe(upstream).pipe(socket);
  });
  const close = () => {
    socket.destroy();
    upstream.destroy();
  };
  upstream.on("error", close);
  socket.on("error", close);
});

server.listen(Number(listenPort), () => console.log(`serving the ${variant} client on :${listenPort}`));
