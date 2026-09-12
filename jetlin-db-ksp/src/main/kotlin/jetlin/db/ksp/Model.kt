package jetlin.db.ksp

/** How a column is stored, mirroring `jetlin.db.ColumnType`. */
internal enum class SqlKind(val sql: String) {
    Integer("INTEGER"),
    Real("REAL"),
    Text("TEXT"),
    Bool("INTEGER"),
}

/**
 * One stored column, as read off an entity declaration.
 *
 * [settable] is the difference between a delegated property and a constructor property: a cell can be
 * written after construction, which is what lets a draft expose it and what lets the loader restore it
 * outside the constructor call.
 */
internal data class ColumnModel(
    val name: String,
    val kind: SqlKind,
    /** `kotlin.String`, `kotlin.Boolean`, or the qualified name of the record a reference points at. */
    val baseType: String,
    val nullable: Boolean,
    val reference: String?,
    val settable: Boolean,
    val owner: Boolean,
    /** Whether the primary constructor takes a parameter of this name, so the loader can pass it. */
    val constructorParameter: Boolean,
) {
    val declaredType: String get() = if (nullable) "$baseType?" else baseType
}

/** A constructor parameter that is not a column: it has to have a default, or nothing can be loaded. */
internal data class ParameterModel(val name: String, val hasDefault: Boolean)

internal data class EntityModel(
    val packageName: String,
    val simpleName: String,
    val tableName: String,
    val isInternal: Boolean,
    val hasPolicy: Boolean,
    /** The qualified viewer type from `Policy<T, V>`, when there is a policy to read it from. */
    val viewerType: String?,
    /** The first type argument of `Policy<T, V>`; a mismatch is a copy-pasted companion. */
    val policySubject: String?,
    val columns: List<ColumnModel>,
    val constructorParameters: List<ParameterModel>,
) {
    val qualifiedName: String get() = if (packageName.isEmpty()) simpleName else "$packageName.$simpleName"

    /** `Todo` → `Todos`: the object holding the table and the columns a policy names. */
    val objectName: String get() = plural(simpleName)

    val draftName: String get() = "${simpleName}Draft"

    val draftQualified: String get() = qualify(draftName)

    val objectQualified: String get() = qualify(objectName)

    /** What `db.todos` is called: the column object's name, lowercased. */
    val collectionName: String get() = objectName.replaceFirstChar(Char::lowercaseChar)

    /**
     * The policy's viewer type, which every generated accessor takes as a context parameter.
     *
     * Never read without a policy: [validate] rejects an entity whose viewer type could not be resolved,
     * because the generated code is unwritable without it.
     */
    val viewer: String get() = viewerType ?: "jetlin.db.Principal"

    val visibility: String get() = if (isInternal) "internal" else "public"

    /** Qualified unless the generated file already sits in this entity's package. */
    fun objectQualified(inPackage: String): String =
        if (inPackage == packageName) objectName else qualify(objectName)

    private fun qualify(name: String): String = if (packageName.isEmpty()) name else "$packageName.$name"
}

/**
 * `Todo` → `todos`, `Status` → `statuses`.
 *
 * Deliberately the naive rule and nothing more: a word English does not pluralize this way is a reason
 * to write `@Entity(table = "people")`, not a reason for the processor to carry a dictionary that is
 * wrong in a different way for someone else.
 */
internal fun plural(name: String): String = when {
    name.endsWith("s", ignoreCase = true) -> "${name}es"
    name.endsWith("y") && name.length > 1 && name[name.length - 2] !in "aeiou" ->
        "${name.dropLast(1)}ies"
    else -> "${name}s"
}

/**
 * Everything wrong with one entity, as messages to report against its declaration.
 *
 * Pure, so that the rules are tested directly rather than by compiling a file and reading the
 * compiler's output: a rule that only fails inside a build is a rule nobody checks the wording of.
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
                "companion copied from another entity guards the wrong rows.",
        )
    }
    if (entity.hasPolicy && entity.viewerType == null) {
        add(
            "@Entity ${entity.simpleName} has a policy, but its viewer type could not be resolved. " +
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
 * Orders entities so that a table is loaded after everything its non-null references point at.
 *
 * References resolve against what is already resident, so load order is part of the schema rather than
 * something the application should have to get right by hand.
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
