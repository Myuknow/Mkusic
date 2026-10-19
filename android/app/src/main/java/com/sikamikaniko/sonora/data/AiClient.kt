package com.sikamikaniko.sonora.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Minimal client for a self-hosted LLM server (fully user-configured — no
 * endpoint is hardcoded). Speaks either Ollama's native API or the
 * OpenAI-compatible API served by vLLM. Supports listing models, one-shot chat
 * (optionally JSON-forced) and token streaming for a live, fast-feeling response.
 */
object AiClient {

    enum class Provider(val label: String, val urlHint: String) {
        OLLAMA("Ollama", "http://your-server:11434"),
        VLLM("vLLM", "http://your-server:8000");

        companion object {
            fun of(name: String?) = values().firstOrNull { it.name == name } ?: OLLAMA
        }
    }

    /** Which API to speak, and the optional bearer token (vLLM `--api-key`). Set from Settings. */
    @Volatile var provider: Provider = Provider.OLLAMA
    @Volatile var apiKey: String = ""

    /** Why the most recent call failed, in plain words — shown next to "couldn't reach your AI". */
    @Volatile var lastError: String? = null
        private set

    /** Outcome of [testConnection]. [models] is what the server offers, when it could be listed. */
    data class TestResult(val ok: Boolean, val message: String, val models: List<String> = emptyList(), val modelFound: Boolean = false)

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        // Per-read timeout: a healthy model streams tokens well within this; a hung
        // or unreachable one now fails in a minute instead of leaving the user waiting.
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val JSON = "application/json".toMediaType()

    data class Msg(val role: String, val content: String)

    /** Trimmed URL with a scheme — "host:port" alone used to fail silently. */
    private fun base(baseUrl: String): String {
        val t = baseUrl.trim().trimEnd('/')
        return if (t.contains("://")) t else "http://$t"
    }

    /** OpenAI-style root — accepts the URL with or without a trailing /v1. */
    private fun v1(baseUrl: String) = base(baseUrl).removeSuffix("/v1") + "/v1"

    private val vllm get() = provider == Provider.VLLM

    private fun chatUrl(baseUrl: String) =
        if (vllm) "${v1(baseUrl)}/chat/completions" else "${base(baseUrl)}/api/chat"

    private fun request(url: String, p: Provider = provider): Request.Builder = Request.Builder().url(url).apply {
        val key = apiKey.trim()
        if (p == Provider.VLLM && key.isNotEmpty()) header("Authorization", "Bearer $key")
    }

    private fun chatPayload(model: String, messages: List<Msg>, stream: Boolean, temperature: Double, json: Boolean): Map<String, Any> {
        val payload = mutableMapOf<String, Any>(
            "model" to model,
            "messages" to messages.map { mapOf("role" to it.role, "content" to it.content) },
            "stream" to stream
        )
        if (vllm) {
            payload["temperature"] = temperature
            // Turns off reasoning on thinking-capable chat templates (Qwen3 etc.); ignored otherwise.
            payload["chat_template_kwargs"] = mapOf("enable_thinking" to false)
            if (json) payload["response_format"] = mapOf("type" to "json_object")
        } else {
            payload["think"] = false // disable reasoning tokens on thinking-capable models (ignored otherwise)
            payload["options"] = mapOf("num_ctx" to 8192, "temperature" to temperature)
            if (json) payload["format"] = "json"
        }
        return payload
    }

    /** The reply text from a full response (`message`) or a stream chunk (`delta` on vLLM). */
    private fun content(obj: JsonObject, streaming: Boolean): String? {
        val el: JsonElement? = if (vllm) {
            obj.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject(if (streaming) "delta" else "message")?.get("content")
        } else {
            obj.getAsJsonObject("message")?.get("content")
        }
        return el?.takeUnless { it.isJsonNull }?.asString
    }

    /** "HTTP 404: The model `x` does not exist." — pulls the server's own message out of either API's error shape. */
    private fun httpError(r: Response): String {
        val detail = try {
            val el = JsonParser.parseString(r.body?.string()).asJsonObject.get("error")
            when {
                el == null || el.isJsonNull -> null
                el.isJsonObject -> el.asJsonObject.get("message")?.asString
                else -> el.asString
            }
        } catch (_: Exception) { null }
        val hint = when (r.code) {
            401, 403 -> " — check the API key"
            404 -> if (detail == null) " — wrong URL or server type?" else ""
            else -> ""
        }
        return "HTTP ${r.code}" + (detail?.let { ": $it" } ?: "") + hint
    }

    private fun describe(e: Exception): String = when (e) {
        is ConnectException -> "nothing is listening there — server down or wrong port"
        is UnknownHostException -> "unknown host — check the address (and VPN/Tailscale)"
        is SocketTimeoutException -> "timed out — server unreachable or too slow"
        is IllegalArgumentException -> "invalid server URL"
        else -> e.message ?: e.javaClass.simpleName
    }

    /** Strips <think>/<thinking> reasoning blocks (including an unclosed trailing one). */
    private fun stripThink(s: String): String {
        var r = s.replace(Regex("(?s)<think>.*?</think>"), "")
            .replace(Regex("(?s)<thinking>.*?</thinking>"), "")
        var open = r.indexOf("<think>")
        if (open < 0) open = r.indexOf("<thinking>")
        if (open >= 0) r = r.substring(0, open)
        return r.trimStart()
    }

    /** Models offered by a server speaking [p], or null plus the reason it couldn't be listed. */
    private fun fetchModels(baseUrl: String, p: Provider): Pair<List<String>?, String?> = try {
        val v = p == Provider.VLLM
        val url = if (v) "${v1(baseUrl)}/models" else "${base(baseUrl)}/api/tags"
        client.newCall(request(url, p).build()).execute().use { r ->
            if (!r.isSuccessful) return null to httpError(r)
            val obj = JsonParser.parseString(r.body?.string()).asJsonObject
            val models = obj.getAsJsonArray(if (v) "data" else "models")?.mapNotNull {
                it.asJsonObject.get(if (v) "id" else "name")?.asString
            }
            if (models == null) null to "the server didn't answer like ${p.label}" else models to null
        }
    } catch (e: Exception) {
        null to describe(e)
    }

    /** The models the server offers — GET /api/tags (Ollama) or /v1/models (vLLM). */
    suspend fun listModels(baseUrl: String): List<String> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext emptyList()
        val (models, err) = fetchModels(baseUrl, provider)
        lastError = err
        models ?: emptyList()
    }

    /**
     * Settings → AI → Test: lists models, checks [model] is among them, then makes one tiny
     * real chat call. Every failure says exactly which step broke and why.
     */
    suspend fun testConnection(baseUrl: String, model: String): TestResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext TestResult(false, "Enter the server URL first.")
        val (models, err) = fetchModels(baseUrl, provider)
        if (models == null) {
            // A common mix-up: the URL points at the other kind of server.
            val other = Provider.values().first { it != provider }
            val hint = if (fetchModels(baseUrl, other).first != null) " This looks like a ${other.label} server — switch the type above." else ""
            return@withContext TestResult(false, "Can't reach ${provider.label} at ${base(baseUrl)}: $err.$hint")
        }
        if (models.isEmpty()) return@withContext TestResult(false, "Connected, but the server offers no models.", models)
        if (model.isBlank()) return@withContext TestResult(false, "Connected — ${models.size} model(s) available. Pick one.", models)
        if (model !in models) return@withContext TestResult(
            false, "Model \"$model\" isn't on this server. Available: ${models.take(5).joinToString()}", models
        )
        val started = System.currentTimeMillis()
        val reply = chat(baseUrl, model, listOf(Msg("user", "Reply with just the word OK.")))
        val secs = (System.currentTimeMillis() - started) / 1000.0
        if (reply == null) TestResult(false, "Model listed, but the chat call failed: ${lastError ?: "no reply"}", models, modelFound = true)
        else TestResult(true, "✓ Connected · $model replied in ${"%.1f".format(secs)} s", models, modelFound = true)
    }

    /** One-shot chat. Set [json] to force a JSON object reply. */
    suspend fun chat(baseUrl: String, model: String, messages: List<Msg>, json: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank() || model.isBlank()) { lastError = "no server or model set"; return@withContext null }
            try {
                // JSON calls are extraction/index-picking — classification, not writing;
                // sampling noise there means off-vocab genres and invalid indices.
                val payload = chatPayload(model, messages, stream = false, temperature = if (json) 0.0 else 0.4, json = json)
                val body = gson.toJson(payload).toRequestBody(JSON)
                val req = request(chatUrl(baseUrl)).post(body).build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) { lastError = httpError(r); return@withContext null }
                    val obj = JsonParser.parseString(r.body?.string()).asJsonObject
                    content(obj, streaming = false)?.let { stripThink(it) }
                        .also { lastError = if (it == null) "the server's reply had no text" else null }
                }
            } catch (e: Exception) {
                lastError = describe(e)
                null
            }
        }

    /** Streaming chat — [onToken] is invoked for each chunk as it arrives. Returns true on success. */
    suspend fun chatStream(
        baseUrl: String,
        model: String,
        messages: List<Msg>,
        onToken: (String) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || model.isBlank()) { lastError = "no server or model set"; return@withContext false }
        try {
            val payload = chatPayload(model, messages, stream = true, temperature = 0.6, json = false)
            val body = gson.toJson(payload).toRequestBody(JSON)
            val req = request(chatUrl(baseUrl)).post(body).build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) { lastError = httpError(r); return@withContext false }
                val source = r.body?.source() ?: return@withContext false
                val raw = StringBuilder()
                var emitted = 0
                while (!source.exhausted()) {
                    // Stop promptly if the caller's coroutine was cancelled (e.g. the user
                    // navigated away) so a stale stream can't keep writing to shared state.
                    if (!isActive) return@withContext false
                    var line = source.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    if (vllm) {
                        // Server-sent events: "data: {...}" lines, ended by "data: [DONE]".
                        if (!line.startsWith("data:")) continue
                        line = line.removePrefix("data:").trim()
                        if (line == "[DONE]") break
                    }
                    val obj = JsonParser.parseString(line).asJsonObject
                    // A mid-stream failure arrives as an error object instead of a chunk.
                    obj.get("error")?.takeUnless { it.isJsonNull }?.let { e ->
                        lastError = if (e.isJsonObject) e.asJsonObject.get("message")?.asString else e.asString
                        return@withContext false
                    }
                    content(obj, streaming = true)?.let { chunk ->
                        // Suppress any <think> reasoning; only stream the real answer.
                        raw.append(chunk)
                        val cleaned = stripThink(raw.toString())
                        if (cleaned.length > emitted) {
                            onToken(cleaned.substring(emitted))
                            emitted = cleaned.length
                        }
                    }
                    if (!vllm && obj.get("done")?.asBoolean == true) break
                }
                lastError = null
                true
            }
        } catch (e: Exception) {
            lastError = describe(e)
            false
        }
    }
}
