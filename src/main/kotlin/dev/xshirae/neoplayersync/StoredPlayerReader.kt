package dev.xshirae.neoplayersync

import com.mojang.datafixers.DataFixer
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtUtils
import java.sql.SQLException
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Reads stored characters for other mods without claiming them. [executor] is the only thread that uses [store].
 * Parsing and upgrading run on [decoder] instead, so a mod reading many players delays joins by query time alone.
 * Results come back through [serverThread].
 */
internal class StoredPlayerReader(
    private val store: PlayerDataStore,
    private val executor: Executor,
    private val decoder: Executor,
    private val fixer: DataFixer,
    private val serverThread: Executor,
    private val timeoutMillis: Long,
) {
    fun read(playerId: UUID): CompletableFuture<Optional<CompoundTag>> {
        val query = try {
            CompletableFuture.supplyAsync({ store.read(playerId) }, executor)
        } catch (exception: RejectedExecutionException) {
            return CompletableFuture.failedFuture(IllegalStateException("NeoPlayerSync is stopping, so $playerId cannot be read", exception))
        }
        val result = CompletableFuture<Optional<CompoundTag>>()
        // A timed-out read still finishes later, and its result is dropped.
        val decoded = query.thenApplyAsync({ decodeStored(playerId, it, fixer) }, decoder)
        decoded.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).whenCompleteAsync({ tag, failure ->
            if (failure == null) {
                result.complete(Optional.ofNullable(tag))
            } else {
                result.completeExceptionally(explainReadFailure(playerId, failure, timeoutMillis))
            }
        }, serverThread)
        return result
    }
}

/**
 * Parses and upgrades a stored row as the claim does. Null for no row or no data yet. Refuses a newer
 * `schema_version` or `DataVersion` and unreadable bytes with an [IllegalStateException], never an empty tag.
 */
internal fun decodeStored(playerId: UUID, row: StoredRow?, fixer: DataFixer): CompoundTag? {
    if (row == null) return null
    check(row.schemaVersion <= PlayerDataStore.PLAYER_DATA_SCHEMA) {
        "Player $playerId was stored with data schema ${row.schemaVersion}, newer than this build's ${PlayerDataStore.PLAYER_DATA_SCHEMA}. Update NeoPlayerSync on this server."
    }
    val data = row.data ?: return null
    val tag = try {
        PlayerDataMerge.readSave(data)
    } catch (exception: Exception) {
        throw IllegalStateException("The stored data of $playerId is unreadable", exception)
    }
    // The fixer leaves the old number in place, which would make a caller upgrade the tag a second time.
    return NbtUtils.addCurrentDataVersion(PlayerDataMerge.upgrade(tag, fixer))
}

/** Every failure becomes an [IllegalStateException] whose message says what went wrong, with the original as its cause. */
internal fun explainReadFailure(playerId: UUID, failure: Throwable, timeoutMillis: Long): Exception =
    when (val cause = if (failure is CompletionException) failure.cause ?: failure else failure) {
        is IllegalStateException -> cause
        is TimeoutException -> IllegalStateException("The database did not return $playerId within $timeoutMillis ms", cause)
        is SQLException -> IllegalStateException("Could not read $playerId from the database", cause)
        else -> IllegalStateException("Could not read the stored data of $playerId", cause)
    }
