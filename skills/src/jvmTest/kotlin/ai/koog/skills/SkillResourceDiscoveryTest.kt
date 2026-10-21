package ai.koog.skills

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SkillResourceDiscoveryTest {
    @TempDir lateinit var root: Path

    private fun skill(): Path {
        val directory = Files.createDirectories(root.resolve("alpha"))
        Files.writeString(directory.resolve("SKILL.md"), "---\nname: alpha\ndescription: Description\n---\nInstructions")
        return directory
    }

    @Test
    fun testNestedReferencesAreCapturedOnceAndSurviveFilteringAndDeletion() = runTest {
        val directory = skill()
        val nested = Files.createDirectories(directory.resolve("references/nested"))
        val file = Files.writeString(nested.resolve("guide.md"), " exact text\n")
        val registry = SkillRegistry.build(listOf(trustedSkillSource(listOf(root))))
        Files.delete(file)
        val child = SkillRegistry.build(listOf(InMemorySkillSource(registry.filter { it.name == "alpha" })))
        assertEquals(listOf("references/nested/guide.md"), LoadSkillTool(child).execute(LoadSkillArgs("alpha")).resourceNames)
        assertEquals(" exact text\n", ReadSkillResourceTool(child).execute(ReadSkillResourceArgs("alpha", "references/nested/guide.md")).text)
    }

    @Test
    fun testSymlinkedReferenceRootFails() = runTest {
        val directory = skill()
        val other = Files.createDirectories(root.resolve("other"))
        Files.createSymbolicLink(directory.resolve("references"), other)
        assertFailsWith<SkillException> { trustedSkillSource(listOf(root)).load() }
    }

    @Test
    fun testSymlinkedReferenceFileFails() = runTest {
        val directory = skill()
        val refs = Files.createDirectories(directory.resolve("references"))
        Files.createSymbolicLink(refs.resolve("guide.md"), directory.resolve("SKILL.md"))
        assertFailsWith<SkillException> { trustedSkillSource(listOf(root)).load() }
    }

    @Test
    fun testInvalidTextAndOversizedBodiesFail() = runTest {
        val directory = skill()
        val refs = Files.createDirectories(directory.resolve("references"))
        val file = refs.resolve("guide.md")
        Files.write(file, byteArrayOf(0xC3.toByte(), 0x28))
        assertFailsWith<SkillException> { trustedSkillSource(listOf(root)).load() }
        Files.write(file, ByteArray(262_145) { 65 })
        assertFailsWith<SkillException> { trustedSkillSource(listOf(root)).load() }
    }
    @Test
    fun testNestedDirectorySymlinkAndDepthLimitFail() = runTest {
        val directory = skill()
        val refs = Files.createDirectories(directory.resolve("references"))
        val nested = refs.resolve("nested")
        Files.createSymbolicLink(nested, directory)
        assertFailsWith<SkillException> { trustedSkillSource(listOf(root)).load() }
        Files.delete(nested)
        Files.createDirectory(nested)
        Files.writeString(nested.resolve("guide.md"), "text")
        assertFailsWith<SkillException> {
            trustedSkillSource(listOf(root), SkillLoadPolicy(limits = SkillLimits(maxRecursiveDepth = 1))).load()
        }
    }

    @Test
    fun testReferenceReplacementWithSymlinkFails() = runTest {
        val directory = skill()
        val refs = Files.createDirectories(directory.resolve("references"))
        val file = Files.writeString(refs.resolve("guide.md"), "text")
        var replaced = false
        val hooks = object : SkillFileSystemHooks {
            override fun afterAttributes(path: Path) {
                if (path == file && !replaced) {
                    replaced = true
                    Files.delete(file)
                    Files.createSymbolicLink(file, directory.resolve("SKILL.md"))
                }
            }
        }
        val source = trustedSkillSource(listOf(root), SkillLoadPolicy(), RootDirectoryOpener { Files.newDirectoryStream(it) }, hooks)
        assertFailsWith<SkillException> { source.load() }
        kotlin.test.assertTrue(replaced)
    }

    @Test
    fun testReferenceGrowthAfterAttributesStillHonoursReadBound() = runTest {
        val directory = skill()
        val refs = Files.createDirectories(directory.resolve("references"))
        val file = Files.writeString(refs.resolve("guide.md"), "text")
        var grown = false
        val hooks = object : SkillFileSystemHooks {
            override fun afterAttributes(path: Path) {
                if (path == file && !grown) {
                    grown = true
                    Files.write(file, ByteArray(262_145) { 65 })
                }
            }
        }
        val source = trustedSkillSource(listOf(root), SkillLoadPolicy(), RootDirectoryOpener { Files.newDirectoryStream(it) }, hooks)
        assertFailsWith<SkillException> { source.load() }
        kotlin.test.assertTrue(grown)
    }

}
