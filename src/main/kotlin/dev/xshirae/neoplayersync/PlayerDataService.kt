package dev.xshirae.neoplayersync

import com.google.gson.JsonObject
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerListener
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/** A player this server has claimed. Everything but the IDs belongs to the server thread. */
internal class PlayerDataSession(
    val playerId: UUID,
    val sessionId: UUID,
    val connected: () -> Boolean,
    /** The stored character, until [PlayerDataService.onLoaded] tells other mods how old it is. */
    var unshiftedSave: SavedPlayer?,
    /** Per synced [PlayerExtra]: only what this server could not load, which every export must keep. */
    val unloadedExtras: JsonObject,
    /** Ids of kinds the next save exports: a tracked kind that changed, or an untracked one whose timed, respawn, sync or final save is due. */
    val changedExtras: MutableSet<String>,
    /** The attachment types of the stored character, until [PlayerDataService.onLoaded] checks that each one loaded. */
    var storedAttachments: Set<String>,
) {
    /** When the claim returned, so time spent finishing the join also counts against cooldowns. */
    val claimedAtNanos: Long = System.nanoTime()
    var inWorld = false
    var closing = false
    /** An item was gained, lost, moved or replaced, or a menu closed. */
    var inventoryChanged = false
    /** Only data on an item changed, such as a mod's charge or energy. Wear waits for the timed save instead. */
    var itemDataChanged = false
    /** Counts saves handed to the writer, so a caller can tell whether vanilla's save went through. */
    var uploads = 0
}

/** Everything that exists only while a dedicated server is using Postgres. Each store belongs to one thread. */
internal class PlayerDataService(
    private val server: MinecraftServer,
    private val claimStore: PlayerDataStore,
    private val writeStore: PlayerDataStore,
    /** Renews leases on a thread of its own, so a burst of joins on the claim thread cannot hold renewals back. */
    private val leaseStore: PlayerDataStore,
    private val serverName: String,
    /** Server-thread time each tick may spend on queued inventory saves. One save always runs, so the queue keeps moving. */
    private val inventorySaveBudgetNanos: Long = INVENTORY_SAVE_BUDGET_NANOS,
    /** The kinds of data outside the player file that this server syncs. */
    private val extras: List<PlayerExtra> = emptyList(),
) {
    private val claimThread = singleThread("NeoPlayerSync claims")
    private val writeThread = singleThread("NeoPlayerSync saves")
    private val leaseThread = singleThread("NeoPlayerSync leases")
    private val extrasById = extras.associateBy { it.id }
    private val untrackedExtras = extras.filterNot { it.tracksChanges }.map { it.id }
    private val writer = PlayerDataWriter(writeStore, writeThread, PlayerDataMerge::travellingDigest, ::comparableExtras, ::onClaimLost)
    private val sessions = ConcurrentHashMap<UUID, PlayerDataSession>()
    /** Players waiting for an inventory save, oldest first, with when each started waiting. */
    private val inventorySaves = LinkedHashMap<UUID, Long>()
    /** Like [inventorySaves] for [PlayerDataSession.itemDataChanged], which saves only once [inventorySaves] is empty. */
    private val itemDataSaves = LinkedHashMap<UUID, Long>()
    private val urgentSaves = LinkedHashSet<UUID>()
    /** Players refused while vanilla placed them, who are disconnected at the end of the tick. */
    private val refusedAtLoad = HashSet<UUID>()
    private var lastBacklogWarningNanos: Long? = null
    private var ping: CompletableFuture<Long>? = null

    val inventorySavesWaiting: Int get() = inventorySaves.size + itemDataSaves.size
    val heldPlayers: Int get() = sessions.size
    val unsentSaves: Int get() = writer.sessionsWithUnsentWrites().size

    init {
        val heartbeatMillis = LeaseTiming.HEARTBEAT.inWholeMilliseconds
        leaseThread.scheduleWithFixedDelay(::renewLeases, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS)
        keepAlive(claimThread, claimStore)
        keepAlive(writeThread, writeStore)
    }

    fun registerLoadTask(event: RegisterConfigurationTasksEvent) {
        val listener = event.listener as ServerConfigurationPacketListenerImpl
        val client = ConfiguringClient(listener, server)
        val playerId = listener.owner.id
        event.register(
            PlayerDataLoadTask(
                playerId = playerId,
                client = client,
                store = claimStore,
                executor = claimThread,
                saveFolder = server.playerList.playerIo.playerDir.toPath(),
                fixer = server.fixerUpper,
                registries = server.registryAccess(),
                serverName = serverName,
                onAdmitted = { sessionId, claim, storedAttachments ->
                    addSession(playerId, sessionId, claim, storedAttachments) { client.connected }
                },
                extras = extras,
            ),
        )
    }

    /** Takes the whole claim, so no caller can drop the stored save that [onLoaded] reports. */
    fun addSession(
        playerId: UUID,
        sessionId: UUID,
        claim: PlayerClaim? = null,
        storedAttachments: Set<String> = emptySet(),
        connected: () -> Boolean,
    ) {
        val stored = claim?.extras ?: JsonObject()
        val unloaded = JsonObject()
        // Kinds this server does not sync stay out of memory; the database keeps them untouched.
        for (extra in extras) stored.get(extra.id)?.let(extra::unloadedAtJoin)?.let { unloaded.add(extra.id, it) }
        // A tracked kind with nothing stored yet uploads with the first save.
        val changed = extras.filter { it.tracksChanges && !stored.has(it.id) }.mapTo(HashSet()) { it.id }
        sessions[playerId] = PlayerDataSession(playerId, sessionId, connected, claim?.saved, unloaded, changed, storedAttachments)
    }

    /** A change-tracking [PlayerExtra] of [player] changed, so their next save exports it. */
    fun onExtraChanged(player: ServerPlayer, id: String) {
        sessions[player.uuid]?.changedExtras?.add(id)
    }

    /**
     * Vanilla has read the player's file. Each server counts its own game time, so this tells other mods how old the
     * save is and which clock it came from, once per join, for timers such as cooldowns.
     */
    fun onLoaded(player: ServerPlayer) {
        val session = sessions[player.uuid] ?: return
        val missing = PlayerDataMerge.missingAttachments(player, session.storedAttachments)
        // Only the first load follows the restore. A later one may come after a mod removed its own attachment.
        session.storedAttachments = emptySet()
        if (missing.isNotEmpty()) {
            refuseLoaded(session, missing)
            return
        }
        val saved = session.unshiftedSave ?: return
        session.unshiftedSave = null
        val sinceClaimMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - session.claimedAtNanos)
        player.persistentData.put(NeoPlayerSync.RESTORED_TAG, CompoundTag().apply {
            putLong(NeoPlayerSync.SAVED_GAME_TIME, saved.savedTick)
            putLong(NeoPlayerSync.ELAPSED_MILLIS, saved.elapsedMillis + sinceClaimMillis)
        })
    }

    /**
     * The player loaded without parts of their stored character, so this server stops acting for them before any save
     * can upload the loss. Vanilla is still placing them, which a disconnect must not interrupt, so that waits for [onTick].
     */
    private fun refuseLoaded(session: PlayerDataSession, missing: List<String>) {
        NeoPlayerSync.LOGGER.warn(
            "Refused {}: this server could not load the stored attachments {}. Check that every server runs the same version of the mods that own them.",
            session.playerId,
            missing,
        )
        sessions.remove(session.playerId, session)
        giveUpClaim(session)
        refusedAtLoad += session.playerId
    }

    fun onLoggedIn(player: ServerPlayer) {
        val session = sessions[player.uuid] ?: return
        session.inWorld = true
        watchInventory(player, session)
    }

    /** Respawning builds a new player with a new inventory menu, and death emptied the old one. */
    fun onRespawn(player: ServerPlayer) {
        val session = sessions[player.uuid] ?: return
        watchInventory(player, session)
        session.changedExtras += untrackedExtras
        urgentSaves += player.uuid
    }

    /**
     * The inventory listener hears nothing while another menu is open, and the ender chest has no listener.
     * A client can send close packets whenever it likes, so this marks the player for a capped save instead.
     */
    fun onContainerClosed(player: ServerPlayer) {
        sessions[player.uuid]?.inventoryChanged = true
    }

    fun onLoggedOut(player: ServerPlayer) {
        val session = sessions[player.uuid] ?: return
        session.closing = true
        session.changedExtras += untrackedExtras
    }

    /** Vanilla wrote this player's file. Every save reaches the database through here, whatever triggered it. */
    fun onSaved(event: PlayerEvent.SaveToFile) {
        val session = sessions[event.entity.uuid] ?: return
        val data = try {
            // The event's getPlayerFile refuses "dat", which it reserves for vanilla's own file.
            Files.readAllBytes(event.playerDirectory.toPath().resolve("${event.playerUUID}.dat"))
        } catch (exception: Exception) {
            NeoPlayerSync.LOGGER.error("Could not read the save of {}", session.playerId, exception)
            return
        }
        session.inventoryChanged = false
        session.itemDataChanged = false
        val extras = (event.entity as? ServerPlayer)?.let { exportExtras(it, session) } ?: JsonObject()
        session.uploads++
        writer.submit(
            PlayerWrite(session.playerId, session.sessionId, data, server.overworld().gameTime, session.closing, extras = extras),
        )
        if (session.closing) sessions.remove(session.playerId, session)
    }

    /**
     * A kind left out of the result keeps its stored copy: one not marked as changed, or one that failed to export,
     * which then stays marked so the next save tries again.
     */
    private fun exportExtras(player: ServerPlayer, session: PlayerDataSession): JsonObject {
        val exported = JsonObject()
        for (extra in extras) {
            if (extra.id !in session.changedExtras) continue
            try {
                val export = extra.export(player, session.unloadedExtras.get(extra.id))
                if (export.unloaded != null) session.unloadedExtras.add(extra.id, export.unloaded) else session.unloadedExtras.remove(extra.id)
                session.changedExtras -= extra.id
                exported.add(extra.id, export.entry)
            } catch (exception: Exception) {
                NeoPlayerSync.LOGGER.error("Could not save the {} of {}", extra.id, session.playerId, exception)
            }
        }
        return exported
    }

    /** Writer thread. Each entry as its [PlayerExtra.comparable] sees it. */
    private fun comparableExtras(exported: JsonObject): JsonObject = JsonObject().apply {
        for ((id, entry) in exported.entrySet()) add(id, extrasById[id]?.comparable(entry) ?: entry)
    }

    fun onTick(tick: Long) {
        if (tick % CHECK_INTERVAL_TICKS == 0L) checkEverySecond()
        for (session in sessions.values) {
            if (session.inWorld && isTimedSaveTick(session.playerId, tick)) {
                session.changedExtras += untrackedExtras
                urgentSaves += session.playerId
            }
        }
        // An urgent save also covers the inventory, so a second save this tick would repeat the same work.
        inventorySaves.keys -= urgentSaves
        itemDataSaves.keys -= urgentSaves
        urgentSaves.forEach(::savePlayer)
        urgentSaves.clear()
        saveQueuedInventories()
        releaseFailedLogouts()
        disconnectRefused()
    }

    private fun disconnectRefused() {
        if (refusedAtLoad.isEmpty()) return
        val refused = refusedAtLoad.toList()
        refusedAtLoad.clear()
        for (playerId in refused) server.playerList.getPlayer(playerId)?.connection?.disconnect(UNREADABLE_ATTACHMENT)
    }

    /** Spreads each player's minute-long timer over its own tick, so the timed saves don't all land at once. */
    internal fun isTimedSaveTick(playerId: UUID, tick: Long): Boolean =
        Math.floorMod(tick + playerId.hashCode(), TIMED_SAVE_TICKS) == 0L

    /**
     * Times one query on the lease thread, which owns [leaseStore]. Completes with the round trip in milliseconds.
     * Callers share a query that is still running, so repeated checks during an outage never queue up ahead of
     * heartbeats. Only the server thread calls this.
     */
    fun pingDatabase(): CompletableFuture<Long> {
        val running = ping?.takeUnless { it.isDone } ?: CompletableFuture.supplyAsync({
            val start = System.nanoTime()
            leaseStore.ping()
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        }, leaseThread).also { ping = it }
        // The timeout goes on a copy, so the shared query counts as running until the lease thread finishes it.
        return running.copy().orTimeout(COMMAND_WAIT_SECONDS, TimeUnit.SECONDS)
    }

    /**
     * Saves every player in the world now, then waits off the server thread until those saves reach the database.
     * Completes with the number of players saved, or fails if any save failed or is still waiting after [COMMAND_WAIT_SECONDS].
     */
    fun saveAllNow(): CompletableFuture<Int> {
        val sent = HashSet<UUID>()
        var failed = 0
        for (session in sessions.values) {
            if (!session.inWorld || session.closing) continue
            val before = session.uploads
            session.changedExtras += untrackedExtras
            savePlayer(session.playerId)
            if (session.uploads == before) failed++ else sent += session.sessionId
        }
        return CompletableFuture.supplyAsync({
            check(writer.awaitSent(TimeUnit.SECONDS.toMillis(COMMAND_WAIT_SECONDS)) { it in sent }) {
                "Saves were still waiting for the database after $COMMAND_WAIT_SECONDS seconds. The writer keeps retrying them."
            }
            check(failed == 0) { "$failed players could not be saved on this server. See the server log." }
            sent.size
        }, Executor { Thread(it, "NeoPlayerSync /nps sync").apply { isDaemon = true }.start() })
    }

    /** Waits for every save, which may block the stopping server thread for up to [STOP_WAIT_SECONDS]. */
    fun stop() {
        sessions.values.forEach { giveUpClaim(it) }
        sessions.clear()
        if (!writer.awaitIdle(TimeUnit.SECONDS.toMillis(STOP_WAIT_SECONDS))) {
            NeoPlayerSync.LOGGER.error(
                "Player data for sessions {} could not be saved before the server stopped. Each player's latest data is in this server's local save.",
                writer.sessionsWithUnsentWrites(),
            )
        }
        shutDown(claimThread, claimStore)
        shutDown(writeThread, writeStore)
        shutDown(leaseThread, leaseStore)
    }

    /**
     * A time budget, so a light modpack saves many players per tick and a heavy one fewer. Item data
     * goes last, so players whose charge items change every second never delay a pickup. If the budget never reaches
     * them, the timed save does.
     */
    private fun saveQueuedInventories() {
        val deadline = System.nanoTime() + inventorySaveBudgetNanos
        for (queue in arrayOf(inventorySaves, itemDataSaves)) {
            val iterator = queue.keys.iterator()
            while (iterator.hasNext()) {
                savePlayer(iterator.next())
                iterator.remove()
                if (System.nanoTime() - deadline >= 0L) return
            }
        }
    }

    private fun checkEverySecond() {
        for (session in sessions.values) {
            when {
                !session.inWorld && !session.connected() -> {
                    // The client left during configuration, so no logout event or save will follow.
                    sessions.remove(session.playerId, session)
                    giveUpClaim(session)
                }
                session.inventoryChanged -> {
                    session.inventoryChanged = false
                    session.itemDataChanged = false
                    // A player already waiting for item data moves up and keeps the time they started waiting.
                    inventorySaves.putIfAbsent(session.playerId, itemDataSaves.remove(session.playerId) ?: System.nanoTime())
                }
                session.itemDataChanged -> {
                    session.itemDataChanged = false
                    if (session.playerId !in inventorySaves) itemDataSaves.putIfAbsent(session.playerId, System.nanoTime())
                }
            }
        }
        warnIfInventorySavesLag(System.nanoTime())
    }

    /**
     * A crash loses whatever is still queued, so a growing wait must reach the logs before it reaches players. Item data
     * has no warning, because the timed save caps its wait at a minute.
     */
    private fun warnIfInventorySavesLag(now: Long) {
        val oldest = inventorySaves.values.firstOrNull() ?: return
        if (now - oldest < BACKLOG_WARNING_NANOS) return
        val lastWarning = lastBacklogWarningNanos
        if (lastWarning != null && now - lastWarning < BACKLOG_WARNING_INTERVAL_NANOS) return
        lastBacklogWarningNanos = now
        NeoPlayerSync.LOGGER.warn(
            "{} players are waiting for an inventory save, the oldest for {} seconds. A crash now would lose their latest inventory changes.",
            inventorySaves.size,
            TimeUnit.NANOSECONDS.toSeconds(now - oldest),
        )
    }

    /**
     * Wear alone waits for the next timed or final save. A gliding elytra or a mining tool changes every few ticks, and
     * saving each change would spend the inventory save budget on one player. A crash then costs at most a minute of wear.
     * Other changes that keep the item and count, such as a mod's charge, queue behind real inventory changes.
     */
    private fun watchInventory(player: ServerPlayer, session: PlayerDataSession) {
        // Vanilla hands listeners a fresh copy of each changed stack and never changes it afterwards, so this keeps it as is.
        val lastSeen = HashMap<Int, ItemStack>()
        player.inventoryMenu.addSlotListener(object : ContainerListener {
            override fun slotChanged(menu: AbstractContainerMenu, slot: Int, stack: ItemStack) {
                val before = lastSeen.put(slot, stack)
                when {
                    before == null || before.count != stack.count || !ItemStack.isSameItem(before, stack) -> session.inventoryChanged = true
                    !onlyWearChanged(before, stack) -> session.itemDataChanged = true
                }
            }

            override fun dataChanged(menu: AbstractContainerMenu, id: Int, value: Int) = Unit
        })
    }

    /** Setting a component to the item's default drops it, so a repair back to full compares equal too. */
    private fun onlyWearChanged(before: ItemStack, after: ItemStack): Boolean =
        before.count == after.count && ItemStack.isSameItemSameComponents(before, after.copy().apply { damageValue = before.damageValue })

    private fun savePlayer(playerId: UUID) {
        val player = server.playerList.getPlayer(playerId) ?: return
        server.playerList.playerIo.save(player)
    }

    /** Vanilla's save swallows its own failures and then fires no event. Without this, no server could load the player again. */
    private fun releaseFailedLogouts() {
        for (session in sessions.values) {
            if (!session.closing) continue
            NeoPlayerSync.LOGGER.error("The final save of {} failed. Their latest changes are only on this server.", session.playerId)
            sessions.remove(session.playerId, session)
            giveUpClaim(session)
        }
    }

    private fun giveUpClaim(session: PlayerDataSession) {
        writer.submit(PlayerWrite(session.playerId, session.sessionId, data = null, savedTick = 0L, release = true))
    }

    private fun renewLeases() {
        try {
            val held = sessions.values.mapTo(HashSet()) { it.sessionId } + writer.sessionsWithUnsentWrites()
            // With nobody held, a ping keeps the connection in use, so the first renewal after a quiet spell finds it alive.
            if (held.isEmpty()) leaseStore.ping() else leaseStore.heartbeat(held)
        } catch (exception: Exception) {
            // Another server takes over once the lease goes LeaseTiming.STALE without renewal, and saves keep working until then.
            NeoPlayerSync.LOGGER.warn("Could not renew player data leases", exception)
        }
    }

    /** Runs on the save thread. Another server owns this player now, so this server must stop acting for them. */
    private fun onClaimLost(playerId: UUID, sessionId: UUID) {
        NeoPlayerSync.LOGGER.error("Player {} is now held by another server. Their local save was not uploaded.", playerId)
        server.execute {
            val session = sessions[playerId]
            if (session?.sessionId != sessionId) return@execute
            sessions.remove(playerId, session)
            server.playerList.getPlayer(playerId)?.connection?.disconnect(CLAIM_LOST)
        }
    }

    /**
     * Firewalls and cloud load balancers drop a connection that stays idle for a few minutes, often without telling
     * either end. The next query then hangs until the driver's socket timeout, so an idle connection gets a ping instead.
     */
    private fun keepAlive(executor: ScheduledThreadPoolExecutor, store: PlayerDataStore) {
        executor.scheduleWithFixedDelay({
            try {
                store.ping()
            } catch (exception: Exception) {
                // The store reconnects on its next use.
                NeoPlayerSync.LOGGER.debug("NeoPlayerSync keep-alive ping failed", exception)
            }
        }, KEEP_ALIVE_SECONDS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS)
    }

    private fun shutDown(executor: ScheduledThreadPoolExecutor, store: PlayerDataStore) {
        // Only its own thread may use the connection, so that thread closes it after the queued work.
        executor.execute { store.close() }
        executor.shutdown()
        if (!executor.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)) {
            NeoPlayerSync.LOGGER.warn("A NeoPlayerSync thread did not finish within {} seconds of the server stopping.", STOP_WAIT_SECONDS)
        }
    }

    private fun singleThread(name: String) = ScheduledThreadPoolExecutor(1, ThreadFactory { task ->
        Thread(task, name).apply { isDaemon = true }
    }).apply {
        // A waiting claim or retry must not run after shutdown, or it would reopen the connection.
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    private companion object {
        const val STOP_WAIT_SECONDS = 20L
        const val COMMAND_WAIT_SECONDS = 20L
        const val CHECK_INTERVAL_TICKS = 20L
        const val TIMED_SAVE_TICKS = 1_200L

        /** Well under the shortest common idle cutoff, about four minutes on cloud load balancers. */
        const val KEEP_ALIVE_SECONDS = 60L

        /** Each save runs on the server thread, so a burst of changes spreads over several ticks. 3 ms is 6% of a tick. */
        val INVENTORY_SAVE_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(3L)
        val BACKLOG_WARNING_NANOS = TimeUnit.SECONDS.toNanos(5L)
        val BACKLOG_WARNING_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1L)

        val CLAIM_LOST: Component = Component.literal("Your character moved to another server.")
        val UNREADABLE_ATTACHMENT: Component = Component.literal(
            "Your character has data this server's mods can't read, perhaps from a newer mod version. Your data is safe; please join another server.",
        )
    }
}
