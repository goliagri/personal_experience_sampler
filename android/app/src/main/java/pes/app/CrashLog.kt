package pes.app

import android.content.Context
import android.content.Intent
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Records the last uncaught exception to a file so it can be read from
 * Settings on the next launch (this app is installed by hand, without adb).
 * The previous handler still runs, so Android's own crash flow is unchanged.
 */
object CrashLog {
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                File(context.filesDir, FILE).writeText(
                    "thread ${thread.name}, app ${pes.APP_VERSION}\n$trace"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Record a caught-but-serious failure (e.g. engine startup) the same way. */
    fun record(context: Context, where: String, error: Throwable) {
        runCatching {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
            File(context.filesDir, FILE).writeText("$where, app ${pes.APP_VERSION}\n$trace")
        }
    }

    fun read(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.isFile }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, "Share crash log"))
    }
}
