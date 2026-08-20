package io.github.doitdan.openapi.customizer

import io.swagger.v3.oas.models.parameters.Parameter
import org.springdoc.core.customizers.ParameterCustomizer
import org.springframework.core.MethodParameter

class EnumParameterCustomizer internal constructor(
    private val enumDocs: EnumDocs,
) : ParameterCustomizer {
    override fun customize(
        parameterModel: Parameter?,
        methodParameter: MethodParameter,
    ): Parameter? {
        if (parameterModel == null) return null
        val enumClass = enumClassOf(methodParameter) ?: return parameterModel
        parameterModel.schema?.let { schema ->
            enumDocs.fillEnumNames(schema, enumClass)
            enumDocs.describe(schema, enumClass)
        }
        return parameterModel
    }

    private fun enumClassOf(methodParameter: MethodParameter): Class<*>? {
        val parameterType = methodParameter.parameterType
        if (parameterType.isEnum) return parameterType
        return enumDocs.annotatedEnumClassOf(methodParameter.parameterAnnotations.asIterable())
    }
}
