package io.github.doitdan.openapi

import org.apache.commons.logging.LogFactory
import org.springdoc.api.AbstractOpenApiResource
import org.springdoc.webmvc.api.OpenApiWebMvcResource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import java.util.Locale

class SpecWarmUp(
    private val resourceProvider: ObjectProvider<OpenApiWebMvcResource>,
) {
    private val logger = LogFactory.getLog(SpecWarmUp::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun warmUp() {
        Thread.ofPlatform().daemon().name(THREAD_NAME).start(::build)
    }

    private fun build() {
        runCatching { resourceProvider.getIfAvailable()?.let(::buildOpenApi) }
            .onFailure { logger.warn("Could not build the OpenAPI document before the first request", it) }
    }

    private fun buildOpenApi(resource: OpenApiWebMvcResource) {
        AbstractOpenApiResource::class.java
            .getDeclaredMethod("getOpenApi", Locale::class.java)
            .apply { isAccessible = true }
            .invoke(resource, Locale.getDefault())
    }

    private companion object {
        const val THREAD_NAME = "openapi-warm-up"
    }
}
