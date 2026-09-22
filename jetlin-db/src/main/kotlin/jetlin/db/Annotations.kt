package jetlin.db

/**
 * Marks a [Record] subclass as stored. The KSP processor generates its table, column object and
 * draft type.
 *
 * Which properties become columns depends on how they are declared, not on annotations:
 *
 * - A delegated property, such as `var title by column(title)` or `var project: Project? by
 *   reference()`, is a mutable column that a draft can write.
 * - A primary-constructor property, such as `@Owner val owner: User`, is an immutable column, written
 *   once on insert.
 * - Anything else is not stored. A `val` with a custom getter is derived, and `private` properties are
 *   internal to the class.
 *
 * [table] sets the table name. By default it is the class name in snake case, pluralized by a simple
 * rule (`Todo` is stored in `todos`, `Status` in `statuses`). Set it for names the rule gets wrong,
 * such as `@Entity(table = "people")`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
public annotation class Entity(public val table: String = "")

/**
 * Marks the column holding the record's owner, for use by the `owned()` policy and by migrations.
 *
 * It is declared separately from the policy because ownership is a property of the data, not of the
 * access rules, and stays the same when the rules change.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
public annotation class Owner
