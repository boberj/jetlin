package jetlin.db.ksp

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Visibility

/** Creates the [JetlinDbProcessor]. KSP finds it through the service loader. */
public class JetlinDbProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        JetlinDbProcessor(environment.codeGenerator, environment.logger, environment.options)
}

private const val RECORD = "jetlin.db.Record"
private const val ENTITY = "jetlin.db.Entity"
private const val OWNER = "jetlin.db.Owner"
private const val POLICY = "jetlin.db.Policy"

/**
 * Generates the table, column object, and draft type for each `@Entity` class.
 *
 * Hand-written schemas tend to drift in two ways: a column stops matching the property it stores,
 * and a load function stops matching the constructor. Generating both from the entity declaration
 * rules these out.
 *
 * The processor also fails the build for any entity without a policy. An entity with no access
 * rules is almost always a mistake, and compile time is the cheapest point to catch it.
 *
 * It reads two options: `jetlin.db.schemaPackage`, the package of the generated schema object,
 * which defaults to the entities' common package, and `jetlin.db.schemaName`, the object's name,
 * which defaults to `JetlinSchema`.
 */
internal class JetlinDbProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {

    /** Whether this processor already generated its files. It generates them in the first round only. */
    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()

        val declarations = resolver.getSymbolsWithAnnotation(ENTITY)
            .filterIsInstance<KSClassDeclaration>()
            .toList()
        if (declarations.isEmpty()) return emptyList()
        generated = true

        val models = declarations.mapNotNull { declaration -> model(declaration) }
        var failed = declarations.size != models.size
        for ((index, model) in models.withIndex()) {
            val errors = validate(model)
            errors.forEach { logger.error(it, declarations[index]) }
            failed = failed || errors.isNotEmpty()
        }
        if (failed) return emptyList()

        for ((index, model) in models.withIndex()) {
            write(
                packageName = model.packageName,
                fileName = "${model.simpleName}Table",
                contents = emitTable(model),
                dependencies = Dependencies(aggregating = false, declarations[index].containingFile!!),
            )
        }

        when (val order = orderForLoad(models)) {
            is OrderResult.Cycle -> logger.error(
                "The non-null references between ${order.entities.joinToString()} form a cycle, so there " +
                    "is no order in which their tables can be loaded: a reference resolves against what " +
                    "is already resident. Make one of them nullable.",
            )
            is OrderResult.Ordered -> {
                val schemaPackage = options["jetlin.db.schemaPackage"] ?: commonPackage(models)
                val schemaName = options["jetlin.db.schemaName"] ?: "JetlinSchema"
                val files = declarations.mapNotNull { it.containingFile }.toTypedArray()
                write(
                    packageName = schemaPackage,
                    fileName = schemaName,
                    contents = emitSchema(order.entities, schemaPackage, schemaName),
                    dependencies = Dependencies(aggregating = true, *files),
                )
                writeSnapshot(emitSnapshot(order.entities), Dependencies(aggregating = true, *files))
            }
        }
        return emptyList()
    }

    /** Reads [declaration] into a model, or reports an error and returns `null`. */
    private fun model(declaration: KSClassDeclaration): EntityModel? {
        val name = declaration.simpleName.asString()
        if (declaration.classKind != ClassKind.CLASS || !extendsRecord(declaration)) {
            logger.error("@Entity $name must be a class extending jetlin.db.Record.", declaration)
            return null
        }

        val constructor = declaration.primaryConstructor
        if (constructor == null) {
            logger.error("@Entity $name has no primary constructor, so a stored row cannot be rebuilt.", declaration)
            return null
        }
        // Collect all parameter names, not only the ones declared as properties. With the usual
        // `var title by column(title)` pattern, `title` is a plain parameter, and the loader passes
        // the stored value through it.
        val constructorParameters = constructor.parameters.mapNotNull { it.name?.asString() }.toSet()
        val constructorProperties = constructor.parameters
            .filter { it.isVal || it.isVar }
            .mapNotNull { it.name?.asString() }
            .toSet()
        val ownerParameters = constructor.parameters
            .filter { parameter -> parameter.annotations.any { it.qualifiedName() == OWNER } }
            .mapNotNull { it.name?.asString() }
            .toSet()

        val columns = declaration.getDeclaredProperties().mapNotNull { property ->
            column(declaration, property, constructorParameters, constructorProperties, ownerParameters)
        }.toList()

        val policy = declaration.declarations
            .filterIsInstance<KSClassDeclaration>()
            .firstOrNull { it.isCompanionObject }
            ?.let { companion -> findPolicy(companion) }

        val annotation = declaration.annotations.first { it.qualifiedName() == ENTITY }
        val declaredTable = annotation.arguments
            .firstOrNull { it.name?.asString() == "table" }
            ?.value as? String

        return EntityModel(
            packageName = declaration.packageName.asString(),
            simpleName = name,
            tableName = declaredTable?.takeIf { it.isNotBlank() } ?: plural(snakeCase(name)),
            isInternal = declaration.getVisibility() == Visibility.INTERNAL,
            hasPolicy = policy != null,
            principalType = policy?.arguments?.getOrNull(1)?.type?.resolve()?.qualified(),
            policySubject = policy?.arguments?.getOrNull(0)?.type?.resolve()?.qualified(),
            columns = columns,
            constructorParameters = constructor.parameters.mapNotNull { parameter ->
                parameter.name?.asString()?.let { ParameterModel(it, parameter.hasDefault) }
            },
        )
    }

    /**
     * Reads [property] into a column model.
     *
     * @return the column, or `null` if the property isn't stored or can't be. For a property that
     *   can't be stored, this reports an error or a warning.
     */
    private fun column(
        entity: KSClassDeclaration,
        property: KSPropertyDeclaration,
        constructorParameters: Set<String>,
        constructorProperties: Set<String>,
        ownerParameters: Set<String>,
    ): ColumnModel? {
        val name = property.simpleName.asString()
        val delegated = property.isDelegated()
        val isConstructorProperty = name in constructorProperties
        val fromConstructor = name in constructorParameters
        if (property.isPrivate()) return null

        if (!delegated && !isConstructorProperty) {
            // Computed properties are derived, so skipping them is expected. A property with a
            // backing field is more likely a column declared the wrong way, so warn instead of not
            // storing it without a word.
            if (property.hasBackingField) {
                logger.warn(
                    "${entity.simpleName.asString()}.$name is not stored: only delegated properties " +
                        "(`by column(…)`, `by reference()`) and constructor properties are. Declare it as a " +
                        "column, or make it private if it is meant to be scratch state.",
                    property,
                )
            }
            return null
        }

        if (!delegated && property.isMutable) {
            logger.error(
                "${entity.simpleName.asString()}.$name is a plain `var`, so a write to it would reach the " +
                    "screen and never the database — nothing records it. Declare it as `var $name by " +
                    "column($name)`, or make it a `val`.",
                property,
            )
            return null
        }

        val type = property.type.resolve()
        val qualified = type.qualified()
        val reference = referenceTarget(type)
        val kind = when {
            reference != null -> SqlKind.Integer
            qualified == "kotlin.String" -> SqlKind.Text
            qualified == "kotlin.Boolean" -> SqlKind.Bool
            qualified == "kotlin.Long" || qualified == "kotlin.Int" -> SqlKind.Integer
            qualified == "kotlin.Double" || qualified == "kotlin.Float" -> SqlKind.Real
            else -> {
                logger.error(
                    "${entity.simpleName.asString()}.$name is a $qualified, which jetlin-db cannot store. " +
                        "A column is a String, Boolean, Int, Long, Double, Float, or a reference to " +
                        "another Record.",
                    property,
                )
                return null
            }
        }

        return ColumnModel(
            name = name,
            kind = kind,
            baseType = reference ?: qualified,
            nullable = type.isMarkedNullable,
            reference = reference,
            settable = delegated && property.isMutable,
            owner = name in ownerParameters ||
                property.annotations.any { it.qualifiedName() == OWNER },
            constructorParameter = fromConstructor,
        )
    }

    /** Returns the qualified name of the referenced record type, or `null` if [type] isn't a record. */
    private fun referenceTarget(type: KSType): String? {
        val declaration = type.declaration as? KSClassDeclaration ?: return null
        return if (extendsRecord(declaration)) declaration.qualifiedName?.asString() else null
    }

    /** Returns whether [declaration] is `jetlin.db.Record` or extends it. */
    private fun extendsRecord(declaration: KSClassDeclaration): Boolean {
        if (declaration.qualifiedName?.asString() == RECORD) return true
        return declaration.superTypes.any { reference ->
            val super_ = reference.resolve().declaration as? KSClassDeclaration ?: return@any false
            extendsRecord(super_)
        }
    }

    /** Finds the companion's `Policy<T, P>` supertype, searching inherited interfaces too. */
    private fun findPolicy(declaration: KSClassDeclaration): KSType? {
        for (reference in declaration.superTypes) {
            val type = reference.resolve()
            val super_ = type.declaration as? KSClassDeclaration ?: continue
            if (super_.qualifiedName?.asString() == POLICY) return type
            findPolicy(super_)?.let { return it }
        }
        return null
    }

    /** Writes a generated Kotlin source file. */
    private fun write(packageName: String, fileName: String, contents: String, dependencies: Dependencies) {
        codeGenerator.createNewFile(dependencies, packageName, fileName).bufferedWriter().use { writer ->
            writer.write(contents)
        }
    }

    /**
     * Writes the schema as JSON for the migration tooling.
     *
     * The file goes into the generated resources output, not the source tree, because a code
     * generator shouldn't write files that people are expected to review. `dbDiff` and `dbVerify`
     * compare it with the snapshot checked into the repository.
     */
    private fun writeSnapshot(contents: String, dependencies: Dependencies) {
        codeGenerator.createNewFileByPath(dependencies, "jetlin-db-schema", "json")
            .bufferedWriter()
            .use { writer -> writer.write(contents) }
    }

    /** Returns the longest package prefix that all [models] share. */
    private fun commonPackage(models: List<EntityModel>): String {
        val packages = models.map { it.packageName.split('.') }
        val shared = packages.reduce { a, b -> a.zip(b).takeWhile { (x, y) -> x == y }.map { it.first } }
        return shared.joinToString(".")
    }
}

/** Returns the annotation class's qualified name. */
private fun com.google.devtools.ksp.symbol.KSAnnotation.qualifiedName(): String? =
    annotationType.resolve().declaration.qualifiedName?.asString()

/** Returns the type's qualified name, or its simple name if it has none. */
private fun KSType.qualified(): String =
    declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()

/** Converts a class name to snake case, for example `MyThing` → `my_thing`, for the default table name. */
internal fun snakeCase(name: String): String = buildString {
    name.forEachIndexed { index, character ->
        if (character.isUpperCase()) {
            if (index > 0) append('_')
            append(character.lowercaseChar())
        } else {
            append(character)
        }
    }
}
