/** In-memory fake of the Drive REST v3 surface DriveStore uses. Mirrors
 * `desktop/tests/scenarios/drive_fake.py`: files.list with the three query
 * shapes, files.get (metadata / alt=media / 404), folder create, multipart
 * create, and media update. modifiedTime is a deterministic counter — opaque
 * to callers, like the real timestamp. */
package pes.scenarios

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pes.store.HttpResponse
import pes.store.HttpSession
import pes.store.parseJson

class FakeFile(
    val id: String,
    var name: String,
    val mimeType: String,
    val parents: List<String>,
    var trashed: Boolean = false,
    var modifiedTime: String,
    var content: ByteArray,
)

class FakeDrive : HttpSession {
    val files = linkedMapOf<String, FakeFile>()
    private var seq = 0
    val calls = mutableListOf<Triple<String, String, Map<String, String>>>()
    var failAfter: Int? = null // throw IOException past this many calls

    private fun next(prefix: String): String {
        seq += 1
        return "%s-%06d".format(prefix, seq)
    }

    fun add(name: String, mime: String, parents: List<String>, content: ByteArray = ByteArray(0)): String {
        val id = next("id")
        files[id] = FakeFile(id, name, mime, parents, modifiedTime = next("mt"), content = content)
        return id
    }

    fun byName(name: String): List<FakeFile> =
        files.values.filter { it.name == name && !it.trashed }

    fun mediaDownloads(): List<String> = calls
        .filter { it.first == "GET" && it.third["alt"] == "media" }
        .map { it.second.substringAfterLast("/") }

    override fun request(
        method: String,
        url: String,
        params: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray?,
        json: JsonObject?,
    ): HttpResponse {
        calls.add(Triple(method, url, params))
        failAfter?.let { if (calls.size > it) throw IOException("simulated network failure") }

        if (url == "https://www.googleapis.com/drive/v3/files") {
            if (method == "GET") return list(params.getValue("q"))
            if (method == "POST") return create(json!!, ByteArray(0)) // folder create
        }
        if (url == "https://www.googleapis.com/upload/drive/v3/files" &&
            method == "POST" && params["uploadType"] == "multipart"
        ) {
            val (meta, content) = parseMultipart(body!!, headers.getValue("Content-Type"))
            return create(meta, content)
        }
        if (url.startsWith("https://www.googleapis.com/upload/drive/v3/files/")) {
            val fileId = url.substringAfterLast("/")
            if (method == "PATCH" && params["uploadType"] == "media") {
                val record = files[fileId]
                if (record == null || record.trashed) return jsonResponse(404, "error" to "not found")
                record.content = body!!
                record.modifiedTime = next("mt")
                return jsonResponse(200, "id" to fileId)
            }
        }
        if (url.startsWith("https://www.googleapis.com/drive/v3/files/")) {
            val fileId = url.substringAfterLast("/")
            val record = files[fileId]
            if (method == "GET") {
                if (record == null || record.trashed) return jsonResponse(404, "error" to "not found")
                if (params["alt"] == "media") return HttpResponse(200, record.content)
                return HttpResponse(200, Json.encodeToString(JsonElement.serializer(), meta(record)).toByteArray())
            }
        }
        throw AssertionError("FakeDrive: unexpected $method $url $params")
    }

    private fun jsonResponse(status: Int, vararg pairs: Pair<String, String>): HttpResponse =
        HttpResponse(
            status,
            Json.encodeToString(
                JsonElement.serializer(),
                buildJsonObject { for ((k, v) in pairs) put(k, v) },
            ).toByteArray(),
        )

    private fun meta(record: FakeFile): JsonObject = buildJsonObject {
        put("id", record.id)
        put("name", record.name)
        put("mimeType", record.mimeType)
        put("trashed", record.trashed)
        put("modifiedTime", record.modifiedTime)
        put("size", record.content.size.toString())
    }

    private fun create(metaDoc: JsonObject, content: ByteArray): HttpResponse {
        val id = add(
            (metaDoc.getValue("name") as JsonPrimitive).content,
            (metaDoc["mimeType"] as? JsonPrimitive)?.content ?: "application/octet-stream",
            (metaDoc["parents"] as? JsonArray)?.map { (it as JsonPrimitive).content } ?: emptyList(),
            content,
        )
        return jsonResponse(200, "id" to id)
    }

    private val nameRe = Regex("""name = '((?:[^'\\]|\\.)*)'""")
    private val mimeRe = Regex("""mimeType = '([^']*)'""")
    private val parentRe = Regex("""'([^']*)' in parents""")

    private fun list(q: String): HttpResponse {
        var name: String? = null
        var mime: String? = null
        var parent: String? = null
        for (raw in q.split(" and ")) {
            val clause = raw.trim()
            val nm = nameRe.matchEntire(clause)
            val mm = mimeRe.matchEntire(clause)
            val pm = parentRe.matchEntire(clause)
            when {
                nm != null -> name = nm.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
                mm != null -> mime = mm.groupValues[1]
                pm != null -> parent = pm.groupValues[1]
                clause != "trashed = false" ->
                    throw AssertionError("FakeDrive: unsupported clause $clause")
            }
        }
        val matches = files.values.filter {
            !it.trashed &&
                (name == null || it.name == name) &&
                (mime == null || it.mimeType == mime) &&
                (parent == null || parent in it.parents)
        }
        val doc = buildJsonObject { put("files", JsonArray(matches.map { meta(it) })) }
        return HttpResponse(200, Json.encodeToString(JsonElement.serializer(), doc).toByteArray())
    }

    private fun parseMultipart(body: ByteArray, contentType: String): Pair<JsonObject, ByteArray> {
        val boundary = "--" + contentType.substringAfter("boundary=")
        val text = body.toString(Charsets.ISO_8859_1)
        val parts = text.split(boundary)
        val metaPart = parts[1]
        val dataPart = parts[2]
        val metaDoc = parseJson(metaPart.substringAfter("\r\n\r\n").trimEnd('\r', '\n'))
        var content = dataPart.substringAfter("\r\n\r\n")
        content = content.removeSuffix("\r\n")
        return Pair(metaDoc, content.toByteArray(Charsets.ISO_8859_1))
    }
}
