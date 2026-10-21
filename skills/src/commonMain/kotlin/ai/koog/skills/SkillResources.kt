package ai.koog.skills

/** Bounds for immutable reference text. Byte budgets include resource names. */
public data class SkillResourceLimits(
    public val maxResources: Int = 64,
    public val maxNameBytes: Long = 1_024,
    public val maxBodyBytes: Long = 262_144,
    public val maxSkillBytes: Long = 1_048_576,
    public val maxTotalBytes: Long = 16_777_216,
) {
    init { require(maxResources > 0 && maxNameBytes > 0 && maxBodyBytes > 0 && maxSkillBytes > 0 && maxTotalBytes > 0) }
}

/** A defensive snapshot of canonical references-relative names and exact text. */
public class SkillResources(files: Map<String, String>, limits: SkillResourceLimits = SkillResourceLimits()) {
    private val contents: Map<String, String>
    internal val byteSize: Long
    /** Resource names in lexical order. */
    public val names: List<String>
    init {
        checkResourceLimit(files.size.toLong(), limits.maxResources.toLong(), "resource count")
        var total = 0L
        files.forEach { (name, body) ->
            val nameBytes = validateResourceName(name, limits)
            val bodyBytes = resourceUtf8Size(body, limits.maxBodyBytes)
            checkResourceLimit(nameBytes, limits.maxSkillBytes - total, "skill resource bytes")
            total += nameBytes
            checkResourceLimit(bodyBytes, limits.maxSkillBytes - total, "skill resource bytes")
            total += bodyBytes
        }
        checkResourceLimit(total, limits.maxTotalBytes, "total resource bytes")
        byteSize = total
        contents = files.toMap()
        val ordered = contents.keys.sorted()
        names = object : AbstractList<String>() {
            override val size: Int get() = ordered.size
            override fun get(index: Int): String = ordered[index]
        }
    }
    /** Returns text only for an exact canonical name present in this snapshot. */
    public fun findExact(name: String): String? = contents[name]
    public companion object {
        /** Shared empty resource snapshot. */
        public val Empty: SkillResources = SkillResources(emptyMap())
    }
}

internal fun checkResourceLimit(value: Long, maximum: Long, label: String) {
    if (value > maximum) throw SkillException(SkillError.LimitExceeded(label, maximum))
}

internal fun validateResourceName(name: String, limits: SkillResourceLimits): Long {
    val size = resourceUtf8Size(name, limits.maxNameBytes)
    if (!name.startsWith("references/") || name.any { it == '\\' || it == ':' || it.code < 32 || it.code == 127 } ||
        name.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
        throw SkillException(SkillError.InvalidField("resource name", "must be a canonical path below references"))
    }
    return size
}

/** Counts without allocating an encoded copy, stopping at the supplied bound. */
internal fun resourceUtf8Size(text: String, maximum: Long): Long {
    var bytes = 0L
    var index = 0
    while (index < text.length) {
        val ch = text[index++].code
        val count = when {
            ch < 0x80 -> 1
            ch < 0x800 -> 2
            ch in 0xD800..0xDBFF -> {
                if (index == text.length || text[index++].code !in 0xDC00..0xDFFF)
                    throw SkillException(SkillError.DecodingFailure("UTF-8"))
                4
            }
            ch in 0xDC00..0xDFFF -> throw SkillException(SkillError.DecodingFailure("UTF-8"))
            else -> 3
        }
        if (count > maximum - bytes) throw SkillException(SkillError.LimitExceeded("resource UTF-8 bytes", maximum))
        bytes += count
    }
    return bytes
}

internal class SkillResourceBudget(private val limits: SkillResourceLimits = SkillResourceLimits()) {
    private var bytes = 0L
    val remainingBytes: Long get() = limits.maxTotalBytes - bytes
    fun checkCandidate(size: Long) {
        checkResourceLimit(size, limits.maxTotalBytes - bytes, "total resource bytes")
    }
    fun add(resources: SkillResources) {
        // Revalidate caller-created resources against the registry's fixed safety ceiling.
        checkResourceLimit(resources.names.size.toLong(), limits.maxResources.toLong(), "resource count")
        var skillBytes = 0L
        resources.names.forEach { name ->
            val nameBytes = validateResourceName(name, limits)
            val bodyBytes = resourceUtf8Size(checkNotNull(resources.findExact(name)), limits.maxBodyBytes)
            checkResourceLimit(nameBytes, limits.maxSkillBytes - skillBytes, "skill resource bytes")
            skillBytes += nameBytes
            checkResourceLimit(bodyBytes, limits.maxSkillBytes - skillBytes, "skill resource bytes")
            skillBytes += bodyBytes
        }
        checkCandidate(skillBytes)
        bytes += skillBytes
    }
}
