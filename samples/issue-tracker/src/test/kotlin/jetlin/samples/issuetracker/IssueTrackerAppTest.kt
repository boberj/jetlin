package jetlin.samples.issuetracker

import jetlin.testing.NodeSelection
import jetlin.testing.ViewTest
import jetlin.testing.check
import jetlin.testing.click
import jetlin.testing.hasTestTag
import jetlin.testing.hasText
import jetlin.testing.pressKey
import jetlin.testing.recordUpdate
import jetlin.testing.runViewTest
import jetlin.testing.setRoutes
import jetlin.testing.submit
import jetlin.testing.type
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The issue tracker, tested the way someone uses it: open a menu, pick a status, see the row move.
 *
 * Every test composes the whole application — the Shell and every route — because most of what
 * matters here crosses that line: the dialogs and palette live in the Shell, and what they do is
 * navigate or change a page underneath them.
 *
 * Drag-and-drop and keyboard shortcuts start in app.js, but both end by poking something the server
 * rendered — a hidden input, a hidden button — so the part with any logic in it is tested here from
 * that point on.
 */
class IssueTrackerAppTest {

    @BeforeTest
    fun seed() {
        Workspace.reset()
    }

    @Test
    fun `a team's issues are grouped by status in workflow order`(): Unit = runViewTest(url = "/team/ENG/issues") {
        issueTracker()

        onAll(hasTestTag("group-name")).assertTexts("Backlog", "Todo", "In Progress", "In Review", "Done", "Canceled")
        onAll(hasTestTag("group-count")).assertTexts("4", "5", "3", "2", "3", "1")
        onAll(hasTestTag("issue-row")).assertCount(18)
    }

    @Test
    fun `the active tab leaves out the backlog and closed issues`(): Unit = runViewTest(url = "/team/ENG/issues") {
        issueTracker()

        onNode(hasTestTag("tab") and hasText("Active")).click()

        assertUrl("/team/ENG/issues?tab=active")
        onAll(hasTestTag("group-name")).assertTexts("Todo", "In Progress", "In Review")
    }

    @Test
    fun `picking a status from a row moves it to that group and leaves the rest of the page alone`(): Unit =
        runViewTest(url = "/team/ENG/issues") {
            issueTracker()

            within(row("ENG-2")) { onNode(hasTestTag("status-trigger")).click() }
            val update = recordUpdate {
                within(row("ENG-2")) { onNode(hasTestTag("status-option") and hasText("Done")).click() }
            }

            // Not assertOnlyWithin: a row changing group is removed from one and inserted into the
            // other, and a removed node is no longer anywhere a matcher could place it.
            update.assertUntouched(hasTestTag("nav-link"), hasTestTag("tab"), hasTestTag("group-name"))
            assertEquals(Status.Done, Workspace.issue("ENG-2")?.status)
            onAll(hasTestTag("group-count")).assertTexts("4", "4", "3", "2", "4", "1")
            // Picking closes the menu.
            onNode(hasTestTag("menu")).assertDoesNotExist()
        }

    @Test
    fun `collapsing a group hides its rows but keeps its count`(): Unit = runViewTest(url = "/team/ENG/issues") {
        issueTracker()

        onAll(hasTestTag("group-toggle"))[0].click()

        onAll(hasTestTag("issue-row")).assertCount(14)
        onAll(hasTestTag("group-count")).assertTexts("4", "5", "3", "2", "3", "1")
    }

    @Test
    fun `a priority filter narrows the list and follows the user to the board`(): Unit =
        runViewTest(url = "/team/ENG/issues") {
            issueTracker()

            onNode(hasTestTag("filter-priority")).click()
            onNode(hasTestTag("filter-option") and hasText("Urgent")).click()

            onAll(hasTestTag("issue-identifier")).assertTexts("ENG-6", "ENG-4", "ENG-9")

            // The filter lives in the Shell, so the board is narrowed the same way.
            onNode(hasTestTag("layout-board")).click()
            onAll(hasTestTag("card")).assertCount(3)

            onNode(hasTestTag("clear-filters")).click()
            onAll(hasTestTag("card")).assertCount(18)
        }

    @Test
    fun `creating an issue files it in the team and opens it`(): Unit = runViewTest(url = "/team/DES/issues") {
        issueTracker()

        onNode(hasTestTag("header-new-issue")).click()
        onNode(hasTestTag("create-title")).type("Redesign the settings page")
        within(onNode(hasTestTag("create-modal"))) {
            onNode(hasTestTag("priority-trigger")).click()
            onNode(hasTestTag("priority-option") and hasText("High")).click()
        }
        onNode(hasTestTag("create-title")).submit(mapOf("title" to "Redesign the settings page", "description" to ""))

        assertUrl("/issue/DES-8")
        onNode(hasTestTag("create-modal")).assertDoesNotExist()
        onNode(hasTestTag("issue-title-input")).assertValue("Redesign the settings page")
        assertEquals(Priority.High, Workspace.issue("DES-8")?.priority)
    }

    @Test
    fun `submitting without a title shows why and creates nothing`(): Unit = runViewTest(url = "/team/ENG/issues") {
        issueTracker()

        onNode(hasTestTag("new-issue")).click()
        onNode(hasTestTag("create-title")).submit(mapOf("title" to "  "))

        onNode(hasTestTag("create-title-error")).assertText("A title is required")
        assertEquals(25, Workspace.issues.size)
    }

    @Test
    fun `create more keeps the dialog open and clears it for the next one`(): Unit =
        runViewTest(url = "/team/ENG/issues") {
            issueTracker()

            onAll(hasTestTag("group-new-issue"))[0].click()
            onNode(hasTestTag("create-more")).check()
            onNode(hasTestTag("create-title")).type("First")
            onNode(hasTestTag("create-title")).submit(mapOf("title" to "First"))

            assertUrl("/team/ENG/issues")
            onNode(hasTestTag("created-notice")).assertText("Created ENG-19")
            onNode(hasTestTag("create-title")).assertValue("")
            // Opened from the Backlog group's "+", so that is where it went.
            assertEquals(Status.Backlog, Workspace.issue("ENG-19")?.status)
        }

    @Test
    fun `dropping a card on a column moves the issue there`(): Unit = runViewTest(url = "/team/ENG/board") {
        issueTracker()
        val issue = Workspace.issue("ENG-2")!!

        // What app.js does on drop: write the card's id into the column's hidden input.
        within(column(Status.Done)) { onNode(hasTestTag("drop-input")).type("${issue.id}") }

        assertEquals(Status.Done, issue.status)
        within(column(Status.Done)) { onNode(hasTestTag("column-count")).assertText("4") }
        within(column(Status.Todo)) { onNode(hasTestTag("column-count")).assertText("4") }
        val change = assertIs<Entry.Change>(issue.history.last())
        assertEquals("changed status from Todo to Done", change.description)
    }

    @Test
    fun `a comment is added to the activity and the draft is cleared`(): Unit = runViewTest(url = "/issue/ENG-1") {
        issueTracker()

        onNode(hasTestTag("comment-submit")).assertDisabled()
        onNode(hasTestTag("comment-draft")).type("Looking into it today.")
        onNode(hasTestTag("comment-submit")).click()

        onAll(hasTestTag("comment-body")).assertTexts("I can reproduce this on slow connections only.", "Looking into it today.")
        onNode(hasTestTag("comment-draft")).assertValue("")
    }

    @Test
    fun `a half-written comment survives the session hibernating`(): Unit = runViewTest(url = "/issue/ENG-1") {
        issueTracker()

        onNode(hasTestTag("comment-draft")).type("Half a thought")
        hibernateAndRestore()

        onNode(hasTestTag("comment-draft")).assertValue("Half a thought")
    }

    @Test
    fun `changing a property from the detail panel records it in the activity`(): Unit =
        runViewTest(url = "/issue/ENG-2") {
            issueTracker()

            onNode(hasTestTag("assignee-trigger")).click()
            onNode(hasTestTag("assignee-option") and hasText("Priya Shah", substring = true)).click()

            onNode(hasTestTag("assignee-trigger")).assertText("PSPriya Shah")
            onAll(hasTestTag("change")).texts().last().let { assertEquals(true, "assigned to Priya Shah" in it, it) }
        }

    @Test
    fun `the palette finds an issue and Enter opens it`(): Unit = runViewTest(url = "/") {
        issueTracker()

        onNode(hasTestTag("open-palette")).click()
        onNode(hasTestTag("palette-input")).type("p95")
        onAll(hasTestTag("palette-label")).assertTexts("ENG-4  Search p95 latency above 300ms")
        onNode(hasTestTag("palette-input")).pressKey("Enter")

        assertUrl("/issue/ENG-4")
        onNode(hasTestTag("palette")).assertDoesNotExist()
    }

    @Test
    fun `arrow keys move the palette's highlight to the command Enter runs`(): Unit = runViewTest(url = "/") {
        issueTracker()

        onNode(hasTestTag("open-palette")).click()
        onNode(hasTestTag("palette-input")).pressKey("ArrowDown")
        onNode(hasTestTag("palette-input")).pressKey("ArrowDown")
        onNode(hasTestTag("palette-input")).pressKey("Enter")

        assertUrl("/projects")
    }

    @Test
    fun `escape closes the open menu before the dialog under it`(): Unit = runViewTest(url = "/") {
        issueTracker()

        onNode(hasTestTag("shortcut-create")).click()
        within(onNode(hasTestTag("create-modal"))) { onNode(hasTestTag("status-trigger")).click() }
        onNode(hasTestTag("menu")).assertExists()

        onNode(hasTestTag("shortcut-dismiss")).click()
        onNode(hasTestTag("menu")).assertDoesNotExist()
        onNode(hasTestTag("create-modal")).assertExists()

        onNode(hasTestTag("shortcut-dismiss")).click()
        onNode(hasTestTag("create-modal")).assertDoesNotExist()
    }

    @Test
    fun `go-to-board goes to the board of the team being looked at`(): Unit = runViewTest(url = "/issue/DES-1") {
        issueTracker()

        onNode(hasTestTag("shortcut-go-board")).click()

        assertUrl("/team/DES/board")
    }

    @Test
    fun `project progress counts done issues and moves when one closes`(): Unit = runViewTest(url = "/projects") {
        issueTracker()

        onAll(hasTestTag("project-progress")).assertTexts("1 / 6", "0 / 3", "0 / 5")
        Workspace.setStatus(Workspace.issue("ENG-1")!!, Status.Done)
        awaitIdle()
        onAll(hasTestTag("project-progress")).assertTexts("1 / 6", "0 / 3", "1 / 5")
    }

    @Test
    fun `an unknown issue says so`(): Unit = runViewTest(url = "/issue/ENG-999") {
        issueTracker()

        onNode(hasTestTag("missing")).assertText("No such issue")
    }

    private suspend fun ViewTest.issueTracker() = setRoutes {
        app { route -> Shell(route) }
        view("/") { MyIssuesPage() }
        view("/team/{key}/issues") { TeamIssuesPage() }
        view("/team/{key}/board") { BoardPage() }
        view("/issue/{identifier}") { IssuePage() }
        view("/projects") { ProjectsPage() }
        view("/project/{id}") { ProjectPage() }
    }

    /** The list row for [identifier]. Rows carry no id of their own, so it is found by position. */
    private suspend fun ViewTest.row(identifier: String): NodeSelection {
        val identifiers = onAll(hasTestTag("issue-identifier")).texts()
        return onAll(hasTestTag("issue-row"))[identifiers.indexOf(identifier).also { check(it >= 0) { "$identifier not listed" } }]
    }

    /** Every status has a column, always in workflow order. */
    private fun ViewTest.column(status: Status): NodeSelection = onAll(hasTestTag("column"))[status.ordinal]
}
