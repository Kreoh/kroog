package ai.koog.skills.consumer

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.skills.InMemorySkillSource
import ai.koog.skills.LoadSkillArgs
import ai.koog.skills.LoadSkillResult
import ai.koog.skills.LoadSkillTool
import ai.koog.skills.Skill
import ai.koog.skills.SkillRegistry
import ai.koog.skills.mergeToolInto

/** Compiles representative public API use with only skills-jvm on the consumer classpath. */
public suspend fun consumeSkillsApi(): ToolRegistry {
    val registry = SkillRegistry.build(
        listOf(InMemorySkillSource(listOf(Skill("example", "Example skill", "Follow the instructions."))))
    )
    val tool = LoadSkillTool(registry)
    val argsSerializer = LoadSkillArgs.serializer()
    val resultSerializer = LoadSkillResult.serializer()

    check(tool.name == LoadSkillTool.NAME)
    check(argsSerializer.descriptor.serialName.endsWith("LoadSkillArgs"))
    check(resultSerializer.descriptor.serialName.endsWith("LoadSkillResult"))
    return registry.mergeToolInto(ToolRegistry.EMPTY)
}
