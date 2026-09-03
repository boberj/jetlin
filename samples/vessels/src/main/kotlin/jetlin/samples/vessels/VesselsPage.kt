package jetlin.samples.vessels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import jetlin.html.Button
import jetlin.html.Circle
import jetlin.html.Div
import jetlin.html.H1
import jetlin.html.Input
import jetlin.html.Link
import jetlin.html.LocalNavigator
import jetlin.html.P
import jetlin.html.Span
import jetlin.html.Svg
import jetlin.html.Table
import jetlin.html.Tbody
import jetlin.html.Td
import jetlin.html.Text
import jetlin.html.Th
import jetlin.html.Thead
import jetlin.html.Tr
import jetlin.html.queryParam
import jetlin.protocol.ListenerSpec
import kotlin.math.PI

/**
 * The fleet list.
 *
 * Two pieces of state decide what is on screen, and they live in different places on purpose. The
 * **sort** is in the query string, because a fleet sorted by priority is a link an operator sends a
 * colleague and expects to survive a reload. The **search** is in the session's container, because a
 * half-typed name is not worth a URL and putting it there would mean a history entry per keystroke —
 * and because it has to still be there after opening a vessel and pressing back, which is the thing
 * the container exists for.
 *
 * Every matching vessel is rendered. The original virtualizes instead; a server cannot, because it
 * cannot see the scroll position. What that costs is measured in FINDINGS.md rather than argued.
 */
@Composable
fun VesselsPage() {
    val navigator = LocalNavigator.current
    val view = LocalFleetView.current

    val sort = SortKey.from(queryParam("sort"))
    val ascending = queryParam("dir") != "desc"
    val vessels = FleetStore.list(view.query, sort, ascending)

    Div({ classes("container mx-auto p-8 space-y-6") }) {
        FleetHeader()

        Div({ classes("rounded-xl border border-border bg-card text-card-foreground") }) {
            Div({ classes("flex flex-col space-y-1.5 px-6 pt-6 pb-3") }) {
                Div({ classes("flex flex-col sm:flex-row sm:items-center justify-between gap-3") }) {
                    Div({ classes("flex items-center gap-2 text-base font-medium text-foreground") }) {
                        Icon(Icon.SHIP, "h-5 w-5 text-blue-600")
                        Text("Fleet Status Overview")
                    }
                    Div({ classes("relative w-full sm:w-64") }) {
                        Span({ classes("pointer-events-none absolute left-2.5 top-1/2 -translate-y-1/2 text-muted-foreground") }) {
                            Icon(Icon.SEARCH, "h-4 w-4")
                        }
                        Input({
                            type("search")
                            classes("flex h-9 w-full rounded-md border border-border bg-input-background pl-8 pr-3 py-1 text-sm placeholder:text-muted-foreground outline-none focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]")
                            testTag("search")
                            attr("placeholder", "Search vessels...")
                            value(view.query)
                            // Debounced, so a name is one round trip rather than one per keystroke. No
                            // navigation: this writes session state, which invalidates only the table.
                            onInput(debounceMs = 200) { typed -> view.query = typed }
                        })
                    }
                }
            }

            Div({ classes("p-6 pt-0") }) {
                if (vessels.isEmpty()) {
                    P({ classes("px-4 py-12 text-center text-sm text-muted-foreground"); testTag("empty") }) {
                        Text(
                            if (view.query.isBlank()) "No vessels found."
                            else "No vessels match “${view.query}”.",
                        )
                    }
                } else {
                    Div({ classes("rounded-md border overflow-auto max-h-[calc(100vh-260px)]") }) {
                        Table({ classes("w-full text-sm text-left table-fixed") }) {
                            Thead({
                                classes("text-xs text-muted-foreground uppercase bg-gray-50 border-b border-border sticky top-0 z-10")
                            }) {
                                Tr {
                                    SortHeader("Vessel", SortKey.NAME, sort, ascending, "px-2 py-3")
                                    Th({ classes("px-2 py-3 w-24") }) { Text("Status") }
                                    SortHeader("Data Usage", SortKey.USAGE, sort, ascending, "px-2 py-3 w-56")
                                    SortHeader("Progress", SortKey.PROGRESS, sort, ascending, "px-2 py-3 w-28")
                                    SortHeader("Priority", SortKey.PRIORITY, sort, ascending, "px-2 py-3 w-24")
                                    Th({ classes("px-2 py-3 w-44") }) { Text("Actions") }
                                }
                            }
                            Tbody({ classes("divide-y divide-border"); testTag("rows") }) {
                                vessels.forEach { vessel ->
                                    // Keyed by identity, so re-sorting moves the existing rows rather
                                    // than rewriting every cell of every one of them. The assertion
                                    // that this holds is the one no diffing framework can make.
                                    key(vessel.id) { VesselRow(vessel, navigator::push) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The organisation, and the counts across the whole fleet rather than the filtered view. */
@Composable
private fun FleetHeader() {
    Div({ classes("flex flex-col sm:flex-row sm:items-center justify-between gap-4") }) {
        Div({ classes("flex items-center gap-4") }) {
            OrgAvatar("flex h-16 w-16 items-center justify-center rounded-xl bg-blue-100", "h-8 w-8 text-blue-600")
            Div {
                H1({ classes("text-2xl font-bold text-gray-900") }) {
                    Text(FleetStore.organizationName)
                }
                P({ classes("text-gray-600") }) { Text("Fleet Management Dashboard") }
            }
        }
        Div({ classes("flex flex-col items-end gap-2") }) {
            Button({
                classes("inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md border border-border bg-card text-xs font-medium transition-colors outline-none hover:bg-accent hover:text-accent-foreground h-8 px-3")
                testTag("wan-analysis")
            }) {
                Icon(Icon.ACTIVITY, "h-4 w-4")
                Text("Start WAN analysis")
            }
            Div({ classes("flex items-center gap-1.5 text-sm font-medium text-gray-700") }) {
                Icon(Icon.SHIP, "h-4 w-4 text-blue-600")
                Span({ testTag("vessel-count") }) { Text("${FleetStore.size} vessels") }
            }
            Div({ classes("flex items-center gap-4 text-sm") }) {
                StatusCount("bg-green-500", "text-green-700", "text-green-600/70", FleetStore.onlineCount(), "online", "count-online")
                StatusCount("bg-red-500", "text-red-700", "text-red-600/70", FleetStore.offlineCount(), "offline", "count-offline")
                val unknown = FleetStore.unknownCount()
                if (unknown > 0) {
                    StatusCount("bg-gray-400", "text-gray-500", "text-gray-400", unknown, "unknown", "count-unknown")
                }
            }
        }
    }
}

@Composable
private fun StatusCount(dot: String, textColour: String, labelColour: String, count: Int, label: String, tag: String) {
    Span({ classes("flex items-center gap-1.5 $textColour"); testTag(tag) }) {
        Span({ classes("h-2.5 w-2.5 rounded-full $dot shrink-0") })
        Span({ classes("font-semibold") }) { Text(count.toString()) }
        Span({ classes(labelColour) }) { Text(label) }
    }
}

/**
 * A sortable column heading.
 *
 * Clicking the active column flips direction; clicking a new one starts ascending for the name and
 * descending for the numbers, which is the original's rule and the one that puts the interesting end
 * of each column first.
 *
 * The button's own colour never changes — only the chevron's opacity does, full for the active
 * column and faint otherwise — which is the original's way of marking the active column without
 * moving the label's colour around.
 */
@Composable
private fun SortHeader(label: String, key: SortKey, current: SortKey, ascending: Boolean, thClasses: String) {
    val navigator = LocalNavigator.current
    val active = current == key
    Th({ classes(thClasses) }) {
        Button({
            classes("flex items-center gap-1 uppercase text-xs text-muted-foreground hover:text-foreground transition-colors")
            testTag("sort-${key.param}")
            onClick {
                val nextAscending = if (active) !ascending else key == SortKey.NAME
                navigator.push(fleetUrl(key, nextAscending))
            }
        }) {
            Text(label)
            Icon(
                if (active && ascending) Icon.CHEVRON_UP else Icon.CHEVRON_DOWN,
                if (active) "h-3 w-3 opacity-100" else "h-3 w-3 opacity-30",
            )
        }
    }
}

@Composable
private fun VesselRow(vessel: Vessel, open: (String) -> Unit) {
    val device = FleetStore.deviceStatus(vessel)

    Tr({
        classes(rowClass(vessel))
        testTag("row")
        onClick { open("/vessels/${vessel.id}") }
    }) {
        Td({ classes("px-2 py-2") }) {
            Div({ classes("flex items-center gap-2") }) {
                if (vessel.emergency) {
                    Icon(Icon.ALERT_TRIANGLE, "h-5 w-5 text-red-600 flex-shrink-0 animate-pulse")
                } else {
                    Icon(Icon.SHIP, "h-4 w-4 shrink-0 text-blue-600")
                }
                Div({ classes("flex min-w-0 flex-col") }) {
                    Div({ classes("flex items-center gap-2") }) {
                        Link("/vessels/${vessel.id}", {
                            classes(
                                if (vessel.emergency) "truncate text-sm font-medium text-red-700 font-bold hover:underline"
                                else "truncate text-sm font-medium text-foreground hover:underline",
                            )
                            testTag("name")
                        }) { Text(vessel.name) }
                        if (vessel.emergency) {
                            Badge("bg-red-600 text-white animate-pulse text-[10px] px-1 py-0") { Text("EMERGENCY") }
                        }
                    }
                    Div({ classes("flex items-center gap-2 text-[11px] text-muted-foreground font-mono") }) {
                        Span { Text(vessel.serial) }
                        Span({ classes("text-border") }) { Text("·") }
                        Span { Text(vessel.lanIp) }
                    }
                }
            }
        }

        Td({ classes("px-2 py-2") }) { StatusPill(vessel, device) }
        Td({ classes("px-2 py-2") }) { UsageCell(vessel) }
        Td({ classes("px-2 py-2") }) { ProgressCell(vessel) }
        Td({ classes("px-2 py-2") }) { PriorityCell(vessel) }
        Td({ classes("px-2 py-2") }) { ActionsCell(vessel) }
    }
}

@Composable
private fun StatusPill(vessel: Vessel, device: DeviceStatus?) {
    if (device == null) {
        Span({ classes("text-xs text-muted-foreground"); testTag("status") }) { Text("—") }
        return
    }
    val variant = when {
        vessel.disabled -> "bg-gray-100 text-gray-500 border-gray-300"
        device.online -> "bg-green-100 text-green-800 border-green-200"
        else -> "bg-red-100 text-red-800 border-red-200"
    }
    Badge(variant, tag = "status") { Text(if (device.online) "Online" else "Offline") }
}

/**
 * The two stacked meters: Starlink on a solid track, cellular as a row of dots.
 *
 * The fill is coloured by how close the plan is to being spent, not by the raw percentage — the
 * original's reason, which is that going over costs money rather than merely being a big number.
 */
@Composable
private fun UsageCell(vessel: Vessel) {
    val usage = FleetStore.usage(vessel)
    val cellularGb = FleetStore.cellularUsageGb(vessel)

    if (usage == null && cellularGb == null) {
        Span({ classes("text-xs text-muted-foreground") }) { Text("—") }
        return
    }

    Div({ classes("w-full max-w-[200px]") }) {
        if (usage != null) {
            Div({ classes("mb-1.5") }) { StarlinkMeter(usage, vessel.disabled) }
        }
        if (cellularGb != null) {
            CellularMeter(cellularGb, vessel.disabled)
        }
    }
}

@Composable
private fun StarlinkMeter(usage: UsageStatus, disabled: Boolean) {
    val barColour = when {
        disabled -> "bg-gray-400"
        usage.level == "critical" -> "bg-red-500"
        usage.level == "warning" -> "bg-yellow-500"
        else -> "bg-green-500"
    }
    val textColour = when {
        disabled -> "text-gray-400"
        usage.level == "critical" -> "text-red-600 font-bold"
        usage.level == "warning" -> "text-yellow-600 font-semibold"
        else -> "text-green-600"
    }
    Div({ classes("flex items-center gap-2") }) {
        Span({ classes("text-[10px] font-medium text-blue-600 w-6") }) { Text("SL") }
        Div({ classes("flex-1 bg-gray-200 rounded-full h-2.5 overflow-hidden") }) {
            Div({
                classes("h-full rounded-full transition-all duration-300 $barColour")
                // The one number here that is genuinely continuous, so it is a style rather than a
                // class: Tailwind cannot generate a utility per percentage.
                style("width: ${(usage.fraction * 100).toInt()}%")
            })
        }
        Span({ classes("text-[11px] font-mono min-w-[45px] text-right $textColour") }) {
            Text("%.0f".format(usage.usedGb) + "GB")
        }
    }
}

/** One dot per 50 GB used, ten dots per row, and a new row starts every 500 GB. */
@Composable
private fun CellularMeter(usedGb: Double, disabled: Boolean) {
    val greenDots = (usedGb / 50).toInt()
    val rows = (usedGb / 500).toInt() + 1
    val totalDots = rows * 10
    val textColour = if (disabled) "text-gray-400" else "text-green-600"

    Div({ classes("flex items-center gap-2") }) {
        Span({ classes("text-[10px] font-medium text-green-600 w-6") }) { Text("5G") }
        Div({ classes("flex-1 grid grid-cols-10 gap-y-0.5 justify-items-center") }) {
            repeat(totalDots) { i ->
                Span({
                    classes(
                        when {
                            i >= greenDots -> "h-1.5 w-1.5 rounded-full bg-gray-300"
                            disabled -> "h-1.5 w-1.5 rounded-full bg-gray-400"
                            else -> "h-1.5 w-1.5 rounded-full bg-green-500"
                        },
                    )
                })
            }
        }
        Span({ classes("text-[11px] font-mono min-w-[45px] text-right $textColour") }) {
            Text("%.0f".format(usedGb) + "GB")
        }
    }
}

/**
 * The progress ring, ported from the original's `CircleProgress`.
 *
 * A stroked circle with its dash pattern set to the circumference and the gap moved round to the
 * fraction wanted — the standard trick, and the reason this needs real SVG rather than a div.
 */
@Composable
private fun ProgressCell(vessel: Vessel) {
    val radius = 12.0
    val circumference = 2 * PI * radius
    val offset = circumference - (vessel.progress / 100.0) * circumference
    Div({ classes("flex items-center gap-2") }) {
        Svg({
            classes("h-7 w-7 shrink-0")
            attr("viewBox", "0 0 28 28")
            attr("aria-hidden", "true")
        }) {
            Circle({
                attr("cx", "14"); attr("cy", "14"); attr("r", "12")
                attr("fill", "none"); attr("stroke", "#e5e7eb"); attr("stroke-width", "3")
            })
            Circle({
                attr("cx", "14"); attr("cy", "14"); attr("r", "12")
                attr("fill", "none")
                attr("stroke", if (vessel.progress >= 100) "#16a34a" else "#6b7280")
                attr("stroke-width", "3")
                attr("stroke-dasharray", "%.2f".format(circumference))
                attr("stroke-dashoffset", "%.2f".format(offset))
                attr("stroke-linecap", "round")
                attr("transform", "rotate(-90 14 14)")
            })
        }
        Span({ classes("text-xs text-muted-foreground"); testTag("progress") }) {
            Text("${vessel.progress}%")
        }
    }
}

/**
 * Priority, edited in place.
 *
 * The sharpest thing in this sample: one click writes one vessel's field, and because the rows are
 * keyed, the update should touch a handful of cells in one row out of eighty. When the table is
 * sorted by priority it also reorders, which makes it the one interaction producing moves and
 * content edits in the same patch.
 */
@Composable
private fun PriorityCell(vessel: Vessel) {
    Div({ classes("flex items-center gap-1") }) {
        Stepper(Icon.CHEVRON_UP, "priority-up-${vessel.id}", disabled = false) {
            vessel.priority = (vessel.priority + 1).coerceAtMost(9)
        }
        Span({ classes("font-mono text-sm min-w-[20px] text-center"); testTag("priority-${vessel.id}") }) {
            Text(vessel.priority.toString())
        }
        Stepper(Icon.CHEVRON_DOWN, "priority-down-${vessel.id}", disabled = vessel.priority == 0) {
            vessel.priority = (vessel.priority - 1).coerceAtLeast(0)
        }
    }
}

@Composable
private fun Stepper(icon: Icon, tag: String, disabled: Boolean, onPress: () -> Unit) {
    Button({
        classes(
            if (disabled) "h-6 w-6 p-0 rounded flex items-center justify-center hover:bg-blue-100 transition-colors disabled:opacity-30"
            else "h-6 w-6 p-0 rounded flex items-center justify-center hover:bg-blue-100 transition-colors",
        )
        testTag(tag)
        disabled(disabled)
        // The row navigates on click; a button inside it must not also do that.
        on("click", ListenerSpec(stopPropagation = true)) { onPress() }
    }) { Icon(icon, "h-3 w-3") }
}

/**
 * The eleven per-row actions.
 *
 * Five of them toggle the flags that tint the row; two carry counts; the rest stand in for dialogs
 * the replica does not open. Every one has a `title`, which is the honest fallback for a tooltip —
 * see FINDINGS.md.
 */
@Composable
private fun ActionsCell(vessel: Vessel) {
    Div({ classes("grid grid-cols-6 gap-1") }) {
        FlagAction(vessel, VesselFlag.CONSTRUCTION, Icon.CONSTRUCTION, "bg-orange-200 text-orange-700 hover:bg-orange-300")
        FlagAction(vessel, VesselFlag.MAINTENANCE, Icon.WRENCH, "bg-purple-200 text-purple-700 hover:bg-purple-300")
        FlagAction(vessel, VesselFlag.ALERT, Icon.BELL, "bg-red-200 text-red-700 hover:bg-red-300") { on ->
            if (on) "h-4 w-4 bell-ringing" else "h-4 w-4"
        }
        FlagAction(vessel, VesselFlag.EMERGENCY, Icon.ALERT_CIRCLE, "bg-red-600 text-red-700 animate-pulse") { on ->
            if (on) "h-4 w-4 fill-white" else "h-4 w-4"
        }
        FlagAction(vessel, VesselFlag.DISABLED, Icon.EYE_OFF, "bg-gray-300 text-gray-700 hover:bg-gray-400")
        Badged(Icon.NOTEBOOK_TEXT, "Notes", vessel.noteCount, "bg-blue-100 text-blue-600 hover:bg-blue-200", "bg-blue-500")
        Badged(Icon.CLIPBOARD_LIST, "Tickets", vessel.openTicketCount, "bg-green-100 text-green-600 hover:bg-green-200", "bg-red-500")
        InertAction(Icon.MAP_PIN, "Show on map")
        InertAction(Icon.PHONE, "Contact")
        InertAction(Icon.SETTINGS, "Settings")
        InertAction(Icon.EXTERNAL_LINK, "Open in InControl")
    }
}

@Composable
private fun FlagAction(
    vessel: Vessel,
    flag: VesselFlag,
    icon: Icon,
    activeClasses: String,
    iconClasses: (on: Boolean) -> String = { "h-4 w-4" },
) {
    val on = FleetStore.isSet(vessel, flag)
    Button({
        classes(
            if (on) "h-6 w-6 p-0 rounded flex items-center justify-center transition-colors $activeClasses"
            else "h-6 w-6 p-0 rounded flex items-center justify-center transition-colors text-muted-foreground hover:text-foreground hover:bg-accent",
        )
        attr("title", flag.label)
        testTag("flag-${flag.name.lowercase()}-${vessel.id}")
        on("click", ListenerSpec(stopPropagation = true)) { FleetStore.toggle(vessel, flag) }
    }) { Icon(icon, iconClasses(on)) }
}

@Composable
private fun Badged(icon: Icon, title: String, count: Int, tint: String, badgeColour: String) {
    Div({ classes("relative") }) {
        Button({
            classes(
                if (count > 0) "h-6 w-6 p-0 rounded flex items-center justify-center transition-colors $tint"
                else "h-6 w-6 p-0 rounded flex items-center justify-center transition-colors text-muted-foreground hover:text-foreground hover:bg-accent",
            )
            attr("title", title)
            on("click", ListenerSpec(stopPropagation = true)) { }
        }) { Icon(icon, "h-4 w-4") }
        if (count > 0) {
            Span({
                classes(
                    "absolute -top-0.5 -right-0.5 min-w-[12px] h-3 px-[3px] rounded-full text-white text-[8px] font-semibold leading-3 text-center tabular-nums $badgeColour",
                )
            }) { Text(if (count > 99) "99+" else count.toString()) }
        }
    }
}

@Composable
private fun InertAction(icon: Icon, title: String) {
    Button({
        classes("h-6 w-6 p-0 rounded flex items-center justify-center transition-colors text-muted-foreground hover:text-foreground hover:bg-accent")
        attr("title", title)
        on("click", ListenerSpec(stopPropagation = true)) { }
    }) { Icon(icon, "h-4 w-4") }
}

/**
 * The row's tint, straight from the original's `rowClass`.
 *
 * Whole class strings rather than assembled ones, and for the same reason the original writes them
 * that way: Tailwind reads its class names out of this file, and one built at runtime is not there
 * to be read.
 */
private fun rowClass(vessel: Vessel): String = when {
    vessel.emergency || vessel.alert -> "cursor-pointer hover:bg-gray-50 bg-red-50 border-l-4 border-l-red-400"
    vessel.construction -> "cursor-pointer hover:bg-gray-50 bg-orange-50 border-l-4 border-l-orange-400"
    vessel.disabled -> "cursor-pointer hover:bg-gray-50 bg-gray-200 opacity-70"
    else -> "cursor-pointer hover:bg-gray-50"
}

/**
 * The fleet URL, carrying the sort and nothing else.
 *
 * Written out by hand because there is nothing in the framework for "the current URL with one
 * parameter changed", which is the only URL operation a page like this ever wants. See FINDINGS.md.
 */
internal fun fleetUrl(sort: SortKey, ascending: Boolean): String {
    val parts = buildList {
        if (sort != SortKey.PRIORITY) add("sort=${sort.param}")
        if (!ascending) add("dir=desc")
    }
    return if (parts.isEmpty()) "/" else "/?" + parts.joinToString("&")
}
