package jetlin.db.ksp

/** A column's storage type. It mirrors `jetlin.db.ColumnType`. */
internal enum class SqlKind(val sql: String) {
    Integer("INTEGER"),
    Real("REAL"),
    Text("TEXT"),
    Bool("INTEGER"),
}

/**
 * A stored column, as read from an entity declaration.
 *
 * @property name the property name, which is also the column name.
 * @property kind how the column is stored.
 * @property baseType a type such as `kotlin.String` or `kotlin.Boolean`, or the qualified name of a
 *   referenced record type.
 * @property nullable whether the property's type is nullable.
 * @property reference the qualified name of the referenced record type, or `null` if the column
 *   isn't a reference.
 * @property settable whether the property is a delegated `var`. A delegated property is a cell that
 *   can be written after construction, so the draft can expose it, and the loader can set it outside
 *   the constructor call. Constructor properties aren't settable.
 * @property owner whether the property is marked with `@Owner`.
 * @property constructorParameter whether the primary constructor has a parameter with this name,
 *   which the loader can pass the value to.
 */
internal data class ColumnModel(
    val name: String,
    val kind: SqlKind,
    val baseType: String,
    val nullable: Boolean,
    val reference: String?,
    val settable: Boolean,
    val owner: Boolean,
    val constructorParameter: Boolean,
) {
    /** The property's type as written in Kotlin, including `?` when it's nullable. */
    val declaredType: String get() = if (nullable) "$baseType?" else baseType
}

/**
 * A parameter of an entity's primary constructor.
 *
 * A parameter that isn't a column needs a default value, or the loader can't call the constructor.
 */
internal data class ParameterModel(val name: String, val hasDefault: Boolean)

/**
 * An `@Entity` class, as the processor read it.
 *
 * @property packageName the class's package.
 * @property simpleName the class's name.
 * @property tableName the table name, from `@Entity(table = …)` or the default rule.
 * @property isInternal whether the class is `internal`. The generated declarations match it.
 * @property hasPolicy whether the companion object implements `Policy`.
 * @property principalType the qualified principal type `P` from `Policy<T, P>`, or `null` if there's
 *   no policy.
 * @property policySubject the type `T` from `Policy<T, P>`. If it isn't this entity, the companion
 *   was probably copied from another entity.
 * @property columns the stored columns, in declaration order.
 * @property constructorParameters the primary constructor's parameters, in order.
 */
internal data class EntityModel(
    val packageName: String,
    val simpleName: String,
    val tableName: String,
    val isInternal: Boolean,
    val hasPolicy: Boolean,
    val principalType: String?,
    val policySubject: String?,
    val columns: List<ColumnModel>,
    val constructorParameters: List<ParameterModel>,
) {
    /** The class's qualified name. */
    val qualifiedName: String get() = if (packageName.isEmpty()) simpleName else "$packageName.$simpleName"

    /**
     * The name of the generated object that holds the table and its columns, for example `Todo` →
     * `Todos`.
     */
    val objectName: String get() = plural(simpleName)

    /** The name of the generated draft class, for example `TodoDraft`. */
    val draftName: String get() = "${simpleName}Draft"

    /** The draft class's qualified name. */
    val draftQualified: String get() = qualify(draftName)

    /** The generated object's qualified name. */
    val objectQualified: String get() = qualify(objectName)

    /** The name of the collection accessor, such as `db.todos`: [objectName] with a lowercase first letter. */
    val collectionName: String get() = objectName.replaceFirstChar(Char::lowercaseChar)

    /**
     * The policy's principal type, which every generated accessor takes as a context parameter.
     *
     * It's read only for entities with a policy. [validate] rejects an entity whose principal type
     * can't be resolved, because the generated code can't be written without it.
     */
    val principal: String get() = principalType ?: "jetlin.db.Principal"

    /**
     * The column `transferTo` writes, or `null` if the entity has no transfer functions.
     *
     * That's the `@Owner` column, if it's a settable `var` and holds the principal type: a transfer
     * sets it to a principal. An owner that's a `val` can't change hands.
     */
    val transferableOwner: ColumnModel?
        get() = columns.singleOrNull { it.owner }?.takeIf { it.settable && it.reference == principalType }

    /** The visibility modifier for the generated declarations. */
    val visibility: String get() = if (isInternal) "internal" else "public"

    /** Returns the object's name, qualified unless [inPackage] is the entity's package. */
    fun objectQualified(inPackage: String): String =
        if (inPackage == packageName) objectName else qualify(objectName)

    /** Returns [name] qualified with the entity's package. */
    private fun qualify(name: String): String = if (packageName.isEmpty()) name else "$packageName.$name"
}

/**
 * Pluralizes a name with a simple rule: `Todo` → `todos`, `Status` → `statuses`.
 *
 * The rule is deliberately basic. For words it gets wrong, use `@Entity(table = "people")`. A
 * dictionary of irregular plurals would still be wrong for some names, only different ones.
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
 * This is a pure function, so the rules and their messages can be unit-tested directly, without
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
 * References are resolved against records that are already loaded, so the load order has to be
 * right. Computing it here means the application doesn't have to maintain it by hand. A nullable
 * reference doesn't constrain the order, and neither does a reference to a type that isn't among
 * [entities].
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

/** The result of [orderForLoad]. */
internal sealed interface OrderResult {
    /** The entities, in an order in which their tables can be loaded. */
    data class Ordered(val entities: List<EntityModel>) : OrderResult

    /** The names of the entities whose non-null references form a cycle, sorted. */
    data class Cycle(val entities: List<String>) : OrderResult
}
