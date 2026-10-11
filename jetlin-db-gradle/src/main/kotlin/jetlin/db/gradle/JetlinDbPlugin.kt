package jetlin.db.gradle

import javax.inject.Inject
import jetlin.db.migrate.ACKNOWLEDGEMENT_MARKER
import jetlin.db.migrate.MigrationSource
import jetlin.db.migrate.migrate
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SourceSetContainer
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

    /**
     * The directory of migration files, which `dbDiff` writes, `dbMigrate` applies, and the build
     * packages into the JAR for `Db.open` to apply.
     */
    public abstract val migrations: DirectoryProperty

    /** The database file that `dbMigrate` applies migrations to. */
    public abstract val database: RegularFileProperty
}

/**
 * Adds the `dbDiff`, `dbMigrate`, `dbVerify`, and `dbPackageMigrations` tasks.
 *
 * Each task covers one step of the workflow:
 *
 * - `./gradlew dbDiff --name=share_todos_by_team` writes an editable migration for the differences
 *   between the entities and the recorded schema, then updates the recorded schema.
 * - `./gradlew dbMigrate` applies pending migrations to the database file. An application doesn't
 *   need it, because `Db.open` applies the packaged migrations when it starts. It's for development,
 *   and for operators who open the database with `MigrationMode.Verify` and migrate as a separate step.
 * - `./gradlew dbVerify` fails if the entities and the recorded schema differ, or if replaying the
 *   migrations doesn't produce the schema the entities declare. It's meant for CI, and it's what makes
 *   the others reliable: a migration generated against an outdated snapshot, or edited into something
 *   else, is worse than no migration.
 * - `dbPackageMigrations` copies the migrations into the main resources, under
 *   `META-INF/jetlin-db/migrations/`, with an index. `processResources` runs it, so nobody has to.
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
            task.migrations.set(extension.migrations)
            task.dependsOn(target.tasks.matching { it.name == "kspKotlin" })
        }

        target.tasks.register("dbMigrate", DbMigrateTask::class.java) { task ->
            task.group = "jetlin"
            task.description = "Applies pending migrations to the database"
            task.migrations.set(extension.migrations)
            task.database.set(extension.database)
        }

        val packageMigrations = target.tasks.register("dbPackageMigrations", DbPackageMigrationsTask::class.java) { task ->
            task.group = "jetlin"
            task.description = "Copies db/migrations into the main resources, with an index, for Db.open"
            task.migrations.set(extension.migrations)
            task.output.set(target.layout.buildDirectory.dir("generated/jetlin-db/resources"))
        }
        // Adding the task's output as a resource directory makes `processResources` depend on the task.
        target.plugins.withId("java") {
            target.extensions.getByType(SourceSetContainer::class.java).named("main") { main ->
                main.resources.srcDir(packageMigrations.flatMap { it.output })
            }
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
                    "before it will run.",
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

    /** The directory of migration files, which are replayed. */
    @get:Internal
    public abstract val migrations: DirectoryProperty

    /** Throws an error that lists the differences, if there are any. */
    @TaskAction
    public fun run() {
        val declaredSchema = SchemaFile.parse(declared.get().asFile.readText())
        val recordedFile = recorded.get().asFile
        val recordedSchema =
            if (recordedFile.exists()) SchemaFile.parse(recordedFile.readText()) else SchemaFile.Empty

        val changes = diff(recordedSchema, declaredSchema)
        if (changes.isNotEmpty()) {
            error(
                buildString {
                    appendLine("The entities and ${recordedFile.name} disagree:")
                    changes.forEach { appendLine("  * ${it.summary}") }
                    append("Run `./gradlew dbDiff --name=<what it does>` and commit what it writes.")
                },
            )
        }

        // The snapshot matches, so a difference here means a migration doesn't do what the snapshot
        // says it does, most likely because it was edited by hand.
        val directory = migrations.get().asFile
        val problems = replayProblems(declaredSchema, Migrations(directory).all())
        if (problems.isNotEmpty()) {
            error(
                buildString {
                    appendLine("Replaying ${directory.relativeTo(project.projectDir)} doesn't produce the schema the entities declare:")
                    problems.forEach { appendLine("  * $it") }
                    append(
                        "Fix the migration that changed it. If it has already been applied somewhere, add a " +
                            "new migration instead.",
                    )
                },
            )
        }
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

    /** Applies every pending migration, and logs what it did. */
    @TaskAction
    public fun run() {
        val source = MigrationSource.Directory(migrations.get().asFile.toPath())
        val result = migrate(database.get().asFile.toPath(), source.load()) { event ->
            logger.lifecycle("jetlin-db: ${event.message}")
        }
        if (result.applied.isEmpty()) {
            logger.lifecycle("jetlin-db: no pending migrations (${result.alreadyApplied.size} applied).")
        }
    }
}

/**
 * Copies the migrations into a resource directory, under [MigrationSource.RESOURCE_DIRECTORY], and
 * writes [MigrationSource.INDEX] next to them.
 *
 * That's how migrations reach a deployed application: inside its JAR, like the entities they belong
 * to, where `Db.open` finds them with `MigrationSource.Classpath`. With no migrations, it writes
 * nothing, and `Db.open` creates tables from the entities instead.
 */
public abstract class DbPackageMigrationsTask @Inject constructor() : DefaultTask() {
    /** The directory of migration files. It may not exist. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    public abstract val migrations: DirectoryProperty

    /** The resource directory to write to. */
    @get:OutputDirectory
    public abstract val output: DirectoryProperty

    /** Writes the migrations and the index. */
    @TaskAction
    public fun run() {
        val root = output.get().asFile
        root.deleteRecursively()
        val all = Migrations(migrations.get().asFile).all()
        if (all.isEmpty()) return

        val directory = root.resolve(MigrationSource.RESOURCE_DIRECTORY)
        directory.mkdirs()
        all.forEach { directory.resolve(it.name).writeText(it.sql) }
        root.resolve(MigrationSource.INDEX).writeText(MigrationSource.writeIndex(all))
    }
}
