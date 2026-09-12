package jetlin.samples.demo

import jetlin.db.Db
import jetlin.db.Entity
import jetlin.db.Id
import jetlin.db.Policy
import jetlin.db.Principal
import jetlin.db.Record
import jetlin.db.insertUnchecked
import jetlin.db.unsafe
import kotlin.io.path.createTempDirectory

/**
 * The demo's viewer, of which there is exactly one.
 *
 * A `Principal` need not be a stored record — it is whatever an application means by "who is asking" —
 * and this application has no accounts. Saying so in one line is better than inventing a user table to
 * satisfy a type: `:samples:teams` is where multiple viewers are the point.
 *
 * Because the viewer is a constant, it can be supplied inside this file and the pages never mention it.
 * An application with real accounts puts it in lexical scope at the root of each page instead; see
 * `docs/db.md` §3.
 */
object Visitor : Principal

@Entity
class Todo(
    title: String,
    notes: String = "",
    done: Boolean = false,
    position: Int = 0,
) : Record() {
    // Cells: Compose state with a name. A composable that reads one is subscribed to it, so a write
    // recomposes readers in *other* users' sessions too — the store is one shared list.
    var title: String by column(title)
    var notes: String by column(notes)
    var done: Boolean by column(done)

    /**
     * Where this sits in the list.
     *
     * A `View` has no order of its own beyond insertion, and this list can be reordered, so the order has
     * to be something stored rather than something the collection remembers.
     */
    var position: Int by column(position)

    companion object : Policy<Todo, Visitor> {
        // Everything here is everybody's: one shared list is the thing this demo exists to show. A policy
        // still has to say so, because an entity with no policy does not compile.
        override fun canRead(row: Todo, viewer: Visitor): Boolean = true
    }
}

/**
 * A shared store, now durable.
 *
 * Deliberately process-wide, as it was when it was a list in memory: open the demo in two browser windows
 * and an edit in one appears in the other. Nothing subscribes or broadcasts — both sessions read the same
 * cells, so both recompose. What is new is that the write reaches SQLite first, and only then becomes
 * visible to either of them.
 *
 * The database is a file in a temporary directory, recreated every time the demo starts, because a demo
 * that remembers yesterday's mess is worse than one that starts from a known state. A real application
 * opens a file it keeps and has run `./gradlew dbMigrate` against it.
 */
object TodoStore {

    private val db: Db =
        Db.open(createTempDirectory("jetlin-demo").resolve("demo.db"), JetlinSchema.tables)

    init {
        reset()
    }

    /** The list, in the order it is shown. Reading it subscribes whoever is composing. */
    val todos: List<Todo> get() = with(Visitor) { db.todos }.sortedBy { it.position }

    /**
     * Returns the demo to the state it starts in.
     *
     * Shared state is the point of this store, which also means anyone can leave it in a mess — a visitor
     * who deleted everything, or a test that just finished. Rather than have callers tidy up after
     * themselves, there is one button that puts it back.
     *
     * Every open session sees this immediately: they read the same cells, so they all recompose. One
     * transaction, so they see the finished list rather than an empty one on the way past.
     *
     * The seeded ids are part of the fixture — `/todo/1` is the first item, here and in both test suites —
     * which is why they are chosen rather than allocated.
     */
    fun reset() {
        db.transact {
            with(Visitor) { todos.forEach { it.delete() } }
            unsafe("the demo's reset re-seeds the list, ids and all") {
                db.insertUnchecked(
                    Todo(
                        "Read the architecture doc",
                        "Start with the update path in section 1.",
                        position = 1,
                    ),
                    id = 1,
                )
                db.insertUnchecked(
                    Todo("Run the tests", "./gradlew test and the Playwright suite.", position = 2),
                    id = 2,
                )
                db.insertUnchecked(
                    Todo(
                        "Open this page twice",
                        "Edits in one window show up in the other.",
                        position = 3,
                    ),
                    id = 3,
                )
            }
        }
    }

    fun add(title: String, notes: String = ""): Todo {
        val next = (todos.maxOfOrNull { it.position } ?: 0) + 1
        return with(Visitor) { db.todos.add(Todo(title, notes, position = next)) }
    }

    fun find(id: Long): Todo? = with(Visitor) { Todos.find(db, Id(id)) }

    fun remove(todo: Todo) {
        with(Visitor) { todo.delete() }
    }

    /** Ticking a box is a write, and a write goes through a draft rather than straight at the field. */
    fun setDone(todo: Todo, done: Boolean) {
        with(Visitor) { todo.update { this.done = done } }
    }

    fun edit(todo: Todo, title: String, notes: String) {
        with(Visitor) {
            todo.update {
                this.title = title
                this.notes = notes
            }
        }
    }

    /**
     * Moves [todo] by [offset] places, by swapping positions with whoever is there.
     *
     * One transaction for both writes: two rows change, and a session that saw one without the other would
     * see two items claiming the same place.
     */
    fun move(todo: Todo, offset: Int) {
        val ordered = todos
        val from = ordered.indexOf(todo)
        val to = from + offset
        if (from < 0 || to !in ordered.indices) return
        val other = ordered[to]
        db.transact {
            with(Visitor) {
                val vacated = todo.position
                todo.update { position = other.position }
                other.update { position = vacated }
            }
        }
    }
}
