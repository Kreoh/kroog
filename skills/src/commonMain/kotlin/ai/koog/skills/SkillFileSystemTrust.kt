package ai.koog.skills

/** The caller's explicit host-filesystem trust declaration. Immutable memory sources do not need it. */
public enum class SkillFileSystemTrust {
    /** Host discovery must fail before opening roots, including an empty root selection. */
    UNATTESTED,

    /**
     * The caller attests that roots, ancestor resolution and all contained entries are operator-controlled,
     * trusted and immutable throughout discovery. No untrusted or concurrent writer may change them.
     * The caller also selects storage with suitable ordinary I/O latency for its application.
     *
     * This is a deployment precondition, not a runtime verification. A false declaration permits hostile
     * replacement races, including a blocking FIFO open. Neither cancellation nor file-size bounds
     * establish a wall-clock I/O deadline on arbitrary or failing filesystems.
     */
    TRUSTED_IMMUTABLE,
}

internal fun requireTrustedFileSystem(trust: SkillFileSystemTrust, policy: SkillLoadPolicy) {
    if (trust != SkillFileSystemTrust.TRUSTED_IMMUTABLE) {
        throw SkillException(SkillError.UntrustedFileSystem, policy.diagnosticPaths)
    }
}
