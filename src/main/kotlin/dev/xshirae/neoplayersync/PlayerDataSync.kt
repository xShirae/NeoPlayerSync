package dev.xshirae.neoplayersync

import dev.xshirae.neoplayersync.api.NeoPlayerSyncApi
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.entity.player.AdvancementEvent
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent

/** Routes game events to the service. With the database off, there is no service and every handler does nothing. */
@EventBusSubscriber(modid = NeoPlayerSync.MOD_ID)
object PlayerDataSync {
    /** Other mods read this through the public API from any thread. */
    @Volatile
    internal var service: PlayerDataService? = null

    @SubscribeEvent
    fun onServerAboutToStart(event: ServerAboutToStartEvent) {
        DatabaseConfig.startupProblem?.let { error(it) }
        if (!DatabaseConfig.enabled()) return
        val server = event.server
        if (!server.isDedicatedServer) {
            NeoPlayerSync.LOGGER.warn("${NeoPlayerSync.CONFIG_FILE} has enabled = true, but only a dedicated server uses Postgres. Using local files.")
            return
        }
        // Startup fails here on purpose. Falling back to local files would split a player's data across servers.
        val settings = DatabaseConfig.settings()
        val claimStore = PlayerDataStore(settings::connect)
        claimStore.prepareTables()
        service = PlayerDataService(
            server = server,
            claimStore = claimStore,
            writeStore = PlayerDataStore(settings::connect),
            leaseStore = PlayerDataStore(settings::connect),
            serverName = settings.serverName.ifEmpty { server.port.toString() },
            extras = buildList {
                if (DatabaseConfig.syncAdvancements()) add(PlayerExtras.advancements(server))
                if (DatabaseConfig.syncStatistics()) add(PlayerExtras.statistics(server))
            },
        )
        NeoPlayerSync.LOGGER.info("NeoPlayerSync is storing players in Postgres.")
    }

    @SubscribeEvent
    fun onRegisterCommands(event: RegisterCommandsEvent) {
        NpsCommand.register(event.dispatcher)
    }

    internal fun registerLoadTask(event: RegisterConfigurationTasksEvent) {
        service?.registerLoadTask(event)
    }

    /** First, so the restored tag is in place for every other mod's listener. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun onLoaded(event: PlayerEvent.LoadFromFile) {
        (event.entity as? ServerPlayer)?.let { service?.onLoaded(it) }
    }

    /** Last, so the tag is gone before anything can save it into the player's file. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun afterLoaded(event: PlayerEvent.LoadFromFile) {
        event.entity.persistentData.remove(NeoPlayerSyncApi.RESTORED_TAG)
    }

    @SubscribeEvent
    fun onLoggedIn(event: PlayerEvent.PlayerLoggedInEvent) {
        (event.entity as? ServerPlayer)?.let { service?.onLoggedIn(it) }
    }

    @SubscribeEvent
    fun onRespawn(event: PlayerEvent.PlayerRespawnEvent) {
        (event.entity as? ServerPlayer)?.let { service?.onRespawn(it) }
    }

    @SubscribeEvent
    fun onContainerClosed(event: PlayerContainerEvent.Close) {
        (event.entity as? ServerPlayer)?.let { service?.onContainerClosed(it, event.container) }
    }

    @SubscribeEvent
    fun onLoggedOut(event: PlayerEvent.PlayerLoggedOutEvent) {
        (event.entity as? ServerPlayer)?.let { service?.onLoggedOut(it) }
    }

    /** Grants and revokes both arrive here, so the next save exports advancements only when they changed. */
    @SubscribeEvent
    fun onAdvancementProgress(event: AdvancementEvent.AdvancementProgressEvent) {
        (event.entity as? ServerPlayer)?.let { service?.onExtraChanged(it, PlayerExtras.ADVANCEMENTS) }
    }

    @SubscribeEvent
    fun onSaved(event: PlayerEvent.SaveToFile) {
        service?.onSaved(event)
    }

    @SubscribeEvent
    fun onServerTick(event: ServerTickEvent.Post) {
        service?.onTick(event.server.tickCount.toLong())
    }

    @SubscribeEvent
    fun onServerStopped(@Suppress("UNUSED_PARAMETER") event: ServerStoppedEvent) {
        service?.stop()
        service = null
    }
}
