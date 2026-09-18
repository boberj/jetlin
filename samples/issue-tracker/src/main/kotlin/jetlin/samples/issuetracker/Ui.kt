package jetlin.samples.issuetracker

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/** What the issue lists are narrowed to. Empty sets mean "don't filter on this". */
data class Filters(
    val statuses: Set<Status> = emptySet(),
    val priorities: Set<Priority> = emptySet(),
    val assignees: Set<String> = emptySet(),
    val labels: Set<String> = emptySet(),
) {
    val isEmpty: Boolean
        get() = statuses.isEmpty() && priorities.isEmpty() && assignees.isEmpty() && labels.isEmpty()

    fun matches(issue: Issue): Boolean =
        (statuses.isEmpty() || issue.status in statuses) &&
            (priorities.isEmpty() || issue.priority in priorities) &&
            (assignees.isEmpty() || (issue.assignee?.id ?: NO_ASSIGNEE) in assignees) &&
            (labels.isEmpty() || issue.labels.any { it.id in labels })

    companion object {
        /** Stands in for "unassigned" in [assignees], so it can be filtered on like a person. */
        const val NO_ASSIGNEE: String = "none"
    }
}

/**
 * One session's view state: which overlay is open, how the lists are filtered.
 *
 * Remembered in the app container rather than in a view, so it outlives navigation — a filter set on
 * the list still applies on the board — and deliberately not saved, so a hibernated session wakes
 * with menus closed rather than with a popover hanging open from yesterday.
 *
 * None of this touches the shared [Workspace]. Two sessions filtering differently is the normal case.
 */
class UiState {
    /** The one popover that is open, by an id its trigger chose; opening another closes this one. */
    var openMenu: String? by mutableStateOf(null)

    /** The create-issue modal, and what it should start with when it opens. */
    var create: CreateRequest? by mutableStateOf(null)

    var paletteOpen: Boolean by mutableStateOf(false)

    var filters: Filters by mutableStateOf(Filters())

    var collapsed: Set<Status> by mutableStateOf(emptySet())

    fun toggleMenu(id: String) {
        openMenu = if (openMenu == id) null else id
    }

    fun openCreate(request: CreateRequest = CreateRequest()) {
        openMenu = null
        paletteOpen = false
        create = request
    }

    fun openPalette() {
        openMenu = null
        create = null
        paletteOpen = true
    }

    /** Escape: closes whatever is on top, one layer per press. */
    fun dismiss() {
        when {
            openMenu != null -> openMenu = null
            paletteOpen -> paletteOpen = false
            create != null -> create = null
        }
    }
}

class CreateRequest(
    val team: Team? = null,
    val status: Status = Status.Todo,
    val project: Project? = null,
)

/**
 * The session's [UiState]. Static, because the holder itself never changes: its fields are state,
 * and reading one of those is what subscribes a composable.
 *
 * The default is a fresh holder, so a view composed alone in a test still works.
 */
val LocalUi: ProvidableCompositionLocal<UiState> = staticCompositionLocalOf { UiState() }
