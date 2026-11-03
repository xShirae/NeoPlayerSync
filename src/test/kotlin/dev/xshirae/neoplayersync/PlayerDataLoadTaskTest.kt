package dev.xshirae.neoplayersync

import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtUtils
import net.minecraft.network.chat.Component
import net.minecraft.util.datafix.DataFixers
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerDataLoadTaskTest {
    private class FakeClient(private val leavesAfterClaim: Boolean = false, connectedAtStart: Boolean = true) : JoiningClient {
        @Volatile
        override var connected: Boolean = connectedAtStart
        val finished = CountDownLatch(1)

        @Volatile
        var admitted = false

        @Volatile
        var refusedWith: Component? = null

        override fun admit(beforeContinuing: () -> Unit, ifGone: () -> Unit) {
            if (leavesAfterClaim) {
                connected = false
                ifGone()
            } else {
                beforeContinuing()
                admitted = true
            }
            finished.countDown()
        }

        override fun refuse(reason: Component) {
            refusedWith = reason
            finished.countDown()
        }
    }

    private val player = UUID.randomUUID()
    private var database: PlayerDataTestDatabase? = null
    private lateinit var store: PlayerDataStore
    private lateinit var executor: ScheduledThreadPoolExecutor
    private lateinit var folder: Path

    private val localFile: Path get() = folder.resolve("$player.dat")

    private fun sql(statement: String) = checkNotNull(database).sql(statement)

    private fun ownerCount(): Int = checkNotNull(database)
        .intQuery("SELECT count(*) FROM $SCHEMA.neoplayersync_player WHERE player_id = '$player' AND owner_session IS NOT NULL")

    private fun bytesOf(tag: CompoundTag): ByteArray = ByteArrayOutputStream().also { NbtIo.writeCompressed(tag, it) }.toByteArray()

    private fun currentSave(vararg values: Pair<String, Int>): CompoundTag = NbtUtils.addCurrentDataVersion(
        CompoundTag().apply { values.forEach { (key, value) -> putInt(key, value) } },
    )

    private fun storeCharacter(data: ByteArray) {
        val session = UUID.randomUUID()
        store.claim(player, session, "earlier server")
        executor.submit { store.saveAndRelease(player, session, data, savedTick = 100L) }.get()
    }

    private fun readLocal(): CompoundTag = NbtIo.readCompressed(localFile, NbtAccounter.unlimitedHeap())

    @Volatile
    private var admittedClaim: PlayerClaim? = null

    private fun join(client: FakeClient, waitMillis: Long = 10_000L, staleMillis: Long = 300L) {
        PlayerDataLoadTask(
            playerId = player,
            client = client,
            store = store,
            executor = executor,
            saveFolder = folder,
            fixer = DataFixers.getDataFixer(),
            serverName = "test",
            waitMillis = waitMillis,
            staleMillis = staleMillis,
            onAdmitted = { _, claim -> admittedClaim = claim },
        ).run(Consumer { })
        assertTrue(client.finished.await(30, TimeUnit.SECONDS), "The join step never finished")
        executor.submit { }.get()
    }

    @BeforeTest
    fun setUp() {
        database = PlayerDataTestDatabase(SCHEMA)
        store = checkNotNull(database).newStore()
        store.prepareTables()
        executor = ScheduledThreadPoolExecutor(1)
        folder = Files.createTempDirectory("neoplayersync-load-test")
    }

    @AfterTest
    fun tearDown() {
        if (::executor.isInitialized) executor.shutdownNow()
        database?.close()
        if (::folder.isInitialized) folder.toFile().deleteRecursively()
    }

    @Test
    fun `a new player is admitted with no file written`() {
        val client = FakeClient()
        join(client)
        assertTrue(client.admitted)
        assertFalse(localFile.exists())
        assertEquals(1, ownerCount())
    }

    @Test
    fun `an existing local file stays when the database has no character yet`() {
        val local = bytesOf(currentSave("XpLevel" to 5))
        localFile.writeBytes(local)
        val client = FakeClient()
        join(client)
        assertTrue(client.admitted)
        assertContentEquals(local, localFile.readBytes())
    }

    @Test
    fun `a stored character is merged into the local file`() {
        storeCharacter(bytesOf(currentSave("XpLevel" to 30, "Pos" to 1)))
        localFile.writeBytes(bytesOf(currentSave("XpLevel" to 1, "Pos" to 2)))
        val client = FakeClient()
        join(client)
        assertTrue(client.admitted)
        assertEquals(30, readLocal().getIntOr("XpLevel", -1))
        assertEquals(2, readLocal().getIntOr("Pos", -1))
        assertEquals(1, ownerCount())
        val saved = assertNotNull(admittedClaim?.saved, "the cooldown shift needs the stored save")
        assertEquals(100L, saved.savedTick)
    }

    @Test
    fun `damaged stored data refuses the player and leaves the local file alone`() {
        storeCharacter(byteArrayOf(0, 17, 34))
        val local = bytesOf(currentSave("XpLevel" to 5))
        localFile.writeBytes(local)
        val client = FakeClient()
        join(client)
        assertEquals(PlayerDataLoadTask.LOAD_FAILED, client.refusedWith)
        assertFalse(client.admitted)
        assertContentEquals(local, localFile.readBytes())
        assertEquals(0, ownerCount(), "the row must be released")
    }

    @Test
    fun `a newer data schema refuses the player`() {
        storeCharacter(bytesOf(currentSave("XpLevel" to 30)))
        sql("UPDATE $SCHEMA.neoplayersync_player SET schema_version = 999")
        val client = FakeClient()
        join(client)
        assertEquals(PlayerDataLoadTask.LOAD_FAILED, client.refusedWith)
        assertFalse(localFile.exists())
        assertEquals(0, ownerCount())
    }

    @Test
    fun `stored data from a newer game version refuses the player`() {
        val newer = NbtUtils.addDataVersion(CompoundTag(), SharedConstants.getCurrentVersion().dataVersion().version() + 1)
        storeCharacter(bytesOf(newer))
        val client = FakeClient()
        join(client)
        assertEquals(PlayerDataLoadTask.LOAD_FAILED, client.refusedWith)
        assertFalse(localFile.exists())
    }

    @Test
    fun `an unreadable local file only costs the position`() {
        storeCharacter(bytesOf(currentSave("XpLevel" to 30)))
        localFile.writeBytes(byteArrayOf(9, 9, 9))
        val client = FakeClient()
        join(client)
        assertTrue(client.admitted)
        assertEquals(30, readLocal().getIntOr("XpLevel", -1))
    }

    @Test
    fun `a local file from a newer game version only costs the position`() {
        storeCharacter(bytesOf(currentSave("XpLevel" to 30)))
        val newer = NbtUtils.addDataVersion(CompoundTag().apply { putInt("Pos", 2) }, SharedConstants.getCurrentVersion().dataVersion().version() + 1)
        localFile.writeBytes(bytesOf(newer))
        val client = FakeClient()
        join(client)
        assertTrue(client.admitted, "the character is fine, so a bad local file must not refuse the player")
        assertEquals(30, readLocal().getIntOr("XpLevel", -1))
        assertFalse(readLocal().contains("Pos"))
    }

    @Test
    fun `a player held by a live server is refused after the wait and the claim is left alone`() {
        store.claim(player, UUID.randomUUID(), "other server")
        val client = FakeClient()
        join(client, waitMillis = 600L)
        assertEquals(PlayerDataLoadTask.STILL_HELD, client.refusedWith)
        assertEquals(1, ownerCount())
    }

    @Test
    fun `a player is admitted once the other server's lease runs out`() {
        store.claim(player, UUID.randomUUID(), "crashed server")
        sql("UPDATE $SCHEMA.neoplayersync_player SET lease_until = now() + interval '1 second'")
        val client = FakeClient()
        join(client)
        assertTrue(client.admitted)
    }

    @Test
    fun `after a database outage the old server's waiting save is not shut out`() {
        val holder = UUID.randomUUID()
        store.claim(player, holder, "old server")
        // Every lease has run out by the time the database is back.
        sql("UPDATE $SCHEMA.neoplayersync_player SET lease_until = now() - interval '1 minute'")
        val oldServer = thread {
            checkNotNull(database).newStore().use { oldStore ->
                Thread.sleep(400L)
                oldStore.heartbeat(listOf(holder))
                Thread.sleep(400L)
                check(oldStore.saveAndRelease(player, holder, bytesOf(currentSave("XpLevel" to 7)), savedTick = 55L))
            }
        }
        val client = FakeClient()
        join(client, waitMillis = 5_000L, staleMillis = 1_500L)
        oldServer.join()
        assertTrue(client.admitted)
        assertEquals(55L, admittedClaim?.saved?.savedTick, "the join must load the save that waited out the outage")
        assertEquals(7, readLocal().getIntOr("XpLevel", -1))
    }

    @Test
    fun `a server that renews once and then stops is still taken over`() {
        store.claim(player, UUID.randomUUID(), "old server")
        sql("UPDATE $SCHEMA.neoplayersync_player SET lease_until = now() - interval '1 minute'")
        val lastRenewal = thread {
            Thread.sleep(300L)
            sql("UPDATE $SCHEMA.neoplayersync_player SET lease_until = now() + interval '500 milliseconds'")
        }
        val client = FakeClient()
        join(client, waitMillis = 5_000L, staleMillis = 1_000L)
        lastRenewal.join()
        assertTrue(client.admitted, "the watch must follow the renewal, or the stopped server keeps the player forever")
    }

    @Test
    fun `a client that leaves right after the claim releases the row`() {
        val client = FakeClient(leavesAfterClaim = true)
        join(client)
        assertFalse(client.admitted)
        assertEquals(0, ownerCount())
    }

    @Test
    fun `a client that left before the claim never takes the row`() {
        val client = FakeClient(connectedAtStart = false)
        PlayerDataLoadTask(player, client, store, executor, folder, DataFixers.getDataFixer(), "test").run(Consumer { })
        executor.submit { }.get()
        assertEquals(0, ownerCount())
        assertFalse(client.admitted)
        assertNull(client.refusedWith)
    }

    @Test
    fun `a failed file swap refuses the player and leaves no temporary file`() {
        storeCharacter(bytesOf(currentSave("XpLevel" to 30)))
        val local = bytesOf(currentSave("XpLevel" to 5))
        localFile.writeBytes(local)
        // The backup step cannot replace a folder that holds a file, so the swap fails.
        val blockedBackup = folder.resolve("$player.dat_old")
        Files.createDirectory(blockedBackup)
        Files.writeString(blockedBackup.resolve("blocker"), "x")
        val client = FakeClient()
        join(client)
        assertEquals(PlayerDataLoadTask.LOAD_FAILED, client.refusedWith)
        assertContentEquals(local, localFile.readBytes())
        val leftovers = folder.listDirectoryEntries().filter { it.name.startsWith("$player-") }
        assertTrue(leftovers.isEmpty(), "temporary files left behind: $leftovers")
    }

    private companion object {
        const val SCHEMA = "neoplayersync_load_test"
    }
}
