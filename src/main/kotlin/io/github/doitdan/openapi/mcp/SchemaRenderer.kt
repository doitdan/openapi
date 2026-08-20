package io.github.doitdan.openapi.mcp

import com.fasterxml.jackson.databind.JsonNode
import io.github.doitdan.openapi.schemaType
import io.github.doitdan.openapi.unionBranches
import io.github.doitdan.openapi.unwrapNullable
import com.fasterxml.jackson.databind.node.ObjectNode
import io.swagger.v3.core.util.Json31

class SchemaRenderer(
    private val spec: JsonNode,
) {
    private val mapper = Json31.mapper()

    fun resolve(
        schema: JsonNode,
        seen: List<String> = emptyList(),
    ): Pair<JsonNode, List<String>> {
        val unwrapped = schema.unwrapNullable()
        val ref = unwrapped.path("\$ref").asText("")
        if (ref.isBlank()) return unwrapped to seen
        if (seen.contains(ref)) return mapper.createObjectNode() to seen

        var target: JsonNode = spec
        ref.removePrefix("#/").split("/").forEach { segment -> target = target.path(segment) }
        return target to (seen + ref)
    }

    fun example(
        schema: JsonNode,
        seen: List<String> = emptyList(),
    ): JsonNode {
        val (resolved, trail) = resolve(schema, seen)

        resolved.path("example").takeIf { !it.isMissingNode }?.let { return it }
        resolved.path("enum").takeIf { it.isArray && !it.isEmpty }?.let { return it.first() }

        return when (resolved.schemaType()) {
            "array" -> mapper.createArrayNode().add(example(resolved.path("items"), trail))
            "integer", "number" -> mapper.nodeFactory.numberNode(0)
            "boolean" -> mapper.nodeFactory.booleanNode(true)
            "string" -> mapper.nodeFactory.textNode(stringExample(resolved))
            else -> objectExample(resolved, trail)
        }
    }

    private fun stringExample(resolved: JsonNode) = when (resolved.path("format").asText("")) {
        "date-time" -> "2026-01-01T09:00:00"
        "date" -> "2026-01-01"
        "uuid" -> "00000000-0000-0000-0000-000000000000"
        "binary" -> "<file>"
        else -> "string"
    }

    private fun objectExample(
        resolved: JsonNode,
        trail: List<String>,
    ): JsonNode {
        val properties = resolved.path("properties")
        if (properties.isMissingNode) return mapper.createObjectNode()
        val node: ObjectNode = mapper.createObjectNode()
        properties.fieldNames().forEach { name -> node.set<JsonNode>(name, example(properties.path(name), trail)) }
        return node
    }

    fun fields(
        schema: JsonNode,
        prefix: String = "",
        seen: List<String> = emptyList(),
        depth: Int = 0,
    ): List<String> {
        if (depth > 5) return emptyList()
        val (resolved, trail) = resolve(schema, seen)

        if (resolved.schemaType() == "array") {
            return fields(resolved.path("items"), "$prefix[]", trail, depth)
        }

        val properties = resolved.path("properties")
        if (properties.isMissingNode) return emptyList()
        val required = resolved.path("required").map { it.asText() }

        val lines = mutableListOf<String>()
        properties.fieldNames().forEach { name ->
            val property = properties.path(name)
            val target = resolve(property, trail).first
            val path = if (prefix.isBlank()) name else "$prefix.$name"
            val flag = if (required.contains(name)) "required" else "optional"

            lines += "- $path (${typeName(property, target)}, $flag)" +
                describe(target) +
                enumHint(target)

            if (target.has("properties") || target.schemaType() == "array") {
                lines += fields(property, path, trail, depth + 1)
            }
        }
        return lines
    }

    private fun describe(target: JsonNode): String {
        val description = target
            .path("description")
            .asText("")
            .lineSequence()
            .map(String::trim)
            .filter { line -> line.isNotBlank() && !line.startsWith("- `") }
            .firstOrNull()
        return description?.let { ": $it" } ?: ""
    }

    private fun enumHint(target: JsonNode): String {
        val values = target.path("enum").takeIf { it.isArray && !it.isEmpty } ?: return ""
        val descriptions = target.path("x-enum-descriptions")
        val rendered = values.joinToString(", ") { value ->
            val key = value.asText()
            val meaning = descriptions.path(key).asText("")
            if (meaning.isBlank()) key else "$key($meaning)"
        }
        return "\n  allowed: $rendered"
    }

    fun typeName(
        schema: JsonNode,
        resolved: JsonNode = resolve(schema).first,
    ): String {
        val branches = schema.unionBranches()
        if (branches.size > 1) return branches.joinToString(" | ") { branch -> typeName(branch) }

        val ref = schema.unwrapNullable().path("\$ref").asText("")
        if (ref.isNotBlank() && !resolved.has("enum")) return ref.substringAfterLast('/')
        if (resolved.has("enum")) return ref.substringAfterLast('/').ifBlank { "enum" }

        return when (resolved.schemaType()) {
            "array" -> "array of " + typeName(resolved.path("items"))
            "integer" -> if (resolved.path("format").asText("") == "int64") "int64" else "int32"
            "number" -> "number"
            "boolean" -> "boolean"
            "string" -> resolved.path("format").asText("").ifBlank { "string" }
            "object" -> "object"
            else -> "unknown"
        }
    }

    fun pretty(node: JsonNode): String = Json31.pretty(node)
}
