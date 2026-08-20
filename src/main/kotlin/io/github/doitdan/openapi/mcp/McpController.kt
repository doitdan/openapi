package io.github.doitdan.openapi.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.NullNode
import io.swagger.v3.core.util.Json31
import io.swagger.v3.oas.annotations.Hidden
import io.github.doitdan.openapi.OpenApiProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseBody

@Hidden
@Controller
class McpController(
    private val properties: OpenApiProperties,
    private val tools: McpTools,
    private val applicationName: String,
) {
    @PostMapping("\${openapi.ui.path:/docs}/mcp", produces = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseBody
    fun exchange(
        @RequestBody body: String,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, Any?>> {
        if (!originAllowed(request)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build()

        val message = runCatching { Json31.mapper().readTree(body) }.getOrNull()
            ?: return ResponseEntity.badRequest().body(error(NullNode.instance, -32700, "Parse error"))

        val id = message.path("id")
        val method = message.path("method").asText("")
        if (id.isMissingNode || id.isNull) return ResponseEntity.accepted().build()

        return runCatching { ResponseEntity.ok(handle(method, message, request)) }
            .getOrElse { failure -> ResponseEntity.ok(error(id, -32603, failure.message ?: "Internal error")) }
    }

    @GetMapping("\${openapi.ui.path:/docs}/mcp")
    fun stream(): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build()

    @DeleteMapping("\${openapi.ui.path:/docs}/mcp")
    fun terminate(): ResponseEntity<Void> = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build()

    private fun handle(
        method: String,
        message: JsonNode,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val id = Json31.mapper().convertValue(message.path("id"), Any::class.java)
        return when (method) {
            "initialize" -> result(id, initialize())
            "ping" -> result(id, emptyMap<String, Any>())
            "tools/list" -> result(id, mapOf("tools" to tools.definitions()))
            "tools/call" -> result(id, callTool(message.path("params"), request))
            else -> mapOf(
                "jsonrpc" to "2.0",
                "id" to id,
                "error" to mapOf("code" to -32601, "message" to "Method not found: $method"),
            )
        }
    }

    private fun initialize() = mapOf(
        "protocolVersion" to PROTOCOL_VERSION,
        "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
        "serverInfo" to mapOf(
            "name" to properties.mcp.serverName(applicationName),
            "version" to properties.mcp.version,
        ),
        "instructions" to "Read-only access to this service's API documentation: endpoints, parameters, schemas and the policy written for each endpoint.",
    )

    private fun callTool(
        params: JsonNode,
        request: HttpServletRequest,
    ): Map<String, Any?> {
        val name = params.path("name").asText("")
        val arguments = params.path("arguments")
        return runCatching {
            mapOf(
                "content" to listOf(mapOf("type" to "text", "text" to tools.call(name, arguments, request))),
                "isError" to false,
            )
        }.getOrElse { failure ->
            mapOf(
                "content" to listOf(mapOf("type" to "text", "text" to (failure.message ?: "Tool failed"))),
                "isError" to true,
            )
        }
    }

    private fun result(
        id: Any?,
        payload: Any,
    ) = mapOf("jsonrpc" to "2.0", "id" to id, "result" to payload)

    private fun error(
        id: JsonNode,
        code: Int,
        message: String,
    ) = mapOf(
        "jsonrpc" to "2.0",
        "id" to Json31.mapper().convertValue(id, Any::class.java),
        "error" to mapOf("code" to code, "message" to message),
    )

    private fun originAllowed(request: HttpServletRequest): Boolean {
        val origin = request.getHeader("Origin") ?: return true
        val allowed = properties.mcp.allowedOrigins
        if (allowed.isNotEmpty()) return allowed.contains(origin)
        val host = request.getHeader("Host") ?: return false
        return origin.endsWith("//$host") || origin.contains("://localhost") || origin.contains("://127.0.0.1")
    }

    private companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
    }
}
