package io.github.doitdan.openapi.sample

import kotlin.reflect.KClass

@Target(AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class SampleEnumRef(
    val value: KClass<out Enum<*>>,
)
