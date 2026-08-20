package io.github.doitdan.openapi

import io.github.doitdan.openapi.export.TypeScriptExporter
import io.github.doitdan.openapi.mcp.SchemaRenderer
import io.swagger.v3.core.util.Json31
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NullableSchemaTest {
    private val spec = Json31.mapper().readTree(
        """
        {
          "openapi": "3.1.0",
          "paths": {},
          "components": {
            "schemas": {
              "SampleRequest": {
                "type": "object",
                "properties": {
                  "memo": { "type": ["string", "null"], "description": "설명" },
                  "attempts": { "type": ["integer", "null"], "format": "int32" },
                  "tags": { "type": ["array", "null"], "items": { "type": "string" } },
                  "name": { "type": "string" },
                  "owner": { "oneOf": [{ "${"$"}ref": "#/components/schemas/Owner" }, { "type": "null" }] },
                  "detail": { "oneOf": [{ "${"$"}ref": "#/components/schemas/Owner" }, { "${"$"}ref": "#/components/schemas/Tag" }] }
                },
                "required": ["name"]
              },
              "Owner": { "type": "object", "properties": { "id": { "type": "integer", "format": "int64" } } },
              "Tag": { "type": "object", "properties": { "label": { "type": "string" } } }
            }
          }
        }
        """.trimIndent(),
    )

    private val schema = spec.path("components").path("schemas").path("SampleRequest")

    @Test
    fun `nullable 타입 배열에서 실제 타입을 읽는다`() {
        val renderer = SchemaRenderer(spec)

        assertEquals(
            listOf(
                "- memo (string, optional): 설명",
                "- attempts (int32, optional)",
                "- tags (array of string, optional)",
                "- name (string, required)",
                "- owner (Owner, optional)",
                "- owner.id (int64, optional)",
                "- detail (Owner | Tag, optional)",
            ),
            renderer.fields(schema),
        )
    }

    @Test
    fun `nullable 필드의 예시가 타입에 맞게 생성된다`() {
        val example = Json31.pretty(SchemaRenderer(spec).example(schema))

        assertTrue(example.contains("\"memo\" : \"string\""), example)
        assertTrue(example.contains("\"attempts\" : 0"), example)
        assertTrue(example.contains("\"tags\" : [ \"string\" ]"), example)
    }

    @Test
    fun `nullable 필드가 TypeScript에서 unknown이 되지 않는다`() {
        val types = TypeScriptExporter().types(spec)

        assertTrue(types.contains("memo?: string;"), types)
        assertTrue(types.contains("attempts?: number;"), types)
        assertTrue(types.contains("tags?: string[];"), types)
        assertTrue(types.contains("owner?: Owner;"), types)
        assertTrue(types.contains("detail?: Owner | Tag;"), types)
        assertTrue(!types.contains("unknown"), types)
    }
}
