package io.github.doitdan.openapi.export

import com.fasterxml.jackson.databind.JsonNode
import io.github.doitdan.openapi.OpenApiProperties
import io.github.doitdan.openapi.SpecReader
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody

@Hidden
@Controller
class ExportController(
    private val properties: OpenApiProperties,
    private val specReader: SpecReader,
    private val exporter: TypeScriptExporter,
    private val applicationName: String,
) {
    @GetMapping("\${openapi.ui.path:/docs}/export/manifest.json")
    @ResponseBody
    fun manifest(request: HttpServletRequest): Map<String, String> {
        val name = nameOf(specReader.read(request))
        return mapOf(
            "name" to name,
            "types" to "$name.types.d.ts",
            "client" to "$name.client.ts",
        )
    }

    @GetMapping("\${openapi.ui.path:/docs}/export/types.d.ts")
    @ResponseBody
    fun types(request: HttpServletRequest): ResponseEntity<String> {
        val spec = specReader.read(request)
        val name = nameOf(spec)
        return text(exporter.types(spec, "$name.types", name), "$name.types.d.ts")
    }

    @GetMapping("\${openapi.ui.path:/docs}/export/client.ts")
    @ResponseBody
    fun client(request: HttpServletRequest): ResponseEntity<String> {
        val spec = specReader.read(request)
        val name = nameOf(spec)
        return text(exporter.client(spec, "$name.types", name), "$name.client.ts")
    }

    private fun nameOf(spec: JsonNode): String =
        properties.export.fileStem(applicationName, spec.path("info").path("title").asText(""))

    private fun text(
        body: String,
        filename: String,
    ): ResponseEntity<String> = ResponseEntity
        .ok()
        .contentType(MediaType.valueOf("text/plain; charset=UTF-8"))
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
        .header(HttpHeaders.CACHE_CONTROL, "no-cache")
        .body(body)
}
