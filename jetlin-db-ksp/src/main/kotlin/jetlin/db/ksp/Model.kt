package jetlin.db.ksp

/** A column's storage type. Mirrors `jetlin.db.ColumnType`. */
internal enum class SqlKind(val sql: String) {
    Integer("INTEGER"),
    Real("REAL"),
    Text("TEXT"),
    Bool("INTEGER"),
}

/**
 * A stored column, as read from an entity declaration.
 *
 * [settable] is true for delegated properties and false for constructor properties. A delegated
 * property is a cell that can be written after construction, so the draft can expose it and the loader
 * can set it outside the constructor call.
 */
internal data class ColumnModel(
    val name: String,
    val kind: SqlKind,
    /** A type such as `kotlin.String` or `kotlin.Boolean`, or the qualified name of a referenced record type. */
    val baseType: String,
    val nullable: Boolean,
    val reference: String?,
    val settable: Boolean,
    val owner: Boolean,
    /** Whether the primary constructor has a parameter with this name, which the loader can pass it to. */
    val constructorParameter: Boolean,
) {
    val declaredType: String get() = if (nullable) "$baseType?" else baseType
}

/** A constructor parameter that isn't a column. It needs a default value, or the loader can't call the constructor. */
internal data class ParameterModel(val name: String, val hasDefault: Boolean)

internal data class EntityModel(
    val packageName: String,
    val simpleName: String,
    val tableName: String,
    val isInternal: Boolean,
    val hasPolicy: Boolean,
    /** The qualified principal type `P` from `Policy<T, P>`, or null if there is no policy. */
    val principalType: String?,
    /** The type `T` from `Policy<T, P>`. If it isn't this entity, the companion was probably copied from another entity. */
    val policySubject: String?,
    val columns: List<ColumnModel>,
    val constructorParameters: List<ParameterModel>,
) {
    val qualifiedName: String get() = if (packageName.isEmpty()) simpleName else "$packageName.$simpleName"

    /** The name of the generated object holding the table and its columns, for example `Todo` → `Todos`. */
    val objectName: String get() = plural(simpleName)

    val draftName: String get() = "${simpleName}Draft"

    val draftQualified: String get() = qualify(draftName)

    val objectQualified: String get() = qualify(objectName)

    /** The name of the collection accessor, such as `db.todos`: [objectName] with a lowercase first letter. */
    val collectionName: String get() = objectName.replaceFirstChar(Char::lowercaseChar)

    /**
     * The policy's principal type, which every generated accessor takes as a context parameter.
     *
     * This is only read for entities with a policy. [validate] rejects an entity whose principal type
     * can't be resolved, because the generated code can't be written without it.
     */
    val principal: String get() = principalType ?: "jetlin.db.Principal"

    val visibility: String get() = if (isInternal) "internal" else "public"

    /** The object's name, qualified unless the generated file is in the same package as the entity. */
    fun objectQualified(inPackage: String): String =
        if (inPackage == packageName) objectName else qualify(objectName)

    private fun qualify(name: String): String = if (packageName.isEmpty()) name else "$packageName.$name"
}

/**
 * Pluralizes a name with a simple rule: `Todo` → `todos`, `Status` → `statuses`.
 *
 * The rule is intentionally basic. For words it gets wrong, use `@Entity(table = "people")`. A
 * dictionary of irregular plurals would still be wrong for some names, just different ones.
 */
internal fun plural(name: String): String = when {
    name.endsWith("s", ignoreCase = true) -> "${name}es"
    name.endsWith("y") && name.length > 1 && name[name.length - 2] !in "aeiou" ->
        "${name.dropLast(1)}ies"
    else -> "${name}s"
}

/**
 * Returns every problem with an entity, as error messages to report on its declaration.
 *
 * This is a pure function so the rules and their messages can be unit-tested directly, without
 * compiling a source file and parsing the compiler output.
 */
internal fun validate(entity: EntityModel): List<String> = buildList {
    if (!entity.hasPolicy) {
        add(
            "@Entity ${entity.simpleName} declares no policy, so nothing decides who may read or write " +
                "it. Give it a companion object implementing Policy<${entity.simpleName}, YourUser> — " +
                "`companion object : Policy<${entity.simpleName}, User> by owned(${entity.simpleName}::owner)` " +
                "is the common case.",
        )
    }
    if (entity.policySubject != null && entity.policySubject != entity.qualifiedName) {
        add(
            "@Entity ${entity.simpleName} has a policy for ${entity.policySubject}, not for itself. A " +
                "companion copied from another entity guards the wrong records.",
        )
    }
    if (entity.hasPolicy && entity.principalType == null) {
        add(
            "@Entity ${entity.simpleName} has a policy, but its principal type could not be resolved. " +
                "Declare `Policy<${entity.simpleName}, YourUser>` on the companion directly rather than " +
                "through an interface that hides the type arguments.",
        )
    }
    if (entity.columns.isEmpty()) {
        add(
            "@Entity ${entity.simpleName} has no stored columns. A column is a delegated property — " +
                "`var title by column(title)` — or a primary-constructor property.",
        )
    }
    for (column in entity.columns) {
        if (!column.constructorParameter && !column.settable) {
            add(
                "${entity.simpleName}.${column.name} can never be loaded: it is not a constructor " +
                    "parameter, and it is not settable either, so a row read from disk has nowhere to " +
                    "put it. Make it a `var`, or take it in the constructor.",
            )
        }
    }
    for (parameter in entity.constructorParameters) {
        val stored = entity.columns.any { it.name == parameter.name }
        if (!stored && !parameter.hasDefault) {
            add(
                "${entity.simpleName} cannot be rebuilt from a stored row: its constructor requires " +
                    "'${parameter.name}', which is not a stored column and has no default. Store it as a " +
                    "column, or give it a default.",
            )
        }
    }
}

/**
 * Orders entities so each table is loaded after every table its non-null references point to.
 *
 * References are resolved against records that are already loaded, so the load order has to be right.
 * Computing it here means the application doesn't have to maintain it by hand.
 */
internal fun orderForLoad(entities: List<EntityModel>): OrderResult {
    val byName = entities.associateBy { it.qualifiedName }
    val ordered = mutableListOf<EntityModel>()
    val placed = mutableSetOf<String>()
    val remaining = entities.toMutableList()

    while (remaining.isNotEmpty()) {
        val ready = remaining.filter { entity ->
            entity.columns.all { column ->
                val target = column.reference
                column.nullable || target == null || target == entity.qualifiedName || target in placed ||
                    target !in byName
            }
        }
        if (ready.isEmpty()) {
            return OrderResult.Cycle(remaining.map { it.simpleName }.sorted())
        }
        ready.forEach { placed += it.qualifiedName }
        ordered += ready
        remaining -= ready.toSet()
    }
    return OrderResult.Ordered(ordered)
}

internal sealed interface OrderResult {
    data class Ordered(val entities: List<EntityModel>) : OrderResult
    data class Cycle(val entities: List<String>) : OrderResult
}
