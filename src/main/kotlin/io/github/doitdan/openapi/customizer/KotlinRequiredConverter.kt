package io.github.doitdan.openapi.customizer

import com.fasterxml.jackson.databind.type.TypeFactory
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.oas.models.media.Schema
import kotlin.reflect.full.primaryConstructor

/**
 * Kotlin already says which fields are mandatory — a non-null property cannot be omitted,
 * and the application rejects a payload that leaves one out. swagger-core cannot see that:
 * Kotlin writes its nullability into `@Metadata` and into class-retention annotations, so
 * neither Jackson nor reflection reaches it, and every field reads as optional unless a
 * Bean Validation annotation happens to be present.
 *
 * The generated TypeScript is what suffers: a field the server demands arrives as `field?:`.
 */
class KotlinRequiredConverter : ModelConverter {
    override fun resolve(
        type: AnnotatedType,
        context: ModelConverterContext,
        chain: MutableIterator<ModelConverter>,
    ): Schema<*>? {
        val resolved = (if (chain.hasNext()) chain.next().resolve(type, context, chain) else null) ?: return null
        val constructor = kotlinClassOf(type)?.primaryConstructor ?: return resolved

        // A top level type comes back as a `$ref`, and the model it points at is the one
        // carrying the properties.
        val target = resolved.takeIf { !it.properties.isNullOrEmpty() } ?: definedModel(context, resolved)
        if (target == null || target.properties.isNullOrEmpty()) return resolved

        constructor.parameters
            .filter { parameter -> !parameter.type.isMarkedNullable && !parameter.isOptional }
            .mapNotNull { parameter -> parameter.name }
            .filter { name -> target.properties.containsKey(name) && target.required?.contains(name) != true }
            .forEach { name -> target.addRequiredItem(name) }

        return resolved
    }

    private fun definedModel(
        context: ModelConverterContext,
        schema: Schema<*>,
    ): Schema<*>? {
        val name = schema.`$ref`?.substringAfterLast('/') ?: return null
        return context.definedModels[name]
    }

    /**
     * A parameter with a default value is not mandatory on the wire, so `isOptional` is
     * checked alongside nullability.
     */
    private fun kotlinClassOf(type: AnnotatedType) = runCatching {
        val javaType = TypeFactory.defaultInstance().constructType(type.type)
        javaType.rawClass
            ?.takeIf { raw -> raw.isAnnotationPresent(Metadata::class.java) }
            ?.kotlin
            ?.takeUnless { klass -> klass.isAbstract || klass.java.isEnum }
    }.getOrNull()
}
