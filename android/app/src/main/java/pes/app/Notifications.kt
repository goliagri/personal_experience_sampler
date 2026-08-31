package pes.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import pes.Engine
import pes.Notifier
import pes.core.bool
import pes.core.int
import pes.core.objList
import pes.core.parseUtc
import pes.core.splitTags
import pes.core.str
import pes.core.TAG_RE

const val CHANNEL_PINGS = "pings"
const val EXTRA_SAMPLE = "pes.sample_id"
const val KEY_REPLY = "pes.reply"

fun ensureNotificationChannel(context: Context) {
    val channel = NotificationChannel(
        CHANNEL_PINGS, "Pings", NotificationManager.IMPORTANCE_HIGH
    ).apply { description = "Experience-sampling pings" }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

/**
 * Platform `Notifier` (spec §10.3): stream name + original time in the
 * content, Open / Snooze / Skip actions, and an inline tags reply when the
 * survey's first field is `tags` and no other field is required. One
 * notification id per sample keeps near-simultaneous streams distinguishable.
 */
class AndroidNotifier(private val context: Context) : Notifier {
    /** Set by EngineHost after construction; notify() runs on the engine
     * thread, so reading engine state here is safe. */
    var engine: Engine? = null

    override fun notify(sampleId: String, title: String, body: String) {
        val open = PendingIntent.getActivity(
            context, sampleId.hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(EXTRA_SAMPLE, sampleId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun action(verb: String): PendingIntent = PendingIntent.getBroadcast(
            context, (sampleId + verb).hashCode(),
            Intent(context, ActionReceiver::class.java)
                .setAction(verb)
                .putExtra(EXTRA_SAMPLE, sampleId),
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (verb == ActionReceiver.REPLY) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE),
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_PINGS)
            // Carried so `reconcile` can tell which sample a posted
            // notification belongs to without re-deriving the id hash.
            .addExtras(android.os.Bundle().apply { putString(EXTRA_SAMPLE, sampleId) })
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        // Android renders at most three actions. Tapping the body already
        // opens the sample, so the explicit "Open" button yields its slot to
        // the inline reply whenever §10.3 offers one.
        val replyField = inlineTagsField(sampleId)
        if (replyField != null) {
            val remote = RemoteInput.Builder(KEY_REPLY).setLabel("tags").build()
            builder.addAction(
                NotificationCompat.Action.Builder(0, "Reply tags", action(ActionReceiver.REPLY))
                    .addRemoteInput(remote)
                    .setAllowGeneratedReplies(false)
                    .build()
            )
        } else {
            builder.addAction(0, "Open", open)
        }
        builder
            .addAction(0, "Snooze", action(ActionReceiver.SNOOZE))
            .addAction(0, "Skip", action(ActionReceiver.SKIP))

        try {
            NotificationManagerCompat.from(context).notify(sampleId.hashCode(), builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted; the checklist surfaces it.
        }
    }

    override fun cancel(sampleId: String) {
        NotificationManagerCompat.from(context).cancel(sampleId.hashCode())
    }

    /**
     * Take down ping notifications whose sample is no longer active.
     *
     * Notifications outlive the process, so an alarm the system dropped (a
     * force-stop, an OEM battery manager) can leave a card reading "answer
     * now" beside a live ping, indistinguishable from it, with working Snooze
     * and inline-reply actions — Tier 3 charter C2 F3. Called on every tick,
     * so the next time the app runs for any reason the shade is made honest.
     */
    fun reconcile(activeSamples: Set<String>) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val posted = runCatching { manager.activeNotifications }.getOrNull() ?: return
        for (sbn in posted) {
            if (sbn.notification?.channelId != CHANNEL_PINGS) continue
            val sample = sbn.notification.extras?.getString(EXTRA_SAMPLE) ?: continue
            if (sample !in activeSamples) manager.cancel(sbn.id)
        }
    }

    /** The tags field id for the inline reply, or null if not offered
     * (§10.3: first field `tags` and no other field `required`). */
    fun inlineTagsField(sampleId: String): String? {
        val engine = engine ?: return null
        val streamId = sampleId.substringBefore("|")
        val scheduled = parseUtc(sampleId.substringAfter("|"))
        val stream = engine.streamConfig(streamId, scheduled) ?: return null
        val surveyRef = stream["survey"] as? kotlinx.serialization.json.JsonObject ?: return null
        val survey = engine.db.survey(surveyRef.str("id"), surveyRef.int("version")) ?: return null
        val fields = survey.objList("fields")
        val first = fields.firstOrNull() ?: return null
        if (first.str("type") != "tags") return null
        if (fields.drop(1).any { it.bool("required", false) }) return null
        return first.str("id")
    }
}

/** Snooze refusal codes (§6.5) as user-facing text. */
fun snoozeRefusalText(refusal: String): String = when (refusal) {
    "max_snoozes" -> "Snooze refused: no snoozes left"
    "expired" -> "Too late to snooze: this ping has expired"
    "near_expiry" -> "Snooze refused: too close to expiry"
    else -> "Snooze refused: $refusal"
}

private fun toast(context: Context, message: String) {
    android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
    }
}

class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val SNOOZE = "pes.action.SNOOZE"
        const val SKIP = "pes.action.SKIP"
        const val REPLY = "pes.action.REPLY"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val sampleId = intent.getStringExtra(EXTRA_SAMPLE) ?: return
        val pending = goAsync()
        val host = context.pesHost()
        host.post { engine ->
            when (intent.action) {
                SNOOZE -> {
                    val refusal = engine.snooze(sampleId)
                    Alarms.schedule(context, engine.nextWake(engine.clock.now()))
                    // Refusal leaves the notification up; say why.
                    if (refusal != null) toast(context, snoozeRefusalText(refusal))
                }
                SKIP -> engine.skip(sampleId)
                REPLY -> reply(context, engine, host.notifier, sampleId, intent)
            }
            pending.finish()
        }
    }

    /** Inline reply: log an `answered` event with `partial: true` and only
     * the tags field (§10.3). An empty or all-invalid reply must not create
     * an answered event (it would outrank a later expired). */
    private fun reply(
        context: Context,
        engine: Engine,
        notifier: AndroidNotifier,
        sampleId: String,
        intent: Intent,
    ) {
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_REPLY)?.toString() ?: return
        val fieldId = notifier.inlineTagsField(sampleId) ?: return
        // Same normalisation as the in-app form: the shade's IME capitalises.
        val tags = splitTags(text).filter { it.matches(TAG_RE) }
        if (tags.isEmpty()) {
            toast(context, "No valid tags in reply; ping still pending")
            return
        }
        engine.answer(
            sampleId,
            buildJsonObject { put(fieldId, JsonArray(tags.map { JsonPrimitive(it) })) },
            partial = true,
        )
    }
}
