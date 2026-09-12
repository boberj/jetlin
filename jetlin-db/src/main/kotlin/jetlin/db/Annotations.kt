package jetlin.db

/**
 * Marks a [Record] subclass as stored, so that its table, column object and draft type are generated.
 *
 * What becomes a column is decided by how the property is declared, not by an annotation on it:
 *
 * - a delegated property — `var title by column(title)`, `var project: Project? by reference()` — is a
 *   column, and is mutable, so a draft can write it;
 * - a primary-constructor property — `@Owner val owner: User` — is a column, and is immutable, so it
 *   is written once by the insert;
 * - anything else is not stored: a computed `val` with a getter is derived, and a `private` property is
 *   the class's own business.
 *
 * [table] overrides the table name, which otherwise follows the class name: `Todo` is stored in
 * `todos`. Override it for anything English does not pluralize by adding an `s`.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
public annotation class Entity(public val table: String = "")

/**
 * Marks the column that owns the record, for the `owned()` policy shorthand and for migrations.
 *
 * Worth stating separately from the policy because it is a fact about the data rather than about the
 * rules: it stays true when the rules change.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
public annotation class Owner
