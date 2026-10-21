package ai.koog.skills

import java.nio.file.Files
import java.nio.file.Path

/**
 * These fixtures use isolated operator-owned temporary trees. Race tests deliberately probe specific
 * best-effort safeguards; they do not establish protection against a false immutability declaration.
 */
internal fun trustedSkillSource(
    roots: Collection<Path>,
    policy: SkillLoadPolicy = SkillLoadPolicy(),
    rootDirectoryOpener: RootDirectoryOpener = RootDirectoryOpener { Files.newDirectoryStream(it) },
    hooks: SkillFileSystemHooks = object : SkillFileSystemHooks {},
): JvmFileSystemSkillSource = JvmFileSystemSkillSource(
    roots, policy, rootDirectoryOpener, hooks, SkillFileSystemTrust.TRUSTED_IMMUTABLE,
)
