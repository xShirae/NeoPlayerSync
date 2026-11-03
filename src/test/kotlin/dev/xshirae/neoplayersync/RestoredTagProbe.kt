package dev.xshirae.neoplayersync

import net.minecraft.nbt.CompoundTag
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Listens at normal priority, the way another mod would, and records every restored tag it sees. */
@EventBusSubscriber(modid = NeoPlayerSync.MOD_ID)
object RestoredTagProbe {
    val seen = ConcurrentHashMap<UUID, MutableList<CompoundTag>>()

    @SubscribeEvent
    fun onLoaded(event: PlayerEvent.LoadFromFile) {
        val tag = event.entity.persistentData.getCompound(NeoPlayerSync.RESTORED_TAG).orElse(null) ?: return
        seen.computeIfAbsent(event.entity.uuid) { mutableListOf() } += tag.copy()
    }
}
