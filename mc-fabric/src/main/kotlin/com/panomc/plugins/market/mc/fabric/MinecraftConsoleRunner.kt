package com.panomc.plugins.market.mc.fabric

import net.minecraft.commands.CommandResultCallback
import net.minecraft.commands.Commands
import net.minecraft.server.MinecraftServer

/**
 * The only place that runs a command on the real server: parse as the console, refuse what does not parse, run what does
 * through vanilla's own entry point (`Commands.performCommand`, which also runs `/execute` and `/function` correctly, unlike
 * a bare Brigadier `execute`) with a result callback that counts what the command reported. Must be called on the server thread.
 */
class MinecraftConsoleRunner(private val server: MinecraftServer) : ConsoleRunner {
    override fun run(command: String): ConsoleOutcome {
        var successes = 0
        var failures = 0
        val callback = object : CommandResultCallback {
            override fun onResult(success: Boolean, result: Int) {
                if (success) successes++ else failures++
            }
        }
        val source = server.createCommandSourceStack().withCallback(callback)
        val commands = server.commands
        val parse = commands.dispatcher.parse(command, source)
        val error = Commands.getParseException(parse)
        if (error != null) return ConsoleOutcome.NotRun(error.message ?: "does not parse")
        commands.performCommand(parse, command)
        return ConsoleOutcome.Ran(successes, failures)
    }
}
