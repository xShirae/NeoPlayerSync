package dev.xshirae.neoplayersync

import com.mojang.logging.LogUtils
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import org.slf4j.Logger

@Mod(NeoPlayerSync.MOD_ID)
class NeoPlayerSync(modBus: IEventBus, modContainer: ModContainer) {
    init {
        // LOCAL, because the file holds a password and NeoForge sends SYNCED configs to clients.
        modContainer.registerConfig(ModConfig.Type.LOCAL, DatabaseConfig.SPEC, CONFIG_FILE)
        modBus.addListener(PlayerDataSync::registerLoadTask)
    }

    companion object {
        const val MOD_ID = "neoplayersync"
        const val CONFIG_FILE = "neoplayersync.toml"

        /**
         * Other mods read this compound during [net.neoforged.neoforge.event.entity.player.PlayerEvent.LoadFromFile]
         * after NeoPlayerSync loads the player's stored character. It holds [SAVED_GAME_TIME] and [ELAPSED_MILLIS].
         */
        const val RESTORED_TAG = "$MOD_ID:restored"

        /** The overworld game time on the server that saved the character. */
        const val SAVED_GAME_TIME = "saved_game_time"

        /** Real time since that save, including the join. The database's clock times it up to the claim. */
        const val ELAPSED_MILLIS = "elapsed_millis"

        @JvmField
        val LOGGER: Logger = LogUtils.getLogger()
    }
}
