# Koog AI Agent Framework

Koog is a Kotlin multiplatform framework for building AI agents with graph-based workflows.
It supports JVM and JS targets and integrates with multiple LLM providers
(OpenAI, Anthropic, Google, OpenRouter, Ollama) and Model Context Protocol (MCP).

## Project Structure

The project follows a modular architecture with a clear separation of concerns:

```
koog/
├── agents/
│   ├── agents-core/           # Core abstractions (AIAgent, AIAgentStrategy, AIAgentEnvironment)
│   ├── agents-tools/          # Tool infrastructure (Tool<TArgs, TResult>, ToolRegistry, AIAgentTool)
│   ├── agents-features-*/     # Feature implementations (memory, tracing, event handling)
│   ├── agents-mcp/           # Model Context Protocol integration
│   └── agents-test/          # Testing utilities and framework
├── prompt-*/                 # LLM interaction layer (executors, models, structured data)
├── embeddings-*/             # Vector embedding support
├── examples/                 # Reference implementations and usage patterns
└── build.gradle.kts          # Root build configuration
```

## Build & Commands

### Kreoh JVM-only scope

Kreoh currently builds, validates, publishes, and consumes Kroog for the JVM only. For Kreoh work:

- Use module-specific JVM tasks and the JVM CI publication tasks.
- Never run the root `build` or `assemble` tasks.
- Never run the aggregate `updateLegacyAbi` or `checkLegacyAbi` tasks. The current Kotlin Gradle plugin exposes no
  JVM-only legacy ABI task for these multiplatform modules. Use a reliable JVM-only ABI tool against JVM compilation
  output and the checked-in `api/jvm` dump, or document that the ABI check could not run.
- Never compile Android, JavaScript, Wasm, Native, or iOS targets.
- Never regenerate or commit Android or KLIB ABI dumps unless the user explicitly requests cross-platform work.

This JVM-only rule overrides broader multiplatform commands and quality gates elsewhere in this repository, including
`TESTING.md`, unless the user explicitly requests cross-platform work.

### JVM development commands

```bash
# Test a specific JVM module
./gradlew :agents:agents-core:jvmTest

# Compile JVM test classes for a specific module
./gradlew :agents:agents-core:jvmTestClasses

# Build the JVM JAR for a specific module
./gradlew :agents:agents-core:jvmJar

# Generate JVM publication metadata for a specific module
./gradlew :agents:agents-core:generatePomFileForJvmPublication

# Run specific test class
./gradlew :agents:agents-core:jvmTest --tests "ai.koog.agents.test.SimpleAgentMockedTest"

# Run specific test method  
./gradlew :agents:agents-core:jvmTest --tests "ai.koog.agents.test.SimpleAgentMockedTest.test AIAgent doesn't call tools by default"
```

Use the JVM publication tasks selected by CI when validating or publishing artefacts. The snapshot workflow uses
`publishJvmPublicationToCentralPortalSnapshotsRepository` and
`publishMavenPublicationToCentralPortalSnapshotsRepository`. Scope publication tasks to the intended modules where
Gradle provides module tasks, and never publish unless the user explicitly requests publication.

### Explicit cross-platform commands

The following upstream commands compile multiplatform targets. Run them only when the user explicitly requests
cross-platform work:

```bash
./gradlew build
./gradlew assemble
./gradlew jvmTest
./gradlew jsTest
./gradlew jvmTestClasses jsTestClasses
./gradlew updateLegacyAbi
./gradlew checkLegacyAbi
```

### Development Environment

- **JDK**: 17+ required for JVM target
- **Build System**: Gradle with version catalogs for dependency management
- **Targets**: JVM, JavaScript (Kotlin Multiplatform), WASM
- **IDE**: IntelliJ IDEA recommended with Kotlin plugin

## Code Style

- Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use four spaces for indentation (consistent across all files)
- Name test functions as `testXxx` (no backticks for readability)
- Use descriptive variable and function names
- Prefer functional programming patterns where appropriate
- Use type-safe builders and DSLs for configuration
- Document public APIs with KDoc comments
- NEVER suppress compiler warnings without a good reason

## Quality Gates
Read and follow the Quality Gates section in /TESTING.md before considering any code change complete.

## Architecture

### Core Framework Components

**AIAgent** — Main orchestrator that executes strategies in coroutine scopes, manages tools via ToolRegistry,
runs features through AIAgentPipeline, and handles LLM communication via PromptExecutor.

**AIAgentStrategy** — Graph-based execution logic that defines workflows as subgraphs with start/finish nodes,
manages tool selection strategy, and handles termination/error reporting.

**ToolRegistry** — Centralized, type-safe tool management using a builder pattern: `ToolRegistry { tool(MyTool()) }`.
Supports registry merging with `+` operator.

**AIAgentFeature** — Extensible capabilities installed into AIAgentPipeline with configuration.
Features have unique storage keys and can intercept agent lifecycle events.

### Module Organization

1. **agents-core**: Core abstractions (`AIAgent`, `AIAgentStrategy`, `AIAgentEnvironment`)
2. **agents-tools**: Tool infrastructure (`Tool<TArgs, TResult>`, `ToolRegistry`, `AIAgentTool`)
3. **agents-features-***: Feature implementations (memory, tracing, event handling)
4. **agents-mcp**: Model Context Protocol integration
5. **prompt-***: LLM interaction layer (executors, models, structured data)
6. **embeddings-***: Vector embedding support
7. **examples**: Reference implementations and usage patterns

### Key Architectural Patterns

- **State Machine Graphs**: Agents execute as node graphs with typed edges
- **Feature Pipeline**: Extensible behavior via installable features with lifecycle hooks
- **Environment Abstraction**: Safe tool execution context preventing direct tool calls
- **Type Safety**: Generics ensure compile-time correctness for tool arguments/results
- **Builder Patterns**: Fluent APIs for configuration throughout the framework

## Testing

The framework provides comprehensive testing utilities in `agents-test` module:

### LLM Response Mocking

```kotlin
val mockLLMApi = getMockExecutor(toolRegistry, eventHandler) {
    mockLLMAnswer("Hello!") onRequestContains "Hello"
    mockLLMToolCall(CreateTool, CreateTool.Args("solve")) onRequestEquals "Solve task"
    mockLLMAnswer("Default response").asDefaultResponse
}
```

### Tool Behavior Mocking

```kotlin
// Simple return value
mockTool(PositiveToneTool) alwaysReturns "The text has a positive tone."

// With additional actions
mockTool(NegativeToneTool) alwaysTells {
    println("Tool called")
    "The text has a negative tone."
}

// Conditional responses
mockTool(SearchTool) returns SearchTool.Result("Found") onArgumentsMatching {
    args.query.contains("important")
}
```

### Graph Structure Testing

```kotlin
AIAgent(...) {
    withTesting()

    testGraph("test") {
        val firstSubgraph = assertSubgraphByName<String, String>("first")
        val secondSubgraph = assertSubgraphByName<String, String>("second")

        assertEdges {
            startNode() alwaysGoesTo firstSubgraph
            firstSubgraph alwaysGoesTo secondSubgraph
        }

        verifySubgraph(firstSubgraph) {
            val askLLM = assertNodeByName<String, Message.Response>("callLLM")
            assertNodes {
                askLLM withInput "Hello" outputs Message.Assistant("Hello!")
            }
        }
    }
}
```

For comprehensive testing examples, see `agents/agents-test/TESTING.md`.

## Security

### API Key Management

- **NEVER** commit API keys or secrets to the repository
- Use environment variables for all sensitive configuration
- Store test API keys in a local environment only
- Required environment variables for integration tests:
    - `ANTHROPIC_API_TEST_KEY`
    - `GEMINI_API_TEST_KEY`
    - `MISTRAL_AI_API_TEST_KEY`
    - `OLLAMA_IMAGE_URL`
    - `OPEN_AI_API_TEST_KEY`
    - `OPEN_ROUTER_API_TEST_KEY`

### Tool Execution Safety

- Tools execute within controlled `AIAgentEnvironment` contexts
- Direct tool calls are prevented outside agent execution
- Use type-safe tool arguments to prevent injection attacks
- Validate all external inputs in tool implementations

### Dependency Security

- Regularly update dependencies using Gradle version catalogs
- Use specific version ranges to avoid supply chain attacks
- Review dependencies for known vulnerabilities
- Follow the principle of the least privilege in tool implementations

## Configuration

### Environment Setup

Set environment variables for integration testing (never commit API keys):

```bash
# Export in your shell or IDE run configuration
export ANTHROPIC_API_TEST_KEY=your_key_here
export DEEPSEEK_API_TEST_KEY=your_key_here
export GEMINI_API_TEST_KEY=your_key_here
export MISTRAL_AI_API_TEST_KEY=your_key_here
export OLLAMA_IMAGE_URL=http://localhost:11434
export OPEN_AI_API_TEST_KEY=your_key_here
export OPEN_ROUTER_API_TEST_KEY=your_key_here

# Or add to ~/.bashrc, ~/.zshrc, or IDE environment variables
```

### Gradle Configuration

- Uses version catalogs (`gradle/libs.versions.toml`) for dependency management
- Multiplatform configuration in `build.gradle.kts`
- Test configuration supports both JVM and JS targets

### Development Environment Requirements

- **JDK**: 17+ (OpenJDK recommended)
- **IDE**: IntelliJ IDEA with Kotlin Multiplatform plugin
- **Optional**: Docker for Ollama local testing

## Development Workflow

### Branch Strategy

- **develop**: All development (features and bug fixes)
- **main**: Released versions only
- Base all PRs against `develop` branch
- Use descriptive branch names: `feature/agent-memory`, `fix/tool-registry-bug`

### Code Quality

- For Kreoh work, run the narrowest module-specific JVM tests, JVM ABI checks, and JVM CI publication tasks that cover
  the change.
- Do not run root builds or compile non-JVM targets unless the user explicitly requests cross-platform work.
- For explicitly requested upstream cross-platform work, run the applicable multiplatform tests and build checks.
- Follow established patterns in existing code
- Add tests for new functionality
- Update documentation for API changes

### Commit Guidelines

- Use conventional commit format: `feat:`, `fix:`, `docs:`, `test:`
- Include issue references where applicable
- Keep commits focused and atomic
