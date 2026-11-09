package dev.xshirae.neoplayersync

import com.mojang.authlib.GameProfile
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.stats.Stats
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerListener
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.storage.TagValueInput
import net.neoforged.neoforge.event.EventHooks
import org.opentest4j.TestAbortedException
import java.nio.file.Files
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real players, real vanilla saves and the real database. Each test has its own batch, because they share the one active
 * service. Tests using the database log a warning and pass without checking anything when tools/test-postgres.ps1 is not
 * running, because the report has no way to show a skipped test. Run them with it started.
 */
object PlayerDataSyncGameTests {
    private class Fixture(val helper: GameTestHelper, val database: PlayerDataTestDatabase, val service: PlayerDataService) {
        val checks: PlayerDataStore = database.newStore()

        fun claimedPlayer(name: String, connected: () -> Boolean = { true }): Pair<ServerPlayer, UUID> {
            val player = TestPlayers.survival(helper, name)
            val session = UUID.randomUUID()
            checkNotNull(checks.claim(player.uuid, session, "test"))
            service.addSession(player.uuid, session, connected = connected)
            service.onLoggedIn(player)
            return player to session
        }

        /** Vanilla's own save, which every save trigger ends in. */
        fun save(player: ServerPlayer) = helper.level.server.playerList.playerIo.save(player)

        fun localSaveExists(player: ServerPlayer): Boolean =
            Files.exists(helper.level.server.playerList.playerIo.playerDir.toPath().resolve("${player.uuid}.dat"))

        fun localInventoryCount(player: ServerPlayer): Int {
            val file = helper.level.server.playerList.playerIo.playerDir.toPath().resolve("${player.uuid}.dat")
            return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()).getListOrEmpty("Inventory").size
        }

        fun savedCount(player: ServerPlayer): Int =
            database.intQuery("SELECT count(*) FROM $SCHEMA.neoplayersync_player WHERE player_id = '${player.uuid}' AND data IS NOT NULL")

        fun ownedCount(player: ServerPlayer): Int =
            database.intQuery("SELECT count(*) FROM $SCHEMA.neoplayersync_player WHERE player_id = '${player.uuid}' AND owner_session IS NOT NULL")

        fun storedExtraHas(player: ServerPlayer, path: String): Boolean =
            database.intQuery("SELECT count(*) FROM $SCHEMA.neoplayersync_player WHERE player_id = '${player.uuid}' AND extra -> $path") == 1

        fun storedInventoryCount(player: ServerPlayer): Int = storedSave(player)?.getListOrEmpty("Inventory")?.size ?: -1

        fun storedSave(player: ServerPlayer): CompoundTag? {
            val stored = database.newConnectionForTest().use { connection ->
                connection.prepareStatement("SELECT data FROM neoplayersync_player WHERE player_id = '${player.uuid}'").use { query ->
                    query.executeQuery().use { row ->
                        row.next()
                        row.getBytes(1)
                    }
                }
            } ?: return null
            return PlayerDataMerge.readSave(stored)
        }

        /**
         * The first one-second check where no timed save of [players] lands in the next [span] ticks. Test players get
         * random IDs, so a fixed tick would sometimes add an urgent save and change how many inventory saves wait.
         */
        fun quietCheckTick(players: List<ServerPlayer>, span: Int = 3): Long =
            generateSequence(20L) { it + 20L }.first { start ->
                (start until start + span).none { tick -> players.any { service.isTimedSaveTick(it.uuid, tick) } }
            }

        /** Passes the test once [condition] holds. Runs the check each tick, because saves land on another thread. */
        fun finishWhen(message: String, condition: () -> Boolean) {
            helper.succeedWhen {
                helper.assertTrue(condition(), message)
                finish()
            }
        }

        fun finish() {
            if (PlayerDataSync.service === service) PlayerDataSync.service = null
            service.stop()
            database.close()
        }
    }

    private fun withDatabase(
        helper: GameTestHelper,
        writeStore: (PlayerDataTestDatabase) -> PlayerDataStore = { it.newStore() },
        // No time to spare, so exactly one queued inventory save runs per tick and counts don't depend on machine speed.
        inventorySaveBudgetNanos: Long = 0L,
        extras: (MinecraftServer) -> List<PlayerExtra> = { emptyList() },
        test: Fixture.() -> Unit,
    ) {
        val database = try {
            PlayerDataTestDatabase(SCHEMA)
        } catch (_: TestAbortedException) {
            NeoPlayerSync.LOGGER.warn("SKIPPED a player data GameTest: test Postgres is not running, so it passes without checking anything.")
            helper.succeed()
            return
        }
        database.newStore().prepareTables()
        val service = PlayerDataService(
            helper.level.server,
            database.newStore(),
            writeStore(database),
            database.newStore(),
            "test",
            inventorySaveBudgetNanos,
            extras(helper.level.server),
        )
        PlayerDataSync.service = service
        Fixture(helper, database, service).test()
    }

    /** Only the listener notices pickups, because vanilla's inventory change counter never moves for them. */
    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-listener")
    fun everyKindOfInventoryChangeReachesASlotListener(helper: GameTestHelper) {
        val player = TestPlayers.survival(helper, "sync-slots")
        var changes = 0
        player.inventoryMenu.addSlotListener(object : ContainerListener {
            override fun slotChanged(menu: AbstractContainerMenu, slot: Int, stack: ItemStack) {
                changes++
            }

            override fun dataChanged(menu: AbstractContainerMenu, id: Int, value: Int) = Unit
        })
        fun check(what: String, action: () -> Unit) {
            changes = 0
            action()
            player.inventoryMenu.broadcastChanges()
            helper.assertTrue(changes > 0, "$what should reach the slot listener")
        }

        check("A pickup") { player.inventory.add(ItemStack(Items.DIAMOND, 7)) }
        check("Using up an item") { player.inventory.getItem(0).shrink(1) }
        check("Removing an item") { player.inventory.removeItem(0, 1) }
        check("Armor") { player.setItemSlot(EquipmentSlot.FEET, ItemStack(Items.IRON_BOOTS)) }
        check("The off hand") { player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack(Items.SHIELD)) }
        check("Durability") { player.getItemBySlot(EquipmentSlot.FEET).damageValue = 3 }
        helper.level.server.commands.performPrefixedCommand(
            helper.level.server.createCommandSourceStack(),
            "give ${player.scoreboardName} emerald 2",
        )
        player.inventoryMenu.broadcastChanges()
        helper.assertTrue(changes > 0, "A command should reach the slot listener")

        helper.level.server.playerList.remove(player)
        helper.succeed()
    }

    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-pickup")
    fun aPickupReachesTheDatabaseWithinASecondOrSo(helper: GameTestHelper) = withDatabase(helper) {
        val (player, _) = claimedPlayer("sync-pick")
        player.inventory.add(ItemStack(Items.DIAMOND, 7))
        player.inventoryMenu.broadcastChanges()
        // Checked now, because the 60-second timed save could otherwise satisfy the wait below.
        service.onTick(20L)
        helper.assertTrue(localSaveExists(player), "The pickup should trigger a save at the next one-second check")
        finishWhen("The pickup should be stored") {
            (storedInventoryCount(player) == 1).also { if (it) helper.level.server.playerList.remove(player) }
        }
    }

    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-wear")
    fun wearAloneWaitsForTheNextTimedSave(helper: GameTestHelper) = withDatabase(helper) {
        val (player, _) = claimedPlayer("sync-wear")
        val localSave = helper.level.server.playerList.playerIo.playerDir.toPath().resolve("${player.uuid}.dat")
        val checkTicks = generateSequence(20L) { it + 20L }.filterNot { service.isTimedSaveTick(player.uuid, it) }.iterator()
        fun savesAtNextCheck(what: String, expected: Boolean, change: () -> Unit) {
            Files.deleteIfExists(localSave)
            change()
            player.inventoryMenu.broadcastChanges()
            service.onTick(checkTicks.next())
            helper.assertTrue(Files.exists(localSave) == expected, "$what should ${if (expected) "" else "not "}trigger a save")
        }
        val elytra = { player.getItemBySlot(EquipmentSlot.CHEST) }

        savesAtNextCheck("Putting on an elytra", true) { player.setItemSlot(EquipmentSlot.CHEST, ItemStack(Items.ELYTRA)) }
        savesAtNextCheck("Wear", false) { elytra().damageValue = 5 }
        savesAtNextCheck("A repair back to full", false) { elytra().damageValue = 0 }
        savesAtNextCheck("Renaming", true) { elytra().set(DataComponents.CUSTOM_NAME, Component.literal("Wings")) }
        savesAtNextCheck("Breaking", true) { player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY) }

        helper.level.server.playerList.remove(player)
        finish()
        helper.succeed()
    }

    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-logout")
    fun loggingOutStoresTheFinalSaveAndFreesThePlayer(helper: GameTestHelper) = withDatabase(helper) {
        val (player, _) = claimedPlayer("sync-out")
        player.inventory.add(ItemStack(Items.EMERALD, 3))
        helper.level.server.playerList.remove(player)
        finishWhen("The final save should be stored and the player freed") {
            savedCount(player) == 1 && ownedCount(player) == 0 && storedInventoryCount(player) == 1
        }
    }

    /**
     * Vanilla writes advancements and statistics only after the player file, which sends the final upload, so the export
     * must save them itself or the last thing earned before leaving would be missing.
     */
    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-extras")
    fun advancementsAndStatisticsEarnedJustBeforeLeavingAreStored(helper: GameTestHelper) = withDatabase(
        helper,
        extras = { listOf(PlayerExtras.advancements(it), PlayerExtras.statistics(it)) },
    ) {
        val (player, _) = claimedPlayer("sync-extras")
        val root = checkNotNull(helper.level.server.advancements.get(Identifier.withDefaultNamespace("story/root")))
        player.advancements.award(root, "crafting_table")
        player.awardStat(Stats.JUMP)
        helper.level.server.playerList.remove(player)
        finishWhen("The final upload should carry the new advancement and statistic, and free the player") {
            ownedCount(player) == 0 &&
                storedExtraHas(player, "'advancements' ? 'minecraft:story/root'") &&
                storedExtraHas(player, "'statistics' -> 'stats' -> 'minecraft:custom' ? 'minecraft:jump'")
        }
    }

    /** A marker planted in the stored advancements shows whether a save replaced them. */
    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-advancement-changes")
    fun advancementsUploadOnlyAfterAGrantOrRevoke(helper: GameTestHelper) = withDatabase(
        helper,
        extras = { listOf(PlayerExtras.advancements(it)) },
    ) {
        val (player, _) = claimedPlayer("sync-adv")
        val marker = "'advancements' ? 'marker'"
        var step = -1
        // Each step waits for the previous save to land, so a failed check repeats until the timeout reports it.
        helper.succeedWhen {
            when (step) {
                -1 -> {
                    // The first tick grants the recipes vanilla unlocks right away, which would count as a change below.
                    helper.assertTrue(player.tickCount > 0, "The player should tick once")
                    save(player)
                    step++
                }
                0 -> {
                    helper.assertTrue(storedExtraHas(player, "'advancements' ? 'DataVersion'"), "The first save should upload advancements")
                    database.sql("UPDATE $SCHEMA.neoplayersync_player SET extra = jsonb_set(extra, '{advancements,marker}', 'true') WHERE player_id = '${player.uuid}'")
                    // Health, because an item could unlock a recipe advancement and upload them for a real reason.
                    player.health = 7f
                    save(player)
                    step++
                }
                1 -> {
                    helper.assertTrue(storedSave(player)?.getFloatOr("Health", 0f) == 7f, "The second save should land")
                    helper.assertTrue(storedExtraHas(player, marker), "A save without a grant or revoke must leave advancements alone")
                    val root = checkNotNull(helper.level.server.advancements.get(Identifier.withDefaultNamespace("story/root")))
                    player.advancements.award(root, "crafting_table")
                    save(player)
                    step++
                }
                else -> {
                    helper.assertTrue(
                        !storedExtraHas(player, marker) && storedExtraHas(player, "'advancements' ? 'minecraft:story/root'"),
                        "A grant should upload advancements again",
                    )
                    helper.level.server.playerList.remove(player)
                    finish()
                    return@succeedWhen
                }
            }
            helper.assertTrue(false, "Waiting for step $step")
        }
    }

    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-failed-save")
    fun aFinalSaveThatFailsStillFreesThePlayer(helper: GameTestHelper) = withDatabase(helper) {
        val (player, _) = claimedPlayer("sync-fail")
        // Marks the logout the way a real one does, with no save following, as when vanilla's save fails.
        service.onLoggedOut(player)
        service.onTick(1L)
        finishWhen("The claim should be given up") {
            (ownedCount(player) == 0 && savedCount(player) == 0).also { if (it) helper.level.server.playerList.remove(player) }
        }
    }

    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-save-all")
    fun savingEveryoneFailsWhileOneSaveFailsAndSucceedsOnceItIsGone(helper: GameTestHelper) = withDatabase(helper) {
        val (player, _) = claimedPlayer("sync-all")
        // Missing from the player list, so vanilla saves nothing and fires no event, as when its save fails.
        val profile = GameProfile(UUID.randomUUID(), "syncghost")
        val ghost = ServerPlayer(
            helper.level.server, helper.level, profile, CommonListenerCookie.createInitial(profile, false).clientInformation(),
        )
        service.addSession(ghost.uuid, UUID.randomUUID()) { true }
        service.onLoggedIn(ghost)
        val withGhost = service.saveAllNow()
        var withoutGhost: CompletableFuture<Int>? = null
        finishWhen("Saving everyone should fail with the ghost, then save the one player once the ghost is gone") {
            if (withoutGhost == null && withGhost.isDone) {
                helper.assertTrue(withGhost.isCompletedExceptionally, "The failed save should fail the whole sync")
                helper.assertTrue(savedCount(player) == 1, "The other player's save should still land")
                service.onLoggedOut(ghost)
                service.onTick(1L)
                withoutGhost = service.saveAllNow()
            }
            (withoutGhost?.getNow(null) == 1).also { if (it) helper.level.server.playerList.remove(player) }
        }
    }

    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-left")
    fun aClientThatLeavesDuringConfigurationFreesThePlayer(helper: GameTestHelper) = withDatabase(helper) {
        val player = TestPlayers.survival(helper, "sync-left")
        val session = UUID.randomUUID()
        checkNotNull(checks.claim(player.uuid, session, "test"))
        service.addSession(player.uuid, session) { false }
        service.onTick(20L)
        finishWhen("The claim should be given up") {
            (ownedCount(player) == 0).also { if (it) helper.level.server.playerList.remove(player) }
        }
    }

    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-lost")
    fun aServerThatLostThePlayerKicksThemAndStoresNothing(helper: GameTestHelper) = withDatabase(helper) {
        val (player, _) = claimedPlayer("sync-lost")
        database.sql("UPDATE $SCHEMA.neoplayersync_player SET owner_session = gen_random_uuid() WHERE player_id = '${player.uuid}'")
        save(player)
        finishWhen("The player should be kicked with nothing stored") {
            (!player.connection.connection.isConnected && savedCount(player) == 0).also { kicked ->
                if (kicked) helper.level.server.playerList.remove(player)
            }
        }
    }

    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-cap")
    fun inventorySavesWaitTheirTurnOnceTheBudgetIsSpent(helper: GameTestHelper) = withDatabase(helper) {
        val players = (1..5).map { claimedPlayer("sync-cap$it").first }
        players.forEach {
            it.inventory.add(ItemStack(Items.DIAMOND))
            it.inventoryMenu.broadcastChanges()
        }
        val tick = quietCheckTick(players, span = 5)
        service.onTick(tick)
        helper.assertTrue(service.inventorySavesWaiting == 4, "1 of 5 should save with no time to spare, ${service.inventorySavesWaiting} are waiting")
        service.onTick(tick + 1)
        helper.assertTrue(service.inventorySavesWaiting == 3, "1 more should save next tick, ${service.inventorySavesWaiting} are waiting")
        (2L..4L).forEach { service.onTick(tick + it) }
        helper.assertTrue(service.inventorySavesWaiting == 0, "All should be saved by now")
        finishWhen("All 5 should be stored") {
            players.all { savedCount(it) == 1 }.also { done ->
                if (done) players.forEach(helper.level.server.playerList::remove)
            }
        }
    }

    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-budget")
    fun aRoomyBudgetSavesTheWholeQueueInOneTick(helper: GameTestHelper) = withDatabase(helper, inventorySaveBudgetNanos = TimeUnit.SECONDS.toNanos(10L)) {
        val players = (1..5).map { claimedPlayer("sync-budget$it").first }
        players.forEach {
            it.inventory.add(ItemStack(Items.DIAMOND))
            it.inventoryMenu.broadcastChanges()
        }
        service.onTick(quietCheckTick(players, span = 1))
        helper.assertTrue(service.inventorySavesWaiting == 0, "All 5 should save in one tick, ${service.inventorySavesWaiting} are waiting")
        finishWhen("All 5 should be stored") {
            players.all { savedCount(it) == 1 }.also { done ->
                if (done) players.forEach(helper.level.server.playerList::remove)
            }
        }
    }

    /** Without the skip, this tick's inventory save would repeat a player the urgent saves just covered, and 3 would still wait. */
    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-urgent")
    fun anUrgentSaveTakesThePlayerOutOfTheInventoryQueue(helper: GameTestHelper) = withDatabase(helper) {
        val players = (1..5).map { claimedPlayer("sync-urg$it").first }
        players.forEach {
            it.inventory.add(ItemStack(Items.DIAMOND))
            it.inventoryMenu.broadcastChanges()
        }
        val tick = quietCheckTick(players)
        service.onTick(tick)
        helper.assertTrue(service.inventorySavesWaiting == 4, "1 of 5 should save this tick, ${service.inventorySavesWaiting} are waiting")

        val respawned = players.map { helper.level.server.playerList.respawn(it, false, Entity.RemovalReason.KILLED) }
        service.onTick(tick + 1)
        helper.assertTrue(service.inventorySavesWaiting == 0, "Urgent saves should empty the queue, ${service.inventorySavesWaiting} are waiting")
        finishWhen("All 5 should be stored") {
            players.all { savedCount(it) == 1 }.also { done ->
                if (done) respawned.forEach(helper.level.server.playerList::remove)
            }
        }
    }

    /** Vanilla puts no limit on client close packets, so they must never force more saves than the budget allows. */
    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-close")
    fun closingContainersStaysWithinTheBudget(helper: GameTestHelper) = withDatabase(helper) {
        val players = (1..5).map { claimedPlayer("sync-close$it").first }
        players.forEach { it.doCloseContainer() }
        helper.assertTrue(players.none(::localSaveExists), "Closing a container must not save right away")

        val tick = quietCheckTick(players)
        service.onTick(tick)
        helper.assertTrue(service.inventorySavesWaiting == 4, "1 of 5 should save this tick, ${service.inventorySavesWaiting} are waiting")
        finishWhen("Every closed container should be stored") {
            service.onTick(tick + 1)
            players.all { savedCount(it) == 1 }.also { done ->
                if (done) players.forEach(helper.level.server.playerList::remove)
            }
        }
    }

    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-respawn")
    fun inventoryChangesAreStillNoticedAfterRespawning(helper: GameTestHelper) = withDatabase(helper) {
        val (player, _) = claimedPlayer("sync-back")
        val respawned = helper.level.server.playerList.respawn(player, false, Entity.RemovalReason.KILLED)
        service.onTick(1L)
        helper.assertTrue(localSaveExists(respawned), "Respawning should save the emptied inventory right away")

        respawned.inventory.add(ItemStack(Items.DIAMOND, 7))
        respawned.inventoryMenu.broadcastChanges()
        service.onTick(20L)
        helper.assertTrue(localInventoryCount(respawned) == 1, "A pickup after respawning should trigger a save")
        finishWhen("The pickup should be stored") {
            (storedInventoryCount(respawned) == 1).also { if (it) helper.level.server.playerList.remove(respawned) }
        }
    }

    /** The first write fails, so only waiting for the retry can land it. Shutdown drops a retry that is still waiting. */
    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-stop")
    fun stoppingWaitsForSavesAndFreesEveryPlayer(helper: GameTestHelper) {
        val firstConnection = AtomicBoolean(true)
        withDatabase(helper, writeStore = { database ->
            PlayerDataStore {
                if (firstConnection.getAndSet(false)) throw SQLException("The database is briefly down")
                database.newConnectionForTest()
            }
        }) {
            val (saved, _) = claimedPlayer("sync-stop")
            val (joining, _) = claimedPlayer("sync-join")
            saved.inventory.add(ItemStack(Items.EMERALD, 3))
            save(saved)

            PlayerDataSync.service = null
            service.stop()

            helper.assertTrue(storedInventoryCount(saved) == 1, "Stopping must wait for the save to land")
            helper.assertTrue(ownedCount(saved) == 0 && ownedCount(joining) == 0, "Stopping must free every player")
            listOf(saved, joining).forEach(helper.level.server.playerList::remove)
            database.close()
            helper.succeed()
        }
    }

    /** Loads a player the way vanilla does, as if the claim had returned a save from [savedTick], and returns what other mods saw. */
    private fun Fixture.loadAndWatch(name: String, savedTick: Long?, elapsedMillis: Long, fires: Int = 1): Pair<ServerPlayer, List<CompoundTag>> {
        val player = TestPlayers.survival(helper, name)
        val claim = PlayerClaim(PlayerDataStore.PLAYER_DATA_SCHEMA, savedTick?.let { SavedPlayer(ByteArray(0), it, elapsedMillis) })
        service.addSession(player.uuid, UUID.randomUUID(), claim) { true }
        repeat(fires) { EventHooks.firePlayerLoadingEvent(player, helper.level.server.playerList, player.stringUUID) }
        val seen = RestoredTagProbe.seen.remove(player.uuid).orEmpty()
        helper.level.server.playerList.remove(player)
        return player to seen
    }

    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-restored")
    fun otherModsAreToldHowOldTheLoadedSaveIs(helper: GameTestHelper) = withDatabase(helper) {
        val (player, seen) = loadAndWatch("restored", savedTick = 5_000_000L, elapsedMillis = 5_000L)
        helper.assertTrue(seen.size == 1, "Other mods should see the tag once, saw ${seen.size}")
        val tag = seen.single()
        helper.assertTrue(tag.getLongOr(NeoPlayerSync.SAVED_GAME_TIME, -1L) == 5_000_000L, "The tag should name the old server's clock: $tag")
        val elapsed = tag.getLongOr(NeoPlayerSync.ELAPSED_MILLIS, -1L)
        helper.assertTrue(elapsed in 5_000L..6_000L, "The tag should count the save's age and the join, was $elapsed")
        helper.assertTrue(!player.persistentData.contains(NeoPlayerSync.RESTORED_TAG), "The tag must be gone before anything saves it")
        finish()
        helper.succeed()
    }

    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-restored-none")
    fun aCharacterWithNoStoredDataGetsNoTag(helper: GameTestHelper) = withDatabase(helper) {
        val (_, seen) = loadAndWatch("restored-none", savedTick = null, elapsedMillis = 0L)
        helper.assertTrue(seen.isEmpty(), "A first join has no stored save to report, saw ${seen.size}")
        finish()
        helper.succeed()
    }

    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-restored-once")
    fun theTagIsGivenOnlyOncePerJoin(helper: GameTestHelper) = withDatabase(helper) {
        val (_, seen) = loadAndWatch("restored-once", savedTick = 5_000_000L, elapsedMillis = 5_000L, fires = 2)
        helper.assertTrue(seen.size == 1, "A second load event must not repeat the tag, saw ${seen.size}")
        finish()
        helper.succeed()
    }

    /**
     * Claims a player and loads them the way vanilla does, from their own save with the test attachment stored as
     * [attachment]. Returns the player once the load event has run.
     */
    private fun Fixture.loadWithAttachment(name: String, attachment: CompoundTag): ServerPlayer {
        val player = TestPlayers.survival(helper, name)
        val session = UUID.randomUUID()
        checkNotNull(checks.claim(player.uuid, session, "test"))
        val stored = player.saveEntityForTest().apply {
            put("neoforge:attachments", CompoundTag().apply { put("${NeoPlayerSync.MOD_ID}:test_attachment", attachment) })
        }
        service.addSession(player.uuid, session, storedAttachments = PlayerDataMerge.storedAttachments(stored)) { true }
        player.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.level.registryAccess(), stored))
        EventHooks.firePlayerLoadingEvent(player, helper.level.server.playerList, player.stringUUID)
        return player
    }

    /** NeoForge skips an attachment it cannot read, and the next save would erase it on every server. */
    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-attachment-unreadable")
    fun anAttachmentThatFailsToLoadRefusesThePlayerAndStoresNothing(helper: GameTestHelper) = withDatabase(helper) {
        // As if a newer version of the owning mod wrote a shape this one's codec rejects.
        val player = loadWithAttachment("attach-bad", CompoundTag().apply { putString("value", "a newer format") })
        service.onTick(1L)
        save(player)
        finishWhen("The player should be disconnected, the row freed and nothing stored") {
            (!player.connection.connection.isConnected && ownedCount(player) == 0 && savedCount(player) == 0).also { done ->
                if (done) helper.level.server.playerList.remove(player)
            }
        }
    }

    @JvmStatic
    @SyncGameTest(timeoutTicks = TIMEOUT_TICKS, template = "empty", batch = "player-data-sync-attachment-loaded")
    fun anAttachmentThatLoadsKeepsThePlayer(helper: GameTestHelper) = withDatabase(helper) {
        val player = loadWithAttachment("attach-ok", CompoundTag().apply { putInt("value", 4_000) })
        service.onTick(1L)
        helper.assertTrue(player.getData(SyncGameTestRegistration.TEST_ATTACHMENT) == 4_000, "The attachment should have loaded")
        helper.assertTrue(player.connection.connection.isConnected, "A player whose attachments loaded must stay")
        helper.assertTrue(service.heldPlayers == 1, "This server should still hold the player")
        helper.level.server.playerList.remove(player)
        finishWhen("The final save should be stored") { savedCount(player) == 1 && ownedCount(player) == 0 }
    }

    private const val SCHEMA = "neoplayersync_sync_test"

    /** GameTests run ticks far faster than real time, and the database and queued server tasks do not keep up. */
    private const val TIMEOUT_TICKS = 4_000
}
