package ai.koog.prompt.executor.clients.bedrock

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesCapability
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesDialect
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow

/**
 * Bedrock Mantle client for OpenAI-compatible Chat Completions and Responses endpoints.
 * Supports Gemma 4 and GPT-6 Astra using an Amazon Bedrock API key.
 * Configure Responses behaviour with OpenAI parameters, including explicit stateless mode where needed.
 * AWS credential signing and the Runtime Converse API belong to [BedrockLLMClient].
 */
public class BedrockMantleLLMClient : OpenAILLMClient {
    private val region: BedrockRegions

    /** Creates a Mantle client using a Bedrock API key and the selected AWS region. */
    public constructor(
        apiKey: String,
        httpClientFactory: KoogHttpClient.Factory,
        region: BedrockRegions = BedrockRegions.US_WEST_2,
    ) : super(apiKey = apiKey, settings = mantleSettings(region), httpClientFactory = httpClientFactory) {
        this.region = region
    }

    /** Creates a Mantle client with an authenticated HTTP client configured for the selected region. */
    public constructor(
        httpClient: KoogHttpClient,
        region: BedrockRegions = BedrockRegions.US_WEST_2,
    ) : super(settings = mantleSettings(region), httpClient = httpClient) {
        this.region = region
    }

    override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
        super.execute(prompt, mantleModel(model), tools)

    override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> =
        super.executeStreaming(prompt, mantleModel(model), tools)

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<Message.Assistant> = super.executeMultipleChoices(prompt, mantleModel(model), tools)

    private fun mantleModel(model: LLModel): LLModel {
        val baseId = model.id.removePrefix("us.").removePrefix("global.")
        if (baseId != "openai.gpt-6-astra") return model
        require(region == BedrockRegions.US_WEST_2) {
            "GPT-6 Astra on Mantle requires the us-west-2 region."
        }
        return OpenAIModels.Chat.GPT6Astra.copy(provider = LLMProvider.Bedrock, id = baseId)
    }

    override fun llmProvider(): LLMProvider = LLMProvider.Bedrock
}

private fun mantleSettings(region: BedrockRegions): OpenAIClientSettings = OpenAIClientSettings(
    baseUrl = "https://bedrock-mantle.${region.regionCode}.api.aws",
    chatCompletionsPath = "openai/v1/chat/completions",
    responsesAPIPath = "openai/v1/responses",
    modelsPath = "openai/v1/models",
    responsesDialect = OpenAIResponsesDialect.Compatible,
    declaredResponsesCapability = OpenAIResponsesCapability.Supported,
)
