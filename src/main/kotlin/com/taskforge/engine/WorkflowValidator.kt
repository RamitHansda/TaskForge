package com.taskforge.engine

import com.taskforge.model.ValidationError
import com.taskforge.model.WorkflowDefinition

class WorkflowValidator(private val registry: TaskHandlerRegistry) {
    fun validate(definition: WorkflowDefinition): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()
        val tasksById = definition.tasks.associateBy { it.id }

        if (definition.name.isBlank()) {
            errors += ValidationError(field = "name", message = "Workflow name cannot be blank")
        }
        if (definition.tasks.isEmpty()) {
            errors += ValidationError(field = "tasks", message = "Workflow must define at least one task")
            return errors
        }

        val duplicates = definition.tasks.groupBy { it.id }.filterValues { it.size > 1 }
        duplicates.keys.forEach { dupId ->
            errors += ValidationError(
                taskId = dupId,
                field = "tasks.id",
                message = "Task id '$dupId' is duplicated",
            )
        }

        definition.tasks.forEach { task ->
            if (task.id.isBlank()) {
                errors += ValidationError(taskId = task.id, field = "tasks.id", message = "Task id cannot be blank")
            }
            if (task.type.isBlank()) {
                errors += ValidationError(taskId = task.id, field = "tasks.type", message = "Task type cannot be blank")
            } else if (registry.get(task.type) == null) {
                errors += ValidationError(
                    taskId = task.id,
                    field = "tasks.type",
                    message = "Unknown task type '${task.type}'",
                )
            }
            if (task.timeoutMs != null && task.timeoutMs <= 0) {
                errors += ValidationError(
                    taskId = task.id,
                    field = "tasks.timeoutMs",
                    message = "Task timeoutMs must be > 0 when provided",
                )
            }
            if (task.retryPolicy.maxRetries < 0) {
                errors += ValidationError(
                    taskId = task.id,
                    field = "tasks.retryPolicy.maxRetries",
                    message = "Task retryPolicy.maxRetries must be >= 0",
                )
            }
            if (task.retryPolicy.backoffMs < 0) {
                errors += ValidationError(
                    taskId = task.id,
                    field = "tasks.retryPolicy.backoffMs",
                    message = "Task retryPolicy.backoffMs must be >= 0",
                )
            }
            task.dependencies.forEach { dependency ->
                if (tasksById[dependency] == null) {
                    errors += ValidationError(
                        taskId = task.id,
                        field = "tasks.dependencies",
                        message = "Dependency '$dependency' does not exist",
                    )
                }
            }
        }

        errors += validateNoCycles(definition)
        return errors
    }

    private fun validateNoCycles(definition: WorkflowDefinition): List<ValidationError> {
        val tasksById = definition.tasks.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val stack = mutableListOf<String>()
        val errors = mutableListOf<ValidationError>()

        fun dfs(taskId: String) {
            if (taskId in visited) return
            if (taskId in visiting) {
                val index = stack.indexOf(taskId).coerceAtLeast(0)
                val cycle = (stack.subList(index, stack.size) + taskId).joinToString(" -> ")
                errors += ValidationError(
                    taskId = taskId,
                    field = "tasks.dependencies",
                    message = "Cycle detected: $cycle",
                )
                return
            }

            visiting += taskId
            stack += taskId
            val task = tasksById[taskId] ?: return
            task.dependencies.forEach { dependency ->
                if (tasksById[dependency] != null) {
                    dfs(dependency)
                }
            }
            stack.removeLast()
            visiting -= taskId
            visited += taskId
        }

        definition.tasks.forEach { dfs(it.id) }
        return errors
    }
}
