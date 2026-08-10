package ai.koog.skills

import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.api.lowlevel.Parse
import org.snakeyaml.engine.v2.events.AliasEvent
import org.snakeyaml.engine.v2.events.MappingEndEvent
import org.snakeyaml.engine.v2.events.MappingStartEvent
import org.snakeyaml.engine.v2.events.SequenceEndEvent
import org.snakeyaml.engine.v2.events.SequenceStartEvent
import org.snakeyaml.engine.v2.exceptions.YamlEngineException
import org.snakeyaml.engine.v2.schema.CoreSchema

/** Strict JVM parser for an Agent Skills `SKILL.md` document. */
public object SkillParser {
    private val allowedFields: Set<String> = setOf("name", "description")

    @JvmStatic
    public fun parse(
        document: String,
        expectedName: String? = null,
        policy: SkillLoadPolicy = SkillLoadPolicy(),
    ): Skill {
        val normalised: String = document.replace("\r\n", "\n")
        if ('\r' in normalised) {
            malformed("unsupported line ending", policy)
        }
        val lines: List<String> = normalised.split('\n')
        if (lines.firstOrNull() != "---") {
            malformed("document must start with an exact '---' line", policy)
        }
        val closingIndex: Int = lines.indexOfFirstFrom(1) { it == "---" }
        if (closingIndex < 0) {
            malformed("frontmatter is not closed by an exact '---' line", policy)
        }

        val yaml: String = lines.subList(1, closingIndex).joinToString("\n")
        val instructions: String = lines.subList(closingIndex + 1, lines.size).joinToString("\n").trim()
        val settings: LoadSettings = LoadSettings.builder()
            .setSchema(CoreSchema())
            .setAllowDuplicateKeys(false)
            .setAllowRecursiveKeys(false)
            .setMaxAliasesForCollections(0)
            .setCodePointLimit(policy.limits.maxYamlCodePoints)
            .build()

        val loaded: Any? = try {
            preScan(yaml, settings, policy)
            Load(settings).loadFromString(yaml)
        } catch (error: SkillException) {
            throw error
        } catch (error: RuntimeException) {
            if (error.isCodePointLimitExceeded()) {
                throw SkillException(
                    SkillError.LimitExceeded("YAML code points", policy.limits.maxYamlCodePoints.toLong()),
                    policy.diagnosticPaths,
                )
            }
            throw SkillException(
                SkillError.MalformedFrontmatter(error.message?.lineSequence()?.firstOrNull() ?: "invalid YAML"),
                policy.diagnosticPaths,
            )
        }

        val mapping: Map<*, *> = loaded as? Map<*, *>
            ?: throw SkillException(
                SkillError.MalformedFrontmatter("frontmatter root must be a mapping"),
                policy.diagnosticPaths,
            )
        val keys: Set<String> = mapping.keys.map { key ->
            key as? String ?: throw SkillException(
                SkillError.InvalidField("frontmatter", "all field names must be strings"),
                policy.diagnosticPaths,
            )
        }.toSet()
        val unknown: Set<String> = keys - allowedFields
        if (unknown.isNotEmpty() && policy.unknownField == UnknownFieldPolicy.REJECT) {
            throw SkillException(
                SkillError.InvalidField("frontmatter", "unknown fields: ${unknown.sorted().joinToString() }"),
                policy.diagnosticPaths,
            )
        }
        val name: String = requiredString(mapping, "name", policy).trim()
        val description: String = requiredString(mapping, "description", policy).trim()
        SkillValidation.validate(name, description, instructions, policy.limits, expectedName)
        return Skill(name, description, instructions)
    }

    private fun RuntimeException.isCodePointLimitExceeded(): Boolean {
        if (this !is YamlEngineException) return false
        val detail: String = message ?: return false
        return detail.startsWith("The incoming YAML document exceeds the limit: ") && detail.endsWith(" code points.")
    }

    private fun preScan(yaml: String, settings: LoadSettings, policy: SkillLoadPolicy) {
        var depth: Int = 0
        Parse(settings).parseString(yaml).forEach { event ->
            when (event) {
                is AliasEvent -> throw SkillException(
                    SkillError.MalformedFrontmatter("YAML aliases are not allowed"),
                    policy.diagnosticPaths,
                )
                is MappingStartEvent, is SequenceStartEvent -> {
                    depth++
                    if (depth > policy.limits.maxYamlNestingDepth) {
                        throw SkillException(
                            SkillError.LimitExceeded(
                                "YAML nesting depth",
                                policy.limits.maxYamlNestingDepth.toLong(),
                            ),
                            policy.diagnosticPaths,
                        )
                    }
                }
                is MappingEndEvent, is SequenceEndEvent -> depth--
            }
        }
    }

    private fun requiredString(mapping: Map<*, *>, field: String, policy: SkillLoadPolicy): String {
        if (!mapping.containsKey(field)) {
            throw SkillException(SkillError.InvalidField(field, "is required"), policy.diagnosticPaths)
        }
        return mapping[field] as? String
            ?: throw SkillException(SkillError.InvalidField(field, "must be a string"), policy.diagnosticPaths)
    }

    private fun malformed(reason: String, policy: SkillLoadPolicy): Nothing {
        throw SkillException(SkillError.MalformedFrontmatter(reason), policy.diagnosticPaths)
    }

    private inline fun <T> List<T>.indexOfFirstFrom(startIndex: Int, predicate: (T) -> Boolean): Int {
        for (index in startIndex until size) {
            if (predicate(this[index])) return index
        }
        return -1
    }
}
