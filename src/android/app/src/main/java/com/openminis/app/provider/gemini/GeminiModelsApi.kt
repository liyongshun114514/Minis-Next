package com.openminis.app.provider.gemini

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.provider.ProviderModelsCache
import com.openminis.app.provider.applyUserAgentOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

object GeminiModelsApi {
    private val client = OkHttpClient()
    private val cache = ProviderModelsCache("gemini")

    /**
     * Fetch the Gemini model catalog. Three auth modes matching iOS
     * `GeminiModelsAPI`:
     *   - API key via `?key=<k>` query param
     *   - OAuth via `Authorization: Bearer <token>` (no key param)
     *   - Cloud Code Assist: no public list endpoint — caller passes
     *     `cloudCodeFallback=true` to short-circuit straight to the built-in
     *     `LLMModel.allGemini` list.
     *
     * The spec also requires a **403 fallback** on OAuth: when the OAuth
     * token lacks the `generative-language` scope (common for Cloud Code
     * Assist tokens) the endpoint returns 403 — we fall back to the built-in
     * list instead of surfacing an error, matching iOS.
     *
     * @param context When provided, enables the 7-day disk cache at
     *   `cacheDir/models-cache/gemini/<sha256>.json`.
     */
    suspend fun fetchModels(
        apiKey: String,
        isOAuth: Boolean = false,
        cloudCodeFallback: Boolean = false,
        context: Context? = null,
        forceRefresh: Boolean = false,
        // [FEAT-261021-16] The instance's Settings > Network tag (prov:<id>);
        // null follows the default route.
        netTag: String? = null,
        // [T-authoritative-model-refresh] Called when the fetch could not read a
        // real model list and is about to return the built-in placeholder. The
        // caller then KEEPS its stored catalog instead of replacing it.
        onVendorFailure: ((String) -> Unit)? = null,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        if (cloudCodeFallback) return@withContext LLMModel.allGemini

        val cacheKey = (if (isOAuth) "oauth|" else "key|") + apiKey
        if (context != null && !forceRefresh) {
            cache.load(context, cacheKey)?.let { return@withContext it }
        }

        val builder = Request.Builder()
        if (isOAuth) {
            builder.url("https://generativelanguage.googleapis.com/v1beta/models")
            builder.header("Authorization", "Bearer $apiKey")
        } else {
            builder.url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
        }

        // [T-android-default-ua] brand outbound /v1beta/models request.
        builder.applyUserAgentOverride(null)
        val response = com.openminis.app.network.NetworkHttp.client(netTag, client).newCall(builder.build()).execute()
        val body = response.body?.string() ?: run {
            onVendorFailure?.invoke("the provider returned an empty response")
            return@withContext LLMModel.allGemini
        }

        if (!response.isSuccessful) {
            // 403 on OAuth almost always means the token lacks the
            // generative-language scope. Falling back to the built-in list
            // matches iOS and keeps Cloud Code Assist users functional.
            if (isOAuth && response.code == 403) {
                onVendorFailure?.invoke("HTTP 403: the OAuth token lacks the model-list scope")
                return@withContext LLMModel.allGemini
            }
            if (context != null && (response.code == 401 || response.code == 403)) {
                cache.invalidate(context, cacheKey)
            }
            onVendorFailure?.invoke("HTTP ${response.code}")
            return@withContext LLMModel.allGemini
        }

        val models = try {
            val json = JSONObject(body)
            val arr = json.optJSONArray("models") ?: run {
                onVendorFailure?.invoke("the response is not a model list")
                return@withContext LLMModel.allGemini
            }
            val result = mutableListOf<LLMModel>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name").removePrefix("models/")
                val displayName = obj.optString("displayName", "").ifBlank { LLMModel.modelDisplayName(name) }
                // Filter to chat-capable models (matching iOS).
                val supportsGen = obj.optJSONArray("supportedGenerationMethods")
                    ?.let { methods ->
                        (0 until methods.length()).any {
                            methods.getString(it).contains("generateContent")
                        }
                    } == true
                if (supportsGen) {
                    result.add(LLMModel(name, displayName, "Google"))
                }
            }
            // Parsed and empty is the provider's real answer: the caller may
            // clear the catalog instead of showing built-ins as live data.
            if (result.isEmpty()) return@withContext emptyList()
            ModelsDevApi.enrichModels(result)
        } catch (e: Exception) {
            onVendorFailure?.invoke("could not read the model list: ${e.message ?: e.javaClass.simpleName}")
            return@withContext LLMModel.allGemini
        }

        if (context != null) cache.save(context, cacheKey, models)
        models
    }
}
