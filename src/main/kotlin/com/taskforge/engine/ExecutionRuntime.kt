package com.taskforge.engine

import com.taskforge.model.ExecutionStatus
import com.taskforge.model.TaskStatus
import com.taskforge.model.WorkflowDefinition
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject

internal data class MutableTaskState(
    var status: TaskStatus = TaskStatus.PENDING,
    var attempts: Int = 0,
    var startedAt: String? = null,
    var endedAt: String? = null,
    var error: String? = null,
    var output: JsonObject? = null,
)

internal data class PendingApprovalState(
    val taskId: String,
    val requiredIdentity: String?,
    val prompt: String?,
    val requestedAt: String,
    val deadlineAt: String,
    val onTimeout: ApprovalTimeoutAction,
    val timeoutJob: Job,
)

internal data class ExecutionRuntime(
    val id: String,
    val workflow: WorkflowDefinition,
    val startedAt: String,
    var endedAt: String? = null,
    var status: ExecutionStatus = ExecutionStatus.RUNNING,
    var state: JsonObject,
    val taskStates: MutableMap<String, MutableTaskState>,
    val pendingApprovals: MutableMap<String, PendingApprovalState> = mutableMapOf(),
    val runningJobs: MutableMap<String, Job> = mutableMapOf(),
    var cancelRequested: Boolean = false,
    var schedulerRunning: Boolean = false,
    var scheduleRequested: Boolean = false,
    val mutex: Mutex = Mutex(),
)

internal val terminalExecutionStates = setOf(
    ExecutionStatus.SUCCEEDED,
    ExecutionStatus.FAILED,
    ExecutionStatus.CANCELLED,
)

internal fun TaskStatus.blocksDownstream(): Boolean =
    this == TaskStatus.FAILED || this == TaskStatus.TIMED_OUT || this == TaskStatus.CANCELLED || this == TaskStatus.SKIPPED_UPSTREAM

internal fun TaskStatus.isTerminal(): Boolean =
    this != TaskStatus.PENDING && this != TaskStatus.RUNNING && this != TaskStatus.WAITING_APPROVAL
