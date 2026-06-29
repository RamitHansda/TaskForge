package com.taskforge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class TaskForgeControllerIntegrationTest(
    @Autowired private val mockMvc: MockMvc,
) {
    private val json = Json
    private val idempotencyHeader = "Idempotency-Key"

    @Test
    fun `creates workflow and retrieves it`() {
        val workflowId = "wf-${UUID.randomUUID()}"
        val createPayload =
            """
            {
              "id": "$workflowId",
              "name": "api-basic",
              "tasks": [
                {
                  "id": "seed",
                  "type": "set_state",
                  "config": {
                    "values": {
                      "artifact.version": "1.2.3"
                    }
                  }
                }
              ]
            }
            """.trimIndent()

        val createResponse =
            mockMvc.post("/workflows") {
                contentType = MediaType.APPLICATION_JSON
                content = createPayload
            }.andExpect {
                status { isCreated() }
            }.andReturn().response.contentAsString

        val createdId = json.parseToJsonElement(createResponse).jsonObject["id"]?.jsonPrimitive?.content
        assertEquals(workflowId, createdId)

        mockMvc.get("/workflows/$workflowId").andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `returns actionable validation errors for unknown task type`() {
        val badPayload =
            """
            {
              "name": "bad",
              "tasks": [
                {
                  "id": "x",
                  "type": "non_existing_type",
                  "config": {}
                }
              ]
            }
            """.trimIndent()

        val body =
            mockMvc.post("/workflows") {
                contentType = MediaType.APPLICATION_JSON
                content = badPayload
            }.andExpect {
                status { isBadRequest() }
            }.andReturn().response.contentAsString

        val parsed = json.parseToJsonElement(body).jsonObject
        val errors = parsed["validationErrors"]?.jsonArray ?: error("Expected validationErrors array")
        assertTrue(errors.any { it.jsonObject["field"]?.jsonPrimitive?.content == "tasks.type" })
    }

    @Test
    fun `returns 404 when starting execution for missing workflow`() {
        val payload =
            """
            {
              "workflowId": "missing-workflow"
            }
            """.trimIndent()

        mockMvc.post("/executions") {
            contentType = MediaType.APPLICATION_JSON
            content = payload
            header(idempotencyHeader, "start-missing-${UUID.randomUUID()}")
        }.andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `returns 409 when approval actor does not satisfy required identity`() {
        val workflowId = "wf-approval-${UUID.randomUUID()}"
        val createPayload =
            """
            {
              "id": "$workflowId",
              "name": "approval-check",
              "tasks": [
                {
                  "id": "gate",
                  "type": "approval",
                  "config": {
                    "requiredIdentity": "ops-lead",
                    "timeoutMs": 30000,
                    "onTimeout": "FAIL"
                  }
                }
              ]
            }
            """.trimIndent()

        mockMvc.post("/workflows") {
            contentType = MediaType.APPLICATION_JSON
            content = createPayload
        }.andExpect {
            status { isCreated() }
        }

        val startResponse =
            mockMvc.post("/executions") {
                contentType = MediaType.APPLICATION_JSON
                header(idempotencyHeader, "start-${UUID.randomUUID()}")
                content =
                    """
                    {
                      "workflowId": "$workflowId"
                    }
                    """.trimIndent()
            }.andExpect {
                status { isAccepted() }
            }.andReturn().response.contentAsString
        val executionId = json.parseToJsonElement(startResponse).jsonObject["id"]?.jsonPrimitive?.content
            ?: error("Missing execution id")

        awaitWaitingApproval(executionId)

        mockMvc.post("/executions/$executionId/approvals/gate") {
            contentType = MediaType.APPLICATION_JSON
            header(idempotencyHeader, "approval-${UUID.randomUUID()}")
            content =
                """
                {
                  "approved": true,
                  "actor": "developer"
                }
                """.trimIndent()
        }.andExpect {
            status { isConflict() }
        }
    }

    @Test
    fun `requires idempotency key for mutating execution endpoints`() {
        val workflowId = createSetStateWorkflow()
        mockMvc.post("/executions") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "workflowId": "$workflowId"
                }
                """.trimIndent()
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `replays start execution response for same idempotency key`() {
        val workflowId = createSetStateWorkflow()
        val key = "start-replay-${UUID.randomUUID()}"
        val payload =
            """
            {
              "workflowId": "$workflowId"
            }
            """.trimIndent()

        val first = mockMvc.post("/executions") {
            contentType = MediaType.APPLICATION_JSON
            header(idempotencyHeader, key)
            content = payload
        }.andExpect {
            status { isAccepted() }
        }.andReturn()

        val second = mockMvc.post("/executions") {
            contentType = MediaType.APPLICATION_JSON
            header(idempotencyHeader, key)
            content = payload
        }.andExpect {
            status { isAccepted() }
        }.andReturn()

        val firstId = executionIdFrom(first.response.contentAsString)
        val secondId = executionIdFrom(second.response.contentAsString)
        assertEquals(firstId, secondId)
        assertEquals("false", first.response.getHeader(IdempotencyService.IDEMPOTENCY_REPLAYED_HEADER))
        assertEquals("true", second.response.getHeader(IdempotencyService.IDEMPOTENCY_REPLAYED_HEADER))
    }

    @Test
    fun `rejects idempotency key reuse for different start request`() {
        val workflowA = createSetStateWorkflow()
        val workflowB = createSetStateWorkflow()
        val key = "start-conflict-${UUID.randomUUID()}"

        mockMvc.post("/executions") {
            contentType = MediaType.APPLICATION_JSON
            header(idempotencyHeader, key)
            content =
                """
                {
                  "workflowId": "$workflowA"
                }
                """.trimIndent()
        }.andExpect {
            status { isAccepted() }
        }

        val conflictBody = mockMvc.post("/executions") {
            contentType = MediaType.APPLICATION_JSON
            header(idempotencyHeader, key)
            content =
                """
                {
                  "workflowId": "$workflowB"
                }
                """.trimIndent()
        }.andExpect {
            status { isConflict() }
        }.andReturn().response.contentAsString

        val message = json.parseToJsonElement(conflictBody).jsonObject["message"]?.jsonPrimitive?.content
        assertTrue(message?.contains("different request") == true)
    }

    @Test
    fun `replays cancel response for same idempotency key`() {
        val workflowId = createApprovalWorkflow()
        val startBody = startExecution(workflowId, "start-cancel-${UUID.randomUUID()}")
        val executionId = executionIdFrom(startBody)
        awaitWaitingApproval(executionId)

        val key = "cancel-replay-${UUID.randomUUID()}"
        val first = mockMvc.post("/executions/$executionId/cancel") {
            header(idempotencyHeader, key)
        }.andExpect {
            status { isOk() }
        }.andReturn()
        val second = mockMvc.post("/executions/$executionId/cancel") {
            header(idempotencyHeader, key)
        }.andExpect {
            status { isOk() }
        }.andReturn()

        assertEquals(first.response.contentAsString, second.response.contentAsString)
        assertEquals("false", first.response.getHeader(IdempotencyService.IDEMPOTENCY_REPLAYED_HEADER))
        assertEquals("true", second.response.getHeader(IdempotencyService.IDEMPOTENCY_REPLAYED_HEADER))
    }

    @Test
    fun `replays approval response for same idempotency key`() {
        val workflowId = createApprovalWorkflow()
        val startBody = startExecution(workflowId, "start-approval-${UUID.randomUUID()}")
        val executionId = executionIdFrom(startBody)
        awaitWaitingApproval(executionId)
        val approvalPayload =
            """
            {
              "approved": true,
              "actor": "ops-lead"
            }
            """.trimIndent()
        val key = "approval-replay-${UUID.randomUUID()}"

        val first = mockMvc.post("/executions/$executionId/approvals/gate") {
            contentType = MediaType.APPLICATION_JSON
            header(idempotencyHeader, key)
            content = approvalPayload
        }.andExpect {
            status { isOk() }
        }.andReturn()

        val second = mockMvc.post("/executions/$executionId/approvals/gate") {
            contentType = MediaType.APPLICATION_JSON
            header(idempotencyHeader, key)
            content = approvalPayload
        }.andExpect {
            status { isOk() }
        }.andReturn()

        val firstStatus = json.parseToJsonElement(first.response.contentAsString).jsonObject["status"]?.jsonPrimitive?.content
        assertEquals("SUCCEEDED", firstStatus)
        assertEquals(first.response.contentAsString, second.response.contentAsString)
        assertEquals("false", first.response.getHeader(IdempotencyService.IDEMPOTENCY_REPLAYED_HEADER))
        assertEquals("true", second.response.getHeader(IdempotencyService.IDEMPOTENCY_REPLAYED_HEADER))
    }

    private fun createSetStateWorkflow(): String {
        val workflowId = "wf-${UUID.randomUUID()}"
        mockMvc.post("/workflows") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "id": "$workflowId",
                  "name": "set-state",
                  "tasks": [
                    {
                      "id": "seed",
                      "type": "set_state",
                      "config": {
                        "values": {
                          "artifact.version": "1.2.3"
                        }
                      }
                    }
                  ]
                }
                """.trimIndent()
        }.andExpect {
            status { isCreated() }
        }
        return workflowId
    }

    private fun createApprovalWorkflow(): String {
        val workflowId = "wf-approval-${UUID.randomUUID()}"
        mockMvc.post("/workflows") {
            contentType = MediaType.APPLICATION_JSON
            content =
                """
                {
                  "id": "$workflowId",
                  "name": "approval-check",
                  "tasks": [
                    {
                      "id": "gate",
                      "type": "approval",
                      "config": {
                        "requiredIdentity": "ops-lead",
                        "timeoutMs": 30000,
                        "onTimeout": "FAIL"
                      }
                    }
                  ]
                }
                """.trimIndent()
        }.andExpect {
            status { isCreated() }
        }
        return workflowId
    }

    private fun startExecution(workflowId: String, key: String): String =
        mockMvc.post("/executions") {
            contentType = MediaType.APPLICATION_JSON
            header(idempotencyHeader, key)
            content =
                """
                {
                  "workflowId": "$workflowId"
                }
                """.trimIndent()
        }.andExpect {
            status { isAccepted() }
        }.andReturn().response.contentAsString

    private fun executionIdFrom(responseBody: String): String =
        json.parseToJsonElement(responseBody).jsonObject["id"]?.jsonPrimitive?.content
            ?: error("Missing execution id")

    private fun awaitWaitingApproval(executionId: String) {
        repeat(30) {
            val body =
                mockMvc.get("/executions/$executionId")
                    .andExpect { status { isOk() } }
                    .andReturn()
                    .response
                    .contentAsString
            val status = json.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.content
            if (status == "WAITING_APPROVAL") return
            Thread.sleep(25)
        }
        error("Execution '$executionId' did not reach WAITING_APPROVAL")
    }
}
