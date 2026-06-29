package com.taskforge.engine

import com.taskforge.model.WorkflowDefinition
import java.util.concurrent.ConcurrentHashMap

internal class ExecutionStore {
    private val workflows = ConcurrentHashMap<String, WorkflowDefinition>()
    private val executions = ConcurrentHashMap<String, ExecutionRuntime>()

    fun saveWorkflow(workflow: WorkflowDefinition): Boolean = workflows.putIfAbsent(workflow.id, workflow) == null

    fun getWorkflow(workflowId: String): WorkflowDefinition? = workflows[workflowId]

    fun saveExecution(runtime: ExecutionRuntime) {
        executions[runtime.id] = runtime
    }

    fun getExecution(executionId: String): ExecutionRuntime? = executions[executionId]
}
