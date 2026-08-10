package ai.koog.skills

public data class SkillSourceReference(public val value: String)

public sealed class SkillError(public open val source: SkillSourceReference? = null) {
    public data class MalformedFrontmatter(public val reason: String, override val source: SkillSourceReference? = null) : SkillError(source)
    public data class InvalidField(public val field: String, public val reason: String, override val source: SkillSourceReference? = null) : SkillError(source)
    public data class LimitExceeded(public val limit: String, public val maximum: Long, override val source: SkillSourceReference? = null) : SkillError(source)
    public data class DuplicateName(public val name: String, override val source: SkillSourceReference? = null) : SkillError(source)
    public data class MissingRoot(override val source: SkillSourceReference? = null) : SkillError(source)
    public data class ContainmentViolation(override val source: SkillSourceReference? = null) : SkillError(source)
    public data class SymlinkRejected(override val source: SkillSourceReference? = null) : SkillError(source)
    public data class DecodingFailure(public val charsetName: String, override val source: SkillSourceReference? = null) : SkillError(source)
    public data class IoFailure(public val operation: String, override val source: SkillSourceReference? = null) : SkillError(source)
    public data class UnknownSkill(public val name: String) : SkillError()
    public data object CatalogueOverflow : SkillError()
    public data object ToolCollision : SkillError()

    public fun message(pathPolicy: DiagnosticPathPolicy = DiagnosticPathPolicy.REDACT): String {
        val detail: String = when (this) {
            is MalformedFrontmatter -> "Malformed skill frontmatter: $reason"
            is InvalidField -> "Invalid skill field '$field': $reason"
            is LimitExceeded -> "Skill resource limit '$limit' exceeded (maximum $maximum)"
            is DuplicateName -> "Duplicate skill name '$name'"
            is MissingRoot -> "Skill root does not exist"
            is ContainmentViolation -> "Skill entry escapes its configured root"
            is SymlinkRejected -> "Symbolic links are disabled for skill discovery"
            is DecodingFailure -> "Skill document is not valid $charsetName"
            is IoFailure -> "Skill I/O operation failed: $operation"
            is UnknownSkill -> "Unknown skill '$name'"
            CatalogueOverflow -> "Skill catalogue exceeds its configured limit"
            ToolCollision -> "A tool named 'load_skill' is already registered"
        }
        val visibleSource: String? = source?.value?.takeIf { pathPolicy == DiagnosticPathPolicy.DISCLOSE }
        return if (visibleSource == null) detail else "$detail (source: $visibleSource)"
    }
}

public class SkillException(
    public val error: SkillError,
    public val pathPolicy: DiagnosticPathPolicy = DiagnosticPathPolicy.REDACT,
    cause: Throwable? = null,
) : IllegalArgumentException(error.message(pathPolicy), cause)

public enum class SkillDiagnosticSeverity { WARNING, ERROR }

public data class SkillDiagnostic(
    public val error: SkillError,
    public val severity: SkillDiagnosticSeverity = SkillDiagnosticSeverity.WARNING,
)
