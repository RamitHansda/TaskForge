package com.taskforge.engine

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class TemplateResolutionException(message: String) : RuntimeException(message)

private val entireReference = Regex("^\\$\\{([^}]+)}$")
private val inlineReferences = Regex("\\$\\{([^}]+)}")

fun renderTemplate(element: JsonElement, state: JsonObject): JsonElement =
    when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> renderTemplate(value, state) })
        is JsonArray -> JsonArray(element.map { renderTemplate(it, state) })
        is JsonPrimitive -> {
            if (!element.isString) {
                element
            } else {
                val raw = element.content
                val exact = entireReference.matchEntire(raw)
                if (exact != null) {
                    val path = exact.groupValues[1]
                    resolvePath(state, path)
                        ?: throw TemplateResolutionException("Missing state reference '$path'")
                } else {
                    val replaced = inlineReferences.replace(raw) { match ->
                        val path = match.groupValues[1]
                        val resolved = resolvePath(state, path)
                            ?: throw TemplateResolutionException("Missing state reference '$path'")
                        resolved.asTemplateString()
                    }
                    JsonPrimitive(replaced)
                }
            }
        }
    }
