package dev.xshirae.neoplayersync

import com.mojang.logging.LogUtils
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.config.ModConfig
import net.neoforged.fml.loading.FMLPaths
import org.slf4j.Logger

@Mod(NeoPlayerSync.MOD_ID)
class NeoPlayerSync(modBus: IEventBus, modContainer: ModContainer) {
    init {
        // NeoForge opens this config type after mod construction, so the file is still the operator's own.
        DatabaseConfig.startupProblem = DatabaseConfig.fileProblem(FMLPaths.CONFIGDIR.get().resolve(CONFIG_FILE))
        modContainer.registerConfig(localConfigType(), DatabaseConfig.SPEC, CONFIG_FILE)
        modBus.addListener(PlayerDataSync::registerLoadTask)
    }

    companion object {
        const val MOD_ID = "neoplayersync"
        const val CONFIG_FILE = "neoplayersync.toml"

        @JvmField
        val LOGGER: Logger = LogUtils.getLogger()

        /**
         * The config file holds a password, so it needs a type NeoForge never sends to clients. The 26.3 loader calls
         * that type LOCAL, and the 26.1 and 26.2 loaders call it COMMON. Looking it up by name lets one source build
         * for all three.
         */
        internal fun localConfigType(): ModConfig.Type =
            ModConfig.Type.values().firstOrNull { it.name == "LOCAL" } ?: ModConfig.Type.valueOf("COMMON")
    }
}
