package jetlin.samples.issuetracker

import androidx.compose.runtime.mutableStateListOf
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * A shared in-memory workspace standing in for a database.
 *
 * Process-wide on purpose, as in the demo: open the issue tracker in two windows and a card dragged in one
 * moves in the other. Nothing broadcasts anything — every session reads the same state objects, so
 * every session that shows a changed issue recomposes.
 *
 * There is no sign-in, so every visitor is [me]. That keeps "My issues" meaningful without pretending
 * to be an authentication example.
 */
object Workspace {
    const val NAME = "Acme"

    val engineering = Team("ENG", "Engineering")
    val design = Team("DES", "Design")
    val teams = listOf(engineering, design)

    val me = User("u1", "Alex Kim", "bg-indigo-500")
    private val sam = User("u2", "Sam Rivera", "bg-emerald-600")
    private val jordan = User("u3", "Jordan Lee", "bg-amber-600")
    private val priya = User("u4", "Priya Shah", "bg-rose-600")
    val users = listOf(me, sam, jordan, priya)

    private val bug = Label("bug", "Bug", "bg-red-500")
    private val feature = Label("feature", "Feature", "bg-violet-500")
    private val improvement = Label("improvement", "Improvement", "bg-sky-500")
    private val performance = Label("performance", "Performance", "bg-amber-500")
    private val docs = Label("docs", "Docs", "bg-emerald-500")
    val labels = listOf(bug, feature, improvement, performance, docs)

    private val onboarding = Project(
        "onboarding", "New onboarding", "A first-run flow that gets a team to its first issue in a minute.",
        lead = sam, target = LocalDate.of(2026, 10, 15), status = ProjectStatus.Started,
    )
    private val mobile = Project(
        "mobile", "Mobile app", "Triage and quick edits from a phone.",
        lead = jordan, target = LocalDate.of(2026, 12, 1), status = ProjectStatus.Planned,
    )
    private val search = Project(
        "search", "Search v2", "Faster, typo-tolerant search across issues and comments.",
        lead = me, target = LocalDate.of(2026, 9, 30), status = ProjectStatus.Started,
    )
    val projects = listOf(onboarding, mobile, search)

    val issues = mutableStateListOf<Issue>()

    private var nextId = 1
    private val nextNumber = mutableMapOf<Team, Int>()

    init {
        reset()
    }

    fun issue(identifier: String): Issue? = issues.firstOrNull { it.identifier.equals(identifier, ignoreCase = true) }
    fun issue(id: Int): Issue? = issues.firstOrNull { it.id == id }
    fun team(key: String): Team? = teams.firstOrNull { it.key.equals(key, ignoreCase = true) }
    fun project(id: String): Project? = projects.firstOrNull { it.id == id }
    fun user(id: String): User? = users.firstOrNull { it.id == id }
    fun label(id: String): Label? = labels.firstOrNull { it.id == id }

    fun issuesOf(team: Team): List<Issue> = issues.filter { it.team == team }

    @Synchronized
    fun create(
        team: Team,
        title: String,
        description: String = "",
        status: Status = Status.Todo,
        priority: Priority = Priority.None,
        assignee: User? = null,
        labels: List<Label> = emptyList(),
        project: Project? = null,
        by: User = me,
        at: Instant = Instant.now(),
    ): Issue {
        val number = nextNumber.getOrDefault(team, 1)
        nextNumber[team] = number + 1
        return Issue(nextId++, team, number, title, description, status, priority, assignee, labels, project, by, at)
            .also { issues += it }
    }

    fun setStatus(issue: Issue, status: Status, by: User = me) {
        if (issue.status == status) return
        record(issue, by, "changed status from ${issue.status.label} to ${status.label}")
        issue.status = status
    }

    fun setPriority(issue: Issue, priority: Priority, by: User = me) {
        if (issue.priority == priority) return
        record(issue, by, "set priority to ${priority.label}")
        issue.priority = priority
    }

    fun setAssignee(issue: Issue, assignee: User?, by: User = me) {
        if (issue.assignee == assignee) return
        record(issue, by, if (assignee == null) "removed the assignee" else "assigned to ${assignee.name}")
        issue.assignee = assignee
    }

    fun setProject(issue: Issue, project: Project?, by: User = me) {
        if (issue.project == project) return
        record(issue, by, if (project == null) "removed the project" else "moved to ${project.name}")
        issue.project = project
    }

    fun toggleLabel(issue: Issue, label: Label, by: User = me) {
        if (label in issue.labels) {
            record(issue, by, "removed label ${label.name}")
            issue.labels = issue.labels - label
        } else {
            record(issue, by, "added label ${label.name}")
            issue.labels = (issue.labels + label).sortedBy { labels.indexOf(it) }
        }
    }

    fun rename(issue: Issue, title: String, by: User = me) {
        if (title.isBlank() || issue.title == title) return
        record(issue, by, "renamed the issue")
        issue.title = title
    }

    fun describe(issue: Issue, description: String) {
        if (issue.description == description) return
        issue.description = description
        issue.updatedAt = Instant.now()
    }

    fun comment(issue: Issue, body: String, by: User = me) {
        issue.history += Entry.Comment(by, Instant.now(), body)
        issue.updatedAt = Instant.now()
    }

    private fun record(issue: Issue, by: User, description: String) {
        val now = Instant.now()
        issue.history += Entry.Change(by, now, description)
        issue.updatedAt = now
    }

    /** Done issues out of all non-canceled ones, which is how far along a project reads. */
    fun progress(project: Project): Pair<Int, Int> {
        val counted = issues.filter { it.project == project && it.status != Status.Canceled }
        return counted.count { it.status == Status.Done } to counted.size
    }

    /**
     * Returns the workspace to its seeded state. Every open session sees it at once, and every test
     * starts here.
     */
    @Synchronized
    fun reset() {
        issues.clear()
        nextId = 1
        nextNumber.clear()
        onboarding.status = ProjectStatus.Started
        mobile.status = ProjectStatus.Planned
        search.status = ProjectStatus.Started

        val start = Instant.now().minus(Duration.ofDays(30))
        var hours = 0L
        fun seed(
            team: Team, title: String, status: Status, priority: Priority, assignee: User?,
            labels: List<Label> = emptyList(), project: Project? = null, description: String = "",
        ) {
            hours += 19
            create(team, title, description, status, priority, assignee, labels, project, by = sam, at = start.plus(Duration.ofHours(hours)))
        }

        seed(engineering, "Search results flicker while typing", Status.InProgress, Priority.High, me, listOf(bug), search,
            "Results briefly clear between keystrokes. Keep the previous results on screen until the new ones arrive.")
        seed(engineering, "Typo-tolerant matching for issue titles", Status.Todo, Priority.Medium, me, listOf(feature), search)
        seed(engineering, "Index comments for search", Status.Backlog, Priority.Low, jordan, listOf(feature), search)
        seed(engineering, "Search p95 latency above 300ms", Status.InProgress, Priority.Urgent, me, listOf(performance), search,
            "Measured on the staging workspace with 40k issues. The label join is the obvious suspect.")
        seed(engineering, "Welcome checklist on first sign-in", Status.InProgress, Priority.High, sam, listOf(feature), onboarding)
        seed(engineering, "Invite teammates step skips validation", Status.Todo, Priority.Urgent, priya, listOf(bug), onboarding)
        seed(engineering, "Sample issues for new workspaces", Status.Done, Priority.Medium, sam, listOf(feature), onboarding)
        seed(engineering, "Track onboarding completion", Status.Backlog, Priority.None, null, listOf(improvement), onboarding)
        seed(engineering, "Crash when opening an archived issue", Status.Done, Priority.Urgent, jordan, listOf(bug))
        seed(engineering, "Keyboard shortcut cheat sheet", Status.Backlog, Priority.Low, null, listOf(docs))
        seed(engineering, "Upgrade the websocket library", Status.Canceled, Priority.Low, priya, listOf(improvement))
        seed(engineering, "Offline drafts for comments", Status.Todo, Priority.Medium, jordan, listOf(feature), mobile)
        seed(engineering, "Push notifications for mentions", Status.Backlog, Priority.High, null, listOf(feature), mobile)
        seed(engineering, "Board columns don't scroll independently", Status.InReview, Priority.Medium, priya, listOf(bug))
        seed(engineering, "Write the API rate-limit docs", Status.Todo, Priority.Low, me, listOf(docs))
        seed(engineering, "Reduce bundle size of the editor", Status.InProgress, Priority.Medium, jordan, listOf(performance))
        seed(engineering, "Session expires during long edits", Status.Todo, Priority.High, sam, listOf(bug))
        seed(engineering, "Dark mode for the settings page", Status.Done, Priority.Low, priya, listOf(improvement))

        seed(design, "Empty states for the issue list", Status.InProgress, Priority.Medium, priya, listOf(improvement), onboarding)
        seed(design, "Mobile navigation patterns", Status.Todo, Priority.High, jordan, emptyList(), mobile)
        seed(design, "Priority icon set", Status.Done, Priority.Low, priya, listOf(improvement))
        seed(design, "Command palette visual refresh", Status.InReview, Priority.Medium, me, listOf(improvement))
        seed(design, "Onboarding illustrations", Status.Backlog, Priority.None, null, emptyList(), onboarding)
        seed(design, "Search results layout exploration", Status.Todo, Priority.Medium, me, listOf(feature), search)
        seed(design, "Audit color contrast in dark theme", Status.Backlog, Priority.High, priya, listOf(bug))

        issue("ENG-1")?.let { comment(it, "I can reproduce this on slow connections only.", by = jordan) }
        issue("ENG-4")?.let {
            setStatus(it, Status.InReview, by = me)
            comment(it, "Dropping the join brings it to 120ms. PR is up.", by = me)
        }
    }
}
