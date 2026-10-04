package jetlin.db.testing

import java.nio.file.Path
import java.util.IdentityHashMap
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.random.Random
import jetlin.db.Column
import jetlin.db.ColumnType
import jetlin.db.Db
import jetlin.db.deleteFixture
import jetlin.db.Policy
import jetlin.db.Principal
import jetlin.db.Record
import jetlin.db.Schema
import jetlin.db.Table
import jetlin.db.isNullable
import jetlin.db.referencedType
import jetlin.db.storageType
import jetlin.db.insertUnchecked
import jetlin.db.unsafe
import jetlin.db.storedColumns
import jetlin.db.valueOf

/** A column value in a [WorldSpec]: a plain value, or a reference to an earlier record. */
internal sealed interface SpecValue {
    /** A value stored as it is: a string, number, Boolean, or `null`. */
    data class Plain(val value: Any?) : SpecValue

    /** A reference to the record at [index] in the same world. */
    data class Ref(val index: Int) : SpecValue
}

/** One record of a world: its table, and a value for every column. */
internal data class RecordSpec(val table: Table<Record>, val values: Map<String, SpecValue>)

/**
 * A world, described as data: the records to store, in order. A reference always points at an
 * earlier record, so the records can be stored in this order.
 */
internal data class WorldSpec(val records: List<RecordSpec>)

/** The schema, the test's settings, and how to generate worlds from them. */
internal class Universe(schema: Schema, private val settings: PolicyCheck) {
    // A schema's tables and policies are generic in their entity. Here, every record is a Record.
    @Suppress("UNCHECKED_CAST")
    val tables: List<Table<Record>> = schema.tables as List<Table<Record>>

    private val policies: Map<Table<Record>, Policy<Record, Principal>> = tables.associateWith { table ->
        // The schema pairs each table with its own entity's policy.
        @Suppress("UNCHECKED_CAST")
        (settings.policyOverrides[table] ?: schema.policyFor(table)) as Policy<Record, Principal>
    }

    private val tablesByType = tables.associateBy { it.type }

    fun tableOf(record: Record): Table<Record> = checkNotNull(tablesByType[record::class]) {
        "${record::class.simpleName} isn't part of the schema"
    }

    fun policyOf(record: Record): Policy<Record, Principal> = policies.getValue(tableOf(record))

    fun policyOf(table: Table<Record>): Policy<Record, Principal> = policies.getValue(table)

    fun isPrincipal(table: Table<Record>): Boolean = Principal::class.java.isAssignableFrom(table.type.java)

    private fun count(table: Table<Record>): Int =
        settings.entities[table.type]?.count ?: if (isPrincipal(table)) 3 else 2

    /**
     * Returns the values a plain column can take. They're few on purpose: policies go wrong when
     * values collide, such as two users on the same team, or a team that's missing on both sides, so
     * a small pool makes collisions common. By default, a nullable column can also be `null`.
     */
    fun pool(table: Table<Record>, column: Column<Record>): List<Any?> {
        // Values the test chose are used exactly as given, so a test can leave out null.
        settings.entities[table.type]?.pools?.get(column.name)?.let { return it }
        val values = when (column.storageType) {
            ColumnType.Text -> listOf("a", "b")
            ColumnType.Integer -> listOf(0L, 1L)
            ColumnType.Real -> listOf(0.0, 1.0)
            ColumnType.Bool -> listOf(false, true)
        }
        return if (column.isNullable) values + null else values
    }

    /** Generates a random world. */
    fun generate(random: Random): WorldSpec {
        val records = mutableListOf<RecordSpec>()
        for (table in tables) {
            repeat(count(table)) {
                randomRecord(random, table, records)?.let { records += it }
            }
        }
        return WorldSpec(records)
    }

    /**
     * Returns a random record of [table] whose references point into [earlier], or `null` if a
     * non-null reference has nothing to point at.
     */
    fun randomRecord(random: Random, table: Table<Record>, earlier: List<RecordSpec>): RecordSpec? {
        val values = LinkedHashMap<String, SpecValue>()
        for (column in table.storedColumns) {
            values[column.name] = randomValue(random, table, column, earlier) ?: return null
        }
        return RecordSpec(table, values)
    }

    /** Returns a random value for [column], or `null` if a non-null reference has no target. */
    fun randomValue(random: Random, table: Table<Record>, column: Column<Record>, earlier: List<RecordSpec>): SpecValue? {
        val target = column.referencedType ?: return SpecValue.Plain(pool(table, column).random(random))
        val candidates = earlier.indices.filter { earlier[it].table.type == target }.map { SpecValue.Ref(it) }
        val options: List<SpecValue> = if (column.isNullable) candidates + SpecValue.Plain(null) else candidates
        return options.randomOrNull(random)
    }
}

/**
 * A world built from a [WorldSpec]: a real database in a temporary directory, with its records
 * stored in spec order, so `records[i]` is the record for `spec.records[i]`.
 */
internal class World(val universe: Universe, val spec: WorldSpec) : AutoCloseable {
    private val directory: Path = createTempDirectory("jetlin-db-testing")
    val db: Db = Db.open(directory.resolve("world.db"), universe.tables)
    val records: MutableList<Record> = mutableListOf()
    private val indexOf = IdentityHashMap<Record, Int>()

    init {
        unsafe("jetlin-db-testing builds throwaway worlds to test policies in") {
            for (recordSpec in spec.records) {
                val record = db.insertUnchecked(recordSpec.table, recordSpec.values.mapValues { resolve(it.value) })
                indexOf[record] = records.size
                records += record
            }
        }
    }

    /**
     * Returns a new record built from [spec], unstored, the way a record is before it's added.
     *
     * Only the generated loader can build a record from column values, and it's reached through
     * [insertUnchecked], so the record is stored and then deleted again.
     */
    fun unstored(spec: RecordSpec): Record = unsafe("jetlin-db-testing builds a record to try adding") {
        db.insertUnchecked(spec.table, spec.values.mapValues { resolve(it.value) }).also { db.deleteFixture(it) }
    }

    /** Stores [record], which [unstored] built or a check deleted, as it was. */
    fun restore(record: Record) {
        unsafe("jetlin-db-testing puts back a record it took out") { db.insertUnchecked(record) }
    }

    /** The indices of the records that are principals. */
    val principals: List<Int> = records.indices.filter { records[it] is Principal }

    /** Returns the value that [value] stands for in this world. */
    fun resolve(value: SpecValue): Any? = when (value) {
        is SpecValue.Plain -> value.value
        is SpecValue.Ref -> records[value.index]
    }

    /** Returns the index of [record], or `null` if it isn't one of this world's records. */
    fun indexOf(record: Any?): Int? = if (record is Record) indexOf[record] else null

    /** Registers [record], added during a check, so it has an index and a label. */
    fun adopt(record: Record): Int {
        indexOf[record] = records.size
        records += record
        return records.size - 1
    }

    /** Forgets the record that [adopt] registered last. */
    fun forgetLast() {
        indexOf.remove(records.removeAt(records.lastIndex))
    }

    /** Returns a short, stable name for the record at [index], such as `User2` or `Todo1`. */
    fun label(index: Int): String {
        val type = records[index]::class
        val ordinal = (0..index).count { records[it]::class == type }
        return "${type.simpleName}$ordinal"
    }

    /** Returns a label for [value]: another record's label, or the value written as Kotlin. */
    fun show(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"$value\""
        is Record -> indexOf(value)?.let(::label) ?: value.toString()
        else -> value.toString()
    }

    /** Describes the record at [index] with all its values, for reports. */
    fun describe(index: Int): String {
        val record = records[index]
        val table = universe.tableOf(record)
        return label(index) + table.storedColumns.joinToString(prefix = "(", postfix = ")") { column ->
            "${column.name} = ${show(column.valueOf(record))}"
        }
    }

    @OptIn(ExperimentalPathApi::class)
    override fun close() {
        db.close()
        directory.deleteRecursively()
    }
}
