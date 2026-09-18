package jetlin.samples.issuetracker

import androidx.compose.runtime.Composable
import jetlin.html.Div
import jetlin.html.H1
import jetlin.html.Link
import jetlin.html.P
import jetlin.html.Span
import jetlin.html.Text
import jetlin.html.pathParam
import jetlin.html.queryParam

/** `/team/{key}/issues`, with `?tab=active` or `?tab=backlog` narrowing it the way the tabs say. */
@Composable
fun TeamIssuesPage() {
    val team = Workspace.team(pathParam("key")) ?: return Missing("No such team")
    val tab = queryParam("tab") ?: "all"
    val base = "/team/${team.key}/issues"

    val issues = Workspace.issuesOf(team).filter {
        when (tab) {
            "active" -> it.status.isActive
            "backlog" -> it.status == Status.Backlog
            else -> true
        }
    }

    PageHeader(actions = {
        LayoutSwitch(team, board = false)
        NewIssueButton(CreateRequest(team, if (tab == "backlog") Status.Backlog else Status.Todo))
    }) {
        Span({ classes("text-[13px] font-medium") }) { Text(team.name) }
        Div({ classes("flex items-center gap-1.5") }) {
            HeaderTab(base, "All issues", tab == "all")
            HeaderTab("$base?tab=active", "Active", tab == "active")
            HeaderTab("$base?tab=backlog", "Backlog", tab == "backlog")
        }
    }
    FilterBar()
    IssueList(issues, CreateRequest(team))
}

/** The home page: everything assigned to the visitor, across teams, still open or not. */
@Composable
fun MyIssuesPage() {
    val issues = Workspace.issues.filter { it.assignee == Workspace.me }
    PageHeader(actions = { NewIssueButton(CreateRequest()) }) {
        Span({ classes("text-[13px] font-medium") }) { Text("My issues") }
        Span({ classes("text-xs text-muted-foreground"); testTag("my-count") }) {
            Text("${issues.count { !it.status.isClosed }} open")
        }
    }
    FilterBar()
    IssueList(issues, CreateRequest(), emptyText = "Nothing is assigned to you")
}

@Composable
fun Missing(message: String) {
    Div({ classes("flex flex-1 flex-col items-center justify-center gap-3") }) {
        H1({ classes("text-base font-medium"); testTag("missing") }) { Text(message) }
        P({ classes("text-sm text-muted-foreground") }) { Text("It may have been removed, or the link is wrong.") }
        Link("/", { classes("text-sm text-accent hover:underline") }) { Text("Back to my issues") }
    }
}
