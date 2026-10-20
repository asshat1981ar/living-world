package com.livingworld.game.android.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.livingworld.ai.huggingface.HfTransport
import java.net.HttpURLConnection
import java.net.URL

/**
 * Real HTTP for [HfTransport] using the platform's HttpURLConnection, so no extra network library is pulled in.
 * The endpoint host is already restricted by HuggingFaceEndpointConfig; any non-2xx status or I/O error comes back as
 * a failed Result so the dialogue falls back to the built-in line (G7).
 */
class HttpUrlHfTransport(private val timeoutMillis: Int = 20_000) : HfTransport {
    override suspend fun executePost(url: String, jsonBody: String, headers: Map<String, String>): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                try {
                    connection.requestMethod = "POST"
                    connection.connectTimeout = timeoutMillis
                    connection.readTimeout = timeoutMillis
                    connection.doOutput = true
                    headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                    connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        Result.failure(IllegalStateException("Hugging Face returned HTTP $status"))
                    } else {
                        Result.success(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                    }
                } finally {
                    connection.disconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
