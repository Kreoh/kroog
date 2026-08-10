package ai.koog.skills

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.typeToken
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class LoadSkillToolTest {
    @Test
    fun testToolHasTypedDescriptorAndSerializableArguments() = runTest {
        val tool = LoadSkillTool(registryOf(skill("alpha")))
        val serializer = KotlinxSerializer()

        assertEquals("load_skill", tool.name)
        assertEquals(listOf("name"), tool.descriptor.requiredParameters.map { it.name })
        assertEquals(emptyList(), tool.descriptor.optionalParameters)
        assertEquals("{\"name\":\"alpha\"}", tool.encodeArgsToString(LoadSkillArgs("alpha"), serializer))
        assertEquals(LoadSkillArgs("alpha"), tool.decodeArgs(tool.encodeArgs(LoadSkillArgs("alpha"), serializer), serializer))
    }

    @Test
    fun testKnownSkillReturnsMetadataAndFullInstructionsWithoutOrigin() = runTest {
        val selected = skill("alpha", "Visible description", "Full\nmultiline\ninstructions")
        val tool = LoadSkillTool(registryOf(selected))

        val result = tool.execute(LoadSkillArgs("alpha"))

        assertEquals(
            LoadSkillResult("alpha", "Visible description", "Full\nmultiline\ninstructions"),
            result,
        )
        val encoded = tool.encodeResultToString(result, KotlinxSerializer())
        assertEquals(
            """{"name":"alpha","description":"Visible description","instructions":"Full\nmultiline\ninstructions"}""",
            encoded,
        )
    }

    @Test
    fun testUnknownAndTraversalNamesThrowTypedErrorsWithoutReloadingSource() = runTest {
        var loads = 0
        val source = SkillSource {
            loads++
            SkillSourceResult(listOf(skill("alpha")))
        }
        val registry = SkillRegistry.build(listOf(source))
        val tool = LoadSkillTool(registry)

        assertEquals("alpha", tool.execute(LoadSkillArgs("alpha")).name)
        listOf("Alpha", "unknown", "../alpha", "alpha/../../secret", "..\\alpha").forEach { name ->
            val exception = assertFailsWith<SkillException> { tool.execute(LoadSkillArgs(name)) }
            assertEquals(SkillError.UnknownSkill(name), exception.error)
        }
        assertEquals(1, loads)
    }

    @Test
    fun testEmptyRegistryReturnsExactTargetForEveryCollisionPolicy() = runTest {
        val target = ToolRegistry { tool(NamedTool("existing")) }
        val empty = SkillRegistry.build(emptyList())

        ToolCollisionPolicy.entries.forEach { policy ->
            assertSame(target, empty.mergeToolInto(target, policy))
        }
    }

    @Test
    fun testNoCollisionPreservesOrderAndIdentityThenAppendsSkillTool() = runTest {
        val first = NamedTool("first")
        val second = NamedTool("second")
        val target = ToolRegistry {
            tool(first)
            tool(second)
        }

        val merged = registryOf(skill("alpha")).mergeToolInto(target)

        assertNotSame(target, merged)
        assertEquals(listOf("first", "second", "load_skill"), merged.tools.map { it.name })
        assertSame(first, merged.tools[0])
        assertSame(second, merged.tools[1])
        assertIs<LoadSkillTool>(merged.tools[2])
    }

    @Test
    fun testCollisionFailThrowsTypedError() = runTest {
        val target = ToolRegistry { tool(NamedTool("load_skill")) }

        val exception = assertFailsWith<SkillException> {
            registryOf(skill("alpha")).mergeToolInto(target, ToolCollisionPolicy.FAIL)
        }

        assertEquals(SkillError.ToolCollision, exception.error)
    }

    @Test
    fun testCollisionKeepExistingReturnsExactTarget() = runTest {
        val existing = NamedTool("load_skill")
        val target = ToolRegistry { tool(existing) }

        val merged = registryOf(skill("alpha")).mergeToolInto(target, ToolCollisionPolicy.KEEP_EXISTING)

        assertSame(target, merged)
        assertSame(existing, merged.getTool("load_skill"))
    }

    @Test
    fun testCollisionReplacePreservesPositionSurroundingOrderAndIdentity() = runTest {
        val before = NamedTool("before")
        val existing = NamedTool("load_skill")
        val after = NamedTool("after")
        val target = ToolRegistry {
            tool(before)
            tool(existing)
            tool(after)
        }

        val merged = registryOf(skill("alpha")).mergeToolInto(target, ToolCollisionPolicy.REPLACE)

        assertEquals(listOf("before", "load_skill", "after"), merged.tools.map { it.name })
        assertSame(before, merged.tools[0])
        assertIs<LoadSkillTool>(merged.tools[1])
        assertSame(after, merged.tools[2])
    }

    @Test
    fun testDuplicateCollisionsReplaceFirstAndSuppressLaterToolsWithSameName() = runTest {
        val before = NamedTool("before")
        val firstExisting = NamedTool("load_skill")
        val middle = NamedTool("middle")
        val secondExisting = NamedTool("load_skill")
        val after = NamedTool("after")
        val target = ToolRegistry {
            tool(before)
            tool(firstExisting)
            tool(middle)
        }
        target.add(secondExisting)
        target.add(after)

        val merged = registryOf(skill("alpha")).mergeToolInto(target, ToolCollisionPolicy.REPLACE)

        assertEquals(listOf("before", "load_skill", "middle", "after"), merged.tools.map { it.name })
        assertSame(before, merged.tools[0])
        assertIs<LoadSkillTool>(merged.tools[1])
        assertSame(middle, merged.tools[2])
        assertSame(after, merged.tools[3])
    }

    @Test
    fun testDuplicateCollisionsFailWithTypedError() = runTest {
        val target = ToolRegistry { tool(NamedTool("load_skill")) }
        target.add(NamedTool("load_skill"))

        val exception = assertFailsWith<SkillException> {
            registryOf(skill("alpha")).mergeToolInto(target, ToolCollisionPolicy.FAIL)
        }

        assertEquals(SkillError.ToolCollision, exception.error)
    }

    @Test
    fun testDuplicateCollisionsKeepExistingRegistryIdentity() = runTest {
        val firstExisting = NamedTool("load_skill")
        val secondExisting = NamedTool("load_skill")
        val target = ToolRegistry { tool(firstExisting) }
        target.add(secondExisting)

        val merged = registryOf(skill("alpha")).mergeToolInto(target, ToolCollisionPolicy.KEEP_EXISTING)

        assertSame(target, merged)
        assertEquals(2, merged.tools.count { it.name == "load_skill" })
        assertSame(firstExisting, merged.tools[0])
        assertSame(secondExisting, merged.tools[1])
    }

    @Test
    fun testToolRegistryPlusIsLeftBiasedWhileCheckedReplaceSelectsSkillTool() = runTest {
        val existing = NamedTool("load_skill")
        val target = ToolRegistry { tool(existing) }
        val skills = registryOf(skill("alpha"))
        val skillContribution = skills.mergeToolInto(ToolRegistry.EMPTY)

        val plusMerged = target + skillContribution
        val checkedMerged = skills.mergeToolInto(target, ToolCollisionPolicy.REPLACE)

        assertSame(existing, plusMerged.getTool("load_skill"))
        assertIs<LoadSkillTool>(checkedMerged.getTool("load_skill"))
    }

    private suspend fun registryOf(vararg skills: Skill): SkillRegistry = SkillRegistry.build(
        listOf(SkillSource { SkillSourceResult(skills.toList()) })
    )

    private fun skill(
        name: String,
        description: String = "Description for $name",
        instructions: String = "Instructions for $name",
    ): Skill = Skill(name, description, instructions)

    @Serializable
    private data class NamedArgs(val value: String = "value")

    private class NamedTool(name: String) : SimpleTool<NamedArgs>(
        argsType = typeToken<NamedArgs>(),
        name = name,
        description = "Test tool $name",
    ) {
        override suspend fun execute(args: NamedArgs): String = args.value
    }
}
