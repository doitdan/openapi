package io.github.doitdan.openapi.customizer

import io.github.doitdan.openapi.OpenApiProperties
import io.swagger.v3.oas.models.media.Schema
import java.lang.reflect.Field
import kotlin.reflect.KClass

internal class EnumDocs(
    private val config: OpenApiProperties.EnumDescriptions,
) {
    fun descriptionsOf(enumClass: Class<*>): Map<String, String>? {
        val constants = enumClass.enumConstants ?: return null
        val field = descriptionField(enumClass) ?: return null
        return constants.associate { constant -> (constant as Enum<*>).name to field.get(constant).toString() }
    }

    fun annotatedEnumClassOf(annotations: Iterable<Annotation>): Class<*>? = annotations
        .firstOrNull { it.annotationClass.java.name in config.stringEnumAnnotations }
        ?.let(::enumClassOf)

    fun describe(
        schema: Schema<*>,
        enumClass: Class<*>,
    ) {
        val descriptions = descriptionsOf(enumClass) ?: return
        val markdown = descriptions.entries.joinToString("\n") { (name, description) -> "- `$name`: $description" }
        schema.description = listOfNotNull(schema.description?.takeIf(String::isNotBlank), markdown).joinToString("\n\n")
        schema.addExtension(ENUM_DESCRIPTIONS, descriptions)
    }

    @Suppress("UNCHECKED_CAST")
    fun fillEnumNames(
        schema: Schema<*>,
        enumClass: Class<*>,
    ) {
        if (!schema.enum.isNullOrEmpty()) return
        (schema as Schema<Any>).enum = enumClass.enumConstants.map { constant -> (constant as Enum<*>).name }
    }

    private fun descriptionField(enumClass: Class<*>): Field? = runCatching {
        enumClass.getDeclaredField(config.descriptionMember).apply { isAccessible = true }
    }.getOrNull()

    private fun enumClassOf(annotation: Annotation): Class<*>? {
        val value = runCatching { annotation.annotationClass.java.getMethod("value").invoke(annotation) }.getOrNull()
        val enumClass = when (value) {
            is Class<*> -> value
            is KClass<*> -> value.java
            else -> null
        }
        return enumClass?.takeIf(Class<*>::isEnum)
    }

    private companion object {
        const val ENUM_DESCRIPTIONS = "x-enum-descriptions"
    }
}
