package jetlin.samples.issuetracker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.Form
import jetlin.html.Input
import jetlin.html.Label
import jetlin.html.LocalNavigator
import jetlin.html.P
import jetlin.html.Span
import jetlin.html.Text
import jetlin.html.TextArea
import jetlin.html.bind
import jetlin.html.rememberField

/**
 * The new-issue dialog, composed by [Shell] while [UiState.create] is set.
 *
 * Everything in it is `remember`, not saved: an abandoned dialog is not worth waking a hibernated
 * session up to. Keyed on the request, so opening it again — or from a different "+" — starts clean.
 */
@Composable
fun CreateIssueModal() {
    val ui = LocalUi.current
    val request = ui.create ?: return
    key(request) { CreateIssueForm(request) }
}

@Composable
private fun CreateIssueForm(request: CreateRequest) {
    val ui = LocalUi.current
    val navigator = LocalNavigator.current

    val title = rememberField("") { if (it.isBlank()) "A title is required" else null }
    val description = rememberField("")
    var team by remember { mutableStateOf(request.team ?: Workspace.engineering) }
    var status by remember { mutableStateOf(request.status) }
    var priority by remember { mutableStateOf(Priority.None) }
    var assignee by remember { mutableStateOf<User?>(null) }
    var project by remember { mutableStateOf(request.project) }
    var labels by remember { mutableStateOf(emptyList<Label>()) }
    var createMore by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf<Issue?>(null) }

    Modal(tag = "create-modal", onDismiss = { ui.create = null }, panelClass = "max-w-2xl") {
        Form({
            // The browser's values, not the fields': they are bound with a debounce, so pressing
            // Enter straight after typing would otherwise submit the title as it was 150ms ago.
            onSubmit { form ->
                val submittedTitle = (form["title"] ?: title.value).trim()
                if (submittedTitle.isEmpty()) {
                    title.edit(submittedTitle)
                    return@onSubmit
                }
                val issue = Workspace.create(
                    team = team,
                    title = submittedTitle,
                    description = form["description"] ?: description.value,
                    status = status,
                    priority = priority,
                    assignee = assignee,
                    labels = labels,
                    project = project,
                )
                if (createMore) {
                    created = issue
                    title.reset("")
                    description.reset("")
                } else {
                    ui.create = null
                    navigator.push("/issue/${issue.identifier}")
                }
            }
        }) {
            Div({ classes("flex items-center gap-2 px-5 pt-4 text-xs text-muted-foreground") }) {
                Dropdown("create-team", tag = "team-trigger", triggerClass = "flex h-6 items-center gap-1.5 rounded-md border border-border-strong px-2 text-xs text-foreground hover:bg-panel-hover", trigger = {
                    Text(team.key)
                }) { close ->
                    for (option in Workspace.teams) {
                        MenuItem("team-option", selected = option == team, onPick = { team = option; close() }) {
                            Text(option.name)
                        }
                    }
                }
                Glyph(Glyphs.CHEVRON, size = "size-3")
                Text("New issue")
                Button({
                    classes("ml-auto flex size-6 items-center justify-center rounded text-muted-foreground hover:bg-panel-hover hover:text-foreground")
                    type("button")
                    attr("title", "Close (Esc)")
                    testTag("create-close")
                    onClick { ui.create = null }
                }) { Glyph(Glyphs.CLOSE, size = "size-3.5") }
            }

            Div({ classes("flex flex-col gap-1 px-5 pb-2 pt-3") }) {
                Input({
                    classes("w-full bg-transparent text-lg font-medium text-foreground outline-none placeholder:text-subtle-foreground")
                    name("title")
                    placeholder("Issue title")
                    attr("autocomplete", "off")
                    testTag("create-title")
                    autofocus()
                    bind(title)
                })
                title.error?.let { message ->
                    P({ classes("text-xs text-destructive"); testTag("create-title-error") }) { Text(message) }
                }
                TextArea({
                    classes("min-h-24 w-full resize-none bg-transparent text-[13px] leading-relaxed text-foreground outline-none placeholder:text-subtle-foreground")
                    name("description")
                    placeholder("Add description…")
                    testTag("create-description")
                    bind(description)
                })
            }

            Div({ classes("flex flex-wrap items-center gap-1.5 px-5 pb-4") }) {
                StatusMenu("create-status", status, CHIP_TRIGGER, { status = it }, showLabel = true)
                PriorityMenu("create-priority", priority, CHIP_TRIGGER, { priority = it }, showLabel = true)
                AssigneeMenu("create-assignee", assignee, CHIP_TRIGGER, { assignee = it }, showLabel = true)
                LabelsMenu("create-labels", labels, CHIP_TRIGGER) { label ->
                    labels = if (label in labels) labels - label else (labels + label).sortedBy { Workspace.labels.indexOf(it) }
                }
                ProjectMenu("create-project", project, CHIP_TRIGGER, { project = it })
            }

            Div({ classes("flex items-center gap-3 border-t border-border px-5 py-3") }) {
                created?.let { issue ->
                    Span({ classes("text-xs text-muted-foreground"); testTag("created-notice") }) {
                        Text("Created ${issue.identifier}")
                    }
                }
                Label({ classes("ml-auto flex cursor-pointer items-center gap-2 text-xs text-muted-foreground") }) {
                    Input({
                        type("checkbox")
                        classes("accent-accent")
                        testTag("create-more")
                        checked(createMore)
                        onChecked { createMore = it }
                    })
                    Text("Create more")
                }
                Button({
                    classes("h-8 rounded-md bg-accent px-3 text-[13px] font-medium text-accent-foreground hover:bg-accent-hover disabled:cursor-not-allowed disabled:opacity-40")
                    type("submit")
                    testTag("create-submit")
                    // Not disabled while the title is empty, though that would look tidier: the
                    // field reaches the server after a debounce, and a disabled default button
                    // stops Enter submitting the form at all in the moment before it does.
                }) { Text("Create issue") }
            }
        }
    }
}

/** A bordered chip, for the property row under the title. */
private const val CHIP_TRIGGER: String =
    "flex h-7 items-center gap-1.5 rounded-md border border-border-strong px-2 text-xs text-foreground hover:bg-panel-hover"
