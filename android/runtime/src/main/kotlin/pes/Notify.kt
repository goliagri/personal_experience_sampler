/** Notification sink (mirrors `pes/notify.py`): the engine never talks to a
 * platform notification API directly. */
package pes

interface Notifier {
    fun notify(sampleId: String, title: String, body: String)

    fun cancel(sampleId: String)
}

/** Records calls for scenario tests (mirrors the Python RecordingNotifier). */
class RecordingNotifier : Notifier {
    val shown = mutableListOf<Triple<String, String, String>>()
    val log = mutableListOf<Pair<String, String>>() // ("show"|"cancel", sampleId)

    override fun notify(sampleId: String, title: String, body: String) {
        shown.add(Triple(sampleId, title, body))
        log.add(Pair("show", sampleId))
    }

    override fun cancel(sampleId: String) {
        log.add(Pair("cancel", sampleId))
    }

    fun active(): Set<String> {
        val current = mutableSetOf<String>()
        for ((op, sampleId) in log) {
            if (op == "show") current.add(sampleId) else current.remove(sampleId)
        }
        return current
    }
}
