package jetlin.db.gradle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A schema as data, for both the schema the entities declare and the recorded snapshot.
 *
 * `:jetlin-db-ksp` writes the declared schema into the generated resources, and the recorded schema
 * is checked into the repository as `db/schema.json`. Comparing them determines what a migration has
 * to do, and `dbVerify` fails when they differ. That check ensures that generated migrations start
 * from an up-to-date snapshot.
 *
 * @property version the format version.
 * @property tables the tables, sorted by name.
 */
@Serializable
public data class SchemaFile(
    val version: Int = 1,
    val tables: List<TableSchema> = emptyList(),
) {
    /** Returns the table called [name], or `null` if there's none. */
    internal fun table(name: String): TableSchema? = tables.firstOrNull { it.name == name }

    public companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        /**
         * An empty schema. It stands in for the recorded schema when a repository has no snapshot
         * yet.
         */
        public val Empty: SchemaFile = SchemaFile()

        /** Parses a schema from JSON. Unknown keys are ignored. */
        public fun parse(text: String): SchemaFile = json.decodeFromString(text)

        /** Writes [schema] as indented JSON. */
        public fun write(schema: SchemaFile): String = json.encodeToString(schema)
    }
}

/**
 * One table in a [SchemaFile].
 *
 * @property name the table name.
 * @property entity the qualified name of the entity class that the table stores.
 * @property columns the columns, with `id` first and the rest sorted by name.
 */
@Serializable
public data class TableSchema(
    val name: String,
    val entity: String = "",
    val columns: List<ColumnSchema> = emptyList(),
) {
    /** Returns the column called [name], or `null` if there's none. */
    internal fun column(name: String): ColumnSchema? = columns.firstOrNull { it.name == name }
}

/**
 * One column in a [TableSchema].
 *
 * @property name the column name.
 * @property type the SQLite type, such as `INTEGER` or `TEXT`.
 * @property nullable whether the column can hold `NULL`.
 * @property references the name of the table this column references, or `null` if it isn't a
 *   reference.
 * @property primaryKey whether this is the `id` column.
 * @property owner whether the column holds the record's owner.
 */
@Serializable
public data class ColumnSchema(
    val name: String,
    val type: String,
    val nullable: Boolean = true,
    val references: String? = null,
    val primaryKey: Boolean = false,
    val owner: Boolean = false,
) {
    /** Returns the column definition as written inside `CREATE TABLE`. */
    internal fun definition(): String = buildString {
        append(name).append(' ').append(type)
        if (primaryKey) append(" PRIMARY KEY")
        if (!nullable && !primaryKey) append(" NOT NULL")
        references?.let { append(" REFERENCES ").append(it).append("(id)") }
    }

    /**
     * Returns the value to fill existing rows with when this column is added.
     *
     * It's a guess, written into the generated migration so the author sees it before running it.
     * The framework can't know the right value, but generating nothing would leave the author to
     * write the whole migration by hand.
     */
    internal fun backfill(): String = when {
        nullable -> "NULL"
        references != null -> "NULL /* jetlin-db: a non-null reference has no default — supply one */"
        type == "TEXT" -> "''"
        else -> "0"
    }
}
