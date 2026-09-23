package jetlin.db

/**
 * Marks a [Record] subclass as stored. The KSP processor generates its table, column object, and
 * draft type.
 *
 * How a property is declared, not an annotation, decides whether it becomes a column:
 *
 * - A delegated property, such as `var title by column(title)` or
 *   `var project: Project? by reference()`, is a mutable column that a draft can write.
 * - A primary-constructor property, such as `@Owner val owner: User`, is an immutable column,
 *   written once on insert.
 * - Anything else isn't stored. A `val` with a custom getter is derived, and `private` properties
 *   are internal to the class.
 *
 * @property table the table name. By default, it's the class name in snake case, pluralized by a
 *   simple rule: `Todo` is stored in `todos`, and `Status` in `statuses`. Set it for names that the
 *   rule gets wrong, such as `@Entity(table = "people")`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
public annotation class Entity(public val table: String = "")

/**
 * Marks the column that holds the record's owner. The `owned()` policy and migrations use it.
 *
 * It's declared separately from the policy because ownership is a property of the data, not of the
 * access rules, and it stays the same when the rules change.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
public annotation class Owner
