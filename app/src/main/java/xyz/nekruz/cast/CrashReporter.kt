package xyz.nekruz.cast

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.webkit.WebView
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Keeps the reason of the last crash so the next launch can show it on screen
 * (the panel has no adb access — the report is meant to be photographed).
 *
 * - Java crashes: caught by the default uncaught-exception handler, full stack trace saved to a file.
 * - Native crashes / ANR / kills (Android 11+): taken from ApplicationExitInfo on the next launch.
 * - A short event log (renderer restarts, load errors) is attached to either.
 */
object CrashReporter {
    private const val CRASH_FILE = "last_crash.txt"
    private const val EVENTS_FILE = "events.txt"
    private const val MAX_EVENTS = 30
    private const val PREFS = "crash"
    private const val KEY_EXIT_SEEN = "exitInfoSeenAt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                File(app.filesDir, CRASH_FILE).writeText(
                    report(app, "Необработанное исключение в потоке «${thread.name}»", trace)
                )
            } catch (_: Throwable) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    /** Appends a timestamped line to the event log shown together with a crash report. */
    fun note(context: Context, event: String) {
        try {
            val file = File(context.filesDir, EVENTS_FILE)
            val lines = (if (file.exists()) file.readLines() else emptyList()) + "${now()}  $event"
            file.writeText(lines.takeLast(MAX_EVENTS).joinToString("\n", postfix = "\n"))
        } catch (_: Throwable) {
        }
    }

    /** The report about the previous run, or null if it ended normally. */
    fun pendingReport(context: Context): String? {
        val crashFile = File(context.filesDir, CRASH_FILE)
        if (crashFile.exists()) {
            return try { crashFile.readText() } catch (_: Throwable) { null }
        }
        return exitInfoReport(context)
    }

    /** Called when the user taps "Продолжить": forget everything already shown. */
    fun clear(context: Context) {
        File(context.filesDir, CRASH_FILE).delete()
        File(context.filesDir, EVENTS_FILE).delete()
        markExitInfoSeen(context)
    }

    private fun exitInfoReport(context: Context): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        return try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return null
            val seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_EXIT_SEEN, 0)
            // Newest first; an app update or a normal kill may sit on top of the interesting one.
            val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
                .filter { it.timestamp > seen }
                .firstOrNull { isAbnormal(it) } ?: run {
                markExitInfoSeen(context)
                return null
            }
            val details = buildString {
                append("Причина: ").append(reasonName(info.reason))
                append("\nСтатус/сигнал: ").append(info.status)
                append("\nВремя: ").append(format(info.timestamp))
                append("\nПамять (PSS/RSS): ").append(info.pss / 1024).append(" / ").append(info.rss / 1024).append(" МБ")
                info.description?.let { append("\nОписание: ").append(it) }
                traceExcerpt(info)?.let { append("\n\n").append(it) }
            }
            report(context, "Приложение было завершено системой", details)
        } catch (_: Throwable) {
            null
        }
    }

    private fun isAbnormal(info: ApplicationExitInfo): Boolean {
        val abnormal = info.reason in setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_OTHER,
        )
        // Background kills of an idle app are normal; only report deaths while it was on screen.
        val onScreen = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
        return abnormal && onScreen
    }

    private fun markExitInfoSeen(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val latest = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()?.timestamp ?: return
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_EXIT_SEEN, latest).apply()
        } catch (_: Throwable) {
        }
    }

    // ANR traces are text; native tombstones (Android 12+) are protobuf — keep their readable strings.
    private fun traceExcerpt(info: ApplicationExitInfo): String? {
        if (info.reason != ApplicationExitInfo.REASON_ANR && info.reason != ApplicationExitInfo.REASON_CRASH_NATIVE) return null
        val bytes = try {
            info.traceInputStream?.use { input ->
                val buf = ByteArray(256 * 1024)
                var total = 0
                while (total < buf.size) {
                    val n = input.read(buf, total, buf.size - total)
                    if (n <= 0) break
                    total += n
                }
                buf.copyOf(total)
            }
        } catch (_: Throwable) {
            null
        } ?: return null

        val lines = if (info.reason == ApplicationExitInfo.REASON_ANR) {
            String(bytes, Charsets.UTF_8).lines().filter { it.isNotBlank() }
        } else {
            Regex("[\\x20-\\x7E]{6,}").findAll(String(bytes, Charsets.ISO_8859_1)).map { it.value }.toList()
        }
        return "Трасса:\n" + lines.take(80).joinToString("\n")
    }

    private fun report(context: Context, title: String, body: String, includeEvents: Boolean = true): String =
        buildString {
            append(title).append('\n')
            append(deviceInfo(context)).append("\n\n")
            append(body.trim())
            if (includeEvents) {
                val events = File(context.filesDir, EVENTS_FILE)
                if (events.exists()) {
                    val text = try { events.readText().trim() } catch (_: Throwable) { "" }
                    if (text.isNotEmpty()) append("\n\nПоследние события:\n").append(text)
                }
            }
        }

    private fun deviceInfo(context: Context): String {
        val app = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Throwable) {
            "?"
        }
        val webView = if (Build.VERSION.SDK_INT >= 26) {
            try {
                WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" }
            } catch (_: Throwable) {
                null
            }
        } else null
        return "Cast $app · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL} · ${Build.DISPLAY}" +
            (webView?.let { "\nWebView: $it" } ?: "") +
            "\nЗаписано: ${now()}"
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH (исключение Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (падение в нативном коде, часто WebView/GPU)"
        ApplicationExitInfo.REASON_ANR -> "ANR (приложение зависло)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (не хватило памяти)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (процесс убит сигналом)"
        ApplicationExitInfo.REASON_OTHER -> "OTHER (завершено системой/прошивкой)"
        else -> "код $reason"
    }

    private fun now() = format(System.currentTimeMillis())
    private fun format(ms: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
}
