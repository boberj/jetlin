package jetlin.samples.issuetracker

import androidx.compose.runtime.Composable
import jetlin.html.AttrsScope
import jetlin.html.Button
import jetlin.html.Circle
import jetlin.html.Div
import jetlin.html.Path
import jetlin.html.Rect
import jetlin.html.Span
import jetlin.html.Svg
import jetlin.html.Text
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------------------------------- icons

/**
 * The workflow glyph: an outline that fills as the issue moves along.
 *
 * Drawn on the server like everything else, so a status change is a handful of attribute patches
 * on an existing drawing rather than a new image.
 */
@Composable
fun StatusIcon(status: Status, size: String = "size-3.5") {
    val tone = when (status) {
        Status.Backlog -> "text-status-backlog"
        Status.Todo -> "text-status-todo"
        Status.InProgress -> "text-status-progress"
        Status.InReview -> "text-status-review"
        Status.Done -> "text-status-done"
        Status.Canceled -> "text-status-canceled"
    }
    Svg({
        attr("class", "$size shrink-0 $tone")
        attr("viewBox", "0 0 14 14")
        attr("aria-label", status.label)
    }) {
        when (status) {
            Status.Backlog -> Circle({
                attr("cx", "7"); attr("cy", "7"); attr("r", "6"); attr("fill", "none")
                attr("stroke", "currentColor"); attr("stroke-width", "1.5"); attr("stroke-dasharray", "1.4 1.6")
            })
            Status.Todo -> Circle({
                attr("cx", "7"); attr("cy", "7"); attr("r", "6"); attr("fill", "none")
                attr("stroke", "currentColor"); attr("stroke-width", "1.5")
            })
            Status.InProgress, Status.InReview -> {
                Circle({
                    attr("cx", "7"); attr("cy", "7"); attr("r", "6"); attr("fill", "none")
                    attr("stroke", "currentColor"); attr("stroke-width", "1.5")
                })
                // A pie wedge from twelve o'clock: half for in progress, three quarters for review.
                Path({
                    attr("fill", "currentColor")
                    attr("d", if (status == Status.InProgress) "M7 3.5 A3.5 3.5 0 0 1 7 10.5 Z" else "M7 3.5 A3.5 3.5 0 1 1 3.5 7 L7 7 Z")
                })
            }
            Status.Done -> {
                Circle({ attr("cx", "7"); attr("cy", "7"); attr("r", "7"); attr("fill", "currentColor") })
                Path({
                    attr("d", "M4.2 7.2 6.1 9 9.8 5.2"); attr("fill", "none"); attr("stroke", "#0f1011")
                    attr("stroke-width", "1.6"); attr("stroke-linecap", "round"); attr("stroke-linejoin", "round")
                })
            }
            Status.Canceled -> {
                Circle({ attr("cx", "7"); attr("cy", "7"); attr("r", "7"); attr("fill", "currentColor") })
                Path({
                    attr("d", "M4.8 4.8 9.2 9.2 M9.2 4.8 4.8 9.2"); attr("fill", "none"); attr("stroke", "#0f1011")
                    attr("stroke-width", "1.6"); attr("stroke-linecap", "round")
                })
            }
        }
    }
}

/** Three bars filled to the priority, an alarm for urgent, and a quiet dotted line for none. */
@Composable
fun PriorityIcon(priority: Priority, size: String = "size-3.5") {
    val tone = if (priority == Priority.Urgent) "text-priority-urgent" else "text-muted-foreground"
    Svg({
        attr("class", "$size shrink-0 $tone")
        attr("viewBox", "0 0 14 14")
        attr("aria-label", priority.label)
    }) {
        when (priority) {
            Priority.Urgent -> {
                Rect({ attr("x", "1"); attr("y", "1"); attr("width", "12"); attr("height", "12"); attr("rx", "3"); attr("fill", "currentColor") })
                Rect({ attr("x", "6.25"); attr("y", "3.5"); attr("width", "1.5"); attr("height", "4.5"); attr("rx", ".5"); attr("fill", "#0f1011") })
                Rect({ attr("x", "6.25"); attr("y", "9"); attr("width", "1.5"); attr("height", "1.5"); attr("rx", ".5"); attr("fill", "#0f1011") })
            }
            Priority.None -> for (x in listOf("1.5", "6", "10.5")) {
                Rect({ attr("x", x); attr("y", "6.25"); attr("width", "2"); attr("height", "1.5"); attr("rx", ".5"); attr("fill", "currentColor") })
            }
            else -> {
                val filled = when (priority) {
                    Priority.High -> 3
                    Priority.Medium -> 2
                    else -> 1
                }
                listOf("8" to "4", "5" to "7", "2" to "10").forEachIndexed { index, (y, height) ->
                    Rect({
                        attr("x", "${1.5 + index * 4}"); attr("y", y); attr("width", "3"); attr("height", height); attr("rx", ".75")
                        attr("fill", "currentColor")
                        attr("fill-opacity", if (index < filled) "1" else "0.3")
                    })
                }
            }
        }
    }
}

/** A person's initials on their color, or a dashed ring for nobody. */
@Composable
fun Avatar(user: User?, size: String = "size-5 text-[9px]") {
    if (user == null) {
        Span({
            attr("class", "$size inline-flex shrink-0 items-center justify-center rounded-full border border-dashed border-subtle-foreground")
            attr("title", "Unassigned")
        })
    } else {
        Span({
            attr("class", "$size ${user.avatar} inline-flex shrink-0 items-center justify-center rounded-full font-semibold text-white")
            attr("title", user.name)
        }) { Text(user.initials) }
    }
}

@Composable
fun LabelPill(label: Label) {
    Span({ classes("inline-flex items-center gap-1.5 rounded-full border border-border-strong px-2 py-0.5 text-xs text-muted-foreground") }) {
        Span({ attr("class", "size-2 rounded-full ${label.dot}") })
        Text(label.name)
    }
}

@Composable
fun Kbd(key: String) {
    Span({ classes("rounded border border-border-strong bg-panel px-1 py-px text-[10px] leading-none text-muted-foreground") }) {
        Text(key)
    }
}

/** Line-art icons for chrome, drawn from a single path each. */
@Composable
fun Glyph(d: String, size: String = "size-4") {
    Svg({
        attr("class", "$size shrink-0")
        attr("viewBox", "0 0 16 16")
        attr("fill", "none")
        attr("stroke", "currentColor")
        attr("stroke-width", "1.4")
        attr("stroke-linecap", "round")
        attr("stroke-linejoin", "round")
    }) {
        Path({ attr("d", d) })
    }
}

object Glyphs {
    const val SEARCH = "M7 12.5a5.5 5.5 0 1 0 0-11 5.5 5.5 0 0 0 0 11ZM11 11l3.5 3.5"
    const val COMPOSE = "M11.5 2.5l2 2L7 11H5V9l6.5-6.5ZM13 9v4.5H2.5V3H7"
    const val INBOX = "M2 9h3.5l1 2h3l1-2H14M2 9l2-6h8l2 6v4.5H2V9Z"
    const val LIST = "M5.5 4H14M5.5 8H14M5.5 12H14M2 4h.01M2 8h.01M2 12h.01"
    const val BOARD = "M2 2.5h3.5v11H2zM6.25 2.5h3.5v7h-3.5zM10.5 2.5H14v9h-3.5z"
    const val PROJECT = "M2 4.5 8 1.5l6 3-6 3-6-3ZM2 8l6 3 6-3M2 11.5l6 3 6-3"
    const val FILTER = "M2 3h12M4.5 8h7M7 13h2"
    const val PLUS = "M8 3v10M3 8h10"
    const val CHEVRON = "M6 4l4 4-4 4"
    const val CHECK = "M3.5 8.5l3 3 6-7"
    const val CLOSE = "M4 4l8 8M12 4l-8 8"
    const val ARROW = "M3 8h10M9 4l4 4-4 4"
}

// ---------------------------------------------------------------------------------------- menus

/**
 * A button that opens a popover, with the popover's open state held on the server.
 *
 * Opening a menu is a round trip, because the server owns which menu is open. In return, opening
 * one closes every other menu, and Escape handling and tests work without extra code. A transparent
 * layer behind the popover catches the click that dismisses it.
 *
 * [id] has to be unique on the page, since only one menu is open at a time.
 */
@Composable
fun Dropdown(
    id: String,
    tag: String,
    triggerClass: String,
    trigger: @Composable () -> Unit,
    panelClass: String = "left-0 w-56",
    title: String? = null,
    content: @Composable (close: () -> Unit) -> Unit,
) {
    val ui = LocalUi.current
    val open = ui.openMenu == id
    Div({ classes("relative") }) {
        Button({
            classes(triggerClass)
            // Menus sit inside forms too, where a button with no type is a submit button.
            type("button")
            testTag(tag)
            title?.let { attr("title", it) }
            attr("aria-expanded", if (open) "true" else "false")
            onClick { ui.toggleMenu(id) }
        }) { trigger() }
        if (open) {
            Div({ classes("fixed inset-0 z-40 cursor-default"); onClick { ui.openMenu = null } })
            Div({
                attr("class", "absolute top-full z-50 mt-1 max-h-80 overflow-y-auto rounded-lg border border-border-strong bg-popover p-1 shadow-2xl shadow-black/60 $panelClass")
                testTag("menu")
            }) {
                content { ui.openMenu = null }
            }
        }
    }
}

@Composable
fun MenuItem(
    tag: String,
    selected: Boolean = false,
    onPick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Button({
        classes("flex w-full items-center gap-2.5 rounded-md px-2 py-1.5 text-left text-[13px] text-foreground hover:bg-panel-hover")
        type("button")
        testTag(tag)
        onClick(onPick)
    }) {
        content()
        Span({ classes("ml-auto text-accent") }) {
            if (selected) Glyph(Glyphs.CHECK, size = "size-3.5")
        }
    }
}

@Composable
fun MenuHeading(text: String) {
    Div({ classes("px-2 pb-1 pt-1.5 text-[11px] font-medium text-subtle-foreground") }) { Text(text) }
}

/** The pill-shaped trigger the property menus use in the detail panel and the create modal. */
const val PROPERTY_TRIGGER: String =
    "flex h-7 items-center gap-2 rounded-md px-2 text-[13px] text-foreground hover:bg-panel-hover"

/** A bare icon trigger, for the dense rows of the issue list. */
const val ICON_TRIGGER: String =
    "flex size-6 items-center justify-center rounded hover:bg-panel-hover"

@Composable
fun StatusMenu(id: String, current: Status, triggerClass: String, onPick: (Status) -> Unit, showLabel: Boolean, panelClass: String = "left-0 w-56") {
    Dropdown(id, tag = "status-trigger", triggerClass = triggerClass, panelClass = panelClass, title = "Change status", trigger = {
        StatusIcon(current)
        if (showLabel) Text(current.label)
    }) { close ->
        for (status in Status.entries) {
            MenuItem("status-option", selected = status == current, onPick = { onPick(status); close() }) {
                StatusIcon(status)
                Text(status.label)
            }
        }
    }
}

@Composable
fun PriorityMenu(id: String, current: Priority, triggerClass: String, onPick: (Priority) -> Unit, showLabel: Boolean, panelClass: String = "left-0 w-56") {
    Dropdown(id, tag = "priority-trigger", triggerClass = triggerClass, panelClass = panelClass, title = "Change priority", trigger = {
        PriorityIcon(current)
        if (showLabel) Text(current.label)
    }) { close ->
        for (priority in Priority.entries) {
            MenuItem("priority-option", selected = priority == current, onPick = { onPick(priority); close() }) {
                PriorityIcon(priority)
                Text(priority.label)
            }
        }
    }
}

@Composable
fun AssigneeMenu(
    id: String,
    current: User?,
    triggerClass: String,
    onPick: (User?) -> Unit,
    showLabel: Boolean,
    panelClass: String = "left-0 w-56",
) {
    Dropdown(id, tag = "assignee-trigger", triggerClass = triggerClass, panelClass = panelClass, title = "Assign", trigger = {
        Avatar(current)
        if (showLabel) Text(current?.name ?: "Unassigned")
    }) { close ->
        MenuItem("assignee-option", selected = current == null, onPick = { onPick(null); close() }) {
            Avatar(null)
            Text("No assignee")
        }
        for (user in Workspace.users) {
            MenuItem("assignee-option", selected = user == current, onPick = { onPick(user); close() }) {
                Avatar(user)
                Text(if (user == Workspace.me) "${user.name} (you)" else user.name)
            }
        }
    }
}

@Composable
fun ProjectMenu(id: String, current: Project?, triggerClass: String, onPick: (Project?) -> Unit, panelClass: String = "left-0 w-56") {
    Dropdown(id, tag = "project-trigger", triggerClass = triggerClass, panelClass = panelClass, title = "Set project", trigger = {
        Glyph(Glyphs.PROJECT, size = "size-3.5")
        Text(current?.name ?: "No project")
    }) { close ->
        MenuItem("project-option", selected = current == null, onPick = { onPick(null); close() }) {
            Text("No project")
        }
        for (project in Workspace.projects) {
            MenuItem("project-option", selected = project == current, onPick = { onPick(project); close() }) {
                Glyph(Glyphs.PROJECT, size = "size-3.5")
                Text(project.name)
            }
        }
    }
}

/** Stays open while labels are toggled, since picking several is the usual case. */
@Composable
fun LabelsMenu(id: String, current: List<Label>, triggerClass: String, panelClass: String = "left-0 w-56", onToggle: (Label) -> Unit) {
    Dropdown(id, tag = "labels-trigger", triggerClass = triggerClass, panelClass = panelClass, title = "Change labels", trigger = {
        if (current.isEmpty()) {
            Text("Add label")
        } else {
            for (label in current) Span({ attr("class", "size-2 rounded-full ${label.dot}") })
            Text(current.joinToString(", ") { it.name })
        }
    }) {
        for (label in Workspace.labels) {
            MenuItem("label-option", selected = label in current, onPick = { onToggle(label) }) {
                Span({ attr("class", "size-2.5 rounded-full ${label.dot}") })
                Text(label.name)
            }
        }
    }
}

// --------------------------------------------------------------------------------------- overlay

/**
 * A dialog over the page, shown by being composed and dismissed by not being.
 *
 * Not a `<dialog>`: showing one modally is a DOM method call, which Jetlin deliberately has no way
 * to make. Escape is app.js clicking the session's dismiss button, and focus is its autofocus.
 */
@Composable
fun Modal(tag: String, onDismiss: () -> Unit, panelClass: String, content: @Composable () -> Unit) {
    Div({ classes("fixed inset-0 z-50 flex items-start justify-center px-4 pt-[12vh]") }) {
        Div({ classes("absolute inset-0 bg-black/60"); onClick(onDismiss) })
        Div({
            attr("class", "relative w-full rounded-xl border border-border-strong bg-popover shadow-2xl shadow-black/60 $panelClass")
            attr("role", "dialog")
            testTag(tag)
        }) { content() }
    }
}

/**
 * Marks a field for app.js to focus when it arrives. `autofocus` only applies to the page as first
 * loaded, not to an element a patch inserts later.
 */
fun AttrsScope.autofocus() {
    attr("data-autofocus", "")
}

// ------------------------------------------------------------------------------------------ time

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)

fun shortDate(at: Instant): String = SHORT_DATE.format(at.atZone(ZoneId.systemDefault()))

fun shortDate(date: LocalDate): String = SHORT_DATE.format(date)

/**
 * "3h ago", computed when the composable runs.
 *
 * The text doesn't update on its own, because nothing here reads a clock as state. It's as current
 * as the last recomposition of its row, which in practice is the last time the issue changed.
 */
fun ago(at: Instant, now: Instant = Instant.now()): String {
    val elapsed = Duration.between(at, now)
    return when {
        elapsed.toMinutes() < 1 -> "just now"
        elapsed.toHours() < 1 -> "${elapsed.toMinutes()}m ago"
        elapsed.toDays() < 1 -> "${elapsed.toHours()}h ago"
        elapsed.toDays() < 30 -> "${elapsed.toDays()}d ago"
        else -> shortDate(at)
    }
}
