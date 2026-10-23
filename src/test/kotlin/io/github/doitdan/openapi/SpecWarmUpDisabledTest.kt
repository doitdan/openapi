package io.github.doitdan.openapi

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

@SpringBootTest(classes = [io.github.doitdan.openapi.sample.TestApplication::class])
class SpecWarmUpDisabledTest(
    @Autowired private val context: ApplicationContext,
) {
    @Test
    fun `설정하지 않으면 문서를 미리 짓지 않는다`() {
        assertTrue(context.getBeanNamesForType(SpecWarmUp::class.java).isEmpty())
    }
}
