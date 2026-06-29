package com.taskforge

import com.taskforge.engine.ExecutionService
import com.taskforge.engine.TaskHandler
import com.taskforge.engine.TaskHandlerRegistry
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TaskForgeConfiguration {
    private val requiredBuiltinTaskTypes = setOf("http", "script", "approval", "set_state", "assert_state")

    @Bean
    fun json(): Json =
        Json {
            prettyPrint = true
            isLenient = true
            ignoreUnknownKeys = false
        }

    @Bean
    fun taskHandlerRegistry(handlers: List<TaskHandler>): TaskHandlerRegistry =
        TaskHandlerRegistry(handlers).also { registry ->
            val missing = requiredBuiltinTaskTypes - registry.knownTypes()
            require(missing.isEmpty()) {
                "Missing required task handlers: ${missing.joinToString(", ")}"
            }
        }

    @Bean
    fun executionService(registry: TaskHandlerRegistry): ExecutionService = ExecutionService(registry)

    @Bean
    fun idempotencyService(): IdempotencyService = IdempotencyService()
}
