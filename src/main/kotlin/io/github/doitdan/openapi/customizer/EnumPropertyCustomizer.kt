package io.github.doitdan.openapi.customizer

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.PropertyCustomizer

class EnumPropertyCustomizer internal constructor(
    private val enumDocs: EnumDocs,
) : PropertyCustomizer {
    override fun customize(
        property: Schema<*>?,
        type: AnnotatedType,
    ): Schema<*>? {
        if (property == null) return null
        enumTypeOf(type)?.let { enumClass ->
            enumDocs.describe(property, enumClass)
            return property
        }
        annotatedEnumOf(type)?.let { enumClass ->
            enumDocs.fillEnumNames(property, enumClass)
            enumDocs.describe(property, enumClass)
        }
        return property
    }

    private fun enumTypeOf(type: AnnotatedType): Class<*>? = rawClassOf(type.type)?.takeIf(Class<*>::isEnum)

    private fun annotatedEnumOf(type: AnnotatedType): Class<*>? = type.ctxAnnotations
        ?.let { annotations -> enumDocs.annotatedEnumClassOf(annotations.asIterable()) }

    private fun rawClassOf(type: Any?): Class<*>? = when (type) {
        is Class<*> -> type
        null -> null
        else -> runCatching { type.javaClass.getMethod("getRawClass").invoke(type) as? Class<*> }.getOrNull()
    }
}
