package jetlin.samples.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class Todo(id: Int, title: String, notes: String = "", done: Boolean = false) {
    val id: Int = id

    // Compose state, so any composable that reads a field re-runs when it changes — including
    // composables in *other* users' sessions, since this store is shared across the process.
    var title: String by mutableStateOf(title)
    var notes: String by mutableStateOf(notes)
    var done: Boolean by mutableStateOf(done)
}

/**
 * A shared in-memory store standing in for a database.
 *
 * Deliberately process-wide: open the demo in two browser windows and an edit in one appears in the
 * other. Nothing subscribes or broadcasts — both sessions read the same state objects, so both
 * recompose.
 */
object TodoStore {
    private var nextId = 1
    val todos = mutableStateListOf<Todo>()

    init {
        add("Read the architecture doc", "Start with the update path in section 1.")
        add("Run the tests", "./gradlew test and the Playwright suite.")
        add("Open this page twice", "Edits in one window show up in the other.")
    }

    fun add(title: String, notes: String = ""): Todo =
        Todo(nextId++, title, notes).also { todos += it }

    fun find(id: Int): Todo? = todos.firstOrNull { it.id == id }

    fun remove(todo: Todo) {
        todos.remove(todo)
    }

    fun move(todo: Todo, offset: Int) {
        val from = todos.indexOf(todo)
        val to = from + offset
        if (from < 0 || to !in todos.indices) return
        todos.removeAt(from)
        todos.add(to, todo)
    }
}
