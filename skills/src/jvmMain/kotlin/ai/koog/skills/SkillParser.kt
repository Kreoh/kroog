package ai.koog.skills

import ai.koog.skills.discovery.SkillDocumentParser

/** Strict JVM parser for an Agent Skills `SKILL.md` document. */
public object SkillParser {
    /** Parses a loadable skill using the canonical strict document parser. */
    @JvmStatic
    public fun parse(
        document: String,
        expectedName: String? = null,
        policy: SkillLoadPolicy = SkillLoadPolicy(),
    ): Skill = SkillDocumentParser.parse(document, expectedName, policy, "", false).toLegacy()
}
