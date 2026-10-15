// Prints the raw, gzip and brotli size of each file given, plus per-variant totals, as a Markdown
// table. Compressed sizes are what a browser actually downloads, so they're the ones to compare.
// Usage: node sizes.mjs <label>=<file>[+<file>...] ...
import { readFileSync } from "node:fs";
import { brotliCompressSync, gzipSync, constants } from "node:zlib";

const row = (cells) => `| ${cells.join(" | ")} |`;
console.log(row(["variant", "raw", "gzip -9", "brotli -11"]));
console.log(row(["---", "---:", "---:", "---:"]));
for (const arg of process.argv.slice(2)) {
  const [label, files] = arg.split("=");
  let raw = 0, gz = 0, br = 0;
  for (const file of files.split("+")) {
    const bytes = readFileSync(file);
    raw += bytes.length;
    gz += gzipSync(bytes, { level: 9 }).length;
    br += brotliCompressSync(bytes, { params: { [constants.BROTLI_PARAM_QUALITY]: 11 } }).length;
  }
  const kb = (n) => `${(n / 1024).toFixed(1)} KB`;
  console.log(row([label, kb(raw), kb(gz), kb(br)]));
}
