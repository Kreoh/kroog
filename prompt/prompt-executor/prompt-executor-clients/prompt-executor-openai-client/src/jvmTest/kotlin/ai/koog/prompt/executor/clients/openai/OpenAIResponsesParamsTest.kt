package ai.koog.prompt.executor.clients.openai

import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.base.models.ServiceTier
import ai.koog.prompt.executor.clients.openai.models.OpenAIInclude
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.executor.clients.openai.models.ReasoningSummary
import ai.koog.prompt.executor.clients.openai.models.Truncation
import ai.koog.prompt.params.LLMParams
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.equality.shouldBeEqualToComparingFields
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.NullSource
import org.junit.jupiter.params.provider.ValueSource

class OpenAIResponsesParamsTest {

    @ParameterizedTest
    @ValueSource(doubles = [0.1, 1.0])
    fun `OpenAIResponsesParams topP bounds`(value: Double) {
        OpenAIResponsesParams(topP = value).shouldNotBeNull()
    }

    @ParameterizedTest
    @ValueSource(doubles = [-0.1, 1.1])
    fun `OpenAIResponsesParams invalid topP`(value: Double) {
        shouldThrow<IllegalArgumentException> {
            OpenAIResponsesParams(topP = value)
        }.message shouldBe "topP must be in (0.0, 1.0], but was $value"
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(booleans = [false])
    fun `OpenAIResponsesParams topLogprobs requires logprobs=true`(logprobsValue: Boolean?) {
        shouldThrow<IllegalArgumentException> {
            OpenAIResponsesParams(
                logprobs = logprobsValue,
                topLogprobs = 1
            )
        }.message shouldBe "`topLogprobs` requires `logprobs=true`."
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 20])
    fun `OpenAIResponsesParams topLogprobs bounds`(topLogprobs: Int) {
        // With logprobs=true the allowed range is [0, 20]
        OpenAIResponsesParams(logprobs = true, topLogprobs = topLogprobs)
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 21])
    fun `OpenAIResponsesParams invalid topLogprobs values when logprobs=true`(value: Int) {
        shouldThrow<IllegalArgumentException> {
            OpenAIResponsesParams(
                logprobs = true,
                topLogprobs = value
            )
        }.message shouldBe "`topLogprobs` must be in [0, 20], but was $value"
    }

    @Test
    fun `LLMParams to OpenAIResponsesParams conversions preserve base fields`() {
        val base = LLMParams(
            temperature = 0.7,
            maxTokens = 123,
            numberOfChoices = 2,
            speculation = "spec",
            user = "user-id",
        )

        base.toOpenAIResponsesParams().shouldNotBeNull {
            assertSoftly {
                temperature shouldBe base.temperature
                maxTokens shouldBe base.maxTokens
                numberOfChoices shouldBe base.numberOfChoices
                speculation shouldBe base.speculation
                user shouldBe base.user
                additionalProperties shouldBe base.additionalProperties
            }
        }
    }

    @Test
    fun `temperature and topP are mutually exclusive in Responses`() {
        shouldThrow<IllegalArgumentException> {
            OpenAIResponsesParams(
                temperature = 0.5,
                topP = 0.5
            )
        }.message shouldBe "temperature and topP are mutually exclusive"
    }

    @Test
    fun `non-blank identifiers validated`() {
        shouldThrow<IllegalArgumentException> {
            OpenAIResponsesParams(
                promptCacheKey = " "
            )
        }.message shouldBe "promptCacheKey must be non-blank"

        shouldThrow<IllegalArgumentException> {
            OpenAIResponsesParams(
                safetyIdentifier = ""
            )
        }.message shouldBe "safetyIdentifier must be non-blank"

        OpenAIChatParams(promptCacheKey = "key", safetyIdentifier = "sid")
    }

    @Test
    fun `responses include and maxToolCalls validations`() {
        shouldThrow<IllegalArgumentException> {
            OpenAIResponsesParams(
                include = emptyList()
            )
        }.message shouldBe "include must not be empty when provided."

        shouldThrow<IllegalArgumentException> {
            OpenAIResponsesParams(
                maxToolCalls = -1
            )
        }.message shouldBe "maxToolCalls must be >= 0"
    }

    @Test
    fun testCodeInterpreterConfigurationValidatesProviderIdentifiersAndIncompatibleModes() {
        listOf("", " ", " file_123").forEach { invalidId ->
            shouldThrow<IllegalArgumentException> {
                OpenAICodeInterpreterConfig(fileIds = listOf(invalidId))
            }.message shouldBe "Code Interpreter fileIds must contain only non-blank provider file IDs"
        }
        shouldThrow<IllegalArgumentException> {
            OpenAICodeInterpreterConfig(fileIds = listOf("file_123", "file_123"))
        }.message shouldBe "Code Interpreter fileIds must not contain duplicates"
        listOf("", " ", " cntr_123").forEach { invalidId ->
            shouldThrow<IllegalArgumentException> {
                OpenAICodeInterpreterConfig(containerId = invalidId)
            }.message shouldBe "Code Interpreter containerId must be a non-blank provider container ID"
        }
        OpenAICodeInterpreterConfig(
            fileIds = listOf("file_123"),
            containerId = "cntr_123",
        ) shouldBe OpenAICodeInterpreterConfig(listOf("file_123"), "cntr_123")

        OpenAIResponsesParams().codeInterpreter shouldBe null
        OpenAICodeInterpreterConfig() shouldBe OpenAICodeInterpreterConfig(emptyList(), null)
        OpenAICodeInterpreterConfig(fileIds = listOf("file_123", "file_456")).fileIds shouldBe
            listOf("file_123", "file_456")
        OpenAICodeInterpreterConfig(containerId = "cntr_123").containerId shouldBe "cntr_123"
    }

    @Test
    fun testOpenAIResponsesParamsPreservesLegacyJvmConstructors() {
        val descriptors = OpenAIResponsesParams::class.java.declaredConstructors.map { constructor ->
            constructor.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.name }
        }
        val legacyParameters = listOf(
            "java.lang.Double",
            "java.lang.Integer",
            "java.lang.Integer",
            "java.lang.String",
            "ai.koog.prompt.params.LLMParams\$Schema",
            "ai.koog.prompt.params.LLMParams\$ToolChoice",
            "java.lang.String",
            "java.util.Map",
            "java.lang.Boolean",
            "java.util.List",
            "java.lang.Integer",
            "java.lang.Boolean",
            "ai.koog.prompt.executor.clients.openai.models.ReasoningConfig",
            "ai.koog.prompt.executor.clients.openai.models.Truncation",
            "java.lang.String",
            "java.lang.String",
            "ai.koog.prompt.executor.clients.openai.base.models.ServiceTier",
            "java.lang.Boolean",
            "java.lang.Boolean",
            "java.lang.Integer",
            "java.lang.Double",
        )

        descriptors.contains(legacyParameters.joinToString(prefix = "(", postfix = ")")) shouldBe true
        descriptors.contains(
            (legacyParameters + "int" + "kotlin.jvm.internal.DefaultConstructorMarker")
                .joinToString(prefix = "(", postfix = ")")
        ) shouldBe true
    }

    @Test
    fun testOpenAIResponsesParamsPreservesLegacyJvmCopyMethods() {
        val legacyParameters = listOf(
            "java.lang.Double",
            "java.lang.Integer",
            "java.lang.Integer",
            "java.lang.String",
            "ai.koog.prompt.params.LLMParams\$Schema",
            "ai.koog.prompt.params.LLMParams\$ToolChoice",
            "java.lang.String",
            "java.util.Map",
            "java.lang.Boolean",
            "java.util.List",
            "java.lang.Integer",
            "java.lang.Boolean",
            "ai.koog.prompt.executor.clients.openai.models.ReasoningConfig",
            "ai.koog.prompt.executor.clients.openai.models.Truncation",
            "java.lang.String",
            "java.lang.String",
            "ai.koog.prompt.executor.clients.openai.base.models.ServiceTier",
            "java.lang.Boolean",
            "java.lang.Boolean",
            "java.lang.Integer",
            "java.lang.Double",
        )
        val methods = OpenAIResponsesParams::class.java.declaredMethods

        methods.single {
            it.name == "copy" &&
                it.returnType == OpenAIResponsesParams::class.java &&
                it.parameterTypes.map { parameter -> parameter.name } == legacyParameters
        }
        methods.single {
            it.name == "copy\$default" &&
                it.returnType == OpenAIResponsesParams::class.java &&
                it.parameterTypes.map { parameter -> parameter.name } ==
                listOf(OpenAIResponsesParams::class.java.name) + legacyParameters + "int" + "java.lang.Object"
        }
        methods.none {
            it.name == "copy" &&
                it.parameterTypes.lastOrNull() == OpenAICodeInterpreterConfig::class.java
        } shouldBe true
    }

    @Test
    fun `Should make a full copy`() {
        val source = OpenAIResponsesParams(
            temperature = 0.75,
            maxTokens = 123424,
            numberOfChoices = 10,
            speculation = "spec",
            schema = LLMParams.Schema.JSON.Basic("test", JsonObject(mapOf())),
            toolChoice = LLMParams.ToolChoice.Required,
            user = "user-id",
            additionalProperties = mapOf("foo" to JsonPrimitive("bar")),
            background = true,
            include = listOf(OpenAIInclude.REASONING_ENCRYPTED_CONTENT, OpenAIInclude.OUTPUT_TEXT_LOGPROBS),
            maxToolCalls = 10,
            parallelToolCalls = true,
            reasoning = ReasoningConfig(effort = ReasoningEffort.HIGH, summary = ReasoningSummary.DETAILED),
            truncation = Truncation.DISABLED,
            promptCacheKey = "abcdefghijklmnop",
            safetyIdentifier = "key",
            serviceTier = ServiceTier.FLEX,
            store = true,
            logprobs = true,
            topLogprobs = 14,
            codeInterpreter = OpenAICodeInterpreterConfig(fileIds = listOf("file_123")),
        )

        val target = source.copy()
        target shouldBeEqualToComparingFields source

        val replacement = OpenAICodeInterpreterConfig(containerId = "cntr_123")
        source.withCodeInterpreter(replacement).codeInterpreter shouldBe replacement
        source.withCodeInterpreter(null).codeInterpreter shouldBe null
        source.copy(maxTokens = 42).codeInterpreter shouldBe source.codeInterpreter
    }
}
