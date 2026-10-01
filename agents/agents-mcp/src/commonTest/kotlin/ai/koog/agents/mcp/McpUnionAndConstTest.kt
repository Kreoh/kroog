package ai.koog.agents.mcp

import ai.koog.agents.core.tools.ToolParameterType
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class McpUnionAndConstTest {
    @Test
    fun testBranchArrayItemsRetainParentTypeWhenNarrowedByConstant() {
        val alternatives = assertIs<ToolParameterType.AnyOf>(
            parse("""{"type":"array","items":{"type":"string"},"anyOf":[{"items":{"const":"fixed"}}]}"""),
        )
        val items = assertIs<ToolParameterType.List>(alternatives.types.single().type).itemsType
        assertEquals(ToolParameterType.Enum(arrayOf("fixed")), items)
    }

    @Test
    fun testBranchAdditionalPropertiesCannotRelaxParentShape() {
        val closed = assertIs<ToolParameterType.AnyOf>(
            parse("""{"type":"object","additionalProperties":false,"anyOf":[{"additionalProperties":true}]}"""),
        )
        assertEquals(false, assertIs<ToolParameterType.Object>(closed.types.single().type).additionalProperties)
        val inherited = assertIs<ToolParameterType.AnyOf>(
            parse("""{"type":"object","additionalProperties":{"type":"string"},"anyOf":[{"additionalProperties":true}]}"""),
        )
        assertEquals(
            ToolParameterType.String,
            assertIs<ToolParameterType.Object>(inherited.types.single().type).additionalPropertiesType,
        )
        val narrowed = assertIs<ToolParameterType.AnyOf>(
            parse("""{"type":"object","additionalProperties":{"type":"string"},"anyOf":[{"additionalProperties":{"const":"fixed"}}]}"""),
        )
        assertEquals(
            ToolParameterType.Enum(arrayOf("fixed")),
            assertIs<ToolParameterType.Object>(narrowed.types.single().type).additionalPropertiesType,
        )
    }

    @Test
    fun testArrayConstraintAlternativesKeepParentItems() {
        for (keyword in listOf("anyOf", "oneOf")) {
            val alternatives = assertIs<ToolParameterType.AnyOf>(
                parse("""{"type":"array","items":{"type":"string"},"$keyword":[{"maxItems":5},{"minItems":10}]}"""),
            )
            alternatives.types.forEach { alternative ->
                assertEquals(ToolParameterType.String, assertIs<ToolParameterType.List>(alternative.type).itemsType)
            }
        }
    }

    @Test
    fun testObjectAlternativesKeepCommonPropertiesAndRequiredFields() {
        val alternatives = assertIs<ToolParameterType.AnyOf>(
            parse(
                """{
                "type":"object",
                "properties":{"common":{"type":"string"},"kind":{"type":"string"}},
                "required":["common"],
                "anyOf":[
                    {"properties":{"kind":{"const":"first"},"extra":{"type":"integer"}},"required":["kind","extra"]},
                    {"properties":{"kind":{"const":"second"}},"required":["kind"]}
                ]
            }""",
            ),
        )
        val first = assertIs<ToolParameterType.Object>(alternatives.types[0].type)
        assertEquals(listOf("common", "kind", "extra"), first.properties.map { it.name })
        assertEquals(listOf("common", "kind", "extra"), first.requiredProperties)
        assertEquals(ToolParameterType.Enum(arrayOf("first")), first.properties[1].type)
        val second = assertIs<ToolParameterType.Object>(alternatives.types[1].type)
        assertEquals(listOf("common", "kind"), second.properties.map { it.name })
        assertEquals(listOf("common", "kind"), second.requiredProperties)
    }

    @Test
    fun testReferencedArrayConstraintKeepsParentItems() {
        val defs = Json.parseToJsonElement("""{"Constraint":{"maxItems":5}}""").jsonObject
        val type = assertIs<ToolParameterType.AnyOf>(
            parse("""{"type":"array","items":{"type":"string"},"anyOf":[{"${'$'}ref":"#/${'$'}defs/Constraint"}]}""", defs),
        )
        assertEquals(ToolParameterType.String, assertIs<ToolParameterType.List>(type.types.single().type).itemsType)
    }

    @Test
    fun testTypedConstraintAlternativesInheritParentType() {
        for (keyword in listOf("anyOf", "oneOf")) {
            val type = assertIs<ToolParameterType.AnyOf>(
                parse("""{"type":"string","$keyword":[{"maxLength":5},{"pattern":"^a"}]}"""),
            )
            assertEquals(listOf(ToolParameterType.String, ToolParameterType.String), type.types.map { it.type })
        }
    }

    @Test
    fun testTypelessReferenceAlternativeInheritsParentType() {
        val defs = Json.parseToJsonElement("""{"Constraint":{"maxLength":5}}""").jsonObject
        val type = assertIs<ToolParameterType.AnyOf>(
            parse("""{"type":"string","anyOf":[{"${'$'}ref":"#/${'$'}defs/Constraint"}]}""", defs),
        )
        assertEquals(ToolParameterType.String, type.types.single().type)
    }

    private fun parse(schema: String, defs: JsonObject? = null): ToolParameterType =
        DefaultMcpToolDescriptorParser.parse(
            Tool(
                name = "union",
                description = "Union schema",
                inputSchema = ToolSchema(
                    properties = buildJsonObject { put("value", Json.parseToJsonElement(schema)) },
                    required = listOf("value"),
                    defs = defs,
                ),
                outputSchema = null,
                annotations = null,
                title = null,
            )
        ).requiredParameters.single().type

    @Test
    fun testOneOfWithReferencesAndStringDiscriminators() {
        val defs = Json.parseToJsonElement(
            """{
            "Path":{"type":"object","properties":{"kind":{"const":"path"},"path":{"type":"string"}},"required":["kind","path"]},
            "Active":{"type":"object","properties":{"kind":{"type":"string","const":"active"}},"required":["kind"]}
        }"""
        ).jsonObject
        val type = assertIs<ToolParameterType.AnyOf>(
            parse(
                """{"oneOf":[{"${'$'}ref":"#/${'$'}defs/Path"},{"${'$'}ref":"#/${'$'}defs/Active"}]}""",
                defs,
            )
        )
        assertEquals(2, type.types.size)
        val path = assertIs<ToolParameterType.Object>(type.types[0].type)
        assertEquals(listOf("kind", "path"), path.requiredProperties)
        assertEquals(ToolParameterType.Enum(arrayOf("path")), path.properties[0].type)
        val active = assertIs<ToolParameterType.Object>(type.types[1].type)
        assertEquals(ToolParameterType.Enum(arrayOf("active")), active.properties[0].type)
    }

    @Test
    fun testOneOfInsideArrayRetainsEachShape() {
        val type = assertIs<ToolParameterType.List>(
            parse(
                """{"type":"array","items":{"oneOf":[{"type":"string"},{"type":"integer"}]}}""",
            )
        )
        val alternatives = assertIs<ToolParameterType.AnyOf>(type.itemsType)
        assertEquals(listOf(ToolParameterType.String, ToolParameterType.Integer), alternatives.types.map { it.type })
    }

    @Test
    fun testStringConstantNarrowsNullableTypeToTheConstant() {
        assertEquals(
            ToolParameterType.Enum(arrayOf("fixed")),
            parse("""{"type":["string","null"],"const":"fixed"}"""),
        )
    }

    @Test
    fun testNonStringConstantsAndContradictoryTypesAreRejected() {
        for (constant in listOf("42", "true", "null", "[]", "{}")) {
            assertFailsWith<IllegalArgumentException> { parse("""{"const":$constant}""") }
        }
        assertFailsWith<IllegalArgumentException> { parse("""{"type":"number","const":"42"}""") }
    }

    @Test
    fun testRecursiveUnionsRetainDepthLimit() {
        for (keyword in listOf("anyOf", "oneOf")) {
            val defs = buildJsonObject {
                put("Loop", Json.parseToJsonElement("""{"$keyword":[{"${'$'}ref":"#/${'$'}defs/Loop"},{"type":"string"}]}"""))
            }
            val error = assertFailsWith<IllegalArgumentException> {
                parse("""{"${'$'}ref":"#/${'$'}defs/Loop"}""", defs)
            }
            kotlin.test.assertTrue(error.message.orEmpty().contains("Maximum recursion depth"))
            assertFailsWith<IllegalArgumentException> { parse("""{"$keyword":[]}""") }
        }
    }
}
