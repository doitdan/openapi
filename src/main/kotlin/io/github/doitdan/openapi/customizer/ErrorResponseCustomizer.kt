package io.github.doitdan.openapi.customizer

import io.github.doitdan.openapi.OpenApiProperties
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import io.swagger.v3.oas.models.responses.ApiResponse
import org.springdoc.core.customizers.GlobalOpenApiCustomizer
import org.springframework.http.HttpStatus

/**
 * springdoc documents only the success response, so a consumer cannot tell a documented
 * failure from an undocumented one. The codes a handler can actually produce follow from
 * its shape — a path variable can be missing, a body can fail validation, a secured
 * operation can be rejected — so they are derived rather than declared per handler.
 */
class ErrorResponseCustomizer(
    private val config: OpenApiProperties.ErrorResponses,
    private val securedByDefault: () -> Boolean,
) : GlobalOpenApiCustomizer {
    override fun customise(openApi: OpenAPI) {
        openApi.paths?.forEach { (_, pathItem) ->
            operationsOf(pathItem).forEach { (method, operation) -> apply(openApi, method, operation) }
        }
    }

    private fun apply(
        openApi: OpenAPI,
        method: String,
        operation: Operation,
    ) {
        val responses = operation.responses ?: return
        codesFor(openApi, method, operation).forEach { status ->
            val code = status.value().toString()
            if (responses.containsKey(code)) return@forEach
            responses.addApiResponse(code, ApiResponse().description(config.describe(status.value(), status.reasonPhrase)))
        }
    }

    private fun codesFor(
        openApi: OpenAPI,
        method: String,
        operation: Operation,
    ): List<HttpStatus> {
        val codes = mutableListOf<HttpStatus>()

        val hasInput = operation.requestBody != null || !operation.parameters.isNullOrEmpty()
        if (hasInput) codes += HttpStatus.BAD_REQUEST

        if (isSecured(openApi, operation)) {
            codes += HttpStatus.UNAUTHORIZED
            codes += HttpStatus.FORBIDDEN
        }

        val takesIdentifier = operation.parameters.orEmpty().any { it.`in` == "path" }
        if (takesIdentifier || method != "post") codes += HttpStatus.NOT_FOUND

        // A conflict needs something to conflict with, which only a write creates.
        if (method in WRITE_METHODS) codes += HttpStatus.CONFLICT

        codes += HttpStatus.INTERNAL_SERVER_ERROR
        return codes.filter { it.value() in config.include }
    }

    private fun isSecured(
        openApi: OpenAPI,
        operation: Operation,
    ): Boolean {
        operation.security?.let { return it.isNotEmpty() }
        return openApi.security?.isNotEmpty() == true && securedByDefault()
    }

    private companion object {
        val WRITE_METHODS = setOf("post", "put", "patch", "delete")
    }

    private fun operationsOf(pathItem: PathItem): Map<String, Operation> = listOfNotNull(
        pathItem.get?.let { "get" to it },
        pathItem.post?.let { "post" to it },
        pathItem.put?.let { "put" to it },
        pathItem.patch?.let { "patch" to it },
        pathItem.delete?.let { "delete" to it },
    ).toMap()
}
