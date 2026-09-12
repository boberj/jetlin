package jetlin.db.gradle

/** One difference between the recorded schema and what the entities now declare. */
public sealed interface Change {
    /** Whether applying this loses data that cannot be recovered by running the migration backwards. */
    public val destructive: Boolean

    /** One line, for the migration's header and for `dbVerify`'s failure message. */
    public val summary: String

    public data class AddTable(val table: TableSchema) : Change {
        override val destructive: Boolean get() = false
        override val summary: String get() = "add table ${table.name}"
    }

    public data class DropTable(val table: TableSchema) : Change {
        override val destructive: Boolean get() = true
        override val summary: String get() = "drop table ${table.name}, and everything in it"
    }

    public data class AddColumn(val table: String, val column: ColumnSchema) : Change {
        override val destructive: Boolean get() = false
        override val summary: String get() = "add $table.${column.name} (${column.type})"
    }

    public data class DropColumn(val table: String, val column: ColumnSchema) : Change {
        override val destructive: Boolean get() = true
        override val summary: String get() = "drop $table.${column.name}, and the values in it"
    }

    /**
     * A column whose type, nullability or foreign key changed.
     *
     * The case SQLite cannot do in place, and therefore the case that makes the generator interesting.
     */
    public data class AlterColumn(
        val table: String,
        val from: ColumnSchema,
        val to: ColumnSchema,
    ) : Change {
        override val destructive: Boolean
            // Narrowing is the lossy direction: a value that does not fit is silently coerced by SQLite,
            // and a row that does not satisfy a new NOT NULL stops the migration rather than warning.
            get() = from.nullable && !to.nullable || from.type != to.type

        override val summary: String get() = buildString {
            append("change $table.${from.name}: ")
            val differences = buildList {
                if (from.type != to.type) add("${from.type} to ${to.type}")
                if (from.nullable != to.nullable) add(if (to.nullable) "now optional" else "now required")
                if (from.references != to.references) {
                    add(
                        when {
                            to.references == null -> "no longer references ${from.references}"
                            from.references == null -> "now references ${to.references}"
                            else -> "references ${to.references} instead of ${from.references}"
                        },
                    )
                }
            }
            append(differences.joinToString(", "))
        }
    }
}

/**
 * What has to happen to turn [from] into [to].
 *
 * Ordered so that the SQL can be applied in sequence: tables arrive before the columns that reference
 * them, and drops come last, so a table is never removed while something still points at it.
 */
public fun diff(from: SchemaFile, to: SchemaFile): List<Change> {
    val changes = mutableListOf<Change>()

    for (table in to.tables) {
        val before = from.table(table.name)
        if (before == null) {
            changes += Change.AddTable(table)
            continue
        }
        for (column in table.columns) {
            val previous = before.column(column.name)
            when {
                previous == null -> changes += Change.AddColumn(table.name, column)
                previous != column -> changes += Change.AlterColumn(table.name, previous, column)
            }
        }
    }

    // Drops last: a column or table still referenced is a foreign key failure rather than a silent hole.
    for (table in from.tables) {
        val now = to.table(table.name) ?: continue
        for (column in table.columns) {
            if (now.column(column.name) == null) changes += Change.DropColumn(table.name, column)
        }
    }
    for (table in from.tables) {
        if (to.table(table.name) == null) changes += Change.DropTable(table)
    }

    return changes
}
