package jetlin.samples.issuetracker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.Header
import jetlin.html.Link
import jetlin.html.P
import jetlin.html.Section
import jetlin.html.Span
import jetlin.html.Text

/** The bar across the top of a page: where you are on the left, what you can do on the right. */
@Composable
fun PageHeader(actions: @Composable () -> Unit = {}, title: @Composable () -> Unit) {
    Header({ classes("flex h-11 shrink-0 items-center gap-3 border-b border-border px-4") }) {
        title()
        Div({ classes("ml-auto flex items-center gap-2") }) { actions() }
    }
}

@Composable
fun HeaderTab(href: String, label: String, active: Boolean) {
    Link(href, {
        classes(
            if (active) "rounded-md border border-border-strong bg-panel-hover px-2.5 py-1 text-xs text-foreground"
            else "rounded-md border border-border px-2.5 py-1 text-xs text-muted-foreground hover:bg-panel hover:text-foreground",
        )
        testTag("tab")
    }) { Text(label) }
}

/** List and board side by side as a two-way switch, the way trackers put the layout choice. */
@Composable
fun LayoutSwitch(team: Team, board: Boolean) {
    Div({ classes("flex items-center rounded-md border border-border p-0.5") }) {
        Link("/team/${team.key}/issues", {
            classes(if (!board) "flex size-6 items-center justify-center rounded bg-panel-hover text-foreground" else "flex size-6 items-center justify-center rounded text-muted-foreground hover:text-foreground")
            attr("title", "List")
            testTag("layout-list")
        }) { Glyph(Glyphs.LIST, size = "size-3.5") }
        Link("/team/${team.key}/board", {
            classes(if (board) "flex size-6 items-center justify-center rounded bg-panel-hover text-foreground" else "flex size-6 items-center justify-center rounded text-muted-foreground hover:text-foreground")
            attr("title", "Board")
            testTag("layout-board")
        }) { Glyph(Glyphs.BOARD, size = "size-3.5") }
    }
}

@Composable
fun NewIssueButton(request: CreateRequest) {
    val ui = LocalUi.current
    Button({
        classes("flex h-7 items-center gap-1.5 rounded-md bg-accent px-2.5 text-xs font-medium text-accent-foreground hover:bg-accent-hover")
        testTag("header-new-issue")
        onClick { ui.openCreate(request) }
    }) {
        Glyph(Glyphs.PLUS, size = "size-3.5")
        Text("New issue")
    }
}

// ---------------------------------------------------------------------------------------- filters

/**
 * One chip per property, each a multi-select. Held in the session's [UiState], so the list and the
 * board narrow the same way and switching between them keeps the filter.
 */
@Composable
fun FilterBar() {
    val ui = LocalUi.current
    val filters = ui.filters
    Div({ classes("flex h-10 shrink-0 items-center gap-1.5 border-b border-border px-4") }) {
        Span({ classes("mr-1 text-muted-foreground") }) { Glyph(Glyphs.FILTER, size = "size-3.5") }

        FilterChip("filter-status", "Status", filters.statuses.size) {
            for (status in Status.entries) {
                MenuItem("filter-option", selected = status in filters.statuses, onPick = {
                    ui.filters = ui.filters.copy(statuses = ui.filters.statuses.toggle(status))
                }) { StatusIcon(status); Text(status.label) }
            }
        }
        FilterChip("filter-priority", "Priority", filters.priorities.size) {
            for (priority in Priority.entries) {
                MenuItem("filter-option", selected = priority in filters.priorities, onPick = {
                    ui.filters = ui.filters.copy(priorities = ui.filters.priorities.toggle(priority))
                }) { PriorityIcon(priority); Text(priority.label) }
            }
        }
        FilterChip("filter-assignee", "Assignee", filters.assignees.size) {
            MenuItem("filter-option", selected = Filters.NO_ASSIGNEE in filters.assignees, onPick = {
                ui.filters = ui.filters.copy(assignees = ui.filters.assignees.toggle(Filters.NO_ASSIGNEE))
            }) { Avatar(null); Text("No assignee") }
            for (user in Workspace.users) {
                MenuItem("filter-option", selected = user.id in filters.assignees, onPick = {
                    ui.filters = ui.filters.copy(assignees = ui.filters.assignees.toggle(user.id))
                }) { Avatar(user); Text(user.name) }
            }
        }
        FilterChip("filter-label", "Label", filters.labels.size) {
            for (label in Workspace.labels) {
                MenuItem("filter-option", selected = label.id in filters.labels, onPick = {
                    ui.filters = ui.filters.copy(labels = ui.filters.labels.toggle(label.id))
                }) {
                    Span({ attr("class", "size-2.5 rounded-full ${label.dot}") })
                    Text(label.name)
                }
            }
        }

        if (!filters.isEmpty) {
            Button({
                classes("ml-1 text-xs text-muted-foreground hover:text-foreground")
                testTag("clear-filters")
                onClick { ui.filters = Filters() }
            }) { Text("Clear") }
        }
    }
}

@Composable
private fun FilterChip(id: String, label: String, count: Int, content: @Composable () -> Unit) {
    Dropdown(
        id = id,
        tag = id,
        triggerClass = if (count > 0) "flex h-6 items-center gap-1.5 rounded-md border border-accent/50 bg-accent/15 px-2 text-xs text-foreground"
        else "flex h-6 items-center gap-1.5 rounded-md border border-border px-2 text-xs text-muted-foreground hover:bg-panel hover:text-foreground",
        trigger = {
            Text(label)
            if (count > 0) Span({ classes("rounded bg-accent px-1 text-[10px] font-semibold text-accent-foreground") }) { Text("$count") }
        },
    ) { content() }
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

// ------------------------------------------------------------------------------------------ list

/** Most pressing first, then most recently created — the order within a status group. */
val ISSUE_ORDER: Comparator<Issue> = compareBy<Issue> { it.priority.ordinal }.thenByDescending { it.id }

/**
 * Issues grouped by status, in workflow order, under headers that collapse.
 *
 * Every row and every group is keyed by identity, so an issue changing priority moves its row within
 * the group rather than rewriting every row after it. Changing status is a removal from one group and
 * an insertion into another — a key only identifies a node among its siblings — but the rows around
 * it are still left alone.
 */
@Composable
fun IssueList(issues: List<Issue>, newIn: CreateRequest, emptyText: String = "No issues") {
    val ui = LocalUi.current
    val visible = issues.filter(ui.filters::matches)

    Div({ classes("min-h-0 flex-1 overflow-y-auto pb-24"); testTag("issue-list") }) {
        if (visible.isEmpty()) {
            Div({ classes("flex h-64 flex-col items-center justify-center gap-2 text-muted-foreground") }) {
                P({ classes("text-sm"); testTag("empty") }) {
                    Text(if (issues.isEmpty()) emptyText else "No issues match these filters")
                }
            }
        }
        val grouped = visible.groupBy { it.status }
        for (status in Status.entries) {
            val group = grouped[status] ?: continue
            key(status) {
                StatusGroup(status, group.sortedWith(ISSUE_ORDER), CreateRequest(newIn.team, status, newIn.project))
            }
        }
    }
}

@Composable
private fun StatusGroup(status: Status, issues: List<Issue>, newIn: CreateRequest) {
    val ui = LocalUi.current
    val collapsed = status in ui.collapsed
    Section({ testTag("group") }) {
        Div({ classes("sticky top-0 z-10 flex h-9 items-center gap-2 border-b border-border bg-panel px-4 text-[13px]"); testTag("group-header") }) {
            Button({
                classes(if (collapsed) "flex items-center gap-2 text-foreground" else "flex items-center gap-2 text-foreground [&>svg:first-child]:rotate-90")
                testTag("group-toggle")
                onClick { ui.collapsed = if (collapsed) ui.collapsed - status else ui.collapsed + status }
            }) {
                Glyph(Glyphs.CHEVRON, size = "size-3 text-subtle-foreground transition-transform")
                StatusIcon(status)
                Span({ classes("font-medium"); testTag("group-name") }) { Text(status.label) }
                Span({ classes("text-muted-foreground"); testTag("group-count") }) { Text("${issues.size}") }
            }
            Button({
                classes("ml-auto flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-panel-hover hover:text-foreground")
                testTag("group-new-issue")
                attr("title", "New issue in ${status.label}")
                onClick { ui.openCreate(newIn) }
            }) { Glyph(Glyphs.PLUS, size = "size-3.5") }
        }
        if (!collapsed) {
            for (issue in issues) {
                key(issue.id) { IssueRow(issue) }
            }
        }
    }
}

@Composable
fun IssueRow(issue: Issue) {
    Div({ classes("flex h-11 items-center gap-1 border-b border-border/60 pl-3 pr-4 hover:bg-panel"); testTag("issue-row") }) {
        PriorityMenu("row-priority-${issue.id}", issue.priority, ICON_TRIGGER, { Workspace.setPriority(issue, it) }, showLabel = false)
        Span({ classes("w-16 shrink-0 whitespace-nowrap text-[13px] text-muted-foreground"); testTag("issue-identifier") }) { Text(issue.identifier) }
        StatusMenu("row-status-${issue.id}", issue.status, ICON_TRIGGER, { Workspace.setStatus(issue, it) }, showLabel = false)
        Link("/issue/${issue.identifier}", {
            classes("ml-1 min-w-0 flex-1 truncate text-[13px] text-foreground")
            testTag("issue-title")
        }) { Text(issue.title) }
        Div({ classes("hidden items-center gap-1 lg:flex") }) {
            for (label in issue.labels) key(label.id) { LabelPill(label) }
        }
        issue.project?.let { project ->
            Link("/project/${project.id}", {
                classes("hidden items-center gap-1.5 rounded-full border border-border-strong px-2 py-0.5 text-xs text-muted-foreground hover:text-foreground xl:flex")
            }) {
                Glyph(Glyphs.PROJECT, size = "size-3")
                Text(project.name)
            }
        }
        Span({ classes("w-14 shrink-0 text-right text-xs text-subtle-foreground") }) { Text(shortDate(issue.createdAt)) }
        AssigneeMenu("row-assignee-${issue.id}", issue.assignee, ICON_TRIGGER, { Workspace.setAssignee(issue, it) }, showLabel = false, panelClass = "right-0 w-56")
    }
}
