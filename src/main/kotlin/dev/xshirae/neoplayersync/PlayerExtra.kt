package dev.xshirae.neoplayersync

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.SharedConstants
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Player data that lives outside the player's save file. Each kind travels as one entry of the row's extra column,
 * under the same claim as the character, so the lease guards it too.
 */
internal interface PlayerExtra {
    /** The entry's name in the stored bundle. Stored data depends on it, so never rename one. */
    val id: String

    /**
     * True when every change reaches [PlayerDataService.onExtraChanged], so saves without one skip this kind and leave
     * the stored copy as it is. False exports it with every save.
     */
    val tracksChanges: Boolean

    /**
     * Claim thread, before the game creates the player: puts [stored] where the game will read it. Throw to refuse the
     * join. A kind that keeps its data in live game state can hand that work to `server.execute`, which runs before the
     * join continues.
     */
    fun restore(playerId: UUID, stored: JsonElement)

    /**
     * Server thread, while the game saves [player]: their current data. [unloaded] is what this server could not load,
     * from the stored entry or the last export's [ExtraExport.unloaded]; keep it, so the upload does not erase it.
     */
    fun export(player: ServerPlayer, unloaded: JsonElement?): ExtraExport

    /**
     * Server thread, as the player joins: the part of [stored] this server cannot load, which is all a session keeps
     * until the first export. Null when it can load everything.
     */
    fun unloadedAtJoin(stored: JsonElement): JsonElement?

    /**
     * Writer thread: what of [entry] must change before a save is worth sending. Leaves [entry] unchanged, and leaves
     * out counters that move on their own, so they travel only with a real change or the final save.
     */
    fun comparable(entry: JsonElement): JsonElement = entry
}

/** [entry] goes to the database. [unloaded] is the part kept from before, which the next export must keep again. */
internal class ExtraExport(val entry: JsonElement, val unloaded: JsonElement?)

internal object PlayerExtras {
    const val ADVANCEMENTS = "advancements"

    fun advancements(server: MinecraftServer) = JsonFileExtra(
        id = ADVANCEMENTS,
        // PlayerDataSync reports each grant and revoke.
        tracksChanges = true,
        folder = server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR),
        saveFile = { it.advancements.save() },
        keepUnloaded = { stored, current ->
            keepUnknownAdvancements(stored, current) { id -> Identifier.tryParse(id)?.let(server.advancements::get) != null }
        },
    )

    fun statistics(server: MinecraftServer) = JsonFileExtra(
        id = "statistics",
        // Statistics change all the time, and no event reports every counter, so each save exports them.
        tracksChanges = false,
        folder = server.getWorldPath(LevelResource.PLAYER_STATS_DIR),
        saveFile = { it.stats.save() },
        keepUnloaded = ::keepMissingStatistics,
        comparablePart = ::withoutRunningCounters,
    )

    /**
     * [statistics] without the counters that move while a player only stands or walks around: time and distance. A
     * player whose other data did not change is then not sent at all. Shares everything else with [statistics].
     */
    fun withoutRunningCounters(statistics: JsonObject): JsonObject {
        val custom = statistics.getAsJsonObject(STATS)?.getAsJsonObject(CUSTOM) ?: return statistics
        val kept = JsonObject()
        for ((key, count) in custom.entrySet()) if (!isRunningCounter(key)) kept.add(key, count)
        val types = JsonObject()
        for ((type, counters) in statistics.getAsJsonObject(STATS).entrySet()) types.add(type, if (type == CUSTOM) kept else counters)
        return JsonObject().apply {
            for ((key, value) in statistics.entrySet()) add(key, if (key == STATS) types else value)
        }
    }

    /** Vanilla counts distances in centimetres, one counter per way of moving, and new ways keep the "_one_cm" ending. */
    private fun isRunningCounter(key: String): Boolean = key in RUNNING_TIMES || key.endsWith("_one_cm")

    /**
     * Copies each stored advancement this server does not have, and returns those copies. One it has stays as the
     * player has it now, so a revoke on this server sticks.
     */
    fun keepUnknownAdvancements(stored: JsonObject, current: JsonObject, exists: (String) -> Boolean): JsonObject {
        val kept = JsonObject()
        for ((id, progress) in stored.entrySet()) {
            if (id != DATA_VERSION && !current.has(id) && !exists(id)) kept.add(id, progress)
        }
        for ((id, progress) in kept.entrySet()) current.add(id, progress)
        return kept
    }

    /**
     * Statistics only ever gain counters, so one stored but missing now is one this server could not load. Copies those
     * and returns them, in the same layout as the file.
     */
    fun keepMissingStatistics(stored: JsonObject, current: JsonObject): JsonObject {
        val kept = JsonObject()
        val storedTypes = stored.getAsJsonObject(STATS) ?: return kept
        val currentTypes = current.getAsJsonObject(STATS) ?: JsonObject().also { current.add(STATS, it) }
        val keptTypes = JsonObject()
        for ((type, counters) in storedTypes.entrySet()) {
            val currentCounters = currentTypes.getAsJsonObject(type) ?: JsonObject()
            val missing = JsonObject()
            for ((key, count) in counters.asJsonObject.entrySet()) {
                if (!currentCounters.has(key)) missing.add(key, count)
            }
            if (missing.size() == 0) continue
            keptTypes.add(type, missing)
            if (!currentTypes.has(type)) currentTypes.add(type, currentCounters)
            for ((key, count) in missing.entrySet()) currentCounters.add(key, count)
        }
        if (keptTypes.size() > 0) kept.add(STATS, keptTypes)
        return kept
    }

    const val DATA_VERSION = "DataVersion"
    private const val STATS = "stats"
    private const val CUSTOM = "minecraft:custom"

    /** Counted in ticks while the player is online, whatever they do. */
    private val RUNNING_TIMES = setOf(
        "minecraft:play_time",
        "minecraft:total_world_time",
        "minecraft:time_since_death",
        "minecraft:time_since_rest",
        "minecraft:sneak_time",
    )
}

/** Vanilla data kept as one JSON file per player in [folder], which the game reads when it creates the player. */
internal class JsonFileExtra(
    override val id: String,
    override val tracksChanges: Boolean,
    private val folder: Path,
    /** Writes the player's file now. Vanilla does so only after the player file, too late for the upload that save sends. */
    private val saveFile: (ServerPlayer) -> Unit,
    /** Copies into the current data what the stored data holds that this server could not load, and returns the copies. */
    private val keepUnloaded: (stored: JsonObject, current: JsonObject) -> JsonObject,
    /** See [PlayerExtra.comparable]. */
    private val comparablePart: (JsonObject) -> JsonObject = { it },
) : PlayerExtra {
    /** Nothing is loaded yet, so everything [keepUnloaded] would copy is what this server cannot load. */
    override fun unloadedAtJoin(stored: JsonElement): JsonElement? =
        (stored as? JsonObject)?.let { keepUnloaded(it, JsonObject()) }?.takeIf { it.size() > 0 }

    override fun comparable(entry: JsonElement): JsonElement = (entry as? JsonObject)?.let(comparablePart) ?: entry

    override fun restore(playerId: UUID, stored: JsonElement) {
        val data = stored.asJsonObject
        val version = data.get(PlayerExtras.DATA_VERSION)?.asInt ?: 0
        val current = SharedConstants.getCurrentVersion().dataVersion().version()
        check(version <= current) { "Stored $id version $version is newer than this server's $current" }

        Files.createDirectories(folder)
        val temporary = Files.createTempFile(folder, "$playerId-", ".json")
        try {
            Files.writeString(temporary, data.toString())
            Files.move(temporary, fileOf(playerId), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun export(player: ServerPlayer, unloaded: JsonElement?): ExtraExport {
        saveFile(player)
        val current = JsonParser.parseString(Files.readString(fileOf(player.uuid))).asJsonObject
        val kept = (unloaded as? JsonObject)?.let { keepUnloaded(it, current) }
        return ExtraExport(current, kept?.takeIf { it.size() > 0 })
    }

    private fun fileOf(playerId: UUID): Path = folder.resolve("$playerId.json")
}
