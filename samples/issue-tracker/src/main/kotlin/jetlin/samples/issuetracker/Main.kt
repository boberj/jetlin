package jetlin.samples.issuetracker

import io.ktor.http.ContentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import jetlin.server.jetlin

/**
 * An issue tracker in the style of the fast, keyboard-driven ones: teams, a grouped list, a board,
 * projects, a command palette.
 *
 * Where the demo exercises the framework's corners one page at a time, this is what an application
 * built on it looks like — and it runs on port 8081 so the two can be open side by side.
 */
fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8081
    embeddedServer(Netty, port = port) {
        routing {
            // Built by Tailwind and committed, like jetlin.js, so running this needs no node.
            asset("app.css", ContentType.Text.CSS)
            asset("app.js", ContentType.Text.JavaScript)
        }
        jetlin {
            // Written into the markup so a browser test has something to select on; a real
            // deployment would leave this off in production.
            exposeTestTags = true
            // app.css asks for Inter; without these it silently fell back to the system UI font.
            head = """
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
                <link href="https://fonts.googleapis.com/css2?family=Inter:opsz,wght@14..32,100..900&display=swap" rel="stylesheet">
                <link rel="stylesheet" href="/issue-tracker/app.css">
            """.trimIndent()
            clientSetup = """<script src="/issue-tracker/app.js"></script>"""

            // The sidebar, overlays and view state are composed once per session, above the pages.
            app { route -> Shell(route) }
            view("/", title = "My issues · Acme") { MyIssuesPage() }
            view("/team/{key}/issues", title = "Issues · Acme") { TeamIssuesPage() }
            view("/team/{key}/board", title = "Board · Acme") { BoardPage() }
            view("/issue/{identifier}", title = "Issue · Acme") { IssuePage() }
            view("/projects", title = "Projects · Acme") { ProjectsPage() }
            view("/project/{id}", title = "Project · Acme") { ProjectPage() }
        }
    }.start(wait = true)
}

private fun io.ktor.server.routing.Routing.asset(name: String, type: ContentType) {
    get("/issue-tracker/$name") {
        val text = checkNotNull(Workspace::class.java.getResource("/issue-tracker/$name")) {
            "/issue-tracker/$name missing from resources" + if (name.endsWith(".css")) "; run `npm run build` in samples/issue-tracker" else ""
        }.readText()
        call.respondText(text, type)
    }
}
