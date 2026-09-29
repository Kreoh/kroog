package ai.koog.skills.consumer

import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.rag.base.files.FileSystemProvider
import ai.koog.serialization.kotlinx.KotlinxSerializer
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

    val serializer = KotlinxSerializer()
    val args = LoadSkillArgs("example")
    check(tool.decodeArgs(tool.encodeArgs(args, serializer), serializer) == args)
    check(tool.encodeResultToString(tool.execute(args), serializer).contains("Follow the instructions."))
    check(tool.name == LoadSkillTool.NAME)
    check(argsSerializer.descriptor.serialName.endsWith("LoadSkillArgs"))
    check(resultSerializer.descriptor.serialName.endsWith("LoadSkillResult"))
    return registry.mergeToolInto(ToolRegistry.EMPTY)
}

/** Compiles the upstream discovery, model serialiser and all prompt formats. */
public suspend fun consumeUpstreamSkillsApi(): List<ai.koog.skills.model.Skill> {
    val fs: FileSystemProvider.ReadOnly<String> = ai.koog.skills.discovery.SkillFileSystemSnapshot(mapOf(
        "/skills/example/SKILL.md" to "---\nname: example\ndescription: Example skill\n---\nInstructions".encodeToByteArray(),
    ))
    val skills = ai.koog.skills.discovery.discoverSkills(fs, listOf("/skills"))
    ai.koog.skills.prompt.SkillsPromptFormat.entries.forEach {
        check(ai.koog.skills.prompt.generateSkillsPrompt(skills, it).isNotEmpty())
    }
    check(ai.koog.skills.model.Skill.serializer().descriptor.serialName.endsWith("Skill"))
    return skills
}
