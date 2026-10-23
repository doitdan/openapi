package io.github.doitdan.openapi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.time.Duration
import java.time.Instant
import java.util.Locale

@ExtendWith(OutputCaptureExtension::class)
@SpringBootTest(
    classes = [io.github.doitdan.openapi.sample.TestApplication::class],
    properties = ["openapi.warm-up.enabled=true", "springdoc.allowed-locales=ko-KR"],
)
class SpecWarmUpTest(
    @Autowired private val context: WebApplicationContext,
) {
    @Test
    fun `켜면 준비된 뒤 문서를 미리 지어 다른 언어의 첫 요청도 다시 짓지 않는다`(output: CapturedOutput) {
        awaitSpecBuilt(output)

        MockMvcBuilders.webAppContextSetup(context).build()
            .perform(get("/v3/api-docs").locale(Locale.ENGLISH))
            .andExpect(status().isOk)

        assertEquals(1, Regex(SPEC_BUILT).findAll(output.all).count())
    }

    private fun awaitSpecBuilt(output: CapturedOutput) {
        val deadline = Instant.now().plus(Duration.ofSeconds(30))
        while (!output.all.contains(SPEC_BUILT)) {
            assertTrue(Instant.now().isBefore(deadline), "준비된 뒤 30초 안에 문서를 짓지 않았다")
            Thread.sleep(50)
        }
    }

    private companion object {
        const val SPEC_BUILT = "Init duration for springdoc-openapi"
    }
}
