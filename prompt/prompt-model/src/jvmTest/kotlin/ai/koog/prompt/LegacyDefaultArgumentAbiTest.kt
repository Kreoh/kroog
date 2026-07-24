package ai.koog.prompt

import ai.koog.prompt.provider.HostedExecutionConfiguration
import ai.koog.prompt.provider.HostedExecutionFeatureSupport
import ai.koog.prompt.provider.HostedExecutionMode
import ai.koog.prompt.provider.ProviderClientIntegration
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.StreamFrame.GeneratedFileBytes
import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyDefaultArgumentAbiTest {
    @Test
    fun testBaselineKotlinConstructorReferencesDefaultsAndCopiesCompile() {
        val bareHosted = hostedFactory(::HostedExecutionConfiguration)
        val typedHosted:
            (
                HostedExecutionMode,
                String?,
                String?,
                HostedExecutionFeatureSupport,
                Boolean,
                HostedExecutionFeatureSupport,
                HostedExecutionFeatureSupport,
                HostedExecutionFeatureSupport,
                ProviderClientIntegration,
            ) -> HostedExecutionConfiguration = ::HostedExecutionConfiguration
        val bareGenerated = generatedFileFactory(::GeneratedFileBytes)
        val typedGenerated:
            (String, ByteArray, Long, String?, Int?, String?) -> StreamFrame.GeneratedFileBytes =
            ::GeneratedFileBytes

        val hosted = HostedExecutionConfiguration(
            mode = HostedExecutionMode.INLINE_PROVIDER_TOOL,
            providerTool = "code_interpreter",
            files = HostedExecutionFeatureSupport.SUPPORTED,
            callerAddressableContainer = true,
            streaming = HostedExecutionFeatureSupport.SUPPORTED,
            replay = HostedExecutionFeatureSupport.SUPPORTED,
            combinesWithCustomTools = HostedExecutionFeatureSupport.SUPPORTED,
            clientIntegration = ProviderClientIntegration.IMPLEMENTED,
        )
        val generated = StreamFrame.GeneratedFileBytes("file-1", byteArrayOf(1), 0)

        assertEquals(hosted, hosted.copy(providerTool = "code_interpreter"))
        assertEquals(generated, generated.copy(bytes = byteArrayOf(1)))
        assertEquals(
            hosted,
            bareHosted(
                hosted.mode,
                hosted.providerTool,
                hosted.managedService,
                hosted.files,
                hosted.callerAddressableContainer,
                hosted.streaming,
                hosted.replay,
                hosted.combinesWithCustomTools,
                hosted.clientIntegration,
            ),
        )
        assertEquals(
            hosted,
            typedHosted(
                hosted.mode,
                hosted.providerTool,
                hosted.managedService,
                hosted.files,
                hosted.callerAddressableContainer,
                hosted.streaming,
                hosted.replay,
                hosted.combinesWithCustomTools,
                hosted.clientIntegration,
            ),
        )
        assertEquals(generated, bareGenerated("file-1", byteArrayOf(1), 0, null, null, null))
        assertEquals(generated, typedGenerated("file-1", byteArrayOf(1), 0, null, null, null))
    }

    @Test
    fun testHostedExecutionConfigurationRetainsExactBaselineDescriptors() {
        val owner = HostedExecutionConfiguration::class.java
        val legacyValues =
            "Lai/koog/prompt/provider/HostedExecutionMode;" +
                "Ljava/lang/String;" +
                "Ljava/lang/String;" +
                "Lai/koog/prompt/provider/HostedExecutionFeatureSupport;" +
                "Z" +
                "Lai/koog/prompt/provider/HostedExecutionFeatureSupport;" +
                "Lai/koog/prompt/provider/HostedExecutionFeatureSupport;" +
                "Lai/koog/prompt/provider/HostedExecutionFeatureSupport;" +
                "Lai/koog/prompt/provider/ProviderClientIntegration;"
        val ownerDescriptor = owner.descriptor()

        assertEquals(
            setOf(
                "($legacyValues)V",
                "(${legacyValues}ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
            ),
            owner.declaredConstructors.mapTo(mutableSetOf()) { it.descriptor() },
        )
        assertEquals(
            setOf(
                "($legacyValues)$ownerDescriptor",
                "(${ownerDescriptor}${legacyValues}ILjava/lang/Object;)$ownerDescriptor",
            ),
            owner.declaredMethods
                .filter { it.name == "copy" || it.name == "copy\$default" }
                .mapTo(mutableSetOf()) { it.descriptor() },
        )
    }

    @Test
    fun testGeneratedFileBytesRetainsExactBaselineDescriptors() {
        val owner = StreamFrame.GeneratedFileBytes::class.java
        val legacyValues = "Ljava/lang/String;[BJLjava/lang/String;Ljava/lang/Integer;Ljava/lang/String;"
        val ownerDescriptor = owner.descriptor()
        val constructors = owner.declaredConstructors
            .filterNot {
                it.parameterTypes.lastOrNull()?.name ==
                    "kotlinx.serialization.internal.SerializationConstructorMarker"
            }
            .mapTo(mutableSetOf()) { it.descriptor() }

        assertEquals(
            setOf(
                "(Ljava/lang/String;[BJ)V",
                "(Ljava/lang/String;[BJLjava/lang/String;)V",
                "(Ljava/lang/String;[BJLjava/lang/String;Ljava/lang/Integer;)V",
                "($legacyValues)V",
                "(${legacyValues}ILkotlin/jvm/internal/DefaultConstructorMarker;)V",
            ),
            constructors,
        )
        assertEquals(
            setOf(
                "($legacyValues)$ownerDescriptor",
                "(${ownerDescriptor}${legacyValues}ILjava/lang/Object;)$ownerDescriptor",
            ),
            owner.declaredMethods
                .filter { it.name == "copy" || it.name == "copy\$default" }
                .mapTo(mutableSetOf()) { it.descriptor() },
        )
    }
}

private fun hostedFactory(
    factory: (
        HostedExecutionMode,
        String?,
        String?,
        HostedExecutionFeatureSupport,
        Boolean,
        HostedExecutionFeatureSupport,
        HostedExecutionFeatureSupport,
        HostedExecutionFeatureSupport,
        ProviderClientIntegration,
    ) -> HostedExecutionConfiguration,
): (
    HostedExecutionMode,
    String?,
    String?,
    HostedExecutionFeatureSupport,
    Boolean,
    HostedExecutionFeatureSupport,
    HostedExecutionFeatureSupport,
    HostedExecutionFeatureSupport,
    ProviderClientIntegration,
) -> HostedExecutionConfiguration = factory

private fun generatedFileFactory(
    factory: (String, ByteArray, Long, String?, Int?, String?) -> StreamFrame.GeneratedFileBytes,
): (String, ByteArray, Long, String?, Int?, String?) -> StreamFrame.GeneratedFileBytes = factory

private fun Class<*>.descriptor(): String = when {
    this == Void.TYPE -> "V"
    isPrimitive -> when (this) {
        Boolean::class.javaPrimitiveType -> "Z"
        Byte::class.javaPrimitiveType -> "B"
        Char::class.javaPrimitiveType -> "C"
        Double::class.javaPrimitiveType -> "D"
        Float::class.javaPrimitiveType -> "F"
        Int::class.javaPrimitiveType -> "I"
        Long::class.javaPrimitiveType -> "J"
        Short::class.javaPrimitiveType -> "S"
        else -> error("Unsupported primitive type: $this")
    }

    isArray -> name.replace('.', '/')
    else -> "L${name.replace('.', '/')};"
}

private fun java.lang.reflect.Constructor<*>.descriptor(): String =
    parameterTypes.joinToString(prefix = "(", postfix = ")V", separator = "") { it.descriptor() }

private fun java.lang.reflect.Method.descriptor(): String =
    parameterTypes.joinToString(prefix = "(", postfix = ")", separator = "") { it.descriptor() } +
        returnType.descriptor()
