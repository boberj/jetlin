package jetlin.db.gradle

/** A difference between the recorded schema and the schema the entities currently declare. */
public sealed interface Change {
    /** Whether applying this change loses data that reversing the migration couldn't restore. */
    public val destructive: Boolean

    /** A one-line description, for the migration's header and `dbVerify`'s error message. */
    public val summary: String

    /** A new table. */
    public data class AddTable(val table: TableSchema) : Change {
        override val destructive: Boolean get() = false
        override val summary: String get() = "add table ${table.name}"
    }

    /** A table that no entity declares anymore. */
    public data class DropTable(val table: TableSchema) : Change {
        override val destructive: Boolean get() = true
        override val summary: String get() = "drop table ${table.name}, and everything in it"
    }

    /** A new column in the existing table [table]. */
    public data class AddColumn(val table: String, val column: ColumnSchema) : Change {
        override val destructive: Boolean get() = false
        override val summary: String get() = "add $table.${column.name} (${column.type})"
    }

    /** A column that the entity doesn't declare anymore. */
    public data class DropColumn(val table: String, val column: ColumnSchema) : Change {
        override val destructive: Boolean get() = true
        override val summary: String get() = "drop $table.${column.name}, and the values in it"
    }

    /**
     * A column whose type, nullability, or foreign key changed.
     *
     * SQLite can't make these changes in place, so they require rebuilding the table.
     */
    public data class AlterColumn(
        val table: String,
        val from: ColumnSchema,
        val to: ColumnSchema,
    ) : Change {
        override val destructive: Boolean
            // Narrowing can lose data. SQLite converts values that don't fit a new type without an
            // error, and a row that violates a new NOT NULL makes the migration fail.
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
 * Lists the changes that turn schema [from] into schema [to].
 *
 * The changes are ordered so the SQL can run from top to bottom. New tables come before columns
 * that reference them, and drops come last, so a table isn't removed while something still
 * references it.
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

    // Put drops last. If something still references a dropped table or column, the foreign key check
    // fails the migration instead of leaving a dangling reference.
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
