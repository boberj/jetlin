package jetlin.db.gradle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A schema as data: what the entities declare, and what the repository has recorded.
 *
 * Written by `:jetlin-db-ksp` into the generated resources, and checked into the repository as
 * `db/schema.json`. The two are compared to decide what a migration has to do, and `dbVerify` fails when
 * they disagree — which is the only thing that keeps a generated migration trustworthy.
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

        /** An empty schema: what a repository with no snapshot yet is compared against. */
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
    /** Column definition as it appears inside `CREATE TABLE`. */
    internal fun definition(): String = buildString {
        append(name).append(' ').append(type)
        if (primaryKey) append(" PRIMARY KEY")
        if (!nullable && !primaryKey) append(" NOT NULL")
        references?.let { append(" REFERENCES ").append(it).append("(id)") }
    }

    /**
     * What to put in an existing row when this column arrives.
     *
     * A guess, and deliberately a visible one: the generated migration carries it as SQL a human reads
     * before running. There is no value a framework can know is right, and refusing to generate anything
     * would leave the author with the harder half of the job.
     */
    internal fun backfill(): String = when {
        nullable -> "NULL"
        references != null -> "NULL /* jetlin-db: a non-null reference has no default — supply one */"
        type == "TEXT" -> "''"
        else -> "0"
    }
}
