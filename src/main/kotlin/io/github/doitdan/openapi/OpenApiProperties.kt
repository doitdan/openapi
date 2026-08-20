package io.github.doitdan.openapi

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.env.Environment

@ConfigurationProperties(prefix = "openapi")
class OpenApiProperties {
    var ui: Ui = Ui()
    var mcp: Mcp = Mcp()
    var export: Export = Export()
    var successStatus: SuccessStatus = SuccessStatus()
    var enumDescriptions: EnumDescriptions = EnumDescriptions()
    var markdownDocs: MarkdownDocs = MarkdownDocs()

    class Ui {
        var enabled: Boolean = true
        var path: String = "/docs"
        var docsUrl: String = "/v3/api-docs"
        var title: String = ""
        var tryItOut: Boolean = true
        var headers: Map<String, String> = emptyMap()
        var cookies: Map<String, String> = emptyMap()
    }

    class Export {
        var enabled: Boolean = true
        var name: String = ""

        fun fileStem(
            applicationName: String,
            apiTitle: String,
        ): String = listOf(name, applicationName, apiTitle)
            .firstNotNullOfOrNull { candidate -> candidate.toSlug().ifBlank { null } }
            ?: "api"
    }

    class Mcp {
        var enabled: Boolean = true
        var name: String = ""
        var version: String = "1.0.0"
        var allowedOrigins: List<String> = emptyList()

        /**
         * An agent connects one server per service, so the name has to say which API it is.
         * `spring.application.name` is often just the service noun (`coach`), which reads as
         * a domain rather than an API — `api` is added unless the name already carries it.
         */
        fun serverName(applicationName: String): String {
            val configured = name.toSlug()
            if (configured.isNotBlank()) return configured

            val application = applicationName.toSlug()
            if (application.isBlank()) return "openapi-docs"

            val segments = application.split("-")
            return if (segments.contains("api")) "$application-docs" else "$application-api-docs"
        }
    }

    class SuccessStatus {
        var enabled: Boolean = true
        var post: Int = 201
        var put: Int = 201
        var patch: Int = 204
        var delete: Int = 204
    }

    class EnumDescriptions {
        var enabled: Boolean = true
        var descriptionMember: String = "description"
        var stringEnumAnnotations: List<String> = emptyList()
    }

    class MarkdownDocs {
        var enabled: Boolean = true
        var basePath: String = "apidocs"
        var locations: List<String> = listOf("classpath:{basePath}/{controller}/{method}.md")
    }
}

internal fun String.toSlug(): String = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

fun Environment.applicationName(): String = getProperty("spring.application.name", "")
