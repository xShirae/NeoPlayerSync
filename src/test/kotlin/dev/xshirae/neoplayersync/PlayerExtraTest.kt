package dev.xshirae.neoplayersync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.SharedConstants
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerExtraTest {
    private val player = UUID.randomUUID()
    private lateinit var folder: Path

    private fun json(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    private val currentVersion get() = SharedConstants.getCurrentVersion().dataVersion().version()

    private fun fileExtra() = JsonFileExtra("test", tracksChanges = false, folder, saveFile = {}, keepUnloaded = { _, _ -> JsonObject() })

    @BeforeTest
    fun setUp() {
        folder = Files.createTempDirectory("neoplayersync-extra-test")
    }

    @AfterTest
    fun tearDown() {
        folder.toFile().deleteRecursively()
    }

    @Test
    fun `an advancement this server lacks is kept and one it has stays as the player has it`() {
        val stored = json("""{"modded:secret":{"done":true},"minecraft:story/root":{"done":true},"DataVersion":1}""")
        val current = json("""{"DataVersion":2}""")
        val kept = PlayerExtras.keepUnknownAdvancements(stored, current) { it.startsWith("minecraft:") }
        assertEquals(json("""{"modded:secret":{"done":true}}"""), kept, "only the unknown advancement stays in memory")
        assertTrue(current.has("modded:secret"), "an unknown advancement must survive the upload")
        assertFalse(current.has("minecraft:story/root"), "a known one missing now was revoked here, and must stay revoked")
        assertEquals(2, current.get("DataVersion").asInt, "the current file's version must win")
    }

    @Test
    fun `a statistic this server could not load is kept and current counters win`() {
        val stored = json("""{"stats":{"minecraft:mined":{"minecraft:stone":5,"modded:ore":3},"modded:type":{"x":1}}}""")
        val current = json("""{"stats":{"minecraft:mined":{"minecraft:stone":9}}}""")
        val kept = PlayerExtras.keepMissingStatistics(stored, current)
        assertEquals(json("""{"stats":{"minecraft:mined":{"modded:ore":3},"modded:type":{"x":1}}}"""), kept, "only missing counters stay in memory")
        assertEquals(json("{}"), PlayerExtras.keepMissingStatistics(current, current), "nothing is kept when every counter loaded")
        val types = current.getAsJsonObject("stats")
        assertEquals(9, types.getAsJsonObject("minecraft:mined").get("minecraft:stone").asInt)
        assertEquals(3, types.getAsJsonObject("minecraft:mined").get("modded:ore").asInt)
        assertEquals(1, types.getAsJsonObject("modded:type").get("x").asInt)
    }

    @Test
    fun `time and distance counters do not count as a change, and the original keeps them`() {
        val statistics = json(
            """{"stats":{"minecraft:custom":{"minecraft:play_time":5,"minecraft:walk_one_cm":7,"minecraft:nautilus_one_cm":1,"minecraft:jump":2},"minecraft:mined":{"minecraft:stone":1}},"DataVersion":1}""",
        )
        val comparable = PlayerExtras.withoutRunningCounters(statistics)
        assertEquals(
            json("""{"stats":{"minecraft:custom":{"minecraft:jump":2},"minecraft:mined":{"minecraft:stone":1}},"DataVersion":1}"""),
            comparable,
        )
        assertEquals(5, statistics.getAsJsonObject("stats").getAsJsonObject("minecraft:custom").get("minecraft:play_time").asInt, "the upload must still carry them")
    }

    @Test
    fun `a joining player keeps only the advancements this server cannot load`() {
        val extra = JsonFileExtra("advancements", tracksChanges = true, folder, saveFile = {}, keepUnloaded = { stored, current ->
            PlayerExtras.keepUnknownAdvancements(stored, current) { it.startsWith("minecraft:") }
        })
        assertEquals(json("""{"modded:secret":{"done":true}}"""), extra.unloadedAtJoin(json("""{"modded:secret":{"done":true},"minecraft:story/root":{"done":true},"DataVersion":1}""")))
        assertEquals(null, extra.unloadedAtJoin(json("""{"minecraft:story/root":{"done":true},"DataVersion":1}""")), "nothing to keep when all of it loads")
    }

    @Test
    fun `a stored file replaces this server's copy`() {
        val file = folder.resolve("$player.json")
        file.writeText("""{"old":true}""")
        fileExtra().restore(player, json("""{"new":true,"DataVersion":$currentVersion}"""))
        assertEquals(json("""{"new":true,"DataVersion":$currentVersion}"""), json(file.readText()))
        assertEquals(listOf(file), Files.list(folder).use { it.toList() }, "no temporary file may be left behind")
    }

    @Test
    fun `a file saved by a newer game version is refused and this server's copy stays`() {
        val file = folder.resolve("$player.json")
        file.writeText("""{"old":true}""")
        assertFailsWith<IllegalStateException> {
            fileExtra().restore(player, json("""{"DataVersion":${currentVersion + 1}}"""))
        }
        assertEquals("""{"old":true}""", file.readText())
    }
}
