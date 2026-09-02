package jetlin.samples.vessels

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.H1
import jetlin.html.Input
import jetlin.html.Link
import jetlin.html.LocalNavigator
import jetlin.html.P
import jetlin.html.Span
import jetlin.html.Table
import jetlin.html.Tbody
import jetlin.html.Td
import jetlin.html.Text
import jetlin.html.Th
import jetlin.html.Thead
import jetlin.html.Tr
import jetlin.html.queryParam

/**
 * The fleet list.
 *
 * Everything that decides what is on screen — the search text, the sort column and direction, the
 * page — lives in the query string rather than in composition state. That is what the original does
 * not do, and it is the more useful thing to build: a filtered fleet is something an operator sends
 * to a colleague, and the back button then means what a browser's back button should mean.
 *
 * It is also the harder path, which is the point of the exercise. Reading it back is `queryParam`;
 * writing it is a navigation, so every keystroke, header click and page step goes through
 * `LocalNavigator` and re-resolves the route.
 */
@Composable
fun VesselsPage() = Shell {
    val navigator = LocalNavigator.current

    val query = queryParam("q").orEmpty()
    val sort = SortKey.from(queryParam("sort"))
    val ascending = queryParam("dir") != "desc"
    val page = queryParam("page")?.toIntOrNull() ?: 0

    val result = FleetStore.page(query, sort, ascending, page)

    fun go(
        newQuery: String = query,
        newSort: SortKey = sort,
        newAscending: Boolean = ascending,
        newPage: Int = page,
        replace: Boolean = false,
    ) {
        val url = fleetUrl(newQuery, newSort, newAscending, newPage)
        if (replace) navigator.replace(url) else navigator.push(url)
    }

    FleetHeader()

    Div({ classes("card") }) {
        Div({ classes("card-head") }) {
            Div({ classes("card-title") }) {
                Span({ classes("icon-ship") }) { Text("⚓") }
                Text("Fleet Status Overview")
            }
            Div({ classes("search") }) {
                Span({ classes("search-icon") }) { Text("⌕") }
                Input({
                    type("search")
                    classes("search-input")
                    testTag("search")
                    attr("placeholder", "Search vessels...")
                    // The value comes from the URL on every render rather than from a remembered
                    // field. That is what makes the back button restore the search box as well as
                    // the results; a field would keep whatever was last typed into it.
                    value(query)
                    // Replace rather than push: a history entry per keystroke would make going back
                    // an exercise in deleting one letter at a time.
                    onInput(debounceMs = 200) { typed -> go(newQuery = typed, newPage = 0, replace = true) }
                })
            }
        }

        Div({ classes("card-body") }) {
            if (result.total == 0) {
                P({ classes("empty"); testTag("empty") }) {
                    Text(
                        if (query.isBlank()) "No vessels found."
                        else "No vessels match “$query”.",
                    )
                }
            } else {
                Table({ classes("fleet") }) {
                    Thead {
                        Tr {
                            SortHeader("Vessel", SortKey.NAME, sort, ascending, ::go)
                            Th({ classes("col-status") }) { Text("Status") }
                            SortHeader("Data Usage", SortKey.USAGE, sort, ascending, ::go, "col-usage")
                            SortHeader("Progress", SortKey.PROGRESS, sort, ascending, ::go, "col-progress")
                            SortHeader("Priority", SortKey.PRIORITY, sort, ascending, ::go, "col-priority")
                            Th({ classes("col-actions") }) { Text("Actions") }
                        }
                    }
                    Tbody({ testTag("rows") }) {
                        result.vessels.forEach { vessel ->
                            // Keyed by identity, so re-sorting moves the existing rows rather than
                            // rewriting every cell in the table. The one assertion in this sample
                            // that no client-side framework could make is that this holds.
                            key(vessel.id) { VesselRow(vessel) }
                        }
                    }
                }
                Pager(result, ::go)
            }
        }
    }
}

/** The counts across the whole fleet, not the page: what the original puts beside the org name. */
@Composable
private fun FleetHeader() {
    Div({ classes("page-head") }) {
        Div({ classes("page-head-left") }) {
            Div({ classes("org-mark") }) { Text("⚓") }
            Div {
                H1 { Text(FleetStore.organizationName) }
                P({ classes("subtitle") }) { Text("Fleet Management Dashboard") }
            }
        }
        Div({ classes("page-head-right") }) {
            Div({ classes("fleet-count"); testTag("fleet-count") }) {
                Text("${FleetStore.size} vessels")
            }
            Div({ classes("status-counts") }) {
                StatusCount("online", FleetStore.onlineCount())
                StatusCount("offline", FleetStore.offlineCount())
                val unknown = FleetStore.unknownCount()
                if (unknown > 0) StatusCount("unknown", unknown)
            }
        }
    }
}

@Composable
private fun StatusCount(kind: String, count: Int) {
    Span({ classes("status-count status-$kind"); testTag("count-$kind") }) {
        Span({ classes("dot") }) { Text("") }
        Span({ classes("count-value") }) { Text("$count") }
        Span({ classes("count-label") }) { Text(kind) }
    }
}

/**
 * A column header that sorts.
 *
 * Clicking the active column flips direction; clicking another switches to it, ascending for the
 * name and descending for the numbers — which is what the original does, and what people expect
 * from a column of scores.
 */
@Composable
private fun SortHeader(
    label: String,
    key: SortKey,
    current: SortKey,
    ascending: Boolean,
    go: (String, SortKey, Boolean, Int, Boolean) -> Unit,
    extraClass: String = "",
) {
    val active = current == key
    Th({ classes(listOf("sortable", extraClass).filter { it.isNotEmpty() }.joinToString(" ")) }) {
        Button({
            classes(if (active) "sort-button active" else "sort-button")
            testTag("sort-${key.param}")
            onClick {
                val nextAscending = if (active) !ascending else key == SortKey.NAME
                go("", key, nextAscending, 0, false)
            }
        }) {
            Text(label)
            Span({ classes(if (active) "chevron active" else "chevron") }) {
                Text(if (active && ascending) "▲" else "▼")
            }
        }
    }
}

@Composable
private fun VesselRow(vessel: Vessel) {
    val usage = FleetStore.usage(vessel)
    val device = FleetStore.deviceStatus(vessel)

    Tr({ classes(rowClass(vessel)); testTag("row") }) {
        Td({ classes("cell-name") }) {
            Div({ classes("name-cell") }) {
                Span({ classes("status-dot status-${device.dotClass()}") }) { Text("") }
                Link("/vessels/${vessel.id}", { classes("vessel-link"); testTag("vessel-link") }) {
                    Text(vessel.name)
                }
                if (vessel.emergency) {
                    Span({ classes("badge badge-emergency") }) { Text("EMERGENCY") }
                }
            }
        }

        Td({ classes("cell-status") }) {
            Span({ classes("badge badge-${vessel.statusLabel.lowercase()}"); testTag("status") }) {
                Text(vessel.statusLabel)
            }
        }

        Td({ classes("cell-usage") }) {
            if (usage == null) {
                Span({ classes("muted") }) { Text("—") }
            } else {
                Div({ classes("usage") }) {
                    Div({ classes("usage-bar") }) {
                        Div({
                            classes("usage-fill usage-${usage.level}")
                            style("width: ${(usage.fraction * 100).toInt()}%")
                        }) { Text("") }
                    }
                    Span({ classes("usage-text"); testTag("usage") }) {
                        Text("${formatGb(usage.usedGb)} / ${formatGb(usage.planGb)}")
                    }
                }
            }
        }

        Td({ classes("cell-progress") }) { ProgressCell(vessel) }
        Td({ classes("cell-priority") }) { PriorityCell(vessel) }

        Td({ classes("cell-actions") }) {
            Div({ classes("actions") }) {
                VesselFlag.entries.forEach { flag ->
                    val on = FleetStore.isSet(vessel, flag)
                    Button({
                        classes(if (on) "action on action-${flag.name.lowercase()}" else "action")
                        testTag("flag-${flag.name.lowercase()}")
                        attr("title", flag.label)
                        onClick { FleetStore.toggle(vessel, flag) }
                    }) { Text(flag.glyph()) }
                }
                Badged("notes", "✎", vessel.noteCount)
                Badged("tickets", "☰", vessel.openTicketCount)
                Badged("files", "🖿", vessel.attachmentCount)
            }
        }
    }
}

/** An action carrying a count, the way the original hangs a number off the corner of a button. */
@Composable
private fun Badged(name: String, glyph: String, count: Int) {
    Button({
        classes(if (count > 0) "action has-count" else "action")
        testTag("action-$name")
        attr("title", name)
    }) {
        Text(glyph)
        if (count > 0) {
            Span({ classes("count-badge") }) { Text(if (count > 99) "99+" else "$count") }
        }
    }
}

/**
 * Progress, editable in place.
 *
 * Click to turn the number into a field, type, and it commits on blur or Enter — the original's
 * behaviour, and the reason this cell keeps composition state rather than putting the editing flag
 * in the URL. Whether a cell is mid-edit is not something anybody would share a link to.
 */
@Composable
private fun ProgressCell(vessel: Vessel) {
    var editing by remember { mutableStateOf(false) }

    if (!editing) {
        Button({
            classes("inline-value")
            testTag("progress")
            onClick { editing = true }
        }) {
            Span({ classes("ring ring-${if (vessel.progress >= 100) "done" else "part"}") }) { Text("") }
            Text("${vessel.progress}%")
        }
    } else {
        Input({
            type("number")
            classes("inline-input")
            testTag("progress-input")
            value(vessel.progress.toString())
            attr("min", "0")
            attr("max", "100")
            onInput(debounceMs = 150) { typed ->
                vessel.progress = typed.toIntOrNull()?.coerceIn(0, 100) ?: vessel.progress
            }
            onKeyDown { key -> if (key == "Enter" || key == "Escape") editing = false }
        })
    }
}

/** Priority, on the same pattern: a number worth changing without leaving the table. */
@Composable
private fun PriorityCell(vessel: Vessel) {
    Div({ classes("stepper") }) {
        Button({
            classes("step")
            testTag("priority-down")
            attr("title", "Lower priority")
            onClick { vessel.priority = (vessel.priority - 1).coerceAtLeast(0) }
        }) { Text("−") }
        Span({ classes("priority-value"); testTag("priority") }) { Text("${vessel.priority}") }
        Button({
            classes("step")
            testTag("priority-up")
            attr("title", "Raise priority")
            onClick { vessel.priority = (vessel.priority + 1).coerceAtMost(9) }
        }) { Text("+") }
    }
}

/**
 * Paging controls.
 *
 * The original virtualizes instead — it holds every vessel and renders the visible window. That
 * cannot be done from a server that has no idea where the scroll bar is, so this pages. See
 * FINDINGS.md.
 */
@Composable
private fun Pager(
    result: VesselPage,
    go: (String, SortKey, Boolean, Int, Boolean) -> Unit,
) {
    Div({ classes("pager") }) {
        Span({ classes("pager-text"); testTag("pager-text") }) {
            Text("${result.firstShown}–${result.lastShown} of ${result.total}")
        }
        Div({ classes("pager-buttons") }) {
            Button({
                classes("pager-button")
                testTag("prev")
                disabled(result.page == 0)
                onClick { go("", SortKey.PRIORITY, true, result.page - 1, false) }
            }) { Text("Previous") }
            Span({ classes("pager-page"); testTag("page-of") }) {
                Text("Page ${result.page + 1} of ${result.pageCount}")
            }
            Button({
                classes("pager-button")
                testTag("next")
                disabled(result.page >= result.pageCount - 1)
                onClick { go("", SortKey.PRIORITY, true, result.page + 1, false) }
            }) { Text("Next") }
        }
    }
}

private fun rowClass(vessel: Vessel): String = when {
    vessel.emergency || vessel.alert -> "row row-alert"
    vessel.construction -> "row row-construction"
    vessel.disabled -> "row row-disabled"
    else -> "row"
}

private fun DeviceStatus?.dotClass(): String = when {
    this == null -> "unknown"
    online -> "online"
    else -> "offline"
}

private fun VesselFlag.glyph(): String = when (this) {
    VesselFlag.CONSTRUCTION -> "⚒"
    VesselFlag.MAINTENANCE -> "⚙"
    VesselFlag.ALERT -> "⚠"
    VesselFlag.EMERGENCY -> "!"
    VesselFlag.DISABLED -> "⊘"
}

/**
 * Builds the fleet URL from the state that belongs in it.
 *
 * Written out by hand because there is nothing in the framework for "the current URL with one
 * parameter changed", which is the only operation a page like this ever wants. See FINDINGS.md.
 */
internal fun fleetUrl(query: String, sort: SortKey, ascending: Boolean, page: Int): String {
    val parts = buildList {
        if (query.isNotBlank()) add("q=$query")
        if (sort != SortKey.PRIORITY) add("sort=${sort.param}")
        if (!ascending) add("dir=desc")
        if (page > 0) add("page=$page")
    }
    return if (parts.isEmpty()) "/" else "/?" + parts.joinToString("&")
}
