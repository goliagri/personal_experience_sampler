/**
 * CloudStore abstraction (spec §8.5) and the local-folder backend. Mirrors
 * `pes/store/cloud.py`: the sync procedure (§8.4) is written against this
 * interface so the Google Drive REST backend and a plain folder
 * (development/tests) are interchangeable. Paths are always cloud-relative,
 * `/`-separated (e.g. `events/laptop-9c11aa00/2026-08.jsonl`).
 *
 * `metadata` returns an opaque change tag (`etag`): callers may only compare
 * it for equality against a previously seen value. The local backend hashes
 * content; the Drive backend uses `modifiedTime`.
 */
package pes.store

import java.io.File
import java.security.MessageDigest

data class CloudMeta(val etag: String, val size: Long)

interface CloudStore {
    /** File content, or null if absent. */
    fun get(path: String): ByteArray?

    /** Write (overwrite) a file, creating parents. */
    fun put(path: String, data: ByteArray)

    /** Write only if the file does not exist; true if written. */
    fun putIfAbsent(path: String, data: ByteArray): Boolean

    /** All file paths under a directory prefix, recursively, sorted. */
    fun list(prefix: String): List<String>

    /** Metadata, or null if absent. */
    fun metadata(path: String): CloudMeta?
}

private fun checkRelative(path: String): String {
    if (path.isEmpty()) return path // the store root itself (list(""))
    val parts = path.split("/")
    require(!path.startsWith("/") && ".." !in parts && parts.none { it.isEmpty() }) {
        "cloud path must be relative, without ..: $path"
    }
    return path
}

/** A directory on disk acting as the cloud folder. */
class LocalFolderStore(root: File) : CloudStore {
    private val root: File = root.apply { mkdirs() }

    private fun full(path: String): File = File(root, checkRelative(path))

    override fun get(path: String): ByteArray? {
        val f = full(path)
        return if (f.isFile) f.readBytes() else null
    }

    override fun put(path: String, data: ByteArray) {
        val f = full(path)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(data)
        check(tmp.renameTo(f) || (f.delete() && tmp.renameTo(f))) { "rename failed: $f" }
    }

    override fun putIfAbsent(path: String, data: ByteArray): Boolean {
        if (full(path).exists()) return false
        put(path, data)
        return true
    }

    override fun list(prefix: String): List<String> {
        val base = full(prefix)
        if (!base.isDirectory) return emptyList()
        return base.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".tmp") }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .sorted()
            .toList()
    }

    override fun metadata(path: String): CloudMeta? {
        val f = full(path)
        if (!f.isFile) return null
        val data = f.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return CloudMeta(digest.joinToString("") { "%02x".format(it) }, data.size.toLong())
    }
}
