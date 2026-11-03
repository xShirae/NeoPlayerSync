package dev.xshirae.neoplayersync

import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** One player's next database write. No [data] means the claim is given up without saving. */
internal class PlayerWrite(
    val playerId: UUID,
    val sessionId: UUID,
    val data: ByteArray?,
    val savedTick: Long,
    val release: Boolean,
    /** When the game saved [data], on this server's own clock, so time spent waiting for the database still counts. */
    val savedAtNanos: Long = System.nanoTime(),
) {
    /** A newer save replaces an unsent one, but a release is never forgotten. */
    fun mergedWith(newer: PlayerWrite): PlayerWrite {
        val newerData = newer.data != null
        return PlayerWrite(
            playerId = playerId,
            sessionId = sessionId,
            data = newer.data ?: data,
            savedTick = if (newerData) newer.savedTick else savedTick,
            release = release || newer.release,
            savedAtNanos = if (newerData) newer.savedAtNanos else savedAtNanos,
        )
    }

    fun ageMillis(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - savedAtNanos)
}

/**
 * Sends saves in order on one thread, so a slow database never holds up the server thread. A failed write is kept and
 * retried, and its session keeps being renewed, so the player stays claimed rather than loading old data elsewhere.
 */
internal class PlayerDataWriter(
    private val store: PlayerDataStore,
    private val executor: ScheduledExecutorService,
    private val onLost: (playerId: UUID, sessionId: UUID) -> Unit,
) {
    private val lock = Any()
    private val pending = LinkedHashMap<UUID, PlayerWrite>()
    private var inFlight: UUID? = null
    private var draining = false
    private var retryMillis = FIRST_RETRY_MILLIS

    fun submit(write: PlayerWrite) {
        synchronized(lock) {
            pending.merge(write.sessionId, write) { old, new -> old.mergedWith(new) }
            if (!draining) {
                draining = true
                executor.execute(::drain)
            }
        }
    }

    /** Sessions that still have unsent data, which must keep their lease even after the player left. */
    fun sessionsWithUnsentWrites(): Set<UUID> = synchronized(lock) {
        pending.keys.toSet() + setOfNotNull(inFlight)
    }

    fun awaitIdle(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (sessionsWithUnsentWrites().isNotEmpty()) {
            if (System.nanoTime() - deadline >= 0L) return false
            Thread.sleep(IDLE_POLL_MILLIS)
        }
        return true
    }

    private fun drain() {
        while (true) {
            val write = synchronized(lock) {
                val next = pending.entries.firstOrNull()?.value
                if (next == null) {
                    draining = false
                    return
                }
                pending.remove(next.sessionId)
                inFlight = next.sessionId
                next
            }
            val stillOwned = try {
                send(write)
            } catch (exception: Exception) {
                NeoPlayerSync.LOGGER.error("Could not save player data for {}. Retrying in {} ms.", write.playerId, retryMillis, exception)
                retryLater(write)
                return
            }
            synchronized(lock) {
                inFlight = null
                retryMillis = FIRST_RETRY_MILLIS
            }
            if (!stillOwned) onLost(write.playerId, write.sessionId)
        }
    }

    private fun send(write: PlayerWrite): Boolean {
        val data = write.data
        return when {
            data == null -> store.release(write.playerId, write.sessionId)
            write.release -> store.saveAndRelease(write.playerId, write.sessionId, data, write.savedTick, write.ageMillis())
            else -> store.save(write.playerId, write.sessionId, data, write.savedTick, write.ageMillis())
        }
    }

    private fun retryLater(failed: PlayerWrite) {
        synchronized(lock) {
            val newer = pending[failed.sessionId]
            pending[failed.sessionId] = if (newer == null) failed else failed.mergedWith(newer)
            inFlight = null
            executor.schedule(::drain, retryMillis, TimeUnit.MILLISECONDS)
            retryMillis = (retryMillis * 2).coerceAtMost(MAX_RETRY_MILLIS)
        }
    }

    private companion object {
        const val FIRST_RETRY_MILLIS = 500L
        const val MAX_RETRY_MILLIS = 10_000L
        const val IDLE_POLL_MILLIS = 20L
    }
}
