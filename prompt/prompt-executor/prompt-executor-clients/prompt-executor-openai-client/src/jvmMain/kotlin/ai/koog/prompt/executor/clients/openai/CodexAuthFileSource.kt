package ai.koog.prompt.executor.clients.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Base64

/**
 * Reloads a specific Codex CLI auth.json on every request. No home-directory or environment fallback is used.
 * Configure file-based credential storage in the external CLI; keychain credentials are not supported.
 * Tokens expiring within five minutes fail closed so the CLI can refresh them before a sustained run.
 */
public class CodexAuthFileSource(private val path: Path) : CodexCredentialSource {
    override suspend fun load(): CodexCredentials = withContext(Dispatchers.IO) {
        if (!Files.isRegularFile(path)) fail("file is missing")
        val root = try {
            if (Files.size(path) > 1_048_576) fail("file is too large")
            Json.parseToJsonElement(Files.readString(path)).jsonObject
        } catch (_: Exception) {
            fail("file cannot be read or contains malformed JSON")
        }
        try {
            val tokens = root["tokens"]?.jsonObject ?: fail("file does not contain Codex account tokens")
            val token = tokens["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: fail("access token is missing")
            val claims = jwtClaims(token)
            val expiry = claims["exp"]?.jsonPrimitive?.longOrNull ?: fail("token expiry is missing or malformed")
            if (expiry <= Instant.now().epochSecond + 300) fail("access token is expired or expires soon")
            val account = tokens["account_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: (claims["https://api.openai.com/auth"] as? JsonObject)
                    ?.get("chatgpt_account_id")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: fail("account identity is missing")
            CodexCredentials(token, account)
        } catch (failure: CodexAuthenticationException) {
            throw failure
        } catch (_: Exception) {
            fail("file contains malformed account credentials")
        }
    }

    private fun jwtClaims(token: String): JsonObject = try {
        val parts = token.split('.')
        if (parts.size != 3) fail("access token is malformed")
        Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)).jsonObject
    } catch (_: Exception) {
        fail("access token is malformed")
    }

    private fun fail(reason: String): Nothing = throw CodexAuthenticationException(
        "Configured Codex auth $reason. Refresh this account with the external Codex CLI using file-based credential storage."
    )

    override fun toString(): String = "CodexAuthFileSource(<explicit path>)"
}
