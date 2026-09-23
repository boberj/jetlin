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
 * Configures where the plugin finds an application's schema files, migrations, and database.
 *
 * ```kotlin
 * jetlinDb {
 *     // These are the defaults. KSP generates the declared schema, and the recorded one is checked in.
 *     declared.set(layout.buildDirectory.file("generated/ksp/main/resources/jetlin-db-schema.json"))
 *     recorded.set(layout.projectDirectory.file("db/schema.json"))
 *     migrations.set(layout.projectDirectory.dir("db/migrations"))
 *     database.set(layout.projectDirectory.file("db/app.db"))
 * }
 * ```
 */
public abstract class JetlinDbExtension {
    /** The schema that the entities declare. `:jetlin-db-ksp` generates it. */
    public abstract val declared: RegularFileProperty

    /** The schema snapshot checked into the repository. New migrations are generated relative to it. */
    public abstract val recorded: RegularFileProperty

    /** The directory of migration files, which `dbDiff` writes and `dbMigrate` applies. */
    public abstract val migrations: DirectoryProperty

    /** The database file that `dbMigrate` applies migrations to. */
    public abstract val database: RegularFileProperty
}

/**
 * Adds the `dbDiff`, `dbMigrate`, and `dbVerify` tasks.
 *
 * Each task covers one step of the workflow:
 *
 * - `./gradlew dbDiff --name=share_todos_by_team` writes an editable migration for the differences
 *   between the entities and the recorded schema, then updates the recorded schema.
 * - `./gradlew dbMigrate` applies pending migrations to the database file.
 * - `./gradlew dbVerify` fails if the entities and the recorded schema differ. It's meant for CI,
 *   and it's what makes the other two reliable: a migration generated against an outdated snapshot
 *   is worse than no migration.
 *
 * The plugin also makes `check` depend on `dbVerify`.
 */
public class JetlinDbPlugin : Plugin<Project> {
    /** Registers the `jetlinDb` extension and the tasks. */
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

        // Like the check that jetlin.js is up to date: a mismatch between the entities and the schema
        // should fail CI instead of being discovered during a deployment.
        target.tasks.matching { it.name == "check" }.configureEach { it.dependsOn("dbVerify") }
    }
}

/**
 * Writes a migration and updates the recorded schema to match.
 *
 * Everything except the declared schema is marked `@Internal`. Someone runs the task by hand, then
 * edits and commits the files it writes. If Gradle tracked them as outputs, its stale-output cleanup
 * could delete a migration.
 */
public abstract class DbDiffTask @Inject constructor() : DefaultTask() {
    /** The schema that the entities declare. */
    @get:InputFile
    public abstract val declared: RegularFileProperty

    /** The recorded schema, which this task compares with and then overwrites. */
    @get:Internal
    public abstract val recorded: RegularFileProperty

    /** The directory to write the migration to. */
    @get:Internal
    public abstract val migrations: DirectoryProperty

    /**
     * What the migration does, in snake case, for its file name. It defaults to a summary of the
     * first change.
     */
    @get:Input
    @get:Optional
    @get:Option(option = "name", description = "What the migration does, for example share_todos_by_team")
    public abstract val migrationName: Property<String>

    /** Writes the migration and the new recorded schema, or does nothing if they already match. */
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
 * Fails if the entities and the recorded schema differ.
 *
 * The task declares no outputs, so Gradle never skips it as up to date. Its whole purpose is to
 * detect a schema that changed since the last build.
 */
public abstract class DbVerifyTask @Inject constructor() : DefaultTask() {
    /** The schema that the entities declare. */
    @get:InputFile
    public abstract val declared: RegularFileProperty

    /** The recorded schema. */
    @get:Internal
    public abstract val recorded: RegularFileProperty

    /** Throws an error that lists the differences, if there are any. */
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
 * Gradle tracks neither the migrations nor the database. The database belongs to a deployment or a
 * developer, not to the build, and the database itself records which migrations have been applied,
 * instead of that being inferred from file timestamps.
 */
public abstract class DbMigrateTask @Inject constructor() : DefaultTask() {
    init {
        outputs.upToDateWhen { false }
    }

    /** The directory of migration files. */
    @get:Internal
    public abstract val migrations: DirectoryProperty

    /** The database file to migrate. It's created if it doesn't exist. */
    @get:Internal
    public abstract val database: RegularFileProperty

    /** Applies every pending migration, and logs what it applied. */
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
