package ai.koog.skills

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class SkillParserTest {
    @Test
    fun `parses plain and folded scalars with LF and CRLF`() {
        val plain = document("name: plain\ndescription: A plain description")
        val folded = document("name: folded\ndescription: >\n  first line\n  second line").replace("\n", "\r\n")

        assertEquals("A plain description", SkillParser.parse(plain).description)
        assertEquals("first line second line", SkillParser.parse(folded).description)
    }

    @Test
    fun `requires exact closed frontmatter boundaries`() {
        listOf(
            "name: absent\ndescription: absent\n---\nbody",
            "---\nname: open\ndescription: open\nbody",
            "--- extra\nname: bad\ndescription: bad\n---\nbody",
            "---\rname: bad\r---\rbody",
        ).forEach { input ->
            assertIs<SkillError.MalformedFrontmatter>(assertFailsWith<SkillException> { SkillParser.parse(input) }.error)
        }
    }

    @Test
    fun `rejects malformed YAML and duplicate keys`() {
        val malformed = document("name: [broken\ndescription: broken")
        val duplicate = document("name: first\nname: second\ndescription: duplicate")
        assertIs<SkillError.MalformedFrontmatter>(assertFailsWith<SkillException> { SkillParser.parse(malformed) }.error)
        assertIs<SkillError.MalformedFrontmatter>(assertFailsWith<SkillException> { SkillParser.parse(duplicate) }.error)
    }

    @Test
    fun `rejects collection and scalar aliases before loading`() {
        val collectionAlias = document("name: alias\ndescription: &value [one, two]\nextra: *value")
        val scalarAlias = document("name: alias\ndescription: &value words\nextra: *value")
        val policy = SkillLoadPolicy(unknownField = UnknownFieldPolicy.IGNORE)
        listOf(collectionAlias, scalarAlias).forEach { input ->
            val error = assertFailsWith<SkillException> { SkillParser.parse(input, policy = policy) }
            assertIs<SkillError.MalformedFrontmatter>(error.error)
        }
    }

    @Test
    fun `enforces YAML nesting and code point limits`() {
        val nested = document("name: nested\ndescription: value\nextra: [[[deep]]]")
        val depthPolicy = SkillLoadPolicy(
            limits = SkillLimits(maxYamlNestingDepth = 2),
            unknownField = UnknownFieldPolicy.IGNORE,
        )
        assertIs<SkillError.LimitExceeded>(
            assertFailsWith<SkillException> { SkillParser.parse(nested, policy = depthPolicy) }.error
        )
        val codePointPolicy = SkillLoadPolicy(limits = SkillLimits(maxYamlCodePoints = 10))
        assertIs<SkillError.LimitExceeded>(
            assertFailsWith<SkillException> { SkillParser.parse(document(), policy = codePointPolicy) }.error
        )
    }

    @Test
    fun `rejects non-mapping root and non-string required fields`() {
        assertIs<SkillError.MalformedFrontmatter>(
            assertFailsWith<SkillException> { SkillParser.parse(document("- name\n- description")) }.error
        )
        listOf(
            "name: 12\ndescription: words",
            "name: valid\ndescription: [words]",
        ).forEach { yaml ->
            assertIs<SkillError.InvalidField>(assertFailsWith<SkillException> { SkillParser.parse(document(yaml)) }.error)
        }
    }

    @Test
    fun `rejects missing blank fields and blank body`() {
        listOf(
            document("description: words"),
            document("name: valid"),
            document("name: ''\ndescription: words"),
            document("name: valid\ndescription: '   '"),
            "---\nname: valid\ndescription: words\n---\n   ",
        ).forEach { input ->
            assertIs<SkillError.InvalidField>(assertFailsWith<SkillException> { SkillParser.parse(input) }.error)
        }
    }

    @Test
    fun `enforces name grammar and boundary lengths`() {
        listOf("Upper", "-leading", "trailing-", "double--hyphen", "under_score", "a".repeat(65)).forEach { name ->
            assertFailsWith<SkillException> { SkillParser.parse(document("name: $name\ndescription: words")) }
        }
        assertEquals("a".repeat(64), SkillParser.parse(document("name: ${"a".repeat(64)}\ndescription: words")).name)
        assertEquals("a", SkillParser.parse(document("name: a\ndescription: words")).name)
        assertEquals("a-1", SkillParser.parse(document("name: a-1\ndescription: words")).name)
    }

    @Test
    fun `enforces expected directory name`() {
        val error = assertFailsWith<SkillException> { SkillParser.parse(document(), expectedName = "other") }
        assertIs<SkillError.InvalidField>(error.error)
    }

    @Test
    fun `unknown fields follow policy`() {
        val input = document("name: example\ndescription: words\nlicense: Apache-2.0")
        assertFailsWith<SkillException> { SkillParser.parse(input) }
        assertEquals("example", SkillParser.parse(input, policy = SkillLoadPolicy(unknownField = UnknownFieldPolicy.IGNORE)).name)
    }

    @Test
    fun `description and body limits are enforced`() {
        val policy = SkillLoadPolicy(limits = SkillLimits(maxDescriptionCharacters = 3, maxInstructionCharacters = 3))
        assertIs<SkillError.LimitExceeded>(
            assertFailsWith<SkillException> { SkillParser.parse(document(description = "four"), policy = policy) }.error
        )
        assertIs<SkillError.LimitExceeded>(
            assertFailsWith<SkillException> { SkillParser.parse(document(body = "four"), policy = policy) }.error
        )
    }

    @Test
    fun `description and body limits count Unicode code points`() {
        val policy = SkillLoadPolicy(
            limits = SkillLimits(maxDescriptionCharacters = 1, maxInstructionCharacters = 1),
        )
        val accepted = SkillParser.parse(document(body = "😀", description = "😀"), policy = policy)
        assertEquals("😀", accepted.description)
        assertEquals("😀", accepted.instructions)
        assertIs<SkillError.LimitExceeded>(
            assertFailsWith<SkillException> {
                SkillParser.parse(document(body = "😀😀", description = "😀"), policy = policy)
            }.error
        )
        assertIs<SkillError.LimitExceeded>(
            assertFailsWith<SkillException> {
                SkillParser.parse(document(body = "😀", description = "😀😀"), policy = policy)
            }.error
        )
    }

    private fun document(
        yaml: String = "name: example\ndescription: words",
        body: String = "Do the work.",
        description: String? = null,
    ): String {
        val actualYaml = description?.let { "name: example\ndescription: $it" } ?: yaml
        return "---\n$actualYaml\n---\n$body"
    }
}
