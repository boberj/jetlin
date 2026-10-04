package jetlin.db

import kotlin.reflect.KClass

/**
 * Marks the parts of `jetlin-db` that exist for testing and tooling libraries, such as
 * `jetlin-db-testing`, and not for applications.
 *
 * These functions reach past the policy checks: they read columns' metadata, write columns
 * directly, and evaluate changes that are never kept. Storing records without a check still goes
 * through [insertUnchecked], inside [unsafe]. Application code reads
 * and writes through the generated accessors instead, which check every access.
 *
 * To use them, opt in where you call them:
 *
 * ```kotlin
 * @OptIn(JetlinDbTooling::class)
 * fun buildWorld(db: Db) { … }
 * ```
 */
@RequiresOptIn(
    message = "This is for testing and tooling libraries. Application code should use the generated, policy-checked accessors.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
public annotation class JetlinDbTooling

/**
 * An application's stored entities, and the policy for each.
 *
 * The KSP processor generates an object that implements this, called `JetlinSchema`, in the package
 * of the first entity. Pass its [tables] to [Db.open]. Tooling, such as `jetlin-db-testing`, uses
 * [policyFor] to find each entity's access rules.
 */
public interface Schema {
    /** The tables, in load order: each one after the tables it references. */
    public val tables: List<Table<out Record>>

    /**
     * Gets the policy for [table].
     *
     * @return The policy declared on the entity's companion object.
     */
    public fun policyFor(table: Table<out Record>): Policy<out Record, out Principal>
}

/** The table's stored columns, not including the implicit `id`. */
@JetlinDbTooling
public val <T : Record> Table<T>.storedColumns: List<Column<T>> get() = columns

/** How the column is stored. */
@JetlinDbTooling
public val Column<*>.storageType: ColumnType get() = type

/** Checks whether the column can hold `null`. */
@JetlinDbTooling
public val Column<*>.isNullable: Boolean get() = nullable

/** The entity class the column references, or `null` if it isn't a reference. */
@JetlinDbTooling
public val Column<*>.referencedType: KClass<out Record>? get() = references

/**
 * Gets the column's value on [record].
 *
 * @return The value, which is a record for a reference.
 */
@JetlinDbTooling
public fun <T : Record> Column<T>.valueOf(record: T): Any? = read(record)

/**
 * Checks whether the column can change after [record] is created: whether it's a `var` with a
 * cell, not a constructor `val`.
 *
 * @return True if `update { }` can set the column; false otherwise.
 */
@JetlinDbTooling
public fun <T : Record> Column<T>.isSettable(record: T): Boolean = name in record.cells

/**
 * Writes [value] to the column on [record], without any policy check.
 *
 * A stored record can only be written inside [Db.transact] or [whatIf].
 *
 * @throws IllegalStateException if the column can't change after the record is created.
 */
@JetlinDbTooling
public fun <T : Record> Column<T>.write(record: T, value: Any?) {
    val cell = checkNotNull(record.cells[name]) { "$name is set only when ${record::class.simpleName} is created" }
    // The value came from the column's own pool, so it has the column's type.
    @Suppress("UNCHECKED_CAST")
    (cell as Cell<Any?>).value = value
}

/**
 * Deletes [record] without any policy check. Afterwards, [record] is unstored again, and
 * [insertUnchecked] can store it again as it was.
 *
 * @throws java.sql.SQLException if another stored record still references [record].
 */
@JetlinDbTooling
public fun Db.deleteFixture(record: Record) {
    transact { delete(record) }
}

/**
 * Runs [apply], then [check], in a snapshot that's discarded afterwards.
 *
 * Inside, [apply] can write stored records without a transaction, and [check] sees the writes, as
 * does everything it reads: other records, relations, and policies. Nothing is saved, and nothing
 * outside sees the writes.
 *
 * @return The value [check] returns.
 */
@JetlinDbTooling
public fun <R> whatIf(apply: () -> Unit, check: () -> R): R = Trials.run(apply, check)

/**
 * Creates the [Change] that an `update { }` setting [values] on [record] would propose, without
 * making it.
 *
 * Pass it to [Policy.canChange] to ask whether a principal could make the change.
 *
 * @param values The new value for each column to change.
 * @return The proposed change.
 */
@JetlinDbTooling
public fun <T : Record> proposedChange(record: T, values: Map<Column<T>, Any?>): Change<T> {
    val draft = ColumnDraft(record)
    for ((column, value) in values) draft.set(column, value)
    return Change(record, draft)
}

/**
 * A grant in a policy built with [policy], as tooling sees it.
 *
 * @property permission The permission, for example, "canEdit()".
 * @property condition The condition, for example, "the principal is its owner".
 */
@JetlinDbTooling
public class GrantDescription internal constructor(
    public val permission: String,
    public val condition: String,
    private val test: (record: Record, principal: Principal) -> Boolean,
) {
    /**
     * Checks whether the grant's condition holds for [principal] and [record].
     *
     * @return True if the condition holds; false otherwise.
     */
    public fun holds(record: Record, principal: Principal): Boolean = test(record, principal)

    override fun toString(): String = "$condition implies $permission"
}

/** A draft that sets columns by name, for [proposedChange]. */
internal class ColumnDraft<T : Record>(private val record: T) : Draft<T>() {
    fun set(column: Column<T>, value: Any?) {
        write(column, value) { newValue ->
            val cell = checkNotNull(record.cells[column.name]) { "${column.name} can't change after it's created" }
            // The value was chosen for this column.
            @Suppress("UNCHECKED_CAST")
            (cell as Cell<Any?>).value = newValue
        }
    }
}
