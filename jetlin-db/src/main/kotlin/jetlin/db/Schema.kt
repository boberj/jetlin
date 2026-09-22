package jetlin.db

import java.sql.PreparedStatement
import java.sql.Types
import kotlin.reflect.KClass

/**
 * The storage type of a column.
 *
 * SQLite has no boolean type, so [Bool] is stored as `INTEGER`. It is still a separate constant so
 * that the column, not the caller, knows to convert between `Boolean` and `0`/`1`.
 */
public enum class ColumnType(internal val sql: String) {
    Integer("INTEGER"),
    Real("REAL"),
    Text("TEXT"),
    Bool("INTEGER"),
}

/**
 * A stored column of a record.
 *
 * For mutable fields, [name] is also the name of the [Cell] that holds the value. That is how a write
 * to a cell becomes an update of a single column without comparing the whole record.
 *
 * [read] is a getter, not a cell lookup, so that immutable fields can be stored too. For example,
 * `@Owner val owner: User` is a constructor property without a cell, and it still needs to be
 * written on insert.
 */
public class Column<T : Record> internal constructor(
    public val name: String,
    internal val type: ColumnType,
    internal val nullable: Boolean,
    internal val references: KClass<out Record>?,
    internal val read: (T) -> Any?,
)

/**
 * How a record class is stored, and how to recreate a record from a stored row.
 *
 * The KSP processor generates these from entity classes by calling [table]. Tests also build them by
 * hand, so the builder is kept simple.
 */
public class Table<T : Record> internal constructor(
    public val name: String,
    public val type: KClass<T>,
    internal val columns: List<Column<T>>,
    internal val instantiate: (Row) -> T,
) {
    internal val columnsByName: Map<String, Column<T>> = columns.associateBy { it.name }

    /**
     * Returns the column called [name].
     *
     * Generated code gets its column objects from here, so `Todos.archived` is the same instance the
     * table uses. This matters because a policy's `when (column)` compares by identity; a separate
     * but identical column object would never match.
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
 * Every table has an implicit `id` column, so no other column may be named `id`.
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

    /** An integer column, for `Long`, `Int` or any other `Number` that is stored as a whole number. */
    public fun integer(name: String, nullable: Boolean = false, read: (T) -> Number?) {
        add(name, ColumnType.Integer, nullable, references = null, read = read)
    }

    /** A floating-point column. */
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
     * A reference to another record, stored as that record's id and resolved to the object when
     * loaded.
     *
     * References are nullable by default. Non-null references are less common and more restrictive:
     * the referenced table has to be loaded first, which constrains the order tables are registered
     * in.
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
     * Declares how to create a record from a stored row.
     *
     * The block does not set the id, and can't. The loader applies the stored id after the block
     * returns.
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
 * A row read from the database, together with the identity map used to resolve its references.
 *
 * Each accessor names the type it expects instead of inferring it. If the schema and the constructor
 * disagree, loading fails on the affected row with the column name in the message, instead of
 * silently converting the value and producing a record that looks valid.
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

    /** Reads a boolean, which SQLite stores as `0` or `1`. */
    public fun boolean(name: String): Boolean = requireValue(name) { (it as Number).toLong() != 0L }

    public fun booleanOrNull(name: String): Boolean? = optionalValue(name) { (it as Number).toLong() != 0L }

    /**
     * The record this column references.
     *
     * The record is looked up in the identity map, so the referenced table must already be loaded,
     * which means it must be registered earlier in the table list. A reference to a later table fails
     * here with an error naming both tables, instead of leaving a missing object in the graph.
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

/** Binds a value to a statement parameter. A record reference is bound as the record's id. */
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

/** The `CREATE TABLE` statement for this table, including foreign keys to the referenced tables. */
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
