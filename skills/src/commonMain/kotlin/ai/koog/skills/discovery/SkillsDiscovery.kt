@file:JvmName("SkillsDiscovery")
package ai.koog.skills.discovery

import ai.koog.skills.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ai.koog.rag.base.files.FileSystemProvider
import ai.koog.skills.model.Skill
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.jvm.JvmName

private val logger = KotlinLogging.logger {}

/**
 * Defines which discovered skill wins when multiple skills share the same name.
 */
public enum class SkillCollisionPrecedence {
    FIRST_FOUND,
    LAST_FOUND,
}

/**
 * Discovers and parses skills from [directoriesToSearch] using breadth-first traversal.
 *
 * For each visited directory, this function looks for a file named [skillFileName].
 * If found, the file is parsed as a skill definition and validated. Skills with missing
 * required frontmatter fields (`name`, `description`) or malformed frontmatter are ignored.
 *
 * Name collisions are resolved by [precedenceRule]:
 * - [SkillCollisionPrecedence.FIRST_FOUND] keeps the first discovered skill.
 * - [SkillCollisionPrecedence.LAST_FOUND] replaces with the most recently discovered skill.
 *
 * Discovery is bounded by [maxDepth] and [maxDirectories], and directories listed in
 * [skippedDirectoryNames] are not traversed.
 *
 * Warnings (ignored roots/directories, malformed skill files, collisions, and format mismatches)
 * are reported through [warningLogger].
 *
 * Strict parsing rejects duplicate fields, aliases, invalid names and directory-name mismatches.
 * The JVM provider uses scoped secure directory descriptors. Unknown providers fail closed;
 * use [SkillFileSystemSnapshot] for bounded immutable in-memory discovery.
 * Diagnostics redact paths. Limits fail or report a diagnostic instead of silently truncating.
 *
 * @param fs file system provider used to interact with files.
 * @param directoriesToSearch absolute root directories to scan for skills.
 * @param maxDepth maximum traversal depth relative to each root directory. Must be `>= 0`.
 * @param maxDirectories maximum number of directories to visit across all roots. Must be `> 0`.
 * @param precedenceRule rule for resolving duplicate skill names.
 * @param skillFileName skill descriptor file name to search for in each directory.
 * @param skippedDirectoryNames directory names that should be ignored during traversal.
 * @param skillNamePattern regular expression used to validate discovered skill names.
 * @param warningLogger callback used to emit non-fatal discovery warnings.
 *
 * @return discovered skills after applying validation and collision precedence.
 *
 * @throws IllegalArgumentException if [maxDepth] is negative or [maxDirectories] is not positive.
 */
public suspend fun <Path> discoverSkills(
    fs: FileSystemProvider.ReadOnly<Path>,
    directoriesToSearch: List<String>,
    maxDepth: Int = 4,
    maxDirectories: Int = 2000,
    precedenceRule: SkillCollisionPrecedence = SkillCollisionPrecedence.LAST_FOUND,
    skillFileName: String = DEFAULT_SKILL_FILE_NAME,
    skippedDirectoryNames: Set<String> = DEFAULT_SKIPPED_DIRECTORY_NAMES,
    skillNamePattern: Regex = DEFAULT_SKILL_NAME_PATTERN,
    warningLogger: (String) -> Unit = ::defaultWarningLogger,
): List<Skill> {
    require(maxDepth >= 0) { "maxDepth must be >= 0" }
    require(maxDirectories > 0) { "maxDirectories must be > 0" }

    require(skillFileName.isNotBlank() && skillFileName != "." && skillFileName != ".." &&
        '/' !in skillFileName && '\\' !in skillFileName) { "skillFileName must be a single file name" }
    val policy = SkillLoadPolicy(
        limits = SkillLimits(maxDirectories = maxDirectories, maxRecursiveDepth = maxOf(1, maxDepth)),
        discoveryMode = SkillDiscoveryMode.RECURSIVE,
        missingRoot = MissingRootPolicy.DIAGNOSTIC,
        malformedSkill = MalformedSkillPolicy.SKIP_WITH_DIAGNOSTIC,
        duplicateSkill = if (precedenceRule == SkillCollisionPrecedence.LAST_FOUND)
            DuplicateSkillPolicy.KEEP_LAST else DuplicateSkillPolicy.KEEP_FIRST,
    )
    val result = discoverRecords(
        openSkillSession(fs, policy), directoriesToSearch, policy,
        upstream = true, maxDepth = maxDepth, skillFileName = skillFileName,
        skippedDirectoryNames = skippedDirectoryNames, skillNamePattern = skillNamePattern,
    )
    result.warnings.forEach(warningLogger)
    result.diagnostics.forEach { warningLogger(SkillException(it.error, policy.diagnosticPaths).message.orEmpty()) }
    return result.records.map { it.skill }
}

private fun defaultWarningLogger(message: String) { logger.warn { message } }
private const val DEFAULT_SKILL_FILE_NAME: String = "SKILL.md"
private val DEFAULT_SKIPPED_DIRECTORY_NAMES: Set<String> = setOf(".git", "node_modules")
private val DEFAULT_SKILL_NAME_PATTERN = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

internal enum class SkillEntryKind { DIRECTORY, FILE, SYMLINK, OTHER }
internal interface SkillEntry { val name: String; val location: String }

/** Entries are session-bound tokens; all host access stays behind held descriptors. */
internal interface SecureSkillSession {
    fun root(path: String): SkillEntry?
    fun inspect(entry: SkillEntry): SkillEntryKind?
    fun openDirectory(entry: SkillEntry): SkillEntry
    fun child(directory: SkillEntry, name: String): SkillEntry
    fun list(directory: SkillEntry, maximum: Int): List<SkillEntry>
    suspend fun read(entry: SkillEntry): String
    fun close()
}

internal expect fun <Path> openSkillSession(fs: FileSystemProvider.ReadOnly<Path>, policy: SkillLoadPolicy): SecureSkillSession

internal data class SkillDiscoveryResult(val records: List<ParsedSkillDocument>, val diagnostics: List<SkillDiagnostic>, val warnings: List<String>)

internal suspend fun discoverRecords(
    session: SecureSkillSession,
    roots: List<String>,
    policy: SkillLoadPolicy,
    upstream: Boolean,
    maxDepth: Int = policy.limits.maxRecursiveDepth,
    skillFileName: String = DEFAULT_SKILL_FILE_NAME,
    skippedDirectoryNames: Set<String> = emptySet(),
    skillNamePattern: Regex = DEFAULT_SKILL_NAME_PATTERN,
): SkillDiscoveryResult {
    val diagnostics = mutableListOf<SkillDiagnostic>()
    val warnings = mutableListOf<String>()
    val candidates = mutableListOf<ParsedSkillDocument>()
    val queue = ArrayDeque<Pair<SkillEntry, Int>>()
    var entries = 0
    var directories = 0
    var exhausted = false
    fun reference(entry: SkillEntry): SkillSourceReference? =
        if (policy.diagnosticPaths == DiagnosticPathPolicy.DISCLOSE) SkillSourceReference(entry.location) else null
    fun malformed(error: SkillError) {
        if (policy.malformedSkill == MalformedSkillPolicy.FAIL) throw SkillException(error, policy.diagnosticPaths)
        diagnostics += SkillDiagnostic(error)
    }
    fun list(directory: SkillEntry): List<SkillEntry> {
        val remaining = policy.limits.maxDirectories - entries
        val children = session.list(directory, if (remaining == Int.MAX_VALUE) remaining else remaining + 1)
        entries += children.size
        if (entries > policy.limits.maxDirectories) {
            exhausted = true
            malformed(SkillError.LimitExceeded("directory entries", policy.limits.maxDirectories.toLong(), reference(directory)))
        }
        return children.take(remaining.coerceAtLeast(0))
    }
    fun kind(entry: SkillEntry): SkillEntryKind? {
        val type = session.inspect(entry)
        if (type == SkillEntryKind.SYMLINK) {
            malformed(SkillError.SymlinkRejected(reference(entry)))
            return null
        }
        return type
    }
    suspend fun load(directory: SkillEntry) {
        val file = session.child(directory, skillFileName)
        if (kind(file) != SkillEntryKind.FILE) return
        // File-byte limits and secure read errors are fatal, as in the legacy source.
        val document = try { session.read(file) } catch (error: SkillException) {
            if (error.error !is SkillError.DecodingFailure) throw error
            malformed(error.error.withSource(reference(file)))
            return
        }
        currentCoroutineContext().ensureActive()
        val record = try {
            SkillDocumentParser.parse(document, directory.name, policy, file.location, upstream).also {
                if (!skillNamePattern.matches(it.skill.name)) throw SkillException(
                    SkillError.InvalidField("name", "does not match expected name format"), policy.diagnosticPaths,
                )
            }
        } catch (error: SkillException) {
            malformed(error.error.withSource(reference(file)))
            return
        }
        candidates += record
        if (candidates.size > policy.limits.maxSkills) throw SkillException(
            SkillError.LimitExceeded("skills", policy.limits.maxSkills.toLong(), reference(file)), policy.diagnosticPaths,
        )
    }
    var primaryFailure: Throwable? = null
    try {
        currentCoroutineContext().ensureActive()
        if (roots.size > policy.limits.maxRoots) throw SkillException(
            SkillError.LimitExceeded("roots", policy.limits.maxRoots.toLong()), policy.diagnosticPaths,
        )
        for (root in roots) {
            currentCoroutineContext().ensureActive()
            val directory = session.root(root)
            if (directory == null) {
                val error = SkillError.MissingRoot(if (policy.diagnosticPaths == DiagnosticPathPolicy.DISCLOSE) SkillSourceReference(root) else null)
                when (policy.missingRoot) {
                    MissingRootPolicy.FAIL -> throw SkillException(error, policy.diagnosticPaths)
                    MissingRootPolicy.IGNORE -> Unit
                    MissingRootPolicy.DIAGNOSTIC -> diagnostics += SkillDiagnostic(error)
                }
            } else queue.addLast(directory to 0)
        }
        while (queue.isNotEmpty() && !exhausted) {
            currentCoroutineContext().ensureActive()
            val (directory, depth) = queue.removeFirst()
            if (upstream) {
                directories++
                if (directories > policy.limits.maxDirectories) {
                    malformed(SkillError.LimitExceeded("directories", policy.limits.maxDirectories.toLong(), reference(directory)))
                    break
                }
            }
            if (upstream || depth > 0) load(directory)
            if (!upstream && policy.discoveryMode == SkillDiscoveryMode.DIRECT_CHILDREN && depth > 0) continue
            if (upstream && depth >= maxDepth) continue
            val children = list(directory)
            for (child in children) {
                currentCoroutineContext().ensureActive()
                if (exhausted) break
                if (child.name in skippedDirectoryNames) {
                    if (upstream) warnings += "Skill discovery ignored directory '${child.name}'"
                    continue
                }
                if (kind(child) != SkillEntryKind.DIRECTORY) continue
                if (!upstream && depth >= maxDepth) {
                    malformed(SkillError.LimitExceeded("recursive depth", maxDepth.toLong(), reference(child)))
                    break
                }
                if (!upstream && ++directories > policy.limits.maxDirectories) {
                    exhausted = true
                    malformed(SkillError.LimitExceeded("directories", policy.limits.maxDirectories.toLong(), reference(child)))
                    break
                }
                queue.addLast(session.openDirectory(child) to depth + 1)
            }
        }
        val snapshot = SkillSnapshot(policy)
        val ordered = if (upstream) candidates else candidates.sortedWith(compareBy({ it.skill.name }, { it.skill.location }))
        ordered.forEach { record ->
            snapshot.add(record, if (policy.diagnosticPaths == DiagnosticPathPolicy.DISCLOSE) SkillSourceReference(record.skill.location) else null)
        }
        return SkillDiscoveryResult(snapshot.records, diagnostics + snapshot.diagnostics, warnings)
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        try { session.close() } catch (error: Throwable) {
            val primary = primaryFailure
            if (primary == null) throw error
            primary.addSuppressed(error)
        }
    }
}

internal fun SkillError.withSource(source: SkillSourceReference?): SkillError = when (this) {
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

/**
 * A bounded, immutable in-memory filesystem accepted by [discoverSkills].
 * [files] maps absolute, normalised slash-separated file paths to bytes, copied on construction.
 * Parent directories are inferred. The snapshot never delegates to a host filesystem.
 */
public class SkillFileSystemSnapshot(
    files: Map<String, ByteArray>,
    limits: SkillLimits = SkillLimits(),
) : FileSystemProvider.ReadOnly<String> {
    private val contents: Map<String, ByteArray>
    private val directories: Set<String>
    init {
        require(files.size <= limits.maxDirectories) { "Too many snapshot files" }
        val parents = linkedSetOf("/")
        val copied = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        files.forEach { (path, bytes) ->
            fromAbsolutePathString(path)
            require(path != "/") { "A snapshot file needs a name" }
            require(bytes.size.toLong() <= limits.maxFileBytes) { "Snapshot file exceeds byte limit" }
            totalBytes += bytes.size
            require(totalBytes == 0L || (totalBytes - 1) / limits.maxFileBytes < limits.maxSkills) {
                "Snapshot exceeds total byte limit"
            }
            var parent = parent(path)
            while (parent != null) {
                parents += parent
                require(parents.size + copied.size <= limits.maxDirectories) { "Too many snapshot entries" }
                parent = parent(parent)
            }
            copied[path] = bytes.copyOf()
        }
        require(copied.keys.none { it in parents }) { "Snapshot file overlaps a directory" }
        require(parents.size + copied.size <= limits.maxDirectories) { "Too many snapshot entries" }
        contents = copied
        directories = parents
    }
    override fun toAbsolutePathString(path: String): String = fromAbsolutePathString(path)
    override fun fromAbsolutePathString(path: String): String {
        require(path.startsWith('/') && (path == "/" || path.split('/').drop(1).all {
            it.isNotEmpty() && it != "." && it != ".." && '\\' !in it && '\u0000' !in it
        })) { "Snapshot paths must be absolute and normalised" }
        return path
    }
    override fun joinPath(base: String, vararg parts: String): String =
        fromAbsolutePathString(base.trimEnd('/') + "/" + parts.joinToString("/"))
    override fun name(path: String): String = path.substringAfterLast('/')
    override fun extension(path: String): String = name(path).substringAfterLast('.', "")
    override fun parent(path: String): String? = if (path == "/") null else path.substringBeforeLast('/').ifEmpty { "/" }
    override fun relativize(root: String, path: String): String? = when {
        path == root -> ""
        path.startsWith(root.trimEnd('/') + "/") -> path.removePrefix(root.trimEnd('/') + "/")
        else -> null
    }
    override suspend fun metadata(path: String): ai.koog.rag.base.files.FileMetadata? {
        val type = when (path) {
            in contents -> ai.koog.rag.base.files.FileMetadata.FileType.File
            in directories -> ai.koog.rag.base.files.FileMetadata.FileType.Directory
            else -> return null
        }
        return ai.koog.rag.base.files.FileMetadata(type, name(path).startsWith('.'))
    }
    override suspend fun getFileContentType(path: String): ai.koog.rag.base.files.FileMetadata.FileContentType {
        require(path in contents)
        return ai.koog.rag.base.files.FileMetadata.FileContentType.Text
    }
    override suspend fun list(directory: String): List<String> {
        require(directory in directories)
        return (directories + contents.keys).filter { parent(it) == directory }.sorted()
    }
    override suspend fun exists(path: String): Boolean = path in contents || path in directories
    override suspend fun readBytes(path: String): ByteArray = checkNotNull(contents[path]).copyOf()
    override suspend fun inputStream(path: String): kotlinx.io.Source = kotlinx.io.Buffer().apply { write(readBytes(path)) }
    override suspend fun size(path: String): Long = checkNotNull(contents[path]).size.toLong()

    internal fun openSession(policy: SkillLoadPolicy): SecureSkillSession = object : SecureSkillSession {
        private var closed = false
        private val identity = Any()
        private inner class Entry(override val location: String) : SkillEntry {
            val owner: Any = identity
            override val name: String get() = this@SkillFileSystemSnapshot.name(location)
        }
        private fun checked(entry: SkillEntry): Entry {
            check(!closed) { "Skill filesystem session is closed" }
            require(entry is Entry && entry.owner === identity) { "Foreign skill filesystem token" }
            return entry
        }
        override fun root(path: String): SkillEntry? {
            check(!closed)
            return fromAbsolutePathString(path).takeIf { it in directories }?.let(::Entry)
        }
        override fun inspect(entry: SkillEntry): SkillEntryKind? = when (checked(entry).location) {
            in directories -> SkillEntryKind.DIRECTORY
            in contents -> SkillEntryKind.FILE
            else -> null
        }
        override fun openDirectory(entry: SkillEntry): SkillEntry = checked(entry).also { require(it.location in directories) }
        override fun child(directory: SkillEntry, name: String): SkillEntry = Entry(joinPath(checked(directory).location, name))
        override fun list(directory: SkillEntry, maximum: Int): List<SkillEntry> {
            val path = checked(directory).location
            return (directories.asSequence() + contents.keys.asSequence())
                .filter { parent(it) == path }.take(maximum).sorted().map(::Entry).toList()
        }
        override suspend fun read(entry: SkillEntry): String {
            val bytes = checkNotNull(contents[checked(entry).location])
            if (bytes.size > policy.limits.maxFileBytes) throw SkillException(
                SkillError.LimitExceeded("file bytes", policy.limits.maxFileBytes), policy.diagnosticPaths,
            )
            return try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (_: CharacterCodingException) {
                throw SkillException(SkillError.DecodingFailure("UTF-8"), policy.diagnosticPaths)
            }
        }
        override fun close() { closed = true }
    }
}
