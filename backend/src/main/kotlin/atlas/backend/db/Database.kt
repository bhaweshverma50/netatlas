package atlas.backend.db

import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import javax.sql.DataSource
import org.jetbrains.exposed.sql.Database as ExposedDatabase

/**
 * Thin wrapper over an Exposed [ExposedDatabase] connection.
 *
 * Owns connection setup, schema migration, and a [transaction] entry point so
 * repositories don't talk to Exposed's global state directly.
 */
class Database private constructor(private val database: ExposedDatabase) {

    companion object {
        /** Connect via a plain JDBC url/user/password. */
        fun connect(jdbcUrl: String, user: String, password: String): Database =
            Database(ExposedDatabase.connect(url = jdbcUrl, driver = "org.postgresql.Driver", user = user, password = password))

        /** Connect via a pre-built [DataSource] (e.g. a pool). */
        fun connect(dataSource: DataSource): Database =
            Database(ExposedDatabase.connect(dataSource))
    }

    /** Run [block] inside an Exposed transaction bound to this database. */
    fun <T> transaction(block: Transaction.() -> T): T = transaction(database, block)

    /**
     * Applies every `V*.sql` migration in version order against the connected
     * database. Migrations are idempotent (they use `IF NOT EXISTS`), so calling
     * this on an already-migrated database is safe.
     *
     * The migrations directory is located by walking up from the working
     * directory looking for `backend/db/migrations` (or `db/migrations` when the
     * working dir already is the `backend/` module). This makes it resolve
     * whether tests run from the repo root or the module dir.
     */
    fun applyMigrations() {
        val dir = findMigrationsDir()
        val files = dir.listFiles { f -> f.isFile && f.name.matches(Regex("""V\d+__.*\.sql""")) }
            ?.sortedBy { versionOf(it.name) }
            ?: error("No migration files found in ${dir.absolutePath}")

        transaction {
            val conn = (this.connection.connection as java.sql.Connection)
            for (file in files) {
                conn.createStatement().use { stmt ->
                    stmt.execute(file.readText())
                }
            }
        }
    }

    /** Extracts the leading integer version from a `V<n>__name.sql` filename. */
    private fun versionOf(name: String): Int =
        Regex("""V(\d+)__""").find(name)?.groupValues?.get(1)?.toInt()
            ?: error("Migration file '$name' has no version prefix")

    /**
     * Walks up parent directories from the current working directory looking for
     * the migrations folder at either `backend/db/migrations` or `db/migrations`.
     */
    private fun findMigrationsDir(): File {
        var current: File? = File(".").absoluteFile.normalize()
        while (current != null) {
            for (rel in listOf("backend/db/migrations", "db/migrations")) {
                val candidate = File(current, rel)
                if (candidate.isDirectory) return candidate
            }
            current = current.parentFile
        }
        error("Could not locate migrations directory (looked for backend/db/migrations or db/migrations walking up from ${File(".").absolutePath})")
    }
}
