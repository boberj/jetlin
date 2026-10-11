package jetlin.db.migrate

import java.sql.Connection

/**
 * A database's schema, as structure rather than SQL text.
 *
 * The plugin compares the schema that replaying the migrations produces with the one the entities
 * declare, and the runner uses it to notice an index, trigger, or view that a migration lost.
 * Comparing structure, instead of `sqlite_schema`'s SQL text, means that formatting, column order,
 * and the SQLite version don't matter. Indexes, triggers, and views are compared by name only.
 *
 * The history table and SQLite's own `sqlite_` tables are left out.
 *
 * @property tables each table's columns, by table name and then column name.
 * @property objects every index, trigger, and view, as `"index name"`, `"trigger name"`, or
 *   `"view name"`.
 */
public data class StoredSchema(
    val tables: Map<String, Map<String, StoredColumn>>,
    val objects: Set<String> = emptySet(),
) {
    /** Whether there's nothing at all: no tables, and no other objects. */
    public val isEmpty: Boolean get() = tables.isEmpty() && objects.isEmpty()

    /**
     * Describes each way this schema differs from [expected], one line per difference, or returns
     * an empty list if they're the same.
     *
     * @param includeObjects whether to compare indexes, triggers, and views too. The entities don't
     *   declare any, so the comparison with them leaves these out.
     */
    public fun differencesFrom(expected: StoredSchema, includeObjects: Boolean = true): List<String> = buildList {
        for ((table, columns) in expected.tables.toSortedMap()) {
            val actual = tables[table]
            if (actual == null) {
                add("table $table is missing")
                continue
            }
            for ((name, column) in columns.toSortedMap()) {
                val stored = actual[name]
                when {
                    stored == null -> add("$table.$name is missing")
                    stored != column -> add("$table.$name is $stored, expected $column")
                }
            }
            (actual.keys - columns.keys).sorted().forEach { add("$table.$it is not expected") }
        }
        (tables.keys - expected.tables.keys).sorted().forEach { add("table $it is not expected") }

        if (includeObjects) {
            (expected.objects - objects).sorted().forEach { add("$it is missing") }
            (objects - expected.objects).sorted().forEach { add("$it is not expected") }
        }
    }

    public companion object {
        /** Reads the schema of the database that [connection] is open on. */
        public fun read(connection: Connection): StoredSchema {
            val tableNames = mutableListOf<String>()
            val objects = mutableSetOf<String>()
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT type, name FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%' AND name != '$HISTORY'",
                ).use { results ->
                    while (results.next()) {
                        val type = results.getString(1)
                        val name = results.getString(2)
                        if (type == "table") tableNames += name else objects += "$type $name"
                    }
                }
            }
            return StoredSchema(tableNames.associateWith { columnsOf(connection, it) }, objects)
        }

        /** Reads [table]'s columns, by name. */
        private fun columnsOf(connection: Connection, table: String): Map<String, StoredColumn> {
            val references = mutableMapOf<String, String>()
            connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA foreign_key_list(\"$table\")").use { results ->
                    while (results.next()) references[results.getString("from")] = results.getString("table")
                }
            }
            return connection.createStatement().use { statement ->
                statement.executeQuery("PRAGMA table_info(\"$table\")").use { results ->
                    buildMap {
                        while (results.next()) {
                            val name = results.getString("name")
                            val primaryKey = results.getInt("pk") > 0
                            put(
                                name,
                                StoredColumn(
                                    type = results.getString("type").uppercase(),
                                    // SQLite reports `notnull = 0` for an INTEGER PRIMARY KEY, because
                                    // it's an alias for the rowid, where inserting NULL means "allocate
                                    // an ID." It's never null.
                                    nullable = !primaryKey && results.getInt("notnull") == 0,
                                    references = references[name],
                                    primaryKey = primaryKey,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One column of a [StoredSchema].
 *
 * @property type the declared type, uppercased, such as `INTEGER`.
 * @property nullable whether the column can hold `NULL`.
 * @property references the table this column references, or `null`.
 * @property primaryKey whether the column is (part of) the primary key.
 */
public data class StoredColumn(
    val type: String,
    val nullable: Boolean,
    val references: String? = null,
    val primaryKey: Boolean = false,
) {
    override fun toString(): String = buildString {
        append(type)
        if (primaryKey) append(" PRIMARY KEY")
        if (!nullable && !primaryKey) append(" NOT NULL")
        references?.let { append(" REFERENCES ").append(it) }
    }
}
