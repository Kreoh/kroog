package ai.koog.skills

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Catalogue formats supported by [SkillCatalogueRenderer]. */
public enum class SkillCatalogueFormat {
    /** A compact JSON array of metadata objects. */
    JSON,
}

/** Renders stable, metadata-only skill catalogues. */
public object SkillCatalogueRenderer {
    /**
     * Returns a compact JSON catalogue, or `null` when [registry] is empty.
     *
     * The configured catalogue limit counts Unicode code points in the final encoded text.
     */
    public fun render(
        registry: SkillRegistry,
        format: SkillCatalogueFormat = SkillCatalogueFormat.JSON,
        limits: SkillLimits = SkillLimits(),
    ): String? {
        if (registry.isEmpty()) return null

        val catalogue: String = when (format) {
            SkillCatalogueFormat.JSON -> Json.encodeToString(JsonArray.serializer(), registry.asJsonArray())
        }
        if (catalogue.unicodeCodePointCount() > limits.maxCatalogueCharacters) {
            throw SkillException(SkillError.CatalogueOverflow)
        }
        return catalogue
    }
}

private fun SkillRegistry.asJsonArray(): JsonArray = buildJsonArray {
    metadata.forEach { skill ->
        add(
            buildJsonObject {
                put("name", skill.name)
                put("description", skill.description)
            }
        )
    }
}

private fun String.unicodeCodePointCount(): Int {
    var count = 0
    var index = 0
    while (index < length) {
        val first: Int = this[index].code
        val hasSurrogatePair: Boolean = first in 0xD800..0xDBFF &&
            index + 1 < length &&
            this[index + 1].code in 0xDC00..0xDFFF
        index += if (hasSurrogatePair) 2 else 1
        count++
    }
    return count
}
