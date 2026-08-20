package io.github.doitdan.openapi

import com.fasterxml.jackson.databind.JsonNode

/**
 * OpenAPI 3.1 writes a nullable field as `"type": ["string", "null"]`,
 * so the declared type is the first entry that is not `null`.
 */
internal fun JsonNode.schemaType(): String {
    val type = path("type")
    if (!type.isArray) return type.asText("")
    return type.map(JsonNode::asText).firstOrNull { it != "null" }.orEmpty()
}

/**
 * OpenAPI 3.1 writes a nullable object as `oneOf: [$ref, {"type": "null"}]`,
 * which is the same shape a real union uses. The null branch carries no type
 * information, so only the remaining branches describe the value.
 */
internal fun JsonNode.unionBranches(): List<JsonNode> {
    val branches = path("oneOf").takeIf { it.isArray && !it.isEmpty }
        ?: path("anyOf").takeIf { it.isArray && !it.isEmpty }
        ?: return emptyList()

    return branches.filterNot { branch -> branch.schemaType() == "null" }
}

/** A nullable wrapper collapses to the single branch it wraps. */
internal fun JsonNode.unwrapNullable(): JsonNode = unionBranches().singleOrNull() ?: this

/**
 * OpenAPI 3.1 writes inheritance as `allOf: [$ref, {own properties}]`.
 * The referenced bases stay by name; only the inline parts describe new fields.
 */
internal fun JsonNode.allOfParts(): Pair<List<String>, List<JsonNode>> {
    val parts = path("allOf").takeIf { it.isArray && !it.isEmpty } ?: return emptyList<String>() to emptyList()

    val (referenced, inline) = parts.partition { part -> part.path("\$ref").asText("").isNotBlank() }
    return referenced.map { part -> part.path("\$ref").asText().substringAfterLast('/').toTypeIdentifier() } to inline
}

/**
 * Schema names mirror the JVM type, so a nested DTO arrives as `Outer.Inner`.
 * A dot is not legal in a TypeScript identifier; the nesting is kept with `_`.
 */
internal fun String.toTypeIdentifier(): String = replace(Regex("[^A-Za-z0-9_$]"), "_")
