package dev.xshirae.neoplayersync

import net.minecraft.SharedConstants
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.nbt.NbtUtils
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PlayerDataMergeTest {
    private fun tag(vararg values: Pair<String, Int>) = CompoundTag().apply {
        values.forEach { (key, value) -> putInt(key, value) }
    }

    @Test
    fun `travelling keys come from the stored character`() {
        val merged = PlayerDataMerge.merge(tag("XpLevel" to 30, "Health" to 7), tag("XpLevel" to 1, "Health" to 20))
        assertEquals(30, merged.getIntOr("XpLevel", -1))
        assertEquals(7, merged.getIntOr("Health", -1))
    }

    @Test
    fun `everything else stays on this server`() {
        val merged = PlayerDataMerge.merge(
            tag("Dimension" to 1, "playerGameType" to 1, "respawn" to 1, "UnknownNewKey" to 1),
            tag("Dimension" to 2, "playerGameType" to 2, "respawn" to 2),
        )
        assertEquals(2, merged.getIntOr("Dimension", -1))
        assertEquals(2, merged.getIntOr("playerGameType", -1))
        assertEquals(2, merged.getIntOr("respawn", -1))
        assertFalse(merged.contains("UnknownNewKey"))
    }

    @Test
    fun `a travelling key missing from the stored character is removed`() {
        val merged = PlayerDataMerge.merge(tag("XpLevel" to 1), tag("active_effects" to 1, "equipment" to 1))
        assertFalse(merged.contains("active_effects"), "an effect that ended must not come back")
        assertFalse(merged.contains("equipment"), "armor that was removed must not come back")
    }

    @Test
    fun `a first visit has no position so the player gets a normal spawn`() {
        val merged = PlayerDataMerge.merge(CompoundTag().apply {
            putInt("XpLevel", 5)
            putInt("Pos", 1)
            putInt("Dimension", 1)
            putInt("Rotation", 1)
        }, null)
        assertEquals(5, merged.getIntOr("XpLevel", -1))
        listOf("Pos", "Dimension", "Rotation").forEach { assertFalse(merged.contains(it), "$it must not travel") }
    }

    @Test
    fun `the merged file has the current data version`() {
        val merged = PlayerDataMerge.merge(tag("XpLevel" to 1), NbtUtils.addDataVersion(CompoundTag(), 1))
        assertEquals(SharedConstants.getCurrentVersion().dataVersion().version(), NbtUtils.getDataVersion(merged))
    }

    @Test
    fun `merging does not change its inputs`() {
        val stored = tag("XpLevel" to 9)
        val local = tag("XpLevel" to 1)
        PlayerDataMerge.merge(stored, local)
        assertEquals(1, local.getIntOr("XpLevel", -1))
        assertEquals(1, stored.size())
    }

    @Test
    fun `saved bytes round trip and damaged bytes are refused`() {
        val original = tag("XpLevel" to 12)
        val bytes = ByteArrayOutputStream().also { NbtIo.writeCompressed(original, it) }.toByteArray()
        assertEquals(12, PlayerDataMerge.readSave(bytes).getIntOr("XpLevel", -1))
        assertFailsWith<Exception> { PlayerDataMerge.readSave(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun `the digest follows only the keys that travel`() {
        fun digest(vararg values: Pair<String, Int>) = PlayerDataMerge.travellingDigest(
            ByteArrayOutputStream().also { NbtIo.writeCompressed(tag(*values), it) }.toByteArray(),
        )
        val idle = digest("XpLevel" to 5, "Health" to 20, "Pos" to 1)
        assertContentEquals(idle, digest("XpLevel" to 5, "Health" to 20, "Pos" to 2), "a key that stays on each server must not count")
        assertFalse(idle.contentEquals(digest("XpLevel" to 6, "Health" to 20, "Pos" to 1)), "a travelling key must count")
    }
}
