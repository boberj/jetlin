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

    Div({ classes("container mx-auto px-4 py-6") }) {
        FleetHeader()

        Div({ classes("mt-6 rounded-xl border border-border bg-card shadow-sm") }) {
            Div({ classes("flex items-center justify-between gap-4 border-b border-border px-6 py-4") }) {
                Div({ classes("flex items-center gap-2 text-base font-medium text-foreground") }) {
                    Icon(Icon.SHIP, "h-5 w-5 text-blue-600")
                    Text("Fleet Status Overview")
                }
                Div({ classes("relative w-72") }) {
                    Span({ classes("pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-muted-foreground") }) {
                        Icon(Icon.SEARCH, "h-4 w-4")
                    }
                    Input({
                        type("search")
                        classes("h-9 w-full rounded-md border border-border bg-input-background pl-9 pr-3 text-sm outline-none focus:ring-2 focus:ring-ring")
                        testTag("search")
                        attr("placeholder", "Search vessels...")
                        value(view.query)
                        // Debounced, so a name is one round trip rather than one per keystroke. No
                        // navigation: this writes session state, which invalidates only the table.
                        onInput(debounceMs = 200) { typed -> view.query = typed }
                    })
                }
            }

            Div({ classes("px-2 py-2") }) {
                if (vessels.isEmpty()) {
                    P({ classes("px-4 py-12 text-center text-sm text-muted-foreground"); testTag("empty") }) {
                        Text(
                            if (view.query.isBlank()) "No vessels found."
                            else "No vessels match “${view.query}”.",
                        )
                    }
                } else {
                    Table({ classes("w-full border-collapse text-sm") }) {
                        Thead {
                            Tr({ classes("border-b border-border text-left text-xs uppercase tracking-wider text-muted-foreground") }) {
                                SortHeader("Vessel", SortKey.NAME, sort, ascending)
                                Th({ classes("px-2 py-2 font-medium") }) { Text("Status") }
                                SortHeader("Data usage", SortKey.USAGE, sort, ascending)
                                SortHeader("Progress", SortKey.PROGRESS, sort, ascending)
                                SortHeader("Priority", SortKey.PRIORITY, sort, ascending)
                                Th({ classes("px-2 py-2 font-medium") }) { Text("Actions") }
                            }
                        }
                        Tbody({ testTag("rows") }) {
                            vessels.forEach { vessel ->
                                // Keyed by identity, so re-sorting moves the existing rows rather
                                // than rewriting every cell of every one of them. The assertion that
                                // this holds is the one no diffing framework can make.
                                key(vessel.id) { VesselRow(vessel, navigator::push) }
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
    Div({ classes("flex items-start justify-between gap-4") }) {
        Div({ classes("flex items-center gap-4") }) {
            OrgTile("h-16 w-16 text-base")
            Div {
                H1({ classes("text-3xl font-bold tracking-tight text-foreground") }) {
                    Text(FleetStore.organizationName)
                }
                P({ classes("text-muted-foreground") }) { Text("Fleet Management Dashboard") }
            }
        }
        Div({ classes("flex flex-col items-end gap-2") }) {
            Button({
                classes("flex items-center gap-2 rounded-md border border-border bg-card px-3 py-1.5 text-sm font-medium hover:bg-accent")
                testTag("wan-analysis")
            }) {
                Icon(Icon.WAVES, "h-4 w-4")
                Text("Start WAN analysis")
            }
            Div({ classes("flex items-center gap-1.5 text-sm text-muted-foreground") }) {
                Icon(Icon.SHIP, "h-4 w-4")
                Span({ testTag("vessel-count") }) { Text("${FleetStore.size} vessels") }
            }
            Div({ classes("flex items-center gap-4 text-sm") }) {
                StatusCount("bg-green-500", FleetStore.onlineCount(), "online", "count-online")
                StatusCount("bg-red-500", FleetStore.offlineCount(), "offline", "count-offline")
                StatusCount("bg-gray-400", FleetStore.unknownCount(), "unknown", "count-unknown")
            }
        }
    }
}

@Composable
private fun StatusCount(dot: String, count: Int, label: String, tag: String) {
    Div({ classes("flex items-center gap-1.5"); testTag(tag) }) {
        Span({ classes("inline-block h-2 w-2 rounded-full $dot") })
        Span({ classes("font-medium text-foreground") }) { Text(count.toString()) }
        Span({ classes("text-muted-foreground") }) { Text(label) }
    }
}

/**
 * A sortable column heading.
 *
 * Clicking the active column flips direction; clicking a new one starts ascending for the name and
 * descending for the numbers, which is the original's rule and the one that puts the interesting end
 * of each column first.
 */
@Composable
private fun SortHeader(label: String, key: SortKey, current: SortKey, ascending: Boolean) {
    val navigator = LocalNavigator.current
    val active = current == key
    Th({ classes("px-2 py-2 font-medium") }) {
        Button({
            classes(
                if (active) "flex items-center gap-1 uppercase tracking-wider text-foreground"
                else "flex items-center gap-1 uppercase tracking-wider text-muted-foreground hover:text-foreground",
            )
            testTag("sort-${key.param}")
            onClick {
                val nextAscending = if (active) !ascending else key == SortKey.NAME
                navigator.push(fleetUrl(key, nextAscending))
            }
        }) {
            Text(label)
            Icon(if (active && ascending) Icon.CHEVRON_UP else Icon.CHEVRON_DOWN, "h-3 w-3")
        }
    }
}

@Composable
private fun VesselRow(vessel: Vessel, open: (String) -> Unit) {
    val usage = FleetStore.usage(vessel)
    val device = FleetStore.deviceStatus(vessel)

    Tr({
        classes(rowClass(vessel))
        testTag("row")
        onClick { open("/vessels/${vessel.id}") }
    }) {
        Td({ classes("px-2 py-2") }) {
            Div({ classes("flex items-center gap-2") }) {
                if (vessel.emergency) {
                    Icon(Icon.ALERT_TRIANGLE, "h-5 w-5 shrink-0 text-red-600 bell-ringing")
                } else {
                    Icon(Icon.SHIP, "h-4 w-4 shrink-0 text-blue-600")
                }
                Div({ classes("flex min-w-0 flex-col") }) {
                    Link("/vessels/${vessel.id}", {
                        classes("truncate font-medium text-foreground hover:underline")
                        testTag("name")
                    }) { Text(vessel.name) }
                    Div({ classes("flex items-center gap-3 font-mono text-xs text-muted-foreground") }) {
                        Span { Text(vessel.serial) }
                        Span { Text(vessel.lanIp) }
                    }
                }
            }
        }

        Td({ classes("px-2 py-2") }) { StatusPill(device) }

        Td({ classes("px-2 py-2") }) {
            Div({ classes("flex flex-col gap-1") }) {
                UsageMeter("SL", usage, dotted = false)
                UsageMeter("5G", FleetStore.usage(vessel)?.let { UsageStatus(it.usedGb * 0.6, it.planGb) }, dotted = true)
            }
        }

        Td({ classes("px-2 py-2") }) { ProgressCell(vessel) }
        Td({ classes("px-2 py-2") }) { PriorityCell(vessel) }
        Td({ classes("px-2 py-2") }) { ActionsCell(vessel) }
    }
}

@Composable
private fun StatusPill(device: DeviceStatus?) {
    val classes = when {
        device == null -> "inline-flex rounded-md border border-gray-300 bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-600"
        device.online -> "inline-flex rounded-md border border-green-300 bg-green-50 px-2 py-0.5 text-xs font-medium text-green-700"
        else -> "inline-flex rounded-md border border-red-300 bg-red-50 px-2 py-0.5 text-xs font-medium text-red-700"
    }
    Span({ classes(classes); testTag("status") }) {
        Text(if (device == null) "Unknown" else if (device.online) "Online" else "Offline")
    }
}

/**
 * One labelled meter: Starlink on a solid track, cellular on a dotted one.
 *
 * The fill is coloured by how close the plan is to being spent, not by the raw percentage — the
 * original's reason, which is that going over costs money rather than merely being a big number.
 */
@Composable
private fun UsageMeter(label: String, usage: UsageStatus?, dotted: Boolean) {
    Div({ classes("flex items-center gap-2") }) {
        Span({ classes("w-5 shrink-0 text-[10px] font-medium text-muted-foreground") }) { Text(label) }
        Div({
            classes(
                if (dotted) "track-dotted h-1.5 w-28 shrink-0 rounded-full"
                else "h-1.5 w-28 shrink-0 rounded-full bg-gray-200",
            )
        }) {
            if (usage != null) {
                Div({
                    classes(
                        when (usage.level) {
                            "critical" -> "h-1.5 rounded-full bg-red-500"
                            "warning" -> "h-1.5 rounded-full bg-yellow-500"
                            else -> "h-1.5 rounded-full bg-green-500"
                        },
                    )
                    // The one number here that is genuinely continuous, so it is a style rather
                    // than a class: Tailwind cannot generate a utility per percentage.
                    style("width: ${(usage.fraction * 100).toInt()}%")
                })
            }
        }
        Span({ classes("w-14 shrink-0 text-right text-xs text-muted-foreground") }) {
            Text(usage?.let { formatGb(it.usedGb) } ?: "—")
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
        Stepper(Icon.CHEVRON_UP, "priority-up-${vessel.id}") {
            vessel.priority = (vessel.priority + 1).coerceAtMost(9)
        }
        Span({ classes("w-4 text-center text-sm font-medium"); testTag("priority-${vessel.id}") }) {
            Text(vessel.priority.toString())
        }
        Stepper(Icon.CHEVRON_DOWN, "priority-down-${vessel.id}") {
            vessel.priority = (vessel.priority - 1).coerceAtLeast(0)
        }
    }
}

@Composable
private fun Stepper(icon: Icon, tag: String, onPress: () -> Unit) {
    Button({
        classes("rounded p-0.5 text-muted-foreground hover:bg-accent hover:text-foreground")
        testTag(tag)
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
    Div({ classes("grid grid-cols-6 gap-0.5") }) {
        FlagAction(vessel, VesselFlag.CONSTRUCTION, Icon.CONSTRUCTION, "text-orange-600")
        FlagAction(vessel, VesselFlag.MAINTENANCE, Icon.WRENCH, "text-purple-600")
        FlagAction(vessel, VesselFlag.ALERT, Icon.BELL, "text-red-600")
        FlagAction(vessel, VesselFlag.EMERGENCY, Icon.ALERT_CIRCLE, "text-red-600")
        FlagAction(vessel, VesselFlag.DISABLED, Icon.EYE_OFF, "text-gray-600")
        Badged(Icon.NOTEBOOK_TEXT, "Notes", vessel.noteCount, "bg-blue-600")
        Badged(Icon.CLIPBOARD_LIST, "Tickets", vessel.openTicketCount, "bg-red-600")
        InertAction(Icon.MAP_PIN, "Show on map")
        InertAction(Icon.PHONE, "Contact")
        InertAction(Icon.SETTINGS, "Settings")
        InertAction(Icon.EXTERNAL_LINK, "Open in InControl")
    }
}

@Composable
private fun FlagAction(vessel: Vessel, flag: VesselFlag, icon: Icon, activeColour: String) {
    val on = FleetStore.isSet(vessel, flag)
    Button({
        classes(
            if (on) "rounded border border-border bg-accent p-1 $activeColour"
            else "rounded p-1 text-muted-foreground hover:bg-accent hover:text-foreground",
        )
        attr("title", flag.label)
        testTag("flag-${flag.name.lowercase()}-${vessel.id}")
        on("click", ListenerSpec(stopPropagation = true)) { FleetStore.toggle(vessel, flag) }
    }) { Icon(icon, "h-3.5 w-3.5") }
}

@Composable
private fun Badged(icon: Icon, title: String, count: Int, badgeColour: String) {
    Div({ classes("relative") }) {
        Button({
            classes("rounded p-1 text-muted-foreground hover:bg-accent hover:text-foreground")
            attr("title", title)
            on("click", ListenerSpec(stopPropagation = true)) { }
        }) { Icon(icon, "h-3.5 w-3.5") }
        if (count > 0) {
            Span({
                classes("absolute -right-1 -top-1 flex h-3.5 min-w-3.5 items-center justify-center rounded-full px-1 text-[9px] font-semibold text-white $badgeColour")
            }) { Text(count.toString()) }
        }
    }
}

@Composable
private fun InertAction(icon: Icon, title: String) {
    Button({
        classes("rounded p-1 text-muted-foreground hover:bg-accent hover:text-foreground")
        attr("title", title)
        on("click", ListenerSpec(stopPropagation = true)) { }
    }) { Icon(icon, "h-3.5 w-3.5") }
}

/**
 * The row's tint, straight from the original's `rowClass`.
 *
 * Whole class strings rather than assembled ones, and for the same reason the original writes them
 * that way: Tailwind reads its class names out of this file, and one built at runtime is not there
 * to be read.
 */
private fun rowClass(vessel: Vessel): String = when {
    vessel.emergency || vessel.alert -> "cursor-pointer border-b border-border bg-red-50 border-l-4 border-l-red-400 hover:bg-red-100"
    vessel.construction -> "cursor-pointer border-b border-border bg-orange-50 border-l-4 border-l-orange-400 hover:bg-orange-100"
    vessel.disabled -> "cursor-pointer border-b border-border bg-gray-200 opacity-70 hover:bg-gray-300"
    else -> "cursor-pointer border-b border-border hover:bg-gray-50"
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
