package jetlin.db.testing

import kotlin.random.Random
import jetlin.db.Principal
import jetlin.db.Schema
import jetlin.db.Table
import jetlin.db.isSettable
import jetlin.db.proposedChange
import jetlin.db.storedColumns

/** Runs [comparePolicies]: asks two policies the same questions in the same worlds. */
internal class Comparison(
    schema: Schema,
    private val table: Table<*>,
    oldSettings: PolicyCheck,
    newSettings: PolicyCheck,
    private val settings: PolicyCheck,
) {
    private val old = Universe(schema, oldSettings)
    private val new = Universe(schema, newSettings)

    /** A question both policies answer, described in terms of a [WorldSpec], so it can be replayed. */
    private sealed interface Question {
        data class Can(val principal: Int, val record: Int, val kind: String) : Question
        data class Try(val attempt: Attempt) : Question
    }

    private class Difference(val key: String, val story: String)

    private class Occurrence(val difference: Difference, val spec: WorldSpec, val question: Question)

    fun run(): PolicyComparison {
        val occurrences = LinkedHashMap<String, Occurrence>()
        val attempts = Engine(old, settings)
        repeat(settings.worlds) { index ->
            val random = Random(settings.seed * 7919 + index)
            val spec = old.generate(random)
            World(old, spec).use { world ->
                val questions = capabilityQuestions(world) + attempts.attempts(world, random).filter(::isAbout).map { Question.Try(it) }
                for (question in questions) {
                    val difference = ask(world, question) ?: continue
                    occurrences.putIfAbsent(difference.key, Occurrence(difference, spec, question))
                }
            }
        }
        return PolicyComparison(occurrences.values.map { shrink(it).story })
    }

    private fun isAbout(attempt: Attempt): Boolean = when (attempt) {
        is Attempt.Create -> attempt.record.table == table
        is Attempt.Update, is Attempt.Delete -> true
    }

    private fun capabilityQuestions(world: World): List<Question> = world.records.indices
        .filter { table.type.isInstance(world.records[it]) }
        .flatMap { record ->
            val columns = old.tableOf(world.records[record]).storedColumns.filter { it.isSettable(world.records[record]) }
            val kinds = listOf("read", "update", "delete") + columns.map { "change ${it.name}" }
            world.principals.flatMap { principal -> kinds.map { Question.Can(principal, record, it) } }
        }

    /** Asks both policies [question] in [world], and returns the difference, or `null` if they agree. */
    private fun ask(world: World, question: Question): Difference? {
        val (key, text) = when (question) {
            is Question.Can -> {
                val record = world.records[question.record]
                if (!table.type.isInstance(record)) return null
                question.kind to "can ${world.label(question.principal)} ${question.kind.replace("change ", "change ${world.label(question.record)}.")}" +
                    (if (question.kind.startsWith("change ")) "" else " ${world.label(question.record)}") + "?"
            }
            is Question.Try -> when (val attempt = question.attempt) {
                is Attempt.Update -> {
                    val record = world.records[attempt.target]
                    if (!table.type.isInstance(record)) return null
                    "update ${attempt.values.keys.sorted()}" to "can ${world.label(attempt.principal)} change ${world.label(attempt.target)}: " +
                        attempt.values.entries.joinToString { (name, value) -> "$name → ${world.show(world.resolve(value))}" } + "?"
                }
                is Attempt.Create -> "create" to "can ${world.label(attempt.principal)} add a ${table.type.simpleName} with " +
                    attempt.record.values.entries.joinToString { (name, value) -> "$name = ${world.show(world.resolve(value))}" } + "?"
                is Attempt.Delete -> {
                    if (!table.type.isInstance(world.records[attempt.target])) return null
                    "delete" to "can ${world.label(attempt.principal)} delete ${world.label(attempt.target)}?"
                }
            }
        }
        val before = answer(world, old, question)
        val after = answer(world, new, question)
        if (before == after) return null
        return Difference(
            "$key|$before",
            world.story("Asked \"$text\", the old policy says ${yes(before)}, and the new one says ${yes(after)}."),
        )
    }

    private fun answer(world: World, universe: Universe, question: Question): Boolean = when (question) {
        is Question.Can -> {
            val record = world.records[question.record]
            val principal = world.records[question.principal] as Principal
            val policy = universe.policyOf(record)
            when (question.kind) {
                "read" -> policy.canRead(record, principal)
                "update" -> policy.canWrite(record, principal)
                "delete" -> policy.canDelete(record, principal)
                else -> policy.canWrite(record, universe.tableOf(record).column(question.kind.removePrefix("change ")), principal)
            }
        }
        is Question.Try -> when (val attempt = question.attempt) {
            is Attempt.Update -> {
                val record = world.records[attempt.target]
                val table = universe.tableOf(record)
                val values = attempt.values.map { (name, value) -> table.column(name) to world.resolve(value) }.toMap()
                universe.policyOf(record).canChange(proposedChange(record, values), world.records[attempt.principal] as Principal)
            }
            is Attempt.Create -> {
                val record = world.unstored(attempt.record)
                universe.policyOf(attempt.record.table).canCreate(record, world.records[attempt.principal] as Principal)
            }
            is Attempt.Delete -> {
                val record = world.records[attempt.target]
                universe.policyOf(record).canDelete(record, world.records[attempt.principal] as Principal)
            }
        }
    }

    /** Returns [occurrence]'s difference, as shown by the smallest world that still shows it. */
    private fun shrink(occurrence: Occurrence): Difference {
        var spec = occurrence.spec
        var question = occurrence.question
        var best = occurrence.difference
        var budget = 80
        var improved = true
        while (improved && budget > 0) {
            improved = false
            for (index in spec.records.indices.reversed()) {
                if (budget-- <= 0) break
                val (smaller, moved) = Shrinking.remove(spec, index) ?: continue
                val remapped = remap(question, moved) ?: continue
                val found = runCatching {
                    World(old, smaller).use { world -> ask(world, remapped)?.takeIf { it.key == best.key } }
                }.getOrNull() ?: continue
                spec = smaller
                question = remapped
                best = found
                improved = true
                break
            }
        }
        return best
    }

    private fun remap(question: Question, moved: Map<Int, Int>): Question? = when (question) {
        is Question.Can -> Question.Can(moved[question.principal] ?: return null, moved[question.record] ?: return null, question.kind)
        is Question.Try -> Shrinking.remap(question.attempt, moved)?.let { Question.Try(it) }
    }

    private fun yes(answer: Boolean): String = if (answer) "yes" else "no"
}
