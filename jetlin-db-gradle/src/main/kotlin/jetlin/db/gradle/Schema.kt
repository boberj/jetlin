package jetlin.db.gradle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A schema as data. Used both for the schema the entities declare and for the recorded snapshot.
 *
 * `:jetlin-db-ksp` writes the declared schema into the generated resources, and the recorded schema is
 * checked into the repository as `db/schema.json`. Comparing them determines what a migration has to
 * do, and `dbVerify` fails when they differ. That check is what ensures generated migrations are based
 * on an up-to-date snapshot.
 */
@Serializable
public data class SchemaFile(
    val version: Int = 1,
    val tables: List<TableSchema> = emptyList(),
) {
    internal fun table(name: String): TableSchema? = tables.firstOrNull { it.name == name }

    public companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            prettyPrintIndent = "  "
        }

        /** An empty schema, used as the recorded schema when a repository has no snapshot yet. */
        public val Empty: SchemaFile = SchemaFile()

        public fun parse(text: String): SchemaFile = json.decodeFromString(text)

        public fun write(schema: SchemaFile): String = json.encodeToString(schema)
    }
}

@Serializable
public data class TableSchema(
    val name: String,
    val entity: String = "",
    val columns: List<ColumnSchema> = emptyList(),
) {
    internal fun column(name: String): ColumnSchema? = columns.firstOrNull { it.name == name }
}

@Serializable
public data class ColumnSchema(
    val name: String,
    val type: String,
    val nullable: Boolean = true,
    val references: String? = null,
    val primaryKey: Boolean = false,
    val owner: Boolean = false,
) {
    /** The column definition as written inside `CREATE TABLE`. */
    internal fun definition(): String = buildString {
        append(name).append(' ').append(type)
        if (primaryKey) append(" PRIMARY KEY")
        if (!nullable && !primaryKey) append(" NOT NULL")
        references?.let { append(" REFERENCES ").append(it).append("(id)") }
    }

    /**
     * The value to fill into existing rows when this column is added.
     *
     * This is a guess, and it is written into the generated migration so the author sees it before
     * running it. The framework can't know the correct value, but generating nothing would leave the
     * author to write the whole migration by hand.
     */
    internal fun backfill(): String = when {
        nullable -> "NULL"
        references != null -> "NULL /* jetlin-db: a non-null reference has no default — supply one */"
        type == "TEXT" -> "''"
        else -> "0"
    }
}
