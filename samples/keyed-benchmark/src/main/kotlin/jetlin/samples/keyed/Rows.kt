package jetlin.samples.keyed

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * One table row, as the benchmark defines it: an id that never changes and a label that does.
 *
 * Both mutable fields are Compose state, and that is the whole reason the numbers come out the way
 * they do. A composable that reads `label` is the only thing invalidated when `label` is written,
 * so "update every 10th row" wakes a hundred rows rather than a thousand. `selected` is here for
 * the same reason: holding the selected id in the store instead would make every row a reader of
 * it, and moving the highlight would recompose the entire table to change two attributes.
 *
 * `@Stable` is load-bearing, not decoration. Compose infers stability from the declared types, sees
 * two `var`s and concludes that a composable given this row cannot safely skip — so every insert or
 * removal anywhere in the table would re-run all thousand rows in full rather than compare two
 * references and stop. The annotation is the promise that makes skipping legal, and it is a true
 * one here: both fields are backed by snapshot state, so a reader is always notified of a change.
 */
@Stable
class Row(val id: Int, label: String) {
    var label: String by mutableStateOf(label)
    var selected: Boolean by mutableStateOf(false)
}

private val ADJECTIVES = listOf(
    "pretty", "large", "big", "small", "tall", "short", "long", "handsome", "plain", "quaint",
    "clean", "elegant", "easy", "angry", "crazy", "helpful", "mushy", "odd", "unsightly",
    "adorable", "important", "inexpensive", "cheap", "expensive", "fancy",
)

private val COLOURS = listOf(
    "red", "yellow", "blue", "green", "pink", "brown", "purple", "white", "black", "orange",
)

private val NOUNS = listOf(
    "table", "chair", "house", "bbq", "desk", "car", "pony", "cookie", "sandwich", "burger",
    "pizza", "mouse", "keyboard",
)

/**
 * The rows on the page and the six operations the benchmark drives them with.
 *
 * Ids keep counting up across every call, exactly as the reference implementation's do: "replace
 * all rows" is a thousand *new* rows, not a thousand relabelled ones, which is what makes it a
 * teardown-and-rebuild for any keyed framework rather than a thousand text writes.
 *
 * [seed] is fixed by default so that a run is reproducible and the tests can name a label. The
 * labels are drawn from the same three word lists as the reference implementation, so the strings
 * being written are the same length and shape.
 *
 * `@Stable` for the same reason [Row] is: a row composable takes the store so it can report its own
 * clicks, and an unstable parameter would defeat skipping just as thoroughly as an unstable row.
 * The mutable list it holds is snapshot state and notifies its readers; [selectedId] is derived from
 * per-row state that does the same.
 */
@Stable
class RowStore(seed: Int = 0) {

    val rows: SnapshotStateList<Row> = mutableStateListOf()

    private val random = Random(seed)
    private var nextId = 1
    private var selected: Row? = null

    /** The id of the highlighted row, or null. Not Compose state; see [Row.selected]. */
    val selectedId: Int? get() = selected?.id

    private fun label(): String =
        "${ADJECTIVES.random(random)} ${COLOURS.random(random)} ${NOUNS.random(random)}"

    private fun build(count: Int): List<Row> = List(count) { Row(nextId++, label()) }

    /** `#run` and `#runlots`: replace everything with [count] freshly built rows. */
    fun run(count: Int) {
        clearSelection()
        rows.clear()
        rows.addAll(build(count))
    }

    /** `#add`: append [count] rows, leaving the existing ones alone. */
    fun add(count: Int) {
        rows.addAll(build(count))
    }

    /** `#update`: append " !!!" to every tenth label. */
    fun update(step: Int = 10) {
        var index = 0
        while (index < rows.size) {
            val row = rows[index]
            row.label = row.label + " !!!"
            index += step
        }
    }

    /** `#clear`. */
    fun clear() {
        clearSelection()
        rows.clear()
    }

    /**
     * `#swaprows`: exchange the second row with the second-to-last.
     *
     * The reference implementation swaps indices 1 and 998 and does nothing at all below 999 rows,
     * so the "swap 2 rows" measurement always moves the same two positions a long way apart.
     */
    fun swap() {
        if (rows.size > SWAP_UPPER) swap(SWAP_LOWER, SWAP_UPPER)
    }

    /** The same exchange between any two positions, for measuring how the cost grows with the table. */
    fun swap(lower: Int, upper: Int) {
        val first = rows[lower]
        val second = rows[upper]
        rows[lower] = second
        rows[upper] = first
    }

    /** Clicking a row's label. */
    fun select(row: Row) {
        if (selected === row) return
        selected?.selected = false
        row.selected = true
        selected = row
    }

    /** Clicking a row's remove glyph. */
    fun remove(row: Row) {
        if (selected === row) selected = null
        rows.remove(row)
    }

    private fun clearSelection() {
        selected?.selected = false
        selected = null
    }

    private companion object {
        const val SWAP_LOWER = 1
        const val SWAP_UPPER = 998
    }
}

/**
 * The reference implementation's `_random`, kept rather than replaced with `nextInt(max)`.
 *
 * `Math.round(Math.random() * 1000) % max` is not uniform for any max that does not divide 1001,
 * which is all three of these lists. Straightening it out would change which labels come up and
 * how often they repeat, and label content is an input to the string work being measured.
 */
private fun <T> List<T>.random(random: Random): T =
    this[(random.nextDouble() * 1000).roundToInt() % size]
