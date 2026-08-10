package ai.koog.skills

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SkillRegistryTest {
    @Test
    fun testBuildLoadsSourcesSequentiallyAndOrdersSkillsLexically() = runTest {
        val events = mutableListOf<String>()
        val first = recordingSource("first", events, listOf(skill("zeta"), skill("beta")))
        val second = recordingSource("second", events, listOf(skill("alpha")))

        val registry = SkillRegistry.build(listOf(first, second))

        assertEquals(listOf("first:start", "first:end", "second:start", "second:end"), events)
        assertEquals(listOf("alpha", "beta", "zeta"), registry.map(Skill::name))
        assertEquals(listOf("alpha", "beta", "zeta"), registry.metadata.map(SkillMetadata::name))
    }

    @Test
    fun testBuildRetainsStableSourceAndDuplicateDiagnostics() = runTest {
        val firstDiagnostic = SkillDiagnostic(SkillError.InvalidField("first", "diagnostic"))
        val secondDiagnostic = SkillDiagnostic(SkillError.InvalidField("second", "diagnostic"))
        val registry = SkillRegistry.build(
            sources = listOf(
                fixedSource(listOf(skill("same", "First")), listOf(firstDiagnostic)),
                fixedSource(listOf(skill("same", "Second")), listOf(secondDiagnostic)),
            ),
            policy = SkillLoadPolicy(duplicateSkill = DuplicateSkillPolicy.KEEP_FIRST),
        )

        assertEquals(firstDiagnostic, registry.diagnostics[0])
        assertEquals(secondDiagnostic, registry.diagnostics[1])
        assertEquals(SkillError.DuplicateName("same"), registry.diagnostics[2].error)
    }

    @Test
    fun testDuplicateFailIsTyped() = runTest {
        val exception = assertFailsWith<SkillException> {
            SkillRegistry.build(
                listOf(fixedSource(listOf(skill("same"))), fixedSource(listOf(skill("same"))))
            )
        }

        assertEquals(SkillError.DuplicateName("same"), exception.error)
    }

    @Test
    fun testDuplicateKeepFirstUsesCallerSourceOrder() = runTest {
        val first = skill("same", "First")
        val second = skill("same", "Second")

        val registry = SkillRegistry.build(
            listOf(fixedSource(listOf(first)), fixedSource(listOf(second))),
            SkillLoadPolicy(duplicateSkill = DuplicateSkillPolicy.KEEP_FIRST),
        )

        assertSame(first, registry.findExact("same"))
    }

    @Test
    fun testDuplicateKeepLastUsesCallerSourceOrder() = runTest {
        val first = skill("same", "First")
        val second = skill("same", "Second")

        val registry = SkillRegistry.build(
            listOf(fixedSource(listOf(first)), fixedSource(listOf(second))),
            SkillLoadPolicy(duplicateSkill = DuplicateSkillPolicy.KEEP_LAST),
        )

        assertSame(second, registry.findExact("same"))
    }

    @Test
    fun testAggregateSkillLimitRunsBeforeDeduplication() = runTest {
        val exception = assertFailsWith<SkillException> {
            SkillRegistry.build(
                sources = listOf(
                    fixedSource(listOf(skill("same", "First"))),
                    fixedSource(listOf(skill("same", "Second"))),
                ),
                policy = SkillLoadPolicy(
                    limits = SkillLimits(maxSkills = 1),
                    duplicateSkill = DuplicateSkillPolicy.KEEP_FIRST,
                ),
            )
        }

        assertEquals(SkillError.LimitExceeded("skills", 1), exception.error)
    }

    @Test
    fun testBuildRevalidatesSkillsAgainstRegistryLimits() = runTest {
        val exception = assertFailsWith<SkillException> {
            SkillRegistry.build(
                listOf(fixedSource(listOf(skill("bounded", description = "long")))),
                SkillLoadPolicy(limits = SkillLimits(maxDescriptionCharacters = 3)),
            )
        }

        assertEquals(SkillError.LimitExceeded("description characters", 3), exception.error)
    }

    @Test
    fun testBuildPropagatesSourceFailureUnchanged() = runTest {
        val failure = IllegalStateException("source failed")
        val source = SkillSource { throw failure }

        val thrown = assertFailsWith<IllegalStateException> {
            SkillRegistry.build(listOf(source))
        }

        assertSame(failure, thrown)
    }

    @Test
    fun testExactLookupIsCaseSensitiveAndDoesNotInterpretTraversal() = runTest {
        val selected = skill("alpha")
        val registry = SkillRegistry.build(listOf(fixedSource(listOf(selected))))

        assertSame(selected, registry.findExact("alpha"))
        assertNull(registry.findExact("Alpha"))
        assertNull(registry.findExact("../alpha"))
        assertNull(registry.findExact("alpha/../alpha"))
    }

    @Test
    fun testRegistrySnapshotDoesNotExposeSourceOrMetadataMutation() = runTest {
        val backing = mutableListOf(skill("alpha"))
        val source = SkillSource { SkillSourceResult(backing) }
        val registry = SkillRegistry.build(listOf(source))

        backing.clear()
        assertEquals(listOf("alpha"), registry.map(Skill::name))
        assertFailsWith<Throwable> {
            @Suppress("UNCHECKED_CAST")
            (registry.metadata as MutableList<SkillMetadata>).clear()
        }
        assertEquals(listOf("alpha"), registry.metadata.map(SkillMetadata::name))
    }

    @Test
    fun testReloadReturnsDistinctSnapshotAndPreservesOldRegistry() = runTest {
        var current = listOf(skill("first"))
        var loads = 0
        val source = SkillSource {
            loads++
            SkillSourceResult(current)
        }
        val oldRegistry = SkillRegistry.build(
            listOf(source),
            SkillLoadPolicy(lifecycle = SkillLifecyclePolicy.EXPLICIT_RELOAD),
        )
        current = listOf(skill("second"))

        val newRegistry = oldRegistry.reload(listOf(source))

        assertNotSame(oldRegistry, newRegistry)
        assertEquals(listOf("first"), oldRegistry.map(Skill::name))
        assertEquals(listOf("second"), newRegistry.map(Skill::name))
        assertEquals(2, loads)
        assertEquals(SkillLifecyclePolicy.EXPLICIT_RELOAD, newRegistry.lifecycle)
    }

    @Test
    fun testFailedReloadPropagatesFailureAndLeavesOriginalSnapshotUnchanged() = runTest {
        val originalSkill = skill("original")
        val registry = SkillRegistry.build(listOf(fixedSource(listOf(originalSkill))))
        val failure = SkillException(SkillError.IoFailure("reload"))
        val failingSource = SkillSource { throw failure }

        val thrown = assertFailsWith<SkillException> {
            registry.reload(listOf(failingSource))
        }

        assertSame(failure, thrown)
        assertEquals(listOf(originalSkill), registry.toList())
        assertSame(originalSkill, registry.findExact("original"))
    }

    @Test
    fun testEmptyRegistryMetadataIterationAndStateAreEmpty() = runTest {
        val registry = SkillRegistry.build(emptyList())

        assertTrue(registry.isEmpty())
        assertFalse(registry.iterator().hasNext())
        assertTrue(registry.metadata.isEmpty())
        assertTrue(registry.diagnostics.isEmpty())
    }

    private fun skill(
        name: String,
        description: String = "Description for $name",
        instructions: String = "Instructions for $name",
    ): Skill = Skill(name, description, instructions)

    private fun fixedSource(
        skills: List<Skill>,
        diagnostics: List<SkillDiagnostic> = emptyList(),
    ): SkillSource = SkillSource { SkillSourceResult(skills, diagnostics) }

    private fun recordingSource(name: String, events: MutableList<String>, skills: List<Skill>): SkillSource =
        SkillSource {
            events += "$name:start"
            val result = SkillSourceResult(skills)
            events += "$name:end"
            result
        }
}
