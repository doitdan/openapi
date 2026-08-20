package io.github.doitdan.openapi.customizer

import io.github.doitdan.openapi.OpenApiProperties
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import org.springdoc.core.customizers.GlobalOpenApiCustomizer
import org.springframework.http.HttpStatus

class SuccessStatusCustomizer(
    private val config: OpenApiProperties.SuccessStatus,
) : GlobalOpenApiCustomizer {
    override fun customise(openApi: OpenAPI) {
        openApi.paths?.values?.forEach { path ->
            remap(path.post, config.post)
            remap(path.put, config.put)
            remap(path.patch, config.patch)
            remap(path.delete, config.delete)
        }
    }

    private fun remap(
        operation: Operation?,
        statusCode: Int,
    ) {
        if (statusCode == DEFAULT_SUCCESS) return
        val responses = operation?.responses ?: return
        responses.remove(DEFAULT_SUCCESS.toString())?.let { response ->
            responses.addApiResponse(statusCode.toString(), response.description(descriptionOf(statusCode)))
        }
    }

    private fun descriptionOf(statusCode: Int) = HttpStatus.resolve(statusCode)?.name?.replace('_', ' ') ?: statusCode.toString()

    private companion object {
        const val DEFAULT_SUCCESS = 200
    }
}
