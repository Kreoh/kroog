package ai.koog.skills

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class InMemorySkillSourceTest {
    @Test
    fun `orders skills and snapshots its input`() = runTest {
        val input = mutableListOf(skill("zulu"), skill("alpha"))
        val source = InMemorySkillSource(input)
        input.clear()

        val snapshot = source.load()
        assertEquals(listOf("alpha", "zulu"), snapshot.skills.map { it.name })
        assertFalse(snapshot.skills is MutableList<*>)
        assertFalse(snapshot.diagnostics is MutableList<*>)
    }

    @Test
    fun `fails duplicate names by default`() = runTest {
        val source = InMemorySkillSource(listOf(skill("same", "first"), skill("same", "second")))
        val error = assertFailsWith<SkillException> { source.load() }
        assertIs<SkillError.DuplicateName>(error.error)
    }

    @Test
    fun `duplicate policies are deterministic`() = runTest {
        val skills = listOf(skill("same", "zulu"), skill("same", "alpha"))
        val first = InMemorySkillSource(skills, SkillLoadPolicy(duplicateSkill = DuplicateSkillPolicy.KEEP_FIRST)).load()
        val last = InMemorySkillSource(skills, SkillLoadPolicy(duplicateSkill = DuplicateSkillPolicy.KEEP_LAST)).load()

        assertEquals("alpha", first.skills.single().description)
        assertEquals("zulu", last.skills.single().description)
        assertEquals(1, first.diagnostics.size)
        assertEquals(1, last.diagnostics.size)
    }

    @Test
    fun `enforces source limits`() = runTest {
        val limits = SkillLimits(maxSkills = 1, maxInstructionCharacters = 3)
        assertIs<SkillError.LimitExceeded>(
            assertFailsWith<SkillException> {
                InMemorySkillSource(listOf(skill("one"), skill("two")), SkillLoadPolicy(limits = limits)).load()
            }.error
        )
        assertIs<SkillError.LimitExceeded>(
            assertFailsWith<SkillException> {
                InMemorySkillSource(listOf(skill("one", instructions = "four")), SkillLoadPolicy(limits = limits)).load()
            }.error
        )
    }

    @Test
    fun `character limits count Unicode code points`() = runTest {
        val policy = SkillLoadPolicy(
            limits = SkillLimits(maxDescriptionCharacters = 1, maxInstructionCharacters = 1),
        )
        val accepted = InMemorySkillSource(listOf(skill("emoji", "😀", "😀")), policy).load()
        assertEquals("😀", accepted.skills.single().instructions)
        assertIs<SkillError.LimitExceeded>(
            assertFailsWith<SkillException> {
                InMemorySkillSource(listOf(skill("emoji", "😀", "😀😀")), policy).load()
            }.error
        )
    }

    @Test
    fun `common construction validates invariants`() {
        listOf("Upper", "-start", "end-", "two--hyphens", "a_b", "a".repeat(65)).forEach { name ->
            assertFailsWith<SkillException> { Skill(name, "description", "instructions") }
        }
        assertFailsWith<SkillException> { Skill("valid", " ", "instructions") }
        assertFailsWith<SkillException> { Skill("valid", "description", " ") }
        assertFailsWith<SkillException> { Skill("valid", " description ", "instructions") }
        assertFailsWith<SkillException> { Skill("valid", "description", " instructions ") }
        assertEquals(64, Skill("a".repeat(64), "description", "instructions").name.length)
    }

    @Test
    fun `all numeric limits must be positive`() {
        assertFailsWith<IllegalArgumentException> { SkillLimits(maxRoots = 0) }
        assertFailsWith<IllegalArgumentException> { SkillLimits(maxDirectories = 0) }
        assertFailsWith<IllegalArgumentException> { SkillLimits(maxFileBytes = 0) }
        assertFailsWith<IllegalArgumentException> { SkillLimits(maxYamlNestingDepth = 0) }
    }

    private fun skill(name: String, description: String = "description", instructions: String = "instructions"): Skill =
        Skill(name, description, instructions)
}
