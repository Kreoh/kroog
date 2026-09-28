package ai.koog.skills.discovery

import ai.koog.rag.base.files.FileSystemProvider
import ai.koog.rag.base.files.JVMFileSystemProvider
import ai.koog.skills.*
import ai.koog.skills.prompt.SkillsPromptFormat
import ai.koog.skills.prompt.generateSkillsPrompt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import kotlin.test.*

class SkillsCompatibilityTest {
    @TempDir
    lateinit var tempDir: Path

    private fun document(name: String = "alpha", description: String = "Description", body: String = "Instructions", fields: String = ""): String =
        "---\nname: $name\ndescription: $description\n$fields---\n$body"

    @Test
    fun testCanonicalParserAndLegacyShimAgreeAndRejectTheSameMalformedYaml() {
        val policy = SkillLoadPolicy()
        val text = document()
        assertEquals(SkillParser.parse(text), SkillDocumentParser.parse(text, null, policy, "", false).toLegacy())
        listOf(
            document(fields = "name: replacement\n"),
            "---\nname: &name alpha\ndescription: *name\n---\nInstructions",
            document().replace("---\n", " ---\n"),
        ).forEach { malformed ->
            val legacy = assertFailsWith<SkillException> { SkillParser.parse(malformed) }
            val canonical = assertFailsWith<SkillException> { SkillDocumentParser.parse(malformed, null, policy, "", true) }
            assertEquals(legacy.error::class, canonical.error::class)
        }
    }

    @Test
    fun testCanonicalParserRejectsHostileNestedAndIgnoredFields() {
        val policy = SkillLoadPolicy(unknownField = UnknownFieldPolicy.IGNORE)
        for (fields in listOf(
            "metadata: {author: first, author: second}\n",
            "extra: &recursive [*recursive]\n",
            "? &recursive [*recursive]\n: value\n",
            "extra: &scalar value\nother: *scalar\n",
        )) {
            assertIs<SkillError.MalformedFrontmatter>(assertFailsWith<SkillException> {
                SkillDocumentParser.parse(document(fields = fields), "alpha", policy, "", true)
            }.error)
        }
        val unknown = document(fields = "extension: safe\n")
        assertEquals("alpha", SkillDocumentParser.parse(unknown, "alpha", policy, "", true).skill.name)
        assertIs<SkillError.InvalidField>(assertFailsWith<SkillException> {
            SkillDocumentParser.parse(unknown, "alpha", SkillLoadPolicy(), "", true)
        }.error)
        val depthPolicy = policy.copy(limits = SkillLimits(maxYamlNestingDepth = 2))
        assertEquals("alpha", SkillDocumentParser.parse(
            document(fields = "extra: [safe]\n"), "alpha", depthPolicy, "", true,
        ).skill.name)
        assertEquals(SkillError.LimitExceeded("YAML nesting depth", 2), assertFailsWith<SkillException> {
            SkillDocumentParser.parse(document(fields = "extra: [[deep]]\n"), "alpha", depthPolicy, "", true)
        }.error)
    }

    @Test
    fun testYamlBudgetCountsSupplementaryCodePointsAtExactBoundary() {
        val yaml = "name: alpha\ndescription: '😀'"
        val count = yaml.codePointCount(0, yaml.length)
        val text = "---\n$yaml\n---\nInstructions"
        val policy = SkillLoadPolicy(limits = SkillLimits(maxYamlCodePoints = count))
        assertEquals("😀", SkillDocumentParser.parse(text, "alpha", policy, "", true).skill.description)
        assertEquals(SkillError.LimitExceeded("YAML code points", (count - 1).toLong()),
            assertFailsWith<SkillException> {
                SkillDocumentParser.parse(text, "alpha",
                    policy.copy(limits = SkillLimits(maxYamlCodePoints = count - 1)), "", true)
            }.error)
    }

    @Test
    fun testDiscoveryCountsDuplicateCandidatesBeforeSelectingWinner() = runTest {
        val fs = SkillFileSystemSnapshot(mapOf(
            "/first/alpha/SKILL.md" to document(description = "First").encodeToByteArray(),
            "/last/alpha/SKILL.md" to document(description = "Last").encodeToByteArray(),
        ))
        for (precedence in listOf(DuplicateSkillPolicy.KEEP_FIRST, DuplicateSkillPolicy.KEEP_LAST)) {
            val policy = SkillLoadPolicy(limits = SkillLimits(maxSkills = 2), duplicateSkill = precedence)
            val roots = listOf("/first/alpha", "/last/alpha")
            val accepted = discoverRecords(fs.openSession(policy), roots, policy, true)
            assertEquals(if (precedence == DuplicateSkillPolicy.KEEP_FIRST) "First" else "Last",
                accepted.records.single().skill.description)
            assertIs<SkillError.DuplicateName>(accepted.diagnostics.single().error)
            val bounded = policy.copy(limits = SkillLimits(maxSkills = 1))
            assertEquals(SkillError.LimitExceeded("skills", 1), assertFailsWith<SkillException> {
                discoverRecords(fs.openSession(bounded), roots, bounded, true)
            }.error)
        }
    }

    @Test
    fun testOptionalFieldsUseStrictTypesAndMetadataOnlyDocumentsCannotBecomeLoadable() {
        val text = document(body = "", fields = "license: MIT\nmetadata:\n  version: '1'\n")
        val parsed = SkillDocumentParser.parse(text, "alpha", SkillLoadPolicy(), "/alpha/SKILL.md", true)
        assertEquals("MIT", parsed.skill.license)
        assertEquals(mapOf("version" to "1"), parsed.skill.metadata)
        assertEquals("", parsed.instructions)
        assertFailsWith<SkillException> { parsed.toLegacy() }
        listOf("license: 12\n", "metadata: text\n", "metadata:\n  version: 1\n").forEach { fields ->
            assertIs<SkillError.InvalidField>(assertFailsWith<SkillException> {
                SkillDocumentParser.parse(document(fields = fields), "alpha", SkillLoadPolicy(), "", true)
            }.error)
        }
        assertFailsWith<SkillException> { SkillParser.parse(text) }
    }

    @Test
    fun testGenericSnapshotIsImmutableAndTraversesBreadthFirst() = runTest {
        val bytes = document("alpha").encodeToByteArray()
        val files = linkedMapOf(
            "/root/deep/alpha/SKILL.md" to document("alpha", "Deep", "Deep body").encodeToByteArray(),
            "/root/alpha/SKILL.md" to bytes,
        )
        val snapshot: FileSystemProvider.ReadOnly<String> = SkillFileSystemSnapshot(files)
        bytes.fill(0)
        files.clear()
        val first = discoverSkills(snapshot, listOf("/root"), precedenceRule = SkillCollisionPrecedence.FIRST_FOUND)
        val last = discoverSkills(snapshot, listOf("/root"))
        assertEquals("Description", first.single().description)
        assertEquals("Deep", last.single().description)
        val exposed = snapshot.readBytes("/root/alpha/SKILL.md")
        exposed.fill(0)
        assertEquals("Description", discoverSkills(snapshot, listOf("/root"), maxDepth = 1).single().description)
    }

    @Test
    fun testCollisionSelectsMetadataAndBodyFromOneRead() = runTest {
        val fs = SkillFileSystemSnapshot(mapOf(
            "/first/alpha/SKILL.md" to document(description = "First", body = "First body").encodeToByteArray(),
            "/last/alpha/SKILL.md" to document(description = "Last", body = "Last body").encodeToByteArray(),
        ))
        for (precedence in listOf(DuplicateSkillPolicy.KEEP_FIRST, DuplicateSkillPolicy.KEEP_LAST)) {
            val policy = SkillLoadPolicy(discoveryMode = SkillDiscoveryMode.RECURSIVE, duplicateSkill = precedence)
            val session = fs.openSession(policy)
            val reads = mutableMapOf<String, Int>()
            var closed = false
            val counted = object : SecureSkillSession by session {
                override suspend fun read(entry: SkillEntry): String {
                    reads[entry.location] = reads.getOrDefault(entry.location, 0) + 1
                    return session.read(entry)
                }
                override fun close() { closed = true; session.close() }
            }
            val result = discoverRecords(counted, listOf("/first", "/last"), policy, true)
            val selected = result.records.single()
            val expected = if (precedence == DuplicateSkillPolicy.KEEP_FIRST) "First" else "Last"
            assertEquals(expected, selected.skill.description)
            assertEquals("$expected body", selected.toLegacy().instructions)
            assertEquals(listOf(1, 1), reads.values.toList())
            assertTrue(closed)
        }
    }

    @Test
    fun testUnknownProviderIsRejectedBeforeAnyRead() = runTest {
        var accessed = false
        val unknown = object : FileSystemProvider.ReadOnly<Path> by JVMFileSystemProvider.ReadOnly {
            override suspend fun readBytes(path: Path): ByteArray { accessed = true; error("Must not read") }
            override fun fromAbsolutePathString(path: String): Path { accessed = true; error("Must not inspect") }
        }
        val failure = assertFailsWith<SkillException> { discoverSkills(unknown, listOf(tempDir.toString())) }
        assertIs<SkillError.IoFailure>(failure.error)
        assertTrue(failure.message.orEmpty().contains("verified secure skills session"))
        assertFalse(accessed)
    }

    @Test
    fun testStockProviderAndLegacySourceUseTheSameStrictDocument() = runTest {
        val directory = Files.createDirectory(tempDir.resolve("alpha"))
        val path = directory.resolve("SKILL.md")
        Files.writeString(path, document())
        val upstream = discoverSkills(JVMFileSystemProvider.ReadOnly, listOf(tempDir.toString())).single()
        val legacy = JvmFileSystemSkillSource(listOf(tempDir)).load().skills.single()
        assertEquals(legacy.name, upstream.name)
        assertEquals(legacy.description, upstream.description)
        assertEquals(path.toString(), upstream.location)
        Files.writeString(path, document(fields = "name: beta\n"))
        val warnings = mutableListOf<String>()
        assertTrue(discoverSkills(JVMFileSystemProvider.ReadOnly, listOf(tempDir.toString()), warningLogger = warnings::add).isEmpty())
        assertFailsWith<SkillException> { JvmFileSystemSkillSource(listOf(tempDir)).load() }
        assertTrue(warnings.isNotEmpty())
        assertTrue(warnings.none { tempDir.toString() in it })
    }

    @Test
    fun testSessionClosesAllDescriptorsOnSuccessFailureAndCancellation() = runTest {
        Files.createDirectory(tempDir.resolve("alpha"))
        Files.writeString(tempDir.resolve("alpha/SKILL.md"), document())
        for (failure in listOf(null, SkillException(SkillError.InvalidField("test", "failure")), CancellationException("cancelled"))) {
            var opened = 0
            var closed = 0
            fun tracked(stream: SecureDirectoryStream<Path>): SecureDirectoryStream<Path> {
                opened++
                return object : SecureDirectoryStream<Path> by stream {
                    override fun newDirectoryStream(path: Path, vararg options: java.nio.file.LinkOption): SecureDirectoryStream<Path> =
                        tracked(stream.newDirectoryStream(path, *options))
                    override fun close() { closed++; stream.close() }
                }
            }
            val source = JvmFileSystemSkillSource(listOf(tempDir), SkillLoadPolicy(),
                RootDirectoryOpener { tracked(Files.newDirectoryStream(it) as SecureDirectoryStream<Path>) },
                object : SkillFileSystemHooks {
                    override fun beforeAttributes(path: Path) {
                        if (path.fileName.toString() == "SKILL.md" && failure != null) throw failure
                    }
                },
            )
            if (failure == null) assertEquals("alpha", source.load().skills.single().name)
            else assertSame(failure, assertFailsWith<Throwable> { source.load() })
            assertEquals(2, opened)
            assertEquals(opened, closed)
        }
    }

    @Test
    fun testSessionRejectsForeignTokensAndUseAfterClose() {
        val fs = SkillFileSystemSnapshot(mapOf("/alpha/SKILL.md" to document().encodeToByteArray()))
        val first = fs.openSession(SkillLoadPolicy())
        val second = fs.openSession(SkillLoadPolicy())
        val root = first.root("/alpha")!!
        assertFailsWith<IllegalArgumentException> { second.list(root, 1) }
        first.close()
        assertFailsWith<IllegalStateException> { first.child(root, "SKILL.md") }
        second.close()
    }

    @Test
    fun testJsonEscapesEveryControlCharacterAndLegacySharesProjection() = runTest {
        val description = "Start" + (0..31).map(Int::toChar).joinToString("") + "End"
        val skill = ai.koog.skills.model.Skill("alpha", description, "/secret", metadata = mapOf("key\u0000" to "value\u000b"))
        val json = Json.parseToJsonElement(generateSkillsPrompt(listOf(skill), SkillsPromptFormat.JSON, includeMetadata = true))
            .jsonObject.getValue("available_skills").jsonArray.single().jsonObject
        assertEquals(description, json.getValue("description").jsonPrimitive.content)
        assertEquals("value\u000b", json.getValue("metadata").jsonObject.getValue("key\u0000").jsonPrimitive.content)
        val registry = SkillRegistry.build(listOf(InMemorySkillSource(listOf(Skill("alpha", description, "Hidden body")))))
        val legacy = Json.parseToJsonElement(SkillCatalogueRenderer.render(registry)!!).jsonArray
        val upstream = Json.parseToJsonElement(generateSkillsPrompt(listOf(skill), SkillsPromptFormat.JSON, includeLocation = false))
            .jsonObject.getValue("available_skills").jsonArray
        assertEquals(upstream, legacy)
        assertEquals(setOf("name", "description"), legacy.single().jsonObject.keys)
    }

    @Test
    fun testSnapshotBoundsAndInvalidUtf8AreExplicit() = runTest {
        assertFailsWith<IllegalArgumentException> { SkillFileSystemSnapshot(mapOf("/a/../b" to byteArrayOf())) }
        assertFailsWith<IllegalArgumentException> {
            SkillFileSystemSnapshot(mapOf("/a" to ByteArray(3)), SkillLimits(maxFileBytes = 2))
        }
        val fs = SkillFileSystemSnapshot(mapOf("/alpha/SKILL.md" to byteArrayOf(0xc3.toByte(), 0x28)))
        val warnings = mutableListOf<String>()
        assertTrue(discoverSkills(fs, listOf("/alpha"), warningLogger = warnings::add).isEmpty())
        assertTrue(warnings.single().contains("not valid UTF-8"))
    }

    @Test
    fun testRootInclusionDepthZeroAndCustomPatternAreEnforced() = runTest {
        val fs = SkillFileSystemSnapshot(mapOf("/alpha/SKILL.md" to document().encodeToByteArray()))
        assertEquals("alpha", discoverSkills(fs, listOf("/alpha"), maxDepth = 0).single().name)
        assertTrue(discoverSkills(fs, listOf("/alpha"), skillNamePattern = Regex("beta")).isEmpty())
        assertFailsWith<IllegalArgumentException> { discoverSkills(fs, listOf("/alpha"), skillFileName = "../SKILL.md") }
    }
    @Test
    fun testActualCoroutineCancellationAfterReadClosesSession() = runTest {
        val policy = SkillLoadPolicy()
        val fs = SkillFileSystemSnapshot(mapOf("/alpha/SKILL.md" to document().encodeToByteArray()))
        val delegate = fs.openSession(policy)
        var closed = false
        var returned = false
        val session = object : SecureSkillSession by delegate {
            override suspend fun read(entry: SkillEntry): String {
                val result = delegate.read(entry)
                currentCoroutineContext().cancel()
                return result
            }
            override fun close() { closed = true; delegate.close() }
        }
        val job = launch {
            discoverRecords(session, listOf("/alpha"), policy, true)
            returned = true
        }
        job.join()
        assertTrue(job.isCancelled)
        assertTrue(closed)
        assertFalse(returned)
    }

    @Test
    fun testUpstreamIgnoresNonDirectoryRootWhileLegacyReportsTypedFailure() = runTest {
        val file = Files.writeString(tempDir.resolve("file"), "text")
        val warnings = mutableListOf<String>()
        assertTrue(discoverSkills(JVMFileSystemProvider.ReadOnly, listOf(file.toString()), warningLogger = warnings::add).isEmpty())
        assertEquals(1, warnings.size)
        assertFalse(warnings.single().contains(file.toString()))
        assertFailsWith<IllegalArgumentException> { discoverSkills(JVMFileSystemProvider.ReadOnly, listOf("relative-root")) }
        assertIs<SkillError.IoFailure>(assertFailsWith<SkillException> { JvmFileSystemSkillSource(listOf(file)).load() }.error)
    }

    @Test
    fun testSevenFieldModelSerialisationAndEscapedXmlAndYaml() {
        val value = "before\n<>&\"'\u0001after"
        val skill = ai.koog.skills.model.Skill("alpha", value, "/location", "MIT", "JVM", mapOf("a:\nb" to value), "Read")
        val encoded = Json.parseToJsonElement(Json.encodeToString(ai.koog.skills.model.Skill.serializer(), skill)).jsonObject
        assertEquals(setOf("name", "description", "location", "license", "compatibility", "metadata", "allowed-tools"), encoded.keys)
        assertEquals(skill, Json.decodeFromJsonElement(ai.koog.skills.model.Skill.serializer(), encoded))
        assertFailsWith<IllegalArgumentException> { generateSkillsPrompt(listOf(skill), SkillsPromptFormat.XML) }
        val yaml = generateSkillsPrompt(listOf(skill), SkillsPromptFormat.YML, includeMetadata = true)
        val parsed = org.snakeyaml.engine.v2.api.Load(org.snakeyaml.engine.v2.api.LoadSettings.builder().build()).loadFromString(yaml) as Map<*, *>
        val row = (parsed["available_skills"] as List<*>).single() as Map<*, *>
        assertEquals(value, row["description"])
        assertEquals(mapOf("a:\nb" to value), row["metadata"])
    }

    @Test
    fun testSnapshotProviderOperationsAndConstructionBudgets() = runTest {
        val fs = SkillFileSystemSnapshot(mapOf("/alpha/SKILL.md" to document().encodeToByteArray()))
        assertEquals("/alpha/SKILL.md", fs.joinPath("/alpha", "SKILL.md"))
        assertEquals("SKILL.md", fs.relativize("/alpha", "/alpha/SKILL.md"))
        assertNull(fs.relativize("/other", "/alpha/SKILL.md"))
        assertEquals("md", fs.extension("/alpha/SKILL.md"))
        assertTrue(fs.exists("/alpha"))
        assertFalse(fs.exists("/missing"))
        assertNull(fs.metadata("/missing"))
        assertEquals(ai.koog.rag.base.files.FileMetadata.FileType.Directory, fs.metadata("/alpha")!!.type)
        assertEquals(ai.koog.rag.base.files.FileMetadata.FileType.File, fs.metadata("/alpha/SKILL.md")!!.type)
        assertEquals(ai.koog.rag.base.files.FileMetadata.FileContentType.Text, fs.getFileContentType("/alpha/SKILL.md"))
        assertEquals(listOf("/alpha/SKILL.md"), fs.list("/alpha"))
        assertEquals(document().encodeToByteArray().size.toLong(), fs.size("/alpha/SKILL.md"))
        fs.inputStream("/alpha/SKILL.md").use { assertEquals('-'.code.toByte(), it.readByte()) }
        assertFailsWith<IllegalArgumentException> { SkillFileSystemSnapshot(mapOf("/a/b" to byteArrayOf()), SkillLimits(maxDirectories = 2)) }
        assertFailsWith<IllegalArgumentException> { SkillFileSystemSnapshot(mapOf("/a" to byteArrayOf(), "/a/b" to byteArrayOf())) }
        assertFailsWith<IllegalArgumentException> {
            SkillFileSystemSnapshot(mapOf("/a" to ByteArray(2), "/b" to ByteArray(2)), SkillLimits(maxFileBytes = 2, maxSkills = 1))
        }
    }

    @Test
    fun testLegacyCustomFilesystemRootIsNeverReplacedWithHostPath() = runTest {
        val zip = tempDir.resolve("skills.zip")
        java.nio.file.FileSystems.newFileSystem(java.net.URI.create("jar:${zip.toUri()}"), mapOf("create" to "true")).use { fs ->
            val root = fs.getPath("/")
            var observed: Path? = null
            val source = JvmFileSystemSkillSource(listOf(root), SkillLoadPolicy(), RootDirectoryOpener {
                observed = it
                Files.newDirectoryStream(it)
            }, object : SkillFileSystemHooks {})
            assertIs<SkillError.IoFailure>(assertFailsWith<SkillException> { source.load() }.error)
            assertSame(fs, observed!!.fileSystem)
        }
    }

    @Test
    fun testCloseFailureStillClosesEveryHeldDescriptor() = runTest {
        Files.createDirectory(tempDir.resolve("alpha"))
        Files.writeString(tempDir.resolve("alpha/SKILL.md"), document())
        var closed = 0
        fun tracked(stream: SecureDirectoryStream<Path>, fail: Boolean): SecureDirectoryStream<Path> =
            object : SecureDirectoryStream<Path> by stream {
                override fun newDirectoryStream(path: Path, vararg options: java.nio.file.LinkOption): SecureDirectoryStream<Path> =
                    tracked(stream.newDirectoryStream(path, *options), true)
                override fun close() {
                    closed++
                    stream.close()
                    if (fail) throw SecurityException("private filesystem detail")
                }
            }
        val source = JvmFileSystemSkillSource(listOf(tempDir), SkillLoadPolicy(), RootDirectoryOpener {
            tracked(Files.newDirectoryStream(it) as SecureDirectoryStream<Path>, false)
        }, object : SkillFileSystemHooks {})
        val failure = assertFailsWith<SkillException> { source.load() }
        assertIs<SkillError.IoFailure>(failure.error)
        assertEquals(2, closed)
        assertNull(failure.cause)
        assertFalse(failure.message.orEmpty().contains("private filesystem detail"))
    }

    @Test
    fun testYamlMetadataReservedKeysRetainExactStringsAndCase() {
        val keys = listOf("true", "True", "TRUE", "false", "False", "FALSE", "null", "Null", "NULL",
            "yes", "no", "on", "off", "y", "n", "~", "", "123", "a:\nb", "author")
        val metadata = keys.associateWith { "value for $it" }
        val skill = ai.koog.skills.model.Skill("alpha", "Description", "/location", metadata = metadata)
        val yaml = generateSkillsPrompt(listOf(skill), SkillsPromptFormat.YML, includeMetadata = true)
        val parsed = org.snakeyaml.engine.v2.api.Load(org.snakeyaml.engine.v2.api.LoadSettings.builder().build())
            .loadFromString(yaml) as Map<*, *>
        val row = (parsed["available_skills"] as List<*>).single() as Map<*, *>
        assertEquals(metadata, row["metadata"])
        assertEquals(keys.toSet(), (row["metadata"] as Map<*, *>).keys)
    }

    @Test
    fun testXmlWholeDocumentPreservesUnicodeEntitiesAndAllOptionalFields() {
        val value = "before\t\n\r<>&\"' café 漢字 \uD83D\uDE00 \uDBFF\uDFFF\uFFFDafter"
        val skill = ai.koog.skills.model.Skill(value, value, value, value, value, mapOf(value to value), value)
        val xml = generateSkillsPrompt(listOf(skill), SkillsPromptFormat.XML,
            includeLicense = true, includeCompatibility = true, includeMetadata = true, includeAllowedTools = true)
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(org.xml.sax.InputSource(java.io.StringReader(xml)))
        assertEquals("available_skills", document.documentElement.tagName)
        assertEquals(1, document.getElementsByTagName("skill").length)
        for (field in listOf("name", "description", "location", "license", "compatibility", "allowed-tools")) {
            assertEquals(value, document.getElementsByTagName(field).item(0).textContent)
        }
        val entry = document.getElementsByTagName("metadata").item(0).childNodes
        val element = (0 until entry.length).map { entry.item(it) }.filterIsInstance<org.w3c.dom.Element>().single()
        assertEquals("entry", element.tagName)
        assertEquals(value, element.getAttribute("key"))
        assertEquals(value, element.textContent)
    }

}
