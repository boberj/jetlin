package jetlin.samples.issuetracker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.LocalDate

/** Workflow states, in the order a list groups them and a board lays out its columns. */
enum class Status(val label: String) {
    Backlog("Backlog"),
    Todo("Todo"),
    InProgress("In Progress"),
    InReview("In Review"),
    Done("Done"),
    Canceled("Canceled"),
    ;

    /** Still on somebody's plate: what the "Active" tab shows. */
    val isActive: Boolean get() = this == Todo || this == InProgress || this == InReview

    val isClosed: Boolean get() = this == Done || this == Canceled
}

/** Declared in the order issues sort by, most pressing first and "no priority" last. */
enum class Priority(val label: String) {
    Urgent("Urgent"),
    High("High"),
    Medium("Medium"),
    Low("Low"),
    None("No priority"),
}

class Team(val key: String, val name: String)

/**
 * [avatar] is a whole Tailwind class, not a color name to be spliced into one: Tailwind only
 * generates what it finds written out in the source.
 */
class User(val id: String, val name: String, val avatar: String) {
    val initials: String get() = name.split(' ').mapNotNull { it.firstOrNull() }.take(2).joinToString("")
}

/** [dot] is a whole Tailwind class, for the same reason as [User.avatar]. */
class Label(val id: String, val name: String, val dot: String)

enum class ProjectStatus(val label: String) {
    Planned("Planned"),
    Started("In progress"),
    Paused("Paused"),
    Completed("Completed"),
}

class Project(
    val id: String,
    val name: String,
    val summary: String,
    val lead: User,
    val target: LocalDate?,
    status: ProjectStatus,
) {
    var status: ProjectStatus by mutableStateOf(status)
}

/** Something that happened to an issue, shown in its activity feed in the order it happened. */
sealed class Entry(val actor: User, val at: Instant) {
    class Comment(actor: User, at: Instant, val body: String) : Entry(actor, at)
    class Change(actor: User, at: Instant, val description: String) : Entry(actor, at)
}

/**
 * One issue. Every field that can be edited is Compose state, so each session showing it recomposes
 * when any session changes it — the store is shared across the whole process.
 */
class Issue(
    val id: Int,
    val team: Team,
    val number: Int,
    title: String,
    description: String,
    status: Status,
    priority: Priority,
    assignee: User?,
    labels: List<Label>,
    project: Project?,
    val creator: User,
    val createdAt: Instant,
) {
    val identifier: String get() = "${team.key}-$number"

    var title: String by mutableStateOf(title)
    var description: String by mutableStateOf(description)
    var status: Status by mutableStateOf(status)
    var priority: Priority by mutableStateOf(priority)
    var assignee: User? by mutableStateOf(assignee)
    var labels: List<Label> by mutableStateOf(labels)
    var project: Project? by mutableStateOf(project)
    var updatedAt: Instant by mutableStateOf(createdAt)

    val history = mutableStateListOf<Entry>(Entry.Change(creator, createdAt, "created the issue"))
}
