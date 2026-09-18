package jetlin.samples.issuetracker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.Input
import jetlin.html.Link
import jetlin.html.Span
import jetlin.html.Text
import jetlin.html.pathParam

/** `/team/{key}/board`: a column per status, and cards that can be dragged between them. */
@Composable
fun BoardPage() {
    val team = Workspace.team(pathParam("key")) ?: return Missing("No such team")
    val ui = LocalUi.current
    val issues = Workspace.issuesOf(team).filter(ui.filters::matches)

    PageHeader(actions = {
        LayoutSwitch(team, board = true)
        NewIssueButton(CreateRequest(team))
    }) {
        Span({ classes("text-[13px] font-medium") }) { Text(team.name) }
        Span({ classes("text-xs text-muted-foreground") }) { Text("Board") }
    }
    FilterBar()

    Div({ classes("flex min-h-0 flex-1 gap-3 overflow-x-auto p-3"); testTag("board") }) {
        val grouped = issues.groupBy { it.status }
        for (status in Status.entries) {
            key(status) {
                BoardColumn(team, status, grouped[status].orEmpty().sortedWith(ISSUE_ORDER))
            }
        }
    }
}

/**
 * One status column, and the drop target for it.
 *
 * Dragging is the browser's business and app.js does it, since the server cannot hear a
 * `dragover`. What the server needs is one fact at the end — this issue landed here — and the
 * hidden input is how it hears it: app.js writes the dropped issue's id into it and raises an
 * ordinary `input` event, which arrives through the same delegated listener as a keystroke would.
 */
@Composable
private fun BoardColumn(team: Team, status: Status, issues: List<Issue>) {
    val ui = LocalUi.current
    Div({
        classes("flex w-72 shrink-0 flex-col rounded-lg bg-panel/40 transition-colors")
        attr("data-drop-status", status.name)
        testTag("column")
    }) {
        Div({ classes("flex h-10 shrink-0 items-center gap-2 px-3 text-[13px]") }) {
            StatusIcon(status)
            Span({ classes("font-medium"); testTag("column-name") }) { Text(status.label) }
            Span({ classes("text-muted-foreground"); testTag("column-count") }) { Text("${issues.size}") }
            Button({
                classes("ml-auto flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-panel-hover hover:text-foreground")
                testTag("column-new-issue")
                attr("title", "New issue in ${status.label}")
                onClick { ui.openCreate(CreateRequest(team, status)) }
            }) { Glyph(Glyphs.PLUS, size = "size-3.5") }
        }
        Input({
            type("hidden")
            attr("data-drop-input", "")
            testTag("drop-input")
            onInput { id -> id.toIntOrNull()?.let(Workspace::issue)?.let { Workspace.setStatus(it, status) } }
        })
        Div({
            classes("flex min-h-24 flex-1 flex-col gap-2 overflow-y-auto px-2 pb-2 transition-opacity")
            // What app.js dims while a card from another column is held over this one.
            attr("data-drop-cards", "")
        }) {
            for (issue in issues) {
                key(issue.id) { BoardCard(issue) }
            }
        }
    }
}

@Composable
private fun BoardCard(issue: Issue) {
    Div({
        classes("group flex cursor-grab flex-col gap-2 rounded-lg border border-card bg-card p-3 shadow-sm shadow-black/30 hover:border-card-hover hover:bg-card-hover")
        attr("draggable", "true")
        attr("data-issue-id", "${issue.id}")
        testTag("card")
    }) {
        Div({ classes("flex items-center gap-2 text-xs text-muted-foreground") }) {
            Span({ testTag("card-identifier") }) { Text(issue.identifier) }
            Div({ classes("ml-auto") }) {
                AssigneeMenu("card-assignee-${issue.id}", issue.assignee, ICON_TRIGGER, { Workspace.setAssignee(issue, it) }, showLabel = false, panelClass = "right-0 w-56")
            }
        }
        Link("/issue/${issue.identifier}", {
            classes("text-[13px] leading-snug text-foreground hover:underline")
            attr("draggable", "false")
            testTag("card-title")
        }) { Text(issue.title) }
        Div({ classes("flex flex-wrap items-center gap-1") }) {
            PriorityMenu("card-priority-${issue.id}", issue.priority, "flex h-6 items-center rounded border border-border-strong px-1 hover:bg-panel-hover", { Workspace.setPriority(issue, it) }, showLabel = false)
            for (label in issue.labels) key(label.id) { LabelPill(label) }
        }
    }
}
