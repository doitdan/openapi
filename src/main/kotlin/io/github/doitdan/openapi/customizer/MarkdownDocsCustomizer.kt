package io.github.doitdan.openapi.customizer

import io.swagger.v3.oas.models.Operation
import org.springdoc.core.customizers.GlobalOperationCustomizer
import org.springframework.web.method.HandlerMethod

class MarkdownDocsCustomizer : GlobalOperationCustomizer {
    override fun customize(
        operation: Operation,
        handlerMethod: HandlerMethod,
    ): Operation {
        operation.addExtension(
            HANDLER_EXTENSION,
            mapOf(
                "controller" to handlerMethod.beanType.simpleName,
                "package" to handlerMethod.beanType.packageName.replace('.', '/'),
                "method" to handlerMethod.method.name,
            ),
        )
        return operation
    }

    companion object {
        const val HANDLER_EXTENSION = "x-openapi-handler"
    }
}
