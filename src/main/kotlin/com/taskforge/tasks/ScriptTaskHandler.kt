package com.taskforge.tasks

import com.taskforge.engine.TaskExecutionInput
import com.taskforge.engine.TaskExecutionOutcome
import com.taskforge.engine.TaskHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

@Component
class ScriptTaskHandler : TaskHandler {
    override val type: String = "script"

    override suspend fun execute(input: TaskExecutionInput): TaskExecutionOutcome = coroutineScope {
        val config = input.resolvedConfig
        val command = config["command"]?.jsonPrimitive?.contentOrNull
            ?: return@coroutineScope TaskExecutionOutcome.Failure(
                message = "script task requires config.command",
                retryable = false,
            )
        val interpreter = config["interpreter"]?.jsonPrimitive?.contentOrNull ?: "/bin/sh"
        val workingDirectory = config["workingDirectory"]?.jsonPrimitive?.contentOrNull
        val envObject = config["env"] as? JsonObject

        val process = try {
            withContext(Dispatchers.IO) {
                val builder = ProcessBuilder(interpreter, "-lc", command)
                if (workingDirectory != null) {
                    builder.directory(java.io.File(workingDirectory))
                }
                envObject?.forEach { (key, value) ->
                    builder.environment()[key] = value.jsonPrimitive.contentOrNull ?: value.toString()
                }
                builder.start()
            }
        } catch (ex: Exception) {
            return@coroutineScope TaskExecutionOutcome.Failure(
                message = "Failed to start script: ${ex.message}",
                retryable = true,
            )
        }

        coroutineContext.job.invokeOnCompletion { cause ->
            if (cause != null && process.isAlive) {
                terminateProcess(process)
            }
        }

        val stdoutDeferred = async(Dispatchers.IO) { process.inputStream.bufferedReader().use { it.readText() } }
        val stderrDeferred = async(Dispatchers.IO) { process.errorStream.bufferedReader().use { it.readText() } }

        val exitCode = withContext(Dispatchers.IO) {
            while (true) {
                if (process.waitFor(200, TimeUnit.MILLISECONDS)) {
                    break
                }
                ensureActive()
            }
            process.exitValue()
        }

        val streamTexts = awaitAll(stdoutDeferred, stderrDeferred)
        val output = JsonObject(
            mapOf(
                "exitCode" to JsonPrimitive(exitCode),
                "stdout" to JsonPrimitive(streamTexts[0] as String),
                "stderr" to JsonPrimitive(streamTexts[1] as String),
            ),
        )

        if (exitCode == 0) {
            TaskExecutionOutcome.Success(output = output)
        } else {
            TaskExecutionOutcome.Failure(
                message = "Script exited with code $exitCode",
                retryable = false,
                output = output,
            )
        }
    }

    private fun terminateProcess(process: Process) {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }
}
