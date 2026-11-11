package dev.xshirae.neoplayersync

import dev.xshirae.neoplayersync.api.NeoPlayerSyncApi
import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtUtils
import net.minecraft.util.datafix.DataFixers
import java.io.ByteArrayOutputStream
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StoredPlayerReaderTest {
    private val player = UUID.randomUUID()
    private val fixer = DataFixers.getDataFixer()
    private val currentVersion = SharedConstants.getCurrentVersion().dataVersion().version()
    private val claimThread = Executors.newSingleThreadExecutor { Thread(it, "claims") }
    private val serverThread = Executors.newSingleThreadExecutor { Thread(it, "server") }
    private val decoder = Executors.newSingleThreadExecutor { Thread(it, "decoder") }

    @AfterTest
    fun tearDown() {
        claimThread.shutdownNow()
        serverThread.shutdownNow()
        decoder.shutdownNow()
    }

    private fun bytesOf(tag: CompoundTag): ByteArray = ByteArrayOutputStream().also { NbtIo.writeCompressed(tag, it) }.toByteArray()

    private fun saveAt(version: Int) = CompoundTag().apply {
        putInt("DataVersion", version)
        putInt("XpLevel", 12)
    }

    private fun failureOf(read: () -> Unit): Throwable = assertFailsWith<ExecutionException> { read() }.cause!!

    @Test
    fun `the read statement only selects and never names the lease`() {
        val sql = PlayerDataStore.READ.lowercase()
        assertTrue(sql.startsWith("select "))
        for (forbidden in listOf("owner_session", "owner_server", "lease_until", "for update", "for share", "insert", "update ")) {
            assertFalse(forbidden in sql, "the read must not use $forbidden")
        }
    }

    @Test
    fun `no row and no data yet both read as empty`() {
        assertNull(decodeStored(player, null, fixer))
        assertNull(decodeStored(player, StoredRow(PlayerDataStore.PLAYER_DATA_SCHEMA, data = null), fixer))
    }

    @Test
    fun `stored data is upgraded to this game version`() {
        val tag = decodeStored(player, StoredRow(PlayerDataStore.PLAYER_DATA_SCHEMA, bytesOf(saveAt(3700))), fixer)!!
        assertEquals(currentVersion, NbtUtils.getDataVersion(tag))
        assertEquals(12, tag.getIntOr("XpLevel", -1))
    }

    @Test
    fun `a newer schema is refused, even without data`() {
        val error = assertFailsWith<IllegalStateException> {
            decodeStored(player, StoredRow(PlayerDataStore.PLAYER_DATA_SCHEMA + 1, data = null), fixer)
        }
        assertTrue("newer" in error.message!!)
    }

    @Test
    fun `a newer game version is refused`() {
        assertFailsWith<IllegalStateException> {
            decodeStored(player, StoredRow(PlayerDataStore.PLAYER_DATA_SCHEMA, bytesOf(saveAt(currentVersion + 1))), fixer)
        }
    }

    @Test
    fun `unreadable bytes are refused`() {
        val error = assertFailsWith<IllegalStateException> {
            decodeStored(player, StoredRow(PlayerDataStore.PLAYER_DATA_SCHEMA, byteArrayOf(1, 2, 3)), fixer)
        }
        assertTrue("unreadable" in error.message!!)
    }

    @Test
    fun `each failure is explained with its cause`() {
        val refused = IllegalStateException("refused")
        assertSame(refused, explainReadFailure(player, CompletionException(refused), 100L))
        for (cause in listOf(TimeoutException(), SQLException("down"), java.io.IOException("bad"))) {
            val explained = explainReadFailure(player, CompletionException(cause), 100L)
            assertIs<IllegalStateException>(explained)
            assertSame(cause, explained.cause)
        }
    }

    @Test
    fun `a database error fails the read on the server thread`() {
        val store = PlayerDataStore { throw SQLException("down") }
        val handedToServer = AtomicInteger()
        val countingServer = Executor { task ->
            handedToServer.incrementAndGet()
            serverThread.execute(task)
        }
        val read = StoredPlayerReader(store, claimThread, decoder, fixer, countingServer, 5_000L).read(player)
        val failure = failureOf { read.get(10, TimeUnit.SECONDS) }
        assertIs<IllegalStateException>(failure)
        assertIs<SQLException>(failure.cause)
        assertEquals(1, handedToServer.get())
    }

    @Test
    fun `a stopped claims thread fails the read at once`() {
        val stopped = Executor { throw RejectedExecutionException() }
        val read = StoredPlayerReader(PlayerDataStore { error("unused") }, stopped, decoder, fixer, serverThread, 5_000L).read(player)
        assertIs<IllegalStateException>(failureOf { read.get(1, TimeUnit.SECONDS) })
    }

    @Test
    fun `a slow database times the read out`() {
        val store = PlayerDataStore {
            Thread.sleep(2_000L)
            throw SQLException("too late")
        }
        val read = StoredPlayerReader(store, claimThread, decoder, fixer, serverThread, 100L).read(player)
        val failure = failureOf { read.get(10, TimeUnit.SECONDS) }
        assertIs<TimeoutException>(failure.cause)
    }

    @Test
    fun `without a database the API says so and refuses reads`() {
        assertNull(PlayerDataSync.service)
        assertFalse(NeoPlayerSyncApi.isDatabaseEnabled())
        assertIs<IllegalStateException>(failureOf { NeoPlayerSyncApi.readStoredPlayer(player).get(1, TimeUnit.SECONDS) })
    }

    @Test
    fun `a stored character reads back as a tag`() {
        PlayerDataTestDatabase(SCHEMA).use { database ->
            val store = database.newStore()
            store.prepareTables()
            val session = UUID.randomUUID()
            store.claim(player, session, "a")
            store.saveAndRelease(player, session, bytesOf(saveAt(currentVersion)), savedTick = 1L)

            // Parsing runs on the decoder, so a burst of reads keeps the claims thread free for joins.
            val decodedOn = AtomicInteger()
            val countingDecoder = Executor { task ->
                decodedOn.incrementAndGet()
                decoder.execute(task)
            }
            val tag = StoredPlayerReader(database.newStore(), claimThread, countingDecoder, fixer, serverThread, 5_000L).read(player).get(10, TimeUnit.SECONDS)
            assertEquals(12, tag.orElseThrow().getIntOr("XpLevel", -1))
            assertEquals(1, decodedOn.get())
            assertTrue(StoredPlayerReader(database.newStore(), claimThread, decoder, fixer, serverThread, 5_000L).read(UUID.randomUUID()).get(10, TimeUnit.SECONDS).isEmpty)
        }
    }

    private companion object {
        const val SCHEMA = "neoplayersync_reader_test"
    }
}
