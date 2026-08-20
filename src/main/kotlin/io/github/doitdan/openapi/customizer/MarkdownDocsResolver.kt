package io.github.doitdan.openapi.customizer

import io.github.doitdan.openapi.OpenApiProperties
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import org.springdoc.core.customizers.GlobalOpenApiCustomizer
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver

class MarkdownDocsResolver(
    private val config: OpenApiProperties.MarkdownDocs,
) : GlobalOpenApiCustomizer {
    private val resolver = PathMatchingResourcePatternResolver()

    override fun customise(openApi: OpenAPI) {
        openApi.paths?.forEach { (path, pathItem) ->
            operationsOf(pathItem).forEach { (httpMethod, operation) ->
                apply(path, httpMethod, operation)
                operation.extensions?.remove(MarkdownDocsCustomizer.HANDLER_EXTENSION)
            }
        }
    }

    private fun apply(
        path: String,
        httpMethod: String,
        operation: Operation,
    ) {
        val markdown = locate(path, httpMethod, operation) ?: return
        operation.description = listOfNotNull(operation.description?.takeIf(String::isNotBlank), markdown).joinToString("\n\n")
    }

    private fun locate(
        path: String,
        httpMethod: String,
        operation: Operation,
    ): String? {
        val handler = operation.extensions?.get(MarkdownDocsCustomizer.HANDLER_EXTENSION) as? Map<*, *>
        val packagePath = handler?.get("package")?.toString().orEmpty()
        return config.locations
            .asSequence()
            .map { pattern ->
                pattern
                    .replace("{basePath}", config.basePath)
                    .replace("{controller}", handler?.get("controller")?.toString().orEmpty())
                    .replace("{parentPackage}", packagePath.substringBeforeLast('/'))
                    .replace("{package}", packagePath)
                    .replace("{method}", handler?.get("method")?.toString().orEmpty())
                    .replace("{httpMethod}", httpMethod)
                    .replace("{path}", slug(path))
                    .replace("{operationId}", operation.operationId.orEmpty())
            }
            .mapNotNull(::read)
            .firstOrNull()
    }

    private fun operationsOf(pathItem: PathItem): Map<String, Operation> = listOfNotNull(
        pathItem.get?.let { "get" to it },
        pathItem.post?.let { "post" to it },
        pathItem.put?.let { "put" to it },
        pathItem.patch?.let { "patch" to it },
        pathItem.delete?.let { "delete" to it },
        pathItem.head?.let { "head" to it },
        pathItem.options?.let { "options" to it },
        pathItem.trace?.let { "trace" to it },
    ).toMap()

    private fun slug(path: String) = path
        .replace(Regex("[{}]"), "")
        .split("/")
        .filter(String::isNotBlank)
        .joinToString("-")
        .ifBlank { "root" }

    private fun read(location: String): String? = runCatching {
        resolver
            .getResources(location)
            .firstOrNull(Resource::exists)
            ?.getContentAsString(Charsets.UTF_8)
            ?.trim()
            ?.ifBlank { null }
    }.getOrNull()
}
