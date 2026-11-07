package dev.xshirae.neoplayersync

import net.minecraft.SharedConstants
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.nbt.NbtUtils
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.ProblemReporter
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.storage.TagValueInput
import java.nio.file.Files

/** Uses real player saves, so a renamed vanilla key or a broken attachment fails here rather than in play. */
object PlayerDataMergeGameTests {
    private fun equippedPlayer(helper: GameTestHelper): ServerPlayer {
        val player = TestPlayers.survival(helper, "merge-full")
        player.inventory.setItem(0, ItemStack(Items.DIAMOND, 7))
        player.setItemSlot(EquipmentSlot.FEET, ItemStack(Items.IRON_BOOTS))
        player.enderChestInventory.setItem(0, ItemStack(Items.EMERALD, 3))
        player.addEffect(MobEffectInstance(MobEffects.SPEED, 1_200))
        player.experienceLevel = 30
        player.persistentData.putBoolean("marker", true)
        player.health = 7.0f
        player.setData(SyncGameTestRegistration.TEST_ATTACHMENT, 4_000)
        return player
    }

    @JvmStatic
    @SyncGameTest(template = "empty", batch = "player-data")
    fun everyTravellingKeyExistsInARealSave(helper: GameTestHelper) {
        val player = equippedPlayer(helper)
        val save = player.saveEntityForTest()

        val missing = PlayerDataMerge.TRAVELLING_KEYS.filterNot(save::contains)
        helper.assertTrue(missing.isEmpty(), "Keys missing from a real player save: $missing")
        helper.level.server.playerList.remove(player)
        helper.succeed()
    }

    /** A false alarm here would lock every player out, so a real save must pass the check. */
    @JvmStatic
    @SyncGameTest(template = "empty", batch = "player-data")
    fun aRealSaveHasNoUnreadableParts(helper: GameTestHelper) {
        val player = equippedPlayer(helper)
        player.inventory.setItem(1, ItemStack(Items.DIAMOND_SWORD).apply {
            enchant(helper.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 3)
        })
        val save = player.saveEntityForTest()

        val unreadable = PlayerDataMerge.unreadableParts(save, helper.level.registryAccess())
        helper.assertTrue(unreadable.isEmpty(), "A real save was reported unreadable: $unreadable")
        helper.level.server.playerList.remove(player)
        helper.succeed()
    }

    @JvmStatic
    @SyncGameTest(template = "empty", batch = "player-data")
    fun aMergedSaveRestoresTheCharacterAndKeepsThisServersPosition(helper: GameTestHelper) {
        val origin = equippedPlayer(helper)
        origin.teleportTo(helper.level, 100.0, 80.0, 100.0, 0.0f, 0.0f)
        val stored = origin.saveEntityForTest()

        val here = TestPlayers.survival(helper, "merge-here")
        here.teleportTo(helper.level, -50.0, 90.0, -50.0, 0.0f, 0.0f)
        val local = here.saveEntityForTest()

        val merged = PlayerDataMerge.merge(stored, local)
        here.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.level.registryAccess(), merged))

        helper.assertTrue(here.inventory.getItem(0).`is`(Items.DIAMOND) && here.inventory.getItem(0).count == 7, "Inventory should travel")
        helper.assertTrue(here.getItemBySlot(EquipmentSlot.FEET).`is`(Items.IRON_BOOTS), "Armor should travel")
        helper.assertTrue(here.enderChestInventory.getItem(0).`is`(Items.EMERALD), "The ender chest should travel")
        helper.assertTrue(here.hasEffect(MobEffects.SPEED), "Effects should travel")
        helper.assertTrue(here.experienceLevel == 30, "Vanilla XP should travel")
        helper.assertTrue(here.health == 7.0f, "Health should travel")
        val attachment = here.getData(SyncGameTestRegistration.TEST_ATTACHMENT)
        helper.assertTrue(attachment == 4_000, "Other mods' attachments should travel, was $attachment")
        helper.assertTrue(here.persistentData.getBooleanOr("marker", false), "Persistent data should travel")
        helper.assertTrue(here.x == -50.0 && here.z == -50.0, "The position should stay on this server, was ${here.x}, ${here.z}")

        helper.level.server.playerList.remove(origin)
        helper.level.server.playerList.remove(here)
        helper.succeed()
    }

    /** The timed save of an idle player is only worth skipping if its travelling part really stays the same. */
    @JvmStatic
    @SyncGameTest(template = "empty", batch = "player-data")
    fun anIdlePlayersSavesKeepTheSameDigest(helper: GameTestHelper) {
        val player = TestPlayers.survival(helper, "merge-idle")
        val playerIo = helper.level.server.playerList.playerIo
        val file = playerIo.playerDir.toPath().resolve("${player.uuid}.dat")
        // A new player's first ticks write default attributes and unlock a starter recipe, which a real join has done long ago.
        helper.runAfterDelay(20) {
            playerIo.save(player)
            val firstBytes = Files.readAllBytes(file)

            helper.runAfterDelay(20) {
                playerIo.save(player)
                val secondBytes = Files.readAllBytes(file)
                val first = PlayerDataMerge.readSave(firstBytes)
                val second = PlayerDataMerge.readSave(secondBytes)
                val changed = PlayerDataMerge.TRAVELLING_KEYS.filter { first.get(it) != second.get(it) }
                helper.assertTrue(
                    PlayerDataMerge.travellingDigest(firstBytes).contentEquals(PlayerDataMerge.travellingDigest(secondBytes)),
                    "An idle player's travelling data changed between two saves a second apart: " +
                        changed.joinToString { "$it ${first.get(it)} -> ${second.get(it)}" },
                )
                helper.level.server.playerList.remove(player)
                helper.succeed()
            }
        }
    }

    @JvmStatic
    @SyncGameTest(template = "empty", batch = "player-data")
    fun upgradeRefusesDataFromANewerGameVersion(helper: GameTestHelper) {
        val fixer = helper.level.server.fixerUpper
        val current = SharedConstants.getCurrentVersion().dataVersion().version()
        val player = TestPlayers.survival(helper, "merge-new")
        val save = player.saveEntityForTest()

        val upgraded = PlayerDataMerge.upgrade(NbtUtils.addDataVersion(save.copy(), current), fixer)
        helper.assertTrue(upgraded.contains("Inventory"), "Current data should pass through")
        val refused = runCatching { PlayerDataMerge.upgrade(NbtUtils.addDataVersion(save.copy(), current + 1), fixer) }
        helper.assertTrue(refused.isFailure, "Newer data must be refused")

        helper.level.server.playerList.remove(player)
        helper.succeed()
    }
}
