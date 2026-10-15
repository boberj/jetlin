import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    js {
        browser()
        // Emit ES modules, so esbuild can bundle them into one IIFE exactly as it does the
        // TypeScript client. Both outputs then go through the same minifier.
        useEsModules()
        binaries.executable()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    // `JsAny`, `js("...")` and the rest of the interop shared by JS and Wasm are still marked
    // experimental, and the client can't be written without them.
    compilerOptions {
        optIn.add("kotlin.js.ExperimentalWasmJsInterop")
    }

    sourceSets {
        // `webMain` is shared by `js` and `wasmJs`. The client lives entirely here.
        webMain {
            // Compile the real protocol sources rather than a copy. If jetlin-protocol became a
            // multiplatform module, this is what its `commonMain` would contain.
            kotlin.srcDir("../../../jetlin-protocol/src/main/kotlin")
            dependencies {
                implementation(libs.serialization.json)
                implementation("org.jetbrains.kotlinx:kotlinx-browser:0.5.0")
            }
        }
    }
}
