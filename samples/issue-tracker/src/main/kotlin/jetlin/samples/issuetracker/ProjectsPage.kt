package jetlin.samples.issuetracker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import jetlin.html.Div
import jetlin.html.Link
import jetlin.html.P
import jetlin.html.Span
import jetlin.html.Text
import jetlin.html.pathParam

@Composable
fun ProjectsPage() {
    PageHeader(actions = { NewIssueButton(CreateRequest()) }) {
        Span({ classes("text-[13px] font-medium") }) { Text("Projects") }
    }
    Div({ classes("min-h-0 flex-1 overflow-y-auto") }) {
        Div({ classes("grid h-9 grid-cols-[1fr_8rem_8rem_7rem_10rem] items-center gap-4 border-b border-border px-6 text-xs text-muted-foreground") }) {
            Span { Text("Name") }
            Span { Text("Status") }
            Span { Text("Lead") }
            Span { Text("Target") }
            Span { Text("Progress") }
        }
        for (project in Workspace.projects) {
            key(project.id) { ProjectRow(project) }
        }
    }
}

@Composable
private fun ProjectRow(project: Project) {
    // Read from the shared issues, so closing an issue anywhere moves this bar for everyone.
    val (done, total) = Workspace.progress(project)
    val percent = if (total == 0) 0 else done * 100 / total

    Link("/project/${project.id}", {
        classes("grid h-14 grid-cols-[1fr_8rem_8rem_7rem_10rem] items-center gap-4 border-b border-border/60 px-6 hover:bg-panel")
        testTag("project-row")
    }) {
        Div({ classes("flex min-w-0 items-center gap-3") }) {
            Span({ classes("flex size-7 shrink-0 items-center justify-center rounded-md bg-panel-hover text-muted-foreground") }) {
                Glyph(Glyphs.PROJECT, size = "size-3.5")
            }
            Div({ classes("min-w-0") }) {
                P({ classes("truncate text-[13px] font-medium"); testTag("project-name") }) { Text(project.name) }
                P({ classes("truncate text-xs text-muted-foreground") }) { Text(project.summary) }
            }
        }
        ProjectStatusBadge(project.status)
        Div({ classes("flex items-center gap-2 text-[13px]") }) {
            Avatar(project.lead)
            Span({ classes("truncate") }) { Text(project.lead.name) }
        }
        Span({ classes("text-[13px] text-muted-foreground") }) { Text(project.target?.let(::shortDate) ?: "—") }
        ProgressBar(percent, "$done / $total")
    }
}

@Composable
fun ProjectStatusBadge(status: ProjectStatus) {
    Span({
        classes(
            when (status) {
                ProjectStatus.Planned -> "w-fit rounded-full border border-border-strong px-2 py-0.5 text-xs text-muted-foreground"
                ProjectStatus.Started -> "w-fit rounded-full border border-status-progress/40 bg-status-progress/10 px-2 py-0.5 text-xs text-status-progress"
                ProjectStatus.Paused -> "w-fit rounded-full border border-border-strong bg-panel-hover px-2 py-0.5 text-xs text-muted-foreground"
                ProjectStatus.Completed -> "w-fit rounded-full border border-status-done/40 bg-status-done/10 px-2 py-0.5 text-xs text-status-done"
            },
        )
    }) { Text(status.label) }
}

/**
 * The width is inline style rather than a class: a percentage has a hundred possible values, and
 * Tailwind only generates classes it finds written out in the source.
 */
@Composable
private fun ProgressBar(percent: Int, caption: String) {
    Div({ classes("flex items-center gap-2") }) {
        Div({ classes("h-1.5 flex-1 overflow-hidden rounded-full bg-panel-hover") }) {
            Div({ classes("h-full rounded-full bg-accent"); style("width: $percent%") })
        }
        Span({ classes("w-12 text-right text-xs text-muted-foreground"); testTag("project-progress") }) { Text(caption) }
    }
}

/** `/project/{id}`: the project's summary over the same grouped list the teams use. */
@Composable
fun ProjectPage() {
    val project = Workspace.project(pathParam("id")) ?: return Missing("No such project")
    val issues = Workspace.issues.filter { it.project == project }
    val (done, total) = Workspace.progress(project)

    PageHeader(actions = { NewIssueButton(CreateRequest(project = project)) }) {
        Link("/projects", { classes("text-[13px] text-muted-foreground hover:text-foreground") }) { Text("Projects") }
        Glyph(Glyphs.CHEVRON, size = "size-3 text-subtle-foreground")
        Span({ classes("text-[13px]"); testTag("project-title") }) { Text(project.name) }
    }
    Div({ classes("flex shrink-0 items-center gap-6 border-b border-border px-6 py-4") }) {
        Div({ classes("min-w-0 flex-1") }) {
            P({ classes("text-[13px] text-muted-foreground") }) { Text(project.summary) }
        }
        ProjectStatusBadge(project.status)
        Div({ classes("flex items-center gap-2 text-[13px]") }) {
            Avatar(project.lead)
            Text(project.lead.name)
        }
        Span({ classes("text-[13px] text-muted-foreground") }) {
            Text("Target ${project.target?.let(::shortDate) ?: "—"}")
        }
        Div({ classes("w-40") }) { ProgressBar(if (total == 0) 0 else done * 100 / total, "$done / $total") }
    }
    FilterBar()
    IssueList(issues, CreateRequest(project = project), emptyText = "No issues in this project yet")
}
