package ai.koog.skills.discovery

import ai.koog.skills.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ai.koog.rag.base.files.FileSystemProvider
import ai.koog.rag.base.files.JVMFileSystemProvider
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
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes


internal actual fun <Path> openSkillSession(fs: FileSystemProvider.ReadOnly<Path>, policy: SkillLoadPolicy): SecureSkillSession =
    when (fs) {
        JVMFileSystemProvider.ReadOnly -> JvmSecureSkillSession(policy, ignoreNonDirectoryRoots = true)
        is SkillFileSystemSnapshot -> fs.openSession(policy)
        else -> throw SkillException(SkillError.IoFailure("provider has no verified secure skills session"), policy.diagnosticPaths)
    }

/** Owns every descriptor until discovery exits, including failed and cancelled traversals. */
internal class JvmSecureSkillSession(
    private val policy: SkillLoadPolicy,
    private val rootDirectoryOpener: RootDirectoryOpener = RootDirectoryOpener { Files.newDirectoryStream(it) },
    private val hooks: SkillFileSystemHooks = object : SkillFileSystemHooks {},
    private val ignoreNonDirectoryRoots: Boolean = false,
    private val configuredRoots: Map<String, Path>? = null,
) : SecureSkillSession {
    private val directories = mutableListOf<SecureDirectoryStream<Path>>()
    private var closed = false
    private inner class Entry(
        val path: Path,
        val parent: SecureDirectoryStream<Path>?,
        val directory: SecureDirectoryStream<Path>? = null,
    ) : SkillEntry {
        val owner: JvmSecureSkillSession = this@JvmSecureSkillSession
        var attributes: BasicFileAttributes? = null
        override val name: String get() = path.fileName?.toString().orEmpty()
        override val location: String get() = path.toString()
    }
    private fun checked(entry: SkillEntry): Entry {
        check(!closed) { "Skill filesystem session is closed" }
        require(entry is Entry && entry.owner === this) { "Foreign skill filesystem token" }
        return entry
    }
    override fun root(path: String): SkillEntry? {
        check(!closed)
        val root = configuredRoots?.getValue(path) ?: Path.of(path).also {
            require(it.isAbsolute) { "Skill root paths must be absolute" }
        }.normalize()
        val opened = try { rootDirectoryOpener.open(root) }
        catch (_: NoSuchFileException) { return null }
        catch (error: NotDirectoryException) {
            if (ignoreNonDirectoryRoots) return null
            throw failure(SkillError.IoFailure("open configured root", reference(root)), error)
        }
        catch (error: IOException) { throw failure(SkillError.IoFailure("open configured root", reference(root)), error) }
        catch (error: SecurityException) { throw failure(SkillError.IoFailure("open configured root", reference(root)), error) }
        val secure = opened as? SecureDirectoryStream<Path> ?: run {
            closeDirectory(opened, root)
            throw failure(SkillError.IoFailure("secure directory operations are unavailable", reference(root)))
        }
        directories += secure
        return Entry(root, null, secure)
    }
    override fun inspect(entry: SkillEntry): SkillEntryKind? {
        val token = checked(entry)
        if (token.directory != null) return SkillEntryKind.DIRECTORY
        val attributes = readAttributes(checkNotNull(token.parent), token.path.fileName, token.path) ?: return null
        token.attributes = attributes
        return when {
            attributes.isSymbolicLink -> SkillEntryKind.SYMLINK
            attributes.isDirectory -> SkillEntryKind.DIRECTORY
            attributes.isRegularFile -> SkillEntryKind.FILE
            else -> SkillEntryKind.OTHER
        }
    }
    override fun openDirectory(entry: SkillEntry): SkillEntry {
        val token = checked(entry)
        afterAttributes(token.path)
        val stream = secureIo("open skill directory", token.path) {
            checkNotNull(token.parent).newDirectoryStream(token.path.fileName, LinkOption.NOFOLLOW_LINKS)
        }
        directories += stream
        return Entry(token.path, token.parent, stream)
    }
    override fun child(directory: SkillEntry, name: String): SkillEntry {
        val token = checked(directory)
        return Entry(token.path.resolve(name), checkNotNull(token.directory))
    }
    override fun list(directory: SkillEntry, maximum: Int): List<SkillEntry> {
        val token = checked(directory)
        val stream = checkNotNull(token.directory)
        val entries = mutableListOf<Entry>()
        val iterator = secureIo("iterate directory", token.path) { stream.iterator() }
        while (entries.size < maximum && hasNext(iterator, token.path)) {
            entries += Entry(token.path.resolve(next(iterator, token.path).fileName), stream)
        }
        return entries.sortedBy { it.name }
    }
    override suspend fun read(entry: SkillEntry): String {
        val token = checked(entry)
        val attributes = checkNotNull(token.attributes)
        if (attributes.size() > policy.limits.maxFileBytes) throw failure(
            SkillError.LimitExceeded("file bytes", policy.limits.maxFileBytes, reference(token.path)),
        )
        afterAttributes(token.path)
        return decode(readBounded(checkNotNull(token.parent), token.path.fileName, token.path), token.path)
    }
    override fun close() {
        if (closed) return
        closed = true
        var failure: Throwable? = null
        directories.asReversed().forEach { stream ->
            try { stream.close() } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        directories.clear()
        failure?.let { throw failure(SkillError.IoFailure("close directory"), it) }
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

    private suspend fun readBounded(
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

    private suspend fun copyBounded(channel: SeekableByteChannel, output: ByteArrayOutputStream, path: Path) {
        val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
        var total: Long = 0
        while (true) {
            currentCoroutineContext().ensureActive()
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

}
