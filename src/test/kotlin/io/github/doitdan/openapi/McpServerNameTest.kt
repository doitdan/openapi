package io.github.doitdan.openapi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class McpServerNameTest {
    private val mcp = OpenApiProperties.Mcp()

    @Test
    fun `api가 없는 애플리케이션 이름에는 api를 넣는다`() {
        assertEquals("coach-api-docs", mcp.serverName("coach"))
        assertEquals("next-coaching-api-docs", mcp.serverName("next-coaching"))
    }

    @Test
    fun `이미 api를 담은 이름에는 넣지 않는다`() {
        assertEquals("orders-api-docs", mcp.serverName("orders-api"))
        assertEquals("api-gateway-docs", mcp.serverName("api-gateway"))
    }

    @Test
    fun `api를 품은 단어는 api로 치지 않는다`() {
        assertEquals("rapid-api-docs", mcp.serverName("rapid"))
    }

    @Test
    fun `설정한 이름이 있으면 그대로 쓴다`() {
        mcp.name = "Coach Docs"
        assertEquals("coach-docs", mcp.serverName("coach"))
    }

    @Test
    fun `애플리케이션 이름이 없으면 기본값을 쓴다`() {
        assertEquals("openapi-docs", mcp.serverName(""))
    }
}
