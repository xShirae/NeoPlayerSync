package dev.xshirae.neoplayersync

import com.mojang.datafixers.DataFixer
import net.minecraft.core.HolderLookup
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
    private val registries: HolderLookup.Provider,
    private val serverName: String,
    private val waitMillis: Long = LeaseTiming.WAIT.inWholeMilliseconds,
    private val staleMillis: Long = LeaseTiming.STALE.inWholeMilliseconds,
    /** [storedAttachments] names the attachment types in the stored character, which vanilla must load in full. */
    private val onAdmitted: (sessionId: UUID, claim: PlayerClaim, storedAttachments: Set<String>) -> Unit = { _, _, _ -> },
    private val extras: List<PlayerExtra> = emptyList(),
) : ICustomConfigurationTask {
    private val sessionId: UUID = UUID.randomUUID()

    // Only attemptClaim uses these, and it runs on the single claims thread.
    private var watchedLease: Long? = null
    private var watchedSinceNanos = 0L
    private var failedBefore = false

    override fun type(): ConfigurationTask.Type = TYPE

    override fun run(sender: Consumer<CustomPacketPayload>) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis)
        executor.execute { attemptClaim(deadline) }
    }

    private fun attemptClaim(deadline: Long) {
        if (!client.connected) return
        val claim = try {
            store.tryClaim(playerId, sessionId, serverName, staleLease()).also { if (it.claim == null) watch(it.heldLease) }.claim
        } catch (exception: Exception) {
            // A dropped connection or a database restart often clears within the wait, and the store reconnects on the next try.
            if (System.nanoTime() - deadline >= 0L) {
                NeoPlayerSync.LOGGER.error("Could not claim player data for {}", playerId, exception)
                client.refuse(LOAD_FAILED)
            } else {
                if (!failedBefore) NeoPlayerSync.LOGGER.warn("Could not claim player data for {}. Retrying.", playerId, exception)
                failedBefore = true
                // The holder could not renew while the database was away either, so the watch starts over once it is back.
                watchedLease = null
                tryAgain(deadline)
            }
            return
        }
        when {
            claim != null -> restore(claim)
            System.nanoTime() - deadline >= 0L -> {
                NeoPlayerSync.LOGGER.warn("Player {} is still held by another server after {} ms", playerId, waitMillis)
                client.refuse(STILL_HELD)
            }
            else -> tryAgain(deadline)
        }
    }

    private fun tryAgain(deadline: Long) {
        executor.schedule({ attemptClaim(deadline) }, POLL_MILLIS, TimeUnit.MILLISECONDS)
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
        val storedAttachments = try {
            check(claim.schemaVersion <= PlayerDataStore.PLAYER_DATA_SCHEMA) {
                "Player data schema ${claim.schemaVersion} is newer than this build's ${PlayerDataStore.PLAYER_DATA_SCHEMA}"
            }
            val attachments = claim.saved?.let { writeLocalFile(it.data) }.orEmpty()
            // A kind with nothing stored yet keeps this server's own copy, which the first save then uploads.
            for (extra in extras) claim.extras.get(extra.id)?.let { extra.restore(playerId, it) }
            attachments
        } catch (exception: UnreadableCharacter) {
            NeoPlayerSync.LOGGER.warn("Refused {}: {}", playerId, exception.message)
            release()
            client.refuse(MISSING_MOD)
            return
        } catch (exception: Exception) {
            NeoPlayerSync.LOGGER.error("Could not load player data for {}", playerId, exception)
            release()
            client.refuse(LOAD_FAILED)
            return
        }
        client.admit(
            beforeContinuing = { onAdmitted(sessionId, claim, storedAttachments) },
            ifGone = { executor.execute { release() } },
        )
    }

    /** Returns the attachment types of the stored character. */
    private fun writeLocalFile(data: ByteArray): Set<String> {
        val stored = PlayerDataMerge.upgrade(PlayerDataMerge.readSave(data), fixer)
        val unreadable = PlayerDataMerge.unreadableParts(stored, registries)
        if (unreadable.isNotEmpty()) throw UnreadableCharacter(unreadable)
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
        return PlayerDataMerge.storedAttachments(stored)
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
        private const val POLL_MILLIS = 250L

        val LOAD_FAILED: Component = Component.literal("Your character could not be loaded. Your data is safe; please try again later.")
        val STILL_HELD: Component = Component.literal("Your character is still being saved by another server. Please try again.")
        val MISSING_MOD: Component = Component.literal(
            "Your character has items or data from a mod this server doesn't have. Your data is safe; please join another server.",
        )
    }
}

/** The stored character holds parts this server cannot load, so admitting the player would lose them. */
private class UnreadableCharacter(parts: List<String>) : Exception(
    "this server cannot load ${parts.size} part(s) of the character: ${parts.take(10).joinToString("; ")}",
)
