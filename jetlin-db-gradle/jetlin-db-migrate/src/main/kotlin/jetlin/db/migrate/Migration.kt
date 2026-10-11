package jetlin.db.migrate

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * A marker line that stops a destructive migration from running until someone has read it.
 *
 * The generator adds it to every migration that loses data, and the author confirms the migration by
 * deleting it. That confirmation is an edit to a file, so it shows up in code review, unlike a
 * command-line flag, which leaves no trace.
 */
public const val ACKNOWLEDGEMENT_MARKER: String = "-- jetlin-db:unacknowledged"

/**
 * One migration: a SQL file whose name starts with a number that determines the order migrations run
 * in, such as `0001_initial_schema.sql`.
 *
 * @property name the file name. The history table records it.
 * @property sql the file's SQL, exactly as it runs.
 */
public class Migration(public val name: String, public val sql: String) {

    /** Whether the migration can run, because it doesn't contain [ACKNOWLEDGEMENT_MARKER]. */
    public val acknowledged: Boolean get() = ACKNOWLEDGEMENT_MARKER !in sql

    /**
     * The SHA-256 of [sql], in hex. The history table records it, so a migration edited after it ran
     * is caught.
     *
     * Line endings are normalized first. Git can check the same file out with `\r\n` on one machine
     * and `\n` on another, and that isn't an edit.
     */
    public val sha256: String by lazy { sha256(sql) }

    override fun toString(): String = name
}

/** Returns the SHA-256 of [sql] in hex, with line endings normalized. See [Migration.sha256]. */
internal fun sha256(sql: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(sql.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * Where an application's migrations come from.
 *
 * Applications use [Classpath], which is the default: the `jetlin.db` Gradle plugin packages
 * `db/migrations/` into the JAR, so migrations travel with the entities they belong to, and there's
 * nothing to copy into an image or configure. [Directory] is for tools and tests.
 */
public fun interface MigrationSource {

    /** Returns every migration, in name order. */
    public fun load(): List<Migration>

    /** No migrations. `Db.open` then creates any missing table from the entities, as it always has. */
    public object None : MigrationSource {
        override fun load(): List<Migration> = emptyList()
        override fun toString(): String = "MigrationSource.None"
    }

    /**
     * The migrations the `jetlin.db` plugin packaged under [RESOURCE_DIRECTORY], read through the
     * context class loader.
     *
     * The plugin writes an index next to them, [INDEX], and only the files it lists are read. Listing
     * a directory on the classpath isn't reliable: it works for a directory, needs a `FileSystem`
     * inside a JAR, and fails in some shaded or nested layouts.
     *
     * Without an index there are no migrations. That's the case for a quick prototype, and for
     * `jetlin-db`'s own tests.
     */
    public object Classpath : MigrationSource {
        override fun load(): List<Migration> {
            val loader = Thread.currentThread().contextClassLoader ?: Classpath::class.java.classLoader
            val index = loader.getResource(INDEX) ?: return emptyList()
            return parseIndex(index.readText()).map { (name, sha256) ->
                val resource = loader.getResource("$RESOURCE_DIRECTORY/$name")
                    ?: error("$INDEX lists $name, but there is no $RESOURCE_DIRECTORY/$name on the classpath")
                Migration(name, resource.readText()).also { migration ->
                    check(migration.sha256 == sha256) {
                        "$RESOURCE_DIRECTORY/$name does not match the hash in $INDEX. The packaged " +
                            "migrations are out of date; rebuild the application."
                    }
                }
            }
        }

        override fun toString(): String = "MigrationSource.Classpath"
    }

    /**
     * The `.sql` files in [path], in name order. A directory that doesn't exist has no migrations.
     *
     * @property path the directory, such as `db/migrations`.
     */
    public data class Directory(val path: Path) : MigrationSource {
        override fun load(): List<Migration> {
            if (!path.isDirectory()) return emptyList()
            return path.listDirectoryEntries("*.sql")
                .filter { it.isRegularFile() }
                .sortedBy { it.name }
                .map { Migration(it.name, it.readText()) }
        }
    }

    public companion object {
        /** Where the plugin puts migrations inside the JAR. */
        public const val RESOURCE_DIRECTORY: String = "META-INF/jetlin-db/migrations"

        /**
         * The index of the packaged migrations, in the format `sha256sum` writes: one line per
         * migration, in order, with the hash, two spaces, and the file name.
         */
        public const val INDEX: String = "$RESOURCE_DIRECTORY/index.sha256"

        /** Writes the index for [migrations]. See [INDEX]. */
        public fun writeIndex(migrations: List<Migration>): String =
            migrations.joinToString("") { "${it.sha256}  ${it.name}\n" }
    }
}

/** Parses an index that [MigrationSource.writeIndex] wrote into pairs of name and hash. */
private fun parseIndex(text: String): List<Pair<String, String>> =
    text.lines().filter { it.isNotBlank() }.map { line ->
        val sha256 = line.substringBefore("  ")
        val name = line.substringAfter("  ", missingDelimiterValue = "")
        check(name.isNotEmpty() && sha256.length == 64) {
            "${MigrationSource.INDEX} has a malformed line: '$line'"
        }
        name to sha256
    }
