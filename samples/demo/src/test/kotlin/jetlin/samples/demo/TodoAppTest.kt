package jetlin.samples.demo

import jetlin.testing.check
import jetlin.testing.click
import jetlin.testing.hasAttr
import jetlin.testing.hasClass
import jetlin.testing.hasTag
import jetlin.testing.hasTestTag
import jetlin.testing.hasText
import jetlin.testing.isDisabled
import jetlin.testing.recordUpdate
import jetlin.testing.runViewTest
import jetlin.testing.setRoutes
import jetlin.testing.type
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The demo's own pages, tested as an application rather than as a framework.
 *
 * Nothing here reaches into a composition: no node ids, no hoisted `Field` references, no HTML
 * strings. Each test describes something a user does and something they would then see, which is
 * the whole reason `jetlin-testing` exists.
 *
 * These overlap deliberately with `e2e/live.spec.ts`. The browser suite proves the client applies
 * what the server sends; these prove the server decides the right thing in the first place, and run
 * in milliseconds without a browser or a socket.
 */
class TodoAppTest {

    /** The store is process-wide, so every test starts by putting it back to its seeded state. */
    @BeforeTest
    fun seed() {
        TodoStore.reset()
    }

    @Test
    fun `the list opens with the seeded items`(): Unit = runViewTest {
        setContent { TodoListPage() }

        onAll(hasTestTag("todo")).assertCount(3)
        onNode(hasClass("todo-text") and hasText("Read the architecture doc")).assertExists()
    }

    @Test
    fun `adding a todo appends a row and clears the draft`(): Unit = runViewTest {
        setContent { TodoListPage() }

        onNode(hasTestTag("draft")).type("Third thing")
        onNode(hasTestTag("add")).click()

        onAll(hasTestTag("todo")).assertCount(4)
        onNode(hasClass("todo-text") and hasText("Third thing")).assertExists()
        // Cleared by the server, which is how the round trip shows up from the client's side.
        onNode(hasTestTag("draft")).assertValue("")
    }

    @Test
    fun `an empty draft blocks the add button`(): Unit = runViewTest {
        setContent { TodoListPage() }

        // Untouched and invalid: the form should not open covered in red.
        onNode(hasTestTag("draft-error")).assertDoesNotExist()
        onNode(hasTestTag("add")).assertDisabled()

        onNode(hasTestTag("draft")).type("something")
        onNode(hasTestTag("add")).assertEnabled()

        onNode(hasTestTag("draft")).type("")
        onNode(hasTestTag("draft-error")).assertText("Enter something to do")
    }

    @Test
    fun `checking a box marks that row done and touches nothing else`(): Unit = runViewTest {
        setContent { TodoListPage() }

        val update = recordUpdate {
            within(onAll(hasTestTag("todo"))[0]) { onNode(hasTag("input")).check() }
        }

        onNode(hasClass("todo-text") and hasText("Read the architecture doc")).assertMatches(hasClass("done"))
        onNode(hasTestTag("remaining")).assertTextContains("2 left")

        // Exactly two places moved: the row that was ticked, and the counter that depends on it. The
        // list itself and the other two rows were left where they were. This is the assertion the
        // browser suite can only approximate, by tagging DOM nodes and checking the tags survived.
        update.assertOnlyWithin(hasTestTag("todo"), hasTestTag("remaining"))
    }

    @Test
    fun `moving a row up reorders without rebuilding the list`(): Unit = runViewTest {
        setContent { TodoListPage() }

        within(onAll(hasTestTag("todo"))[2]) { onNode(hasText("up")).click() }

        onAll(hasClass("todo-text")).assertTexts(
            "Read the architecture doc",
            "Open this page twice",
            "Run the tests",
        )
    }

    @Test
    fun `removing a row drops it from the list`(): Unit = runViewTest {
        setContent { TodoListPage() }

        within(onAll(hasTestTag("todo"))[1]) { onNode(hasText("remove")).click() }

        onAll(hasTestTag("todo")).assertCount(2)
        onNode(hasClass("todo-text") and hasText("Run the tests")).assertDoesNotExist()
    }

    @Test
    fun `resetting restores the seeded list`(): Unit = runViewTest {
        setContent { TodoListPage() }

        onNode(hasTestTag("draft")).type("Something extra")
        onNode(hasTestTag("add")).click()
        onAll(hasTestTag("todo")).assertCount(4)

        onNode(hasTestTag("reset")).click()

        onAll(hasTestTag("todo")).assertCount(3)
    }

    @Test
    fun `a half-typed draft survives hibernation`(): Unit = runViewTest {
        setContent { TodoListPage() }

        onNode(hasTestTag("draft")).type("Typed before the drop")

        hibernateAndRestore()

        onNode(hasTestTag("draft")).assertValue("Typed before the drop")
        onNode(hasTestTag("add")).click()
        onNode(hasClass("todo-text") and hasText("Typed before the drop")).assertExists()
    }

    @Test
    fun `another session's edit shows up without any interaction here`(): Unit = runViewTest {
        setContent { TodoListPage() }

        // The store is shared, so this stands in for a second browser window or a background job.
        TodoStore.add("Added from elsewhere")
        awaitIdle()

        onAll(hasTestTag("todo")).assertCount(4)
        onNode(hasClass("todo-text") and hasText("Added from elsewhere")).assertExists()
    }
}

/**
 * The detail page, reached by a route, so the path parameter has to be supplied.
 */
class TodoDetailTest {

    @BeforeTest
    fun seed() {
        TodoStore.reset()
    }

    @Test
    fun `a deep link renders the item named by the path`(): Unit =
        runViewTest(url = "/todo/2") {
            setContent(route = "/todo/{id}") { TodoDetailPage() }

            onNode(hasTestTag("title")).assertValue("Run the tests")
        }

    @Test
    fun `clearing the title shows the error and disables save`(): Unit =
        runViewTest(url = "/todo/1") {
            setContent(route = "/todo/{id}") { TodoDetailPage() }

            onNode(hasTestTag("save")).assertEnabled()

            onNode(hasTestTag("title")).type("")
            onNode(hasTestTag("title-error")).assertText("A title is required")
            onNode(hasTestTag("save")).assertDisabled()

            onNode(hasTestTag("title")).type("Renamed on the server")
            onNode(hasTestTag("title-error")).assertDoesNotExist()
            onNode(hasTestTag("save")).assertEnabled()
        }

    @Test
    fun `an over-long title is rejected with its own message`(): Unit =
        runViewTest(url = "/todo/1") {
            setContent(route = "/todo/{id}") { TodoDetailPage() }

            onNode(hasTestTag("title")).type("x".repeat(61))

            onNode(hasTestTag("title-error")).assertText("Keep it under 60 characters")
            onNode(hasTestTag("save")).assertMatches(isDisabled())
        }

    @Test
    fun `saving writes the store and navigates back to the list`(): Unit =
        runViewTest(url = "/todo/1") {
            // Routed rather than pinned: the save navigates, so the test has to follow it the way
            // the application does.
            setRoutes {
                view("/") { TodoListPage() }
                view("/todo/{id}") { TodoDetailPage() }
            }

            onNode(hasTestTag("title")).type("Renamed on the server")
            onNode(hasTestTag("save")).click()

            assertUrl("/")
            assertEquals("Renamed on the server", TodoStore.find(1)?.title)
            // The list view is what is composed now, showing the edit that was just saved.
            onNode(hasClass("todo-text") and hasText("Renamed on the server")).assertExists()
        }

    @Test
    fun `following a link navigates without leaving the session`(): Unit =
        runViewTest(url = "/") {
            setRoutes {
                view("/") { TodoListPage() }
                view("/todo/{id}") { TodoDetailPage() }
            }

            onAll(hasClass("todo-text"))[0].click()

            assertUrl("/todo/1")
            onNode(hasTestTag("title")).assertValue("Read the architecture doc")
        }

    @Test
    fun `going back returns to the list`(): Unit =
        runViewTest(url = "/") {
            setRoutes {
                view("/") { TodoListPage() }
                view("/todo/{id}") { TodoDetailPage() }
            }

            onAll(hasClass("todo-text"))[0].click()
            assertUrl("/todo/1")

            // What the browser sends when the user presses back: the address bar has already moved,
            // and the server follows.
            navigate("/")

            assertUrl("/")
            onAll(hasTestTag("todo")).assertCount(3)
        }

    @Test
    fun `an unknown id renders the not-found view`(): Unit =
        runViewTest(url = "/todo/999") {
            setContent(route = "/todo/{id}") { TodoDetailPage() }

            onNode(hasTag("h1")).assertText("No such todo")
        }
}
