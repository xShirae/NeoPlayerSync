package dev.xshirae.neoplayersync

import com.mojang.serialization.Codec
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.FunctionGameTestInstance
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.TestData
import net.minecraft.gametest.framework.TestEnvironmentDefinition
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.attachment.AttachmentType
import net.neoforged.neoforge.event.RegisterGameTestsEvent
import net.neoforged.neoforge.registries.NeoForgeRegistries
import net.neoforged.neoforge.registries.RegisterEvent
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.function.Consumer
import java.util.function.Supplier

/** Registers the scenarios listed below and checks that discovery stays complete. */
@EventBusSubscriber(value = [Dist.DEDICATED_SERVER], modid = NeoPlayerSync.MOD_ID)
object SyncGameTestRegistration {
    /** Stands in for another mod's player data, which must travel with the character. */
    val TEST_ATTACHMENT: AttachmentType<Int> = AttachmentType.builder(Supplier { 0 })
        .serialize(Codec.INT.fieldOf("value"))
        .build()

    @SubscribeEvent
    fun registerContent(event: RegisterEvent) {
        event.register(NeoForgeRegistries.Keys.ATTACHMENT_TYPES) { helper ->
            helper.register(id("test_attachment"), TEST_ATTACHMENT)
        }
        event.register(Registries.TEST_FUNCTION) { helper ->
            discoveredTests.forEach { test ->
                helper.register(test.functionId, Consumer<GameTestHelper> { gameTestHelper ->
                    invokeTest(test.method, gameTestHelper)
                })
            }
        }
    }

    @SubscribeEvent
    fun registerGameTests(event: RegisterGameTestsEvent) {
        val environments = discoveredTests
            .map { it.annotation.batch }
            .distinct()
            .associateWith { batch -> event.registerEnvironment(id("test_batch/$batch"), TestEnvironmentDefinition.AllOf()) }

        discoveredTests.forEach { test ->
            val testData = TestData(
                environments.getValue(test.annotation.batch),
                id(test.annotation.template),
                test.annotation.timeoutTicks,
                0,
                true,
            )
            val functionKey = ResourceKey.create(Registries.TEST_FUNCTION, test.functionId)
            event.registerTest(test.functionId, FunctionGameTestInstance(functionKey, testData))
        }
    }

    private fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(NeoPlayerSync.MOD_ID, path)

    private data class TestSpec(
        val method: Method,
        val annotation: SyncGameTest,
        val functionId: Identifier,
    )

    private val testClasses = listOf(
        PlayerDataMergeGameTests::class.java,
        PlayerDataSyncGameTests::class.java,
    )

    private val discoveredTests: List<TestSpec> by lazy {
        val tests = testClasses.flatMap { testClass ->
            val methods = testClass.declaredMethods
                .filter { method ->
                    method.parameterTypes.contentEquals(arrayOf(GameTestHelper::class.java)) &&
                        method.isAnnotationPresent(SyncGameTest::class.java)
                }
                .groupBy(Method::getName)
                .values
                .map { candidates ->
                    candidates.firstOrNull { Modifier.isStatic(it.modifiers) } ?: candidates.single()
                }

            methods.map { method ->
                val suite = testClass.simpleName.removeSuffix("GameTests").snakeCase()
                TestSpec(method, checkNotNull(method.getAnnotation(SyncGameTest::class.java)), id("test/$suite/${method.name.snakeCase()}"))
            }
        }.sortedBy { it.functionId.toString() }

        check(tests.size == EXPECTED_TEST_COUNT) {
            "Expected $EXPECTED_TEST_COUNT GameTests but discovered ${tests.size}: " + tests.joinToString { it.functionId.toString() }
        }
        tests
    }

    private fun String.snakeCase(): String = replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "_").lowercase()

    private fun invokeTest(method: Method, helper: GameTestHelper) {
        val receiver = if (Modifier.isStatic(method.modifiers)) null else method.declaringClass.getField("INSTANCE").get(null)
        try {
            method.invoke(receiver, helper)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }

    private const val EXPECTED_TEST_COUNT = 18
}
