package dev.xshirae.neoplayersync

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.sql.Connection
import java.sql.SQLException

/**
 * tools/test-postgres.ps1's database, inside a schema of its own, so tests never touch a real database's tables.
 * Tests using it skip when that database is not running, so check the report before claiming they ran.
 */
internal class PlayerDataTestDatabase(val schema: String) : AutoCloseable {
    private val settings = DatabaseSettings.validated(
        host = "127.0.0.1",
        port = 5433,
        database = "neoplayersync",
        user = "neoplayersync",
        password = "neoplayersync",
        requireSsl = false,
        serverName = "",
    )
    private val opened = mutableListOf<PlayerDataStore>()
    private val admin: Connection? = try {
        settings.connect()
    } catch (exception: SQLException) {
        assumeTrue(false, "Test Postgres is not running: ${exception.message}")
        null
    }

    init {
        sql("DROP SCHEMA IF EXISTS $schema CASCADE")
        sql("CREATE SCHEMA $schema")
    }

    fun newConnectionForTest(): Connection = settings.connect().also { it.schema = schema }

    fun newStore(): PlayerDataStore = PlayerDataStore(::newConnectionForTest).also { opened += it }

    fun sql(statement: String) {
        checkNotNull(admin).createStatement().use { it.execute(statement) }
    }

    fun intQuery(statement: String): Int = checkNotNull(admin).createStatement().use { query ->
        query.executeQuery(statement).use { row ->
            row.next()
            row.getInt(1)
        }
    }

    override fun close() {
        opened.forEach(PlayerDataStore::close)
        sql("DROP SCHEMA IF EXISTS $schema CASCADE")
        admin?.close()
    }
}
