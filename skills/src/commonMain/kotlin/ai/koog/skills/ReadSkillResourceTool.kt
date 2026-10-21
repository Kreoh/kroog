package ai.koog.skills

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.serialization.typeToken
import kotlinx.serialization.Serializable

/** Exact identifiers accepted by [ReadSkillResourceTool]. */
@Serializable
public data class ReadSkillResourceArgs(
    @property:LLMDescription("Exact skill name") public val name: String,
    @property:LLMDescription("Exact resource name returned by load_skill") public val resource: String,
)

/** Exact reference text from an immutable registry snapshot. */
@Serializable
public data class ReadSkillResourceResult(public val name: String, public val resource: String, public val text: String)

/** Reads captured reference text without accessing the filesystem. */
public class ReadSkillResourceTool(private val registry: SkillRegistry) : Tool<ReadSkillResourceArgs, ReadSkillResourceResult>(
    argsType = typeToken<ReadSkillResourceArgs>(),
    resultType = typeToken<ReadSkillResourceResult>(),
    name = NAME,
    description = "Read reference text by its exact skill and resource names returned by load_skill",
) {
    override suspend fun execute(args: ReadSkillResourceArgs): ReadSkillResourceResult {
        val skill = registry.findExact(args.name) ?: throw SkillException(SkillError.UnknownSkill(args.name))
        val text = skill.resources.findExact(args.resource) ?: throw SkillException(SkillError.UnknownResource)
        return ReadSkillResourceResult(skill.name, args.resource, text)
    }
    public companion object {
        /** Reserved tool name. */
        public const val NAME: String = "read_skill_resource"
    }
}
