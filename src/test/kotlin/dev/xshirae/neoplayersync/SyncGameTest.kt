package dev.xshirae.neoplayersync

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class SyncGameTest(
    val template: String = "empty",
    val batch: String = "default",
    val timeoutTicks: Int = 100,
)
