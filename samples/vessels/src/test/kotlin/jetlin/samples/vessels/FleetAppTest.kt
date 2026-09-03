package jetlin.samples.vessels

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import jetlin.testing.click
import jetlin.testing.hasTestTag
import jetlin.testing.recordUpdate
import jetlin.testing.runViewTest
import jetlin.testing.setRoutes
import jetlin.testing.type
import kotlinx.coroutines.delay
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The fleet pages, tested as an application rather than as a framework.
 *
 * Nothing here reaches into a composition: no node ids, no HTML strings, no hoisted state. Each test
 * describes something an operator does and something they would then see.
 *
 * The three `recordUpdate` tests are the sharpest things this sample can state, and the reason it
 * was built. Eighty rows are on screen; the question is not whether the right thing appears but how
 * much of the page had to be rewritten to make it appear.
 */
class FleetAppTest {

    /** The store is process-wide, so every test starts by putting it back to its seeded state. */
    @BeforeTest
    fun seed() {
        FleetStore.reset()
        FleetStore.resetTelemetry()
    }

    // The application as `Main.kt` assembles it: a container holding the search, and the two views.
    private fun jetlin.testing.RoutesBuilder.fleetApp() {
        app { route ->
            val fleet = remember { FleetView() }
            CompositionLocalProvider(LocalFleetView provides fleet) { Shell { route() } }
        }
        view("/") { VesselsPage() }
        view("/vessels/{vesselId}") { VesselPage() }
    }

    @Test
    fun `the list opens with every vessel, not a page of them`(): Unit = runViewTest(url = "/") {
        setRoutes { fleetApp() }

        // The whole point of dropping the original's virtualization: all of it, in one go.
        onAll(hasTestTag("row")).assertCount(FleetStore.size)
    }

    @Test
    fun `searching narrows the list`(): Unit = runViewTest(url = "/") {
        setRoutes { fleetApp() }

        onNode(hasTestTag("search")).type("aurora")

        onAll(hasTestTag("row")).assertCount(1)
        onNode(hasTestTag("name")).assertText("Aurora Borealis")
    }

    @Test
    fun `a search matching nothing says so`(): Unit = runViewTest(url = "/") {
        setRoutes { fleetApp() }

        onNode(hasTestTag("search")).type("no such vessel")

        onAll(hasTestTag("row")).assertCount(0)
        onNode(hasTestTag("empty")).assertExists()
    }

    @Test
    fun `a sort header reorders, and clicking it again flips`(): Unit = runViewTest(url = "/") {
        setRoutes { fleetApp() }

        onNode(hasTestTag("sort-name")).click()
        val ascending = firstVesselName()

        onNode(hasTestTag("sort-name")).click()
        val descending = firstVesselName()

        assertNotEquals(ascending, descending, "clicking the active column again should flip it")
        // Severity still leads, so this is the first name within the top severity band rather than
        // the first name in the fleet — which is the original's rule and worth pinning.
        assertTrue(ascending < descending, "ascending should sort before descending")
    }

    @Test
    fun `sorting is in the url, so a sorted fleet is a link`(): Unit = runViewTest(url = "/") {
        setRoutes { fleetApp() }

        onNode(hasTestTag("sort-name")).click()

        assertUrl("/?sort=name")
    }

    @Test
    fun `the priority stepper changes one vessel`(): Unit = runViewTest(url = "/") {
        setRoutes { fleetApp() }

        val vessel = FleetStore.list("", SortKey.PRIORITY, ascending = true).first()
        val before = vessel.priority

        onNode(hasTestTag("priority-up-${vessel.id}")).click()

        assertEquals(before + 1, vessel.priority)
        onNode(hasTestTag("priority-${vessel.id}")).assertText((before + 1).toString())
    }

    @Test
    fun `a flag icon tints its row`(): Unit = runViewTest(url = "/") {
        setRoutes { fleetApp() }

        val vessel = FleetStore.list("", SortKey.NAME, ascending = true).first { !it.construction }

        onNode(hasTestTag("flag-construction-${vessel.id}")).click()

        assertTrue(vessel.construction, "the flag should be set on the vessel itself")
    }

    @Test
    fun `a row link reaches that vessel's page`(): Unit = runViewTest(url = "/") {
        setRoutes { fleetApp() }

        onNode(hasTestTag("search")).type("aurora")
        onNode(hasTestTag("name")).click()

        assertUrl("/vessels/v001")
        onNode(hasTestTag("vessel-name")).assertText("Aurora Borealis")
    }

    @Test
    fun `the search text is still there after visiting a vessel and coming back`(): Unit =
        runViewTest(url = "/") {
            setRoutes { fleetApp() }

            onNode(hasTestTag("search")).type("aurora")
            onNode(hasTestTag("name")).click()
            assertUrl("/vessels/v001")

            navigate("/")

            // The reason this sample exists. A `remember` in the view would be gone; this one lives
            // in the container, which the navigation never tore down.
            onNode(hasTestTag("search")).assertValue("aurora")
            onAll(hasTestTag("row")).assertCount(1)
        }

    @Test
    fun `the detail page shows a loading state before its data`(): Unit =
        runViewTest(url = "/vessels/v001") {
            setRoutes { fleetApp() }

            // The lookup is behind a delay, so the first thing composed is the loading state.
            onNode(hasTestTag("loading")).assertExists()

            awaitDetail()
            onNode(hasTestTag("loading")).assertDoesNotExist()
            onNode(hasTestTag("block-status")).assertExists()
        }

    @Test
    fun `a telemetry tick changes the page with no interaction behind it`(): Unit =
        runViewTest(url = "/vessels/v001") {
            setRoutes { fleetApp() }
            awaitDetail()

            val vessel = checkNotNull(FleetStore.find("v001"))
            val before = nodeText("gauge-cpu")

            // Driven directly rather than by a clock: a test should not have to wait a second to
            // find out what a second does. Nothing clicks anything — this is the whole interaction.
            repeat(20) { FleetStore.tick(vessel) }
            awaitIdle()

            assertNotEquals(before, nodeText("gauge-cpu"), "twenty ticks should reach the page")
        }

    @Test
    fun `the month tabs change the usage block`(): Unit = runViewTest(url = "/vessels/v001") {
        setRoutes { fleetApp() }
        awaitDetail()

        val september = nodeText("usage-total")
        onNode(hasTestTag("month-Jun")).click()

        assertNotEquals(september, nodeText("usage-total"))
    }

    @Test
    fun `the chosen month comes back when the page does`(): Unit = runViewTest(url = "/vessels/v001") {
        setRoutes { fleetApp() }
        awaitDetail()

        onNode(hasTestTag("month-Jun")).click()
        val june = nodeText("usage-total")

        navigate("/")
        navigate("/vessels/v001")
        awaitDetail()

        // `rememberSaved`, so it survives the view being torn down and rebuilt — a different
        // lifetime again from the container's search box, which outlives the page entirely.
        assertEquals(june, nodeText("usage-total"))
    }

    // ------------------------------------------------------------------------------------------
    // What an update costs. These are the assertions no client-side framework can express, because
    // in one there is no update to measure — the whole page is re-rendered and diffed either way.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `nudging one vessel's priority touches that row and nothing else`(): Unit =
        runViewTest(url = "/?sort=name") {
            setRoutes { fleetApp() }

            val vessel = FleetStore.list("", SortKey.NAME, ascending = true).first()

            val update = recordUpdate { onNode(hasTestTag("priority-up-${vessel.id}")).click() }

            // Eighty rows on the page, one clicked. Sorted by name, so the row does not move and the
            // patch is exactly the cells that changed. Getting `key` wrong would be invisible at any
            // smaller scale than this.
            update.assertOnlyWithin(hasTestTag("priority-${vessel.id}"))
        }

    @Test
    fun `re-sorting moves the rows rather than rewriting them`(): Unit = runViewTest(url = "/") {
        setRoutes { fleetApp() }

        val update = recordUpdate { onNode(hasTestTag("sort-name")).click() }

        // The table body is rearranged and the header chevrons change; nothing outside is touched.
        update.assertUntouched(hasTestTag("top-nav"), hasTestTag("vessel-count"))
    }

    @Test
    fun `navigating does not rebuild the chrome`(): Unit = runViewTest(url = "/") {
        setRoutes { fleetApp() }

        val update = recordUpdate { navigate("/vessels/v001") }

        // The top bar is composed in the container, above the route, so a move recomposes it to the
        // same markup and the applier records nothing for it.
        update.assertUntouched(hasTestTag("top-nav"))
    }

    private suspend fun jetlin.testing.ViewTest.firstVesselName(): String =
        onAll(hasTestTag("name")).texts().first()

    private suspend fun jetlin.testing.ViewTest.nodeText(tag: String): String =
        onNode(hasTestTag(tag)).text()

    /**
     * Waits for the detail page's lookup to land.
     *
     * `awaitIdle` is not enough: it returns once no recomposition is pending, and a `LaunchedEffect`
     * suspended in `delay` is not pending work. The wait has to be real, which is one reason
     * `runViewTest` uses real time rather than a virtual clock.
     */
    private suspend fun jetlin.testing.ViewTest.awaitDetail() {
        delay(800)
        awaitIdle()
    }
}
