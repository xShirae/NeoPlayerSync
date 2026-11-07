package dev.xshirae.neoplayersync

import com.mojang.datafixers.DataFixer
import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtUtils
import net.minecraft.util.datafix.DataFixTypes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Decides which parts of a stored character replace the parts of this server's own save file. */
internal object PlayerDataMerge {
    /** A fixed list, so a new vanilla key stays on each server until someone adds it here. */
    val TRAVELLING_KEYS: Set<String> = setOf(
        "Inventory",
        "SelectedItemSlot",
        "equipment",
        "EnderItems",
        "Health",
        "AbsorptionAmount",
        "DeathTime",
        "active_effects",
        "attributes",
        "foodLevel",
        "foodTickTimer",
        "foodSaturationLevel",
        "foodExhaustionLevel",
        "XpP",
        "XpLevel",
        "XpTotal",
        "XpSeed",
        "Score",
        "recipeBook",
        "neoforge:attachments",
        "NeoForgeData",
    )

    fun readSave(bytes: ByteArray): CompoundTag =
        NbtIo.readCompressed(ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap())

    /**
     * SHA-256 of only the [TRAVELLING_KEYS] in a save. Whole saves of an idle player differ from one save to the next,
     * while the part other servers load stays the same.
     */
    fun travellingDigest(save: ByteArray): ByteArray {
        val stored = readSave(save)
        val travelling = CompoundTag()
        for (key in TRAVELLING_KEYS) stored.get(key)?.let { travelling.put(key, it) }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { NbtIo.write(travelling, it) }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
    }

    /** Refuses data from a newer game version, because vanilla cannot downgrade it. */
    fun upgrade(tag: CompoundTag, fixer: DataFixer): CompoundTag {
        val version = NbtUtils.getDataVersion(tag)
        val current = SharedConstants.getCurrentVersion().dataVersion().version()
        check(version <= current) { "Player data version $version is newer than this server's $current" }
        return DataFixTypes.PLAYER.updateToCurrentVersion(fixer, tag, version)
    }

    /** Upgrade both tags first. The merge drops a travelling key that [stored] lacks, such as an effect that ended. */
    fun merge(stored: CompoundTag, local: CompoundTag?): CompoundTag {
        val merged = local?.copy() ?: CompoundTag()
        for (key in TRAVELLING_KEYS) {
            merged.remove(key)
            stored.get(key)?.let { merged.put(key, it.copy()) }
        }
        return NbtUtils.addCurrentDataVersion(merged)
    }
}
