package ai.koog.skills.prompt

import ai.koog.skills.model.Skill
import kotlinx.serialization.json.*

/**
 * Supported output formats for a generated skills prompt.
 */
public enum class SkillsPromptFormat {
    /** XML representation with the `available_skills` root element. */
    XML,

    /** JSON representation with the `available_skills` root property. */
    JSON,

    /** YAML representation with the `available_skills` root key. */
    YML,
}

/**
 * Generates a textual prompt that describes available skills in the requested [format].
 *
 * The output always includes each skill's `name` and `description`. Additional properties can be
 * included via dedicated `include*` flags.
 *
 * @param skills Skills to include in the prompt.
 * @param format Output format for the generated prompt.
 * @param includeLocation Whether to include the `location` property.
 * @param includeLicense Whether to include the `license` property when present.
 * @param includeCompatibility Whether to include the `compatibility` property when present.
 * @param includeMetadata Whether to include the `metadata` property when present and non-empty.
 * @param includeAllowedTools Whether to include the `allowed-tools` property when present.
 * @return Generated prompt string in the selected [format].
 * @throws IllegalArgumentException If an included XML value contains a character forbidden by XML 1.0
 * or an unpaired UTF-16 surrogate.
 */
public fun generateSkillsPrompt(
    skills: List<Skill>,
    format: SkillsPromptFormat,
    includeLocation: Boolean = true,
    includeLicense: Boolean = false,
    includeCompatibility: Boolean = false,
    includeMetadata: Boolean = false,
    includeAllowedTools: Boolean = false,
): String {
    val catalogue = skillsCatalogue(skills, includeLocation, includeLicense, includeCompatibility, includeMetadata, includeAllowedTools)
    return when (format) {
        SkillsPromptFormat.XML -> generateXmlSkillsPrompt(catalogue)
        SkillsPromptFormat.JSON -> generateJsonSkillsPrompt(catalogue)
        SkillsPromptFormat.YML -> generateYmlSkillsPrompt(catalogue)
    }
}

private const val JSON_INDENTATION = 4

private fun generateXmlSkillsPrompt(catalogue: JsonArray): String {
    val skillsContent = catalogue.joinToString(separator = "\n") { skill ->
        buildString {
            appendLine("  <skill>")
            skill.jsonObject.forEach { (name, value) ->
                if (value is JsonObject) {
                    appendLine("    <$name>")
                    value.forEach { (key, entry) ->
                        appendLine("      <entry key=\"${escapeXml(key)}\">${escapeXml(entry.jsonPrimitive.content)}</entry>")
                    }
                    appendLine("    </$name>")
                } else appendLine("    <$name>${escapeXml(value.jsonPrimitive.content)}</$name>")
            }
            append("  </skill>")
        }
    }
    return buildString {
        appendLine("<available_skills>")
        if (skillsContent.isNotEmpty()) appendLine(skillsContent)
        append("</available_skills>")
    }
}

private fun generateJsonSkillsPrompt(catalogue: JsonArray): String {
    val skillsJson = catalogue
        .joinToString(separator = ",\n") { value ->
            val fields = value.jsonObject.entries.toList()
            buildString {
                appendLine("  {")
                fields.forEachIndexed { index, (key, field) ->
                    val prefix = if (index > 1) "," else ""
                    val suffix = if (index == 0) "," else ""
                    val encoded = if (field is JsonObject) toJsonObject(field.mapValues { it.value.jsonPrimitive.content }) else field.toString()
                    appendLine("    $prefix${JsonPrimitive(key)}: $encoded$suffix")
                }
                append("  }")
            }
        }

    return buildString {
        appendLine("{")
        appendLine("  \"available_skills\": [")
        if (skillsJson.isNotEmpty()) {
            appendLine(skillsJson)
        }
        appendLine("  ]")
        append("}")
    }
}

private fun generateYmlSkillsPrompt(catalogue: JsonArray): String = buildString {
    appendLine("available_skills:")
    if (catalogue.isEmpty()) {
        append("  []")
        return@buildString
    }
    catalogue.forEach { skill ->
        skill.jsonObject.entries.forEachIndexed { index, (name, value) ->
            val prefix = if (index == 0) "  - " else "    "
            if (value is JsonObject) {
                appendLine("$prefix$name:")
                value.forEach { (key, entry) -> appendLine("      ${escapeYmlKey(key)}: ${escapeYml(entry.jsonPrimitive.content)}") }
            } else appendLine("$prefix$name: ${escapeYml(value.jsonPrimitive.content)}")
        }
    }
}.trimEnd()

private fun toJsonObject(values: Map<String, String>): String {
    val indent = " ".repeat(JSON_INDENTATION)
    val nestedIndent = " ".repeat(JSON_INDENTATION + 2)
    return buildString {
        appendLine("{")
        append(
            values.entries.joinToString(",\n") { (key, value) ->
                "${nestedIndent}\"${escapeJson(key)}\": \"${escapeJson(value)}\""
            }
        )
        appendLine()
        append("$indent}")
    }
}

private fun escapeXml(value: String): String {
    var index = 0
    while (index < value.length) {
        val character = value[index++]
        val valid = when {
            character.isHighSurrogate() -> index < value.length && value[index++].isLowSurrogate()
            else -> character == '\t' || character == '\n' || character == '\r' ||
                character in '\u0020'..'\uD7FF' || character in '\uE000'..'\uFFFD'
        }
        require(valid) { "Skills prompt contains a character forbidden by XML 1.0" }
    }
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
        .replace("\t", "&#x9;")
        .replace("\n", "&#xA;")
        .replace("\r", "&#xD;")
}

private fun escapeJson(value: String): String = JsonPrimitive(value).toString().removeSurrounding("\"")

private fun escapeYml(value: String): String = JsonPrimitive(value).toString()

private fun escapeYmlKey(value: String): String =
    if (value.matches(Regex("[A-Za-z_][A-Za-z0-9_-]*")) &&
        value.lowercase() !in setOf("true", "false", "null", "yes", "no", "on", "off", "y", "n")
    ) value else escapeYml(value)

/** Shared structured projection for upstream prompts and legacy metadata-only catalogues. */
internal fun skillsCatalogue(
    skills: List<Skill>,
    includeLocation: Boolean = false,
    includeLicense: Boolean = false,
    includeCompatibility: Boolean = false,
    includeMetadata: Boolean = false,
    includeAllowedTools: Boolean = false,
): JsonArray = buildJsonArray {
    skills.forEach { skill ->
        add(buildJsonObject {
            put("name", skill.name)
            put("description", skill.description)
            if (includeLocation) put("location", skill.location)
            if (includeLicense) skill.license?.let { put("license", it) }
            if (includeCompatibility) skill.compatibility?.let { put("compatibility", it) }
            if (includeMetadata && !skill.metadata.isNullOrEmpty()) put("metadata", buildJsonObject {
                skill.metadata.forEach { (key, value) -> put(key, value) }
            })
            if (includeAllowedTools) skill.allowedTools?.let { put("allowed-tools", it) }
        })
    }
}
