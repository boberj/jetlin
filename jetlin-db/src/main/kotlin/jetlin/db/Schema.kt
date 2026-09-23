package jetlin.db

import java.sql.PreparedStatement
import java.sql.Types
import kotlin.reflect.KClass

/**
 * The storage type of a column.
 *
 * SQLite has no Boolean type, so [Bool] is stored as `INTEGER`. It's still a separate constant, so
 * the column, not the caller, knows to convert between `Boolean` and `0` or `1`.
 *
 * @property sql the SQLite type name.
 */
public enum class ColumnType(internal val sql: String) {
    /** A whole number. */
    Integer("INTEGER"),

    /** A floating-point number. */
    Real("REAL"),

    /** A string. */
    Text("TEXT"),

    /** A Boolean, stored as `0` or `1`. */
    Bool("INTEGER"),
}

/**
 * A stored column of a record.
 *
 * For mutable fields, [name] is also the name of the [Cell] that holds the value. That's how a write
 * to a cell becomes an update of a single column, without comparing the whole record.
 *
 * [read] is a getter, not a cell lookup, so immutable fields can be stored too. For example,
 * `@Owner val owner: User` is a constructor property without a cell, and it still has to be written
 * on insert.
 *
 * @property name the column name.
 * @property type how the column is stored.
 * @property nullable whether the column can hold `NULL`.
 * @property references the entity class this column references, or `null` if it isn't a reference.
 * @property read returns the column's value from a record.
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
 * hand, so the builder stays simple.
 *
 * @property name the table name.
 * @property type the entity class.
 * @property columns the columns, not including the implicit `id`.
 * @property instantiate creates a record from a stored row.
 */
public class Table<T : Record> internal constructor(
    public val name: String,
    public val type: KClass<T>,
    internal val columns: List<Column<T>>,
    internal val instantiate: (Row) -> T,
) {
    /** The columns, by name. */
    internal val columnsByName: Map<String, Column<T>> = columns.associateBy { it.name }

    /**
     * Returns the column called [name].
     *
     * Generated code gets its column objects from here, so `Todos.archived` is the same instance the
     * table uses. That matters because a policy's `when (column)` compares by identity, and a separate
     * but identical column object would never match.
     *
     * @throws IllegalStateException if the table has no such column.
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
 * Every table has an implicit `id` column, so no other column can be named `id`.
 *
 * @param name the table name.
 * @param type the entity class.
 * @param declare declares the columns and the `load` block.
 * @throws IllegalStateException if [declare] has no `load` block.
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

    /** The block that [load] set. */
    private var loader: ((Row) -> T)? = null

    /**
     * Declares an integer column, for a `Long`, an `Int`, or any other `Number` stored as a whole
     * number.
     */
    public fun integer(name: String, nullable: Boolean = false, read: (T) -> Number?) {
        add(name, ColumnType.Integer, nullable, references = null, read = read)
    }

    /** Declares a floating-point column. */
    public fun real(name: String, nullable: Boolean = false, read: (T) -> Number?) {
        add(name, ColumnType.Real, nullable, references = null, read = read)
    }

    /** Declares a text column. */
    public fun text(name: String, nullable: Boolean = false, read: (T) -> String?) {
        add(name, ColumnType.Text, nullable, references = null, read = read)
    }

    /** Declares a Boolean column, stored as `0` or `1`. */
    public fun bool(name: String, nullable: Boolean = false, read: (T) -> Boolean?) {
        add(name, ColumnType.Bool, nullable, references = null, read = read)
    }

    /**
     * Declares a reference to another record. It's stored as that record's ID and resolved to the
     * object when loaded.
     *
     * References are nullable by default. Non-null references are less common and more restrictive:
     * the referenced table has to be loaded first, which limits the order tables are registered in.
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
     * The block doesn't set the ID, and can't. The loader applies the stored ID after the block
     * returns.
     *
     * @throws IllegalStateException if the table already has a `load` block.
     */
    public fun load(block: (Row) -> T) {
        check(loader == null) { "table '$name' declares load { } twice" }
        loader = block
    }

    /** Adds a column, or throws if the name is `id` or already taken. */
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

    /** Returns the declared table. */
    internal fun build(): Table<T> {
        val load = checkNotNull(loader) {
            "table '$name' has no load { } block, so rows read from disk cannot be turned back into " +
                "${type.simpleName} objects"
        }
        return Table(name, type, columns.toList(), load)
    }
}

/**
 * A row read from the database, with the identity map that resolves its references.
 *
 * Each accessor names the type it expects instead of inferring it. If the schema and the
 * constructor disagree, loading fails on that row with the column name in the message, instead of
 * converting the value without an error and producing a record that looks valid.
 *
 * The accessors without `OrNull` throw [IllegalStateException] if the value is `NULL`. Every
 * accessor throws [IllegalArgumentException] if the row has no column called `name`.
 */
public class Row internal constructor(
    private val values: Map<String, Any?>,
    private val resident: IdentityMap,
) {
    /** Reads the column [name] as a `Long`. */
    public fun long(name: String): Long = requireValue(name) { (it as Number).toLong() }

    /** Reads the column [name] as a `Long`, or `null`. */
    public fun longOrNull(name: String): Long? = optionalValue(name) { (it as Number).toLong() }

    /** Reads the column [name] as an `Int`. */
    public fun int(name: String): Int = requireValue(name) { (it as Number).toInt() }

    /** Reads the column [name] as an `Int`, or `null`. */
    public fun intOrNull(name: String): Int? = optionalValue(name) { (it as Number).toInt() }

    /** Reads the column [name] as a `Double`. */
    public fun double(name: String): Double = requireValue(name) { (it as Number).toDouble() }

    /** Reads the column [name] as a `Double`, or `null`. */
    public fun doubleOrNull(name: String): Double? = optionalValue(name) { (it as Number).toDouble() }

    /** Reads the column [name] as a `String`. */
    public fun string(name: String): String = requireValue(name) { it.toString() }

    /** Reads the column [name] as a `String`, or `null`. */
    public fun stringOrNull(name: String): String? = optionalValue(name) { it.toString() }

    /** Reads the column [name] as a `Boolean`, which SQLite stores as `0` or `1`. */
    public fun boolean(name: String): Boolean = requireValue(name) { (it as Number).toLong() != 0L }

    /** Reads the column [name] as a `Boolean`, or `null`. */
    public fun booleanOrNull(name: String): Boolean? = optionalValue(name) { (it as Number).toLong() != 0L }

    /**
     * Returns the record that the column [name] references.
     *
     * The record is looked up in the identity map, so the referenced table must already be loaded,
     * which means it must be registered earlier in the table list. A reference to a later table fails
     * here with an error that names both tables, instead of leaving a missing object in the graph.
     */
    public fun <R : Record> reference(name: String, type: KClass<R>): R =
        referenceOrNull(name, type)
            ?: error("Column '$name' is null, but the ${type.simpleName} reference it holds is not nullable")

    /** Returns the record that the column [name] references, or `null`. See [reference]. */
    public fun <R : Record> referenceOrNull(name: String, type: KClass<R>): R? {
        val id = longOrNull(name) ?: return null
        return resident.find(type, Id(id))
            ?: error(
                "Column '$name' points at ${type.simpleName}#$id, which is not resident. Register " +
                    "${type.simpleName}'s table before the table that references it: tables are loaded " +
                    "in the order they are declared, and jetlin-db does not resolve forward references.",
            )
    }

    /** Reads the column [name] with [convert], and throws if it's `NULL`. */
    private inline fun <V> requireValue(name: String, convert: (Any) -> V): V =
        optionalValue(name, convert) ?: error("Column '$name' is null, but was read as a non-null value")

    /** Reads the column [name] with [convert], or returns `null` if it's `NULL`. */
    private inline fun <V> optionalValue(name: String, convert: (Any) -> V): V? {
        require(values.containsKey(name)) { "No column '$name' in this row; it has ${values.keys}" }
        return values[name]?.let(convert)
    }
}

/** Binds [value] to a statement parameter. A record reference is bound as the record's ID. */
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

/**
 * Returns the `CREATE TABLE` statement for this table, including foreign keys to the referenced
 * tables.
 */
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
