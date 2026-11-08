package dev.xshirae.neoplayersync

import com.electronwill.nightconfig.core.CommentedConfig
import com.electronwill.nightconfig.core.io.ParsingException
import com.electronwill.nightconfig.toml.TomlParser
import net.neoforged.neoforge.common.ModConfigSpec
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.sql.Connection
import java.sql.Driver
import java.util.Properties
import kotlin.math.abs

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
    private val sslRootCert = builder
        .comment("Path to the certificate of the authority that signed the database's certificate. Set it to make sure this server talks to your real database. Blank encrypts without that check. Needs require_ssl = true. On Windows, use forward slashes, like C:/certs/ca.crt.")
        .define("ssl_root_cert", "")
    private val serverName = builder
        .comment("Name shown in the database as the player's current server. Blank uses this server's port.")
        .define("server_name", "")
    private val syncAdvancements = builder
        .comment(
            "Move advancements with the player. Use the same value on every server: a server set to false keeps its own " +
                "copy and leaves the stored one alone. When you turn this on, the first server to save a player decides " +
                "their advancements, replacing what other servers kept on their own. Needs a restart.",
        )
        .define("sync_advancements", false)
    private val syncStatistics = builder
        .comment("Move statistics with the player, such as blocks mined and time played. Works like sync_advancements.")
        .define("sync_statistics", false)

    val SPEC: ModConfigSpec = builder.build()

    /** Why the server must not start with the operator's file, or null. */
    var startupProblem: String? = null

    /**
     * Checks [file] the way NeoForge reads it. Call it before NeoForge loads the file, which then still holds the
     * operator's text. NeoForge replaces a file that does not parse with defaults, which turn sync off. Its file watcher
     * does the same while the server runs, so a later start finds defaults next to a broken backup written with them.
     */
    fun fileProblem(file: Path): String? {
        if (!Files.exists(file)) return null
        val name = file.fileName.toString()
        val parsed = parse(file).getOrElse { e ->
            return unparsable(name, if (e is ParsingException) e.message ?: e.toString() else e.toString())
        }
        if (parsed.get<Any?>("enabled") == true) return null
        val backup = brokenBackupWrittenWith(file) ?: return null
        return "NeoForge replaced config/$name with defaults, which turn sync off, because it was not valid TOML. " +
            "Your file is saved as config/$backup. Fix that file, copy it back to config/$name and start the server " +
            "again. If you meant to turn sync off, delete config/$backup."
    }

    private fun parse(file: Path): Result<CommentedConfig> = try {
        Result.success(Files.newBufferedReader(file).use { TomlParser().parse(it) })
    } catch (e: IOException) {
        Result.failure(e)
    } catch (e: ParsingException) {
        Result.failure(e)
    }

    private fun unparsable(name: String, reason: String) =
        "config/$name is not valid TOML ($reason). NeoForge replaced it with defaults, which turn sync off, and saved " +
            "your file as config/${name.removeSuffix(".toml")}-1.toml.bak. Fix that file, copy it back to config/$name " +
            "and start the server again."

    /**
     * The name of a NeoForge backup of [file] saved within seconds of it that does not parse, which marks [file] as
     * NeoForge's defaults. NeoForge also backs up a valid file when it only adds a missing key; that backup parses.
     */
    private fun brokenBackupWrittenWith(file: Path): String? {
        val written = Files.getLastModifiedTime(file).toMillis()
        val pattern = Regex(Regex.escape(file.fileName.toString().removeSuffix(".toml")) + """-\d+\.toml\.bak""")
        return Files.list(file.parent).use { siblings ->
            siblings.filter { pattern.matches(it.fileName.toString()) }
                .filter { abs(Files.getLastModifiedTime(it).toMillis() - written) <= REPLACED_WITHIN_MILLIS }
                .filter { parse(it).isFailure }
                .findFirst()
                .map { it.fileName.toString() }
                .orElse(null)
        }
    }

    // NeoForge writes the backup and then the defaults; on the test network they landed one second apart.
    private const val REPLACED_WITHIN_MILLIS = 10_000L

    fun enabled(): Boolean = enabled.get()

    fun syncAdvancements(): Boolean = syncAdvancements.get()

    fun syncStatistics(): Boolean = syncStatistics.get()

    fun settings(): DatabaseSettings = DatabaseSettings.validated(
        host = host.get(),
        port = port.get(),
        database = database.get(),
        user = user.get(),
        password = password.get(),
        requireSsl = requireSsl.get(),
        sslRootCert = sslRootCert.get(),
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
    /** Null encrypts without checking the server's certificate. */
    private val sslRootCert: Path?,
    val serverName: String,
) {
    fun connect(): Connection {
        // The interface type avoids org.postgresql.Driver's signature, which names an annotation missing from the compile path.
        val driver: Driver = org.postgresql.Driver()
        return checkNotNull(driver.connect("jdbc:postgresql://$host:$port/$database", connectionProperties())) {
            "The Postgres driver rejected the connection URL."
        }
    }

    internal fun connectionProperties(): Properties = Properties().apply {
        setProperty("user", user)
        setProperty("password", password)
        setProperty(
            "sslmode",
            when {
                // verify-full also checks that the certificate names this host, so another trusted server cannot stand in.
                sslRootCert != null -> "verify-full"
                requireSsl -> "require"
                else -> "prefer"
            },
        )
        sslRootCert?.let { setProperty("sslrootcert", it.toString()) }
        setProperty("connectTimeout", CONNECT_TIMEOUT_SECONDS)
        setProperty("socketTimeout", SOCKET_TIMEOUT_SECONDS)
        setProperty("ApplicationName", "NeoPlayerSync")
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
            sslRootCert: String = "",
        ): DatabaseSettings {
            val certPath = sslRootCert.trim().takeIf { it.isNotEmpty() }
            val cert = certPath?.let {
                try {
                    Path.of(it)
                } catch (_: InvalidPathException) {
                    null
                }
            }
            val problems = buildList {
                if (!URL_SAFE.matches(host)) add("host must be an address, such as 127.0.0.1")
                if (port !in 1..65_535) add("port must be between 1 and 65535")
                if (!URL_SAFE.matches(database)) add("database must be a plain database name")
                if (user.isBlank()) add("user is required")
                if (password.isBlank()) add("password is required")
                if (certPath != null && (cert == null || !Files.isRegularFile(cert))) add("ssl_root_cert must be the path of a file: $certPath")
                if (certPath != null && !requireSsl) add("ssl_root_cert needs require_ssl = true")
            }
            require(problems.isEmpty()) { "Invalid ${NeoPlayerSync.CONFIG_FILE}: ${problems.joinToString("; ")}" }
            return DatabaseSettings(host, port, database, user, password, requireSsl, cert, serverName.trim())
        }
    }
}
