package ai.koog.skills

public data class SkillLimits(
    public val maxRoots: Int = 16,
    public val maxDirectories: Int = 1_024,
    public val maxSkills: Int = 256,
    public val maxFileBytes: Long = 1_048_576,
    public val maxInstructionCharacters: Int = 100_000,
    public val maxDescriptionCharacters: Int = 1_024,
    public val maxCatalogueCharacters: Int = 100_000,
    public val maxRecursiveDepth: Int = 8,
    public val maxYamlCodePoints: Int = 1_000_000,
    public val maxYamlNestingDepth: Int = 32,
) {
    init {
        require(maxRoots > 0) { "maxRoots must be positive" }
        require(maxDirectories > 0) { "maxDirectories must be positive" }
        require(maxSkills > 0) { "maxSkills must be positive" }
        require(maxFileBytes > 0) { "maxFileBytes must be positive" }
        require(maxInstructionCharacters > 0) { "maxInstructionCharacters must be positive" }
        require(maxDescriptionCharacters > 0) { "maxDescriptionCharacters must be positive" }
        require(maxCatalogueCharacters > 0) { "maxCatalogueCharacters must be positive" }
        require(maxRecursiveDepth > 0) { "maxRecursiveDepth must be positive" }
        require(maxYamlCodePoints > 0) { "maxYamlCodePoints must be positive" }
        require(maxYamlNestingDepth > 0) { "maxYamlNestingDepth must be positive" }
    }
}

public enum class SkillDiscoveryMode { DIRECT_CHILDREN, RECURSIVE }
public enum class MissingRootPolicy { FAIL, IGNORE, DIAGNOSTIC }
public enum class MalformedSkillPolicy { FAIL, SKIP_WITH_DIAGNOSTIC }
public enum class DuplicateSkillPolicy { FAIL, KEEP_FIRST, KEEP_LAST }
public enum class ToolCollisionPolicy { FAIL, KEEP_EXISTING, REPLACE }
public enum class SymlinkPolicy { REJECT }
public enum class UnknownFieldPolicy { REJECT, IGNORE }
public enum class DiagnosticPathPolicy { REDACT, DISCLOSE }
public enum class SkillLifecyclePolicy { IMMUTABLE_SNAPSHOT, EXPLICIT_RELOAD }

public data class SkillLoadPolicy(
    public val limits: SkillLimits = SkillLimits(),
    public val discoveryMode: SkillDiscoveryMode = SkillDiscoveryMode.DIRECT_CHILDREN,
    public val missingRoot: MissingRootPolicy = MissingRootPolicy.FAIL,
    public val malformedSkill: MalformedSkillPolicy = MalformedSkillPolicy.FAIL,
    public val duplicateSkill: DuplicateSkillPolicy = DuplicateSkillPolicy.FAIL,
    public val symlink: SymlinkPolicy = SymlinkPolicy.REJECT,
    public val unknownField: UnknownFieldPolicy = UnknownFieldPolicy.REJECT,
    public val diagnosticPaths: DiagnosticPathPolicy = DiagnosticPathPolicy.REDACT,
    public val lifecycle: SkillLifecyclePolicy = SkillLifecyclePolicy.IMMUTABLE_SNAPSHOT,
    public val charsetName: String = "UTF-8",
) {
    init {
        require(charsetName.isNotBlank()) { "charsetName must not be blank" }
    }
}
