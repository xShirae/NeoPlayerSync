package dev.xshirae.neoplayersync

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.entity.Relative
import net.minecraft.world.level.GameType
import java.util.UUID

/** Uses a normal logged-in ServerPlayer, so vanilla's login, save and logout paths all run. */
object TestPlayers {
    fun survival(helper: GameTestHelper, name: String): ServerPlayer {
        val id = UUID.randomUUID()
        val profileName = name.filter { it.isLetterOrDigit() }.take(9) + id.toString().replace("-", "").take(7)
        val profile = GameProfile(id, profileName)
        val cookie = CommonListenerCookie.createInitial(profile, false)
        val player = ServerPlayer(helper.level.server, helper.level, profile, cookie.clientInformation())
        val connection = Connection(PacketFlow.SERVERBOUND)
        EmbeddedChannel(connection)
        helper.level.server.playerList.placeNewPlayer(connection, player, cookie)
        // The embedded test client does not send vanilla's post-load acknowledgement itself.
        ServerboundPlayerLoadedPacket().handle(player.connection)
        player.setGameMode(GameType.SURVIVAL)
        return player
    }
}

fun ServerPlayer.teleportTo(
    level: ServerLevel,
    x: Double,
    y: Double,
    z: Double,
    yRot: Float,
    xRot: Float,
): Boolean = teleportTo(level, x, y, z, emptySet<Relative>(), yRot, xRot, true)
