package com.taskforge.tasks

import com.taskforge.engine.ApprovalTimeoutAction
import com.taskforge.engine.TaskExecutionInput
import com.taskforge.engine.TaskExecutionOutcome
import com.taskforge.engine.TaskHandler
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.stereotype.Component

@Component
class ApprovalTaskHandler : TaskHandler {
    override val type: String = "approval"

    override suspend fun execute(input: TaskExecutionInput): TaskExecutionOutcome {
        val config = input.resolvedConfig
        val timeoutMs = config["timeoutMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 300_000L
        val requiredIdentity = config["requiredIdentity"]?.jsonPrimitive?.contentOrNull
        val prompt = config["prompt"]?.jsonPrimitive?.contentOrNull
        val onTimeoutRaw = config["onTimeout"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "FAIL"
        val onTimeout = when (onTimeoutRaw) {
            "APPROVE" -> ApprovalTimeoutAction.APPROVE
            "REJECT" -> ApprovalTimeoutAction.REJECT
            else -> ApprovalTimeoutAction.FAIL
        }

        return TaskExecutionOutcome.AwaitApproval(
            timeoutMs = timeoutMs,
            onTimeout = onTimeout,
            requiredIdentity = requiredIdentity,
            prompt = prompt,
        )
    }
}
