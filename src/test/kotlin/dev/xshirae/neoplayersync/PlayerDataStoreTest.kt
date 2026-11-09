package dev.xshirae.neoplayersync

import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerDataStoreTest {
    private val player = UUID.randomUUID()
    private val sessionA = UUID.randomUUID()
    private val sessionB = UUID.randomUUID()
    private val saveBytes = byteArrayOf(1, 2, 3, 0, -1)
    private var database: PlayerDataTestDatabase? = null
    private lateinit var store: PlayerDataStore

    private fun newStore(): PlayerDataStore = checkNotNull(database).newStore()

    private fun sql(statement: String) = checkNotNull(database).sql(statement)

    private fun intQuery(statement: String): Int = checkNotNull(database).intQuery(statement)

    private fun expireLease() = sql("UPDATE $SCHEMA.neoplayersync_player SET lease_until = now() - interval '1 second'")

    @BeforeTest
    fun setUp() {
        database = PlayerDataTestDatabase(SCHEMA)
        store = newStore()
        store.prepareTables()
    }

    @AfterTest
    fun tearDown() {
        database?.close()
    }

    @Test
    fun `setup on a current database changes nothing`() {
        store.claim(player, sessionA, "a")
        store.prepareTables()
        assertEquals(3, intQuery("SELECT version FROM $SCHEMA.neoplayersync_schema"))
        assertEquals(1, intQuery("SELECT count(*) FROM $SCHEMA.neoplayersync_schema"))
        assertEquals(1, intQuery("SELECT count(*) FROM $SCHEMA.neoplayersync_player"))
    }

    @Test
    fun `setup upgrades layout 1 to the current layout and keeps the players`() {
        assertNotNull(store.claim(player, sessionA, "a"))
        sql("ALTER TABLE $SCHEMA.neoplayersync_player DROP COLUMN extra")
        sql("DROP INDEX $SCHEMA.neoplayersync_player_owner")
        sql("UPDATE $SCHEMA.neoplayersync_schema SET version = 1")

        store.prepareTables()
        assertEquals(3, intQuery("SELECT version FROM $SCHEMA.neoplayersync_schema"))
        assertEquals(
            1,
            intQuery("SELECT count(*) FROM pg_indexes WHERE schemaname = '$SCHEMA' AND indexname = 'neoplayersync_player_owner'"),
        )
        assertTrue(store.save(player, sessionA, saveBytes, savedTick = 1L), "the claim made before the upgrade should still hold")
    }

    @Test
    fun `a row from layout 2 gains an empty extra column`() {
        assertNotNull(store.claim(player, sessionA, "a"))
        sql("ALTER TABLE $SCHEMA.neoplayersync_player DROP COLUMN extra")
        sql("UPDATE $SCHEMA.neoplayersync_schema SET version = 2")

        store.prepareTables()
        assertTrue(store.saveAndRelease(player, sessionA, saveBytes, savedTick = 1L))
        assertEquals(0, assertNotNull(store.claim(player, sessionB, "b")).extras.size())
    }

    @Test
    fun `setup refuses a database from a newer build and leaves it alone`() {
        sql("UPDATE $SCHEMA.neoplayersync_schema SET version = 999")
        val message = assertFailsWith<IllegalStateException> { store.prepareTables() }.message.orEmpty()
        assertTrue("999" in message, message)
        assertEquals(999, intQuery("SELECT version FROM $SCHEMA.neoplayersync_schema"))
    }

    @Test
    fun `servers starting together create the tables once`() {
        sql("DROP TABLE $SCHEMA.neoplayersync_player")
        sql("DROP TABLE $SCHEMA.neoplayersync_schema")
        val results = inParallel(8) { newStore().prepareTables() }
        assertEquals(8, results.size)
        assertEquals(1, intQuery("SELECT count(*) FROM $SCHEMA.neoplayersync_schema"))
    }

    @Test
    fun `a new player claims with no saved data`() {
        val claim = assertNotNull(store.claim(player, sessionA, "a"))
        assertEquals(PlayerDataStore.PLAYER_DATA_SCHEMA, claim.schemaVersion)
        assertNull(claim.saved)
    }

    @Test
    fun `a held player cannot be claimed`() {
        assertNotNull(store.claim(player, sessionA, "a"))
        assertNull(store.claim(player, sessionB, "b"))
    }

    @Test
    fun `the session that holds a player gets it again with the stored data`() {
        store.claim(player, sessionA, "a")
        assertTrue(store.saveAndRelease(player, sessionA, saveBytes, savedTick = 500L))
        assertNotNull(store.claim(player, sessionB, "b"))

        // As if the first reply was lost and the joining server asks again.
        val again = assertNotNull(store.claim(player, sessionB, "b"))
        assertContentEquals(saveBytes, assertNotNull(again.saved).data)
        assertNull(store.claim(player, sessionA, "a"))
    }

    @Test
    fun `only one of many servers wins a claim`() {
        val winners = inParallel(8) { index ->
            newStore().claim(player, UUID.randomUUID(), "server-$index")
        }.count { it != null }
        assertEquals(1, winners)
    }

    @Test
    fun `a released player keeps their data and can be claimed`() {
        store.claim(player, sessionA, "a")
        assertTrue(store.saveAndRelease(player, sessionA, saveBytes, savedTick = 500L))

        val claim = assertNotNull(store.claim(player, sessionB, "b"))
        val saved = assertNotNull(claim.saved)
        assertContentEquals(saveBytes, saved.data)
        assertEquals(500L, saved.savedTick)
        assertTrue(saved.elapsedMillis >= 0L)
    }

    @Test
    fun `a save replaces only the extras it carries`() {
        store.claim(player, sessionA, "a")
        assertTrue(store.save(player, sessionA, saveBytes, savedTick = 1L, extras = """{"advancements":{"a":1},"statistics":{"s":1}}"""))
        assertTrue(store.save(player, sessionA, saveBytes, savedTick = 2L, extras = """{"advancements":{"a":2}}"""))
        assertTrue(store.saveAndRelease(player, sessionA, saveBytes, savedTick = 3L))

        val extras = assertNotNull(store.claim(player, sessionB, "b")).extras
        assertEquals(2, extras.getAsJsonObject("advancements").get("a").asInt)
        assertEquals(1, extras.getAsJsonObject("statistics").get("s").asInt, "a kind the save did not carry must stay")
    }

    @Test
    fun `a new player claims with no extras`() {
        assertEquals(0, assertNotNull(store.claim(player, sessionA, "a")).extras.size())
    }

    @Test
    fun `a save that waited is stored with the time the game saved it`() {
        store.claim(player, sessionA, "a")
        assertTrue(store.saveAndRelease(player, sessionA, saveBytes, savedTick = 1L, ageMillis = 90_000L))
        val elapsed = assertNotNull(store.claim(player, sessionB, "b")?.saved).elapsedMillis
        assertTrue(elapsed in 90_000L..95_000L, "the 90 seconds the save waited should count, measured $elapsed ms")
    }

    @Test
    fun `a release without saving keeps the earlier data`() {
        store.claim(player, sessionA, "a")
        store.save(player, sessionA, saveBytes, savedTick = 1L)
        assertTrue(store.release(player, sessionA))

        assertContentEquals(saveBytes, assertNotNull(store.claim(player, sessionB, "b")?.saved).data)
    }

    @Test
    fun `an expired lease can be taken over and the old server can no longer write`() {
        store.claim(player, sessionA, "a")
        expireLease()
        assertNotNull(store.claim(player, sessionB, "b", store.leaseOf(player)))

        assertFalse(store.save(player, sessionA, saveBytes, savedTick = 1L))
        assertFalse(store.saveAndRelease(player, sessionA, saveBytes, savedTick = 1L))
        assertFalse(store.release(player, sessionA))
        assertNull(store.claim(player, UUID.randomUUID(), "c"), "the old server must not have released the new claim")
        assertEquals(0, intQuery("SELECT count(*) FROM $SCHEMA.neoplayersync_player WHERE data IS NOT NULL"))
    }

    @Test
    fun `an expired lease is only taken over once the caller has watched it go unrenewed`() {
        store.claim(player, sessionA, "a")
        expireLease()
        assertNull(store.claim(player, sessionB, "b"), "expiry alone must not hand the player over")

        val watched = store.leaseOf(player)
        store.heartbeat(listOf(sessionA))
        expireLease()
        assertNull(store.claim(player, sessionB, "b", watched), "a lease renewed since it was watched must not be taken over")
    }

    @Test
    fun `a refused claim reports the holder's lease, which takes the player over once it expired unrenewed`() {
        store.claim(player, sessionA, "a")
        val held = store.tryClaim(player, sessionB, "b")
        assertNull(held.claim)
        assertNotNull(held.heldLease)

        expireLease()
        val expired = store.tryClaim(player, sessionB, "b")
        assertNull(expired.claim, "expiry alone must not hand the player over")
        assertNotNull(store.tryClaim(player, sessionB, "b", expired.heldLease).claim)

        val fresh = store.tryClaim(UUID.randomUUID(), sessionB, "b")
        assertNotNull(fresh.claim, "a player nobody holds is claimed at once")
        assertNull(fresh.heldLease)
    }

    @Test
    fun `a server whose lease expired can still save until someone else claims`() {
        store.claim(player, sessionA, "a")
        expireLease()
        assertTrue(store.save(player, sessionA, saveBytes, savedTick = 1L))
        assertNull(store.claim(player, sessionB, "b"), "the save must renew the lease")
    }

    @Test
    fun `a heartbeat renews only the sessions it names`() {
        val other = UUID.randomUUID()
        store.claim(player, sessionA, "a")
        store.claim(other, sessionB, "a")
        expireLease()

        store.heartbeat(listOf(sessionA))

        assertNull(store.claim(player, UUID.randomUUID(), "c", store.leaseOf(player)))
        assertNotNull(store.claim(other, UUID.randomUUID(), "c", store.leaseOf(other)))
    }

    @Test
    fun `a heartbeat with no sessions does nothing`() {
        store.heartbeat(emptyList())
    }

    @Test
    fun `the store reconnects after losing its connection`() {
        store.claim(player, sessionA, "a")
        sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name = 'NeoPlayerSync' AND pid <> pg_backend_pid()")

        assertFailsWith<SQLException> { store.save(player, sessionA, saveBytes, savedTick = 1L) }
        assertTrue(store.save(player, sessionA, saveBytes, savedTick = 2L))
    }

    @Test
    fun `a wrong password fails to connect`() {
        val wrong = DatabaseSettings.validated("127.0.0.1", 5433, "neoplayersync", "neoplayersync", "wrong", requireSsl = false, serverName = "")
        assertFailsWith<SQLException> { wrong.connect() }
    }

    private fun <T> inParallel(count: Int, action: (Int) -> T): List<T> {
        val pool = Executors.newFixedThreadPool(count)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until count).map { index ->
                pool.submit(Callable {
                    start.await()
                    action(index)
                })
            }
            start.countDown()
            return futures.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val SCHEMA = "neoplayersync_store_test"
    }
}
