package jetlin.db.gradle

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

/**
 * Where an application's schema lives.
 *
 * ```kotlin
 * jetlinDb {
 *     // Defaults, spelled out: the generated schema comes from KSP, the recorded one is checked in.
 *     declared.set(layout.buildDirectory.file("generated/ksp/main/resources/jetlin-db-schema.json"))
 *     recorded.set(layout.projectDirectory.file("db/schema.json"))
 *     migrations.set(layout.projectDirectory.dir("db/migrations"))
 *     database.set(layout.projectDirectory.file("db/app.db"))
 * }
 * ```
 */
public abstract class JetlinDbExtension {
    /** The schema the entities declare, written by `:jetlin-db-ksp`. */
    public abstract val declared: RegularFileProperty

    /** The schema this repository has recorded, and the thing a migration is generated against. */
    public abstract val recorded: RegularFileProperty

    public abstract val migrations: DirectoryProperty

    /** The database `dbMigrate` applies to. */
    public abstract val database: RegularFileProperty
}

/**
 * Adds `dbDiff`, `dbMigrate` and `dbVerify`.
 *
 * The shape of the workflow, and the reason it is three tasks rather than one:
 *
 * - `./gradlew dbDiff --name=share_todos_by_team` writes an editable migration for whatever the entities
 *   now say that the recorded schema does not, and records the new schema.
 * - `./gradlew dbMigrate` applies pending migrations to the database file.
 * - `./gradlew dbVerify` fails if the entities and the recorded schema disagree. For CI, and the reason
 *   the other two can be trusted: a migration generated against a stale snapshot is worse than none.
 */
public class JetlinDbPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        val extension = target.extensions.create("jetlinDb", JetlinDbExtension::class.java)
        extension.declared.convention(
            target.layout.buildDirectory.file("generated/ksp/main/resources/jetlin-db-schema.json"),
        )
        extension.recorded.convention(target.layout.projectDirectory.file("db/schema.json"))
        extension.migrations.convention(target.layout.projectDirectory.dir("db/migrations"))
        extension.database.convention(target.layout.projectDirectory.file("db/app.db"))

        target.tasks.register("dbDiff", DbDiffTask::class.java) { task ->
            task.group = "jetlin"
            task.description = "Writes a migration for the difference between the entities and db/schema.json"
            task.declared.set(extension.declared)
            task.recorded.set(extension.recorded)
            task.migrations.set(extension.migrations)
            task.dependsOn(target.tasks.matching { it.name == "kspKotlin" })
        }

        target.tasks.register("dbVerify", DbVerifyTask::class.java) { task ->
            task.group = "verification"
            task.description = "Fails if the entities and db/schema.json disagree"
            task.declared.set(extension.declared)
            task.recorded.set(extension.recorded)
            task.dependsOn(target.tasks.matching { it.name == "kspKotlin" })
        }

        target.tasks.register("dbMigrate", DbMigrateTask::class.java) { task ->
            task.group = "jetlin"
            task.description = "Applies pending migrations to the database"
            task.migrations.set(extension.migrations)
            task.database.set(extension.database)
        }

        // The same spirit as the check that `jetlin.js` is not stale: drift between entities and schema is
        // something CI should notice, not something a deploy should discover.
        target.tasks.matching { it.name == "check" }.configureEach { it.dependsOn("dbVerify") }
    }
}

/**
 * Writes a migration, and records the schema it migrates to.
 *
 * Everything but the generated schema is `@Internal`: this task is run on demand, like an `init`, and the
 * files it touches are ones a human then edits and commits. Letting Gradle treat them as tracked outputs
 * would mean stale-output cleanup deciding to delete a migration.
 */
public abstract class DbDiffTask @Inject constructor() : DefaultTask() {
    @get:InputFile
    public abstract val declared: RegularFileProperty

    @get:Internal
    public abstract val recorded: RegularFileProperty

    @get:Internal
    public abstract val migrations: DirectoryProperty

    @get:Input
    @get:Optional
    @get:Option(option = "name", description = "What the migration does, e.g. share_todos_by_team")
    public abstract val migrationName: Property<String>

    @TaskAction
    public fun run() {
        val declaredSchema = SchemaFile.parse(declared.get().asFile.readText())
        val recordedFile = recorded.get().asFile
        val recordedSchema =
            if (recordedFile.exists()) SchemaFile.parse(recordedFile.readText()) else SchemaFile.Empty

        val changes = diff(recordedSchema, declaredSchema)
        if (changes.isEmpty()) {
            logger.lifecycle("jetlin-db: the recorded schema already matches the entities; nothing to do.")
            return
        }

        val directory = migrations.get().asFile
        val store = Migrations(directory)
        val file = store.nextFile(migrationName.orNull ?: changes.first().summary)
        store.write(file, migrationSql(recordedSchema, declaredSchema, changes))

        recordedFile.parentFile?.mkdirs()
        recordedFile.writeText(SchemaFile.write(declaredSchema))

        logger.lifecycle("jetlin-db: wrote ${file.relativeTo(project.projectDir)}")
        changes.forEach { logger.lifecycle("  * ${it.summary}") }
        if (changes.any { it.destructive }) {
            logger.lifecycle(
                "  This migration destroys data. Read it and delete its `$ACKNOWLEDGEMENT_MARKER` line " +
                    "before dbMigrate will run it.",
            )
        }
    }
}

/**
 * Fails when the entities and the recorded schema disagree.
 *
 * Declares no outputs, so it runs every time: a schema that drifted since the last build is exactly what
 * it is for, and "up to date" is the wrong answer to that question.
 */
public abstract class DbVerifyTask @Inject constructor() : DefaultTask() {
    @get:InputFile
    public abstract val declared: RegularFileProperty

    @get:Internal
    public abstract val recorded: RegularFileProperty

    @TaskAction
    public fun run() {
        val declaredSchema = SchemaFile.parse(declared.get().asFile.readText())
        val recordedFile = recorded.get().asFile
        val recordedSchema =
            if (recordedFile.exists()) SchemaFile.parse(recordedFile.readText()) else SchemaFile.Empty

        val changes = diff(recordedSchema, declaredSchema)
        if (changes.isEmpty()) return

        error(
            buildString {
                appendLine("The entities and ${recordedFile.name} disagree:")
                changes.forEach { appendLine("  * ${it.summary}") }
                append("Run `./gradlew dbDiff --name=<what it does>` and commit what it writes.")
            },
        )
    }
}

/**
 * Applies pending migrations.
 *
 * Neither the migrations nor the database is tracked: the database is not Gradle's to own — it is a file a
 * deployment or a developer keeps — and "already applied" is recorded inside it rather than inferred from
 * timestamps.
 */
public abstract class DbMigrateTask @Inject constructor() : DefaultTask() {
    init {
        outputs.upToDateWhen { false }
    }

    @get:Internal
    public abstract val migrations: DirectoryProperty

    @get:Internal
    public abstract val database: RegularFileProperty

    @TaskAction
    public fun run() {
        val store = Migrations(migrations.get().asFile)
        val result = applyMigrations(database.get().asFile, store)
        if (result.applied.isEmpty()) {
            logger.lifecycle("jetlin-db: no pending migrations (${result.alreadyApplied.size} applied).")
        } else {
            result.applied.forEach { logger.lifecycle("jetlin-db: applied $it") }
        }
    }
}
