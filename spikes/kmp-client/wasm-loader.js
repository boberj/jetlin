// The classic script that stands in for jetlin.js when the client is the Kotlin/Wasm build.
//
// The page calls `Jetlin.clientComponent(...)` and `Jetlin.connect(...)` synchronously, right after
// this script runs. A Wasm module can't be ready by then: it has to be fetched, compiled and
// instantiated, all asynchronously, and Kotlin's `main` installs the real `window.Jetlin` only at the
// end of that. So this installs a placeholder that records the calls, loads the module, and replays
// them once the real API exists.
//
// It's an extra file and an extra round trip that the TypeScript client doesn't need, and any event
// the user fires before the module finishes loading has no listener yet.
(() => {
  // `currentScript` is only set while this script is executing, so read it now.
  const base = document.currentScript.src.replace(/[^/]*$/, "");
  const components = [];
  const connections = [];

  window.Jetlin = {
    clientComponent(name, factory) {
      components.push([name, factory]);
    },
    connect(options) {
      // The page keeps whatever `connect` returns, so hand back an object now and wire it to the
      // real connection later.
      const handle = {
        real: null,
        disconnect() {
          this.real?.disconnect();
        },
        reload() {
          location.reload();
        },
      };
      connections.push([options, handle]);
      return handle;
    },
  };

  import(`${base}jetlin-wasm.mjs`).then(() => {
    // The module's `main` has replaced `window.Jetlin` with the real API by the time it resolves.
    const real = window.Jetlin;
    for (const [name, factory] of components) real.clientComponent(name, factory);
    for (const [options, handle] of connections) handle.real = real.connect(options);
  });
})();
