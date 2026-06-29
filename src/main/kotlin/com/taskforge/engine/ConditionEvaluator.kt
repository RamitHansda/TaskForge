package com.taskforge.engine

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

fun evaluateCondition(condition: JsonElement?, state: JsonObject): Boolean {
    if (condition == null) return true
    return when (condition) {
        is JsonPrimitive -> {
            condition.booleanOrNull
                ?: condition.contentOrNull?.lowercase()?.let {
                    when (it) {
                        "true" -> true
                        "false" -> false
                        else -> throw TemplateResolutionException(
                            "String condition must evaluate to true/false, got '$it'",
                        )
                    }
                }
                ?: throw TemplateResolutionException("Unsupported primitive condition value")
        }

        is JsonObject -> {
            val ref = condition["ref"]?.jsonPrimitive?.contentOrNull
                ?: throw TemplateResolutionException("Condition object must include 'ref'")
            val actual = resolvePath(state, ref)
            if ("exists" in condition) {
                val expected = condition["exists"]?.jsonPrimitive?.booleanOrNull
                    ?: throw TemplateResolutionException("'exists' must be boolean")
                return (actual != null) == expected
            }
            if ("equals" in condition) {
                return actual == condition["equals"]
            }
            if ("notEquals" in condition) {
                return actual != condition["notEquals"]
            }
            val bool = (actual as? JsonPrimitive)?.booleanOrNull
                ?: throw TemplateResolutionException(
                    "Condition ref '$ref' must resolve to boolean when no comparator is provided",
                )
            bool
        }

        else -> throw TemplateResolutionException("Unsupported condition type")
    }
}
