package com.kidguard.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLog {
    private var logFile: File? = null
    private var initialized = false
    private const val MAX_LOG_SIZE = 50 * 1024  // 最大50KB
    private val lock = Any()

    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            initialized = true
            logFile = File(context.filesDir, "debug.log")
            logFile?.appendText("\n=== 新会话 ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())} ===\n")
            trimLogInternal()
        }
    }

    fun log(tag: String, msg: String) {
        Log.d(tag, msg)
        try {
            val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "[$time] $tag: $msg\n"
            synchronized(lock) {
                logFile?.appendText(line)
                // 每100条日志检查一次文件大小
                if (Math.random() < 0.01) trimLogInternal()
            }
        } catch (_: Exception) {}
    }

    /**
     * 获取最近的日志（最多取最后500行），复制到剪贴板
     */
    fun copyLogToClipboard(context: Context) {
        try {
            val text = getRecentLog(context, maxLines = 500)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("KidGuard Log", text))
            Toast.makeText(context, "日志已复制 (${text.length} 字符)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, "复制失败: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e("DebugLog", "copyLog failed", e)
        }
    }

    private fun getRecentLog(context: Context, maxLines: Int): String {
        return try {
            val file = File(context.filesDir, "debug.log")
            if (!file.exists()) return "暂无日志"
            synchronized(lock) {
                val lines = file.readLines()
                val recent = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
                recent.joinToString("\n")
            }
        } catch (e: Exception) {
            "读取日志失败: ${e.message}"
        }
    }

    /**
     * 截断日志文件，只保留最后部分
     */
    private fun trimLog() {
        synchronized(lock) {
            trimLogInternal()
        }
    }

    private fun trimLogInternal() {
        try {
            val file = logFile ?: return
            if (file.exists() && file.length() > MAX_LOG_SIZE) {
                val lines = file.readLines()
                val keep = lines.takeLast(200) // 保留最后200行
                file.writeText(keep.joinToString("\n") + "\n")
            }
        } catch (_: Exception) {}
    }
}
