/**
 * Google Drive REST v3 backend for `CloudStore` (spec §8.5). Mirrors
 * `pes/store/drive.py`.
 *
 * Thin client over the REST API — no Google SDK. Scope is `drive.file` only,
 * so every visible file was created by this app. The root folder
 * (`PersonalExperienceSampler`) is located by name once, then addressed by
 * its stored Drive file ID (kv namespace "drive"), so renames in Drive are
 * harmless. Folder and file IDs are cached in the same namespace and
 * re-resolved on 404 (something deleted/recreated remotely).
 *
 * `metadata()`'s etag is Drive's `modifiedTime` — opaque to callers, only
 * compared for equality, exactly like the local backend's content hash.
 */
package pes.store

import java.io.IOException
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pes.core.bool
import pes.core.optStr
import pes.core.str

const val FILES_URL = "https://www.googleapis.com/drive/v3/files"
const val UPLOAD_URL = "https://www.googleapis.com/upload/drive/v3/files"
const val FOLDER_MIME = "application/vnd.google-apps.folder"
const val ROOT_FOLDER_NAME = "PersonalExperienceSampler"
private const val LIST_FIELDS = "files(id,name,mimeType,modifiedTime,size),nextPageToken"

/** Unexpected Drive API response. */
open class DriveError(message: String) : IOException(message)

/** More than one root folder found (§8.5): user must resolve manually. */
class MultipleRootsError(message: String) : DriveError(message)

private fun escape(name: String): String = name.replace("\\", "\\\\").replace("'", "\\'")

/** CloudStore over the Drive REST API; ID caches live in the local db. */
class DriveStore(
    private val session: HttpSession,
    private val db: Db, // kv namespace "drive": root + path->id caches
    private val rootName: String = ROOT_FOLDER_NAME,
) : CloudStore {
    private var root: String? = null // verified once per instance

    // -- low-level HTTP ---------------------------------------------------

    private fun request(
        method: String,
        url: String,
        ok: Set<Int> = setOf(200),
        params: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        json: JsonObject? = null,
    ): HttpResponse {
        val resp = session.request(method, url, params, headers, body, json)
        if (resp.statusCode !in ok) {
            throw DriveError("Drive API ${resp.statusCode} on $method $url")
        }
        return resp
    }

    private fun listQuery(q: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        var token: String? = null
        while (true) {
            val params = mutableMapOf("q" to q, "fields" to LIST_FIELDS, "pageSize" to "1000")
            token?.let { params["pageToken"] = it }
            val bodyDoc = request("GET", FILES_URL, params = params).json()
            out.addAll((bodyDoc["files"] as? kotlinx.serialization.json.JsonArray)?.map { it as JsonObject } ?: emptyList())
            token = bodyDoc.optStr("nextPageToken") ?: return out
        }
    }

    private fun children(parentId: String, name: String? = null): List<JsonObject> {
        var q = "'$parentId' in parents and trashed = false"
        if (name != null) q = "name = '${escape(name)}' and $q"
        return listQuery(q)
    }

    // -- ID cache ----------------------------------------------------------

    private fun cached(key: String): String? = db.kvGet("drive", key)?.takeIf { it.isNotEmpty() }

    private fun cache(key: String, fileId: String) = db.kvSet("drive", key, fileId)

    private fun dropCache(key: String) = db.kvSet("drive", key, "")

    private fun exists(fileId: String): Boolean {
        val resp = request(
            "GET", "$FILES_URL/$fileId", ok = setOf(200, 404),
            params = mapOf("fields" to "id,trashed"),
        )
        return resp.statusCode == 200 && !resp.json().bool("trashed", false)
    }

    // -- root folder (§8.5) ------------------------------------------------

    fun rootId(): String {
        root?.let { return it }
        val cachedRoot = cached("root")
        if (cachedRoot != null && exists(cachedRoot)) {
            root = cachedRoot
            return cachedRoot
        }
        if (cachedRoot != null) {
            // Root vanished (folder deleted/recreated): every cached ID under
            // it is stale too.
            for (key in db.kvAll("drive").keys) dropCache(key)
        }
        val matches = listQuery(
            "name = '${escape(rootName)}' and mimeType = '$FOLDER_MIME' and trashed = false"
        )
        if (matches.size > 1) {
            throw MultipleRootsError(
                "Found ${matches.size} '$rootName' folders in Drive;" +
                    " remove or rename the extras, then sync again"
            )
        }
        val id = matches.firstOrNull()?.str("id") ?: createFolder(rootName, null)
        cache("root", id)
        root = id
        return id
    }

    private fun createFolder(name: String, parentId: String?): String {
        val meta = buildJsonObject {
            put("name", name)
            put("mimeType", FOLDER_MIME)
            if (parentId != null) put("parents", buildJsonArray { add(JsonPrimitive(parentId)) })
        }
        return request("POST", FILES_URL, json = meta).json().str("id")
    }

    // -- path resolution ---------------------------------------------------

    /** Folder ID for a directory path (relative to root); walks + caches. */
    private fun resolveDir(parts: List<String>, create: Boolean): String? {
        var parent = rootId()
        var path = ""
        for (name in parts) {
            path = if (path.isEmpty()) name else "$path/$name"
            val key = "dir:$path"
            val hit = cached(key)
            if (hit != null) {
                parent = hit
                continue
            }
            val found = children(parent, name).filter { it.str("mimeType") == FOLDER_MIME }
            parent = when {
                found.isNotEmpty() -> found.first().str("id")
                create -> createFolder(name, parent)
                else -> return null
            }
            cache(key, parent)
        }
        return parent
    }

    private fun pathParts(path: String): List<String> {
        require(!path.startsWith("/") && ".." !in path.split("/")) {
            "cloud path must be relative, without ..: $path"
        }
        return path.split("/").filter { it.isNotEmpty() }
    }

    /** {"id", "modifiedTime", "size"} for a file path, or null if absent. */
    private fun resolveFile(path: String): JsonObject? {
        val parts = pathParts(path)
        val parent = resolveDir(parts.dropLast(1), create = false) ?: return null
        val key = "file:$path"
        val hit = cached(key)
        if (hit != null) {
            val resp = request(
                "GET", "$FILES_URL/$hit", ok = setOf(200, 404),
                params = mapOf("fields" to "id,trashed,modifiedTime,size"),
            )
            if (resp.statusCode == 200 && !resp.json().bool("trashed", false)) return resp.json()
            dropCache(key)
        }
        val found = children(parent, parts.last()).filter { it.str("mimeType") != FOLDER_MIME }
        if (found.isEmpty()) return null
        cache(key, found.first().str("id"))
        return found.first()
    }

    // -- CloudStore interface ---------------------------------------------

    override fun get(path: String): ByteArray? {
        val info = resolveFile(path) ?: return null
        val resp = request(
            "GET", "$FILES_URL/${info.str("id")}", ok = setOf(200, 404),
            params = mapOf("alt" to "media"),
        )
        if (resp.statusCode == 404) { // deleted between resolve and read
            dropCache("file:$path")
            return null
        }
        return resp.content
    }

    override fun put(path: String, data: ByteArray) {
        val info = resolveFile(path)
        if (info != null) {
            val resp = request(
                "PATCH", "$UPLOAD_URL/${info.str("id")}", ok = setOf(200, 404),
                params = mapOf("uploadType" to "media"),
                body = data,
                headers = mapOf("Content-Type" to "application/octet-stream"),
            )
            if (resp.statusCode == 200) return
            dropCache("file:$path") // vanished remotely; create fresh
        }
        createFile(path, data)
    }

    override fun putIfAbsent(path: String, data: ByteArray): Boolean {
        if (resolveFile(path) != null) return false
        createFile(path, data)
        return true
    }

    private fun createFile(path: String, data: ByteArray) {
        val parts = pathParts(path)
        val parent = resolveDir(parts.dropLast(1), create = true)!!
        val boundary = "pes" + ByteArray(12).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val meta = buildJsonObject {
            put("name", parts.last())
            put("parents", buildJsonArray { add(JsonPrimitive(parent)) })
        }
        val body = (
            "--$boundary\r\n" +
                "Content-Type: application/json; charset=UTF-8\r\n\r\n" +
                Json.encodeToString(JsonElement.serializer(), meta) + "\r\n" +
                "--$boundary\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n"
            ).toByteArray() + data + "\r\n--$boundary--\r\n".toByteArray()
        val resp = request(
            "POST", UPLOAD_URL,
            params = mapOf("uploadType" to "multipart"),
            body = body,
            headers = mapOf("Content-Type" to "multipart/related; boundary=$boundary"),
        )
        cache("file:$path", resp.json().str("id"))
    }

    override fun list(prefix: String): List<String> {
        val parts = pathParts(prefix)
        val base = resolveDir(parts, create = false) ?: return emptyList()
        val out = mutableListOf<String>()
        val stack = ArrayDeque(listOf(Pair(parts.joinToString("/"), base)))
        while (stack.isNotEmpty()) {
            val (dirPath, dirId) = stack.removeLast()
            for (f in children(dirId)) {
                val childPath = "$dirPath/${f.str("name")}"
                if (f.str("mimeType") == FOLDER_MIME) {
                    cache("dir:$childPath", f.str("id"))
                    stack.addLast(Pair(childPath, f.str("id")))
                } else {
                    cache("file:$childPath", f.str("id"))
                    out.add(childPath)
                }
            }
        }
        return out.sorted()
    }

    override fun metadata(path: String): CloudMeta? {
        val info = resolveFile(path) ?: return null
        return CloudMeta(info.str("modifiedTime"), info.optStr("size")?.toLongOrNull() ?: 0)
    }
}
