package io.github.doitdan.openapi.ui

import io.github.doitdan.openapi.OpenApiProperties
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody

@Hidden
@Controller
class OpenApiUiController(
    private val properties: OpenApiProperties,
    private val applicationName: String,
) {
    @GetMapping("\${openapi.ui.path:/docs}")
    fun index() = "redirect:${properties.ui.path}/index.html"

    @GetMapping("\${openapi.ui.path:/docs}/config.json")
    @ResponseBody
    fun config() = mapOf(
        "docsUrl" to properties.ui.docsUrl,
        "tryItOut" to properties.ui.tryItOut,
        "title" to properties.ui.title.ifBlank { null },
        "headers" to properties.ui.headers,
        "cookies" to properties.ui.cookies,
        "mcpName" to properties.mcp.serverName(applicationName),
    )
}
