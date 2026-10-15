// Groups an esbuild metafile's output bytes by the Kotlin module they came from, so you can see
// whether the size is the client itself, the Kotlin stdlib, or kotlinx.serialization.
// Usage: node attribute.mjs <metafile.json>
import { readFileSync } from "node:fs";

const meta = JSON.parse(readFileSync(process.argv[2], "utf8"));
const totals = new Map();
for (const output of Object.values(meta.outputs)) {
  for (const [input, { bytesInOutput }] of Object.entries(output.inputs)) {
    const name = input.split("/").pop();
    totals.set(name, (totals.get(name) ?? 0) + bytesInOutput);
  }
}
const sum = [...totals.values()].reduce((a, b) => a + b, 0);
for (const [name, bytes] of [...totals].sort((a, b) => b[1] - a[1])) {
  if (bytes === 0) continue;
  console.log(`${String(bytes).padStart(8)}  ${((100 * bytes) / sum).toFixed(1).padStart(5)}%  ${name}`);
}
