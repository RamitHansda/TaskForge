package com.taskforge.tasks

import com.taskforge.engine.TaskExecutionInput
import com.taskforge.engine.TaskExecutionOutcome
import com.taskforge.engine.TaskHandler
import com.taskforge.engine.resolvePath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Component

@Component
class AssertStateTaskHandler : TaskHandler {
    override val type: String = "assert_state"

    override suspend fun execute(input: TaskExecutionInput): TaskExecutionOutcome {
        val config = input.resolvedConfig
        val path = config["path"]?.jsonPrimitive?.contentOrNull
            ?: return TaskExecutionOutcome.Failure(
                message = "assert_state requires config.path",
                retryable = false,
            )
        val actual = resolvePath(input.state, path)
        val existsExpectation = config["exists"]?.jsonPrimitive?.booleanOrNull
        val equalsExpectation = config["equals"]

        if (existsExpectation != null) {
            val exists = actual != null
            if (existsExpectation != exists) {
                return TaskExecutionOutcome.Failure(
                    message = "Assertion failed: expected existence at '$path' to be $existsExpectation but was $exists",
                    retryable = false,
                )
            }
        }

        if (equalsExpectation != null && actual != equalsExpectation) {
            return TaskExecutionOutcome.Failure(
                message = "Assertion failed at '$path': expected $equalsExpectation but got $actual",
                retryable = false,
            )
        }

        if (actual == null) {
            return TaskExecutionOutcome.Failure(
                message = "Assertion failed: '$path' resolved to null",
                retryable = false,
            )
        }

        return TaskExecutionOutcome.Success(
            output = JsonObject(
                mapOf(
                    "path" to JsonPrimitive(path),
                    "actual" to actual,
                ),
            ),
        )
    }
}
