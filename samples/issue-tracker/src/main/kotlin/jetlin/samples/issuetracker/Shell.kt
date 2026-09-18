package jetlin.samples.issuetracker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.Element
import jetlin.html.Link
import jetlin.html.LocalNavigator
import jetlin.html.LocalRequest
import jetlin.html.Nav
import jetlin.html.Span
import jetlin.html.Text

/**
 * The frame every page sits in: the sidebar, the overlays, and the session's [UiState].
 *
 * Composed once per session by `app { }`, so navigating leaves the sidebar alone — it recomposes to
 * move the highlight and emits a couple of class changes, rather than being torn out and rebuilt.
 */
@Composable
fun Shell(content: @Composable () -> Unit) {
    val ui = remember { UiState() }
    CompositionLocalProvider(LocalUi provides ui) {
        Div({ classes("flex h-full") }) {
            Sidebar()
            Element("main", { classes("m-2 ml-0 flex min-w-0 flex-1 flex-col overflow-hidden rounded-lg border border-border bg-background") }) {
                content()
            }
        }
        ShortcutTargets()
        if (ui.create != null) CreateIssueModal()
        if (ui.paletteOpen) CommandPalette()
    }
}

@Composable
private fun Sidebar() {
    val ui = LocalUi.current
    val here = currentTeam()
    Element("aside", { classes("flex w-60 shrink-0 flex-col gap-4 px-3 py-3") }) {
        Div({ classes("flex items-center gap-2 px-1") }) {
            Span({ classes("flex size-5 items-center justify-center rounded bg-accent text-[11px] font-bold text-accent-foreground") }) {
                Text(Workspace.NAME.take(1))
            }
            Span({ classes("flex-1 text-[13px] font-semibold") }) { Text(Workspace.NAME) }
            Button({
                classes("flex size-7 items-center justify-center rounded-md text-muted-foreground hover:bg-panel-hover hover:text-foreground")
                testTag("open-palette")
                attr("title", "Search (⌘K)")
                onClick { ui.openPalette() }
            }) { Glyph(Glyphs.SEARCH) }
            Button({
                classes("flex size-7 items-center justify-center rounded-md border border-border-strong bg-panel text-foreground hover:bg-panel-hover")
                testTag("new-issue")
                attr("title", "New issue (C)")
                onClick { ui.openCreate(CreateRequest(team = here)) }
            }) { Glyph(Glyphs.COMPOSE) }
        }

        Nav({ classes("flex flex-col gap-px") }) {
            SidebarLink("/", "My issues", Glyphs.INBOX)
            SidebarLink("/projects", "Projects", Glyphs.PROJECT)
        }

        Div({ classes("flex flex-col gap-px") }) {
            Div({ classes("px-2 pb-1 text-[11px] font-medium text-subtle-foreground") }) { Text("Your teams") }
            for (team in Workspace.teams) {
                Div({ classes("flex items-center gap-2 px-2 py-1 text-[13px] font-medium") }) {
                    Span({
                        classes(
                            if (team == Workspace.engineering) "flex size-4 items-center justify-center rounded bg-emerald-600 text-[9px] font-bold text-white"
                            else "flex size-4 items-center justify-center rounded bg-rose-600 text-[9px] font-bold text-white",
                        )
                    }) { Text(team.key.take(1)) }
                    Text(team.name)
                }
                SidebarLink("/team/${team.key}/issues", "Issues", Glyphs.LIST, nested = true)
                SidebarLink("/team/${team.key}/issues?tab=active", "Active", Glyphs.ARROW, nested = true)
                SidebarLink("/team/${team.key}/issues?tab=backlog", "Backlog", Glyphs.FILTER, nested = true)
                SidebarLink("/team/${team.key}/board", "Board", Glyphs.BOARD, nested = true)
            }
        }

        Div({ classes("mt-auto flex flex-col gap-2 px-2 text-[11px] text-subtle-foreground") }) {
            Div({ classes("flex items-center gap-1.5") }) {
                Kbd("C"); Text("new issue")
                Span({ classes("ml-2") }) { Kbd("⌘K") }; Text("search")
            }
            Button({
                classes("self-start text-left hover:text-muted-foreground")
                testTag("reset")
                onClick { Workspace.reset() }
            }) { Text("Reset demo data") }
        }
    }
}

@Composable
private fun SidebarLink(href: String, label: String, glyph: String, nested: Boolean = false) {
    val active = LocalRequest.current.url == href
    Link(href, {
        classes(
            when {
                active && nested -> "flex h-7 items-center gap-2 rounded-md bg-panel-hover pl-7 pr-2 text-[13px] text-foreground"
                nested -> "flex h-7 items-center gap-2 rounded-md pl-7 pr-2 text-[13px] text-muted-foreground hover:bg-panel hover:text-foreground"
                active -> "flex h-7 items-center gap-2 rounded-md bg-panel-hover px-2 text-[13px] text-foreground"
                else -> "flex h-7 items-center gap-2 rounded-md px-2 text-[13px] text-muted-foreground hover:bg-panel hover:text-foreground"
            },
        )
        testTag("nav-link")
    }) {
        Glyph(glyph, size = "size-3.5")
        Text(label)
    }
}

/**
 * The team the current page belongs to, so "new issue" and "go to board" land where the user is.
 * Pages outside a team — My issues, Projects — fall back to the first one.
 */
@Composable
fun currentTeam(): Team {
    val request = LocalRequest.current
    val key = request.path.removePrefix("/team/").substringBefore('/').takeIf { request.path.startsWith("/team/") }
        ?: request.path.removePrefix("/issue/").substringBefore('-').takeIf { request.path.startsWith("/issue/") }
    return key?.let { Workspace.team(it) } ?: Workspace.engineering
}

/**
 * Hidden buttons that app.js clicks when a shortcut is pressed.
 *
 * Keys are read in the browser, because there is no element to listen on for a keypress that
 * happens anywhere on the page. What the key *does* is decided here, so the script stays a table of
 * key names and knows nothing about the application.
 */
@Composable
private fun ShortcutTargets() {
    val ui = LocalUi.current
    val navigator = LocalNavigator.current
    val team = currentTeam()
    Div({ classes("hidden") }) {
        Shortcut("create") { ui.openCreate(CreateRequest(team = team)) }
        Shortcut("palette") { if (ui.paletteOpen) ui.paletteOpen = false else ui.openPalette() }
        Shortcut("dismiss") { ui.dismiss() }
        Shortcut("go-my-issues") { navigator.push("/") }
        Shortcut("go-board") { navigator.push("/team/${team.key}/board") }
        Shortcut("go-projects") { navigator.push("/projects") }
    }
}

@Composable
private fun Shortcut(name: String, action: () -> Unit) {
    Button({
        attr("data-shortcut", name)
        attr("tabindex", "-1")
        testTag("shortcut-$name")
        onClick(action)
    })
}
