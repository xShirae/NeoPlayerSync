package dev.xshirae.neoplayersync

import net.neoforged.neoforge.common.ModConfigSpec
import java.sql.Connection
import java.sql.Driver
import java.util.Properties

/** [NeoPlayerSync] registers this as a LOCAL config, because it holds the database password. */
internal object DatabaseConfig {
    private val builder = ModConfigSpec.Builder()

    private val enabled = builder
        .comment("Store players in Postgres so they can move between servers. false uses local files and ignores the settings below.")
        .define("enabled", false)
    private val host = builder
        .comment("Address of the Postgres server.")
        .define("host", "127.0.0.1")
    private val port = builder
        .comment("Port of the Postgres server.")
        .defineInRange("port", 5432, 1, 65_535)
    private val database = builder
        .comment("An existing database. NeoPlayerSync creates and upgrades its own tables in it.")
        .define("database", "minecraft")
    private val user = builder
        .comment("A user who can create tables in that database.")
        .define("user", "minecraft")
    private val password = builder
        .comment("That user's password. It is stored in plain text, so restrict this file and keep it out of shared modpacks.")
        .define("password", "")
    private val requireSsl = builder
        .comment("Refuse a connection that is not encrypted. Turn this off only if the database is on this machine.")
        .define("require_ssl", true)
    private val serverName = builder
        .comment("Name shown in the database as the player's current server. Blank uses this server's port.")
        .define("server_name", "")

    val SPEC: ModConfigSpec = builder.build()

    fun enabled(): Boolean = enabled.get()

    fun settings(): DatabaseSettings = DatabaseSettings.validated(
        host = host.get(),
        port = port.get(),
        database = database.get(),
        user = user.get(),
        password = password.get(),
        requireSsl = requireSsl.get(),
        serverName = serverName.get(),
    )
}

/** A plain class, so no generated toString can write the password into a log. */
internal class DatabaseSettings private constructor(
    private val host: String,
    private val port: Int,
    private val database: String,
    private val user: String,
    private val password: String,
    private val requireSsl: Boolean,
    val serverName: String,
) {
    fun connect(): Connection {
        val properties = Properties().apply {
            setProperty("user", user)
            setProperty("password", password)
            setProperty("sslmode", if (requireSsl) "require" else "prefer")
            setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS)
            setProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS)
            setProperty("ApplicationName", "NeoPlayerSync")
        }
        // The interface type avoids org.postgresql.Driver's signature, which names an annotation missing from the compile path.
        val driver: Driver = org.postgresql.Driver()
        return checkNotNull(driver.connect("jdbc:postgresql://$host:$port/$database", properties)) {
            "The Postgres driver rejected the connection URL."
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = "5"
        private const val SOCKET_TIMEOUT_SECONDS = "15"
        private val URL_SAFE = Regex("[A-Za-z0-9_.:\\[\\]-]+")

        fun validated(
            host: String,
            port: Int,
            database: String,
            user: String,
            password: String,
            requireSsl: Boolean,
            serverName: String,
        ): DatabaseSettings {
            val problems = buildList {
                if (!URL_SAFE.matches(host)) add("host must be an address, such as 127.0.0.1")
                if (port !in 1..65_535) add("port must be between 1 and 65535")
                if (!URL_SAFE.matches(database)) add("database must be a plain database name")
                if (user.isBlank()) add("user is required")
                if (password.isBlank()) add("password is required")
            }
            require(problems.isEmpty()) { "Invalid ${NeoPlayerSync.CONFIG_FILE}: ${problems.joinToString("; ")}" }
            return DatabaseSettings(host, port, database, user, password, requireSsl, serverName.trim())
        }
    }
}
