package dev.xshirae.neoplayersync

import com.mojang.datafixers.DataFixer
import com.mojang.serialization.Codec
import net.minecraft.SharedConstants
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.NbtUtils
import net.minecraft.nbt.Tag
import net.minecraft.resources.Identifier
import net.minecraft.util.datafix.DataFixTypes
import net.minecraft.world.ItemStackWithSlot
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.ai.attributes.AttributeInstance
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.attachment.IAttachmentHolder
import net.neoforged.neoforge.registries.NeoForgeRegistries
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
        ATTACHMENTS,
        "NeoForgeData",
    )

    private const val ATTACHMENTS = "neoforge:attachments"

    fun readSave(bytes: ByteArray): CompoundTag =
        NbtIo.readCompressed(ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap())

    /**
     * SHA-256 of the [TRAVELLING_KEYS] in a save and nothing else. Whole saves of an idle player differ from one save to the next,
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

    /**
     * Each part of an upgraded [save] that this server would drop on load, such as an item or attachment from a mod it
     * lacks. Vanilla throws away all armor or all effects when one entry fails, and the next save would upload the loss.
     */
    fun unreadableParts(save: CompoundTag, registries: HolderLookup.Provider): List<String> {
        val ops = registries.createSerializationContext(NbtOps.INSTANCE)
        val problems = mutableListOf<String>()
        fun check(key: String, entry: Tag, codec: Codec<*>) {
            codec.parse(ops, entry).error().ifPresent { problems += "$key: ${it.message()}" }
        }
        save.getListOrEmpty("Inventory").forEach { check("Inventory", it, ItemStackWithSlot.CODEC) }
        save.getListOrEmpty("EnderItems").forEach { check("EnderItems", it, ItemStackWithSlot.CODEC) }
        save.getCompoundOrEmpty("equipment").let { equipment ->
            equipment.keySet().forEach { slot -> equipment.get(slot)?.let { check("equipment", it, ItemStack.CODEC) } }
        }
        save.getListOrEmpty("attributes").forEach { check("attributes", it, AttributeInstance.Packed.CODEC) }
        save.getListOrEmpty("active_effects").forEach { check("active_effects", it, MobEffectInstance.CODEC) }

        val known = NeoForgeRegistries.ATTACHMENT_TYPES.keySet().mapTo(HashSet()) { it.toString() }
        save.getCompoundOrEmpty(ATTACHMENTS).keySet().filterNot(known::contains).forEach { problems += "$ATTACHMENTS: unknown type $it" }
        return problems
    }

    /** The attachment types an upgraded [save] holds. */
    fun storedAttachments(save: CompoundTag): Set<String> = save.getCompoundOrEmpty(ATTACHMENTS).keySet().toSet()

    /**
     * Each of [stored] that [player] lacks right after loading. NeoForge logs an attachment it cannot read and skips it,
     * for example one written by a newer version of its mod, and the next save would upload the character without it.
     */
    fun missingAttachments(player: IAttachmentHolder, stored: Set<String>): List<String> = stored.filter { key ->
        val type = Identifier.tryParse(key)?.let(NeoForgeRegistries.ATTACHMENT_TYPES::getValue)
        type == null || !player.hasData(type)
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
