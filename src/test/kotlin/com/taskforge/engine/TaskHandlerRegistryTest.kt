package com.taskforge.engine

import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TaskHandlerRegistryTest {
    @Test
    fun `rejects duplicate task handler type registration`() {
        val a = stubHandler("dup")
        val b = stubHandler("dup")

        val ex = assertThrows<IllegalArgumentException> {
            TaskHandlerRegistry(listOf(a, b))
        }
        assertTrue(ex.message?.contains("Duplicate task handler type 'dup'") == true)
    }

    @Test
    fun `rejects blank handler type`() {
        val ex = assertThrows<IllegalArgumentException> {
            TaskHandlerRegistry(listOf(stubHandler("  ")))
        }
        assertTrue(ex.message?.contains("Task handler type cannot be blank") == true)
    }

    private fun stubHandler(type: String): TaskHandler =
        object : TaskHandler {
            override val type: String = type

            override suspend fun execute(input: TaskExecutionInput): TaskExecutionOutcome =
                TaskExecutionOutcome.Success(output = JsonObject(emptyMap()))
        }
}
