/**
 * Minimal HTTP session abstraction for the Drive client (mirrors the
 * `requests`-shaped session the Python DriveStore injects): production uses
 * `UrlHttpSession` (HttpURLConnection — thin, no SDK, works on JVM and
 * Android), tests use an in-memory fake.
 */
package pes.store

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class HttpResponse(val statusCode: Int, val content: ByteArray) {
    fun json(): JsonObject = parseJson(content.decodeToString())
}

interface HttpSession {
    fun request(
        method: String,
        url: String,
        params: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        json: JsonObject? = null,
    ): HttpResponse
}

class UrlHttpSession(private val timeoutMs: Int = 30_000) : HttpSession {
    override fun request(
        method: String,
        url: String,
        params: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray?,
        json: JsonObject?,
    ): HttpResponse {
        val query = params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val full = if (query.isEmpty()) url else "$url?$query"
        val conn = URL(full).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val payload = json?.let {
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                Json.encodeToString(JsonElement.serializer(), it).toByteArray()
            } ?: body
            if (payload != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(payload) }
            }
            val status = conn.responseCode
            val stream = if (status < 400) conn.inputStream else conn.errorStream
            val content = stream?.use { it.readBytes() } ?: ByteArray(0)
            return HttpResponse(status, content)
        } catch (e: RuntimeException) {
            throw IOException("HTTP $method $url failed", e)
        } finally {
            conn.disconnect()
        }
    }
}

/** Provides bearer tokens for the Drive session. Implementations: DriveAuth
 * on desktop (Python), Google Identity's AuthorizationClient on Android. */
interface TokenSource {
    /** A valid access token; `forceRefresh` after a 401. Throws IOException
     * when the user must reconnect. */
    fun accessToken(forceRefresh: Boolean = false): String
}

/** HttpSession + TokenSource: bearer header, one retry on 401 (mirrors the
 * Python AuthorizedSession). */
class AuthorizedSession(
    private val tokens: TokenSource,
    private val inner: HttpSession = UrlHttpSession(),
) : HttpSession {
    override fun request(
        method: String,
        url: String,
        params: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray?,
        json: JsonObject?,
    ): HttpResponse {
        val first = inner.request(
            method, url, params,
            headers + ("Authorization" to "Bearer ${tokens.accessToken()}"), body, json,
        )
        if (first.statusCode != 401) return first
        return inner.request(
            method, url, params,
            headers + ("Authorization" to "Bearer ${tokens.accessToken(forceRefresh = true)}"), body, json,
        )
    }
}
