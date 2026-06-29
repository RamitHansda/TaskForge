package com.taskforge

import com.taskforge.aop.ObservedOperation
import com.taskforge.engine.ExecutionService
import com.taskforge.model.CreateWorkflowRequest
import com.taskforge.model.ResolveApprovalRequest
import com.taskforge.model.StartExecutionRequest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@RestController
class TaskForgeController(
    private val service: ExecutionService,
    private val json: Json,
    private val idempotencyService: IdempotencyService,
) {
    @PostMapping(
        "/workflows",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @ObservedOperation("api.create_workflow")
    fun createWorkflow(@RequestBody body: String): ResponseEntity<String> {
        val request = decode<CreateWorkflowRequest>(body)
        val workflow = service.createWorkflow(request)
        return ResponseEntity.status(HttpStatus.CREATED).body(encode(workflow))
    }

    @GetMapping("/workflows/{workflowId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ObservedOperation("api.get_workflow")
    fun getWorkflow(@PathVariable workflowId: String): ResponseEntity<String> =
        ResponseEntity.ok(encode(service.getWorkflow(workflowId)))

    @PostMapping(
        "/executions",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @ObservedOperation("api.start_execution")
    fun startExecution(
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody body: String,
    ): ResponseEntity<String> {
        val request = decode<StartExecutionRequest>(body)
        return idempotencyService.execute(
            scope = "executions.start",
            key = requireIdempotencyKey(idempotencyKey),
            requestFingerprint = encode(request),
        ) {
            val execution = service.startExecution(request)
            ResponseEntity.status(HttpStatus.ACCEPTED).body(encode(execution))
        }
    }

    @GetMapping("/executions/{executionId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ObservedOperation("api.get_execution")
    fun getExecution(@PathVariable executionId: String): ResponseEntity<String> =
        ResponseEntity.ok(encode(service.getExecution(executionId)))

    @PostMapping("/executions/{executionId}/cancel", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ObservedOperation("api.cancel_execution")
    fun cancelExecution(
        @PathVariable executionId: String,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<String> =
        idempotencyService.execute(
            scope = "executions.$executionId.cancel",
            key = requireIdempotencyKey(idempotencyKey),
            requestFingerprint = executionId,
        ) {
            val result = runBlocking { service.cancelExecution(executionId) }
            ResponseEntity.ok(encode(result))
        }

    @PostMapping(
        "/executions/{executionId}/approvals/{taskId}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @ObservedOperation("api.resolve_approval")
    fun resolveApproval(
        @PathVariable executionId: String,
        @PathVariable taskId: String,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody body: String,
    ): ResponseEntity<String> {
        val request = decode<ResolveApprovalRequest>(body)
        return idempotencyService.execute(
            scope = "executions.$executionId.approvals.$taskId",
            key = requireIdempotencyKey(idempotencyKey),
            requestFingerprint = encode(request),
        ) {
            val result = runBlocking { service.resolveApproval(executionId, taskId, request) }
            ResponseEntity.ok(encode(result))
        }
    }

    private inline fun <reified T> decode(body: String): T = json.decodeFromString(body)

    private inline fun <reified T> encode(value: T): String = json.encodeToString(value)

    private fun requireIdempotencyKey(value: String?): String {
        require(!value.isNullOrBlank()) { "Idempotency-Key header is required for this endpoint" }
        return value
    }
}
