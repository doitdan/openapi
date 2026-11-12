package io.github.doitdan.openapi

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest(
    classes = [io.github.doitdan.openapi.sample.TestApplication::class],
    properties = ["openapi.export.enabled=false", "openapi.mcp.enabled=false"],
)
class UiConfigDisabledTest(
    @Autowired private val context: WebApplicationContext,
) {
    @Test
    fun `export 와 MCP 를 끄면 UI 설정에도 꺼졌다고 알린다`() {
        MockMvcBuilders.webAppContextSetup(context).build()
            .perform(get("/docs/config.json"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.export").value(false))
            .andExpect(jsonPath("$.mcp").value(false))
    }
}
