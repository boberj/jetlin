package jetlin.samples.vessels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import jetlin.html.Div
import jetlin.html.H1
import jetlin.html.Link
import jetlin.html.P
import jetlin.html.Span
import jetlin.html.Text
import jetlin.html.pathParam
import kotlinx.coroutines.delay

/**
 * One vessel, in detail.
 *
 * Two kinds of data meet here, and telling them apart is the point. [VesselDetail] is looked up once
 * behind a delay, so the page opens through a real loading state. [Telemetry] moves on its own, once
 * a second, with no client event behind it — the case the fleet list cannot make, and the one a
 * framework that only reacts to input could not serve at all.
 */
@Composable
fun VesselPage() {
    val id = pathParam("vesselId")
    val vessel = FleetStore.find(id)

    if (vessel == null) {
        Div({ classes("container mx-auto px-4 py-6") }) {
            BackToFleet()
            P({ classes("mt-6 text-muted-foreground"); testTag("missing") }) {
                Text("No vessel with id $id.")
            }
        }
        return
    }

    var detail: VesselDetail? by remember(vessel.id) { mutableStateOf(null) }
    LaunchedEffect(vessel.id) { detail = FleetStore.detail(vessel) }

    // The ticker runs only while this page is open, and stops when it is navigated away from,
    // because the effect is disposed with the view.
    val telemetry = FleetStore.telemetry(vessel)
    LaunchedEffect(vessel.id) {
        while (true) {
            delay(1000)
            telemetry.tick()
        }
    }

    Div({ classes("container mx-auto px-4 py-6") }) {
        BackToFleet()
        VesselHeader(vessel)

        val loaded = detail
        if (loaded == null) {
            Div({ classes("mt-6 rounded-xl border border-border bg-card p-12 text-center"); testTag("loading") }) {
                Span({ classes("text-sm text-muted-foreground") }) { Text("Loading vessel…") }
            }
        } else {
            VesselBlocks(vessel, loaded, telemetry)
        }
    }
}

@Composable
private fun BackToFleet() {
    Link("/", {
        classes("inline-flex items-center gap-2 text-sm text-muted-foreground hover:text-foreground")
        testTag("back")
    }) {
        Icon(Icon.ARROW_LEFT, "h-4 w-4")
        Text("Back to Fleet")
    }
}

@Composable
private fun VesselHeader(vessel: Vessel) {
    val device = FleetStore.deviceStatus(vessel)
    Div({ classes("mt-4 flex items-start justify-between gap-4") }) {
        Div({ classes("flex items-center gap-3") }) {
            Div({ classes("flex h-12 w-12 items-center justify-center rounded-lg bg-accent") }) {
                Icon(Icon.SHIP, "h-6 w-6 text-slate-700")
            }
            Div {
                Div({ classes("flex items-center gap-3") }) {
                    H1({ classes("text-2xl font-bold tracking-tight"); testTag("vessel-name") }) {
                        Text(vessel.name)
                    }
                    StatusBadge(device)
                }
                Div({ classes("mt-1 flex items-center gap-4 text-sm") }) {
                    ExternalLink("View in InControl")
                    ExternalLink("Remote Web Admin")
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(device: DeviceStatus?) {
    val classes = when {
        device == null -> "inline-flex rounded-md bg-gray-500 px-2 py-0.5 text-xs font-medium text-white"
        device.online -> "inline-flex rounded-md bg-green-600 px-2 py-0.5 text-xs font-medium text-white"
        else -> "inline-flex rounded-md bg-red-600 px-2 py-0.5 text-xs font-medium text-white"
    }
    Span({ classes(classes); testTag("vessel-status") }) {
        Text(if (device == null) "Unknown" else if (device.online) "Online" else "Offline")
    }
}

@Composable
private fun ExternalLink(label: String) {
    Span({ classes("inline-flex items-center gap-1 text-blue-600 hover:underline") }) {
        Text(label)
        Icon(Icon.EXTERNAL_LINK, "h-3 w-3")
    }
}

/** The nine blocks. Filled in next; the grid and the first block are here to prove the shape. */
@Composable
private fun VesselBlocks(vessel: Vessel, detail: VesselDetail, telemetry: Telemetry) {
    Div({ classes("mt-6 grid grid-cols-12 gap-4") }) {
        Block("Status", "col-span-12 lg:col-span-8", "block-status") {
            Div({ classes("grid grid-cols-1 gap-x-8 gap-y-1 md:grid-cols-2") }) {
                Div { detail.identity.forEach { (label, value) -> LabelValue(label, value) } }
                Div { detail.service.forEach { (label, value) -> LabelValue(label, value) } }
            }
            Div({ classes("mt-4 grid grid-cols-1 gap-4 md:grid-cols-2") }) {
                Gauge(Icon.CPU, "CPU load", telemetry.cpuPercent)
                Gauge(Icon.MEMORY_STICK, "Memory", telemetry.memoryPercent)
            }
            Div({ classes("mt-4 flex flex-wrap gap-1.5") }) {
                detail.tags.forEach { tag ->
                    Span({ classes("rounded border border-border bg-background px-2 py-0.5 text-xs text-muted-foreground") }) {
                        Text(tag)
                    }
                }
            }
        }
    }
}

/** A card with the small uppercase heading the original gives every block. */
@Composable
internal fun Block(title: String, span: String, tag: String, content: @Composable () -> Unit) {
    Div({ classes("rounded-xl border border-border bg-card p-4 shadow-sm $span"); testTag(tag) }) {
        Div({ classes("mb-3 text-xs font-medium uppercase tracking-wider text-muted-foreground") }) {
            Text(title)
        }
        content()
    }
}

@Composable
internal fun LabelValue(label: String, value: String) {
    Div({ classes("flex items-baseline justify-between gap-4 py-0.5 text-sm") }) {
        Span({ classes("shrink-0 text-muted-foreground") }) { Text(label) }
        Span({ classes("truncate text-right font-medium text-foreground") }) { Text(value) }
    }
}

/** A labelled percentage bar. Live, so this is one of the things that moves on its own. */
@Composable
internal fun Gauge(icon: Icon, label: String, percent: Int) {
    Div {
        Div({ classes("flex items-center justify-between text-sm") }) {
            Span({ classes("flex items-center gap-1.5 text-muted-foreground") }) {
                Icon(icon, "h-3.5 w-3.5")
                Text(label)
            }
            Span({ classes("font-medium"); testTag("gauge-$label") }) { Text("$percent%") }
        }
        Div({ classes("mt-1 h-1.5 w-full rounded-full bg-gray-200") }) {
            Div({
                classes("h-1.5 rounded-full bg-slate-800")
                style("width: $percent%")
            })
        }
    }
}
