package jetlin.samples.issuetracker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.Element
import jetlin.html.H2
import jetlin.html.Input
import jetlin.html.Link
import jetlin.html.P
import jetlin.html.Span
import jetlin.html.Strong
import jetlin.html.Text
import jetlin.html.TextArea
import jetlin.html.bind
import jetlin.html.pathParam
import jetlin.html.rememberSavedField

/** `/issue/{identifier}`: the issue itself, its history, and every property as a menu. */
@Composable
fun IssuePage() {
    val issue = Workspace.issue(pathParam("identifier")) ?: return Missing("No such issue")

    // Keyed on the issue: going from one issue to another through the palette reuses this view,
    // and the comment being written about the first must not follow the user to the second.
    key(issue.id) {
        PageHeader {
            Link("/team/${issue.team.key}/issues", { classes("text-[13px] text-muted-foreground hover:text-foreground") }) {
                Text(issue.team.name)
            }
            Glyph(Glyphs.CHEVRON, size = "size-3 text-subtle-foreground")
            Span({ classes("text-[13px]"); testTag("breadcrumb-identifier") }) { Text(issue.identifier) }
        }
        Div({ classes("flex min-h-0 flex-1") }) {
            Div({ classes("min-w-0 flex-1 overflow-y-auto") }) {
                Div({ classes("mx-auto flex max-w-3xl flex-col gap-2 px-10 pb-24 pt-8") }) {
                    // Committed on change rather than per keystroke: a rename is one entry in the
                    // history, not one per letter, and nobody else needs to watch it being typed.
                    Input({
                        classes("w-full bg-transparent text-2xl font-semibold text-foreground outline-none placeholder:text-subtle-foreground")
                        testTag("issue-title-input")
                        placeholder("Issue title")
                        value(issue.title)
                        onChange { Workspace.rename(issue, it.trim()) }
                    })
                    TextArea({
                        classes("min-h-32 w-full resize-none bg-transparent text-[15px] leading-relaxed text-foreground/90 outline-none placeholder:text-subtle-foreground")
                        testTag("issue-description")
                        placeholder("Add description…")
                        value(issue.description)
                        onChange { Workspace.describe(issue, it) }
                    })
                    Activity(issue)
                }
            }
            Properties(issue)
        }
    }
}

@Composable
private fun Activity(issue: Issue) {
    // Saved, because it is the one thing on this page only the user has: a half-written comment
    // should survive a dropped connection or a deploy.
    val draft = rememberSavedField("", key = "comment-${issue.identifier}")

    Div({ classes("mt-6 border-t border-border pt-6") }) {
        H2({ classes("mb-4 text-sm font-medium") }) { Text("Activity") }
        Element("ol", { classes("flex flex-col gap-4"); testTag("activity") }) {
            issue.history.forEachIndexed { index, entry ->
                // History only grows at the end, so position is identity.
                key(index) {
                    when (entry) {
                        is Entry.Change -> Element("li", { classes("flex items-center gap-2 pl-1 text-xs text-muted-foreground"); testTag("change") }) {
                            Avatar(entry.actor, size = "size-4 text-[8px]")
                            Span {
                                Strong({ classes("font-medium text-foreground/80") }) { Text(entry.actor.name) }
                                Text(" ${entry.description}")
                            }
                            Span({ classes("text-subtle-foreground") }) { Text("· ${ago(entry.at)}") }
                        }
                        is Entry.Comment -> Element("li", { classes("rounded-lg border border-border bg-panel p-3"); testTag("comment") }) {
                            Div({ classes("mb-1.5 flex items-center gap-2 text-xs") }) {
                                Avatar(entry.actor)
                                Span({ classes("font-medium") }) { Text(entry.actor.name) }
                                Span({ classes("text-subtle-foreground") }) { Text(ago(entry.at)) }
                            }
                            P({ classes("whitespace-pre-wrap text-[13px] leading-relaxed"); testTag("comment-body") }) { Text(entry.body) }
                        }
                    }
                }
            }
        }

        Div({ classes("mt-5 rounded-lg border border-border-strong bg-panel focus-within:border-accent/60") }) {
            TextArea({
                classes("block min-h-20 w-full resize-none bg-transparent p-3 text-[13px] outline-none placeholder:text-subtle-foreground")
                testTag("comment-draft")
                placeholder("Leave a comment…")
                bind(draft)
            })
            Div({ classes("flex justify-end p-2 pt-0") }) {
                Button({
                    classes("h-7 rounded-md bg-accent px-3 text-xs font-medium text-accent-foreground hover:bg-accent-hover disabled:cursor-not-allowed disabled:opacity-40")
                    testTag("comment-submit")
                    disabled(draft.value.isBlank())
                    onClick {
                        if (draft.value.isNotBlank()) {
                            Workspace.comment(issue, draft.value.trim())
                            draft.reset("")
                        }
                    }
                }) { Text("Comment") }
            }
        }
    }
}

@Composable
private fun Properties(issue: Issue) {
    Element("aside", { classes("hidden w-64 shrink-0 flex-col gap-1 border-l border-border p-4 md:flex"); testTag("properties") }) {
        Property("Status") {
            StatusMenu("detail-status", issue.status, PROPERTY_TRIGGER, { Workspace.setStatus(issue, it) }, showLabel = true, panelClass = "right-0 w-56")
        }
        Property("Priority") {
            PriorityMenu("detail-priority", issue.priority, PROPERTY_TRIGGER, { Workspace.setPriority(issue, it) }, showLabel = true, panelClass = "right-0 w-56")
        }
        Property("Assignee") {
            AssigneeMenu("detail-assignee", issue.assignee, PROPERTY_TRIGGER, { Workspace.setAssignee(issue, it) }, showLabel = true, panelClass = "right-0 w-56")
        }
        Property("Labels") {
            LabelsMenu("detail-labels", issue.labels, PROPERTY_TRIGGER, panelClass = "right-0 w-56") { Workspace.toggleLabel(issue, it) }
        }
        Property("Project") {
            ProjectMenu("detail-project", issue.project, PROPERTY_TRIGGER, { Workspace.setProject(issue, it) }, panelClass = "right-0 w-56")
        }
        Div({ classes("mt-4 border-t border-border pt-4 text-xs text-muted-foreground") }) {
            P { Text("Created by ${issue.creator.name}") }
            P({ classes("mt-1") }) { Text("Created ${shortDate(issue.createdAt)} · updated ${ago(issue.updatedAt)}") }
        }
    }
}

@Composable
private fun Property(name: String, content: @Composable () -> Unit) {
    Div({ classes("flex items-center gap-2") }) {
        Span({ classes("w-16 shrink-0 text-xs text-muted-foreground") }) { Text(name) }
        Div({ classes("min-w-0 flex-1") }) { content() }
    }
}
