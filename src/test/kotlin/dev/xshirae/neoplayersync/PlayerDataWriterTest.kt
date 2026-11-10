package dev.xshirae.neoplayersync

import com.google.gson.JsonObject
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerDataWriterTest {
    private val player = UUID.randomUUID()
    private val session = UUID.randomUUID()
    private val lost = CopyOnWriteArrayList<UUID>()
    private var database: PlayerDataTestDatabase? = null
    private lateinit var checks: PlayerDataStore
    private lateinit var executor: ScheduledThreadPoolExecutor
    private lateinit var writer: PlayerDataWriter

    private fun save(vararg bytes: Byte, tick: Long = 1L, release: Boolean = false) =
        PlayerWrite(player, session, bytes, tick, release)

    private fun assertFlushed() = assertTrue(writer.awaitIdle(10_000L), "the writer did not finish")

    private fun storedAndClaim(): PlayerClaim? = checks.claim(player, UUID.randomUUID(), "checker")

    @BeforeTest
    fun setUp() {
        val test = PlayerDataTestDatabase(SCHEMA)
        database = test
        checks = test.newStore()
        checks.prepareTables()
        executor = ScheduledThreadPoolExecutor(1)
        writer = PlayerDataWriter(test.newStore(), executor) { _, sessionId -> lost += sessionId }
        checkNotNull(checks.claim(player, session, "test"))
    }

    @AfterTest
    fun tearDown() {
        if (::executor.isInitialized) executor.shutdownNow()
        database?.close()
    }

    @Test
    fun `waiting for chosen sessions ignores the others`() {
        val gate = CountDownLatch(1)
        executor.execute { gate.await() }
        writer.submit(save(1))
        try {
            assertTrue(writer.awaitSent(100L) { it != session }, "another session's save should not hold this up")
            assertFalse(writer.awaitSent(100L) { it == session }, "an unsent save should time out")
        } finally {
            gate.countDown()
        }
        assertTrue(writer.awaitSent(10_000L) { it == session }, "the save did not land")
    }

    @Test
    fun `a save keeps the claim`() {
        writer.submit(save(1, 2, 3))
        assertFlushed()
        assertNull(storedAndClaim(), "the session should still hold the player")
        assertTrue(lost.isEmpty())
    }

    @Test
    fun `a save and release stores the data and frees the player`() {
        writer.submit(save(1, 2, 3, tick = 77L, release = true))
        assertFlushed()
        val saved = assertNotNull(storedAndClaim()?.saved)
        assertContentEquals(byteArrayOf(1, 2, 3), saved.data)
        assertEquals(77L, saved.savedTick)
    }

    @Test
    fun `a release without data keeps what was stored`() {
        writer.submit(save(4, 5))
        writer.submit(PlayerWrite(player, session, null, 0L, release = true))
        assertFlushed()
        assertContentEquals(byteArrayOf(4, 5), assertNotNull(storedAndClaim()?.saved).data)
    }

    @Test
    fun `merging keeps the newest data and never forgets a release`() {
        val merged = save(1, tick = 1L, release = true).mergedWith(save(2, tick = 2L))
        assertContentEquals(byteArrayOf(2), merged.data)
        assertEquals(2L, merged.savedTick)
        assertTrue(merged.release)

        val releaseOnly = save(1, tick = 5L).mergedWith(PlayerWrite(player, session, null, 0L, release = true))
        assertContentEquals(byteArrayOf(1), releaseOnly.data, "a release must not discard unsent data")
        assertEquals(5L, releaseOnly.savedTick)
        assertTrue(releaseOnly.release)
    }

    @Test
    fun `saves sent together end with the newest`() {
        repeat(50) { writer.submit(save(it.toByte(), tick = it.toLong())) }
        writer.submit(save(99, tick = 99L, release = true))
        assertFlushed()
        val saved = assertNotNull(storedAndClaim()?.saved)
        assertContentEquals(byteArrayOf(99), saved.data)
        assertEquals(99L, saved.savedTick)
    }

    private fun storedTick(): Int = checkNotNull(database).intQuery("SELECT saved_tick FROM $SCHEMA.neoplayersync_player")

    @Test
    fun `a save that matches the stored one is skipped`() {
        writer.submit(save(1, 2, 3, tick = 1L))
        assertFlushed()
        writer.submit(save(1, 2, 3, tick = 2L))
        assertFlushed()
        assertEquals(1, storedTick(), "the unchanged save should not be sent")

        writer.submit(save(1, 2, 4, tick = 3L))
        assertFlushed()
        assertEquals(3, storedTick(), "a changed save should be sent")
    }

    private fun extras(vararg entries: Pair<String, Int>) = JsonObject().apply { entries.forEach { (id, value) -> addProperty(id, value) } }

    @Test
    fun `a save whose only change is in its extras is sent`() {
        writer.submit(PlayerWrite(player, session, byteArrayOf(1), 1L, release = false, extras = extras("advancements" to 1)))
        assertFlushed()
        writer.submit(PlayerWrite(player, session, byteArrayOf(1), 2L, release = false, extras = extras("advancements" to 2)))
        assertFlushed()
        assertEquals(2, storedTick(), "a new advancement alone must reach the database")
    }

    @Test
    fun `a change only in what the comparison leaves out is skipped`() {
        val ignoringTime = PlayerDataWriter(checkNotNull(database).newStore(), executor, comparableExtras = { JsonObject().apply { add("advancements", it.get("advancements")) } }) { _, _ -> }
        fun send(tick: Long, advancements: Int) {
            ignoringTime.submit(PlayerWrite(player, session, byteArrayOf(1), tick, release = false, extras = extras("advancements" to advancements, "time" to tick.toInt())))
            assertTrue(ignoringTime.awaitIdle(10_000L))
        }
        send(1L, advancements = 1)
        send(2L, advancements = 1)
        assertEquals(1, storedTick(), "a save whose only change is left out of the comparison should not be sent")
        send(3L, advancements = 2)
        assertEquals(3, storedTick(), "a real change should be sent, with the left-out part too")
    }

    @Test
    fun `a save that leaves out a stored extra is compared on what it carries`() {
        fun send(tick: Long, vararg entries: Pair<String, Int>) {
            writer.submit(PlayerWrite(player, session, byteArrayOf(1), tick, release = false, extras = extras(*entries)))
            assertFlushed()
        }
        send(1L, "statistics" to 1)
        send(2L, "advancements" to 1)
        assertEquals(2, storedTick(), "a kind stored for the first time should be sent")
        send(3L, "advancements" to 1, "statistics" to 1)
        send(4L)
        assertEquals(2, storedTick(), "kinds that match what is stored, or that a save leaves out, are no change")
        send(5L, "statistics" to 2)
        assertEquals(5, storedTick(), "a changed kind should be sent")
    }

    @Test
    fun `merging keeps every extra and the newest value of each`() {
        val older = PlayerWrite(player, session, byteArrayOf(1), 1L, release = false, extras = extras("advancements" to 1, "statistics" to 1))
        val newer = PlayerWrite(player, session, byteArrayOf(2), 2L, release = false, extras = extras("advancements" to 2))
        val merged = older.mergedWith(newer).extras
        assertEquals(2, merged.get("advancements").asInt)
        assertEquals(1, merged.get("statistics").asInt, "an extra only the older save carried must still go out")
    }

    @Test
    fun `a release is sent even when the save matches`() {
        writer.submit(save(1, 2, 3, tick = 1L))
        assertFlushed()
        writer.submit(save(1, 2, 3, tick = 2L, release = true))
        assertFlushed()
        assertEquals(2L, assertNotNull(storedAndClaim()?.saved, "the player should be free").savedTick)
    }

    @Test
    fun `a save the fingerprint cannot read is still sent`() {
        val strict = PlayerDataWriter(checkNotNull(database).newStore(), executor, fingerprint = { error("not a save file") }) { _, _ -> }
        strict.submit(save(1, 2, 3, tick = 1L))
        assertTrue(strict.awaitIdle(10_000L))
        strict.submit(save(1, 2, 3, tick = 2L))
        assertTrue(strict.awaitIdle(10_000L))
        assertEquals(2, storedTick(), "without a fingerprint, every save must go out")
    }

    @Test
    fun `a server that lost the player reports it and changes nothing`() {
        checkNotNull(database).sql("UPDATE $SCHEMA.neoplayersync_player SET owner_session = gen_random_uuid()")
        writer.submit(save(1, 2, 3))
        assertFlushed()
        assertEquals(listOf(session), lost.toList())
        assertEquals(0, checkNotNull(database).intQuery("SELECT count(*) FROM $SCHEMA.neoplayersync_player WHERE data IS NOT NULL"))
    }

    @Test
    fun `a failed write is kept, listed as unsent and retried until it lands`() {
        val test = checkNotNull(database)
        val failing = AtomicBoolean(true)
        val flaky = PlayerDataStore {
            if (failing.get()) throw SQLException("database is down")
            test.newConnectionForTest()
        }
        val retrying = PlayerDataWriter(flaky, executor) { _, _ -> }
        try {
            retrying.submit(save(1))
            retrying.submit(save(2, release = true))
            assertFalse(retrying.awaitIdle(300L), "nothing can be sent while the database is down")
            assertEquals(setOf(session), retrying.sessionsWithUnsentWrites(), "the lease must keep being renewed")

            Thread.sleep(700L)
            failing.set(false)
            assertTrue(retrying.awaitIdle(10_000L), "the write should land once the database is back")
            val saved = assertNotNull(storedAndClaim()?.saved)
            assertContentEquals(byteArrayOf(2), saved.data)
            assertTrue(saved.elapsedMillis >= 1_000L, "time spent waiting for the database must count, measured ${saved.elapsedMillis} ms")
        } finally {
            executor.submit { flaky.close() }.get()
        }
    }

    /**
     * A full server stopping, as vanilla's stopServer does it: saveAll queues a save for everyone, then removeAll kicks
     * them, which queues the final save and release. PlayerDataService.stop then waits up to 20 seconds for the writer.
     */
    @Test
    fun `one connection drains a full server's final saves within the stop wait`() {
        val test = checkNotNull(database)
        val random = Random(1)
        val players = List(SHUTDOWN_PLAYERS) { UUID.randomUUID() to UUID.randomUUID() }
        for ((playerId, sessionId) in players) checkNotNull(checks.claim(playerId, sessionId, "test"))

        val slow = PlayerDataStore { withLatency(test.newConnectionForTest(), ROUND_TRIP_MILLIS) }
        val shutdownExecutor = ScheduledThreadPoolExecutor(1)
        val stopping = PlayerDataWriter(slow, shutdownExecutor) { _, sessionId -> lost += sessionId }
        try {
            val start = System.nanoTime()
            for ((playerId, sessionId) in players) stopping.submit(PlayerWrite(playerId, sessionId, random.nextBytes(SAVE_BYTES), 1L, false))
            for ((playerId, sessionId) in players) stopping.submit(PlayerWrite(playerId, sessionId, random.nextBytes(SAVE_BYTES), 2L, true))
            assertTrue(stopping.awaitIdle(STOP_WAIT_MILLIS), "unsent after the stop wait: ${stopping.sessionsWithUnsentWrites().size} players")
            println("Drained $SHUTDOWN_PLAYERS final saves at $ROUND_TRIP_MILLIS ms per round trip in ${(System.nanoTime() - start) / 1_000_000} ms")

            assertTrue(lost.isEmpty())
            assertEquals(
                SHUTDOWN_PLAYERS,
                test.intQuery(
                    "SELECT count(*) FROM $SCHEMA.neoplayersync_player " +
                        "WHERE owner_session IS NULL AND saved_tick = 2 AND length(data) = $SAVE_BYTES",
                ),
                "every player should hold their final save and be free to join elsewhere",
            )
        } finally {
            shutdownExecutor.submit { slow.close() }.get()
            shutdownExecutor.shutdown()
        }
    }

    private companion object {
        const val SCHEMA = "neoplayersync_writer_test"
        const val SHUTDOWN_PLAYERS = 500

        /** Compressed NBT does not compress further, so random bytes stand in for the save of a player in a large modpack. */
        const val SAVE_BYTES = 64 * 1024

        /** A database in the same region but not on the same machine. */
        const val ROUND_TRIP_MILLIS = 10L

        /** PlayerDataService.STOP_WAIT_SECONDS. */
        const val STOP_WAIT_MILLIS = 20_000L
    }
}
