package ai.koog.skills

import ai.koog.skills.discovery.JvmSecureSkillSession
import ai.koog.skills.discovery.discoverRecords
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Path

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
        val configuredRoots = roots.associateBy { it.toString() }
        if (roots.any { configuredRoots.getValue(it.toString()) != it }) throw SkillException(
            SkillError.IoFailure("configured roots have ambiguous filesystem identities"), policy.diagnosticPaths,
        )
        val result = discoverRecords(
            JvmSecureSkillSession(policy, rootDirectoryOpener, hooks, configuredRoots = configuredRoots),
            roots.map { it.toString() }, policy, upstream = false,
        )
        return SkillSourceResult(result.records.map { it.toLegacy() }.sortedBy { it.name }, result.diagnostics)
    }
}
