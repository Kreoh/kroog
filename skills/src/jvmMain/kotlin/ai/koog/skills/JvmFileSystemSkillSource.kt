package ai.koog.skills

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.DirectoryIteratorException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes

internal fun interface RootDirectoryOpener {
    fun open(path: Path): DirectoryStream<Path>
}

internal interface SkillFileSystemHooks {
    fun beforeAttributes(path: Path) {}
    fun afterAttributes(path: Path) {}
}

private object DefaultRootDirectoryOpener : RootDirectoryOpener {
    override fun open(path: Path): DirectoryStream<Path> = Files.newDirectoryStream(path)
}

private object NoSkillFileSystemHooks : SkillFileSystemHooks

/** Bounded, deterministic skill discovery rooted at explicitly configured JVM paths. */
public class JvmFileSystemSkillSource internal constructor(
    roots: Collection<Path>,
    private val policy: SkillLoadPolicy,
    private val rootDirectoryOpener: RootDirectoryOpener,
    private val hooks: SkillFileSystemHooks,
) : SkillSource {
    public constructor(
        roots: Collection<Path>,
        policy: SkillLoadPolicy = SkillLoadPolicy(),
    ) : this(roots, policy, DefaultRootDirectoryOpener, NoSkillFileSystemHooks)

    private val roots: List<Path> = roots.map { it.toAbsolutePath().normalize() }.sortedBy { it.toString() }

    override suspend fun load(): SkillSourceResult {
        if (roots.size > policy.limits.maxRoots) {
            throw failure(SkillError.LimitExceeded("roots", policy.limits.maxRoots.toLong()))
        }
        val diagnostics: MutableList<SkillDiagnostic> = mutableListOf()
        val discovered: MutableList<DiscoveredSkill> = mutableListOf()
        val budget = DiscoveryBudget()

        for (root in roots) {
            if (budget.exhausted) break
            withSecureRoot(root, diagnostics) { rootStream ->
                discoverChildren(rootStream, root, depth = 1, budget, discovered, diagnostics)
            }
        }

        val selected: MutableMap<String, DiscoveredSkill> = linkedMapOf()
        discovered.sortedWith(compareBy<DiscoveredSkill>({ it.skill.name }, { it.path.toString() })).forEach { candidate ->
            val previous: DiscoveredSkill? = selected[candidate.skill.name]
            if (previous == null) {
                selected[candidate.skill.name] = candidate
            } else {
                val error: SkillError = SkillError.DuplicateName(candidate.skill.name, reference(candidate.path))
                when (policy.duplicateSkill) {
                    DuplicateSkillPolicy.FAIL -> throw failure(error)
                    DuplicateSkillPolicy.KEEP_FIRST -> diagnostics += SkillDiagnostic(error)
                    DuplicateSkillPolicy.KEEP_LAST -> {
                        selected[candidate.skill.name] = candidate
                        diagnostics += SkillDiagnostic(error)
                    }
                }
            }
        }
        return SkillSourceResult(selected.values.map { it.skill }.sortedBy { it.name }, diagnostics)
    }

    private fun withSecureRoot(
        root: Path,
        diagnostics: MutableList<SkillDiagnostic>,
        block: (SecureDirectoryStream<Path>) -> Unit,
    ) {
        val opened: DirectoryStream<Path> = try {
            rootDirectoryOpener.open(root)
        } catch (_: NoSuchFileException) {
            handleMissingRoot(root, diagnostics)
            return
        } catch (error: IOException) {
            throw failure(SkillError.IoFailure("open configured root", reference(root)), error)
        } catch (error: SecurityException) {
            throw failure(SkillError.IoFailure("open configured root", reference(root)), error)
        }
        val secure: SecureDirectoryStream<Path> = opened as? SecureDirectoryStream<Path> ?: run {
            closeDirectory(opened, root)
            throw failure(
                SkillError.IoFailure("secure directory operations are unavailable", reference(root))
            )
        }
        secureIo("read configured root", root) {
            secure.use(block)
        }
    }

    private fun discoverChildren(
        parent: SecureDirectoryStream<Path>,
        parentPath: Path,
        depth: Int,
        budget: DiscoveryBudget,
        discovered: MutableList<DiscoveredSkill>,
        diagnostics: MutableList<SkillDiagnostic>,
    ) {
        val entries: List<Path> = boundedEntries(parent, parentPath, budget, diagnostics)
        for (name in entries) {
            if (budget.exhausted) break
            val path: Path = parentPath.resolve(name)
            val attributes: BasicFileAttributes = readAttributes(parent, name, path) ?: continue
            if (attributes.isSymbolicLink) {
                handleSymlink(path, diagnostics)
                continue
            }
            if (!attributes.isDirectory) continue

            budget.directories++
            if (budget.directories > policy.limits.maxDirectories) {
                exhaustBudget("directories", path, budget, diagnostics)
                break
            }
            afterAttributes(path)
            val child: SecureDirectoryStream<Path> = secureIo("open skill directory", path) {
                parent.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)
            }
            secureIo("read skill directory", path) {
                child.use { childStream ->
                    loadDocument(childStream, path, discovered, diagnostics)
                    if (policy.discoveryMode == SkillDiscoveryMode.RECURSIVE && !budget.exhausted) {
                        if (depth >= policy.limits.maxRecursiveDepth) {
                            detectDepthOverflow(childStream, path, budget, diagnostics)
                        } else {
                            discoverChildren(childStream, path, depth + 1, budget, discovered, diagnostics)
                        }
                    }
                }
            }
        }
    }

    private fun detectDepthOverflow(
        directory: SecureDirectoryStream<Path>,
        directoryPath: Path,
        budget: DiscoveryBudget,
        diagnostics: MutableList<SkillDiagnostic>,
    ) {
        val entries: List<Path> = boundedEntries(directory, directoryPath, budget, diagnostics)
        for (name in entries) {
            val path: Path = directoryPath.resolve(name)
            val attributes: BasicFileAttributes = readAttributes(directory, name, path) ?: continue
            if (attributes.isSymbolicLink) {
                handleSymlink(path, diagnostics)
            } else if (attributes.isDirectory) {
                handleMalformed(
                    SkillError.LimitExceeded(
                        "recursive depth",
                        policy.limits.maxRecursiveDepth.toLong(),
                        reference(path),
                    ),
                    diagnostics,
                )
                return
            }
        }
    }

    private fun loadDocument(
        directory: SecureDirectoryStream<Path>,
        directoryPath: Path,
        discovered: MutableList<DiscoveredSkill>,
        diagnostics: MutableList<SkillDiagnostic>,
    ) {
        val name: Path = directoryPath.fileSystem.getPath("SKILL.md")
        val path: Path = directoryPath.resolve(name)
        val attributes: BasicFileAttributes = readAttributes(directory, name, path) ?: return
        if (attributes.isSymbolicLink) {
            handleSymlink(path, diagnostics)
            return
        }
        if (!attributes.isRegularFile) return
        if (attributes.size() > policy.limits.maxFileBytes) {
            throw failure(SkillError.LimitExceeded("file bytes", policy.limits.maxFileBytes, reference(path)))
        }

        afterAttributes(path)
        val bytes: ByteArray = readBounded(directory, name, path)
        val skill: Skill = try {
            val document: String = decode(bytes, path)
            SkillParser.parse(document, directoryPath.fileName.toString(), policy)
        } catch (error: SkillException) {
            val sourced: SkillError = error.error.withSource(reference(path))
            if (policy.malformedSkill == MalformedSkillPolicy.FAIL) throw failure(sourced, error)
            diagnostics += SkillDiagnostic(sourced)
            return
        }
        discovered += DiscoveredSkill(skill, path)
        if (discovered.size > policy.limits.maxSkills) {
            throw failure(SkillError.LimitExceeded("skills", policy.limits.maxSkills.toLong(), reference(path)))
        }
    }

    private fun boundedEntries(
        directory: SecureDirectoryStream<Path>,
        directoryPath: Path,
        budget: DiscoveryBudget,
        diagnostics: MutableList<SkillDiagnostic>,
    ): List<Path> {
        val entries: MutableList<Path> = mutableListOf()
        val iterator: Iterator<Path> = directory.iterator()
        while (hasNext(iterator, directoryPath)) {
            val entry: Path = next(iterator, directoryPath)
            budget.entries++
            if (budget.entries > policy.limits.maxDirectories) {
                exhaustBudget("directory entries", directoryPath, budget, diagnostics)
                break
            }
            entries.add(entry.fileName)
        }
        return entries.sortedBy { it.toString() }
    }

    private fun readAttributes(
        directory: SecureDirectoryStream<Path>,
        name: Path,
        path: Path,
    ): BasicFileAttributes? {
        beforeAttributes(path)
        return try {
            val view: BasicFileAttributeView = directory.getFileAttributeView(
                name,
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ) ?: throw failure(SkillError.IoFailure("read entry attributes", reference(path)))
            view.readAttributes()
        } catch (_: NoSuchFileException) {
            null
        } catch (error: SkillException) {
            throw error
        } catch (error: IOException) {
            throw failure(SkillError.IoFailure("read entry attributes", reference(path)), error)
        } catch (error: SecurityException) {
            throw failure(SkillError.IoFailure("read entry attributes", reference(path)), error)
        }
    }

    private fun readBounded(
        directory: SecureDirectoryStream<Path>,
        name: Path,
        path: Path,
    ): ByteArray = secureIo("read skill document", path) {
        val output = ByteArrayOutputStream()
        val options = setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        directory.newByteChannel(name, options).use { channel ->
            copyBounded(channel, output, path)
        }
        output.toByteArray()
    }

    private fun copyBounded(channel: SeekableByteChannel, output: ByteArrayOutputStream, path: Path) {
        val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
        var total: Long = 0
        while (true) {
            buffer.clear()
            val count: Int = channel.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count
            if (total > policy.limits.maxFileBytes) {
                throw failure(SkillError.LimitExceeded("file bytes", policy.limits.maxFileBytes, reference(path)))
            }
            output.write(buffer.array(), 0, count)
        }
    }

    private fun decode(bytes: ByteArray, path: Path): String {
        val charset: Charset = try {
            Charset.forName(policy.charsetName)
        } catch (error: RuntimeException) {
            throw failure(SkillError.DecodingFailure(policy.charsetName, reference(path)), error)
        }
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw failure(SkillError.DecodingFailure(charset.name(), reference(path)), error)
        }
    }

    private fun handleMissingRoot(root: Path, diagnostics: MutableList<SkillDiagnostic>) {
        val error: SkillError = SkillError.MissingRoot(reference(root))
        when (policy.missingRoot) {
            MissingRootPolicy.FAIL -> throw failure(error)
            MissingRootPolicy.IGNORE -> Unit
            MissingRootPolicy.DIAGNOSTIC -> diagnostics += SkillDiagnostic(error)
        }
    }

    private fun handleSymlink(path: Path, diagnostics: MutableList<SkillDiagnostic>) {
        val error: SkillError = when (policy.symlink) {
            SymlinkPolicy.REJECT -> SkillError.SymlinkRejected(reference(path))
            SymlinkPolicy.ALLOW_INTERNAL -> SkillError.ContainmentViolation(reference(path))
        }
        handleMalformed(error, diagnostics)
    }

    private fun exhaustBudget(
        limit: String,
        path: Path,
        budget: DiscoveryBudget,
        diagnostics: MutableList<SkillDiagnostic>,
    ) {
        budget.exhausted = true
        handleMalformed(
            SkillError.LimitExceeded(limit, policy.limits.maxDirectories.toLong(), reference(path)),
            diagnostics,
        )
    }

    private fun handleMalformed(error: SkillError, diagnostics: MutableList<SkillDiagnostic>) {
        when (policy.malformedSkill) {
            MalformedSkillPolicy.FAIL -> throw failure(error)
            MalformedSkillPolicy.SKIP_WITH_DIAGNOSTIC -> diagnostics += SkillDiagnostic(error)
        }
    }

    private fun beforeAttributes(path: Path) {
        secureIo("read entry attributes", path) { hooks.beforeAttributes(path) }
    }

    private fun afterAttributes(path: Path) {
        secureIo("secure entry after attribute read", path) { hooks.afterAttributes(path) }
    }

    private fun hasNext(iterator: Iterator<Path>, path: Path): Boolean = try {
        iterator.hasNext()
    } catch (error: DirectoryIteratorException) {
        throw failure(SkillError.IoFailure("iterate directory", reference(path)), error.cause)
    } catch (error: SecurityException) {
        throw failure(SkillError.IoFailure("iterate directory", reference(path)), error)
    }

    private fun next(iterator: Iterator<Path>, path: Path): Path = try {
        iterator.next()
    } catch (error: DirectoryIteratorException) {
        throw failure(SkillError.IoFailure("iterate directory", reference(path)), error.cause)
    } catch (error: SecurityException) {
        throw failure(SkillError.IoFailure("iterate directory", reference(path)), error)
    }

    private fun closeDirectory(directory: DirectoryStream<Path>, path: Path) {
        try {
            directory.close()
        } catch (error: IOException) {
            throw failure(SkillError.IoFailure("close directory", reference(path)), error)
        }
    }

    private fun reference(path: Path): SkillSourceReference? =
        path.takeIf { policy.diagnosticPaths == DiagnosticPathPolicy.DISCLOSE }?.let { SkillSourceReference(it.toString()) }

    private fun failure(error: SkillError, cause: Throwable? = null): SkillException =
        SkillException(error, policy.diagnosticPaths, cause.takeIf { policy.diagnosticPaths == DiagnosticPathPolicy.DISCLOSE })

    private inline fun <T> secureIo(operation: String, path: Path, block: () -> T): T = try {
        block()
    } catch (error: SkillException) {
        throw error
    } catch (error: IOException) {
        throw failure(SkillError.IoFailure(operation, reference(path)), error)
    } catch (error: SecurityException) {
        throw failure(SkillError.IoFailure(operation, reference(path)), error)
    }

    private data class DiscoveryBudget(
        var entries: Int = 0,
        var directories: Int = 0,
        var exhausted: Boolean = false,
    )

    private data class DiscoveredSkill(val skill: Skill, val path: Path)
}

private fun SkillError.withSource(source: SkillSourceReference?): SkillError = when (this) {
    is SkillError.MalformedFrontmatter -> copy(source = source)
    is SkillError.InvalidField -> copy(source = source)
    is SkillError.LimitExceeded -> copy(source = source)
    is SkillError.DuplicateName -> copy(source = source)
    is SkillError.MissingRoot -> copy(source = source)
    is SkillError.ContainmentViolation -> copy(source = source)
    is SkillError.SymlinkRejected -> copy(source = source)
    is SkillError.DecodingFailure -> copy(source = source)
    is SkillError.IoFailure -> copy(source = source)
    is SkillError.UnknownSkill, SkillError.CatalogueOverflow, SkillError.ToolCollision -> this
}
