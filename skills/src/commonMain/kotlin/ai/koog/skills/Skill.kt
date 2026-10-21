package ai.koog.skills

import kotlin.jvm.JvmOverloads

/** Validated instructions and immutable reference text for one skill. */
public data class Skill @JvmOverloads constructor(
    public val name: String,
    public val description: String,
    public val instructions: String,
    public val resources: SkillResources = SkillResources.Empty,
) {
    init {
        SkillValidation.validate(name, description, instructions)
    }

    /** Preserves the former three-field JVM copy signature and this snapshot's resources. */
    public fun copy(name: String, description: String, instructions: String): Skill =
        Skill(name, description, instructions, resources)

    public val metadata: SkillMetadata get() = SkillMetadata(name, description)
}

public data class SkillMetadata(public val name: String, public val description: String) {
    init {
        SkillValidation.validateName(name)
        if (description.isBlank() || description != description.trim()) {
            throw SkillException(SkillError.InvalidField("description", "must be trimmed and non-blank"))
        }
    }
}

internal object SkillValidation {
    private const val MAX_NAME_LENGTH: Int = 64
    private val namePattern: Regex = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

    fun validate(skill: Skill, limits: SkillLimits, expectedName: String? = null) {
        validate(skill.name, skill.description, skill.instructions, limits, expectedName)
    }

    fun validate(
        name: String,
        description: String,
        instructions: String,
        limits: SkillLimits? = null,
        expectedName: String? = null,
        allowEmptyInstructions: Boolean = false,
    ) {
        validateName(name)
        if (description.isBlank() || description != description.trim()) {
            throw SkillException(SkillError.InvalidField("description", "must be trimmed and non-blank"))
        }
        if ((!allowEmptyInstructions && instructions.isBlank()) || instructions != instructions.trim()) {
            throw SkillException(SkillError.InvalidField("instructions", "must be trimmed and non-blank"))
        }
        if (limits != null && description.unicodeCodePointCount() > limits.maxDescriptionCharacters) {
            throw SkillException(SkillError.LimitExceeded("description characters", limits.maxDescriptionCharacters.toLong()))
        }
        if (limits != null && instructions.unicodeCodePointCount() > limits.maxInstructionCharacters) {
            throw SkillException(SkillError.LimitExceeded("instruction characters", limits.maxInstructionCharacters.toLong()))
        }
        if (expectedName != null && name != expectedName) {
            throw SkillException(SkillError.InvalidField("name", "must match its directory name"))
        }
    }

    fun validateName(name: String) {
        if (name.isBlank() || name != name.trim()) {
            throw SkillException(SkillError.InvalidField("name", "must be trimmed and non-blank"))
        }
        if (name.length > MAX_NAME_LENGTH) {
            throw SkillException(SkillError.LimitExceeded("name characters", MAX_NAME_LENGTH.toLong()))
        }
        if (!namePattern.matches(name)) {
            throw SkillException(SkillError.InvalidField("name", "must contain lowercase ASCII letters or digits separated by single hyphens"))
        }
    }
}

private fun String.unicodeCodePointCount(): Int {
    var count = 0
    var index = 0
    while (index < length) {
        val first = this[index].code
        val hasSurrogatePair = first in 0xD800..0xDBFF &&
            index + 1 < length &&
            this[index + 1].code in 0xDC00..0xDFFF
        index += if (hasSurrogatePair) 2 else 1
        count++
    }
    return count
}
