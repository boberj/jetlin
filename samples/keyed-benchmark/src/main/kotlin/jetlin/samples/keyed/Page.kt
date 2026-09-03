package jetlin.samples.keyed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import jetlin.html.A
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.H1
import jetlin.html.Span
import jetlin.html.Table
import jetlin.html.Tbody
import jetlin.html.Td
import jetlin.html.Text
import jetlin.html.Tr

/**
 * The js-framework-benchmark page, keyed, as `@Composable` functions running on the server.
 *
 * The markup follows the benchmark's VanillaJS reference exactly — same wrapper classes, same six
 * button ids, same four cells per row, same `preloadicon` at the bottom — because the harness
 * selects on all of it and because a row that is cheaper to build than the reference's is not
 * measuring the same thing.
 *
 * What differs is where it runs. Nothing here is sent to the browser as code: the composition
 * lives on the server, a click arrives as an event naming a node, and what goes back is the list
 * of DOM edits the recomposition produced.
 */
@Composable
fun BenchmarkPage(store: RowStore) {
    Div({ classes("container") }) {
        Div({ classes("jumbotron") }) {
            Div({ classes("row") }) {
                Div({ classes("col-md-6") }) { H1 { Text("Jetlin-\"keyed\"") } }
                Div({ classes("col-md-6") }) {
                    Div({ classes("row") }) {
                        Action("run", "Create 1,000 rows") { store.run(ROWS) }
                        Action("runlots", "Create 10,000 rows") { store.run(MANY_ROWS) }
                        Action("add", "Append 1,000 rows") { store.add(ROWS) }
                        Action("update", "Update every 10th row") { store.update() }
                        Action("clear", "Clear") { store.clear() }
                        Action("swaprows", "Swap Rows") { store.swap() }
                    }
                }
            }
        }
        Table({ classes("table table-hover table-striped test-data") }) {
            Tbody({ id("tbody") }) {
                store.rows.forEach { row ->
                    // The keyed part, and the only line that separates this implementation from the
                    // non-keyed one. Without it a swap rewrites the text of two rows; with it the
                    // two nodes move and their contents are never touched.
                    key(row.id) { RowView(row, store) }
                }
            }
        }
        Span({ classes("preloadicon glyphicon glyphicon-remove"); attr("aria-hidden", "true") })
    }
}

/** One of the six buttons in the jumbotron, in the wrapper the reference markup puts it in. */
@Composable
private fun Action(id: String, label: String, onClick: () -> Unit) {
    Div({ classes("col-sm-6 smallpad") }) {
        Button({
            id(id)
            classes("btn btn-primary btn-block")
            type("button")
            onClick(onClick)
        }) { Text(label) }
    }
}

/**
 * One row.
 *
 * Reads exactly two pieces of state — this row's label and this row's highlight — so it is
 * invalidated by a change to this row and by nothing else. That is what keeps "partial update" to
 * a hundred recompositions out of a thousand rows, and "select row" to two.
 */
@Composable
private fun RowView(row: Row, store: RowStore) {
    Tr({ if (row.selected) classes("danger") }) {
        Td({ classes("col-md-1") }) { Text(row.id.toString()) }
        Td({ classes("col-md-4") }) {
            A({ onClick { store.select(row) } }) { Text(row.label) }
        }
        Td({ classes("col-md-1") }) {
            A({ onClick { store.remove(row) } }) {
                Span({ classes("glyphicon glyphicon-remove"); attr("aria-hidden", "true") })
            }
        }
        Td({ classes("col-md-6") })
    }
}

/** The row counts the benchmark's buttons are defined in terms of. */
const val ROWS: Int = 1_000
const val MANY_ROWS: Int = 10_000
