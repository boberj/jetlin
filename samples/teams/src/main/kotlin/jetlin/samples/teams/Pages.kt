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
import jetlin.runtime.Fetched
import jetlin.runtime.Run
import jetlin.runtime.rememberAction

/**
 * The chrome, composed once for the session above whichever view is current.
 *
 * The admin link is hidden by the same guard that blocks the route, which is the point of `IfPermitted`:
 * hiding a link and refusing a route are one fact, and two copies of it drift.
 */
@Composable
fun Shell(hub: Hub?, content: @Composable () -> Unit) {
    val principal = Principals.of(LocalRequest.current)
    Div({ classes("page") }) {
        Nav({ classes("nav") }) {
            Link("/", { classes("brand") }) { Text("Teams") }
            if (principal != null) {
                Link("/") { Text("Todos") }
                Link("/notes") { Text("Notes") }
                Link("/hub") { Text("Hub") }
                IfPermitted("/admin/users") { Link("/admin/users") { Text("Users") } }
                Span({ classes("who"); testTag("principal") }) {
                    Text("${principal.name}${principal.team?.let { " · ${it.name}" } ?: ""}")
                }
                Link("/login", { classes("link") }) { Text("Switch user") }
            }
        }
        // External data in the chrome rather than on a page of its own, because that is where this kind
        // of thing actually goes. One Fetch behind it for the whole process, so a hundred sessions
        // showing this banner cost one request a minute between them — and when it changes at the other
        // end, every one of those sessions is recomposed by the write that fills the cell.
        if (principal != null && hub != null) Announcement(hub)
        content()
    }
}

/**
 * The announcement, when there is one.
 *
 * Renders nothing at all while it is loading or if it failed: the chrome is not the place for a
 * placeholder, and an announcement nobody has yet is not news. The three-way read is still there — it is
 * just that two of the three answers are "say nothing", which is a perfectly good way to handle them.
 *
 * Worth watching on the very first page load after a restart: the banner is absent from the server-rendered
 * HTML and appears a moment later, because the first read is what started the fetch and nothing blocks a
 * first paint on a network call. Every load after that has it server-side, including the first one in
 * somebody else's session — there is one of these for the whole process.
 */
@Composable
private fun Announcement(hub: Hub) {
    val text = (hub.announcement.value as? Fetched.Ready)?.value ?: return
    Div({ classes("banner"); testTag("banner") }) { Text(text) }
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

/**
 * The other kind of data, on a page of its own.
 *
 * Nothing here is stored and nothing here is gated, and the page does not look very different for it: a
 * read subscribes, an arrival recomposes, a write is refused by whoever owns the data rather than by a
 * policy. What *is* different is that every read has three answers rather than one, which is the honest
 * cost of the value living somewhere else — and why `Fetched` is a type rather than a nullable.
 */
@Composable
context(principal: User)
fun HubPage(hub: Hub) {
    val status = rememberSavedField("", key = "status") {
        if (it.isBlank()) "Say something" else null
    }
    // Remembered per page rather than per application: the in-flight state belongs to this button.
    val save = rememberAction { hub.setStatus(principal, status.value.trim()) }

    Div({ classes("card") }) {
        H1 { Text("Hub") }
        P({ classes("muted") }) {
            Text("An external system, stubbed in this process. Not stored, not resident, not gated.")
        }

        H2 { Text("Announcement") }
        // One Fetch for everybody, because the announcement is the same for everybody. A hundred
        // sessions reading this cost one request a minute between them.
        P({ testTag("announcement") }) { Text(hub.announcement.value.say { it }) }

        H2 { Text("Your status") }
        P({ testTag("status") }) {
            Text(hub.profile(principal).value.say { "${it.status} · ${it.updates} updates" })
        }
        Div({ classes("row") }) {
            Input({
                classes("input")
                testTag("status-draft")
                attr("placeholder", "What are you up to?")
                bind(status)
            })
            Button({
                classes("btn")
                testTag("save-status")
                // The request is outstanding, so the button says so. This is the whole reason an action
                // has a state rather than being a fire-and-forget launch.
                disabled(!status.isValid || save.state is Run.Running)
                onClick { save() }
            }) { Text(if (save.state is Run.Running) "Saving…" else "Save") }
        }
        // A refusal the application could not have predicted, shown where the user is looking. The
        // session is untouched: an action catches, so a failed command costs a line of text.
        (save.state as? Run.Failed)?.let { failed ->
            P({ classes("muted"); testTag("status-error") }) { Text(failed.cause.message.orEmpty()) }
        }
    }
}

/** The three answers a fetched value can give, as a page would put them. */
private fun <V> Fetched<V>.say(ready: (V) -> String): String = when (this) {
    is Fetched.Loading -> "…"
    is Fetched.Failed -> "unavailable"
    is Fetched.Ready -> ready(value)
}
