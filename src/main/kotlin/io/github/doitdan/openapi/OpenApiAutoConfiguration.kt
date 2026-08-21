package io.github.doitdan.openapi

import io.github.doitdan.openapi.customizer.EnumDocs
import io.github.doitdan.openapi.customizer.ErrorResponseCustomizer
import io.github.doitdan.openapi.customizer.EnumParameterCustomizer
import io.github.doitdan.openapi.customizer.EnumPropertyCustomizer
import io.github.doitdan.openapi.customizer.MarkdownDocsCustomizer
import io.github.doitdan.openapi.customizer.MarkdownDocsResolver
import io.github.doitdan.openapi.customizer.PublicPathCustomizer
import io.github.doitdan.openapi.customizer.KotlinBooleanNamingPostProcessor
import io.github.doitdan.openapi.customizer.SuccessStatusCustomizer
import io.github.doitdan.openapi.export.ExportController
import io.github.doitdan.openapi.export.TypeScriptExporter
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.properties.SpringDocConfigProperties
import org.springdoc.webmvc.api.OpenApiWebMvcResource
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.core.env.Environment

@AutoConfiguration
@ConditionalOnClass(OpenApiCustomizer::class)
@EnableConfigurationProperties(OpenApiProperties::class)
class OpenApiAutoConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "openapi.success-status", name = ["enabled"], matchIfMissing = true)
    fun successStatusCustomizer(properties: OpenApiProperties) = SuccessStatusCustomizer(properties.successStatus)

    @Bean
    @ConditionalOnProperty(prefix = "openapi.enum-descriptions", name = ["enabled"], matchIfMissing = true)
    fun enumPropertyCustomizer(properties: OpenApiProperties) = EnumPropertyCustomizer(EnumDocs(properties.enumDescriptions))

    @Bean
    @ConditionalOnProperty(prefix = "openapi.enum-descriptions", name = ["enabled"], matchIfMissing = true)
    fun enumParameterCustomizer(properties: OpenApiProperties) = EnumParameterCustomizer(EnumDocs(properties.enumDescriptions))

    @Bean
    @ConditionalOnProperty(prefix = "openapi.markdown-docs", name = ["enabled"], matchIfMissing = true)
    fun markdownDocsCustomizer() = MarkdownDocsCustomizer()

    @Bean
    @ConditionalOnProperty(prefix = "openapi.markdown-docs", name = ["enabled"], matchIfMissing = true)
    fun markdownDocsResolver(properties: OpenApiProperties) = MarkdownDocsResolver(properties.markdownDocs)

    @Bean
    fun publicPathCustomizer(properties: OpenApiProperties) = PublicPathCustomizer(properties.security)

    @Bean
    @ConditionalOnProperty(prefix = "openapi.error-responses", name = ["enabled"], matchIfMissing = true)
    fun errorResponseCustomizer(properties: OpenApiProperties) = ErrorResponseCustomizer(properties.errorResponses) {
        properties.security.publicPaths.isNotEmpty()
    }

    @Bean
    @ConditionalOnProperty(prefix = "openapi.kotlin-boolean-naming", name = ["enabled"], matchIfMissing = true)
    fun kotlinBooleanNamingPostProcessor() = KotlinBooleanNamingPostProcessor()

    @Bean
    fun openApiSpecReader(
        properties: OpenApiProperties,
        resourceProvider: ObjectProvider<OpenApiWebMvcResource>,
        springDocProvider: ObjectProvider<SpringDocConfigProperties>,
    ) = SpecReader(properties, resourceProvider, springDocProvider)

    @Bean
    @ConditionalOnProperty(prefix = "openapi.export", name = ["enabled"], matchIfMissing = true)
    fun openApiExportController(
        properties: OpenApiProperties,
        specReader: SpecReader,
        environment: Environment,
    ) = ExportController(properties, specReader, TypeScriptExporter(), environment.applicationName())
}
