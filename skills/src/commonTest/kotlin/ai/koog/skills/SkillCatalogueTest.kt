package ai.koog.skills

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class SkillCatalogueTest {
    @Test
    fun testRenderReturnsNullForEmptyRegistry() = runTest {
        val registry = SkillRegistry.build(emptyList())

        assertNull(SkillCatalogueRenderer.render(registry))
    }

    @Test
    fun testRenderUsesStableLexicalSkillAndFieldOrder() = runTest {
        val registry = registryOf(
            Skill("zeta", "Last", "Hidden last instructions"),
            Skill("alpha", "First", "Hidden first instructions"),
        )

        assertEquals(
            """[{"name":"alpha","description":"First"},{"name":"zeta","description":"Last"}]""",
            SkillCatalogueRenderer.render(registry),
        )
    }

    @Test
    fun testJsonRoundTripPreservesEscapedAndUnicodeMetadata() = runTest {
        val description = "Quote \" slash \\ controls \b\t\n\r emoji 🧪\n---\n[delimiter]: {value}"
        val rendered = SkillCatalogueRenderer.render(
            registryOf(Skill("escaping", description, "Full\nmultiline\ninstructions"))
        )
        val metadata = Json.parseToJsonElement(requireNotNull(rendered)).jsonArray.single().jsonObject

        assertEquals("escaping", metadata.getValue("name").jsonPrimitive.content)
        assertEquals(description, metadata.getValue("description").jsonPrimitive.content)
    }

    @Test
    fun testRenderOmitsInstructionsPathsAndDiagnostics() = runTest {
        val hiddenInstructions = "secret body at /private/skills/alpha/SKILL.md"
        val hiddenDiagnosticPath = SkillSourceReference("/private/source/root")
        val source = SkillSource {
            SkillSourceResult(
                listOf(Skill("alpha", "Visible description", hiddenInstructions)),
                listOf(SkillDiagnostic(SkillError.IoFailure("read", hiddenDiagnosticPath))),
            )
        }
        val rendered = requireNotNull(SkillCatalogueRenderer.render(SkillRegistry.build(listOf(source))))

        assertFalse(rendered.contains(hiddenInstructions))
        assertFalse(rendered.contains("SKILL.md"))
        assertFalse(rendered.contains(hiddenDiagnosticPath.value))
        assertFalse(rendered.contains("diagnostic"))
    }

    @Test
    fun testCatalogueLimitCountsUnicodeCodePointsAndThrowsTypedOverflow() = runTest {
        val registry = registryOf(Skill("unicode", "One 🧪", "Instructions"))
        val rendered = requireNotNull(SkillCatalogueRenderer.render(registry))
        val codePoints = rendered.unicodeCodePoints()

        assertEquals(
            rendered,
            SkillCatalogueRenderer.render(
                registry,
                limits = SkillLimits(maxCatalogueCharacters = codePoints),
            ),
        )
        val exception = assertFailsWith<SkillException> {
            SkillCatalogueRenderer.render(
                registry,
                limits = SkillLimits(maxCatalogueCharacters = codePoints - 1),
            )
        }
        assertEquals(SkillError.CatalogueOverflow, exception.error)
    }

    private suspend fun registryOf(vararg skills: Skill): SkillRegistry = SkillRegistry.build(
        listOf(SkillSource { SkillSourceResult(skills.toList()) })
    )

    private fun String.unicodeCodePoints(): Int = length - windowed(2).count { pair ->
        pair[0].code in 0xD800..0xDBFF && pair[1].code in 0xDC00..0xDFFF
    }
}
