package jetlin.samples.keyed

import jetlin.protocol.NodeSpec
import jetlin.protocol.Op
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The nine keyed js-framework-benchmark operations, asserted rather than timed.
 *
 * `Runner.kt` reports what each one costs. This says what each one is *allowed* to cost, which is
 * the part worth failing a build over: a timing regression is a slower afternoon, but an operation
 * that starts sending a thousand ops where it used to send two is a different implementation
 * wearing the same page.
 *
 * Every assertion here is on the exact op list, not on what the page ends up looking like. The page
 * ends up looking identical either way — that is precisely why a non-keyed list, or a list keyed by
 * something that changes with the data, is invisible to every other kind of test. Drop the
 * `key(row.id)` from `Page.kt` and the five assertions about moves, text writes and attribute
 * writes below are the ones that go red.
 *
 * Each test drives the operation through the same preconditions the reference harness uses, so what
 * is asserted is the op list from the same click that gets measured.
 */
class KeyedBenchmarkTest {

    // -----------------------------------------------------------------------------------------
    // 01 create rows — creating 1,000 rows
    // -----------------------------------------------------------------------------------------

    @Test
    fun `creating 1000 rows inserts 1000 subtrees and nothing else`(): Unit = bench { driver ->
        repeat(WARMUP) { driver.click("run"); driver.click("clear") }

        val patch = driver.click("run")

        assertEquals(ROWS, patch.ops.size, "one insert per row, and no other work")
        assertTrue(patch.ops.all { it is Op.Insert }, "got ${patch.ops.summary()}")
        assertEquals(ROWS, driver.rowCount())

        // A row arrives as one op carrying its whole subtree, rather than as an insert per element.
        val row = assertIs<Op.Insert>(patch.ops.first())
        assertEquals(TBODY_CHILDREN, row.index)
        assertEquals(4, assertIs<NodeSpec.Element>(row.node).children.size, "a <tr> and its four cells")

        // Setup built and threw away five thousand rows, and ids keep counting, so this table
        // starts at 5001 — which is what the harness checks for too.
        assertEquals("${ROWS * WARMUP + 1}", driver.rowId(0))
    }

    // -----------------------------------------------------------------------------------------
    // 02 replace all rows — updating all 1,000 rows
    // -----------------------------------------------------------------------------------------

    @Test
    fun `replacing all rows builds the new table before dismantling the old`(): Unit = bench { driver ->
        repeat(WARMUP) { driver.click("run") }

        val patch = driver.click("run")

        // Every id is new, so there is nothing for keying to reuse and this is honestly a teardown
        // and a rebuild: a thousand rows arrive and a thousand leave. Pinned here because the order
        // is what the client depends on — the new rows are inserted at 0..999 first, so the removals
        // that follow all name index 1000 and the table is never briefly empty.
        val (inserts, removals) = patch.ops.partition { it is Op.Insert }
        assertEquals(ROWS, inserts.size, "got ${patch.ops.summary()}")
        assertEquals(ROWS, removals.size, "got ${patch.ops.summary()}")
        assertEquals(patch.ops.take(ROWS), inserts, "the inserts come first")
        assertEquals(listOf(ROWS), removals.map { (it as Op.Remove).index }.distinct())

        assertEquals(ROWS, driver.rowCount())
        // Six runs of a thousand, ids counting up throughout, so this table starts at 5001.
        assertEquals("${ROWS * WARMUP + 1}", driver.rowId(0))
    }

    // -----------------------------------------------------------------------------------------
    // 03 partial update — updating every 10th row for 1,000 rows
    // -----------------------------------------------------------------------------------------

    @Test
    fun `updating every 10th row writes 100 texts and touches no other row`(): Unit = bench { driver ->
        driver.click("run")
        repeat(3) { driver.click("update") }

        val patch = driver.click("update")

        assertEquals(ROWS / 10, patch.ops.size, "one text write per updated row, got ${patch.ops.summary()}")
        assertTrue(patch.ops.all { it is Op.SetText }, "got ${patch.ops.summary()}")

        // Four clicks of update in all, so an updated row carries four suffixes; the nine rows
        // between each pair of them carry none, because nothing invalidated them.
        assertTrue(driver.rowLabel(0).endsWith(" !!! !!! !!! !!!"), driver.rowLabel(0))
        assertFalse(driver.rowLabel(1).endsWith("!!!"), driver.rowLabel(1))
        assertTrue(driver.rowLabel(10).endsWith(" !!! !!! !!! !!!"), driver.rowLabel(10))
    }

    // -----------------------------------------------------------------------------------------
    // 04 select row — highlighting a selected row
    // -----------------------------------------------------------------------------------------

    @Test
    fun `moving the highlight writes two class attributes`(): Unit = bench { driver ->
        driver.click("run")
        driver.selectRow(4)

        val patch = driver.selectRow(1)

        // The row that lost the highlight and the row that gained it. Nothing else recomposes,
        // because a row reads its own selection rather than the table's.
        assertEquals(2, patch.ops.size, "got ${patch.ops.summary()}")
        assertTrue(patch.ops.all { it is Op.SetAttr && it.name == "class" }, "got ${patch.ops.summary()}")
        // One gains the class and one loses it; which order they recompose in is Compose's business.
        assertEquals(setOf("danger", null), patch.ops.map { (it as Op.SetAttr).value }.toSet())

        assertTrue(driver.rowSelected(1))
        assertEquals(1, driver.selectedCount())
    }

    // -----------------------------------------------------------------------------------------
    // 05 swap rows — swap 2 rows for a table with 1,000 rows
    // -----------------------------------------------------------------------------------------

    @Test
    fun `swapping two rows moves nodes instead of rewriting them`(): Unit = bench { driver ->
        driver.click("run")
        repeat(WARMUP + 1) { driver.click("swaprows") }
        val before = driver.rowId(1) to driver.rowId(998)

        val patch = driver.click("swaprows")

        // The whole argument for keying, in one assertion. Without `key(row.id)` the two rows would
        // stay where they are and their contents would be rewritten in place: two text writes and
        // no moves, on a table where every row's identity has silently shifted.
        // Two moves: one lifts the lower row up, one slides the block in between back down. No
        // text is rewritten and no node is rebuilt, which is the whole point of the key.
        assertEquals(2, patch.ops.size, "got ${patch.ops.summary()}")
        assertTrue(patch.ops.all { it is Op.Move }, "a keyed swap must move nodes, got ${patch.ops.summary()}")

        assertEquals(before.second, driver.rowId(1))
        assertEquals(before.first, driver.rowId(998))
    }

    // -----------------------------------------------------------------------------------------
    // 06 remove row — removing one row
    // -----------------------------------------------------------------------------------------

    @Test
    fun `removing a row sends one removal and shifts nothing`(): Unit = bench { driver ->
        driver.click("run")
        for (i in 0 until WARMUP) driver.removeRow(WARMUP - i + ROWS_TO_SKIP - 1)
        driver.removeRow(ROWS_TO_SKIP + 1)
        val below = driver.rowId(ROWS_TO_SKIP)

        val patch = driver.removeRow(ROWS_TO_SKIP - 1)

        // One op for the row that went, and none for the 990-odd rows whose position changed. Their
        // nodes did not move; the index they sit at is the browser's business.
        val removal = assertIs<Op.Remove>(patch.ops.single(), "got ${patch.ops.summary()}")
        assertEquals(ROWS_TO_SKIP - 1, removal.index)
        assertEquals(1, removal.count)
        assertEquals(below, driver.rowId(ROWS_TO_SKIP - 1))
        assertEquals(ROWS - WARMUP - 2, driver.rowCount())
    }

    // -----------------------------------------------------------------------------------------
    // 07 create many rows — creating 10,000 rows
    // -----------------------------------------------------------------------------------------

    @Test
    fun `creating 10000 rows stays one insert per row`(): Unit = bench { driver ->
        repeat(WARMUP) { driver.click("run"); driver.click("clear") }

        val patch = driver.click("runlots")

        assertEquals(MANY_ROWS, patch.ops.size, "got ${patch.ops.summary()}")
        assertTrue(patch.ops.all { it is Op.Insert })
        assertEquals(MANY_ROWS, driver.rowCount())
    }

    // -----------------------------------------------------------------------------------------
    // 08 append rows to large table — appending 1,000 to a table of 1,000 rows
    // -----------------------------------------------------------------------------------------

    @Test
    fun `appending 1000 rows leaves the existing 1000 untouched`(): Unit = bench { driver ->
        repeat(WARMUP) { driver.click("run"); driver.click("clear") }
        driver.click("run")
        val first = driver.rowId(0)

        val patch = driver.click("add")

        assertEquals(ROWS, patch.ops.size, "only the arriving rows, got ${patch.ops.summary()}")
        assertTrue(patch.ops.all { it is Op.Insert })
        // Every insert lands past the rows that were already there.
        assertTrue(patch.ops.all { (it as Op.Insert).index >= ROWS + TBODY_CHILDREN })

        assertEquals(first, driver.rowId(0))
        assertEquals(ROWS * 2, driver.rowCount())
    }

    // -----------------------------------------------------------------------------------------
    // 09 clear rows — clearing a table with 1,000 rows
    // -----------------------------------------------------------------------------------------

    @Test
    fun `clearing a table of 1000 rows sends one removal per row`(): Unit = bench { driver ->
        repeat(WARMUP) { driver.click("run"); driver.click("clear") }
        driver.click("run")

        val patch = driver.click("clear")

        // A thousand removals, each taking one row off the front, rather than the single
        // `Remove(tbody, 0, 1000)` the protocol can express. Compose hands the applier one removal
        // per keyed group and nothing downstream merges them, so an operation that could be eighty
        // bytes on the wire is forty kilobytes of it. Pinned as it stands rather than as it ought to
        // be, so that coalescing them is a test that changes rather than a regression that hides.
        assertEquals(ROWS, patch.ops.size, "got ${patch.ops.summary()}")
        val removals = patch.ops.map { assertIs<Op.Remove>(it) }
        assertEquals(listOf(TBODY_CHILDREN), removals.map { it.index }.distinct())
        assertEquals(listOf(1), removals.map { it.count }.distinct())
        assertEquals(0, driver.rowCount())
    }

    // -----------------------------------------------------------------------------------------
    // The page itself, which the harness selects against.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the page carries the ids and classes the benchmark requires`(): Unit = bench { driver ->
        val ids = driver.read { owner -> owner.root.all { it.tag == "button" }.map { it.attribute("id") } }
        assertEquals(listOf("run", "runlots", "add", "update", "clear", "swaprows"), ids)

        val table = driver.read { owner -> owner.root.first { it.tag == "table" } }
        assertEquals("table table-hover table-striped test-data", table?.attribute("class"))

        // The preload icon exists so the browser has fetched the glyph font before the first row
        // needs it. The harness checks for it, and it must be hidden from assistive technology.
        val preload = driver.read { owner -> owner.root.first { it.attribute("class")?.startsWith("preloadicon") == true } }
        assertEquals("true", preload?.attribute("aria-hidden"))

        // And it really reaches the browser, rather than only existing on the server's tree.
        val html = driver.html()
        assertTrue("id=\"run\"" in html, html.take(400))
        assertTrue("<tbody" in html && "id=\"tbody\"" in html)
    }

    @Test
    fun `a row has the four cells the harness selects on`(): Unit = bench { driver ->
        driver.click("run")

        val cells = driver.read { owner -> owner.tbody().children().first().children() }
        assertEquals(
            listOf("col-md-1", "col-md-4", "col-md-1", "col-md-6"),
            cells.map { it.attribute("class") },
        )
        assertEquals("1", cells[0].text(), "the first cell holds the id")
        assertEquals("a", cells[1].children().single().tag, "the second holds the label link")

        // The third holds the remove link, and the glyph inside it is what the harness clicks.
        val glyph = cells[2].children().single().children().single()
        assertEquals("span", glyph.tag)
        assertEquals("glyphicon glyphicon-remove", glyph.attribute("class"))
        assertEquals("true", glyph.attribute("aria-hidden"))

        assertTrue(cells[3].childNodes.isEmpty(), "and the fourth is empty")
    }

    @Test
    fun `ids keep counting up across runs, as the harness checks`(): Unit = bench { driver ->
        driver.click("run")
        assertEquals("1", driver.rowId(0))
        assertEquals("$ROWS", driver.rowId(ROWS - 1))

        driver.click("run")
        assertEquals("${ROWS + 1}", driver.rowId(0))

        driver.click("add")
        assertEquals("${ROWS * 2 + 1}", driver.rowId(ROWS))
    }

    private companion object {
        /** The reference harness's `warmupCount` for most of the operations. */
        const val WARMUP = 5

        /** Its `rowsToSkip`, as a 1-based `nth-of-type`. */
        const val ROWS_TO_SKIP = 4

        /** Rows are the only children of `<tbody>`, so the first one sits at index 0. */
        const val TBODY_CHILDREN = 0
    }
}

/** Runs [block] against a fresh session, closed afterwards whatever happens. */
private fun bench(block: suspend (Driver) -> Unit): Unit = runBlocking {
    Driver.open().use { driver -> block(driver) }
}

/** A short census of an op list, for a failure message that says what actually arrived. */
private fun List<Op>.summary(): String =
    "${size} ops: " + groupingBy { it::class.simpleName }.eachCount()
