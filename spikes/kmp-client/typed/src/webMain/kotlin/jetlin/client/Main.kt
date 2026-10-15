package jetlin.client

/**
 * Installs `window.Jetlin` when the module loads.
 *
 * On Kotlin/JS, `main` runs synchronously while the script executes, so the page's next inline
 * script can call `Jetlin.connect` right away, exactly as with the TypeScript client. On
 * Kotlin/Wasm, `main` runs only after the module has been fetched and instantiated, which is
 * asynchronous. The Wasm build therefore needs a small JS loader in front of it. See `wasm-loader.js`.
 */
fun main() {
    installGlobal(
        connect = { options -> Jetlin(options).handle },
        clientComponent = { name, factory -> components[name] = factory },
    )
}
