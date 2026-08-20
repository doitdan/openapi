package io.github.doitdan.openapi.export

import com.fasterxml.jackson.databind.JsonNode
import io.github.doitdan.openapi.schemaType
import io.github.doitdan.openapi.allOfParts
import io.github.doitdan.openapi.toTypeIdentifier
import io.github.doitdan.openapi.unionBranches

class TypeScriptExporter {
    fun types(
        spec: JsonNode,
        typesModule: String = "api.types",
        serviceName: String = "api",
    ): String {
        val lines = mutableListOf(
            HEADER,
            "// Import this module under a namespace so several services can live side by side:",
            "//   import type * as ${pascal(serviceName)} from \"./$typesModule\";",
            "",
        )
        val schemas = spec.path("components").path("schemas")

        schemas.fieldNames().asSequence().sorted().forEach { name ->
            lines += declaration(name.toTypeIdentifier(), schemas.path(name))
            lines += ""
        }

        operations(spec).forEach { operation ->
            lines += operationTypes(operation)
        }
        return lines.joinToString("\n").trimEnd() + "\n"
    }

    fun client(
        spec: JsonNode,
        typesModule: String,
        serviceName: String = "api",
    ): String {
        val service = pascal(serviceName)
        val operations = operations(spec)
        val declared = spec.path("components").path("schemas").fieldNames().asSequence().map(String::toTypeIdentifier).toSet()
        val imported = operations
            .flatMap { listOf(it.queryType, it.bodyType, it.responseType) }
            .filterNotNull()
            .map { type -> type.removeSuffix("[]").removeSurrounding("(", ")") }
            .flatMap { type -> type.split(" | ", " & ") }
            .map(String::trim)
            .filter { type -> declared.contains(type) || type.endsWith("Query") }
            .distinct()
            .sorted()

        val lines = mutableListOf(
            HEADER,
            "",
            "import type {",
            *imported.map { "  $it," }.toTypedArray(),
            "} from \"./$typesModule\";",
            "",
            "export interface ${service}ClientOptions {",
            "  baseUrl?: string;",
            "  headers?: Record<string, string>;",
            "  fetch?: typeof fetch;",
            "}",
            "",
            "export function create${service}Client(options: ${service}ClientOptions = {}) {",
            "  const baseUrl = (options.baseUrl ?? \"\").replace(/\\/$/, \"\");",
            "  const doFetch = options.fetch ?? fetch;",
            "",
            "  async function request<T>(method: string, path: string, init: RequestInit = {}): Promise<T> {",
            "    const response = await doFetch(baseUrl + path, {",
            "      ...init,",
            "      method,",
            "      credentials: \"include\",",
            "      headers: { ...options.headers, ...(init.headers as Record<string, string>) },",
            "    });",
            "    if (!response.ok) throw new Error(`${'$'}{method} ${'$'}{path} failed with ${'$'}{response.status}`);",
            "    if (response.status === 204) return undefined as T;",
            "    return (await response.json()) as T;",
            "  }",
            "",
            "  return {",
        )

        operations.forEach { operation ->
            lines += clientMethod(operation)
        }

        lines += listOf(
            "  };",
            "}",
            "",
            "export type ${service}Client = ReturnType<typeof create${service}Client>;",
        )
        return lines.joinToString("\n") + "\n"
    }

    private fun clientMethod(operation: ExportedOperation): List<String> {
        val params = mutableListOf<String>()
        operation.pathParams.forEach { name -> params += "$name: string | number" }
        if (operation.queryType != null) params += "query: ${operation.queryType}"
        if (operation.bodyType != null) params += "body: ${operation.bodyType}"
        params += "init?: RequestInit"

        val template = operation.path.replace(Regex("\\{([^}]+)}")) { match ->
            "\${" + camel(match.groupValues[1]) + "}"
        }
        val query = if (operation.queryType != null) {
            "      const search = new URLSearchParams(" +
                "Object.entries(query).filter(([, value]) => value !== undefined && value !== null)" +
                ".map(([key, value]) => [key, String(value)]));"
        } else {
            null
        }
        val suffix = if (operation.queryType != null) "\${search.toString() ? `?\${search}` : \"\"}" else ""
        val body = if (operation.bodyType != null) {
            "        body: JSON.stringify(body),\n        headers: { \"Content-Type\": \"application/json\" },"
        } else {
            null
        }

        return listOfNotNull(
            "    /** ${operation.summary.ifBlank { operation.method.uppercase() + " " + operation.path }} */",
            "    ${operation.functionName}(${params.joinToString(", ")}) {",
            query,
            "      return request<${operation.responseType ?: "void"}>(\"${operation.method.uppercase()}\", `$template$suffix`, {",
            body,
            "        ...init,",
            "      });",
            "    },",
        )
    }

    private fun operationTypes(operation: ExportedOperation): List<String> {
        val lines = mutableListOf<String>()
        if (operation.queryType != null) {
            lines += "export interface ${operation.queryType} {"
            operation.queryParams.forEach { parameter ->
                val optional = if (parameter.required) "" else "?"
                val comment = parameter.description?.let { " // $it" } ?: ""
                lines += "  ${parameter.name}$optional: ${parameter.type};$comment"
            }
            lines += "}"
            lines += ""
        }
        return lines
    }

    private fun declaration(
        name: String,
        schema: JsonNode,
    ): String {
        if (schema.has("enum")) {
            val values = schema.path("enum").joinToString(" | ") { "\"${it.asText()}\"" }
            return "export type $name = $values;"
        }

        val branches = schema.unionBranches()
        if (branches.isNotEmpty()) return "export type $name = ${branches.joinToString(" | ", transform = ::typeOf)};"

        val (bases, inline) = schema.allOfParts()
        if (bases.isNotEmpty()) {
            val own = inline.map { part -> objectBody(part) }.filter { body -> body != "{}" }
            return "export type $name = ${(bases + own).joinToString(" & ")};"
        }

        if (schema.path("properties").isMissingNode) return "export type $name = unknown;"
        return "export interface $name " + objectBody(schema)
    }

    private fun objectBody(schema: JsonNode): String {
        val properties = schema.path("properties")
        if (properties.isMissingNode || properties.isEmpty) return "{}"

        val required = schema.path("required").map { it.asText() }
        val lines = mutableListOf("{")
        properties.fieldNames().forEach { property ->
            val node = properties.path(property)
            val optional = if (required.contains(property)) "" else "?"
            val comment = summaryLine(node.path("description").asText(""))?.let { " // $it" } ?: ""
            lines += "  $property$optional: ${typeOf(node)};$comment"
        }
        lines += "}"
        return lines.joinToString("\n")
    }

    private fun typeOf(node: JsonNode): String {
        node.path("\$ref").takeIf { !it.isMissingNode }?.let { return it.asText().substringAfterLast('/').toTypeIdentifier() }
        if (node.has("enum")) return node.path("enum").joinToString(" | ") { "\"${it.asText()}\"" }

        val branches = node.unionBranches()
        if (branches.isNotEmpty()) return branches.joinToString(" | ", transform = ::typeOf)

        return when (node.schemaType()) {
            "array" -> arrayOf(typeOf(node.path("items")))
            "integer", "number" -> "number"
            "boolean" -> "boolean"
            "string" -> "string"
            "object" -> if (node.has("additionalProperties")) {
                "Record<string, ${typeOf(node.path("additionalProperties"))}>"
            } else {
                "Record<string, unknown>"
            }
            else -> "unknown"
        }
    }

    private fun arrayOf(element: String) = if (element.contains(" | ")) "($element)[]" else "$element[]"

    private fun summaryLine(description: String): String? = description
        .lineSequence()
        .map(String::trim)
        .filter { line -> line.isNotBlank() && !line.matches(Regex("^-\\s+`[^`]+`\\s*:.*")) }
        .firstOrNull()

    private fun operations(spec: JsonNode): List<ExportedOperation> {
        val paths = spec.path("paths")
        val used = mutableSetOf<String>()
        val result = mutableListOf<ExportedOperation>()

        paths.fieldNames().forEach { path ->
            val item = paths.path(path)
            METHODS.forEach { method ->
                val node = item.path(method)
                if (node.isMissingNode) return@forEach

                val parameters = node.path("parameters")
                val queryParams = parameters
                    .filter { it.path("in").asText() == "query" }
                    .map {
                        ExportedParameter(
                            name = it.path("name").asText(),
                            type = typeOf(it.path("schema")),
                            required = it.path("required").asBoolean(false),
                                            description = summaryLine(it.path("description").asText("")),
                        )
                    }
                val pathParams = parameters
                    .filter { it.path("in").asText() == "path" }
                    .map { camel(it.path("name").asText()) }

                var base = node.path("operationId").asText("").ifBlank { camel("$method-${path.replace(Regex("[^A-Za-z0-9]+"), "-")}") }
                while (!used.add(base)) base += "Alt"

                result += ExportedOperation(
                    path = path,
                    method = method,
                    functionName = camel(base),
                    summary = node.path("summary").asText(""),
                    queryParams = queryParams,
                    queryType = if (queryParams.isEmpty()) null else pascal(base) + "Query",
                    pathParams = pathParams,
                    bodyType = schemaRef(node.path("requestBody")),
                    responseType = successResponseType(node.path("responses")),
                )
            }
        }
        return result
    }

    private fun schemaRef(node: JsonNode): String? {
        if (node.isMissingNode) return null
        val content = node.path("content")
        val mediaType = content.fieldNames().asSequence().firstOrNull { it.contains("json") } ?: return null
        val schema = content.path(mediaType).path("schema")
        return schema.takeIf { !it.isMissingNode }?.let { typeOf(it) }
    }

    private fun successResponseType(responses: JsonNode): String? = responses
        .fieldNames()
        .asSequence()
        .filter { it.startsWith("2") }
        .sorted()
        .mapNotNull { code -> schemaRef(responses.path(code)) }
        .firstOrNull()

    private fun camel(value: String): String {
        val parts = value.split(Regex("[^A-Za-z0-9]+")).filter(String::isNotBlank)
        if (parts.isEmpty()) return value
        return parts.first().replaceFirstChar(Char::lowercaseChar) +
            parts.drop(1).joinToString("") { part -> part.replaceFirstChar(Char::uppercaseChar) }
    }

    private fun pascal(value: String) = camel(value).replaceFirstChar(Char::uppercaseChar)

    private companion object {
        const val HEADER = "// Generated from the OpenAPI document. Do not edit by hand."
        val METHODS = listOf("get", "post", "put", "patch", "delete")
    }
}

data class ExportedParameter(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String?,
)

data class ExportedOperation(
    val path: String,
    val method: String,
    val functionName: String,
    val summary: String,
    val queryParams: List<ExportedParameter>,
    val queryType: String?,
    val pathParams: List<String>,
    val bodyType: String?,
    val responseType: String?,
)
