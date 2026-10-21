package ai.koog.skills

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmOverloads

/** Arguments accepted by [LoadSkillTool]. */
@Serializable
public data class LoadSkillArgs(
    @property:LLMDescription("The exact, case-sensitive name of the skill to load")
    public val name: String,
)

/** Metadata, complete instructions and reference names returned by [LoadSkillTool]. */
@Serializable
public data class LoadSkillResult @JvmOverloads constructor(
    public val name: String,
    public val description: String,
    public val instructions: String,
    public val resourceNames: List<String> = emptyList(),
) {
    /** Preserves the former three-field JVM copy signature and captured resource names. */
    public fun copy(name: String, description: String, instructions: String): LoadSkillResult =
        LoadSkillResult(name, description, instructions, resourceNames)
}

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
        return LoadSkillResult(skill.name, skill.description, skill.instructions, skill.resources.names)
    }

    public companion object {
        /** The fixed public name used by the skill-loading tool. */
        public const val NAME: String = "load_skill"
    }
}

/**
 * Adds this snapshot's instruction and resource tools atomically to [target].
 * Empty snapshots and keep-existing collisions preserve [target]'s identity.
 */
public fun SkillRegistry.mergeToolInto(
    target: ToolRegistry,
    collisionPolicy: ToolCollisionPolicy = ToolCollisionPolicy.FAIL,
): ToolRegistry {
    if (isEmpty()) return target
    val names = setOf(LoadSkillTool.NAME, ReadSkillResourceTool.NAME)
    val collision = names.firstOrNull { target.getToolOrNull(it) != null }
    if (collision != null) {
        when (collisionPolicy) {
            ToolCollisionPolicy.FAIL -> throw SkillException(
                if (collision == LoadSkillTool.NAME) SkillError.ToolCollision else SkillError.ResourceToolCollision,
            )
            ToolCollisionPolicy.KEEP_EXISTING -> return target
            ToolCollisionPolicy.REPLACE -> Unit
        }
    }
    val replacements = listOf(LoadSkillTool(this), ReadSkillResourceTool(this))
    return ToolRegistry {
        val emitted = mutableSetOf<String>()
        target.tools.forEach { existing ->
            val replacement = replacements.firstOrNull { it.name == existing.name }
            if (replacement == null) tool(existing)
            else if (emitted.add(existing.name)) tool(replacement)
        }
        replacements.forEach { if (emitted.add(it.name)) tool(it) }
    }
}
