package atlas.backend.ingest

import atlas.backend.db.Database
import java.io.File

/** Repo-relative path to the bundled sample, used when no arg/env path is given. */
private const val DEFAULT_CSV = "ingest-opencellid/sample-bengaluru.csv"

/**
 * Standalone runner for the OpenCelliD importer.
 *
 * CSV path resolution (first match wins): the first CLI arg, then the
 * `OPENCELLID_CSV` env var, then the bundled [DEFAULT_CSV]. A relative path that
 * doesn't resolve against the working dir is also searched for by walking up the
 * directory tree, so the task works whether Gradle runs it from the repo root or
 * the `backend/` module dir.
 * Database connection comes from the same env vars / defaults as the app
 * ([atlas.backend.Application]): `JDBC_URL`, `DB_USER`, `DB_PASSWORD`.
 *
 * Run with: `./gradlew :backend:importOpenCelliD`
 */
fun main(args: Array<String>) {
    val csvPath = args.firstOrNull()
        ?: System.getenv("OPENCELLID_CSV")
        ?: DEFAULT_CSV

    val file = resolveCsv(csvPath)
    if (file == null || !file.isFile) {
        System.err.println("netatlas import: CSV not found for '$csvPath' (cwd: ${File(".").absolutePath})")
        error("OpenCelliD CSV not found: $csvPath")
    }

    val db = Database.connect(
        jdbcUrl = System.getenv("JDBC_URL") ?: "jdbc:postgresql://localhost:5432/netatlas",
        user = System.getenv("DB_USER") ?: "netatlas",
        password = System.getenv("DB_PASSWORD") ?: "netatlas",
    )
    db.applyMigrations()

    println("netatlas import: loading towers from ${file.absolutePath}")
    val imported = TowerImporter(db).importFrom(file)
    println("netatlas import: DONE. imported $imported tower(s) into cell_towers")
}

/**
 * Resolves [csvPath] to a file. Absolute paths and paths that exist relative to the
 * working dir are returned as-is; otherwise the relative path is searched for by
 * walking up parent directories (so it resolves from either the repo root or the
 * `backend/` module dir). Returns null if nothing is found.
 */
private fun resolveCsv(csvPath: String): File? {
    val direct = File(csvPath)
    if (direct.isFile) return direct
    if (direct.isAbsolute) return null

    var current: File? = File(".").absoluteFile.normalize()
    while (current != null) {
        val candidate = File(current, csvPath)
        if (candidate.isFile) return candidate
        current = current.parentFile
    }
    return null
}
