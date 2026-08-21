package io.github.doitdan.openapi.customizer

import io.github.doitdan.openapi.OpenApiProperties
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import org.springdoc.core.customizers.GlobalOpenApiCustomizer
import org.springframework.util.AntPathMatcher

/**
 * A document-level `security` block applies to every operation, so login and sign-up read
 * as if they needed the credential they are about to issue. Declaring the public paths
 * clears the requirement where it does not hold, which is also what makes the remaining
 * operations a truthful list of what needs authentication.
 */
class PublicPathCustomizer(
    private val config: OpenApiProperties.Security,
) : GlobalOpenApiCustomizer {
    private val matcher = AntPathMatcher()

    override fun customise(openApi: OpenAPI) {
        if (config.publicPaths.isEmpty()) return

        openApi.paths?.forEach { (path, pathItem) ->
            if (!isPublic(path)) return@forEach
            operationsOf(pathItem).forEach { operation -> operation.security = emptyList() }
        }
    }

    private fun isPublic(path: String) = config.publicPaths.any { pattern ->
        pattern == path || matcher.match(pattern, path)
    }

    private fun operationsOf(pathItem: PathItem): List<Operation> = listOfNotNull(
        pathItem.get,
        pathItem.post,
        pathItem.put,
        pathItem.patch,
        pathItem.delete,
    )
}
