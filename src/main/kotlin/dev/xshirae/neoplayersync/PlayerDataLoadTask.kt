package dev.xshirae.neoplayersync

import com.mojang.datafixers.DataFixer
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ConfigurationTask
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl
import net.minecraft.util.Util
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

/** The connection of a player who is still joining. It separates the join logic from vanilla's packet listener, so tests can run that logic. */
internal interface JoiningClient {
    /** Safe to read from any thread. */
    val connected: Boolean

    /** On the server thread, runs [beforeContinuing] and lets the join continue, or runs [ifGone] if the client has left. */
    fun admit(beforeContinuing: () -> Unit, ifGone: () -> Unit)

    fun refuse(reason: Component)
}

internal class ConfiguringClient(
    private val listener: ServerConfigurationPacketListenerImpl,
    private val server: MinecraftServer,
) : JoiningClient {
    override val connected: Boolean get() = listener.isAcceptingMessages

    override fun admit(beforeContinuing: () -> Unit, ifGone: () -> Unit) {
        server.execute {
            if (listener.isAcceptingMessages) {
                beforeContinuing()
                listener.finishCurrentTask(PlayerDataLoadTask.TYPE)
            } else {
                ifGone()
            }
        }
    }

    override fun refuse(reason: Component) {
        server.execute { listener.disconnect(reason) }
    }
}

/**
 * Holds a joining player until their row is claimed and their character is in the local save file, which vanilla reads
 * next in PrepareSpawnTask. Any failure disconnects them and leaves that file alone, because vanilla treats an unreadable
 * file as a new player, and the next save would overwrite the real character.
 */
internal class PlayerDataLoadTask(
    private val playerId: UUID,
    private val client: JoiningClient,
    private val store: PlayerDataStore,
    private val executor: ScheduledExecutorService,
    private val saveFolder: Path,
    private val fixer: DataFixer,
    private val serverName: String,
    private val waitMillis: Long = WAIT_MILLIS,
    private val staleMillis: Long = STALE_MILLIS,
    private val onAdmitted: (sessionId: UUID, claim: PlayerClaim) -> Unit = { _, _ -> },
) : ICustomConfigurationTask {
    private val sessionId: UUID = UUID.randomUUID()

    // Only touched by attemptClaim, which runs on the single claims thread.
    private var watchedLease: Long? = null
    private var watchedSinceNanos = 0L

    override fun type(): ConfigurationTask.Type = TYPE

    override fun run(sender: Consumer<CustomPacketPayload>) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis)
        executor.execute { attemptClaim(deadline) }
    }

    private fun attemptClaim(deadline: Long) {
        if (!client.connected) return
        val claim = try {
            store.claim(playerId, sessionId, serverName, staleLease()).also { if (it == null) watch(store.leaseOf(playerId)) }
        } catch (exception: Exception) {
            NeoPlayerSync.LOGGER.error("Could not claim player data for {}", playerId, exception)
            client.refuse(LOAD_FAILED)
            return
        }
        when {
            claim != null -> restore(claim)
            System.nanoTime() - deadline >= 0L -> {
                NeoPlayerSync.LOGGER.warn("Player {} is still held by another server after {} ms", playerId, waitMillis)
                client.refuse(STILL_HELD)
            }
            else -> executor.schedule({ attemptClaim(deadline) }, POLL_MILLIS, TimeUnit.MILLISECONDS)
        }
    }

    /** The holder's lease once it has gone [staleMillis] without a renewal, which only a stopped server fails to do. */
    private fun staleLease(): Long? =
        watchedLease?.takeIf { System.nanoTime() - watchedSinceNanos >= TimeUnit.MILLISECONDS.toNanos(staleMillis) }

    private fun watch(lease: Long?) {
        if (lease == watchedLease) return
        watchedLease = lease
        watchedSinceNanos = System.nanoTime()
    }

    private fun restore(claim: PlayerClaim) {
        try {
            check(claim.schemaVersion <= PlayerDataStore.PLAYER_DATA_SCHEMA) {
                "Player data schema ${claim.schemaVersion} is newer than this build's ${PlayerDataStore.PLAYER_DATA_SCHEMA}"
            }
            claim.saved?.let { writeLocalFile(it.data) }
        } catch (exception: Exception) {
            NeoPlayerSync.LOGGER.error("Could not load player data for {}", playerId, exception)
            release()
            client.refuse(LOAD_FAILED)
            return
        }
        client.admit(
            beforeContinuing = { onAdmitted(sessionId, claim) },
            ifGone = { executor.execute { release() } },
        )
    }

    private fun writeLocalFile(data: ByteArray) {
        val stored = PlayerDataMerge.upgrade(PlayerDataMerge.readSave(data), fixer)
        val file = saveFolder.resolve("$playerId.dat")
        val merged = PlayerDataMerge.merge(stored, readLocalFile(file))

        val temporary = Files.createTempFile(saveFolder, "$playerId-", ".dat")
        try {
            NbtIo.writeCompressed(merged, temporary)
            // Vanilla's safeReplaceFile hides a failed swap, which would load the old character.
            check(Util.safeReplaceOrMoveFile(file, temporary, saveFolder.resolve("$playerId.dat_old"), false)) {
                "Could not replace $file"
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /** Any problem with the local file only costs this server's position, because the character comes from the database. */
    private fun readLocalFile(file: Path): CompoundTag? {
        if (!Files.isRegularFile(file)) return null
        return try {
            PlayerDataMerge.upgrade(NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()), fixer)
        } catch (exception: Exception) {
            NeoPlayerSync.LOGGER.warn("Ignoring unusable local save {}", file, exception)
            null
        }
    }

    private fun release() {
        try {
            store.release(playerId, sessionId)
        } catch (exception: Exception) {
            // The lease expires on its own, because nothing renews this session.
            NeoPlayerSync.LOGGER.warn("Could not release player data for {}", playerId, exception)
        }
    }

    companion object {
        val TYPE = ConfigurationTask.Type("${NeoPlayerSync.MOD_ID}:load")

        /**
         * Longer than both the 15-second lease and [STALE_MILLIS], so a crashed server's claim is always taken over in
         * time: the watch starts with the first attempt, and the lease runs out at most 15 seconds after it.
         */
        private const val WAIT_MILLIS = 20_000L

        /**
         * Two 5-second heartbeats plus room for a slow reconnect. A running server renews within this time once the
         * database is back, even if every lease expired during the outage, so its waiting save is never shut out.
         */
        private const val STALE_MILLIS = 12_000L
        private const val POLL_MILLIS = 250L

        val LOAD_FAILED: Component = Component.literal("Your character could not be loaded. Your data is safe; please try again later.")
        val STILL_HELD: Component = Component.literal("Your character is still being saved by another server. Please try again.")
    }
}
