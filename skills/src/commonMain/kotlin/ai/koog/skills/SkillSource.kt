package ai.koog.skills

import ai.koog.skills.discovery.SkillSnapshot
import ai.koog.skills.discovery.asRecord

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
        val selected = SkillSnapshot(policy)
        snapshot.sortedWith(compareBy<Skill>({ it.name }, { it.description }, { it.instructions })).forEach { skill ->
            SkillValidation.validate(skill, policy.limits)
            selected.add(skill.asRecord())
        }
        return SkillSourceResult(selected.records.map { it.toLegacy() }.sortedBy { it.name }, selected.diagnostics)
    }
}

private class ImmutableSnapshotList<T>(elements: Collection<T>) : AbstractList<T>() {
    private val elements: List<T> = elements.toList()

    override val size: Int get() = elements.size
    override fun get(index: Int): T = elements[index]
}
