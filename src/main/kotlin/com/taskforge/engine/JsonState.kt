package com.taskforge.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun resolvePath(root: JsonObject, path: String): JsonElement? {
    if (path.isBlank()) return null
    val segments = path.split(".")
    var current: JsonElement = root
    for (segment in segments) {
        current = when (current) {
            is JsonObject -> current[segment] ?: return null
            is JsonArray -> {
                val index = segment.toIntOrNull() ?: return null
                current.getOrNull(index) ?: return null
            }
            else -> return null
        }
    }
    return current
}

fun JsonObject.withPath(path: String, value: JsonElement): JsonObject {
    val segments = path.split(".").filter { it.isNotBlank() }
    require(segments.isNotEmpty()) { "State path cannot be blank" }
    return putRecursive(this, segments, 0, value)
}

private fun putRecursive(
    current: JsonObject,
    segments: List<String>,
    index: Int,
    value: JsonElement,
): JsonObject {
    val key = segments[index]
    val next = if (index == segments.lastIndex) {
        value
    } else {
        val child = current[key] as? JsonObject ?: JsonObject(emptyMap())
        putRecursive(child, segments, index + 1, value)
    }
    val mutable = current.toMutableMap()
    mutable[key] = next
    return JsonObject(mutable)
}

fun JsonElement.asTemplateString(): String =
    when (this) {
        JsonNull -> "null"
        is JsonPrimitive -> when {
            isString -> content
            else -> toString()
        }
        else -> toString()
    }
