package dev.xshirae.neoplayersync

import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.atomic.AtomicBoolean
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

    private companion object {
        const val SCHEMA = "neoplayersync_writer_test"
    }
}
