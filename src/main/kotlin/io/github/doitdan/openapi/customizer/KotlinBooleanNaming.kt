package io.github.doitdan.openapi.customizer

import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.cfg.MapperConfig
import com.fasterxml.jackson.databind.introspect.AccessorNamingStrategy
import com.fasterxml.jackson.databind.introspect.AnnotatedClass
import com.fasterxml.jackson.databind.introspect.AnnotatedMethod
import com.fasterxml.jackson.databind.introspect.DefaultAccessorNamingStrategy
import org.springdoc.core.providers.ObjectMapperProvider
import org.springframework.beans.factory.config.BeanPostProcessor

/**
 * The schema is built by swagger-core, which runs Jackson 2 bean rules and turns the
 * getter `isSynced()` into the property `synced`. The application serialises with its
 * own mapper and keeps `isSynced`, so the documented name does not match the wire.
 *
 * Kotlin classes carry `@Metadata`, so the getter name is restored only for those and
 * plain Java beans keep the standard naming.
 */
class KotlinBooleanNamingPostProcessor : BeanPostProcessor {
    override fun postProcessAfterInitialization(
        bean: Any,
        beanName: String,
    ): Any {
        if (bean is ObjectMapperProvider) {
            bean.jsonMapper().setAccessorNaming(Provider())
            bean.yamlMapper().setAccessorNaming(Provider())
        }
        return bean
    }

    private class Provider : AccessorNamingStrategy.Provider() {
        private val delegate = DefaultAccessorNamingStrategy.Provider()

        override fun forPOJO(
            config: MapperConfig<*>,
            target: AnnotatedClass,
        ): AccessorNamingStrategy = KeepIsPrefix(delegate.forPOJO(config, target), target)

        override fun forRecord(
            config: MapperConfig<*>,
            target: AnnotatedClass,
        ): AccessorNamingStrategy = delegate.forRecord(config, target)

        override fun forBuilder(
            config: MapperConfig<*>,
            builder: AnnotatedClass,
            valueTypeConfig: BeanDescription,
        ): AccessorNamingStrategy = delegate.forBuilder(config, builder, valueTypeConfig)
    }

    private class KeepIsPrefix(
        private val delegate: AccessorNamingStrategy,
        target: AnnotatedClass,
    ) : AccessorNamingStrategy() {
        private val kotlin = target.rawType?.isAnnotationPresent(Metadata::class.java) == true

        /**
         * `var isEnabled` compiles to `isEnabled()` and `setEnabled()`, so the mutator alone
         * would still strip the prefix. The declared fields carry the Kotlin property names.
         */
        private val declared: Set<String> = target.rawType
            ?.declaredFields
            .orEmpty()
            .map { field -> field.name }
            .filter { name -> name.startsWith("is") }
            .toSet()

        private fun restore(stripped: String): String? = declared
            .firstOrNull { name -> name == "is" + stripped.replaceFirstChar(Char::uppercaseChar) }

        override fun findNameForIsGetter(
            method: AnnotatedMethod,
            name: String,
        ): String? {
            val stripped = delegate.findNameForIsGetter(method, name) ?: return null
            return if (kotlin) name else stripped
        }

        override fun findNameForRegularGetter(
            method: AnnotatedMethod,
            name: String,
        ): String? = delegate.findNameForRegularGetter(method, name)

        override fun findNameForMutator(
            method: AnnotatedMethod,
            name: String,
        ): String? {
            val stripped = delegate.findNameForMutator(method, name) ?: return null
            return if (kotlin) restore(stripped) ?: stripped else stripped
        }

        override fun modifyFieldName(
            field: com.fasterxml.jackson.databind.introspect.AnnotatedField,
            name: String,
        ): String = delegate.modifyFieldName(field, name)
    }
}
