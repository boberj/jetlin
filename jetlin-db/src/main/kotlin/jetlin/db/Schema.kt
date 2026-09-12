package jetlin.db

import java.sql.PreparedStatement
import java.sql.Types
import kotlin.reflect.KClass

/**
 * How a column is stored.
 *
 * SQLite has four useful storage classes and no boolean, which is why [Bool] declares `INTEGER`: the
 * distinction is kept here rather than in the column list because the conversion on the way in and
 * out belongs to the column, not to the caller.
 */
public enum class ColumnType(internal val sql: String) {
    Integer("INTEGER"),
    Real("REAL"),
    Text("TEXT"),
    Bool("INTEGER"),
}

/**
 * One stored column of a record.
 *
 * [name] is both the column name and, for mutable fields, the name of the [Cell] holding it — which
 * is what lets a write to a cell be flushed as an update to one column without a dirty-check pass
 * over the record.
 *
 * [read] is a plain getter rather than a cell lookup so that an immutable field works too: the
 * `@Owner val owner: User` of §4.2 is a constructor property with no cell behind it, and it still has
 * to reach the insert.
 */
public class Column<T : Record> internal constructor(
    public val name: String,
    internal val type: ColumnType,
    internal val nullable: Boolean,
    internal val references: KClass<out Record>?,
    internal val read: (T) -> Any?,
)

/**
 * How one record class is stored, and how to build one back from a stored row.
 *
 * Written by hand for now; phase 3's KSP processor generates exactly this from the entity classes, so
 * the builder is the seam between the two and is deliberately boring.
 */
public class Table<T : Record> internal constructor(
    public val name: String,
    public val type: KClass<T>,
    internal val columns: List<Column<T>>,
    internal val instantiate: (Row) -> T,
) {
    internal val columnsByName: Map<String, Column<T>> = columns.associateBy { it.name }

    /**
     * The column called [name].
     *
     * Generated code exposes columns this way so that `Todos.archived` and the column the table flushes
     * are the same object — a policy matching on `when (column)` compares identities, so two equal-looking
     * columns would silently never match.
     */
    public fun column(name: String): Column<T> =
        columnsByName[name] ?: error("Table '${this.name}' has no column '$name'")

    override fun toString(): String = "Table($name)"
}

/**
 * Declares how [type] is stored.
 *
 * ```kotlin
 * val Todos = table("todos", Todo::class) {
 *     reference("owner", User::class, nullable = false) { it.owner }
 *     text("title") { it.title }
 *     bool("done") { it.done }
 *     load { row -> Todo(row.reference("owner", User::class), row.string("title"), row.boolean("done")) }
 * }
 * ```
 *
 * The `id` column is implicit: every record has one, and nothing else is allowed to call a column
 * `id`.
 */
public fun <T : Record> table(
    name: String,
    type: KClass<T>,
    declare: TableBuilder<T>.() -> Unit,
): Table<T> = TableBuilder(name, type).apply(declare).build()

/** Collects the columns of one [table] declaration. */
public class TableBuilder<T : Record> internal constructor(
    private val name: String,
    private val type: KClass<T>,
) {
    private val columns = mutableListOf<Column<T>>()
    private var loader: ((Row) -> T)? = null

    /** A whole number: `Long`, `Int` or anything else `Number` that round-trips as one. */
    public fun integer(name: String, nullable: Boolean = false, read: (T) -> Number?) {
        add(name, ColumnType.Integer, nullable, references = null, read = read)
    }

    /** A floating point number. */
    public fun real(name: String, nullable: Boolean = false, read: (T) -> Number?) {
        add(name, ColumnType.Real, nullable, references = null, read = read)
    }

    public fun text(name: String, nullable: Boolean = false, read: (T) -> String?) {
        add(name, ColumnType.Text, nullable, references = null, read = read)
    }

    public fun bool(name: String, nullable: Boolean = false, read: (T) -> Boolean?) {
        add(name, ColumnType.Bool, nullable, references = null, read = read)
    }

    /**
     * A reference to another record, stored as that record's id and resolved back to the object at
     * load.
     *
     * Defaults to nullable, because a reference that must always point somewhere is the rarer case and
     * the stricter one: a non-null reference can only be loaded after the table it points at, which is
     * a constraint on the order tables are registered in.
     */
    public fun reference(
        name: String,
        target: KClass<out Record>,
        nullable: Boolean = true,
        read: (T) -> Record?,
    ) {
        add(name, ColumnType.Integer, nullable, references = target, read = read)
    }

    /**
     * How to rebuild a record from a stored row.
     *
     * The record's stored id is reapplied by the loader afterwards, so this does not have to — and
     * cannot — set it.
     */
    public fun load(block: (Row) -> T) {
        check(loader == null) { "table '$name' declares load { } twice" }
        loader = block
    }

    private fun add(
        name: String,
        type: ColumnType,
        nullable: Boolean,
        references: KClass<out Record>?,
        read: (T) -> Any?,
    ) {
        require(name != "id") { "'id' is implicit on every record and cannot be declared" }
        require(columns.none { it.name == name }) { "table '${this.name}' declares '$name' twice" }
        columns += Column(name, type, nullable, references, read)
    }

    internal fun build(): Table<T> {
        val load = checkNotNull(loader) {
            "table '$name' has no load { } block, so rows read from disk cannot be turned back into " +
                "${type.simpleName} objects"
        }
        return Table(name, type, columns.toList(), load)
    }
}

/**
 * One row as it was read from disk, and the identity map to resolve its references against.
 *
 * The accessors are explicit about type rather than inferred, because a schema and a constructor that
 * disagree should fail at the row that proves it with the column named, not produce a plausible
 * object built from a silent coercion.
 */
public class Row internal constructor(
    private val values: Map<String, Any?>,
    private val resident: IdentityMap,
) {
    public fun long(name: String): Long = requireValue(name) { (it as Number).toLong() }

    public fun longOrNull(name: String): Long? = optionalValue(name) { (it as Number).toLong() }

    public fun int(name: String): Int = requireValue(name) { (it as Number).toInt() }

    public fun intOrNull(name: String): Int? = optionalValue(name) { (it as Number).toInt() }

    public fun double(name: String): Double = requireValue(name) { (it as Number).toDouble() }

    public fun doubleOrNull(name: String): Double? = optionalValue(name) { (it as Number).toDouble() }

    public fun string(name: String): String = requireValue(name) { it.toString() }

    public fun stringOrNull(name: String): String? = optionalValue(name) { it.toString() }

    /** Stored as `0` or `1`, which is the only thing SQLite offers. */
    public fun boolean(name: String): Boolean = requireValue(name) { (it as Number).toLong() != 0L }

    public fun booleanOrNull(name: String): Boolean? = optionalValue(name) { (it as Number).toLong() != 0L }

    /**
     * The record this column points at.
     *
     * Resolved out of the identity map, so the table it points at must already be loaded — which
     * means registered earlier in the table list. A forward reference fails here, loudly, naming both
     * tables, rather than producing a graph with holes in it.
     */
    public fun <R : Record> reference(name: String, type: KClass<R>): R =
        referenceOrNull(name, type)
            ?: error("Column '$name' is null, but the ${type.simpleName} reference it holds is not nullable")

    public fun <R : Record> referenceOrNull(name: String, type: KClass<R>): R? {
        val id = longOrNull(name) ?: return null
        return resident.find(type, Id(id))
            ?: error(
                "Column '$name' points at ${type.simpleName}#$id, which is not resident. Register " +
                    "${type.simpleName}'s table before the table that references it: tables are loaded " +
                    "in the order they are declared, and jetlin-db does not resolve forward references.",
            )
    }

    private inline fun <V> requireValue(name: String, convert: (Any) -> V): V =
        optionalValue(name, convert) ?: error("Column '$name' is null, but was read as a non-null value")

    private inline fun <V> optionalValue(name: String, convert: (Any) -> V): V? {
        require(values.containsKey(name)) { "No column '$name' in this row; it has ${values.keys}" }
        return values[name]?.let(convert)
    }
}

/** Binds one value to a statement parameter, collapsing a reference to the id it is stored as. */
internal fun bind(statement: PreparedStatement, index: Int, value: Any?) {
    when (value) {
        null -> statement.setNull(index, Types.NULL)
        is Record -> statement.setLong(index, value.id)
        is Boolean -> statement.setInt(index, if (value) 1 else 0)
        is Long -> statement.setLong(index, value)
        is Int -> statement.setInt(index, value)
        is Double -> statement.setDouble(index, value)
        is Float -> statement.setDouble(index, value.toDouble())
        is String -> statement.setString(index, value)
        else -> error(
            "No SQLite mapping for ${value::class.qualifiedName}. Store it as one of the types a " +
                "column can declare, or as a reference to another record.",
        )
    }
}

/** `CREATE TABLE` for one table, with foreign keys pointing at the tables they name. */
internal fun Table<*>.ddl(tableNames: Map<KClass<out Record>, String>): String = buildString {
    append("CREATE TABLE IF NOT EXISTS ").append(name).append(" (\n")
    append("  id INTEGER PRIMARY KEY")
    for (column in columns) {
        append(",\n  ").append(column.name).append(' ').append(column.type.sql)
        if (!column.nullable) append(" NOT NULL")
        column.references?.let { target ->
            val targetTable = tableNames[target]
                ?: error("Column '${column.name}' of '$name' references ${target.simpleName}, which has no table")
            append(" REFERENCES ").append(targetTable).append("(id)")
        }
    }
    append("\n)")
}
