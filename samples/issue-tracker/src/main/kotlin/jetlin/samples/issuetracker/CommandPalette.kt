package jetlin.samples.issuetracker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.Input
import jetlin.html.LocalNavigator
import jetlin.html.Navigator
import jetlin.html.P
import jetlin.html.Span
import jetlin.html.Text

/** One thing the palette can do: a [section] to list it under, and the [run] that does it. */
private class Command(
    val key: String,
    val section: String,
    val label: String,
    val hint: String? = null,
    val icon: @Composable () -> Unit,
    val run: () -> Unit,
)

/**
 * ⌘K: type to find a page, an action or an issue, and Enter to go.
 *
 * Every keystroke goes to the server, which does the matching. That is the point of showing it here
 * rather than filtering in the browser: the search runs against the live workspace, including
 * issues another session created a second ago, with no index shipped to the client and no API.
 * Arrow keys move the highlight on the server too, which is what lets Enter run the same command the
 * user can see highlighted.
 */
@Composable
fun CommandPalette() {
    val ui = LocalUi.current
    val navigator = LocalNavigator.current
    val team = currentTeam()

    var query by remember { mutableStateOf("") }
    var highlighted by remember { mutableStateOf(0) }

    val results = commands(query, ui, navigator, team)
    val selected = highlighted.coerceIn(0, (results.size - 1).coerceAtLeast(0))

    fun run(command: Command) {
        ui.paletteOpen = false
        command.run()
    }

    Modal(tag = "palette", onDismiss = { ui.paletteOpen = false }, panelClass = "max-w-xl overflow-hidden") {
        Div({ classes("flex items-center gap-3 border-b border-border px-4") }) {
            Span({ classes("text-muted-foreground") }) { Glyph(Glyphs.SEARCH) }
            Input({
                classes("h-12 flex-1 bg-transparent text-[15px] text-foreground outline-none placeholder:text-subtle-foreground")
                placeholder("Type a command or search issues…")
                attr("autocomplete", "off")
                testTag("palette-input")
                autofocus()
                value(query)
                // No debounce: arrow keys and Enter are sent as they happen, and a query still
                // waiting out a debounce would arrive after the Enter meant for its results.
                onInput { query = it; highlighted = 0 }
                onKeyDown { pressed ->
                    when (pressed) {
                        "ArrowDown" -> highlighted = if (results.isEmpty()) 0 else (selected + 1) % results.size
                        "ArrowUp" -> highlighted = if (results.isEmpty()) 0 else (selected - 1 + results.size) % results.size
                        "Enter" -> results.getOrNull(selected)?.let(::run)
                    }
                }
            })
            Kbd("Esc")
        }
        Div({ classes("max-h-96 overflow-y-auto p-2"); testTag("palette-results") }) {
            if (results.isEmpty()) {
                P({ classes("px-3 py-6 text-center text-[13px] text-muted-foreground"); testTag("palette-empty") }) {
                    Text("No results for “$query”")
                }
            }
            var section: String? = null
            results.forEachIndexed { index, command ->
                if (command.section != section) {
                    section = command.section
                    key("section-${command.section}") { MenuHeading(command.section) }
                }
                key(command.key) {
                    Button({
                        classes(
                            if (index == selected) "flex h-10 w-full items-center gap-3 rounded-md bg-panel-hover px-3 text-left text-[13px] text-foreground"
                            else "flex h-10 w-full items-center gap-3 rounded-md px-3 text-left text-[13px] text-muted-foreground",
                        )
                        testTag("palette-item")
                        attr("aria-selected", if (index == selected) "true" else "false")
                        onClick { run(command) }
                    }) {
                        command.icon()
                        Span({ classes("min-w-0 flex-1 truncate"); testTag("palette-label") }) { Text(command.label) }
                        command.hint?.let { Span({ classes("text-xs text-subtle-foreground") }) { Text(it) } }
                    }
                }
            }
        }
    }
}

private fun commands(query: String, ui: UiState, navigator: Navigator, team: Team): List<Command> {
    val actions = listOf(
        Command("create", "Actions", "Create new issue", "C", { Glyph(Glyphs.COMPOSE, size = "size-3.5") }) {
            ui.openCreate(CreateRequest(team = team))
        },
        Command("go-my-issues", "Navigation", "Go to My issues", "G then I", { Glyph(Glyphs.INBOX, size = "size-3.5") }) {
            navigator.push("/")
        },
        Command("go-projects", "Navigation", "Go to Projects", "G then P", { Glyph(Glyphs.PROJECT, size = "size-3.5") }) {
            navigator.push("/projects")
        },
    ) + Workspace.teams.flatMap { other ->
        listOf(
            Command("go-${other.key}-issues", "Navigation", "Go to ${other.name} issues", null, { Glyph(Glyphs.LIST, size = "size-3.5") }) {
                navigator.push("/team/${other.key}/issues")
            },
            Command("go-${other.key}-board", "Navigation", "Go to ${other.name} board", if (other == team) "G then B" else null, { Glyph(Glyphs.BOARD, size = "size-3.5") }) {
                navigator.push("/team/${other.key}/board")
            },
        )
    }

    val needle = query.trim()
    if (needle.isEmpty()) return actions

    val matchingActions = actions.filter { it.label.contains(needle, ignoreCase = true) }
    val matchingIssues = Workspace.issues
        .filter { it.identifier.contains(needle, ignoreCase = true) || it.title.contains(needle, ignoreCase = true) }
        .sortedWith(compareBy<Issue> { !it.identifier.equals(needle, ignoreCase = true) }.thenByDescending { it.updatedAt })
        .take(8)
        .map { issue ->
            Command("issue-${issue.id}", "Issues", "${issue.identifier}  ${issue.title}", issue.status.label, { StatusIcon(issue.status) }) {
                navigator.push("/issue/${issue.identifier}")
            }
        }
    return matchingActions + matchingIssues
}
