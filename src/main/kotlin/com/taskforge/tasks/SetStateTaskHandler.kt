package com.taskforge.tasks

import com.taskforge.engine.TaskExecutionInput
import com.taskforge.engine.TaskExecutionOutcome
import com.taskforge.engine.TaskHandler
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.springframework.stereotype.Component

@Component
class SetStateTaskHandler : TaskHandler {
    override val type: String = "set_state"

    override suspend fun execute(input: TaskExecutionInput): TaskExecutionOutcome {
        val values = input.resolvedConfig["values"] as? JsonObject
            ?: return TaskExecutionOutcome.Failure(
                message = "set_state requires config.values object",
                retryable = false,
            )

        val statePatch = values.entries.associate { it.key to it.value }
        return TaskExecutionOutcome.Success(
            output = JsonObject(
                mapOf(
                    "appliedPaths" to JsonArray(values.keys.map { JsonPrimitive(it) }),
                ),
            ),
            statePatch = statePatch,
        )
    }
}
