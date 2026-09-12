package jetlin.samples.teams

import jetlin.db.Db
import jetlin.db.authenticate
import jetlin.db.insertUnchecked
import jetlin.db.unsafe
import jetlin.html.LiveView
import jetlin.html.RequestContext
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking

/**
 * Measures the two things that share a heap: the resident graph and the live sessions.
 *
 * Residency is what makes every read cheap and every traversal a pointer dereference, and the bill for it
 * is memory — shared with the compositions of everyone currently connected. Those two numbers together are
 * the ceiling on what a node can carry, so they are worth knowing before they are discovered.
 *
 * Run with: ./gradlew :samples:teams:benchmark
 */
fun main(): Unit = runBlocking {
    val rowCount = System.getenv("ROWS")?.toInt() ?: 20_000
    val sessionCount = System.getenv("SESSIONS")?.toInt() ?: 200

    val directory = createTempDirectory("jetlin-teams-benchmark")
    val db = openSeeded(directory.resolve("benchmark.db"))
    val alice = checkNotNull(db.authenticate(User::class) { it.email == "alice@example.com" })

    // Warm up: class loading, the JIT and SQLite's own structures are not the thing being measured.
    unsafe("benchmark warm-up") {
        db.transact { repeat(200) { db.insertUnchecked(Todo(alice, "warm up $it")) } }
    }
    repeat(20) { LiveView(content = { _ -> }).also { it.start() }.close() }

    val beforeGraph = usedHeap()
    unsafe("benchmark rows") {
        db.transact {
            // Owned by somebody else on no team, so the rows are resident without being on the page the
            // sessions below render. A page that listed twenty thousand rows would be measuring the
            // virtual DOM, which `samples/demo:benchmark` already does.
            val bulk = db.insertUnchecked(User("Bulk", "bulk@example.com"))
            repeat(rowCount) { index ->
                db.insertUnchecked(Todo(bulk, "Row $index of a benchmark, with a title of ordinary length"))
            }
        }
    }
    val afterGraph = usedHeap()
    val perRow = (afterGraph - beforeGraph) / rowCount

    // A baseline first: the same number of sessions composing nothing, so that what the page and the
    // graph cost can be told apart from what a session costs before either.
    val baselineViews = ArrayList<LiveView>(sessionCount)
    repeat(sessionCount) { baselineViews += LiveView(content = { _ -> }).also { it.start() } }
    val afterBaseline = usedHeap()
    val perBaseline = (afterBaseline - afterGraph) / sessionCount
    check(baselineViews.size == sessionCount)
    baselineViews.forEach { it.close() }

    // Sessions on top of that graph, each composing the real page against it.
    val request = RequestContext(path = "/").with(ViewerKey, alice)
    val views = ArrayList<LiveView>(sessionCount)
    repeat(sessionCount) {
        val view = LiveView(request) { _ -> WithViewer { TodoListPage(db) } }
        view.start()
        views += view
    }
    val afterSessions = usedHeap()
    val perSession = (afterSessions - afterBaseline) / sessionCount

    println("rows:               $rowCount resident records")
    println("graph:              $perRow bytes per record (${(afterGraph - beforeGraph) / 1024 / 1024} MB total)")
    println("session baseline:   ${perBaseline / 1024} kB per session composing nothing")
    println("sessions:           $sessionCount, each listing the same graph")
    println("live:               ${perSession / 1024} kB per session (${(afterSessions - afterGraph) / 1024 / 1024} MB total)")
    println("together:           ${(afterSessions - beforeGraph) / 1024 / 1024} MB for $rowCount rows and $sessionCount sessions")
    println()
    println("The graph figure is stable across row counts; the session figures are a heap delta and are")
    println("noisy below a few hundred sessions. Raise SESSIONS before drawing a conclusion from them.")

    // Keep everything reachable until after the last measurement, or this measures garbage collection.
    check(views.size == sessionCount)
    views.forEach { it.close() }
    db.close()
}

private fun usedHeap(): Long {
    val runtime = Runtime.getRuntime()
    repeat(4) {
        System.gc()
        Thread.sleep(120)
    }
    return runtime.totalMemory() - runtime.freeMemory()
}
