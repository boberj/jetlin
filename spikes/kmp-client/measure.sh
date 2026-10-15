#!/usr/bin/env bash
# Builds every variant, minifies it the same way the TypeScript client is minified, and prints the
# sizes side by side. The TypeScript client is rebuilt from source too, so all rows use one esbuild.
#
# Needs `npm --prefix ../../jetlin-client install` first, for esbuild.
set -euo pipefail
cd "$(dirname "$0")"

esbuild=../../jetlin-client/node_modules/.bin/esbuild
# The exact flags from jetlin-client/package.json, minus the output path.
iife=(--bundle --format=iife --minify --target=es2020 --log-level=warning)

../../gradlew -q --console=plain \
  :dynamic:compileProductionExecutableKotlinJs \
  :typed:compileProductionExecutableKotlinJs \
  :typed:compileProductionExecutableKotlinWasmJsOptimize

rm -rf dist
mkdir -p dist

"$esbuild" ../../jetlin-client/src/jetlin.ts "${iife[@]}" --outfile=dist/jetlin-ts.js

for variant in dynamic typed; do
  "$esbuild" "$variant/build/compileSync/js/main/productionExecutable/kotlin/kmp-client-spike-$variant.mjs" \
    "${iife[@]}" --metafile="dist/jetlin-kjs-$variant.meta.json" --outfile="dist/jetlin-kjs-$variant.js"
done

# The Wasm glue uses top-level await, so it has to stay an ES module rather than an IIFE. It also
# carries loading code for Node and Deno, which the browser never runs, but which esbuild can't
# drop because the choice is made at runtime. Those imports are marked external so it doesn't try
# to resolve them.
wasm=typed/build/compileSync/wasmJs/main/productionExecutable/optimized
cp "$wasm/kmp-client-spike-typed.wasm" dist/
"$esbuild" "$wasm/kmp-client-spike-typed.mjs" --bundle --format=esm --minify --target=es2022 \
  --external:node:* --external:https://* --log-level=warning --outfile=dist/jetlin-wasm.mjs
"$esbuild" wasm-loader.js --minify --target=es2020 --log-level=warning --outfile=dist/jetlin-wasm-loader.js

echo
node sizes.mjs \
  "TypeScript (current)=dist/jetlin-ts.js" \
  "Kotlin/JS, dynamic=dist/jetlin-kjs-dynamic.js" \
  "Kotlin/JS, shared protocol=dist/jetlin-kjs-typed.js" \
  "Kotlin/Wasm, shared protocol (wasm)=dist/kmp-client-spike-typed.wasm" \
  "Kotlin/Wasm, shared protocol (all files)=dist/kmp-client-spike-typed.wasm+dist/jetlin-wasm.mjs+dist/jetlin-wasm-loader.js"

for variant in dynamic typed; do
  echo
  echo "Where the bytes of Kotlin/JS, $variant come from:"
  node attribute.mjs "dist/jetlin-kjs-$variant.meta.json"
done
