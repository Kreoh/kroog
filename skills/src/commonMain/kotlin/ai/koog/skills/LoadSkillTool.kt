package ai.koog.skills

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolBase
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/** Arguments accepted by [LoadSkillTool]. */
@Serializable
public data class LoadSkillArgs(
    @property:LLMDescription("The exact, case-sensitive name of the skill to load")
    public val name: String,
)

/** Metadata and complete instructions returned by [LoadSkillTool]. */
@Serializable
public data class LoadSkillResult(
    public val name: String,
    public val description: String,
    public val instructions: String,
)

/** Loads complete instructions from an immutable [SkillRegistry] snapshot. */
public class LoadSkillTool(private val registry: SkillRegistry) : Tool<LoadSkillArgs, LoadSkillResult>(
    argsType = typeToken<LoadSkillArgs>(),
    resultType = typeToken<LoadSkillResult>(),
    name = NAME,
    description = "Load the complete instructions for a skill by its exact name",
) {
    /** Returns the selected skill, or throws a typed unknown-skill error. */
    override suspend fun execute(args: LoadSkillArgs): LoadSkillResult {
        val skill: Skill = registry.findExact(args.name)
            ?: throw SkillException(SkillError.UnknownSkill(args.name))
        return LoadSkillResult(skill.name, skill.description, skill.instructions)
    }

    public companion object {
        /** The fixed public name used by the skill-loading tool. */
        public const val NAME: String = "load_skill"
    }
}

/**
 * Adds this snapshot's skill-loading tool to [target] using explicit collision semantics.
 * Empty snapshots and keep-existing collisions preserve [target]'s identity.
 */
public fun SkillRegistry.mergeToolInto(
    target: ToolRegistry,
    collisionPolicy: ToolCollisionPolicy = ToolCollisionPolicy.FAIL,
): ToolRegistry {
    val existing: ToolBase<*, *>? = target.getToolOrNull(LoadSkillTool.NAME)
    if (isEmpty()) return target

    if (existing != null) {
        when (collisionPolicy) {
            ToolCollisionPolicy.FAIL -> throw SkillException(SkillError.ToolCollision)
            ToolCollisionPolicy.KEEP_EXISTING -> return target
            ToolCollisionPolicy.REPLACE -> Unit
        }
    }

    val skillTool = LoadSkillTool(this)
    return ToolRegistry {
        var replacementEmitted = false
        target.tools.forEach { existingTool ->
            if (existingTool.name == LoadSkillTool.NAME) {
                if (!replacementEmitted) {
                    tool(skillTool)
                    replacementEmitted = true
                }
            } else {
                tool(existingTool)
            }
        }
        if (!replacementEmitted) tool(skillTool)
    }
}
