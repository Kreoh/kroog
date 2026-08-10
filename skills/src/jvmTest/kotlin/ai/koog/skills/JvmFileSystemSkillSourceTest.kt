package ai.koog.skills

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JvmFileSystemSkillSourceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `empty input produces an empty immutable snapshot`() = runTest {
        val result = JvmFileSystemSkillSource(emptyList()).load()
        assertTrue(result.skills.isEmpty())
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun `discovers direct children in stable name order across sorted roots`() = runTest {
        val zRoot = tempDir.resolve("z-root").createDirectories()
        val aRoot = tempDir.resolve("a-root").createDirectories()
        writeSkill(zRoot, "middle")
        writeSkill(aRoot, "zulu")
        writeSkill(aRoot, "alpha")

        val names = JvmFileSystemSkillSource(listOf(zRoot, aRoot)).load().skills.map { it.name }
        assertEquals(listOf("alpha", "middle", "zulu"), names)
    }

    @Test
    fun `ignores unrelated files and directories without a skill document`() = runTest {
        val root = tempDir.resolve("root").createDirectories()
        Files.writeString(root.resolve("README.md"), "unrelated")
        root.resolve("empty").createDirectories()
        writeSkill(root, "valid")

        assertEquals(listOf("valid"), JvmFileSystemSkillSource(listOf(root)).load().skills.map { it.name })
    }

    @Test
    fun `direct mode does not descend and recursive mode does`() = runTest {
        val root = tempDir.resolve("root").createDirectories()
        writeSkill(root.resolve("group").createDirectories(), "nested")

        assertTrue(JvmFileSystemSkillSource(listOf(root)).load().skills.isEmpty())
        val recursive = SkillLoadPolicy(discoveryMode = SkillDiscoveryMode.RECURSIVE)
        assertEquals(listOf("nested"), JvmFileSystemSkillSource(listOf(root), recursive).load().skills.map { it.name })
    }

    @Test
    fun `missing roots follow every policy without disclosing paths by default`() = runTest {
        val missing = tempDir.resolve("secret-missing")
        val failure = assertFailsWith<SkillException> { JvmFileSystemSkillSource(listOf(missing)).load() }
        assertIs<SkillError.MissingRoot>(failure.error)
        assertFalse(failure.message.orEmpty().contains(missing.toString()))

        assertTrue(
            JvmFileSystemSkillSource(
                listOf(missing),
                SkillLoadPolicy(missingRoot = MissingRootPolicy.IGNORE),
            ).load().diagnostics.isEmpty()
        )
        val diagnostic = JvmFileSystemSkillSource(
            listOf(missing),
            SkillLoadPolicy(missingRoot = MissingRootPolicy.DIAGNOSTIC),
        ).load().diagnostics.single()
        assertIs<SkillError.MissingRoot>(diagnostic.error)
        assertEquals(null, diagnostic.error.source)
    }

    @Test
    fun `path disclosure is explicit`() = runTest {
        val missing = tempDir.resolve("visible-missing")
        val policy = SkillLoadPolicy(diagnosticPaths = DiagnosticPathPolicy.DISCLOSE)
        val failure = assertFailsWith<SkillException> { JvmFileSystemSkillSource(listOf(missing), policy).load() }
        assertTrue(failure.message.orEmpty().contains(missing.toString()))
    }

    @Test
    fun `strict UTF-8 rejects malformed input and configured charset is honoured`() = runTest {
        val invalidRoot = tempDir.resolve("invalid").createDirectories()
        val invalidFile = invalidRoot.resolve("broken").createDirectories().resolve("SKILL.md")
        Files.write(invalidFile, byteArrayOf(0xC3.toByte(), 0x28))
        assertIs<SkillError.DecodingFailure>(
            assertFailsWith<SkillException> { JvmFileSystemSkillSource(listOf(invalidRoot)).load() }.error
        )

        val utf16Root = tempDir.resolve("utf16").createDirectories()
        val content = skillDocument("encoded")
        val file = utf16Root.resolve("encoded").createDirectories().resolve("SKILL.md")
        Files.write(file, content.toByteArray(StandardCharsets.UTF_16BE))
        val policy = SkillLoadPolicy(charsetName = "UTF-16BE")
        assertEquals("encoded", JvmFileSystemSkillSource(listOf(utf16Root), policy).load().skills.single().name)
    }

    @Test
    fun `decoding failures can be diagnosed and skipped`() = runTest {
        val root = tempDir.resolve("root").createDirectories()
        val invalidFile = root.resolve("broken").createDirectories().resolve("SKILL.md")
        Files.write(invalidFile, byteArrayOf(0xC3.toByte(), 0x28))
        writeSkill(root, "valid")
        val policy = SkillLoadPolicy(malformedSkill = MalformedSkillPolicy.SKIP_WITH_DIAGNOSTIC)

        val result = JvmFileSystemSkillSource(listOf(root), policy).load()
        assertEquals(listOf("valid"), result.skills.map { it.name })
        assertIs<SkillError.DecodingFailure>(result.diagnostics.single().error)
    }

    @Test
    fun `duplicate names follow deterministic policies`() = runTest {
        val firstRoot = tempDir.resolve("a-root").createDirectories()
        val lastRoot = tempDir.resolve("z-root").createDirectories()
        writeSkill(firstRoot, "same", "alpha")
        writeSkill(lastRoot, "same", "zulu")
        assertIs<SkillError.DuplicateName>(
            assertFailsWith<SkillException> { JvmFileSystemSkillSource(listOf(lastRoot, firstRoot)).load() }.error
        )

        val firstPolicy = SkillLoadPolicy(duplicateSkill = DuplicateSkillPolicy.KEEP_FIRST)
        val lastPolicy = SkillLoadPolicy(duplicateSkill = DuplicateSkillPolicy.KEEP_LAST)
        assertEquals("alpha", JvmFileSystemSkillSource(listOf(lastRoot, firstRoot), firstPolicy).load().skills.single().description)
        assertEquals("zulu", JvmFileSystemSkillSource(listOf(lastRoot, firstRoot), lastPolicy).load().skills.single().description)
    }

    @Test
    fun `malformed entries either fail or become diagnostics`() = runTest {
        val root = tempDir.resolve("root").createDirectories()
        writeSkill(root, "valid")
        val bad = root.resolve("bad").createDirectories().resolve("SKILL.md")
        Files.writeString(bad, "not frontmatter")
        assertIs<SkillError.MalformedFrontmatter>(
            assertFailsWith<SkillException> { JvmFileSystemSkillSource(listOf(root)).load() }.error
        )

        val policy = SkillLoadPolicy(malformedSkill = MalformedSkillPolicy.SKIP_WITH_DIAGNOSTIC)
        val result = JvmFileSystemSkillSource(listOf(root), policy).load()
        assertEquals(listOf("valid"), result.skills.map { it.name })
        assertEquals(1, result.diagnostics.size)
    }

    @Test
    fun `directory name must match validated skill name`() = runTest {
        val root = tempDir.resolve("root").createDirectories()
        val file = root.resolve("directory").createDirectories().resolve("SKILL.md")
        Files.writeString(file, skillDocument("different"))
        assertIs<SkillError.InvalidField>(
            assertFailsWith<SkillException> { JvmFileSystemSkillSource(listOf(root)).load() }.error
        )
    }

    @Test
    fun `root directory skill and empty root are ignored`() = runTest {
        val root = tempDir.resolve("root").createDirectories()
        Files.writeString(root.resolve("SKILL.md"), skillDocument("root"))
        assertTrue(JvmFileSystemSkillSource(listOf(root)).load().skills.isEmpty())
    }

    @Test
    fun `root directory and skill count budgets are enforced`() = runTest {
        val rootA = tempDir.resolve("a").createDirectories()
        val rootB = tempDir.resolve("b").createDirectories()
        assertIs<SkillError.LimitExceeded>(
            assertFailsWith<SkillException> {
                JvmFileSystemSkillSource(listOf(rootA, rootB), SkillLoadPolicy(limits = SkillLimits(maxRoots = 1))).load()
            }.error
        )

        writeSkill(rootA, "one")
        writeSkill(rootA, "two")
        assertIs<SkillError.LimitExceeded>(
            assertFailsWith<SkillException> {
                JvmFileSystemSkillSource(listOf(rootA), SkillLoadPolicy(limits = SkillLimits(maxDirectories = 1))).load()
            }.error
        )
        assertIs<SkillError.LimitExceeded>(
            assertFailsWith<SkillException> {
                JvmFileSystemSkillSource(listOf(rootA), SkillLoadPolicy(limits = SkillLimits(maxSkills = 1))).load()
            }.error
        )
    }

    @Test
    fun `recursive depth exhaustion is explicit`() = runTest {
        val root = tempDir.resolve("root").createDirectories()
        writeSkill(root.resolve("one").createDirectories(), "two")
        val policy = SkillLoadPolicy(
            discoveryMode = SkillDiscoveryMode.RECURSIVE,
            limits = SkillLimits(maxRecursiveDepth = 1),
        )
        assertIs<SkillError.LimitExceeded>(
            assertFailsWith<SkillException> { JvmFileSystemSkillSource(listOf(root), policy).load() }.error
        )
    }

    @Test
    fun `file and body sizes are bounded`() = runTest {
        val fileRoot = tempDir.resolve("file-root").createDirectories()
        writeSkill(fileRoot, "large", body = "x".repeat(200))
        assertIs<SkillError.LimitExceeded>(
            assertFailsWith<SkillException> {
                JvmFileSystemSkillSource(
                    listOf(fileRoot),
                    SkillLoadPolicy(limits = SkillLimits(maxFileBytes = 32)),
                ).load()
            }.error
        )
        assertIs<SkillError.LimitExceeded>(
            assertFailsWith<SkillException> {
                JvmFileSystemSkillSource(
                    listOf(fileRoot),
                    SkillLoadPolicy(limits = SkillLimits(maxInstructionCharacters = 10)),
                ).load()
            }.error
        )
    }

    @Test
    fun `internal file symlinks are rejected`() = runTest {
        val root = tempDir.resolve("root").createDirectories()
        val backing = root.resolve("backing.md")
        Files.writeString(backing, skillDocument("linked"))
        val link = root.resolve("linked").createDirectories().resolve("SKILL.md")
        Files.createSymbolicLink(link, backing)

        assertIs<SkillError.SymlinkRejected>(
            assertFailsWith<SkillException> { JvmFileSystemSkillSource(listOf(root)).load() }.error
        )
    }

    @Test
    fun `escaping file and directory symlinks are rejected`() = runTest {
        val root = tempDir.resolve("root").createDirectories()
        val outsideFile = tempDir.resolve("outside.md")
        Files.writeString(outsideFile, skillDocument("escape"))
        Files.createSymbolicLink(root.resolve("escape").createDirectories().resolve("SKILL.md"), outsideFile)
        assertIs<SkillError.SymlinkRejected>(
            assertFailsWith<SkillException> { JvmFileSystemSkillSource(listOf(root)).load() }.error
        )

        Files.delete(root.resolve("escape").resolve("SKILL.md"))
        val outsideDirectory = tempDir.resolve("outside-dir").createDirectories()
        writeSkill(outsideDirectory, "linked-dir")
        Files.createSymbolicLink(root.resolve("linked-dir"), outsideDirectory.resolve("linked-dir"))
        assertIs<SkillError.SymlinkRejected>(
            assertFailsWith<SkillException> { JvmFileSystemSkillSource(listOf(root)).load() }.error
        )
    }

    @Test
    fun `I O failures are surfaced as typed errors`() = runTest {
        val rootFile = tempDir.resolve("not-a-directory")
        Files.writeString(rootFile, "content")
        assertIs<SkillError.IoFailure>(
            assertFailsWith<SkillException> { JvmFileSystemSkillSource(listOf(rootFile)).load() }.error
        )
    }

    @Test
    fun `high fan-out including unrelated entries is bounded before attributes`() = runTest {
        val root = tempDir.resolve("root").createDirectories()
        repeat(20) { Files.writeString(root.resolve("unrelated-$it.txt"), "content") }
        var attributeReads = 0
        val hooks = object : SkillFileSystemHooks {
            override fun beforeAttributes(path: Path) {
                attributeReads++
            }
        }
        val policy = SkillLoadPolicy(
            limits = SkillLimits(maxDirectories = 5),
            malformedSkill = MalformedSkillPolicy.SKIP_WITH_DIAGNOSTIC,
        )
        val source = source(listOf(root), policy, hooks = hooks)

        val result = source.load()
        assertTrue(result.skills.isEmpty())
        assertIs<SkillError.LimitExceeded>(result.diagnostics.single().error)
        assertEquals(0, attributeReads)
    }

    @Test
    fun `filesystems without secure directory streams are refused`() = runTest {
        val root = tempDir.resolve("root").createDirectories()
        writeSkill(root, "valid")
        val opener = RootDirectoryOpener { path ->
            val delegate = Files.newDirectoryStream(path)
            object : DirectoryStream<Path> {
                override fun iterator(): MutableIterator<Path> = delegate.iterator()
                override fun close() = delegate.close()
            }
        }

        assertIs<SkillError.IoFailure>(
            assertFailsWith<SkillException> {
                source(listOf(root), rootDirectoryOpener = opener).load()
            }.error
        )
    }

    @Test
    fun `attribute access failures are surfaced through a deterministic seam`() = runTest {
        val root = tempDir.resolve("root").createDirectories()
        writeSkill(root, "alpha")
        val hooks = object : SkillFileSystemHooks {
            override fun beforeAttributes(path: Path) {
                if (path.fileName.toString() == "alpha") throw AccessDeniedException(path.toString())
            }
        }

        assertIs<SkillError.IoFailure>(
            assertFailsWith<SkillException> { source(listOf(root), hooks = hooks).load() }.error
        )
    }

    @Test
    fun `concurrent replacement with a symlink is refused by relative no-follow open`() = runTest {
        val root = tempDir.resolve("root").createDirectories()
        val skillFile = writeSkill(root, "replace")
        val outside = tempDir.resolve("outside.md")
        Files.writeString(outside, skillDocument("replace", body = "outside instructions"))
        var replaced = false
        val hooks = object : SkillFileSystemHooks {
            override fun afterAttributes(path: Path) {
                if (!replaced && path.fileName.toString() == "SKILL.md") {
                    replaced = true
                    Files.delete(skillFile)
                    Files.createSymbolicLink(skillFile, outside)
                }
            }
        }

        assertIs<SkillError.IoFailure>(
            assertFailsWith<SkillException> { source(listOf(root), hooks = hooks).load() }.error
        )
        assertTrue(replaced)
    }

    @Test
    fun `returned snapshots do not change after files change`() = runTest {
        val root = tempDir.resolve("root").createDirectories()
        val file = writeSkill(root, "stable", "before")
        val snapshot = JvmFileSystemSkillSource(listOf(root)).load()
        Files.writeString(file, skillDocument("stable", "after"))
        assertEquals("before", snapshot.skills.single().description)
    }

    private fun writeSkill(
        root: Path,
        name: String,
        description: String = "description",
        body: String = "instructions",
    ): Path {
        val file = root.resolve(name).createDirectories().resolve("SKILL.md")
        Files.writeString(file, skillDocument(name, description, body))
        return file
    }

    private fun skillDocument(
        name: String,
        description: String = "description",
        body: String = "instructions",
    ): String = "---\nname: $name\ndescription: $description\n---\n$body"

    private fun source(
        roots: Collection<Path>,
        policy: SkillLoadPolicy = SkillLoadPolicy(),
        rootDirectoryOpener: RootDirectoryOpener = RootDirectoryOpener(Files::newDirectoryStream),
        hooks: SkillFileSystemHooks = object : SkillFileSystemHooks {},
    ): JvmFileSystemSkillSource = JvmFileSystemSkillSource(roots, policy, rootDirectoryOpener, hooks)
}
