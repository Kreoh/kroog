package ai.koog.skills

import ai.koog.skills.discovery.SkillSnapshot
import ai.koog.skills.discovery.asRecord

/**
 * An immutable, deterministically ordered snapshot of validated skills.
 *
 * A registry never retains its sources. Sources are read sequentially during [build] or [reload],
 * and all later lookups use the snapshot's exact-name map.
 */
public class SkillRegistry private constructor(
    skills: Collection<Skill>,
    diagnostics: Collection<SkillDiagnostic>,
    private val policy: SkillLoadPolicy,
) : Iterable<Skill> {
    private val skills: List<Skill> = RegistrySnapshotList(skills)
    private val skillsByName: Map<String, Skill> = skills.associateBy { it.name }

    /** Diagnostics collected while building this snapshot, in stable source order. */
    public val diagnostics: List<SkillDiagnostic> = RegistrySnapshotList(diagnostics)

    /** Metadata for every skill in lexical name order. */
    public val metadata: List<SkillMetadata> = RegistrySnapshotList(skills.map(Skill::metadata))

    /** The lifecycle mode captured when this snapshot was built. */
    public val lifecycle: SkillLifecyclePolicy get() = policy.lifecycle

    /** Returns the skill whose name exactly and case-sensitively equals [name]. */
    public fun findExact(name: String): Skill? = skillsByName[name]

    /** Returns whether this snapshot contains no skills. */
    public fun isEmpty(): Boolean = skills.isEmpty()

    /** Iterates over skills in lexical name order. */
    override fun iterator(): Iterator<Skill> = skills.iterator()

    /**
     * Explicitly reads [sources] and returns a distinct new immutable snapshot.
     * This registry remains unchanged even when loading the replacement fails.
     */
    public suspend fun reload(
        sources: List<SkillSource>,
        policy: SkillLoadPolicy = this.policy,
    ): SkillRegistry = build(sources, policy)

    public companion object {
        /**
         * Reads [sources] sequentially in caller order and constructs an immutable snapshot.
         * Skills within each source are ordered deterministically before duplicate handling.
         */
        public suspend fun build(
            sources: List<SkillSource>,
            policy: SkillLoadPolicy = SkillLoadPolicy(),
        ): SkillRegistry {
            val snapshot = SkillSnapshot(policy)
            val diagnostics = mutableListOf<SkillDiagnostic>()
            var loadedSkillCount = 0L
            var diagnosticCount = 0
            sources.forEach { source ->
                val result = source.load()
                diagnostics += result.diagnostics
                loadedSkillCount += result.skills.size
                if (loadedSkillCount > policy.limits.maxSkills) throw SkillException(
                    SkillError.LimitExceeded("skills", policy.limits.maxSkills.toLong()), policy.diagnosticPaths,
                )
                result.skills.sortedWith(compareBy<Skill>({ it.name }, { it.description }, { it.instructions }))
                    .forEach { skill ->
                        SkillValidation.validate(skill, policy.limits)
                        snapshot.add(skill.asRecord())
                    }
                val currentDiagnostics = snapshot.diagnostics
                diagnostics += currentDiagnostics.drop(diagnosticCount)
                diagnosticCount = currentDiagnostics.size
            }

            return SkillRegistry(
                skills = snapshot.records.map { it.toLegacy() }.sortedBy { it.name },
                diagnostics = diagnostics,
                policy = policy,
            )
        }
    }
}

private class RegistrySnapshotList<T>(elements: Collection<T>) : AbstractList<T>() {
    private val elements: List<T> = elements.toList()

    override val size: Int get() = elements.size
    override fun get(index: Int): T = elements[index]
}
