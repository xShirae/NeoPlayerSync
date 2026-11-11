package dev.xshirae.neoplayersync.api

import dev.xshirae.neoplayersync.PlayerDataSync
import net.minecraft.nbt.CompoundTag
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * NeoPlayerSync's public API for other mods. Only this package and the restored tag it names are public; everything
 * else may change in any release.
 *
 * Guard every call with `ModList.get().isLoaded("neoplayersync")`, and keep the calls in a class that only loads when
 * that check passes, so a mod that soft-depends on NeoPlayerSync still runs without it.
 */
object NeoPlayerSyncApi {
    /**
     * Raised only when a change to this API would break a mod built against an older one. Separate from the database
     * schema. Not a `const`, so a mod reads the installed version at runtime instead of the one it compiled against.
     */
    @JvmField
    val API_VERSION: Int = 1

    /**
     * A compound in the player's persistent data while [net.neoforged.neoforge.event.entity.player.PlayerEvent.LoadFromFile]
     * runs, after NeoPlayerSync loads the character from the database. It holds [SAVED_GAME_TIME] and [ELAPSED_MILLIS].
     * These names are compile-time constants, so using them never loads NeoPlayerSync's classes.
     */
    const val RESTORED_TAG: String = "neoplayersync:restored"

    /** The overworld game time on the server that saved the character. */
    const val SAVED_GAME_TIME: String = "saved_game_time"

    /** Real time since that save, including the join, in milliseconds. The database's clock times it up to the claim. */
    const val ELAPSED_MILLIS: String = "elapsed_millis"

    /**
     * True while this server stores players in Postgres. False with `enabled = false`, on singleplayer and LAN, and before
     * the server starts or after it stops. When false, read the player from the local `playerdata` files instead.
     */
    @JvmStatic
    fun isDatabaseEnabled(): Boolean = PlayerDataSync.service != null

    /**
     * Reads the character the database holds for [playerId], without claiming it: the player can still join any server,
     * and the server that holds them keeps saving. The result is the player's `.dat` contents, upgraded to this game
     * version like vanilla's `PlayerDataStorage.load`, with `DataVersion` set to this game's version. Position and other
     * per-server keys come from the server that last saved. Changing the tag changes nothing stored.
     *
     * The result is empty when the database holds no character for the player yet. A player who has not joined since
     * the network turned sync on may still have one in a server's local `playerdata` file, and the caller decides
     * whether to look there. A player online on another server may have changes from the last minute that are not
     * stored yet; read an online player on this server directly.
     *
     * Each read takes a database round trip on the thread that also claims joining players. Read one player when
     * someone asks; a scan of every stored player would hold up joins.
     *
     * Call from the server thread. The future then completes on the server thread in a later task, so callbacks you add
     * right away run there too. With the database off it fails at once (see [isDatabaseEnabled]).
     *
     * It fails with an [IllegalStateException] that says why, never an empty tag, when: the database is off or the server is not running, the row comes from a newer
     * NeoPlayerSync or Minecraft, the data is unreadable, or the database fails or does not answer within 20 seconds.
     */
    @JvmStatic
    fun readStoredPlayer(playerId: UUID): CompletableFuture<Optional<CompoundTag>> =
        PlayerDataSync.service?.readStoredPlayer(playerId)
            ?: CompletableFuture.failedFuture(IllegalStateException("NeoPlayerSync is not storing players in Postgres on this server"))
}
