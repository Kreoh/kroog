package ai.koog.skills

import ai.koog.agents.core.tools.ToolRegistry
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class SkillResourcesTest {
    @Test
    fun testSnapshotCopiesAndPreservesExactTextAndNames() {
        val files = linkedMapOf("references/z.md" to " final\n", "references/a.md" to "")
        val resources = SkillResources(files)
        files.clear()
        assertEquals(listOf("references/a.md", "references/z.md"), resources.names)
        assertEquals(" final\n", resources.findExact("references/z.md"))
        assertNull(resources.findExact("references/./z.md"))
        assertFailsWith<ClassCastException> { (resources.names as MutableList<String>).clear() }
    }

    @Test
    fun testNamesAndBodiesHaveStrictUtf8AndCanonicalPaths() {
        listOf("/references/a", "references/../a", "references//a", "references/a\\b", "references/http:a", "references/a\u0000", "other/a").forEach {
            assertFailsWith<SkillException> { SkillResources(mapOf(it to "")) }
        }
        assertFailsWith<SkillException> { SkillResources(mapOf("references/a" to "\uD800")) }
        assertFailsWith<SkillException> { SkillResources(mapOf("references/\uDC00" to "")) }
        val name = "references/é"
        SkillResources(mapOf(name to ""), SkillResourceLimits(maxNameBytes = 13))
        assertFailsWith<SkillException> { SkillResources(mapOf(name to ""), SkillResourceLimits(maxNameBytes = 12)) }
        assertFailsWith<SkillException> { SkillResources(mapOf("references/" + "a".repeat(1_024) to "")) }
    }

    @Test
    fun testBudgetsIncludeNamesAndBodiesBeforeSnapshot() {
        val files = mapOf("references/a" to "", "references/b" to "")
        SkillResources(files, SkillResourceLimits(maxSkillBytes = 24))
        assertFailsWith<SkillException> { SkillResources(files, SkillResourceLimits(maxSkillBytes = 23)) }
        assertFailsWith<SkillException> { SkillResources(files, SkillResourceLimits(maxResources = 1)) }
        assertFailsWith<SkillException> { SkillResources(mapOf("references/a" to "é"), SkillResourceLimits(maxBodyBytes = 1)) }
        assertFailsWith<IllegalArgumentException> { SkillResourceLimits(maxTotalBytes = 0) }
    }

    @Test
    fun testDuplicateCandidatesCountNameBytesBeforeSelection() = runTest {
        val names = (0 until 64).associate { index -> ("references/" + index.toString().padStart(4, '0') + "x".repeat(1_009)) to "" }
        val resources = SkillResources(names)
        val skill = Skill("alpha", "Description", "Instructions", resources)
        // 257 candidates exhaust 16 MiB entirely through names, before duplicate selection.
        for (duplicate in listOf(DuplicateSkillPolicy.KEEP_FIRST, DuplicateSkillPolicy.KEEP_LAST)) {
            val policy = SkillLoadPolicy(limits = SkillLimits(maxSkills = 300), duplicateSkill = duplicate)
            assertFailsWith<SkillException> { InMemorySkillSource(List(257) { skill }, policy) }
            val source = SkillSource { SkillSourceResult(List(128) { skill }) }
            assertFailsWith<SkillException> { SkillRegistry.build(listOf(source, source, SkillSource { SkillSourceResult(listOf(skill)) }), policy) }
        }
    }

    @Test
    fun testFilteringCopyAndToolsKeepOnlySelectedResourceSnapshot() = runTest {
        val resources = SkillResources(mapOf("references/a.md" to "exact\n"))
        val alpha = Skill("alpha", "Description", "Instructions", resources)
        assertSame(resources, alpha.copy("alpha", "Updated description", "Updated instructions").resources)
        val names = resources.names
        assertSame(names, LoadSkillResult("alpha", "Description", "Instructions", names).copy("alpha", "Updated", "Updated").resourceNames)
        val beta = alpha.copy(name = "beta")
        val original = SkillRegistry.build(listOf(InMemorySkillSource(listOf(alpha, beta))))
        val filtered = SkillRegistry.build(listOf(InMemorySkillSource(original.filter { it.name == "alpha" })))
        assertSame(resources, filtered.findExact("alpha")!!.resources)
        assertEquals(listOf("references/a.md"), LoadSkillTool(filtered).execute(LoadSkillArgs("alpha")).resourceNames)
        val read = ReadSkillResourceTool(filtered)
        assertEquals("exact\n", read.execute(ReadSkillResourceArgs("alpha", "references/a.md")).text)
        assertFailsWith<SkillException> { read.execute(ReadSkillResourceArgs("beta", "references/a.md")) }
        assertFailsWith<SkillException> { read.execute(ReadSkillResourceArgs("alpha", "../a.md")) }
        val replaced = original.reload(listOf(InMemorySkillSource(listOf(alpha.copy(resources = SkillResources.Empty)))))
        assertEquals("exact\n", read.execute(ReadSkillResourceArgs("alpha", "references/a.md")).text)
        assertEquals(emptyList(), replaced.findExact("alpha")!!.resources.names)
    }

    @Test
    fun testResourceToolCollisionPoliciesAreAtomic() = runTest {
        val registry = SkillRegistry.build(listOf(InMemorySkillSource(listOf(Skill("alpha", "Description", "Instructions")))))
        val old = ReadSkillResourceTool(SkillRegistry.build(emptyList()))
        val target = ToolRegistry { tool(old) }
        assertEquals(SkillError.ResourceToolCollision, assertFailsWith<SkillException> { registry.mergeToolInto(target) }.error)
        assertSame(target, registry.mergeToolInto(target, ToolCollisionPolicy.KEEP_EXISTING))
        val replaced = registry.mergeToolInto(target, ToolCollisionPolicy.REPLACE)
        assertEquals(listOf("read_skill_resource", "load_skill"), replaced.tools.map { it.name })
        assertEquals(emptyList(), (replaced.getTool("load_skill") as LoadSkillTool).execute(LoadSkillArgs("alpha")).resourceNames)
    }
}
