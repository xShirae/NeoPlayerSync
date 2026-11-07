package dev.xshirae.neoplayersync

import com.mojang.brigadier.CommandDispatcher
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** `/nps` and `/nps health` report the database link, `/nps sync` saves every player now. Operators only. */
internal object NpsCommand {
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("nps")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes { health(it.source) }
                .then(Commands.literal("health").executes { health(it.source) })
                .then(Commands.literal("sync").executes { sync(it.source) }),
        )
    }

    private fun health(source: CommandSourceStack): Int {
        val service = PlayerDataSync.service ?: return notInUse(source)
        val stats = "Players held: ${service.heldPlayers}. Saves waiting for the database: ${service.unsentSaves}. " +
            "Inventory saves queued: ${service.inventorySavesWaiting}."
        service.pingDatabase().whenComplete { millis, failure ->
            reply(source, failure) { "Database reachable in $millis ms. $stats" }
        }
        return 1
    }

    private fun sync(source: CommandSourceStack): Int {
        val service = PlayerDataSync.service ?: return notInUse(source)
        val start = System.nanoTime()
        service.saveAllNow().whenComplete { players, failure ->
            val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            reply(source, failure, millis) { "Saved $players players to the database in $millis ms." }
        }
        return 1
    }

    /** Runs on a NeoPlayerSync thread, so the reply goes back through the server thread. */
    private fun reply(source: CommandSourceStack, failure: Throwable?, millis: Long? = null, success: () -> String) {
        val message = if (failure == null) null else describe(failure) + (millis?.let { " ($it ms)" } ?: "")
        source.server.execute {
            if (message == null) {
                source.sendSuccess({ Component.literal(success()) }, false)
            } else {
                source.sendFailure(Component.literal(message))
            }
        }
    }

    private fun describe(failure: Throwable): String {
        val cause = (failure as? CompletionException)?.cause ?: failure
        return when (cause) {
            is TimeoutException -> "The database did not answer in time."
            is IllegalStateException -> cause.message.orEmpty()
            else -> "The database is unreachable: ${cause.message ?: cause.javaClass.simpleName}"
        }
    }

    private fun notInUse(source: CommandSourceStack): Int {
        source.sendFailure(Component.literal("NeoPlayerSync is not using a database on this server."))
        return 0
    }
}
