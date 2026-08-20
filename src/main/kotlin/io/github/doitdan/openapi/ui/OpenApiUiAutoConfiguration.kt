package io.github.doitdan.openapi.ui

import io.github.doitdan.openapi.OpenApiProperties
import io.github.doitdan.openapi.applicationName
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.web.servlet.DispatcherServlet
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@AutoConfiguration
@ConditionalOnClass(DispatcherServlet::class)
@ConditionalOnProperty(prefix = "openapi.ui", name = ["enabled"], matchIfMissing = true)
@EnableConfigurationProperties(OpenApiProperties::class)
class OpenApiUiAutoConfiguration {
    @Bean
    fun openApiUiController(
        properties: OpenApiProperties,
        environment: Environment,
    ) = OpenApiUiController(properties, environment.applicationName())

    @Bean
    fun openApiUiResourceConfigurer(properties: OpenApiProperties): WebMvcConfigurer = object : WebMvcConfigurer {
        override fun addResourceHandlers(registry: ResourceHandlerRegistry) {
            registry
                .addResourceHandler("${properties.ui.path}/**")
                .addResourceLocations("classpath:/openapi-ui/")
                .setCachePeriod(0)
        }
    }
}
