package com.jhosue.utorrent

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Application class that installs a global crash handler.
 * On crash, saves the full stack trace to a file in internal storage.
 * Read the file on next launch to diagnose the crash without Logcat.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // Apply Dark Mode Preference
        val isDark = Prefs.isDarkMode(this)
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (isDark) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES 
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
        )

        installCrashHandler()
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()

                // Write crash log to internal storage
                val crashFile = File(filesDir, "last_crash.txt")
                crashFile.writeText(
                    "CRASH on thread: ${thread.name}\n" +
                    "Time: ${System.currentTimeMillis()}\n\n" +
                    stackTrace
                )

                Log.e("CrashHandler", "Crash saved to: ${crashFile.absolutePath}")
                Log.e("CrashHandler", stackTrace)
            } catch (e: Exception) {
                // Ignore errors in crash handler itself
            }

            // Call the original handler so Android also gets the crash
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
