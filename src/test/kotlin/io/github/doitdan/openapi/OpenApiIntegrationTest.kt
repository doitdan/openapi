package io.github.doitdan.openapi

import io.swagger.v3.core.util.Json31
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasItems
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest(classes = [io.github.doitdan.openapi.sample.TestApplication::class])
class OpenApiIntegrationTest(
    @Autowired private val context: WebApplicationContext,
) {
    private lateinit var mockMvc: MockMvc
    private lateinit var apiDocs: String

    @BeforeEach
    fun setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build()
        apiDocs = mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .getContentAsString(Charsets.UTF_8)
    }

    @Test
    fun `POST 성공 응답을 201로 유도한다`() {
        assertTrue(apiDocs.contains("\"201\":{\"description\":\"CREATED\""))
    }

    @Test
    fun `enum 프로퍼티에 description 목록을 첨부한다`() {
        assertTrue(apiDocs.contains("- `ACTIVE`: 활성"))
        assertTrue(apiDocs.contains("- `INACTIVE`: 비활성"))
    }

    @Test
    fun `enum 설명을 구조화된 확장 필드로도 노출한다`() {
        assertTrue(apiDocs.contains("\"x-enum-descriptions\""))
        assertTrue(apiDocs.contains("\"ACTIVE\":\"활성\""))
    }

    @Test
    fun `enum 참조 애노테이션이 붙은 String 필드에 값 목록을 채운다`() {
        assertTrue(apiDocs.contains("\"enum\":[\"ACTIVE\",\"INACTIVE\"]"))
    }

    @Test
    fun `md 파일을 operation description으로 주입한다`() {
        assertTrue(apiDocs.contains("샘플을 등록한다."))
        assertTrue(apiDocs.contains("코드가 중복이면 409를 반환한다."))
    }

    @Test
    fun `글로브 패턴 위치의 md 파일도 주입한다`() {
        assertTrue(apiDocs.contains("글로브 패턴으로 찾은 문서다."))
    }

    @Test
    fun `컨트롤러 옆 docs 패키지의 md도 주입한다`() {
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(
                jsonPath(
                    "$.paths['/samples/{sampleId}'].get.description",
                    containsString("컨트롤러 옆 docs 패키지에서 읽어온 문서다."),
                ),
            )
    }

    @Test
    fun `md 문서가 각 endpoint에 정확히 매칭된다`() {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.paths['/samples'].post.description", containsString("코드가 중복이면 409를 반환한다.")))
            .andExpect(jsonPath("$.paths['/samples'].post.description", not(containsString("오버로드"))))
            .andExpect(jsonPath("$.paths['/samples/bulk'].post.description", containsString("같은 이름의 오버로드지만 경로로 구분된 문서다.")))
            .andExpect(jsonPath("$.paths['/samples/bulk'].post.description", not(containsString("코드가 중복이면"))))
            .andExpect(jsonPath("$.paths['/samples'].get.description", containsString("글로브 패턴으로 찾은 문서다.")))
            .andExpect(jsonPath("$.paths['/samples/alias-two'].get.description", containsString("경로가 여러 개인 매핑의 두 번째 경로 문서다.")))
            .andExpect(jsonPath("$.paths['/samples/alias'].get.description").doesNotExist())
    }

    @Test
    fun `Kotlin의 is 접두 Boolean 이름을 스펙에서 유지한다`() {
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(jsonPath("$.components.schemas.SampleResponse.properties.isSynced").exists())
            .andExpect(jsonPath("$.components.schemas.SampleResponse.properties.synced").doesNotExist())
            .andExpect(jsonPath("$.components.schemas.SampleResponse.properties.hasChild").exists())

        val types = callTool("""{"name":"get_typescript","arguments":{"kind":"types"}}""")
        assertTrue(types.contains("isSynced?: boolean"), types)
        assertTrue(!types.contains("synced?: boolean"), types)
    }

    @Test
    fun `오류 응답과 공개 경로를 스펙에 표시한다`() {
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(jsonPath("$.paths['/samples'].post.responses.400.description", containsString("보내면")))
            .andExpect(jsonPath("$.paths['/samples'].post.responses.500.description").exists())
            .andExpect(jsonPath("$.paths['/samples/{sampleId}'].get.responses.404").exists())
            // 충돌은 쓰기에서만 생긴다
            .andExpect(jsonPath("$.paths['/samples'].post.responses.409").exists())
            .andExpect(jsonPath("$.paths['/samples'].get.responses.409").doesNotExist())
            .andExpect(jsonPath("$.paths['/samples/alias'].get.security").isEmpty)
    }

    @Test
    fun `export manifest 가 출처를 밝힌다`() {
        mockMvc
            .perform(get("/docs/export/manifest.json"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.specHash").isNotEmpty)
            .andExpect(jsonPath("$.apiVersion").exists())
    }

    @Test
    fun `Kotlin non-null 프로퍼티를 required 로 문서화한다`() {
        mockMvc
            .perform(get("/v3/api-docs"))
            .andExpect(jsonPath("$.components.schemas.SampleResponse.required", hasItems("status", "code")))
            // 기본값이 있는 파라미터는 보내지 않아도 되므로 required 가 아니다
            .andExpect(jsonPath("$.components.schemas.SampleResponse.required", not(hasItems("isSynced", "hasChild"))))
            .andExpect(jsonPath("$.components.schemas.SampleRequest.required").doesNotExist())

        val types = callTool("""{"name":"get_typescript","arguments":{"kind":"types"}}""")
        assertTrue(types.contains("status: \"ACTIVE\" | \"INACTIVE\";"), types)
        assertTrue(types.contains("isSynced?: boolean"), types)
    }

    @Test
    fun `MCP 서버가 도구를 노출하고 문서를 답한다`() {
        mockMvc
            .perform(
                post("/docs/mcp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"))
            .andExpect(jsonPath("$.result.capabilities.tools").exists())
            .andExpect(jsonPath("$.result.serverInfo.name").value("sample-api-docs"))

        mockMvc
            .perform(
                post("/docs/mcp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""),
            ).andExpect(status().isAccepted)

        mockMvc
            .perform(
                post("/docs/mcp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}"""),
            ).andExpect(status().isOk)
            .andExpect(
                jsonPath(
                    "$.result.tools[*].name",
                    hasItems("list_endpoints", "get_endpoint", "search_docs", "get_schema", "get_typescript"),
                ),
            )
    }

    @Test
    fun `MCP endpoint 문서가 서버 주소와 중첩 필드를 담는다`() {
        val body = callTool("""{"name":"get_endpoint","arguments":{"method":"post","path":"/samples"}}""")

        assertTrue(body.contains("server: http://localhost\n"), body.lineSequence().take(5).joinToString("\n"))
        assertTrue(body.contains("## Request body"))
        assertTrue(body.contains("allowed: ACTIVE"))
        assertTrue(body.contains("### Example"))
    }

    @Test
    fun `MCP가 서비스 이름을 붙인 TypeScript를 내려준다`() {
        val types = callTool("""{"name":"get_typescript","arguments":{"kind":"types"}}""")
        assertTrue(types.contains("export interface SampleResponse {"))
        assertTrue(types.contains("import type * as "))

        val client = callTool("""{"name":"get_typescript","arguments":{"kind":"client"}}""")
        assertTrue(client.contains("ClientOptions {"))
        assertTrue(client.contains("Client = ReturnType<typeof create"))
    }

    private fun callTool(params: String): String {
        val response = mockMvc
            .perform(
                post("/docs/mcp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"jsonrpc":"2.0","id":9,"method":"tools/call","params":$params}"""),
            ).andExpect(status().isOk)
            .andReturn()
            .response
            .getContentAsString(Charsets.UTF_8)
        return Json31.mapper().readTree(response).path("result").path("content").first().path("text").asText()
    }

    @Test
    fun `애플리케이션 이름으로 MCP 서버와 export 파일 이름을 짓는다`() {
        mockMvc
            .perform(get("/docs/config.json"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mcpName").value("sample-api-docs"))

        mockMvc
            .perform(get("/docs/export/manifest.json"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("sample-api"))
            .andExpect(jsonPath("$.types").value("sample-api.types.d.ts"))
            .andExpect(jsonPath("$.client").value("sample-api.client.ts"))
    }

    @Test
    fun `TypeScript 인터페이스를 서버 이름으로 내보낸다`() {
        mockMvc
            .perform(get("/docs/export/manifest.json"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.types").value(containsString(".types.d.ts")))
            .andExpect(jsonPath("$.client").value(containsString(".client.ts")))

        val types = mockMvc
            .perform(get("/docs/export/types.d.ts"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .getContentAsString(Charsets.UTF_8)
        assertTrue(types.contains("export interface SampleResponse {"))
        assertTrue(types.contains("\"ACTIVE\" | \"INACTIVE\""))

        val client = mockMvc
            .perform(get("/docs/export/client.ts"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .getContentAsString(Charsets.UTF_8)
        assertTrue(client.contains("export function create") && client.contains("Client(options:"))
        assertTrue(client.contains("request<"))
    }

    @Test
    fun `문서 UI와 설정을 서빙한다`() {
        mockMvc.perform(get("/docs")).andExpect(status().is3xxRedirection)

        val page = mockMvc
            .perform(get("/docs/index.html"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .getContentAsString(Charsets.UTF_8)
        assertTrue(page.contains("app.js"))

        val config = mockMvc
            .perform(get("/docs/config.json"))
            .andExpect(status().isOk)
            .andReturn()
            .response
            .getContentAsString(Charsets.UTF_8)
        assertTrue(config.contains("\"docsUrl\":\"/v3/api-docs\""))
    }
}
