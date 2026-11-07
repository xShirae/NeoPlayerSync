package dev.xshirae.neoplayersync

import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** One player's next database write. Without [data], the write gives up the claim without saving. */
internal class PlayerWrite(
    val playerId: UUID,
    val sessionId: UUID,
    val data: ByteArray?,
    val savedTick: Long,
    val release: Boolean,
    /** When the game saved [data], on this server's own clock, so time spent waiting for the database still counts. */
    val savedAtNanos: Long = System.nanoTime(),
) {
    /** A newer save replaces an unsent one and keeps any pending release. */
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
 * Sends saves in order on one thread, so a slow database never holds up the server thread. The writer keeps and
 * retries a failed write, and the heartbeat keeps renewing its session, so no other server loads old data while it waits.
 */
internal class PlayerDataWriter(
    private val store: PlayerDataStore,
    private val executor: ScheduledExecutorService,
    /** What must change before a save is worth sending. The whole save by default. */
    private val fingerprint: (ByteArray) -> ByteArray = { it },
    private val onLost: (playerId: UUID, sessionId: UUID) -> Unit,
) {
    private val lock = Any()
    private val pending = LinkedHashMap<UUID, PlayerWrite>()

    /** The fingerprint each session last stored. Only the writer thread uses it. */
    private val lastSent = HashMap<UUID, ByteArray>()
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
                // The write may have landed before the error, so the stored save is unknown until the next one goes out.
                lastSent.remove(write.sessionId)
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
        if (data == null) {
            lastSent.remove(write.sessionId)
            return store.release(write.playerId, write.sessionId)
        }
        val print = fingerprintOrNull(write, data)
        // An idle player's timed save repeats what is stored. A release must still go out, or the row stays held.
        if (!write.release && print != null && print.contentEquals(lastSent[write.sessionId])) return true

        val owned = if (write.release) {
            store.saveAndRelease(write.playerId, write.sessionId, data, write.savedTick, write.ageMillis())
        } else {
            store.save(write.playerId, write.sessionId, data, write.savedTick, write.ageMillis())
        }
        if (owned && !write.release && print != null) lastSent[write.sessionId] = print else lastSent.remove(write.sessionId)
        return owned
    }

    /** A save the fingerprint cannot read is still sent, so a bad file never blocks the upload. */
    private fun fingerprintOrNull(write: PlayerWrite, data: ByteArray): ByteArray? = try {
        fingerprint(data)
    } catch (exception: Exception) {
        NeoPlayerSync.LOGGER.warn("Could not compare the save of {} with the last one, so sending it in full", write.playerId, exception)
        null
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
