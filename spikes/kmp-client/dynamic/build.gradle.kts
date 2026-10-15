plugins {
    kotlin("multiplatform")
}

kotlin {
    js {
        browser()
        useEsModules()
        binaries.executable()
    }
}
