package ai.koog.skills

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
            val selected: MutableMap<String, Skill> = linkedMapOf()
            val diagnostics: MutableList<SkillDiagnostic> = mutableListOf()
            var loadedSkillCount = 0

            sources.forEach { source ->
                val result: SkillSourceResult = source.load()
                diagnostics += result.diagnostics

                loadedSkillCount += result.skills.size
                if (loadedSkillCount > policy.limits.maxSkills) {
                    throw SkillException(
                        SkillError.LimitExceeded("skills", policy.limits.maxSkills.toLong()),
                        policy.diagnosticPaths,
                    )
                }

                result.skills
                    .sortedWith(compareBy<Skill>({ it.name }, { it.description }, { it.instructions }))
                    .forEach { skill ->
                        SkillValidation.validate(skill, policy.limits)
                        val previous: Skill? = selected[skill.name]
                        if (previous == null) {
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
            }

            return SkillRegistry(
                skills = selected.values.sortedBy { it.name },
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
