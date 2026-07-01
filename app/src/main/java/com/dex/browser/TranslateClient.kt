package com.dex.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class TranslateClient(private val settings: BrowserSettings) {

    companion object {
        private const val SEPARATOR = "\u2063###DEX_SEG###\u2063"
        private const val MAX_BATCH_CHARS = 3000
        private const val TIMEOUT_MS = 30000
    }

    suspend fun translateOne(text: String, targetLang: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val response = callApi(buildPrompt(targetLang, false), text)
                Result.success(response.trim())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun translateBatch(
        texts: List<String>,
        targetLang: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val batches = splitIntoBatches(texts)
            val allResults = mutableListOf<String>()

            for (batch in batches) {
                val joined = batch.joinToString(SEPARATOR)
                val response = callApi(buildPrompt(targetLang, true), joined)
                val parts = response.split(SEPARATOR)
                if (parts.size == batch.size) {
                    allResults.addAll(parts.map { it.trim() })
                } else {
                    for (t in batch) {
                        val single = callApi(buildPrompt(targetLang, false), t)
                        allResults.add(single.trim())
                    }
                }
            }
            Result.success(allResults)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun splitIntoBatches(texts: List<String>): List<List<String>> {
        val batches = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        var currentLen = 0
        for (t in texts) {
            if (currentLen + t.length > MAX_BATCH_CHARS && current.isNotEmpty()) {
                batches.add(current)
                current = mutableListOf()
                currentLen = 0
            }
            current.add(t)
            currentLen += t.length + SEPARATOR.length
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }

    private fun buildPrompt(targetLang: String, isBatch: Boolean): String {
        return if (isBatch) {
            "你是专业翻译引擎。将用户给出的文本翻译成${targetLang}。" +
                "文本由分隔符\"${SEPARATOR}\"分隔成多段，你必须逐段翻译，" +
                "并用完全相同的分隔符\"${SEPARATOR}\"拼接返回，段数必须和输入一致。" +
                "只返回译文，不要解释，不要加序号，保持原有段落顺序。"
        } else {
            "你是专业翻译引擎。将用户给出的文本翻译成${targetLang}。只返回译文，不要解释。"
        }
    }

    private fun callApi(systemPrompt: String, userContent: String): String {
        val authValue = settings.getDsAuth()
        if (authValue.isBlank()) {
            throw IllegalStateException("请先填写 DeepSeek API Key")
        }

        val messages = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            put(JSONObject().put("role", "user").put("content", userContent))
        }
        val body = JSONObject().apply {
            put("model", "deepseek-chat")
            put("messages", messages)
            put("temperature", 0.1)
            put("max_tokens", 4096)
        }

        val conn = (URL(settings.translateUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $authValue")
            doOutput = true
        }

        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        if (code != 200) {
            val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
            throw RuntimeException("DeepSeek 接口返回 $code: $err")
        }

        val responseText = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
        val json = JSONObject(responseText)
        return json.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }
}
