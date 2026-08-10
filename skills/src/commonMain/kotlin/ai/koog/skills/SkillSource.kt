package ai.koog.skills

public fun interface SkillSource {
    public suspend fun load(): SkillSourceResult
}

public class SkillSourceResult(skills: Collection<Skill>, diagnostics: Collection<SkillDiagnostic> = emptyList()) {
    public val skills: List<Skill> = ImmutableSnapshotList(skills)
    public val diagnostics: List<SkillDiagnostic> = ImmutableSnapshotList(diagnostics)
}

public class InMemorySkillSource(
    skills: Collection<Skill>,
    private val policy: SkillLoadPolicy = SkillLoadPolicy(),
) : SkillSource {
    private val snapshot: List<Skill> = skills.toList()

    override suspend fun load(): SkillSourceResult {
        if (snapshot.size > policy.limits.maxSkills) {
            throw SkillException(SkillError.LimitExceeded("skills", policy.limits.maxSkills.toLong()), policy.diagnosticPaths)
        }
        val selected: MutableMap<String, Skill> = linkedMapOf()
        val diagnostics: MutableList<SkillDiagnostic> = mutableListOf()
        snapshot.sortedWith(compareBy<Skill>({ it.name }, { it.description }, { it.instructions })).forEach { skill ->
            SkillValidation.validate(skill, policy.limits)
            if (skill.name !in selected) {
                selected[skill.name] = skill
            } else {
                val error: SkillError = SkillError.DuplicateName(skill.name)
                when (policy.duplicateSkill) {
                    DuplicateSkillPolicy.FAIL -> throw SkillException(error, policy.diagnosticPaths)
                    DuplicateSkillPolicy.KEEP_FIRST -> diagnostics += SkillDiagnostic(error)
                    DuplicateSkillPolicy.KEEP_LAST -> {
                        selected[skill.name] = skill
                        diagnostics += SkillDiagnostic(error)
                    }
                }
            }
        }
        return SkillSourceResult(selected.values.sortedBy { it.name }, diagnostics)
    }
}

private class ImmutableSnapshotList<T>(elements: Collection<T>) : AbstractList<T>() {
    private val elements: List<T> = elements.toList()

    override val size: Int get() = elements.size
    override fun get(index: Int): T = elements[index]
}
