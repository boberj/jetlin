package jetlin.samples.vessels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import jetlin.html.Div
import jetlin.html.Link
import jetlin.html.Nav
import jetlin.html.Span
import jetlin.html.Text
import jetlin.server.jetlin

/**
 * A replica of the fleet list and vessel detail pages of a real fleet-management dashboard, built to
 * find out what Jetlin is like to write a real application in.
 *
 * The original is a TanStack Start app: React 19 on the client, server functions for data, Tailwind
 * for styling, a virtualized table. It was designed with no reference to this framework, so whatever
 * it needs is what a dashboard needs, and wherever this got awkward is recorded in FINDINGS.md.
 *
 * Styling is Tailwind, as in the original, but compiled ahead of time — see src/main/css/app.css.
 */
fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(Netty, port = port) {
        routing {
            get("/vessels/app.css") {
                val css = checkNotNull(object {}.javaClass.getResource("/vessels/app.css")) {
                    "app.css is missing. Run `npm run build` in samples/vessels."
                }
                call.respondText(css.readText(), ContentType.Text.CSS)
            }
            get("/vessels/map.js") {
                val script = checkNotNull(object {}.javaClass.getResource("/vessels/map.js"))
                call.respondText(script.readText(), ContentType.Text.JavaScript)
            }
        }
        jetlin {
            // Written into the markup so browser tests have something real to select on. A shipping
            // application would leave this off, which is the default.
            exposeTestTags = true
            head = """
                <link rel="stylesheet" href="/vessels/app.css">
                <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.css">
            """.trimIndent()

            // Registered before the session connects, so the map's implementation is there when the
            // runtime takes up the markup it was served. Leaflet first: map.js needs it at load.
            clientSetup = """
                <script src="https://cdn.jsdelivr.net/npm/leaflet@1.9.4/dist/leaflet.js"></script>
                <script src="/vessels/map.js"></script>
            """.trimIndent()

            // Composed once for the session, above whichever page is current. That is what lets the
            // search box still hold its text after opening a vessel and coming back: a `remember`
            // in a view dies when the view is swapped, and this one does not.
            app { route ->
                val fleet = remember { FleetView() }
                CompositionLocalProvider(LocalFleetView provides fleet) {
                    Shell { route() }
                }
            }

            view("/", title = "Fleet · CYGNiFi") { VesselsPage() }
            view("/vessels/{vesselId}", title = "Vessel · CYGNiFi") { VesselPage() }
        }
    }.start(wait = true)
}

/**
 * What the operator has narrowed the fleet down to.
 *
 * Deliberately not in the URL. A sorted fleet is a link worth sending a colleague; a half-typed
 * search box is not, and putting it in the address bar would mean a history entry per keystroke.
 * Sort does live in the query string, for the opposite reason.
 */
internal class FleetView {
    var query: String by mutableStateOf("")
}

/**
 * Reads back what the container is holding.
 *
 * Declared by the application rather than the framework: `app { }` provides somewhere for session
 * state to live and says nothing about what it is. The default is a throwaway holder so that a page
 * composed on its own — as several tests do — reads an empty query instead of failing.
 */
internal val LocalFleetView: ProvidableCompositionLocal<FleetView> = compositionLocalOf { FleetView() }

/** The top bar, which is the same on every page and, being up here, is never rebuilt by one. */
@Composable
internal fun Shell(content: @Composable () -> Unit) {
    Div({ classes("min-h-screen bg-background") }) {
        Nav({
            classes("sticky top-0 z-50 w-full border-b border-border bg-card/80 backdrop-blur-md")
            testTag("top-nav")
        }) {
            Div({ classes("container mx-auto flex h-16 items-center px-4") }) {
                Link("/", { classes("flex items-center gap-2") }) {
                    Icon(Icon.WAVES, "h-8 w-8 text-slate-800")
                    Span({ classes("text-xl font-bold tracking-tight text-foreground") }) {
                        Text("CYGNiFi Fleet Portal Dashboard")
                    }
                }
                Div({ classes("ml-auto flex items-center gap-6") }) {
                    NavLink("/", Icon.SHIP, "Vessels")
                    NavLink("/map", Icon.MAP, "Map")
                    NavLink("/starlink", Icon.SATELLITE, "Starlink")
                    NavLink("/tickets", Icon.CLIPBOARD_LIST, "Tickets")
                    Div({ classes("ml-6 flex items-center gap-3 rounded-md px-2 py-1.5 outline-none hover:bg-accent") }) {
                        OrgAvatar("flex h-9 w-9 items-center justify-center rounded-md bg-blue-100", "h-5 w-5 text-blue-600")
                        Div({ classes("flex flex-col items-start leading-tight") }) {
                            Span({ classes("text-sm font-semibold text-foreground") }) { Text("Johan") }
                            Span({ classes("text-xs font-normal text-muted-foreground") }) {
                                Text(FleetStore.organizationName)
                            }
                        }
                        Icon(Icon.CHEVRON_DOWN, "h-4 w-4 text-muted-foreground")
                    }
                }
            }
        }
        content()
    }
}

@Composable
private fun NavLink(href: String, icon: Icon, label: String) {
    Link(href, {
        classes("flex items-center gap-1.5 text-sm font-medium text-muted-foreground hover:text-foreground transition-colors")
    }) {
        Icon(icon, "h-4 w-4")
        Text(label)
    }
}

/**
 * The organisation's avatar where it has no logo of its own: a ship icon in a tinted box, as the
 * original falls back to in both the nav bar and the fleet header — not an initials tile, which
 * nothing in the original renders.
 */
@Composable
internal fun OrgAvatar(boxClasses: String, iconClasses: String) {
    Div({ classes(boxClasses) }) { Icon(Icon.SHIP, iconClasses) }
}
