package jetlin.samples.teams

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import jetlin.db.Db
import jetlin.db.View
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.H1
import jetlin.html.H2
import jetlin.html.IfPermitted
import jetlin.html.Input
import jetlin.html.Li
import jetlin.html.Link
import jetlin.html.LocalRequest
import jetlin.html.Nav
import jetlin.html.P
import jetlin.html.Span
import jetlin.html.Text
import jetlin.html.Ul
import jetlin.html.bind
import jetlin.html.rememberSavedField

/**
 * The chrome, composed once for the session above whichever view is current.
 *
 * The admin link is hidden by the same guard that blocks the route, which is the point of `IfPermitted`:
 * hiding a link and refusing a route are one fact, and two copies of it drift.
 */
@Composable
fun Shell(content: @Composable () -> Unit) {
    val principal = Principals.of(LocalRequest.current)
    Div({ classes("page") }) {
        Nav({ classes("nav") }) {
            Link("/", { classes("brand") }) { Text("Teams") }
            if (principal != null) {
                Link("/") { Text("Todos") }
                Link("/notes") { Text("Notes") }
                IfPermitted("/admin/users") { Link("/admin/users") { Text("Users") } }
                Span({ classes("who"); testTag("principal") }) {
                    Text("${principal.name}${principal.team?.let { " · ${it.name}" } ?: ""}")
                }
                Link("/login", { classes("link") }) { Text("Switch user") }
            }
        }
        content()
    }
}

/**
 * Signing in, such as it is: pick a seeded account and a cookie is set.
 *
 * Reads nothing from the database. A sign-in page is the one page whose job is to precede having a principal,
 * and the accounts it offers are this sample's fixture rather than data — which keeps the page from needing
 * a hole in the gate to render itself.
 */
@Composable
fun SignInPage() {
    Div({ classes("card") }) {
        H1 { Text("Sign in") }
        P { Text("Pick someone. Open a second window, pick someone else, and watch a share arrive.") }
        Ul({ classes("people") }) {
            for (account in SEEDED_ACCOUNTS) {
                key(account.email) {
                    Li({ classes("person") }) {
                        // A plain link to a route that sets a cookie. Nothing here is jetlin-db's business.
                        Link("/signin/${account.email}", { testTag("signin-${account.label.lowercase()}") }) {
                            Text(account.label)
                        }
                        account.note?.let { Span({ classes("muted") }) { Text(" · $it") } }
                    }
                }
            }
        }
    }
}

/**
 * Todos: the principal's own, plus anything shared with their team.
 *
 * `db.todos` is a generated, policy-filtered collection. Iterating it subscribes this composition to the
 * list *and* to whatever the policy read — which is why sharing a todo with the team makes it appear here
 * with nothing else happening.
 */
@Composable
context(principal: User)
fun TodoListPage(db: Db) {
    val draft = rememberSavedField("", key = "draft") {
        if (it.isBlank()) "Enter something to do" else null
    }

    Div({ classes("card") }) {
        H1 { Text("Todos") }
        Div({ classes("row") }) {
            Input({
                classes("input")
                testTag("draft")
                attr("placeholder", "What needs doing?")
                bind(draft)
            })
            Button({
                classes("btn")
                testTag("add")
                disabled(!draft.isValid)
                onClick {
                    if (draft.isValid) {
                        db.todos.add(Todo(principal, draft.value.trim()))
                        draft.value = ""
                    }
                }
            }) { Text("Add") }
        }

        Ul({ classes("todos"); testTag("todos") }) {
            for (todo in db.todos.sortedBy { it.archived }) {
                key(todo.id) { TodoRow(db, todo) }
            }
        }
        if (db.todos.isEmpty()) P({ classes("muted") }) { Text("Nothing here yet.") }
    }
}

@Composable
context(principal: User)
private fun TodoRow(db: Db, todo: Todo) {
    val mine = todo.owner == principal
    Li({ classes("todo"); testTag("todo") }) {
        Input({
            attr("type", "checkbox")
            testTag("done")
            // A teammate can see it and cannot change it, so the checkbox says so rather than failing.
            disabled(!mine)
            if (todo.done) attr("checked", "")
            onChange { if (mine) todo.update { done = !done } }
        })
        Link("/todo/${todo.id}", { classes("todo-text") }) { Text(todo.title) }
        if (!mine) Span({ classes("muted"); testTag("owner") }) { Text("· ${todo.owner.name}") }
        if (todo.team != null) Span({ classes("badge"); testTag("shared") }) { Text(todo.team?.name ?: "") }
        if (mine) {
            Button({
                classes("link")
                testTag("share")
                onClick { todo.update { team = if (team == null) principal.team else null } }
            }) { Text(if (todo.team == null) "Share with team" else "Unshare") }
            Button({ classes("link"); testTag("delete"); onClick { todo.delete() } }) { Text("Delete") }
        }
    }
}

/** One todo, reached by a route that resolved it. A principal who may not read it never gets here. */
@Composable
context(principal: User)
fun TodoDetailPage(db: Db, todo: Todo) {
    val mine = todo.owner == principal
    Div({ classes("card") }) {
        H1({ testTag("title") }) { Text(todo.title) }
        P({ classes("muted") }) { Text("Owned by ${todo.owner.name}") }
        if (mine) {
            Div({ classes("row") }) {
                Button({
                    classes("btn")
                    testTag("rename")
                    onClick { todo.update { title = "$title (edited)" } }
                }) { Text("Append “(edited)”") }
            }
        }
        Div({ classes("row") }) {
            Input({
                attr("type", "checkbox")
                testTag("archived")
                if (todo.archived) attr("checked", "")
                // Column-level policy: the control is disabled for anyone who is not an admin, and the
                // write would be refused even if the browser sent it anyway.
                disabled(!principal.admin)
                onChange { todo.update { archived = !archived } }
            })
            Span { Text("Archived (admins only)") }
        }
        Link("/") { Text("Back") }
    }
}

/** Shape 1: notes nobody else can see, however they ask. */
@Composable
context(principal: User)
fun NotesPage(db: Db) {
    Div({ classes("card") }) {
        H1 { Text("Private notes") }
        P({ classes("muted") }) { Text("Only yours, whoever else is signed in elsewhere.") }
        Ul({ classes("notes") }) {
            for (note in db.notes) {
                key(note.id) { Li({ classes("note"); testTag("note") }) { Text(note.text) } }
            }
        }
    }
}

/** An admin-only page, guarded in the route table. */
@Composable
context(principal: User)
fun AdminUsersPage(db: Db) {
    Div({ classes("card") }) {
        H1 { Text("Users") }
        Ul({ classes("people") }) {
            for (user in db.users) {
                key(user.id) {
                    Li({ classes("person"); testTag("user") }) {
                        Text(user.name)
                        Span({ classes("muted") }) { Text(" · ${user.email}") }
                        user.team?.let { Span({ classes("badge") }) { Text(it.name) } }
                        Button({
                            classes("link")
                            testTag("toggle-admin")
                            // Revoking a role while someone is looking at this page moves them off it:
                            // the guard read `admin`, so writing it re-evaluates the guard.
                            onClick { user.update { admin = !admin } }
                        }) { Text(if (user.admin) "Revoke admin" else "Make admin") }
                    }
                }
            }
        }
        H2 { Text("Teams") }
        Ul { for (team in db.teams) key(team.id) { Li { Text(team.name) } } }
    }
}
