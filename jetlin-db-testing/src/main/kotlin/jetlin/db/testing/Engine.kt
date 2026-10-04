package jetlin.db.testing

import kotlin.random.Random
import kotlin.reflect.KClass
import jetlin.db.Column
import jetlin.db.ColumnType
import jetlin.db.GrantDescription
import jetlin.db.Principal
import jetlin.db.Record
import jetlin.db.Table
import jetlin.db.deleteFixture
import jetlin.db.isNullable
import jetlin.db.isSettable
import jetlin.db.proposedChange
import jetlin.db.referencedType
import jetlin.db.storageType
import jetlin.db.storedColumns
import jetlin.db.valueOf
import jetlin.db.whatIf
import jetlin.db.write

/** Something a principal tries, described in terms of a [WorldSpec], so it can be replayed. */
internal sealed interface Attempt {
    /** The index of the principal who tries it. */
    val principal: Int

    /** Sets the columns in [values] on the record at [target]. */
    data class Update(override val principal: Int, val target: Int, val values: Map<String, SpecValue>) : Attempt

    /** Adds a new record. */
    data class Create(override val principal: Int, val record: RecordSpec) : Attempt

    /** Deletes the record at [target]. */
    data class Delete(override val principal: Int, val target: Int) : Attempt
}

/** Something a principal can do with a record: read, update, delete, or change one column. */
internal data class Capability(val record: Int, val kind: String)

/**
 * A finding in one world.
 *
 * @property key Identifies the finding across worlds, so each kind is reported once, and shrinking
 *   can tell whether a smaller world still shows it.
 * @property columns The columns involved, for matching [PolicyCheck.allow].
 * @property story Describes what happened in the world where it was found.
 */
internal class Finding(
    val check: Check?,
    val key: String,
    val entity: KClass<*>?,
    val columns: Set<String>,
    val summary: String,
    val story: String,
)

/** Runs [checkPolicies]: generates worlds, looks for findings, and shrinks and reports them. */
internal class Engine(private val universe: Universe, private val settings: PolicyCheck) {

    /** The first world and attempt that showed each finding, by key. */
    private class Occurrence(val finding: Finding, val spec: WorldSpec, val attempt: Attempt?)

    fun run(): PolicyReport {
        val occurrences = LinkedHashMap<String, Occurrence>()
        val grants = GrantTracker(universe)
        repeat(settings.worlds) { index ->
            val random = Random(settings.seed * 7919 + index)
            val spec = universe.generate(random)
            World(universe, spec).use { world ->
                val attempts = attempts(world, random)
                grants.observe(world, attempts)
                for (finding in worldFindings(world)) occurrences.putIfAbsent(finding.key, Occurrence(finding, spec, null))
                val before = capabilitiesOfAll(world)
                for (attempt in attempts) {
                    for (finding in evaluate(world, attempt, before)) {
                        occurrences.putIfAbsent(finding.key, Occurrence(finding, spec, attempt))
                    }
                }
            }
        }

        val problems = mutableListOf<Problem>()
        val allowed = mutableListOf<Problem>()
        for (occurrence in withoutSupersets(occurrences.values)) {
            val allowance = allowanceFor(occurrence.finding)
            if (allowance != null) {
                allowed += problem(occurrence.finding, allowance.because)
            } else {
                problems += problem(shrink(occurrence), because = null)
            }
        }
        for (dead in grants.dead()) {
            val allowance = allowanceFor(dead)
            if (allowance != null) allowed += problem(dead, allowance.because) else problems += problem(dead, null)
        }
        return PolicyReport(problems, allowed, settings.worlds)
    }

    /**
     * Drops each finding whose columns include all of another finding's, for the same check and
     * entity. When changing `admin` alone shows a finding, changing `admin` and `name` together shows
     * it again, and says nothing new.
     */
    private fun withoutSupersets(occurrences: Collection<Occurrence>): List<Occurrence> = occurrences.filter { occurrence ->
        val finding = occurrence.finding
        finding.columns.isEmpty() || occurrences.none { other ->
            other !== occurrence &&
                other.finding.check == finding.check &&
                other.finding.entity == finding.entity &&
                other.finding.columns.isNotEmpty() &&
                other.finding.columns != finding.columns &&
                finding.columns.containsAll(other.finding.columns)
        }
    }

    // ---- Attempts ---------------------------------------------------------------------------------

    /** Returns the attempts to try in [world]: changes, additions, and deletions, at most the limit. */
    internal fun attempts(world: World, random: Random): List<Attempt> {
        val attempts = mutableListOf<Attempt>()
        for (principal in world.principals) {
            val reachable = reachable(world, principal)
            for (target in reachable) {
                val record = world.records[target]
                val settable = universe.tableOf(record).storedColumns.filter { it.isSettable(record) }
                for (column in settable) {
                    val current = column.valueOf(record)
                    for (option in options(world, record, column)) {
                        if (world.resolve(option) != current) {
                            attempts += Attempt.Update(principal, target, mapOf(column.name to option))
                        }
                    }
                }
                // Some changes only make sense together, such as accepting an offer: taking a record
                // and clearing the offer in one update. Try handing principal-held columns to the
                // principal while clearing the others.
                val held = settable.filter { it.referencedType?.let { type -> Principal::class.java.isAssignableFrom(type.java) } == true }
                for (take in held) {
                    val clear = held.filter { it != take && it.isNullable }
                    if (clear.isNotEmpty()) {
                        attempts += Attempt.Update(
                            principal,
                            target,
                            mapOf(take.name to SpecValue.Ref(principal)) + clear.associate { it.name to SpecValue.Plain(null) },
                        )
                    }
                }
                repeat(if (settable.size >= 2) 2 else 0) {
                    val (first, second) = settable.shuffled(random).take(2)
                    val values = listOf(first, second).associate { column ->
                        column.name to options(world, record, column).random(random)
                    }
                    attempts += Attempt.Update(principal, target, values)
                }
                if (!isReferenced(world, target)) attempts += Attempt.Delete(principal, target)
            }
            for (table in universe.tables) {
                val earlier = world.spec.records
                repeat(2) {
                    universe.randomRecord(random, table, earlier)?.let { attempts += Attempt.Create(principal, it) }
                }
                // The most natural addition: a record that points at its creator wherever it can.
                universe.randomRecord(random, table, earlier)?.let { spec ->
                    val own = spec.values.mapValues { (name, value) ->
                        val column = table.storedColumns.first { it.name == name }
                        if (column.referencedType?.isInstance(world.records[principal]) == true) SpecValue.Ref(principal) else value
                    }
                    attempts += Attempt.Create(principal, RecordSpec(table, own))
                }
            }
        }
        return if (attempts.size <= settings.maxActionsPerWorld) attempts else attempts.shuffled(random).take(settings.maxActionsPerWorld)
    }

    /** Returns the values [column] of [record] could be changed to. */
    private fun options(world: World, record: Record, column: Column<Record>): List<SpecValue> {
        val target = column.referencedType
        if (target != null) {
            val references = world.records.indices.filter { target.isInstance(world.records[it]) }.map { SpecValue.Ref(it) }
            return if (column.isNullable) references + SpecValue.Plain(null) else references
        }
        val sample = world.records.firstNotNullOfOrNull { other ->
            if (universe.tableOf(other).type == universe.tableOf(record).type) column.valueOf(other) else null
        }
        return universe.pool(universe.tableOf(record), column).mapNotNull { value ->
            if (value == null) SpecValue.Plain(null) else coerce(value, sample, column)?.let { SpecValue.Plain(it) }
        }
    }

    /**
     * Converts a pool value to the column's Kotlin type, which only a stored value can tell: an
     * `INTEGER` column might hold an `Int` or a `Long`. Returns `null` if there's nothing to tell by.
     */
    private fun coerce(value: Any, sample: Any?, column: Column<Record>): Any? = when (column.storageType) {
        ColumnType.Integer, ColumnType.Real -> when (sample) {
            is Int -> (value as Number).toInt()
            is Long -> (value as Number).toLong()
            is Short -> (value as Number).toShort()
            is Double -> (value as Number).toDouble()
            is Float -> (value as Number).toFloat()
            else -> null
        }
        else -> value
    }

    /**
     * Returns the records [principal] can reach: those they can see, themselves, and every record
     * those point at. Following a reference isn't checked, so a principal holds every record they
     * can reach this way.
     */
    private fun reachable(world: World, principal: Int): Set<Int> {
        val who = world.records[principal] as Principal
        val reached = LinkedHashSet<Int>()
        val queue = ArrayDeque<Int>()
        for (index in world.records.indices) {
            val record = world.records[index]
            if (index == principal || guarded(world, "whether ${world.label(principal)} can see ${world.label(index)}") {
                    universe.policyOf(record).canRead(record, who)
                }
            ) {
                queue += index
            }
        }
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            if (!reached.add(index)) continue
            val record = world.records[index]
            for (column in universe.tableOf(record).storedColumns) {
                world.indexOf(column.valueOf(record))?.let { queue += it }
            }
        }
        return reached
    }

    private fun isReferenced(world: World, target: Int): Boolean {
        val record = world.records[target]
        return world.records.any { other ->
            other !== record && universe.tableOf(other).storedColumns.any { it.valueOf(other) === record }
        }
    }

    // ---- Capabilities -----------------------------------------------------------------------------

    /** Returns what every principal can do with every record in [indices]. */
    private fun capabilitiesOfAll(world: World, indices: Collection<Int> = world.records.indices.toList()): Map<Int, Set<Capability>> =
        world.principals.associateWith { capabilities(world, it, indices) }

    private fun capabilities(world: World, principal: Int, indices: Collection<Int>): Set<Capability> {
        val who = world.records[principal] as Principal
        val result = LinkedHashSet<Capability>()
        for (index in indices) {
            val record = world.records[index]
            val policy = universe.policyOf(record)
            guarded(world, "what ${world.label(principal)} can do with ${world.label(index)}") {
                if (policy.canRead(record, who)) result += Capability(index, "read")
                if (policy.canWrite(record, who)) result += Capability(index, "update")
                if (policy.canDelete(record, who)) result += Capability(index, "delete")
                for (column in universe.tableOf(record).storedColumns) {
                    if (column.isSettable(record) && policy.canWrite(record, column, who)) {
                        result += Capability(index, "change ${column.name}")
                    }
                }
            }
        }
        return result
    }

    // ---- Evaluating attempts ----------------------------------------------------------------------

    /** Tries [attempt] in [world], and returns what it shows, if the policy allows it. */
    private fun evaluate(world: World, attempt: Attempt, before: Map<Int, Set<Capability>>): List<Finding> =
        when (attempt) {
            is Attempt.Update -> evaluateUpdate(world, attempt, before)
            is Attempt.Create -> evaluateCreate(world, attempt, before)
            is Attempt.Delete -> evaluateDelete(world, attempt, before)
        }

    private fun evaluateUpdate(world: World, attempt: Attempt.Update, before: Map<Int, Set<Capability>>): List<Finding> {
        val record = world.records[attempt.target]
        val who = world.records[attempt.principal] as Principal
        val policy = universe.policyOf(record)
        val table = universe.tableOf(record)
        val values = attempt.values.map { (name, value) -> table.column(name) to world.resolve(value) }.toMap()
        // Setting columns to the values they already have changes nothing, so there's nothing to judge.
        if (values.all { (column, value) -> column.valueOf(record) == value }) return emptyList()
        val allowed = guarded(world, "whether ${world.label(attempt.principal)} can change ${world.label(attempt.target)}") {
            policy.canChange(proposedChange(record, values), who)
        }
        if (!allowed) return emptyList()

        val oldValues = table.storedColumns.associate { it.name to it.valueOf(record) }
        val changed = values.filter { (column, value) -> oldValues[column.name] != value }.keys.map { it.name }.toSet()
        val findings = mutableListOf<Finding>()
        val story = world.story(describe(world, attempt, oldValues))

        whatIf(apply = { for ((column, value) in values) column.write(record, value) }) {
            val after = capabilitiesOfAll(world)
            val newValues = table.storedColumns.associate { it.name to it.valueOf(record) }
            findings += escalations(world, attempt, before, after, table.type, changed, story, target = attempt.target)

            val readersBefore = world.principals.filter { Capability(attempt.target, "read") in before.getValue(it) }
            val readersAfter = world.principals.filter { Capability(attempt.target, "read") in after.getValue(it) }
            if (readersBefore.isNotEmpty() && readersAfter.isEmpty()) {
                findings += finding(
                    Check.Orphan, table.type, changed,
                    "${table.type.simpleName}: changing ${changed.joined()} can leave it visible to nobody",
                    story + "\nAfterwards, nobody can see ${world.label(attempt.target)}.",
                )
            }
            if (!guarded(world, "whether ${world.label(attempt.principal)} could add ${world.label(attempt.target)}") {
                    policy.canCreate(record, who)
                }
            ) {
                findings += finding(
                    Check.NotCreatable, table.type, changed,
                    "${table.type.simpleName}: changing ${changed.joined()} reaches a record that couldn't be added directly",
                    story + "\nAfterwards, ${world.describe(attempt.target)} is a record " +
                        "${world.label(attempt.principal)} couldn't have added directly.",
                )
            }
            if (changed.size == 1) {
                val column = table.column(changed.single())
                val undo = mapOf(column to oldValues[column.name])
                val canUndo = guarded(world, "whether ${world.label(attempt.principal)} can undo the change") {
                    policy.canChange(proposedChange(record, undo), who)
                }
                if (!canUndo) {
                    findings += finding(
                        Check.CannotUndo, table.type, changed,
                        "${table.type.simpleName}: changing ${changed.single()} can't be undone by whoever changed it",
                        story + "\nAfterwards, ${world.label(attempt.principal)} can't set ${column.name} back to " +
                            "${world.show(oldValues[column.name])}.",
                    )
                }
            }
            findings += nevers(world, Action.Update, who, record, oldValues, newValues, story)
        }
        return findings
    }

    private fun evaluateCreate(world: World, attempt: Attempt.Create, before: Map<Int, Set<Capability>>): List<Finding> {
        val who = world.records[attempt.principal] as Principal
        val table = attempt.record.table
        val record = world.unstored(attempt.record)
        val policy = universe.policyOf(table)
        val allowed = guarded(world, "whether ${world.label(attempt.principal)} can add a ${table.type.simpleName}") {
            policy.canCreate(record, who)
        }
        if (!allowed) return emptyList()

        world.restore(record)
        val index = world.adopt(record)
        try {
            val values = table.storedColumns.associate { it.name to it.valueOf(record) }
            val story = world.story("${world.label(attempt.principal)} adds ${world.describe(index)}. The policy allows it.")
            val after = capabilitiesOfAll(world)
            val findings = mutableListOf<Finding>()
            findings += escalations(world, attempt, before, after, table.type, blame(world, attempt, index), story, target = index)
            findings += nevers(world, Action.Create, who, record, values, values, story)
            return findings
        } finally {
            world.forgetLast()
            world.db.deleteFixture(record)
        }
    }

    private fun evaluateDelete(world: World, attempt: Attempt.Delete, before: Map<Int, Set<Capability>>): List<Finding> {
        val record = world.records[attempt.target]
        val who = world.records[attempt.principal] as Principal
        val table = universe.tableOf(record)
        val allowed = guarded(world, "whether ${world.label(attempt.principal)} can delete ${world.label(attempt.target)}") {
            universe.policyOf(record).canDelete(record, who)
        }
        if (!allowed) return emptyList()

        val values = table.storedColumns.associate { it.name to it.valueOf(record) }
        val story = world.story("${world.label(attempt.principal)} deletes ${world.label(attempt.target)}. The policy allows it.")
        world.db.deleteFixture(record)
        try {
            val remaining = world.records.indices.filter { it != attempt.target }
            val after = world.principals.associateWith { capabilities(world, it, remaining) }
            val findings = mutableListOf<Finding>()
            findings += escalations(world, attempt, before, after, table.type, emptySet(), story, target = attempt.target)
            findings += nevers(world, Action.Delete, who, record, values, values, story)
            return findings
        } finally {
            world.restore(record)
        }
    }

    /** Returns the escalation findings: what [attempt] gave its principal, and what it gave others. */
    private fun escalations(
        world: World,
        attempt: Attempt,
        before: Map<Int, Set<Capability>>,
        after: Map<Int, Set<Capability>>,
        entity: KClass<*>,
        columns: Set<String>,
        story: String,
        target: Int,
    ): List<Finding> {
        val findings = mutableListOf<Finding>()
        val principal = attempt.principal
        val action = when (attempt) {
            is Attempt.Update -> "changing ${columns.joined()}"
            is Attempt.Create -> "adding one"
            is Attempt.Delete -> "deleting one"
        }
        val gained = after.getValue(principal) - before.getValue(principal)
        val elsewhere = gained.filter { it.record != target }
        if (elsewhere.isNotEmpty()) {
            findings += finding(
                Check.SelfEscalation, entity, columns,
                "${entity.simpleName}: $action can give a principal access to other records",
                story + "\nAfterwards, ${world.label(principal)} can also: ${elsewhere.describe(world)}.",
            )
        }
        val onTarget = gained.filter { it.record == target && it.kind != "read" }
        if (attempt is Attempt.Update && onTarget.isNotEmpty()) {
            findings += finding(
                Check.TakeOver, entity, columns,
                "${entity.simpleName}: $action can give a principal control of the record",
                story + "\nAfterwards, ${world.label(principal)} can also: ${onTarget.describe(world)}.",
            )
        }
        val pushed = world.principals.filter { it != principal }.mapNotNull { other ->
            val had = before.getValue(other)
            val gains = (after.getValue(other) - had).filterNot { alreadyEverywhere(world, it, had) }
            if (gains.isEmpty()) null else "${world.label(other)} can now: ${gains.describe(world)}"
        }
        if (pushed.isNotEmpty()) {
            findings += finding(
                Check.PushOntoOthers, entity, columns,
                "${entity.simpleName}: $action can give other principals access",
                story + "\nAfterwards, without doing anything, " + pushed.joinToString("; ") + ".",
            )
        }
        return findings
    }

    /**
     * Checks whether whoever holds [had] can already do what [capability] allows on every other
     * record of the same entity. An admin who can edit every todo doesn't gain anything when someone
     * adds one, so that isn't pushing it onto them.
     */
    private fun alreadyEverywhere(world: World, capability: Capability, had: Set<Capability>): Boolean {
        val type = world.records[capability.record]::class
        val others = world.records.indices.filter { it != capability.record && world.records[it]::class == type }
        return others.isNotEmpty() && others.all { Capability(it, capability.kind) in had }
    }

    /**
     * Returns the nullable columns of the record at [index], which [attempt] added, whose values
     * gave other principals access: clearing any one of them takes the access away again. These
     * are the columns an allowance for pushing onto others refers to.
     */
    private fun blame(world: World, attempt: Attempt.Create, index: Int): Set<String> {
        val record = world.records[index]
        val table = attempt.record.table
        val others = world.principals.filter { it != attempt.principal }
        val gainsWith = others.associateWith { capabilities(world, it, listOf(index)) }
        return table.storedColumns.filter { column ->
            column.isNullable && column.isSettable(record) && column.valueOf(record) != null &&
                whatIf(apply = { column.write(record, null) }) {
                    others.any { capabilities(world, it, listOf(index)) != gainsWith.getValue(it) }
                }
        }.map { it.name }.toSet()
    }

    /** Returns the findings for every [PolicyCheck.never] that the action matches. */
    private fun nevers(
        world: World,
        action: Action,
        principal: Principal,
        record: Record,
        before: Map<String, Any?>,
        after: Map<String, Any?>,
        story: String,
    ): List<Finding> {
        val event = Event(action, principal, record, before, after)
        return settings.nevers.filter { it.forbidden(event) }.map { never ->
            finding(
                null, record::class, emptySet(), "Never: ${never.description}",
                story + "\nThis breaks the property \"never: ${never.description}\".",
                key = "never|${never.description}",
            )
        }
    }

    // ---- Findings about a whole world -------------------------------------------------------------

    /** Returns the findings that don't depend on an action: missing values, and `always` properties. */
    private fun worldFindings(world: World): List<Finding> = missingValueMatches(world) + alwaysViolations(world)

    private fun alwaysViolations(world: World): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (always in settings.alwayses) {
            for (index in world.records.indices) {
                val record = world.records[index]
                if (!always.entity.isInstance(record)) continue
                for (principal in world.principals) {
                    val who = world.records[principal]
                    if (!always.principal.isInstance(who)) continue
                    val access = access(world, record, who as Principal)
                    val holds = guarded(world, "the property \"${always.description}\"") { always.check(record, who, access) }
                    if (!holds) {
                        findings += finding(
                            null, record::class, emptySet(), "Always: ${always.description}",
                            world.story(
                                "For ${world.label(principal)} and ${world.label(index)}, this breaks the property " +
                                    "\"always: ${always.description}\".",
                            ),
                            key = "always|${always.description}",
                        )
                    }
                }
            }
        }
        return findings
    }

    private fun access(world: World, record: Record, principal: Principal): Access {
        val policy = universe.policyOf(record)
        val columns = universe.tableOf(record).storedColumns
            .filter { it.isSettable(record) }
            .associate { it.name to policy.canWrite(record, it, principal) }
        return Access(policy.canRead(record, principal), policy.canWrite(record, principal), policy.canDelete(record, principal), columns)
    }

    /**
     * Finds access that depends on two missing values counting as the same. For each missing value
     * on a principal and a missing value on a record that could be compared, it gives the two
     * different real values, and checks whether that takes access away. If it does, the access came
     * from the two missing values matching.
     */
    private fun missingValueMatches(world: World): List<Finding> {
        val findings = mutableListOf<Finding>()
        for (principal in world.principals) {
            val who = world.records[principal]
            val whoTable = universe.tableOf(who)
            for (index in world.records.indices) {
                if (index == principal) continue
                val record = world.records[index]
                val table = universe.tableOf(record)
                val policy = universe.policyOf(record)
                val had = listOf("read" to policy.canRead(record, who as Principal), "update" to policy.canWrite(record, who))
                    .filter { it.second }.map { it.first }
                if (had.isEmpty()) continue
                for (mine in whoTable.storedColumns) {
                    if (!mine.isNullable || !mine.isSettable(who) || mine.valueOf(who) != null) continue
                    for (theirs in table.storedColumns) {
                        if (!theirs.isNullable || !theirs.isSettable(record) || theirs.valueOf(record) != null) continue
                        if (mine.storageType != theirs.storageType || mine.referencedType != theirs.referencedType) continue
                        val (first, second) = distinctValues(world, mine, theirs, whoTable, table) ?: continue
                        val lost = whatIf(apply = {
                            mine.write(who, first)
                            theirs.write(record, second)
                        }) {
                            had.filter { kind ->
                                if (kind == "read") !policy.canRead(record, who) else !policy.canWrite(record, who)
                            }
                        }
                        if (lost.isNotEmpty()) {
                            val columns = setOf(mine.name, theirs.name)
                            findings += Finding(
                                Check.MissingValuesMatch,
                                "${Check.MissingValuesMatch}|${whoTable.type.simpleName}.${mine.name}|${table.type.simpleName}.${theirs.name}",
                                table.type,
                                columns,
                                "${table.type.simpleName}: access depends on ${whoTable.type.simpleName}.${mine.name} and " +
                                    "${table.type.simpleName}.${theirs.name} both being missing",
                                world.story(
                                    "${world.label(principal)} can ${lost.joinToString(" and ")} ${world.label(index)}, but only because " +
                                        "${world.label(principal)}.${mine.name} and ${world.label(index)}.${theirs.name} are both null. " +
                                        "With two different values, they can't.",
                                ),
                            )
                        }
                    }
                }
            }
        }
        return findings
    }

    /** Returns two different, non-null values the two columns can both hold, or `null` if there are none. */
    private fun distinctValues(
        world: World,
        mine: Column<Record>,
        theirs: Column<Record>,
        mineTable: Table<Record>,
        theirsTable: Table<Record>,
    ): Pair<Any, Any>? {
        mine.referencedType?.let { target ->
            val candidates = world.records.filter { target.isInstance(it) }
            return if (candidates.size >= 2) candidates[0] to candidates[1] else null
        }
        val sample = world.records.firstNotNullOfOrNull { record ->
            when (universe.tableOf(record)) {
                mineTable -> mine.valueOf(record)
                theirsTable -> theirs.valueOf(record)
                else -> null
            }
        }
        return when (mine.storageType) {
            ColumnType.Text -> "missing-1" to "missing-2"
            ColumnType.Bool -> false to true
            else -> {
                val first = coerce(1001, sample, mine) ?: return null
                val second = coerce(1002, sample, mine) ?: return null
                first to second
            }
        }
    }

    // ---- Shrinking and reporting ------------------------------------------------------------------

    /**
     * Returns [occurrence]'s finding, as shown by the smallest world that still shows it.
     *
     * It removes records one at a time, and then makes values as plain as possible, keeping each
     * step that still shows the finding.
     */
    private fun shrink(occurrence: Occurrence): Finding {
        var spec = occurrence.spec
        var attempt = occurrence.attempt
        var best = occurrence.finding
        var budget = 80

        fun tryCandidate(candidate: WorldSpec, candidateAttempt: Attempt?): Boolean {
            if (budget-- <= 0) return false
            val found = reproduce(candidate, candidateAttempt, occurrence.finding.key) ?: return false
            spec = candidate
            attempt = candidateAttempt
            best = found
            return true
        }

        var improved = true
        while (improved && budget > 0) {
            improved = false
            for (index in spec.records.indices.reversed()) {
                val removal = Shrinking.remove(spec, index) ?: continue
                val remapped = attempt?.let { Shrinking.remap(it, removal.second) ?: continue }
                if (tryCandidate(removal.first, remapped)) {
                    improved = true
                    break
                }
            }
        }
        for (index in spec.records.indices) {
            val recordSpec = spec.records[index]
            for ((name, value) in recordSpec.values) {
                if (value !is SpecValue.Plain) continue
                val column = recordSpec.table.column(name)
                val plainest = if (column.isNullable) null else universe.pool(recordSpec.table, column).firstOrNull()
                if (value.value == plainest) continue
                tryCandidate(Shrinking.set(spec, index, name, SpecValue.Plain(plainest)), attempt)
            }
        }
        return best
    }

    /** Builds [spec], tries [attempt] there, and returns the finding with [key], or `null` if it's gone. */
    private fun reproduce(spec: WorldSpec, attempt: Attempt?, key: String): Finding? =
        runCatching {
            World(universe, spec).use { world ->
                val findings = if (attempt == null) worldFindings(world) else evaluate(world, attempt, capabilitiesOfAll(world))
                findings.firstOrNull { it.key == key }
            }
        }.getOrNull()

    private fun allowanceFor(finding: Finding): Allowance? = settings.allowances.firstOrNull { allowance ->
        allowance.check == finding.check &&
            (allowance.entity == null || allowance.entity == finding.entity) &&
            (allowance.column == null || allowance.column in finding.columns)
    }

    private fun problem(finding: Finding, because: String?): Problem {
        val details = buildString {
            if (finding.check != null) {
                appendLine("${finding.check.title}: ${finding.summary}")
                appendLine("  ${finding.check.explanation}")
            } else {
                appendLine(finding.summary)
            }
            appendLine()
            appendLine(finding.story.prependIndent("  "))
            if (finding.check != null && because == null) {
                appendLine()
                appendLine("  If this is intended, allow it:")
                val target = finding.columns.firstOrNull()?.let { "${finding.entity?.simpleName}::$it" }
                    ?: "${finding.entity?.simpleName}::class"
                appendLine("    allow(Check.${finding.check.name}, $target, because = \"…\")")
            }
        }
        return Problem(finding.check, finding.summary, details, because)
    }

    private fun finding(
        check: Check?,
        entity: KClass<*>,
        columns: Set<String>,
        summary: String,
        story: String,
        key: String = "$check|${entity.simpleName}|${columns.sorted().joinToString(",")}",
    ): Finding = Finding(check, key, entity, columns, summary, story)

    private fun describe(world: World, attempt: Attempt.Update, oldValues: Map<String, Any?>): String {
        val changes = attempt.values.entries.joinToString(", ") { (name, value) ->
            "$name ${world.show(oldValues[name])} → ${world.show(world.resolve(value))}"
        }
        return "${world.label(attempt.principal)} changes ${world.label(attempt.target)}: $changes. The policy allows it."
    }

    private fun Iterable<Capability>.describe(world: World): String = joinToString(", ") { capability ->
        val label = world.label(capability.record)
        if (capability.kind.startsWith("change ")) "change $label.${capability.kind.removePrefix("change ")}" else "${capability.kind} $label"
    }

    private fun Set<String>.joined(): String = if (isEmpty()) "it" else sorted().joinToString(" and ")

    /** Runs a policy call, and turns an exception into an assertion that says what was being asked. */
    private fun <R> guarded(world: World, question: String, block: () -> R): R =
        try {
            block()
        } catch (e: AssertionError) {
            throw e
        } catch (e: Exception) {
            throw AssertionError("A policy threw while checking $question: ${e.message}\n\n${world.story("")}", e)
        }
}

/** Describes the world, then [event], for a finding. */
internal fun World.story(event: String): String = buildString {
    appendLine("In this world:")
    for (index in records.indices) appendLine("  ${describe(index)}")
    append(event)
}.trimEnd()

private fun <T : Record, P : Principal> Always<T, P>.check(record: Record, principal: Record, access: Access): Boolean {
    // The caller checked both types with isInstance.
    @Suppress("UNCHECKED_CAST")
    return holds(record as T, principal as P, access)
}

/** Follows which grants of each rule-built policy ever held, to find the ones that never do. */
internal class GrantTracker(private val universe: Universe) {
    private class Tracked(val table: Table<Record>, val grant: GrantDescription) {
        var held = false
    }

    private val tracked: List<Tracked> = universe.tables.flatMap { table ->
        universe.policyOf(table).describeGrants().orEmpty().map { Tracked(table, it) }
    }

    /** Evaluates every grant for every principal and record in [world], and for every record [attempts] would add. */
    fun observe(world: World, attempts: List<Attempt>) {
        val pending = tracked.filter { !it.held }
        if (pending.isEmpty()) return
        val candidates = world.records.map { it } + attempts.filterIsInstance<Attempt.Create>().mapNotNull { attempt ->
            runCatching { world.unstored(attempt.record) }.getOrNull()
        }
        for (entry in pending) {
            entry.held = candidates.any { record ->
                entry.table.type.isInstance(record) &&
                    world.principals.any { runCatching { entry.grant.holds(record, world.records[it] as Principal) }.getOrDefault(false) }
            }
        }
    }

    /** Returns a finding for every grant that never held. */
    fun dead(): List<Finding> = tracked.filter { !it.held }.map { entry ->
        Finding(
            Check.DeadGrant,
            "${Check.DeadGrant}|${entry.table.type.simpleName}|${entry.grant}",
            entry.table.type,
            emptySet(),
            "${entry.table.type.simpleName}: the grant of ${entry.grant.permission} never applied",
            "The grant \"${entry.grant}\" has no effect: in no world did its condition hold, for any " +
                "principal and any ${entry.table.type.simpleName}.\n" +
                "Check that the condition compares the fields you meant, and that they can hold the same values.",
        )
    }
}
