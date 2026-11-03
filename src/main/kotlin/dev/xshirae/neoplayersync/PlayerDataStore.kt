package dev.xshirae.neoplayersync

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Types
import java.util.UUID

/** The bytes of a player's save file and when they were written. */
internal class SavedPlayer(val data: ByteArray, val savedTick: Long, val elapsedMillis: Long)

/** A successful claim. [saved] is null until the player's first save. */
internal class PlayerClaim(val schemaVersion: Int, val saved: SavedPlayer?)

/**
 * All SQL for player data. It holds one connection and reconnects after a failure, so each instance belongs to one thread.
 * A session ID proves ownership: a server that lost its claim changes nothing, whatever its lease says.
 */
internal class PlayerDataStore(private val connect: () -> Connection) : AutoCloseable {
    private var connection: Connection? = null

    /** Creates or upgrades the tables. The lock makes servers that start together take turns. */
    fun prepareTables() = withConnection { connection ->
        connection.autoCommit = false
        try {
            connection.execute("SELECT pg_advisory_xact_lock($SCHEMA_LOCK)")
            connection.execute("CREATE TABLE IF NOT EXISTS neoplayersync_schema (version integer NOT NULL)")
            val stored = connection.prepareStatement("SELECT version FROM neoplayersync_schema").use { statement ->
                statement.executeQuery().use { row -> if (row.next()) row.getInt(1) else null }
            }
            val current = stored ?: 0
            check(current <= SCHEMA_STEPS.size) {
                "The database uses NeoPlayerSync table layout $current, but this build only knows ${SCHEMA_STEPS.size}. Update NeoPlayerSync on this server."
            }
            SCHEMA_STEPS.drop(current).forEach { connection.execute(it) }
            connection.prepareStatement(
                if (stored == null) "INSERT INTO neoplayersync_schema (version) VALUES (?)" else "UPDATE neoplayersync_schema SET version = ?",
            ).use { statement ->
                statement.setInt(1, SCHEMA_STEPS.size)
                statement.executeUpdate()
            }
            connection.commit()
        } catch (exception: Exception) {
            // On a dead connection these fail too, and must not hide the original error.
            runCatching { connection.rollback() }
            throw exception
        } finally {
            runCatching { connection.autoCommit = true }
        }
    }

    /**
     * Returns null while another session holds this player. An expired lease is only taken over when it still equals
     * [staleLease], a value from [leaseOf] that the caller has watched stay unchanged. Expiry alone is not enough: after
     * a database outage every lease has expired, and the holder may still have a save waiting to be sent.
     */
    fun claim(playerId: UUID, sessionId: UUID, serverName: String, staleLease: Long? = null): PlayerClaim? = withConnection { connection ->
        connection.prepareStatement(CLAIM).use { statement ->
            statement.setObject(1, playerId)
            statement.setInt(2, PLAYER_DATA_SCHEMA)
            statement.setObject(3, sessionId)
            statement.setString(4, serverName)
            if (staleLease == null) statement.setNull(5, Types.BIGINT) else statement.setLong(5, staleLease)
            statement.executeQuery().use { row ->
                if (!row.next()) return@withConnection null
                val data = row.getBytes(2)
                PlayerClaim(
                    schemaVersion = row.getInt(1),
                    saved = data?.let { SavedPlayer(it, row.getLong(3), row.getLong(4)) },
                )
            }
        }
    }

    /** The holder's lease as an exact number that changes on every renewal, or null when nobody holds the player. */
    fun leaseOf(playerId: UUID): Long? = withConnection { connection ->
        connection.prepareStatement(LEASE_OF).use { statement ->
            statement.setObject(1, playerId)
            statement.executeQuery().use { row -> if (row.next()) row.getLong(1).takeUnless { row.wasNull() } else null }
        }
    }

    /**
     * Returns false if this session no longer owns the player. [ageMillis] is how long the save waited before this write,
     * so the stored time is when the game saved, not when the database was reachable again.
     */
    fun save(playerId: UUID, sessionId: UUID, data: ByteArray, savedTick: Long, ageMillis: Long = 0L): Boolean =
        writeSave(SAVE, playerId, sessionId, data, savedTick, ageMillis)

    /** Returns false if this session no longer owns the player. [ageMillis] works as in [save]. */
    fun saveAndRelease(playerId: UUID, sessionId: UUID, data: ByteArray, savedTick: Long, ageMillis: Long = 0L): Boolean =
        writeSave(SAVE_AND_RELEASE, playerId, sessionId, data, savedTick, ageMillis)

    /** Gives up the claim and keeps the stored data, for a player who never reached the world. */
    fun release(playerId: UUID, sessionId: UUID): Boolean = withConnection { connection ->
        connection.prepareStatement(RELEASE).use { statement ->
            statement.setObject(1, playerId)
            statement.setObject(2, sessionId)
            statement.executeUpdate() == 1
        }
    }

    fun heartbeat(sessionIds: Collection<UUID>) {
        if (sessionIds.isEmpty()) return
        withConnection { connection ->
            connection.prepareStatement(HEARTBEAT).use { statement ->
                statement.setArray(1, connection.createArrayOf("uuid", sessionIds.toTypedArray()))
                statement.executeUpdate()
            }
        }
    }

    override fun close() {
        connection?.close()
        connection = null
    }

    private fun writeSave(sql: String, playerId: UUID, sessionId: UUID, data: ByteArray, savedTick: Long, ageMillis: Long): Boolean =
        withConnection { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.bindSave(data, savedTick, ageMillis)
                statement.setObject(5, playerId)
                statement.setObject(6, sessionId)
                statement.executeUpdate() == 1
            }
        }

    private fun PreparedStatement.bindSave(data: ByteArray, savedTick: Long, ageMillis: Long) {
        setBytes(1, data)
        setLong(2, savedTick)
        setDouble(3, ageMillis.coerceAtLeast(0L) / 1_000.0)
        setInt(4, PLAYER_DATA_SCHEMA)
    }

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private inline fun <T> withConnection(block: (Connection) -> T): T {
        val current = connection ?: connect().also { connection = it }
        try {
            return block(current)
        } catch (exception: SQLException) {
            // The connection may be broken, and the next call opens a new one.
            connection = null
            runCatching { current.close() }
            throw exception
        }
    }

    companion object {
        /** Raised when the meaning of the saved bytes changes, so an older build refuses data it cannot read. */
        const val PLAYER_DATA_SCHEMA = 1

        /** The word "NeoSync" as a number, a key that other programs sharing this database are unlikely to use. */
        private const val SCHEMA_LOCK = 0x4E656F53796E63L

        private const val NEW_LEASE = "now() + interval '15 seconds'"
        private const val WRITE_SAVE = "data = ?, saved_tick = ?, saved_at = now() - make_interval(secs => ?), schema_version = ?"
        private const val OWNED_BY_SESSION = "WHERE player_id = ? AND owner_session = ?"
        private const val LEASE_MICROS = "(extract(epoch FROM neoplayersync_player.lease_until) * 1000000)::bigint"
        private const val LEASE_OF = "SELECT $LEASE_MICROS FROM neoplayersync_player WHERE player_id = ? AND owner_session IS NOT NULL"

        /** Each entry upgrades the layout by one version. Add new entries at the end and never edit old ones. */
        private val SCHEMA_STEPS = listOf(
            """
            CREATE TABLE neoplayersync_player (
                player_id      uuid PRIMARY KEY,
                schema_version integer NOT NULL,
                data           bytea,
                saved_tick     bigint,
                saved_at       timestamptz,
                owner_session  uuid,
                owner_server   text,
                lease_until    timestamptz
            )
            """.trimIndent(),
        )

        private const val CLAIM = """
            INSERT INTO neoplayersync_player (player_id, schema_version, owner_session, owner_server, lease_until)
            VALUES (?, ?, ?, ?, $NEW_LEASE)
            ON CONFLICT (player_id) DO UPDATE
                SET owner_session = EXCLUDED.owner_session, owner_server = EXCLUDED.owner_server, lease_until = EXCLUDED.lease_until
                WHERE neoplayersync_player.owner_session IS NULL
                    OR (neoplayersync_player.lease_until < now() AND ${LEASE_MICROS} = ?)
            RETURNING schema_version, data, saved_tick, (extract(epoch FROM now() - saved_at) * 1000)::bigint
        """
        private const val SAVE = "UPDATE neoplayersync_player SET $WRITE_SAVE, lease_until = $NEW_LEASE $OWNED_BY_SESSION"
        private const val SAVE_AND_RELEASE =
            "UPDATE neoplayersync_player SET $WRITE_SAVE, owner_session = NULL, owner_server = NULL, lease_until = NULL $OWNED_BY_SESSION"
        private const val RELEASE =
            "UPDATE neoplayersync_player SET owner_session = NULL, owner_server = NULL, lease_until = NULL $OWNED_BY_SESSION"
        private const val HEARTBEAT = "UPDATE neoplayersync_player SET lease_until = $NEW_LEASE WHERE owner_session = ANY(?)"
    }
}
