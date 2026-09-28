package ai.koog.skills.discovery

import ai.koog.skills.*

/** Collects complete records and accounts for every candidate before duplicate selection. */
internal class SkillSnapshot(private val policy: SkillLoadPolicy) {
    private val selected = linkedMapOf<String, ParsedSkillDocument>()
    private val collectedDiagnostics = mutableListOf<SkillDiagnostic>()
    private var count = 0L

    val records: List<ParsedSkillDocument> get() = selected.values.toList()
    val diagnostics: List<SkillDiagnostic> get() = collectedDiagnostics.toList()

    fun add(record: ParsedSkillDocument, source: SkillSourceReference? = null) {
        count++
        if (count > policy.limits.maxSkills) throw SkillException(
            SkillError.LimitExceeded("skills", policy.limits.maxSkills.toLong(), source), policy.diagnosticPaths,
        )
        val name = record.skill.name
        if (name in selected) {
            val error = SkillError.DuplicateName(name, source)
            when (policy.duplicateSkill) {
                DuplicateSkillPolicy.FAIL -> throw SkillException(error, policy.diagnosticPaths)
                DuplicateSkillPolicy.KEEP_FIRST -> { collectedDiagnostics += SkillDiagnostic(error); return }
                DuplicateSkillPolicy.KEEP_LAST -> collectedDiagnostics += SkillDiagnostic(error)
            }
        }
        selected[name] = record
    }
}

internal fun Skill.asRecord(): ParsedSkillDocument =
    ParsedSkillDocument(ai.koog.skills.model.Skill(name, description, ""), instructions, this)
