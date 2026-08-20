package io.github.doitdan.openapi.mcp

import com.fasterxml.jackson.databind.JsonNode
import io.github.doitdan.openapi.OpenApiProperties
import io.github.doitdan.openapi.SpecOperation
import io.github.doitdan.openapi.SpecReader
import io.github.doitdan.openapi.export.TypeScriptExporter
import io.swagger.v3.core.util.Json31
import jakarta.servlet.http.HttpServletRequest

class McpTools(
    private val specReader: SpecReader,
    private val exporter: TypeScriptExporter,
    private val properties: OpenApiProperties,
    private val applicationName: String,
) {
    private fun getTypeScript(
        spec: JsonNode,
        arguments: JsonNode,
    ): String {
        val name = exportName(spec)
        return when (arguments.path("kind").asText("types")) {
            "client" -> "// $name.client.ts\n" + exporter.client(spec, "$name.types", name)
            else -> "// $name.types.d.ts\n" + exporter.types(spec, "$name.types", name)
        }
    }

    private fun exportName(spec: JsonNode): String =
        properties.export.fileStem(applicationName, spec.path("info").path("title").asText(""))

    fun definitions(): List<Map<String, Any>> = listOf(
        tool(
            name = "list_endpoints",
            title = "List endpoints",
            description = "List every documented endpoint with its method, path, tag and summary. Filter by tag or free text.",
            properties = mapOf(
                "tag" to stringProperty("Only endpoints carrying this tag"),
                "query" to stringProperty("Free text matched against path, summary and description"),
            ),
        ),
        tool(
            name = "get_endpoint",
            title = "Get endpoint documentation",
            description = "Full documentation for one endpoint: description and policy, parameters, request body and responses.",
            properties = mapOf(
                "method" to stringProperty("HTTP method, for example get or post"),
                "path" to stringProperty("Templated path, for example /coaches/{coachId}"),
            ),
            required = listOf("method", "path"),
        ),
        tool(
            name = "search_docs",
            title = "Search documentation",
            description = "Search endpoint documentation, including the policy text written in markdown files.",
            properties = mapOf("query" to stringProperty("Text to look for")),
            required = listOf("query"),
        ),
        tool(
            name = "get_schema",
            title = "Get schema",
            description = "Return one component schema by name, or list the available schema names when no name is given.",
            properties = mapOf("name" to stringProperty("Schema name, for example CoachResponse")),
        ),
        tool(
            name = "get_typescript",
            title = "Get TypeScript definitions",
            description = "Generate TypeScript for this service: \"types\" for interfaces and enums, \"client\" for a typed fetch client. " +
                "Names are prefixed per service so several services can live side by side.",
            properties = mapOf("kind" to stringProperty("types (default) or client")),
        ),
    )

    fun call(
        name: String,
        arguments: JsonNode,
        request: HttpServletRequest,
    ): String {
        val spec = specReader.read(request)
        return when (name) {
            "list_endpoints" -> listEndpoints(spec, arguments)
            "get_endpoint" -> getEndpoint(spec, arguments)
            "search_docs" -> searchDocs(spec, arguments)
            "get_schema" -> getSchema(spec, arguments)
            "get_typescript" -> getTypeScript(spec, arguments)
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
    }

    private fun listEndpoints(
        spec: JsonNode,
        arguments: JsonNode,
    ): String {
        val tag = arguments.path("tag").asText("")
        val query = arguments.path("query").asText("")
        val operations = specReader
            .operations(spec)
            .filter { tag.isBlank() || it.tags.any { value -> value.equals(tag, ignoreCase = true) } }
            .filter { query.isBlank() || it.matches(query) }

        if (operations.isEmpty()) return "No endpoints matched."
        return operations.joinToString("\n") { operation ->
            val tags = operation.tags.joinToString(",").ifBlank { "-" }
            "${operation.method.uppercase()} ${operation.path}  [$tags]  ${operation.summary}"
        }
    }

    private fun getEndpoint(
        spec: JsonNode,
        arguments: JsonNode,
    ): String {
        val method = arguments.path("method").asText("").lowercase()
        val path = arguments.path("path").asText("")
        val operation = specReader
            .operations(spec)
            .firstOrNull { it.method == method && it.path == path }
            ?: return "No endpoint found for ${method.uppercase()} $path"

        val renderer = SchemaRenderer(spec)
        val lines = mutableListOf("# ${method.uppercase()} $path")
        if (operation.summary.isNotBlank()) lines += operation.summary

        serverLine(spec)?.let { lines += it }
        securityLine(spec, operation)?.let { lines += it }

        if (operation.description.isNotBlank()) lines += listOf("", "## Documentation and policy", operation.description)

        val parameters = operation.node.path("parameters")
        if (parameters.isArray && !parameters.isEmpty) {
            lines += listOf("", "## Parameters")
            parameters.forEach { parameter ->
                val resolved = renderer.resolve(parameter.path("schema")).first
                val required = if (parameter.path("required").asBoolean(false)) "required" else "optional"
                val description = parameter.path("description").asText("").lineSequence().firstOrNull().orEmpty()
                lines += "- ${parameter.path("name").asText()} (in ${parameter.path("in").asText()}, " +
                    "${renderer.typeName(parameter.path("schema"), resolved)}, $required)" +
                    (if (description.isBlank()) "" else ": $description") +
                    enumHintOf(resolved)
            }
        }

        contentOf(operation.node.path("requestBody"))?.let { (mediaType, schema) ->
            lines += listOf("", "## Request body ($mediaType)", "### Fields")
            lines += renderer.fields(schema)
            lines += listOf("", "### Example", "```json", renderer.pretty(renderer.example(schema)), "```")
        }

        lines += listOf("", "## Responses")
        val responses = operation.node.path("responses")
        responses.fieldNames().forEach { code ->
            val response = responses.path(code)
            val description = response.path("description").asText("")
            lines += listOf("", "### $code $description")
            contentOf(response)?.let { (mediaType, schema) ->
                lines += "type: ${renderer.typeName(schema)} ($mediaType)"
                lines += listOf("```json", renderer.pretty(renderer.example(schema)), "```")
            }
            val headers = response.path("headers")
            if (!headers.isMissingNode) {
                headers.fieldNames().forEach { header ->
                    lines += "header $header: ${headers.path(header).path("description").asText("")}"
                }
            }
        }
        return lines.joinToString("\n")
    }

    private fun contentOf(holder: JsonNode): Pair<String, JsonNode>? {
        val content = holder.path("content")
        if (content.isMissingNode) return null
        val mediaType = content.fieldNames().asSequence().firstOrNull { it.contains("json") }
            ?: content.fieldNames().asSequence().firstOrNull()
            ?: return null
        val schema = content.path(mediaType).path("schema")
        return if (schema.isMissingNode) null else mediaType to schema
    }

    private fun serverLine(spec: JsonNode): String? = spec
        .path("servers")
        .firstOrNull()
        ?.path("url")
        ?.asText("")
        ?.takeIf(String::isNotBlank)
        ?.let { "server: $it" }

    private fun securityLine(
        spec: JsonNode,
        operation: SpecOperation,
    ): String? {
        val requirements = operation.node.path("security").takeIf { it.isArray && !it.isEmpty }
            ?: spec.path("security").takeIf { it.isArray && !it.isEmpty }
            ?: return null

        val schemes = spec.path("components").path("securitySchemes")
        val names = requirements.flatMap { requirement -> requirement.fieldNames().asSequence().toList() }.distinct()
        val rendered = names.joinToString(", ") { name ->
            val scheme = schemes.path(name)
            when (scheme.path("type").asText("")) {
                "apiKey" -> "$name (${scheme.path("in").asText()} ${scheme.path("name").asText()})"
                "http" -> "$name (${scheme.path("scheme").asText()} auth header)"
                else -> name
            }
        }
        return "auth: $rendered"
    }

    private fun enumHintOf(resolved: JsonNode): String {
        val values = resolved.path("enum").takeIf { it.isArray && !it.isEmpty } ?: return ""
        val descriptions = resolved.path("x-enum-descriptions")
        return "\n  allowed: " + values.joinToString(", ") { value ->
            val key = value.asText()
            val meaning = descriptions.path(key).asText("")
            if (meaning.isBlank()) key else "$key($meaning)"
        }
    }

    private fun searchDocs(
        spec: JsonNode,
        arguments: JsonNode,
    ): String {
        val query = arguments.path("query").asText("")
        if (query.isBlank()) return "Provide a query."
        val matches = specReader.operations(spec).filter { it.matches(query) }
        if (matches.isEmpty()) return "Nothing matched \"$query\"."

        return matches.joinToString("\n\n") { operation ->
            val excerpt = operation.description
                .lineSequence()
                .filter { line -> line.contains(query, ignoreCase = true) }
                .take(3)
                .joinToString("\n")
                .ifBlank { operation.summary }
            "${operation.method.uppercase()} ${operation.path}\n$excerpt"
        }
    }

    private fun getSchema(
        spec: JsonNode,
        arguments: JsonNode,
    ): String {
        val schemas = spec.path("components").path("schemas")
        val name = arguments.path("name").asText("")
        if (name.isBlank()) return schemas.fieldNames().asSequence().sorted().joinToString("\n")
        val schema = schemas.path(name)
        if (schema.isMissingNode) return "No schema named $name"
        return Json31.pretty(schema)
    }

    private fun tool(
        name: String,
        title: String,
        description: String,
        properties: Map<String, Map<String, String>>,
        required: List<String> = emptyList(),
    ) = mapOf(
        "name" to name,
        "title" to title,
        "description" to description,
        "inputSchema" to mapOf(
            "type" to "object",
            "properties" to properties,
            "required" to required,
        ),
    )

    private fun stringProperty(description: String) = mapOf("type" to "string", "description" to description)
}
