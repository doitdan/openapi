package io.github.doitdan.openapi

import com.fasterxml.jackson.databind.JsonNode
import io.swagger.v3.core.util.Json31
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import org.springdoc.core.properties.SpringDocConfigProperties
import org.springdoc.webmvc.api.OpenApiWebMvcResource
import org.springframework.beans.factory.ObjectProvider
import java.util.Locale

class SpecReader(
    private val properties: OpenApiProperties,
    private val resourceProvider: ObjectProvider<OpenApiWebMvcResource>,
    private val springDocProvider: ObjectProvider<SpringDocConfigProperties>,
) {
    fun read(request: HttpServletRequest): JsonNode {
        val resource = resourceProvider.getIfAvailable()
            ?: throw IllegalStateException("springdoc is not serving an OpenAPI document in this application")
        val docsPath = springDocProvider.getIfAvailable()?.apiDocs?.path ?: properties.ui.docsUrl
        val body = resource.openapiJson(DocsPathRequest(request, request.contextPath + docsPath), docsPath, Locale.getDefault())
        return Json31.mapper().readTree(String(body, Charsets.UTF_8))
    }

    /**
     * springdoc derives the server url by stripping the api-docs path from the current request url,
     * so the request has to look like the api-docs request itself.
     */
    private class DocsPathRequest(
        request: HttpServletRequest,
        private val docsUri: String,
    ) : HttpServletRequestWrapper(request) {
        override fun getRequestURI() = docsUri

        override fun getRequestURL(): StringBuffer {
            val origin = StringBuffer()
            origin.append(super.getScheme()).append("://").append(super.getServerName())
            val port = super.getServerPort()
            val defaultPort = (super.getScheme() == "http" && port == 80) || (super.getScheme() == "https" && port == 443)
            if (!defaultPort) origin.append(':').append(port)
            return origin.append(docsUri)
        }
    }

    fun operations(spec: JsonNode): List<SpecOperation> {
        val paths = spec.path("paths")
        val result = mutableListOf<SpecOperation>()
        paths.fieldNames().forEach { path ->
            val item = paths.path(path)
            METHODS.forEach { method ->
                val operation = item.path(method)
                if (!operation.isMissingNode) result.add(SpecOperation(path, method, operation))
            }
        }
        return result
    }

    private companion object {
        val METHODS = listOf("get", "post", "put", "patch", "delete", "head", "options", "trace")
    }
}

data class SpecOperation(
    val path: String,
    val method: String,
    val node: JsonNode,
) {
    val summary: String get() = node.path("summary").asText("")
    val description: String get() = node.path("description").asText("")
    val tags: List<String> get() = node.path("tags").map { it.asText() }

    fun matches(needle: String): Boolean = listOf(path, method, summary, description, tags.joinToString(" "))
        .joinToString(" ")
        .lowercase()
        .contains(needle.lowercase())
}
