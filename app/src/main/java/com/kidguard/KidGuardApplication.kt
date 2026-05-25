package com.kidguard

import android.app.Application
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@HiltAndroidApp
class KidGuardApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())

                // Write to app's external files dir - accessible via file manager!
                val crashDir = File(getExternalFilesDir(null), "crash_logs")
                crashDir.mkdirs()
                val crashFile = File(crashDir, "crash_$timestamp.log")

                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                crashFile.writeText(
                    "=== KidGuard 崩溃报告 ===\n" +
                    "时间: $timestamp\n" +
                    "线程: ${thread.name}\n" +
                    "异常: ${throwable.javaClass.simpleName}: ${throwable.message}\n\n" +
                    "堆栈:\n$sw\n"
                )

                // Also log to logcat
                Log.e("KidGuard", "CRASH: ${throwable.javaClass.simpleName}: ${throwable.message}", throwable)
            } catch (_: Exception) {}

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
