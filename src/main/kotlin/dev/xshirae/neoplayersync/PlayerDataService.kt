package dev.xshirae.neoplayersync

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
import java.util.concurrent.ConcurrentHashMap
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
) {
    /** When the claim returned, so time spent finishing the join also counts against cooldowns. */
    val claimedAtNanos: Long = System.nanoTime()
    var inWorld = false
    var closing = false
    var inventoryChanged = false
}

/** Everything that exists only while a dedicated server is using Postgres. Each store belongs to one thread. */
internal class PlayerDataService(
    private val server: MinecraftServer,
    private val claimStore: PlayerDataStore,
    private val writeStore: PlayerDataStore,
    private val serverName: String,
    /** Server-thread time each tick may spend on queued inventory saves. One save always runs, so the queue keeps moving. */
    private val inventorySaveBudgetNanos: Long = INVENTORY_SAVE_BUDGET_NANOS,
) {
    private val claimThread = singleThread("NeoPlayerSync claims")
    private val writeThread = singleThread("NeoPlayerSync saves")
    private val writer = PlayerDataWriter(writeStore, writeThread, PlayerDataMerge::travellingDigest, ::onClaimLost)
    private val sessions = ConcurrentHashMap<UUID, PlayerDataSession>()
    /** Players waiting for an inventory save, oldest first, with when each started waiting. */
    private val inventorySaves = LinkedHashMap<UUID, Long>()
    private val urgentSaves = LinkedHashSet<UUID>()
    private var lastBacklogWarningNanos: Long? = null

    val inventorySavesWaiting: Int get() = inventorySaves.size

    init {
        claimThread.scheduleWithFixedDelay(::renewLeases, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS)
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
                serverName = serverName,
                onAdmitted = { sessionId, claim -> addSession(playerId, sessionId, claim) { client.connected } },
            ),
        )
    }

    /** Takes the whole claim, so no caller can drop the stored save that [onLoaded] reports. */
    fun addSession(playerId: UUID, sessionId: UUID, claim: PlayerClaim? = null, connected: () -> Boolean) {
        sessions[playerId] = PlayerDataSession(playerId, sessionId, connected, claim?.saved)
    }

    /**
     * Vanilla has read the player's file. Each server counts its own game time, so this tells other mods how old the
     * save is and which clock it came from, once per join, for timers such as cooldowns.
     */
    fun onLoaded(player: ServerPlayer) {
        val session = sessions[player.uuid] ?: return
        val saved = session.unshiftedSave ?: return
        session.unshiftedSave = null
        val sinceClaimMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - session.claimedAtNanos)
        player.persistentData.put(NeoPlayerSync.RESTORED_TAG, CompoundTag().apply {
            putLong(NeoPlayerSync.SAVED_GAME_TIME, saved.savedTick)
            putLong(NeoPlayerSync.ELAPSED_MILLIS, saved.elapsedMillis + sinceClaimMillis)
        })
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
        sessions[player.uuid]?.closing = true
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
        writer.submit(PlayerWrite(session.playerId, session.sessionId, data, server.overworld().gameTime, session.closing))
        if (session.closing) sessions.remove(session.playerId, session)
    }

    fun onTick(tick: Long) {
        if (tick % CHECK_INTERVAL_TICKS == 0L) checkEverySecond()
        for (session in sessions.values) {
            if (session.inWorld && isTimedSaveTick(session.playerId, tick)) {
                urgentSaves += session.playerId
            }
        }
        // An urgent save also covers the inventory, so a second save this tick would repeat the same work.
        inventorySaves.keys -= urgentSaves
        urgentSaves.forEach(::savePlayer)
        urgentSaves.clear()

        // A time budget rather than a count, so a light modpack saves many players per tick and a heavy one fewer.
        val deadline = System.nanoTime() + inventorySaveBudgetNanos
        val iterator = inventorySaves.keys.iterator()
        while (iterator.hasNext()) {
            savePlayer(iterator.next())
            iterator.remove()
            if (System.nanoTime() - deadline >= 0L) break
        }
        releaseFailedLogouts()
    }

    /** Spreads each player's minute-long timer over its own tick, so the timed saves don't all land at once. */
    internal fun isTimedSaveTick(playerId: UUID, tick: Long): Boolean =
        Math.floorMod(tick + playerId.hashCode(), TIMED_SAVE_TICKS) == 0L

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
                    inventorySaves.putIfAbsent(session.playerId, System.nanoTime())
                }
            }
        }
        warnIfInventorySavesLag(System.nanoTime())
    }

    /** A crash loses whatever is still queued, so a growing wait must reach the logs before it reaches players. */
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

    private fun watchInventory(player: ServerPlayer, session: PlayerDataSession) {
        player.inventoryMenu.addSlotListener(object : ContainerListener {
            override fun slotChanged(menu: AbstractContainerMenu, slot: Int, stack: ItemStack) {
                session.inventoryChanged = true
            }

            override fun dataChanged(menu: AbstractContainerMenu, id: Int, value: Int) = Unit
        })
    }

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
            claimStore.heartbeat(sessions.values.mapTo(HashSet()) { it.sessionId } + writer.sessionsWithUnsentWrites())
        } catch (exception: Exception) {
            // Another server takes over after the lease goes 12 seconds without renewal, and saves keep working until then.
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
        const val HEARTBEAT_SECONDS = 5L
        const val STOP_WAIT_SECONDS = 20L
        const val CHECK_INTERVAL_TICKS = 20L
        const val TIMED_SAVE_TICKS = 1_200L

        /** Each save runs on the server thread, so a burst of changes waits rather than causing lag. 3 ms is 6% of a tick. */
        val INVENTORY_SAVE_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(3L)
        val BACKLOG_WARNING_NANOS = TimeUnit.SECONDS.toNanos(5L)
        val BACKLOG_WARNING_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1L)

        val CLAIM_LOST: Component = Component.literal("Your character moved to another server.")
    }
}
