package ai.koog.skills.discovery

import ai.koog.skills.SkillLoadPolicy
import ai.koog.skills.model.Skill

/** Metadata and body captured by one read and selected together. */
internal data class ParsedSkillDocument(val skill: Skill, val instructions: String, private val legacy: ai.koog.skills.Skill? = null) {
    fun toLegacy(): ai.koog.skills.Skill = legacy ?: ai.koog.skills.Skill(skill.name, skill.description, instructions)
}

internal expect object SkillDocumentParser {
    fun parse(document: String, expectedName: String?, policy: SkillLoadPolicy, location: String, upstream: Boolean): ParsedSkillDocument
}
