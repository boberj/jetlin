package jetlin.samples.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One todo in the shared store. */
class Todo(id: Int, title: String, notes: String = "", done: Boolean = false) {
    val id: Int = id

    // The fields are Compose state, so any composable that reads one runs again when it changes,
    // including composables in other users' sessions, because the whole process shares this store.
    var title: String by mutableStateOf(title)
    var notes: String by mutableStateOf(notes)
    var done: Boolean by mutableStateOf(done)
}

/**
 * A shared in-memory store that stands in for a database.
 *
 * It's deliberately shared by the whole process: open the demo in two browser windows, and an edit
 * in one appears in the other. Nothing subscribes or broadcasts. Both sessions read the same state
 * objects, so both recompose.
 */
object TodoStore {
    private var nextId = 1

    /** The todos, in display order. */
    val todos = mutableStateListOf<Todo>()

    init {
        reset()
    }

    /**
     * Returns the demo to the state it starts in.
     *
     * Shared state is the point of this store, which also means anyone can leave it in a mess, such
     * as a visitor who deleted everything or a test that just finished. Instead of making callers
     * clean up after themselves, one button puts it back.
     *
     * Every open session sees this immediately, because they read the same state objects, so they
     * all recompose.
     */
    fun reset() {
        todos.clear()
        nextId = 1
        add("Read the architecture doc", "Start with the update path in section 1.")
        add("Run the tests", "./gradlew test and the Playwright suite.")
        add("Open this page twice", "Edits in one window show up in the other.")
    }

    /** Adds a todo at the end of the list, and returns it. */
    fun add(title: String, notes: String = ""): Todo =
        Todo(nextId++, title, notes).also { todos += it }

    /** Returns the todo with [id], or `null` if there's none. */
    fun find(id: Int): Todo? = todos.firstOrNull { it.id == id }

    /** Removes [todo] from the list. */
    fun remove(todo: Todo) {
        todos.remove(todo)
    }

    /** Moves [todo] by [offset] positions. It does nothing if that would move it off the list. */
    fun move(todo: Todo, offset: Int) {
        val from = todos.indexOf(todo)
        val to = from + offset
        if (from < 0 || to !in todos.indices) return
        todos.removeAt(from)
        todos.add(to, todo)
    }
}
