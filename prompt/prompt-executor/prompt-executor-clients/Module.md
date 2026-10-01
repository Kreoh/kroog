# Module prompt:prompt-executor:prompt-executor-clients

A collection of client implementations for executing prompts using various LLM providers and retry logic features.

### Overview

This module provides client implementations for different LLM providers, allowing you to execute prompts using various
models with support for multimodal content including images, audio, video, and documents. The module includes 
**production-ready retry logic** through the `RetryingLLMClient` decorator, which adds automatic error handling and
resilience to any client implementation.

The module consists of:

**Core Functionality:**
- **LLMClient interface**: Base interface for all LLM client implementations
- **RetryingLLMClient**: Decorator that adds retry logic with configurable policies
- **RetryConfig**: Flexible retry configuration with predefined settings for different use cases

**Provider-Specific Sub-modules:**
1. **prompt-executor-anthropic-client**: Client implementation for Anthropic's Claude models with image and document support
2. **prompt-executor-openai-client**: Client implementation for OpenAI's GPT models with image and audio capabilities
3. **prompt-executor-google-client**: Client implementation for Google Gemini models with comprehensive multimodal support
4. **prompt-executor-mistralai-client**: Client implementation for Mistral AI models with vision, embeddings, and moderation support
5. **prompt-executor-openrouter-client**: Client implementation for OpenRouter's API with image, audio, and document support
6. **prompt-executor-bedrock-client**: Client implementation for AWS Bedrock with support for multiple model providers (JVM only)
7. **prompt-executor-ollama-client**: Client implementation for local Ollama models

Each client handles authentication, request formatting, response parsing, and media content encoding specific to its
respective API requirements.

### Adding or changing model support

Update the provider definitions and registry together with the central `ModelCatalogue` profiles, aliases and
provider API compatibility. Applications use that catalogue to discover capabilities and limits.
A new deployment of an existing semantic model requires a route review rather than a duplicate semantic profile.

Complete the model support gate in [TESTING.md](../../../TESTING.md): add catalogue lookup and profile regression
tests, update the normalised fixture and expected IDs, and run `:prompt:prompt-model:jvmTest` alongside the
affected provider JVM tests. Provider unit tests and live requests do not establish catalogue completeness.
Resolve catalogue contract gaps before release, then verify discovery through staged JVM artefacts as required by
[PUBLISHING.md](../../../PUBLISHING.md).

### Using in your project

Add the confirmed published JVM dependency for the client you want to use.
These examples use stable or beta revision 15, as appropriate. The current
source prepares unpublished `1.3.0-kroog.1` and `1.3.0-beta-kroog.1`.
Use the POM-based Maven Central repository settings in [PUBLISHING.md](../../../PUBLISHING.md).

```kotlin
dependencies { 
   // For Anthropic 
   implementation("com.kreoh.kroog:prompt-executor-anthropic-client-jvm:1.1.1-kroog.15")

   // For Bedrock
   implementation("com.kreoh.kroog:prompt-executor-bedrock-client-jvm:1.1.1-kroog.15")

   // For DeepSeek
   implementation("com.kreoh.kroog:prompt-executor-deepseek-client-jvm:1.1.1-beta-kroog.15")

   // For Google Gemini
   implementation("com.kreoh.kroog:prompt-executor-google-client-jvm:1.1.1-beta-kroog.15")

   // For MistralAI
   implementation("com.kreoh.kroog:prompt-executor-mistralai-client-jvm:1.1.1-beta-kroog.15")

   // For Ollama 
   implementation("com.kreoh.kroog:prompt-executor-ollama-client-jvm:1.1.1-kroog.15")

   // For OpenAI
   implementation("com.kreoh.kroog:prompt-executor-openai-client-jvm:1.1.1-kroog.15")

   // For OpenRouter 
   implementation("com.kreoh.kroog:prompt-executor-openrouter-client-jvm:1.1.1-kroog.15")
}
```

### Using in tests

For testing, you can use mock implementations provided by each client module:

```kotlin
// Mock Anthropic client
val mockAnthropicClient = MockAnthropicClient(
    responses = listOf("Mocked response 1", "Mocked response 2")
)

// Mock OpenAI client
val mockOpenAIClient = MockOpenAIClient(
    responses = listOf("Mocked response 1", "Mocked response 2")
)

// Mock OpenRouter client
val mockOpenRouterClient = MockOpenRouterClient(
    responses = listOf("Mocked response 1", "Mocked response 2")
)
```

### Example of usage

```kotlin
// Choose the client implementation based on your needs
val client = when (providerType) {
    ProviderType.ANTHROPIC -> AnthropicLLMClient(
        apiKey = System.getenv("ANTHROPIC_API_KEY"),
    )
    ProviderType.OPENAI -> OpenAILLMClient(
        apiKey = System.getenv("OPENAI_API_KEY"),
    )
    ProviderType.GOOGLE -> GoogleLLMClient(
        apiKey = System.getenv("GEMINI_API_KEY"),
    )
    ProviderType.MISTRALAI -> MistralAILLMClient(
        apiKey = System.getenv("MISTRALAI_API_KEY"),
    )
    ProviderType.OPENROUTER -> OpenRouterLLMClient(
        apiKey = System.getenv("OPENROUTER_API_KEY"),
    )
}

val response = client.execute(
    prompt = prompt {
        system("You are helpful assistant")
        user("What time is it now?")
    },
    model = chosenModel
)

println(response)
```

### Retry Logic

Wrap any client with `RetryingLLMClient` to add automatic retry capabilities:

```kotlin
val baseClient = OpenAILLMClient(apiKey = System.getenv("OPENAI_API_KEY"))
val resilientClient = RetryingLLMClient(
    delegate = baseClient,
    config = RetryConfig.PRODUCTION  // Or CONSERVATIVE, AGGRESSIVE, DISABLED
)

val response = resilientClient.execute(prompt, model)

resilientClient.executeStreaming(prompt, model).collect { chunk ->
    print(chunk)
}
```

**Retry Configurations:**
- `RetryConfig.PRODUCTION` - Recommended for production (3 attempts, balanced delays)
- `RetryConfig.CONSERVATIVE` - Fewer retries, longer delays (3 attempts, 2s initial delay)
- `RetryConfig.AGGRESSIVE` - More retries, shorter delays (5 attempts, 500ms initial delay)
- `RetryConfig.DISABLED` - No retries (1 attempt)

### Multimodal Content Support

All clients now support multimodal content through the unified MediaContent API:

```kotlin
// Image analysis example
val response = client.execute(
    prompt = prompt {
        user {
            text("What do you see in this image?")
            attachments {
                image("/path/to/image.jpg")
            }
        }
    },
    model = visionModel
)

// Document processing example  
val response = client.execute(
    prompt = prompt {
        user {
            text("Summarize this document")
            attachments {
                document("/path/to/document.pdf")
            }
        }
    },
    model = documentModel
)

// Audio transcription (supported by Google and OpenAI)
val audioData = File("/path/to/audio.mp3").readBytes()
val response = client.execute(
    prompt = prompt {
        user {
            text("Transcribe this audio")
            attachments {
                audio(audioData, "mp3")
            }
        }
    },
    model = audioModel
)

// Mixed media content
val response = client.execute(
    prompt = prompt {
        user {
            text("Compare the image with the document content:")
            attachments {
               image("/path/to/screenshot.png")
               document("/path/to/report.pdf")
            }
            text("What are the key differences?")
        }
    },
    model = multimodalModel
)
```

### Supported Media Types by Provider

| Provider         | Images | Audio | Video | Documents |
|------------------|--------|-------|-------|-----------|
| Anthropic Claude | ✅      | ❌     | ❌     | ✅         |
| OpenAI GPT       | ✅      | ✅     | ❌     | ❌         |
| Google Gemini    | ✅      | ✅     | ✅     | ✅         |
| Mistral AI       | ✅      | ❌     | ❌     | ✅         |
| OpenRouter       | ✅      | ✅     | ❌     | ✅         |
| Ollama           | ✅      | ❌     | ❌     | ❌         |
