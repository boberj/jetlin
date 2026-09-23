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
import jetlin.runtime.fresh
import jetlin.runtime.rememberAction

/**
 * The page chrome. It's composed once per session, around whichever view is current.
 *
 * `IfPermitted` hides the admin link with the same guard that protects the route, so the link and
 * the route can't disagree about who can use them.
 *
 * @param hub the external system, or `null` to leave out the announcement banner.
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
        // Show external data in the chrome, which is where an announcement banner usually goes. One
        // Fetch serves the whole process, so a hundred sessions showing the banner share one request
        // per interval. When the announcement changes, the write that stores the new value
        // recomposes all of those sessions.
        if (principal != null && hub != null) Announcement(hub)
        content()
    }
}

/**
 * The announcement banner, shown once the announcement has loaded.
 *
 * While the announcement is loading, or if it failed to load, nothing is rendered. A placeholder or
 * an error message would be out of place in the chrome. The code still handles all three [Fetched]
 * states. Two of them render nothing.
 *
 * On the first page load after a restart, the banner is missing from the server-rendered HTML and
 * appears a moment later. The first read starts the fetch, and the first paint doesn't wait for
 * network calls. After that, the whole process caches the value, so every later page load includes
 * it in the server-rendered HTML, even in other sessions.
 *
 * `fresh` keeps the value current. While any page shows the banner, the value is fetched again
 * periodically, and the polling stops when the last such page closes. The value counts its
 * watchers, not each session, so ten viewers cost the same as one.
 */
@Composable
private fun Announcement(hub: Hub) {
    val text = (hub.announcement.fresh(every = hub.refreshEvery) as? Fetched.Ready)?.value ?: return
    Div({ classes("banner"); testTag("banner") }) { Text(text) }
}

/**
 * The sign-in page. Choosing a seeded account sets a cookie.
 *
 * It reads nothing from the database. The sign-in page is shown before there's a principal, so it
 * lists the sample's fixed accounts instead of querying for them, which would need a way around the
 * policy checks.
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
                        // A plain link to a route that sets a cookie. jetlin-db isn't involved.
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
 * The principal's own todos, and any that are shared with their team.
 *
 * `db.todos` is a generated collection that the policy filters. Iterating it subscribes this
 * composable to the list and to everything the policy read. That's why a todo appears here as soon
 * as a teammate shares it, with no other code involved.
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

/** One todo, with the controls its owner can use. */
@Composable
context(principal: User)
private fun TodoRow(db: Db, todo: Todo) {
    val mine = todo.owner == principal
    Li({ classes("todo"); testTag("todo") }) {
        Input({
            attr("type", "checkbox")
            testTag("done")
            // A teammate can see the todo but not change it, so disable the checkbox instead of letting
            // the write fail.
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

/** The detail page for one todo. The route already looked it up, so the principal can read it. */
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
                // The column-level policy: the checkbox is disabled for anyone but an admin, and the
                // server would refuse the write even if the event were sent anyway.
                disabled(!principal.admin)
                onChange { todo.update { archived = !archived } }
            })
            Span { Text("Archived (admins only)") }
        }
        Link("/") { Text("Back") }
    }
}

/** Pattern 1: private notes that only their owner can see. */
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

/** A page for admins only, which lists users and teams. The route table declares its guard. */
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
                            // If a user is viewing this page when their admin role is revoked,
                            // they're moved off it. The route guard read `admin`, so changing it runs
                            // the guard again.
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
 * A page that shows data from the external system.
 *
 * None of this data is stored or policy-checked, but the page works much like the others. Reading a
 * value subscribes the page, an arrival recomposes it, and a write can be refused, here by the
 * external system instead of a policy. The main difference is that every value has three possible
 * states, loading, failed, or ready, instead of one. That's the cost of data that lives elsewhere,
 * and it's why `Fetched` is a sealed type instead of a nullable value.
 */
@Composable
context(principal: User)
fun HubPage(hub: Hub) {
    val status = rememberSavedField("", key = "status") {
        if (it.isBlank()) "Say something" else null
    }
    // Remember the action in this page, not globally, because its state belongs to this button.
    val save = rememberAction { hub.setStatus(principal, status.value.trim()) }

    Div({ classes("card") }) {
        H1 { Text("Hub") }
        P({ classes("muted") }) {
            Text("An external system, stubbed in this process. Not stored, not resident, not gated.")
        }

        H2 { Text("Announcement") }
        // The announcement is the same for everyone, so one Fetch serves every session. A hundred
        // sessions reading it share its requests.
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
                // Disable the button while the request runs. This is why an action exposes its state
                // instead of only launching a coroutine.
                disabled(!status.isValid || save.state is Run.Running)
                onClick { save() }
            }) { Text(if (save.state is Run.Running) "Saving…" else "Save") }
        }
        // Show errors the application couldn't predict, next to the control that caused them. The
        // action catches the exception, so a failed command produces only this message, and the
        // session carries on.
        (save.state as? Run.Failed)?.let { failed ->
            P({ classes("muted"); testTag("status-error") }) { Text(failed.cause.message.orEmpty()) }
        }
    }
}

/** Formats each of the three [Fetched] states as display text. */
private fun <V> Fetched<V>.say(ready: (V) -> String): String = when (this) {
    is Fetched.Loading -> "…"
    is Fetched.Failed -> "unavailable"
    is Fetched.Ready -> ready(value)
}
