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
        }
        jetlin {
            // Written into the markup so browser tests have something real to select on. A shipping
            // application would leave this off, which is the default.
            exposeTestTags = true
            head = """<link rel="stylesheet" href="/vessels/app.css">"""

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
                    Div({ classes("ml-6 flex items-center gap-3 rounded-md px-2 py-1.5 hover:bg-accent") }) {
                        OrgTile()
                        Div({ classes("flex flex-col leading-tight") }) {
                            Span({ classes("text-sm font-medium text-foreground") }) { Text("Johan") }
                            Span({ classes("text-xs text-muted-foreground") }) {
                                Text(FleetStore.organizationName)
                            }
                        }
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

/** The organisation's initials, as the original renders them: a square tile of letters. */
@Composable
internal fun OrgTile(classes: String = "h-9 w-9 text-[10px]") {
    Div({
        classes("grid shrink-0 grid-cols-2 place-items-center rounded bg-blue-700 font-bold leading-none text-white $classes")
    }) {
        Span { Text("N") }
        Span { Text("O") }
        Span { Text("S") }
    }
}
