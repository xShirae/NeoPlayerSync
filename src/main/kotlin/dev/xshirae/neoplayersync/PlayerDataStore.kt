package dev.xshirae.neoplayersync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.sql.Connection
import java.sql.SQLException
import java.sql.Types
import java.util.UUID

/** The bytes of a player's save file and when the game wrote them. */
internal class SavedPlayer(val data: ByteArray, val savedTick: Long, val elapsedMillis: Long)

/** A successful claim. [saved] is null until the player's first save. [extras] holds one entry per stored [PlayerExtra] kind. */
internal class PlayerClaim(val schemaVersion: Int, val saved: SavedPlayer?, val extras: JsonObject = JsonObject())

/** Either [claim] succeeded, or another session holds the player with [heldLease], which is null if none was seen. */
internal class ClaimAttempt(val claim: PlayerClaim?, val heldLease: Long?)

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
     * Claims the player, or reports the holder's lease in the same round trip, so a server with many joins waiting
     * reaches each of them sooner. This takes over an expired lease only when it still equals [staleLease], a lease from
     * an earlier attempt that the caller watched stay unchanged. After a database outage every lease has expired while
     * holders may still have saves waiting, so expiry proves nothing. A session that already holds the player claims it
     * again, so a retry after a claim whose reply was lost gets the row back at once.
     */
    fun tryClaim(playerId: UUID, sessionId: UUID, serverName: String, staleLease: Long? = null): ClaimAttempt = withConnection { connection ->
        connection.prepareStatement(TRY_CLAIM).use { statement ->
            statement.setObject(1, playerId)
            statement.setInt(2, PLAYER_DATA_SCHEMA)
            statement.setObject(3, sessionId)
            statement.setString(4, serverName)
            if (staleLease == null) statement.setNull(5, Types.BIGINT) else statement.setLong(5, staleLease)
            statement.setObject(6, playerId)
            statement.executeQuery().use { row ->
                if (!row.next()) return@withConnection ClaimAttempt(claim = null, heldLease = null)
                val schemaVersion = row.getInt(1)
                if (row.wasNull()) return@withConnection ClaimAttempt(claim = null, heldLease = row.getLong(6).takeUnless { row.wasNull() })
                val data = row.getBytes(2)
                val claim = PlayerClaim(
                    schemaVersion = schemaVersion,
                    saved = data?.let { SavedPlayer(it, row.getLong(3), row.getLong(4)) },
                    // Only `||` of two objects ever writes the column, so it always holds an object.
                    extras = row.getString(5)?.let { JsonParser.parseString(it).asJsonObject } ?: JsonObject(),
                )
                ClaimAttempt(claim, heldLease = null)
            }
        }
    }

    /**
     * Returns false if this session no longer owns the player. [ageMillis] is how long the save waited before this write,
     * so the stored time is when the game saved. [extras] is a JSON object whose entries replace stored ones of the same
     * name. Other stored entries stay, so a server that does not sync a kind leaves it alone.
     */
    fun save(
        playerId: UUID,
        sessionId: UUID,
        data: ByteArray,
        savedTick: Long,
        ageMillis: Long = 0L,
        extras: String = NO_EXTRAS,
    ): Boolean = writeSave(release = false, playerId, sessionId, data, savedTick, ageMillis, extras)

    /** Returns false if this session no longer owns the player. [ageMillis] and [extras] work as in [save]. */
    fun saveAndRelease(
        playerId: UUID,
        sessionId: UUID,
        data: ByteArray,
        savedTick: Long,
        ageMillis: Long = 0L,
        extras: String = NO_EXTRAS,
    ): Boolean = writeSave(release = true, playerId, sessionId, data, savedTick, ageMillis, extras)

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

    /** One round trip, reconnecting first if the last call broke the connection. */
    fun ping() = withConnection { it.execute("SELECT 1") }

    override fun close() {
        connection?.close()
        connection = null
    }

    private fun writeSave(
        release: Boolean,
        playerId: UUID,
        sessionId: UUID,
        data: ByteArray,
        savedTick: Long,
        ageMillis: Long,
        extras: String,
    ): Boolean =
        withConnection { connection ->
            // Assigning the column, even to a merge that adds nothing, makes Postgres compress and write the whole stored value again.
            val mergeExtras = extras != NO_EXTRAS
            val owner = if (release) "$CLEAR_OWNER " else "lease_until = $NEW_LEASE "
            val sql = "UPDATE neoplayersync_player SET $WRITE_SAVE${if (mergeExtras) MERGE_EXTRAS else ""}, $owner$OWNED_BY_SESSION"
            connection.prepareStatement(sql).use { statement ->
                var index = 0
                statement.setBytes(++index, data)
                statement.setLong(++index, savedTick)
                statement.setDouble(++index, ageMillis.coerceAtLeast(0L) / 1_000.0)
                statement.setInt(++index, PLAYER_DATA_SCHEMA)
                if (mergeExtras) statement.setString(++index, extras)
                statement.setObject(++index, playerId)
                statement.setObject(++index, sessionId)
                statement.executeUpdate() == 1
            }
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
        /** Raise this when the meaning of the saved bytes changes, so an older build refuses data it cannot read. */
        const val PLAYER_DATA_SCHEMA = 1

        /** The word "NeoSync" as a number, a key that other programs sharing this database are unlikely to use. */
        private const val SCHEMA_LOCK = 0x4E656F53796E63L

        private val NEW_LEASE = "now() + interval '${LeaseTiming.LEASE.inWholeMilliseconds} milliseconds'"
        const val NO_EXTRAS = "{}"
        private const val WRITE_SAVE = "data = ?, saved_tick = ?, saved_at = now() - make_interval(secs => ?), schema_version = ?"
        private const val MERGE_EXTRAS = ", extra = coalesce(extra, '{}'::jsonb) || ?::jsonb"
        private const val CLEAR_OWNER = "owner_session = NULL, owner_server = NULL, lease_until = NULL"
        private const val OWNED_BY_SESSION = "WHERE player_id = ? AND owner_session = ?"
        private const val LEASE_MICROS = "(extract(epoch FROM neoplayersync_player.lease_until) * 1000000)::bigint"

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
            // The heartbeat looks players up by session. Only held players are indexed, so it stays the size of the online count.
            "CREATE INDEX neoplayersync_player_owner ON neoplayersync_player (owner_session) WHERE owner_session IS NOT NULL",
            // Player data kept outside the save file, one entry per PlayerExtra kind. Older builds never touch it.
            "ALTER TABLE neoplayersync_player ADD COLUMN extra jsonb",
        )

        /**
         * The second branch reads the row as it was before this statement, the same row a separate read just before
         * would have seen. A renewal that lands meanwhile only makes the caller watch an older lease, which then no
         * longer matches, so it can delay a takeover but never allow one.
         */
        private val TRY_CLAIM = """
            WITH claimed AS (
                INSERT INTO neoplayersync_player (player_id, schema_version, owner_session, owner_server, lease_until)
                VALUES (?, ?, ?, ?, $NEW_LEASE)
                ON CONFLICT (player_id) DO UPDATE
                    SET owner_session = EXCLUDED.owner_session, owner_server = EXCLUDED.owner_server, lease_until = EXCLUDED.lease_until
                    WHERE neoplayersync_player.owner_session IS NULL
                        OR neoplayersync_player.owner_session = EXCLUDED.owner_session
                        OR (neoplayersync_player.lease_until < now() AND ${LEASE_MICROS} = ?)
                RETURNING schema_version, data, saved_tick, (extract(epoch FROM now() - saved_at) * 1000)::bigint AS age_millis, extra::text AS extra
            )
            SELECT schema_version, data, saved_tick, age_millis, extra, NULL::bigint FROM claimed
            UNION ALL
            SELECT NULL, NULL, NULL, NULL, NULL, $LEASE_MICROS FROM neoplayersync_player
                WHERE player_id = ? AND owner_session IS NOT NULL AND NOT EXISTS (SELECT 1 FROM claimed)
        """
        private const val RELEASE = "UPDATE neoplayersync_player SET $CLEAR_OWNER $OWNED_BY_SESSION"
        private val HEARTBEAT = "UPDATE neoplayersync_player SET lease_until = $NEW_LEASE WHERE owner_session = ANY(?)"
    }
}
