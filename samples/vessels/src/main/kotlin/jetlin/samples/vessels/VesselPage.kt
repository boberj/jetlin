package jetlin.samples.vessels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import jetlin.html.Button
import jetlin.html.ClientComponent
import jetlin.html.Div
import jetlin.html.H1
import jetlin.html.Link
import jetlin.html.P
import jetlin.html.Polyline
import jetlin.html.Rect
import jetlin.html.Span
import jetlin.html.Svg
import jetlin.html.Text
import jetlin.html.pathParam
import jetlin.runtime.rememberSaved
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One vessel, in detail: the original's nine blocks, at the positions its grid gives them.
 *
 * Three kinds of data meet here, and telling them apart is most of the point.
 *
 * [VesselDetail] is looked up once behind a delay, so the page opens through a real loading state.
 * [Telemetry] moves on its own once a second with no client event behind it — the case the fleet
 * list cannot make, and the one a framework that only reacts to input could not serve at all. And
 * the selected month is a `rememberSaved`, so leaving the page and coming back finds the tab where
 * it was left rather than back on the default.
 *
 * The drag-and-resize layout editor is deliberately not built. It is pointer-move state at 60 Hz,
 * which is `ClientComponent` territory, and saying so is more useful than a bad imitation.
 */
@Composable
fun VesselPage() {
    val id = pathParam("vesselId")
    val vessel = FleetStore.find(id)

    if (vessel == null) {
        Div({ classes("container mx-auto space-y-6 p-6") }) {
            BackToFleet()
            P({ classes("text-muted-foreground"); testTag("missing") }) {
                Text("No vessel with id $id.")
            }
        }
        return
    }

    var detail: VesselDetail? by remember(vessel.id) { mutableStateOf(null) }
    LaunchedEffect(vessel.id) { detail = FleetStore.detail(vessel) }

    val telemetry = FleetStore.telemetry(vessel)
    // Runs only while this page is composed, and stops when it is navigated away from, because the
    // effect is disposed with the view.
    LaunchedEffect(vessel.id) {
        while (true) {
            delay(1000)
            telemetry.tick()
        }
    }

    Div({ classes("container mx-auto space-y-6 p-6") }) {
        BackToFleet()
        VesselHeader(vessel)

        val loaded = detail
        if (loaded == null) {
            Div({
                classes("rounded-xl border border-border bg-card p-12 text-center")
                testTag("loading")
            }) {
                Span({ classes("text-sm text-muted-foreground") }) { Text("Loading vessel…") }
            }
        } else {
            Blocks(vessel, loaded, telemetry)
        }
    }
}

@Composable
private fun BackToFleet() {
    Link("/", {
        classes("inline-flex items-center text-sm font-medium text-muted-foreground transition-colors hover:text-primary")
        testTag("back")
    }) {
        Icon(Icon.ARROW_LEFT, "mr-2 h-4 w-4")
        Text("Back to Fleet")
    }
}

@Composable
private fun VesselHeader(vessel: Vessel) {
    val device = FleetStore.deviceStatus(vessel)
    Div({ classes("flex items-center gap-3") }) {
        Div({ classes("flex h-10 w-10 items-center justify-center rounded-xl bg-primary/10 text-primary") }) {
            Icon(Icon.SHIP, "h-5 w-5")
        }
        Div({ classes("flex-1") }) {
            Div({ classes("flex items-center gap-3") }) {
                H1({ classes("text-2xl font-bold tracking-tight text-foreground"); testTag("vessel-name") }) {
                    Text(vessel.name)
                }
                StatusBadge(device)
            }
            Div({ classes("mt-1 flex flex-wrap items-center gap-4") }) {
                AdminLink("View in InControl")
                AdminLink("Remote Web Admin")
            }
        }
        Div({ classes("flex items-center gap-2") }) {
            Div({ classes("flex h-9 items-center gap-2 rounded-lg border border-border px-3 text-muted-foreground transition-colors") }) {
                Icon(Icon.HEADSET, "h-4 w-4")
                Span({ classes("whitespace-nowrap text-sm font-medium") }) { Text("Start RS") }
                Div({ classes("inline-flex h-5 w-9 shrink-0 items-center rounded-full border-2 border-transparent bg-input-background") }) {
                    Div({ classes("h-4 w-4 rounded-full bg-background shadow-lg") })
                }
            }
            HeaderAction(Icon.NOTEBOOK_TEXT, "Notes", vessel.noteCount, "border-blue-200 bg-blue-100 text-blue-600 hover:bg-blue-200", "bg-blue-500")
            HeaderAction(Icon.CLIPBOARD_LIST, "Tickets", vessel.openTicketCount, "border-green-200 bg-green-100 text-green-600 hover:bg-green-200", "bg-red-500")
            PlainHeaderButton(Icon.SCROLL_TEXT, "Events")
            PlainHeaderButton(Icon.LAYOUT_GRID, "Edit layout")
        }
    }
}

@Composable
private fun StatusBadge(device: DeviceStatus?) {
    if (device == null) return
    if (device.online) {
        Badge("border-transparent bg-green-600 text-white", tag = "vessel-status") { Text("Online") }
    } else {
        Badge("border-transparent bg-secondary text-secondary-foreground", tag = "vessel-status") { Text("Offline") }
    }
}

@Composable
private fun AdminLink(label: String) {
    Span({ classes("inline-flex items-center gap-1 text-sm font-medium text-muted-foreground transition-colors hover:text-primary") }) {
        Text(label)
        Icon(Icon.EXTERNAL_LINK, "h-3.5 w-3.5")
    }
}

@Composable
private fun HeaderAction(icon: Icon, title: String, count: Int, activeClasses: String, badgeColour: String) {
    Div({ classes("relative") }) {
        Button({
            classes(
                if (count > 0) "relative flex h-9 w-9 items-center justify-center rounded-lg border transition-colors $activeClasses"
                else "relative flex h-9 w-9 items-center justify-center rounded-lg border border-border text-muted-foreground transition-colors hover:bg-accent hover:text-foreground",
            )
            attr("title", title)
        }) { Icon(icon, "h-4 w-4") }
        if (count > 0) {
            Span({
                classes(
                    "absolute -right-1 -top-1 flex h-4 min-w-[16px] items-center justify-center rounded-full px-1 text-[10px] font-semibold leading-none text-white tabular-nums $badgeColour",
                )
            }) { Text(if (count > 99) "99+" else count.toString()) }
        }
    }
}

@Composable
private fun PlainHeaderButton(icon: Icon, title: String) {
    Button({
        classes("relative flex h-9 w-9 items-center justify-center rounded-lg border border-border text-muted-foreground transition-colors hover:bg-accent hover:text-foreground")
        attr("title", title)
    }) { Icon(icon, "h-4 w-4") }
}

/**
 * The nine blocks, at the spans `vessel-layout.ts` gives them on its twelve-column grid.
 *
 * Static positions rather than a draggable grid: the same arrangement, without the part that is a
 * client component wearing a layout's clothes.
 */
@Composable
private fun Blocks(vessel: Vessel, detail: VesselDetail, telemetry: Telemetry) {
    Div({ classes("grid grid-cols-12 gap-4") }) {
        StatusBlock(detail, telemetry)
        NotesAndPortsBlock(detail)
        ThroughputBlock(detail, telemetry)
        MapBlock(detail)
        ConnectionsBlock(detail, telemetry)
        RoutingBlock(detail.routing)
        VlansBlock(detail.vlans)
        SpeedFusionBlock(detail)
        DataUsageBlock(vessel, detail)
    }
}

@Composable
private fun StatusBlock(detail: VesselDetail, telemetry: Telemetry) {
    Block("Status", "col-span-12 lg:col-span-8", "block-status") {
        Div({ classes("space-y-3") }) {
            Div({ classes("grid gap-x-10 gap-y-0.5 md:grid-cols-2") }) {
                Div({ classes("space-y-0.5") }) { detail.identity.forEach { (label, value) -> StatusMetricRow(label, value) } }
                Div({ classes("space-y-0.5") }) { detail.service.forEach { (label, value) -> StatusMetricRow(label, value) } }
            }
            Div({ classes("grid gap-4 border-t border-border pt-3 sm:grid-cols-2") }) {
                Gauge(Icon.CPU, "CPU load", telemetry.cpuPercent, "cpu")
                Gauge(Icon.MEMORY_STICK, "Memory", telemetry.memoryPercent, "memory")
            }
            Div({ classes("flex flex-wrap gap-1.5 border-t border-border pt-3") }) {
                detail.tags.forEach { tag ->
                    Badge("text-foreground border-border") { Text(tag) }
                }
            }
        }
    }
}

@Composable
private fun NotesAndPortsBlock(detail: VesselDetail) {
    Block("Notes & Ports", "col-span-12 lg:col-span-4", "block-notes") {
        Div({ classes("space-y-4") }) {
            Div {
                Div({ classes("mb-1 flex items-center justify-between gap-2") }) {
                    SubHeading("Note")
                    Span({ classes("flex items-center gap-1 rounded text-xs text-muted-foreground transition-colors hover:text-foreground") }) {
                        Icon(Icon.PENCIL, "h-3 w-3")
                        Text(if (detail.note == null) "Add" else "Edit")
                    }
                }
                if (detail.note == null) {
                    Div({ classes("rounded-md bg-muted/50 p-2 text-xs italic text-muted-foreground") }) { Text("No note") }
                } else {
                    Div({ classes("whitespace-pre-wrap rounded-md bg-muted/50 p-2 text-xs text-foreground") }) { Text(detail.note) }
                }
            }

            Div {
                Div({ classes("mb-1.5") }) { SubHeading("Ports") }
                PortChips(detail.ports)
            }

            Div {
                Div({ classes("mb-1") }) { SubHeading("Applied licenses") }
                Div({ classes("space-y-1") }) {
                    detail.licences.forEach { licence ->
                        Div({ classes("text-xs") }) {
                            Div({ classes("text-foreground") }) { Text(licence.name) }
                            Div({ classes("text-muted-foreground") }) {
                                Text("${licence.relative} (${licence.granted})")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PortChips(ports: List<Port>) {
    Div({ classes("flex flex-wrap gap-1.5") }) {
        ports.forEach { port ->
            Span({ classes(portChipClasses(port.state)) }) { Text(port.label) }
        }
    }
}

private fun portChipClasses(state: PortState): String = when (state) {
    PortState.LINK_UP -> "flex h-7 min-w-[28px] items-center justify-center rounded border border-green-600/40 bg-green-600/15 px-1 text-[11px] font-semibold text-green-700"
    PortState.ENABLED -> "flex h-7 min-w-[28px] items-center justify-center rounded border border-red-500/40 bg-red-500/10 px-1 text-[11px] font-semibold text-red-600"
    PortState.DISABLED -> "flex h-7 min-w-[28px] items-center justify-center rounded border border-border bg-muted px-1 text-[11px] font-semibold text-muted-foreground"
}

@Composable
private fun ThroughputBlock(detail: VesselDetail, telemetry: Telemetry) {
    Block("Speed & data transfer", "col-span-12 lg:col-span-4", "block-throughput") {
        Div({ classes("flex flex-col gap-5") }) {
            Div {
                Div({ classes("mb-1 flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1") }) {
                    Div {
                        Span({ classes("text-xs font-medium uppercase tracking-wider text-muted-foreground") }) { Text("Bandwidth") }
                        Span({ classes("ml-2 text-xs text-muted-foreground") }) { Text("last 10 min") }
                    }
                    Div({ classes("flex flex-wrap gap-x-4 gap-y-1 text-xs") }) {
                        Span({ classes("text-muted-foreground") }) { Text("Download ") }
                        Span({ classes("font-semibold tabular-nums text-foreground"); testTag("download") }) {
                            Text("${telemetry.downMbps} Mbps")
                        }
                        Span({ classes("text-muted-foreground") }) { Text("Upload ") }
                        Span({ classes("font-semibold tabular-nums text-foreground"); testTag("upload") }) {
                            Text("${telemetry.upMbps} Mbps")
                        }
                    }
                }
                BandwidthChart(telemetry)
            }

            Div {
                Div({ classes("mb-1 flex items-baseline justify-between") }) {
                    SubHeading("Data usage per hour")
                    Span({ classes("text-xs text-muted-foreground") }) { Text("last 3 days") }
                }
                HourlyChart(detail.hours)
                Legend()
            }
        }
    }
}

/**
 * The bandwidth trace, redrawn every second.
 *
 * One `points` attribute per update — the whole chart is a single `SetAttr` on a `<polyline>`,
 * which is what "a chart is elements, so it is patched like anything else" means in practice.
 */
@Composable
private fun BandwidthChart(telemetry: Telemetry) {
    val samples = telemetry.bandwidth
    val peak = max(12_000.0, samples.maxOf { it.downKbps }.toDouble())
    Div({ classes("mt-2 flex gap-2") }) {
        Div({ classes("flex w-12 shrink-0 flex-col justify-between py-1 text-right text-[10px] text-muted-foreground") }) {
            listOf(12, 9, 6, 3, 0).forEach { label -> Span { Text("$label Mbps") } }
        }
        Svg({
            classes("h-24 w-full")
            attr("viewBox", "0 0 240 96")
            attr("preserveAspectRatio", "none")
            testTag("bandwidth-chart")
        }) {
            listOf(0, 24, 48, 72, 96).forEach { y ->
                Rect({
                    attr("x", "0"); attr("y", y.toString())
                    attr("width", "240"); attr("height", "1")
                    attr("fill", "#f1f5f9")
                })
            }
            Polyline({
                attr("fill", "none")
                attr("stroke", "#0d9488")
                attr("stroke-width", "1.5")
                attr("stroke-linejoin", "round")
                attr("points", trace(samples.map { it.downKbps.toDouble() }, peak))
                testTag("bandwidth-line")
            })
            Polyline({
                attr("fill", "none")
                attr("stroke", "#f97316")
                attr("stroke-width", "1.5")
                attr("stroke-linejoin", "round")
                attr("points", trace(samples.map { it.upKbps.toDouble() }, peak))
            })
        }
    }
}

/** `x,y x,y …` across a 240×96 box, with y flipped because SVG counts downwards. */
private fun trace(values: List<Double>, peak: Double): String =
    values.mapIndexed { index, value ->
        val x = index * (240.0 / max(1, values.size - 1))
        val y = 96.0 - (value / peak).coerceIn(0.0, 1.0) * 96.0
        "%.1f,%.1f".format(x, y)
    }.joinToString(" ")

@Composable
private fun HourlyChart(hours: List<HourUsage>) {
    val peak = max(0.1, hours.maxOf { it.wanGb + it.cellularGb })
    Div({ classes("mt-2 flex gap-2") }) {
        Div({ classes("flex w-12 shrink-0 flex-col justify-between py-1 text-right text-[10px] text-muted-foreground") }) {
            listOf("2.6 GB", "2 GB", "1.3 GB", "0.7 GB", "0 GB").forEach { label -> Span { Text(label) } }
        }
        Svg({
            classes("h-24 w-full")
            attr("viewBox", "0 0 240 96")
            attr("preserveAspectRatio", "none")
            testTag("hourly-chart")
        }) {
            val width = 240.0 / hours.size
            hours.forEachIndexed { index, hour ->
                val wanHeight = (hour.wanGb / peak) * 96.0
                val cellHeight = (hour.cellularGb / peak) * 96.0
                Rect({
                    attr("x", "%.2f".format(index * width))
                    attr("y", "%.2f".format(96.0 - wanHeight - cellHeight))
                    attr("width", "%.2f".format(width * 0.7))
                    attr("height", "%.2f".format(cellHeight))
                    attr("fill", "#0d9488")
                })
                Rect({
                    attr("x", "%.2f".format(index * width))
                    attr("y", "%.2f".format(96.0 - wanHeight))
                    attr("width", "%.2f".format(width * 0.7))
                    attr("height", "%.2f".format(wanHeight))
                    attr("fill", "#f97316")
                })
            }
        }
    }
}

@Composable
private fun Legend() {
    Div({ classes("mt-2 flex items-center gap-4 text-xs text-muted-foreground") }) {
        Div({ classes("flex items-center gap-1.5") }) {
            Span({ classes("inline-block h-2 w-2 rounded-full bg-orange-500") })
            Text("WAN")
        }
        Div({ classes("flex items-center gap-1.5") }) {
            Span({ classes("inline-block h-2 w-2 rounded-full bg-teal-600") })
            Text("Cellular")
        }
    }
}

/**
 * The map, which is the one block the server cannot draw.
 *
 * Leaflet mutates its own DOM continuously — tiles arrive, layers pan — and nothing here patches
 * inside it. Props go down, a click comes back up, and the position it is given is the one the
 * server already holds, so a reconnect rebuilds the widget from state that never left the server.
 */
@Composable
private fun MapBlock(detail: VesselDetail) {
    Block("Map", "col-span-12 lg:col-span-8", "block-map", showHeader = false) {
        Div({ classes("relative min-h-[160px]") }) {
            ClientComponent(
                name = "vessel-map",
                props = buildJsonObject {
                    put("lat", detail.position.first)
                    put("lon", detail.position.second)
                    put("zoom", 6)
                },
                attrs = {
                    classes("isolate h-80 min-h-[160px] w-full overflow-hidden rounded-lg border border-border bg-muted")
                    testTag("map")
                },
                onEvent = { _, _ -> },
            )
            if (detail.geofences.isNotEmpty()) {
                Div({
                    classes(
                        "absolute left-14 top-3 z-[500] flex flex-wrap items-center gap-1.5 rounded-md border border-border bg-card/90 px-2 py-1 text-sm shadow-sm backdrop-blur",
                    )
                }) {
                    Span({ classes("text-muted-foreground") }) { Text("Current geofence") }
                    detail.geofences.forEach { fence ->
                        Badge("border-transparent bg-secondary text-secondary-foreground") { Text(fence) }
                    }
                }
            }
            Button({
                classes(
                    "absolute bottom-3 left-3 z-[500] inline-flex h-9 items-center justify-center gap-1.5 rounded-lg border border-border bg-card/95 px-4 text-sm font-medium text-foreground shadow-sm backdrop-blur transition-colors hover:bg-accent",
                )
            }) {
                Icon(Icon.BELL, "h-4 w-4")
                Text("Geo e-mail alerts")
            }
            Span({
                classes(
                    "absolute bottom-3 right-3 z-[500] inline-flex h-9 items-center justify-center rounded-lg border border-border bg-card/95 px-4 text-sm font-medium text-foreground shadow-sm backdrop-blur transition-colors hover:bg-accent",
                )
            }) { Text("View on map") }
        }
    }
}

@Composable
private fun ConnectionsBlock(detail: VesselDetail, telemetry: Telemetry) {
    Block("Connections", "col-span-12", "block-connections") {
        Div({ classes("grid gap-3 sm:grid-cols-2 xl:grid-cols-3") }) {
            detail.connections.forEach { connection ->
                val colour = stateColour(connection.state)
                Div({
                    classes("rounded-lg border border-l-4 border-border bg-card p-3 text-sm text-card-foreground")
                    style("border-left-color: $colour")
                }) {
                    Div({ classes("flex items-center justify-between gap-2") }) {
                        Div({ classes("flex items-center gap-2") }) {
                            Span({ classes("h-2.5 w-2.5 shrink-0 rounded-full"); style("background-color: $colour") })
                            Span({ classes("font-medium text-foreground") }) { Text(connection.title) }
                        }
                        Div({ classes("flex items-center gap-1.5") }) {
                            connection.pills.forEach { pill ->
                                if (pill == "Roaming") {
                                    Span({ classes("rounded bg-amber-500/15 px-1.5 py-0.5 text-[11px] font-medium text-amber-600") }) {
                                        Text(pill)
                                    }
                                } else {
                                    Badge("border-transparent bg-secondary text-secondary-foreground") { Text(pill) }
                                }
                            }
                        }
                    }
                    Div({ classes("mt-2 space-y-0.5") }) {
                        connection.rows.forEach { (label, value) -> MetricRow(label, value) }
                        if (connection.title == "Cellular") {
                            MetricRow(
                                "Signal",
                                "RSRP ${telemetry.rsrpDbm} dBm · RSRQ ${telemetry.rsrqDb} dB · SINR ${telemetry.sinrDb} dB",
                            )
                            Div({ classes("mt-1 flex gap-1") }) {
                                listOf("BYO A", "BYO B", "Peplink").forEach { sim ->
                                    Span({ classes("rounded border border-border bg-background px-1.5 py-0.5 text-[10px] text-muted-foreground") }) {
                                        Text(sim)
                                    }
                                }
                            }
                        }
                    }
                    if (connection.title == "WAN") StarlinkPanel(telemetry)
                }
            }
        }
    }
}

/** The dish, nested under WAN as the original nests it. Every number here is live. */
@Composable
private fun StarlinkPanel(telemetry: Telemetry) {
    Div({ classes("mt-2 space-y-0.5 border-t border-border pt-2"); testTag("starlink") }) {
        Div({ classes("mb-1 flex items-center justify-between") }) {
            Span({ classes("flex items-center gap-1.5 text-xs font-medium uppercase tracking-wider text-muted-foreground") }) {
                Icon(Icon.SATELLITE, "h-3.5 w-3.5")
                Text("Starlink dish")
            }
            Div({ classes("flex items-center gap-1.5") }) {
                Badge("text-foreground border-border") { Text("BUSINESS") }
                Badge("border-transparent bg-green-600 text-white") { Text("Serving") }
            }
        }
        MetricRow("Throughput", "↓ ${telemetry.downKbps} kbps · ↑ ${telemetry.upKbps} kbps")
        MetricRow("Latency", "${telemetry.latencyMs} ms")
        MetricRow("Obstruction", "%.2f%%".format(telemetry.obstructionPercent))
        MetricRow("Dish uptime", formatUptime(telemetry.dishUptimeSeconds))
        MetricRow("GPS", "Locked (${telemetry.satellites} sats)")
    }
}

private fun stateColour(state: State): String = when (state) {
    State.UP -> "#16a34a"
    State.DEGRADED -> "#f59e0b"
    State.IDLE -> "var(--color-muted-foreground)"
}

/**
 * The original groups routes by kind (WAN, LAN, SpeedFusion, OSPF) under their own uppercase
 * sub-heading; this sample's fake routes are all LAN-side, so there is exactly one group.
 */
@Composable
private fun RoutingBlock(rows: List<NetRow>) {
    Block("Routing", "col-span-12 lg:col-span-4", "block-routing") {
        Div({ classes("space-y-1.5") }) {
            SubHeading("LAN")
            Div({ classes("divide-y divide-border") }) {
                rows.forEach { row ->
                    Div({ classes("flex items-start justify-between gap-3 py-1 text-xs") }) {
                        Span({ classes("flex items-center gap-2") }) {
                            Span({ classes("font-medium text-foreground") }) {
                                Text(row.name.ifEmpty { row.gateway })
                            }
                            Badge("text-foreground border-border") { Text("VLAN ${row.vlan}") }
                        }
                        Span({ classes("text-right font-mono text-xs text-muted-foreground") }) { Text(row.cidr) }
                    }
                }
            }
        }
    }
}

@Composable
private fun VlansBlock(rows: List<NetRow>) {
    Block("VLANs", "col-span-12 lg:col-span-4", "block-vlans") {
        Div({ classes("divide-y divide-border") }) {
            rows.forEach { row ->
                Div({ classes("flex items-center justify-between gap-3 py-1 text-xs") }) {
                    Span({ classes("font-medium text-foreground") }) { Text(row.name.ifEmpty { row.gateway }) }
                    Span({ classes("flex items-center gap-2") }) {
                        Span({ classes("font-mono text-xs text-muted-foreground") }) { Text(row.gateway) }
                        Badge("text-foreground border-border") { Text("VLAN ${row.vlan}") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedFusionBlock(detail: VesselDetail) {
    val peer = detail.peer
    Block("SpeedFusion VPN", "col-span-12 lg:col-span-4", "block-speedfusion") {
        Div({ classes("flex items-center justify-between") }) {
            Span({ classes("font-medium") }) { Text(peer.name) }
            Span({ classes("rounded bg-green-600 px-2 py-0.5 text-[11px] font-medium text-white") }) {
                Text("${peer.connected}/${peer.links.size} connected")
            }
        }
        Div({ classes("mt-3 space-y-2") }) {
            peer.links.forEach { link ->
                Div({ classes("rounded-md border border-border p-2.5") }) {
                    Div({ classes("flex items-center justify-between") }) {
                        Span({ classes("text-sm font-medium") }) { Text(link.name) }
                        Span({
                            classes(
                                if (link.connected) "rounded bg-green-600 px-1.5 py-0.5 text-[10px] font-medium text-white"
                                else "rounded bg-gray-400 px-1.5 py-0.5 text-[10px] font-medium text-white",
                            )
                        }) { Text(if (link.connected) "CONNECTED" else "DOWN") }
                    }
                    MetricRow("Remote device", link.remote)
                    MetricRow(if (link.active) "Active" else "Inactive", link.subnets)
                }
            }
        }
    }
}

/**
 * Usage by month, with tabs.
 *
 * The selected month is `rememberSaved` rather than `remember`: leaving this page and coming back
 * finds the tab where it was left. That is a different lifetime from the search box in the container
 * — which outlives the page entirely — and a different one again from anything in a plain
 * `remember`, which would be back on September.
 */
@Composable
private fun DataUsageBlock(vessel: Vessel, detail: VesselDetail) {
    var selected by rememberSaved(String.serializer(), key = "month") { MONTH_LABELS.last() }
    val month = detail.months.firstOrNull { it.label == selected } ?: detail.months.last()

    Block("Data usage", "col-span-12", "block-data-usage") {
        Div({ classes("space-y-4") }) {
            Div {
                Div({ classes("text-sm text-muted-foreground") }) { Text("Total download this month") }
                Div({ classes("text-3xl font-bold tabular-nums text-foreground"); testTag("usage-total") }) {
                    Text("%.1f GB".format(month.totalGb))
                }
            }

            Div({ classes("flex flex-wrap gap-1 text-sm") }) {
                detail.months.forEach { candidate ->
                    val active = candidate.label == selected
                    Span({
                        classes(
                            if (active) "cursor-pointer rounded-md px-2.5 py-1 font-medium transition-colors bg-accent text-foreground"
                            else "cursor-pointer rounded-md px-2.5 py-1 font-medium transition-colors text-muted-foreground hover:text-foreground",
                        )
                        testTag("month-${candidate.label.substringBefore(' ')}")
                        onClick { selected = candidate.label }
                    }) { Text(candidate.label) }
                }
            }

            Div({ classes("grid gap-4 lg:grid-cols-[1fr_220px]") }) {
                Div({ classes("overflow-x-auto") }) { MonthChart(month) }
                Div({ classes("space-y-2") }) {
                    Div({ classes("flex items-center justify-between gap-3 text-sm") }) {
                        Span({ classes("flex items-center gap-2") }) {
                            Span({ classes("h-0.5 w-4 shrink-0 rounded-full bg-orange-500") })
                            Span({ classes("font-medium text-foreground") }) { Text("WAN") }
                        }
                        Span({ classes("tabular-nums text-muted-foreground") }) { Text("%.1f GB".format(month.wanGb)) }
                    }
                    Div({ classes("flex items-center justify-between gap-3 text-sm") }) {
                        Span({ classes("flex items-center gap-2") }) {
                            Span({ classes("h-0.5 w-4 shrink-0 rounded-full bg-teal-600") })
                            Span({ classes("font-medium text-foreground") }) { Text("Cellular") }
                        }
                        Span({ classes("tabular-nums text-muted-foreground") }) { Text("%.1f GB".format(month.cellularGb)) }
                    }
                }
            }
            P({ classes("text-xs text-muted-foreground") }) {
                Text("Download usage per WAN, reported by InControl.")
            }
        }
    }
}

@Composable
private fun MonthChart(month: MonthUsage) {
    val peak = max(0.1, month.days.maxOf { it.wanGb + it.cellularGb })
    Div({ classes("flex flex-1 gap-2") }) {
        Div({ classes("flex w-10 shrink-0 flex-col justify-between py-1 text-right text-[10px] text-muted-foreground") }) {
            listOf("12 GB", "9 GB", "6 GB", "3 GB", "0 GB").forEach { label -> Span { Text(label) } }
        }
        Svg({
            classes("h-40 w-full")
            attr("viewBox", "0 0 240 160")
            attr("preserveAspectRatio", "none")
            testTag("month-chart")
        }) {
            month.days.forEachIndexed { index, day ->
                val wanHeight = (day.wanGb / peak) * 160.0
                val cellHeight = (day.cellularGb / peak) * 160.0
                val x = 12.0 + index * 24.0
                Rect({
                    attr("x", "%.1f".format(x)); attr("y", "%.1f".format(160.0 - wanHeight))
                    attr("width", "8"); attr("height", "%.1f".format(wanHeight))
                    attr("fill", "#f97316")
                })
                Rect({
                    attr("x", "%.1f".format(x + 9)); attr("y", "%.1f".format(160.0 - cellHeight))
                    attr("width", "8"); attr("height", "%.1f".format(cellHeight))
                    attr("fill", "#0d9488")
                })
            }
        }
    }
}

/**
 * A card with the small uppercase heading the original gives every block — `DashboardGrid`'s chrome,
 * minus the drag handle and the height-filling flex it needs only for the resizable grid this replica
 * does not build.
 */
@Composable
private fun Block(
    title: String,
    span: String,
    tag: String,
    showHeader: Boolean = true,
    content: @Composable () -> Unit,
) {
    Div({ classes("overflow-hidden rounded-xl border border-border bg-card text-card-foreground $span"); testTag(tag) }) {
        if (showHeader) {
            Div({ classes("flex items-center gap-1.5 px-4 py-2.5") }) {
                Span({ classes("text-sm font-medium uppercase tracking-wider text-muted-foreground") }) { Text(title) }
            }
        }
        Div({ classes(if (showHeader) "px-4 pt-1 pb-4" else "px-4 pt-4 pb-4") }) {
            content()
        }
    }
}

@Composable
private fun SubHeading(text: String) {
    Div({ classes("text-xs font-medium uppercase tracking-wider text-muted-foreground") }) { Text(text) }
}

/** Used in CONNECTIONS, SPEEDFUSION and the Starlink panel. */
@Composable
private fun MetricRow(label: String, value: String) {
    Div({ classes("flex items-start justify-between gap-4 py-0.5 text-xs") }) {
        Span({ classes("shrink-0 text-muted-foreground") }) { Text(label) }
        Span({ classes("text-right font-medium text-foreground") }) { Text(value) }
    }
}

/** Used in the STATUS block, whose labels sit right-aligned against their values. */
@Composable
private fun StatusMetricRow(label: String, value: String) {
    Div({ classes("grid grid-cols-[minmax(6.5rem,42%)_minmax(0,1fr)] items-start gap-3 text-xs leading-5") }) {
        Span({ classes("text-right text-muted-foreground") }) { Text(label) }
        Span({ classes("min-w-0 text-left font-medium text-foreground") }) { Text(value) }
    }
}

/**
 * The shared badge base every pill on these pages is drawn from — the original's `Badge` component,
 * with the variant's colours passed in rather than looked up, since Tailwind reads class names out
 * of this file rather than out of a runtime map.
 */
@Composable
internal fun Badge(extra: String, tag: String? = null, content: @Composable () -> Unit) {
    Span({
        classes(
            "inline-flex items-center justify-center gap-1 rounded-md border px-2 py-0.5 text-xs font-medium whitespace-nowrap w-fit shrink-0 transition-colors $extra",
        )
        if (tag != null) testTag(tag)
    }) { content() }
}

/** A labelled percentage bar. Live, so this is one of the things that moves on its own. */
@Composable
private fun Gauge(icon: Icon, label: String, percent: Int, tag: String) {
    Div {
        Div({ classes("mb-1 flex items-center justify-between text-sm") }) {
            Span({ classes("flex items-center gap-1.5 text-muted-foreground") }) {
                Icon(icon, "h-3.5 w-3.5")
                Text(label)
            }
            Span({ classes("font-medium text-foreground"); testTag("gauge-$tag") }) { Text("$percent%") }
        }
        Div({ classes("relative h-2 w-full overflow-hidden rounded-full bg-border") }) {
            Div({
                classes("h-full rounded-full bg-primary transition-all")
                style("width: $percent%")
            })
        }
    }
}
