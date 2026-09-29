package prompt

import ai.koog.skills.model.Skill
import ai.koog.skills.prompt.SkillsPromptFormat
import ai.koog.skills.prompt.generateSkillsPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Imported Koog 1.3.0 (3acc88cf) goldens with British English fixtures; XML rejection regression added. */
class SkillsPromptTest {
    @Test
    fun testEmptySkillsRetainEveryUpstreamFormatEnvelope() {
        assertEquals(
            "<available_skills>\n</available_skills>",
            generateSkillsPrompt(emptyList(), SkillsPromptFormat.XML),
        )
        assertEquals(
            "{\n  \"available_skills\": [\n  ]\n}",
            generateSkillsPrompt(emptyList(), SkillsPromptFormat.JSON),
        )
        assertEquals(
            "available_skills:\n  []",
            generateSkillsPrompt(emptyList(), SkillsPromptFormat.YML),
        )
    }

    private val skills = listOf(
        Skill(
            name = "pdf-processing",
            description = "Extract PDF text, fill forms, merge files.",
            location = "/home/user/.agents/skills/pdf-processing/SKILL.md",
            license = "Apache-2.0",
            compatibility = "Requires local filesystem access",
            metadata = mapOf("author" to "koog", "version" to "1.0"),
            allowedTools = "Bash(git:*) Read",
        ),
        Skill(
            name = "data-analysis",
            description = "Analyse datasets and create summary reports.",
            location = "/home/user/project/.agents/skills/data-analysis/SKILL.md",
        ),
    )

    @Test
    fun testGenerateSkillsPromptGeneratesXmlPromptWithLocation() {
        val prompt = generateSkillsPrompt(skills, SkillsPromptFormat.XML)

        val expected =
            """
            <available_skills>
              <skill>
                <name>pdf-processing</name>
                <description>Extract PDF text, fill forms, merge files.</description>
                <location>/home/user/.agents/skills/pdf-processing/SKILL.md</location>
              </skill>
              <skill>
                <name>data-analysis</name>
                <description>Analyse datasets and create summary reports.</description>
                <location>/home/user/project/.agents/skills/data-analysis/SKILL.md</location>
              </skill>
            </available_skills>
            """.trimIndent()

        assertEquals(expected, prompt)
    }

    @Test
    fun testGenerateSkillsPromptGeneratesJsonPromptWithoutLocation() {
        val prompt = generateSkillsPrompt(skills, SkillsPromptFormat.JSON, includeLocation = false)

        val expected =
            """
            {
              "available_skills": [
              {
                "name": "pdf-processing",
                "description": "Extract PDF text, fill forms, merge files."
              },
              {
                "name": "data-analysis",
                "description": "Analyse datasets and create summary reports."
              }
              ]
            }
            """.trimIndent()

        assertEquals(expected, prompt)
    }

    @Test
    fun testGenerateSkillsPromptGeneratesYmlPromptWithLocation() {
        val prompt = generateSkillsPrompt(skills, SkillsPromptFormat.YML)

        val expected =
            """
            available_skills:
              - name: "pdf-processing"
                description: "Extract PDF text, fill forms, merge files."
                location: "/home/user/.agents/skills/pdf-processing/SKILL.md"
              - name: "data-analysis"
                description: "Analyse datasets and create summary reports."
                location: "/home/user/project/.agents/skills/data-analysis/SKILL.md"
            """.trimIndent()

        assertEquals(expected, prompt)
    }

    @Test
    fun testGenerateSkillsPromptGeneratesYmlPromptWithoutLocation() {
        val prompt = generateSkillsPrompt(skills, SkillsPromptFormat.YML, includeLocation = false)

        val expected =
            """
            available_skills:
              - name: "pdf-processing"
                description: "Extract PDF text, fill forms, merge files."
              - name: "data-analysis"
                description: "Analyse datasets and create summary reports."
            """.trimIndent()

        assertEquals(expected, prompt)
    }

    @Test
    fun testGenerateSkillsPromptIncludesOptionalFieldsInXml() {
        val prompt = generateSkillsPrompt(
            skills = skills,
            format = SkillsPromptFormat.XML,
            includeLocation = true,
            includeLicense = true,
            includeCompatibility = true,
            includeMetadata = true,
            includeAllowedTools = true,
        )

        val expected =
            """
            <available_skills>
              <skill>
                <name>pdf-processing</name>
                <description>Extract PDF text, fill forms, merge files.</description>
                <location>/home/user/.agents/skills/pdf-processing/SKILL.md</location>
                <license>Apache-2.0</license>
                <compatibility>Requires local filesystem access</compatibility>
                <metadata>
                  <entry key="author">koog</entry>
                  <entry key="version">1.0</entry>
                </metadata>
                <allowed-tools>Bash(git:*) Read</allowed-tools>
              </skill>
              <skill>
                <name>data-analysis</name>
                <description>Analyse datasets and create summary reports.</description>
                <location>/home/user/project/.agents/skills/data-analysis/SKILL.md</location>
              </skill>
            </available_skills>
            """.trimIndent()

        assertEquals(expected, prompt)
    }

    @Test
    fun testGenerateSkillsPromptIncludesOptionalFieldsInJson() {
        val prompt = generateSkillsPrompt(
            skills = skills,
            format = SkillsPromptFormat.JSON,
            includeLocation = true,
            includeLicense = true,
            includeCompatibility = true,
            includeMetadata = true,
            includeAllowedTools = true,
        )

        val expected =
            """
            {
              "available_skills": [
              {
                "name": "pdf-processing",
                "description": "Extract PDF text, fill forms, merge files."
                ,"location": "/home/user/.agents/skills/pdf-processing/SKILL.md"
                ,"license": "Apache-2.0"
                ,"compatibility": "Requires local filesystem access"
                ,"metadata": {
                  "author": "koog",
                  "version": "1.0"
                }
                ,"allowed-tools": "Bash(git:*) Read"
              },
              {
                "name": "data-analysis",
                "description": "Analyse datasets and create summary reports."
                ,"location": "/home/user/project/.agents/skills/data-analysis/SKILL.md"
              }
              ]
            }
            """.trimIndent()

        assertEquals(expected, prompt)
    }

    @Test
    fun testGenerateSkillsPromptIncludesOptionalFieldsInYml() {
        val prompt = generateSkillsPrompt(
            skills = skills,
            format = SkillsPromptFormat.YML,
            includeLocation = true,
            includeLicense = true,
            includeCompatibility = true,
            includeMetadata = true,
            includeAllowedTools = true,
        )

        val expected =
            """
            available_skills:
              - name: "pdf-processing"
                description: "Extract PDF text, fill forms, merge files."
                location: "/home/user/.agents/skills/pdf-processing/SKILL.md"
                license: "Apache-2.0"
                compatibility: "Requires local filesystem access"
                metadata:
                  author: "koog"
                  version: "1.0"
                allowed-tools: "Bash(git:*) Read"
              - name: "data-analysis"
                description: "Analyse datasets and create summary reports."
                location: "/home/user/project/.agents/skills/data-analysis/SKILL.md"
            """.trimIndent()

        assertEquals(expected, prompt)
    }
    @Test
    fun testXmlRejectsForbiddenCharactersAndUnpairedSurrogatesInEveryIncludedField() {
        val invalidValues = (0..31).filter { it !in listOf(9, 10, 13) }.map { it.toChar().toString() } +
            listOf("\uFFFE", "\uFFFF", "\uD800", "\uDC00", "\uD800x", "\uD800\uD800")
        for (value in invalidValues) {
            val base = Skill("alpha", "Description", "/location")
            val variants = listOf(
                base.copy(name = value), base.copy(description = value), base.copy(location = value),
                base.copy(license = value), base.copy(compatibility = value), base.copy(allowedTools = value),
                base.copy(metadata = mapOf(value to "value")), base.copy(metadata = mapOf("key" to value)),
            )
            for (skill in variants) {
                assertFailsWith<IllegalArgumentException> {
                    generateSkillsPrompt(listOf(skill), SkillsPromptFormat.XML,
                        includeLicense = true, includeCompatibility = true, includeMetadata = true, includeAllowedTools = true)
                }
            }
            assertEquals(generateSkillsPrompt(listOf(base), SkillsPromptFormat.XML, includeLocation = false),
                generateSkillsPrompt(listOf(base.copy(location = value, license = value, compatibility = value,
                    metadata = mapOf(value to value), allowedTools = value)), SkillsPromptFormat.XML, includeLocation = false))
        }
    }

}
