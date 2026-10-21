package ai.koog.skills

import ai.koog.rag.base.files.FileSystemProvider
import ai.koog.rag.base.files.JVMFileSystemProvider
import ai.koog.skills.discovery.JvmSecureSkillSession
import ai.koog.skills.discovery.SkillFileSystemSnapshot
import ai.koog.skills.discovery.discoverSkills
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SkillFileSystemTrustTest {
    @TempDir lateinit var temporary: Path

    @Test fun testUnattestedReplacementCannotReachHostIo() = probe("replacement")
    @Test fun testPublicHostEntrypointsFailClosedWithoutAttestation() = probe("entrypoints")
    @Test fun testTrustedStableReferenceFifoFailsWithoutOpening() = probe("reference")
    @Test fun testTrustedStableDocumentFifoIsNotOpened() = probe("document")

    private fun probe(scenario: String) {
        check(System.getProperty("os.name").startsWith("Linux")) { "FIFO trust regression requires Linux" }
        val root = Files.createDirectories(temporary.resolve("skills"))
        val skill = Files.createDirectories(root.resolve("alpha"))
        Files.writeString(skill.resolve("SKILL.md"), "---\nname: alpha\ndescription: Description\n---\nInstructions")
        val fifo = temporary.resolve("replacement-fifo")
        // Setup has its own deadline and is reaped before the disposable JVM starts.
        val setup = ProcessBuilder("mkfifo", fifo.toString()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        try {
            assertTrue(setup.waitFor(5, TimeUnit.SECONDS), "mkfifo setup timed out")
            assertEquals(0, setup.exitValue(), "FIFO fixture could not be created")
        } finally {
            if (setup.isAlive) setup.destroyForcibly()
            check(setup.waitFor(5, TimeUnit.SECONDS)) { "Could not reap mkfifo setup" }
        }
        val output = temporary.resolve("probe-output.txt")
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", checkNotNull(System.getProperty("skills.test.runtimeClasspath")),
            SkillTrustProbe::class.java.name, scenario, root.toString(), fifo.toString(),
        ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Disposable FIFO probe timed out: $scenario")
            val detail = Files.newInputStream(output).use { it.readNBytes(4096).decodeToString() }
            assertEquals(0, process.exitValue(), detail)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "Could not reap disposable FIFO probe" }
        }
    }

    @Test
    fun testImmutableMemoryNeedsNoAttestationAndUnknownProvidersRemainRejected(): Unit = runBlocking {
        val memory = SkillFileSystemSnapshot(mapOf(
            "/skills/alpha/SKILL.md" to "---\nname: alpha\ndescription: Description\n---\nInstructions".encodeToByteArray(),
        ))
        assertEquals("alpha", discoverSkills(memory, listOf("/skills")).single().name)
        val unknown = object : FileSystemProvider.ReadOnly<String> by memory {}
        assertFailsWith<SkillException> { discoverSkills(unknown, listOf("/skills")) }
        assertFailsWith<SkillException> {
            discoverSkills(unknown, listOf("/skills"), SkillFileSystemTrust.TRUSTED_IMMUTABLE)
        }
    }
}

/** Test-only process entrypoint. Never claims protection when the immutability attestation is false. */
internal object SkillTrustProbe {
    @JvmStatic
    fun main(args: Array<String>): Unit = runBlocking {
        val root = Path.of(args[1])
        val fifo = Path.of(args[2])
        val document = root.resolve("alpha/SKILL.md")
        when (args[0]) {
            "replacement" -> {
                var opened = 0
                var hooked = 0
                val source = JvmFileSystemSkillSource(
                    listOf(root), SkillLoadPolicy(),
                    RootDirectoryOpener { opened++; Files.newDirectoryStream(it) },
                    object : SkillFileSystemHooks {
                        override fun afterAttributes(path: Path) {
                            hooked++
                            if (path == document) Files.move(fifo, document, StandardCopyOption.REPLACE_EXISTING)
                        }
                    },
                )
                assertUntrusted { source.load() }
                assertEquals(0, opened)
                assertEquals(0, hooked)
            }
            "entrypoints" -> {
                Files.move(fifo, document, StandardCopyOption.REPLACE_EXISTING)
                val permissive = SkillLoadPolicy(
                    missingRoot = MissingRootPolicy.IGNORE,
                    malformedSkill = MalformedSkillPolicy.SKIP_WITH_DIAGNOSTIC,
                )
                for (roots in listOf(emptyList(), listOf(root))) {
                    assertUntrusted { JvmFileSystemSkillSource(roots).load() }
                    assertUntrusted { JvmFileSystemSkillSource(roots, permissive).load() }
                    assertUntrusted { JvmFileSystemSkillSource(roots, SkillFileSystemTrust.UNATTESTED).load() }
                    assertUntrusted { discoverSkills(JVMFileSystemProvider.ReadOnly, roots.map { it.toString() }) }
                    assertUntrusted { discoverSkills(JVMFileSystemProvider.ReadOnly, roots.map { it.toString() }, SkillFileSystemTrust.UNATTESTED) }
                }
                assertUntrusted { JvmSecureSkillSession(SkillLoadPolicy()) }
            }
            "reference" -> {
                val refs = Files.createDirectory(root.resolve("alpha/references"))
                Files.move(fifo, refs.resolve("guide.md"))
                val error = assertFailsWith<SkillException> {
                    JvmFileSystemSkillSource(listOf(root), SkillFileSystemTrust.TRUSTED_IMMUTABLE).load()
                }
                assertEquals(SkillError.InvalidField("resource", "must be a regular file or directory"), error.error)
            }
            "document" -> {
                Files.move(fifo, document, StandardCopyOption.REPLACE_EXISTING)
                assertTrue(JvmFileSystemSkillSource(listOf(root), SkillFileSystemTrust.TRUSTED_IMMUTABLE).load().skills.isEmpty())
            }
            else -> error("Unknown fixed probe scenario")
        }
    }

    private suspend fun assertUntrusted(block: suspend () -> Unit) {
        assertEquals(SkillError.UntrustedFileSystem, assertFailsWith<SkillException> { block() }.error)
    }
}
