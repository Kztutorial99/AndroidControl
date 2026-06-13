package com.kztutorial99.androidconnector

import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileOperations {

    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun listDir(path: String): Pair<String, String> {
        return try {
            val dir = File(path)
            if (!dir.exists()) return Pair("error", "Path not found: $path")
            if (!dir.isDirectory) return Pair("error", "Not a directory: $path")

            val entries = JsonArray()
            val files = dir.listFiles()
            if (files == null) return Pair("error", "Permission denied or empty: $path")

            files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                .forEach { f ->
                    val entry = JsonObject()
                    entry.addProperty("name", f.name)
                    entry.addProperty("type", if (f.isDirectory) "dir" else "file")
                    entry.addProperty("size", if (f.isFile) formatSize(f.length()) else "--")
                    entry.addProperty("permissions", getPermissions(f))
                    entry.addProperty("modified", dateFmt.format(Date(f.lastModified())))
                    entries.add(entry)
                }

            val result = JsonObject()
            result.addProperty("path", path)
            result.add("entries", entries)
            Pair("file_listing", result.toString())
        } catch (e: SecurityException) {
            Pair("error", "Permission denied: $path")
        } catch (e: Exception) {
            Pair("error", e.message ?: "Unknown error")
        }
    }

    fun readFileBase64(path: String): String {
        return try {
            val bytes = File(path).readBytes()
            if (bytes.size > 5_000_000) return "ERROR: File too large (>${bytes.size / 1024}KB). Use read_text for text files."
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: SecurityException) {
            "ERROR: Permission denied"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    fun readFileText(path: String, maxLines: Int = 500): String {
        return try {
            val lines = File(path).readLines()
            val taken = lines.take(maxLines)
            val result = taken.joinToString("\n")
            if (lines.size > maxLines)
                result + "\n\n[... ${lines.size - maxLines} more lines truncated ...]"
            else result
        } catch (e: SecurityException) {
            "ERROR: Permission denied"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    fun writeFileBase64(path: String, base64: String): String {
        return try {
            val bytes = Base64.decode(base64.trim(), Base64.DEFAULT)
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
            "OK: Written ${formatSize(bytes.size.toLong())} to $path"
        } catch (e: SecurityException) {
            "ERROR: Permission denied"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    fun writeFileText(path: String, content: String): String {
        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            "OK: Written ${content.length} chars to $path"
        } catch (e: SecurityException) {
            "ERROR: Permission denied"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    fun makeDir(path: String): String {
        return try {
            if (File(path).mkdirs()) "OK: Created directory $path"
            else "ERROR: Could not create $path (may already exist)"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    fun deleteFile(path: String): String {
        return try {
            val f = File(path)
            if (!f.exists()) return "ERROR: Not found: $path"
            if (f.deleteRecursively()) "OK: Deleted $path"
            else "ERROR: Could not fully delete $path"
        } catch (e: SecurityException) {
            "ERROR: Permission denied"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    fun moveFile(src: String, dst: String): String {
        return try {
            val srcFile = File(src)
            val dstFile = File(dst)
            if (!srcFile.exists()) return "ERROR: Source not found: $src"
            dstFile.parentFile?.mkdirs()
            if (srcFile.renameTo(dstFile)) {
                "OK: Moved to $dst"
            } else {
                srcFile.copyTo(dstFile, overwrite = true)
                srcFile.delete()
                "OK: Copied to $dst"
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    fun getFileInfo(path: String): String {
        return try {
            val f = File(path)
            if (!f.exists()) return "ERROR: Not found: $path"
            buildString {
                appendLine("Path: $path")
                appendLine("Type: ${if (f.isDirectory) "Directory" else "File"}")
                appendLine("Size: ${formatSize(f.length())}")
                appendLine("Permissions: ${getPermissions(f)}")
                appendLine("Modified: ${dateFmt.format(Date(f.lastModified()))}")
                appendLine("Readable: ${f.canRead()}")
                appendLine("Writable: ${f.canWrite()}")
                if (f.isDirectory) appendLine("Children: ${f.listFiles()?.size ?: 0}")
            }
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 0) return "--"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.2f GB".format(mb / 1024.0)
    }

    private fun getPermissions(f: File) = buildString {
        append(if (f.isDirectory) 'd' else '-')
        append(if (f.canRead()) 'r' else '-')
        append(if (f.canWrite()) 'w' else '-')
        append(if (f.canExecute()) 'x' else '-')
    }
}
