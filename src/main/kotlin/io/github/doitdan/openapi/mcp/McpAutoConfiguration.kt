package io.github.doitdan.openapi.mcp

import io.github.doitdan.openapi.OpenApiProperties
import io.github.doitdan.openapi.SpecReader
import io.github.doitdan.openapi.applicationName
import io.github.doitdan.openapi.export.TypeScriptExporter
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment
import org.springframework.web.servlet.DispatcherServlet

@AutoConfiguration
@ConditionalOnClass(DispatcherServlet::class)
@ConditionalOnProperty(prefix = "openapi.mcp", name = ["enabled"], matchIfMissing = true)
@EnableConfigurationProperties(OpenApiProperties::class)
class McpAutoConfiguration {
    @Bean
    fun openApiMcpTools(
        specReader: SpecReader,
        properties: OpenApiProperties,
        environment: Environment,
    ) = McpTools(specReader, TypeScriptExporter(), properties, environment.applicationName())

    @Bean
    fun openApiMcpController(
        properties: OpenApiProperties,
        tools: McpTools,
        environment: Environment,
    ) = McpController(properties, tools, environment.applicationName())
}
