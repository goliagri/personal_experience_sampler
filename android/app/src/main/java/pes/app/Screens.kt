package pes.app

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pes.Engine
import pes.core.bool
import pes.core.int
import pes.core.objList
import pes.core.optStr
import pes.core.parseUtc
import pes.core.str

// -- Backlog (§10.2) --------------------------------------------------------

data class BacklogRow(val sampleId: String, val stream: String, val whenLabel: String, val status: String)

@Composable
fun BacklogScreen(host: EngineHost, refresh: Int, push: (Screen) -> Unit) {
    val rows by produceState<List<BacklogRow>?>(null, refresh) {
        value = host.withEngine { engine ->
            engine.backlog().map {
                BacklogRow(
                    it.str("sample"),
                    engine.streamConfig(it.str("stream"), parseUtc(it.str("scheduled_utc")))?.str("name")
                        ?: it.str("stream"),
                    localDateTime(engine, parseUtc(it.str("scheduled_utc"))),
                    it.str("status"),
                )
            }
        }
    }
    val r = rows ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Backlog", style = MaterialTheme.typography.headlineSmall)
        Text(
            "These pings have expired. Answers will be marked late.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for ((stream, group) in r.groupBy { it.stream }) {
            HorizontalDivider()
            Text(stream, style = MaterialTheme.typography.titleMedium)
            for (row in group) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        Text(row.whenLabel, style = MaterialTheme.typography.titleLarge)
                        Text(row.status, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = { push(Screen.Answer(row.sampleId, fromBacklog = true)) }) {
                        Text("Answer")
                    }
                }
            }
        }
        if (r.isEmpty()) Text("Backlog is empty.")
    }
}

// -- History (§10.2) --------------------------------------------------------

private val STATUSES = listOf("all", "answered", "skipped", "expired", "unobserved", "suppressed", "pending", "retracted")

data class HistoryRow(
    val sampleId: String,
    val label: String,
    val status: String,
    val answered: Boolean,
    /** Past its active window — the clock decides, not the route or the status. */
    val late: Boolean,
)

@Composable
fun HistoryScreen(host: EngineHost, refresh: Int, push: (Screen) -> Unit) {
    var filter by remember { mutableStateOf("all") }
    var localBump by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val rows by produceState<List<HistoryRow>?>(null, refresh, filter, localBump) {
        value = host.withEngine { engine ->
            engine.db.sampleRows(statuses = if (filter == "all") null else listOf(filter))
                .sortedByDescending { it.str("scheduled_utc") }
                .take(200)
                .map {
                    val name = engine.streamConfig(it.str("stream"), parseUtc(it.str("scheduled_utc")))
                        ?.str("name") ?: it.str("stream")
                    val scheduled = parseUtc(it.str("scheduled_utc"))
                    val expiry = engine.effectiveSettings(it.str("stream"), scheduled)
                        .int("expiry_minutes") * 60L
                    HistoryRow(
                        it.str("sample"),
                        "${localDateTime(engine, parseUtc(it.str("scheduled_utc")))}  $name",
                        it.str("status"),
                        it.str("status") == "answered",
                        late = scheduled + expiry <= engine.clock.now(),
                    )
                }
        }
    }
    val r = rows ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("History", style = MaterialTheme.typography.headlineSmall)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (s in STATUSES) {
                FilterChip(selected = filter == s, onClick = { filter = s }, label = { Text(s) })
            }
        }
        for (row in r) {
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.padding(end = 8.dp)) {
                    Text(row.label)
                    Text(
                        row.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor(row.status),
                        // §10.1: retracted is struck through.
                        textDecoration = if (row.status == "retracted") {
                            androidx.compose.ui.text.style.TextDecoration.LineThrough
                        } else {
                            null
                        },
                    )
                }
                if (row.answered) {
                    TextButton(onClick = {
                        scope.launch {
                            host.withEngine { it.retract(row.sampleId) }
                            localBump += 1
                        }
                    }) { Text("Retract") }
                } else if (row.status in listOf("expired", "unobserved", "skipped", "pending")) {
                    // Whether answering is *late* is the clock's business, not
                    // the row's status: a ping skipped a minute ago is still
                    // inside its window, and calling that "Answer late" is a
                    // lie the user would learn to ignore (Tier 3 charter C2 F1).
                    TextButton(onClick = { push(Screen.Answer(row.sampleId, fromBacklog = true)) }) {
                        Text(if (row.late) "Answer late" else "Answer")
                    }
                }
            }
        }
        if (r.isEmpty()) Text("Nothing yet.")
    }
}

/**
 * The shared status palette (spec §10.1), the same hex values the desktop uses
 * in `ui/theme.py` — the two clients are meant to be recognisably one app, and
 * theme roles collapsed skipped/unobserved/pending into one grey (Tier 3
 * charter C6 F4). Lightened in dark mode to keep contrast on a dark surface;
 * `retracted` is rendered struck-through by its caller.
 */
private val STATUS_COLORS_LIGHT = mapOf(
    "answered" to Color(0xFF2E7D32), // green
    "skipped" to Color(0xFF757575), // gray
    "expired" to Color(0xFFF9A825), // amber
    "unobserved" to Color(0xFF607D8B), // blue-gray
    "suppressed" to Color(0xFF9E9E9E), // muted
    "pending" to Color(0xFF1565C0), // accent
    "retracted" to Color(0xFF9E9E9E),
)

private val STATUS_COLORS_DARK = mapOf(
    "answered" to Color(0xFF81C784),
    "skipped" to Color(0xFFBDBDBD),
    "expired" to Color(0xFFFFD54F),
    "unobserved" to Color(0xFF90A4AE),
    "suppressed" to Color(0xFF9E9E9E),
    "pending" to Color(0xFF64B5F6),
    "retracted" to Color(0xFF9E9E9E),
)

@Composable
fun statusColor(status: String): Color {
    val palette = if (isSystemInDarkTheme()) STATUS_COLORS_DARK else STATUS_COLORS_LIGHT
    return palette[status] ?: MaterialTheme.colorScheme.onSurfaceVariant
}

// -- Streams (§10.2, read-only in the Android MVP) --------------------------

data class StreamRow(val id: String, val name: String, val enabled: Boolean, val summary: String)

@Composable
fun StreamsScreen(host: EngineHost, refresh: Int, bump: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val rows by produceState<List<StreamRow>?>(null, refresh) {
        value = host.withEngine { engine ->
            (engine.db.latestConfig()?.objList("streams") ?: emptyList()).map { s ->
                val p = s["protocol"] as JsonObject
                // Type + params, the same summary the desktop's stream list
                // shows; the bare type id said nothing about the cadence (C4 F7).
                val params = p.entries.filter { it.key != "type" }
                    .joinToString(", ") { (k, v) -> "$k=${(v as? JsonPrimitive)?.content ?: v}" }
                val summary = if (params.isEmpty()) p.str("type") else "${p.str("type")} $params"
                StreamRow(s.str("id"), s.str("name"), s.bool("enabled", true), summary)
            }
        }
    }
    val r = rows ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Streams", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Streams are edited on the desktop; changes arrive at the next sync.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        for (row in r) {
            HorizontalDivider()
            Text("${row.name} (${row.summary})${if (row.enabled) "" else " — disabled"}")
            if (row.enabled) {
                OutlinedButton(onClick = {
                    scope.launch {
                        // The raw sample id told the user nothing (C4 F8); the
                        // stream and the local time are what they can check.
                        host.tryWithEngine {
                            val id = it.fireTestPing(row.id)
                            Alarms.schedule(context, it.nextWake(it.clock.now()))
                            localDateTime(it, parseUtc(id.substringAfter("|")))
                        }.onSuccess { at ->
                            status = "Test ping fired for ${row.name} at $at — check your notifications."
                        }.onFailure { status = "Could not fire a test ping: $it" }
                        bump()
                    }
                }) { Text("Fire test ping now") }
            }
        }
        if (r.isEmpty()) Text("No streams configured.")
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    }
}

// -- Settings + permissions checklist (§10.2, §11) --------------------------

data class ChecklistItem(val label: String, val ok: Boolean, val actionLabel: String, val action: () -> Unit)

@Composable
fun SettingsScreen(host: EngineHost, refresh: Int, bump: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checkTick by remember { mutableIntStateOf(0) }
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { checkTick += 1 }

    val info by produceState<Triple<String, String, String?>?>(null, refresh) {
        value = host.withEngine {
            Triple(
                it.deviceId,
                it.db.latestConfig()?.str("timezone") ?: "(no config)",
                it.db.kvGet("device", "name"),
            )
        }
    }
    val role by produceState<String?>("", refresh) {
        value = host.withEngine { it.db.kvGet("device", "role") }
    }
    var confirmRestore by remember { mutableStateOf(false) }
    val i = info ?: return
    var name by remember(i.third) { mutableStateOf(i.third ?: i.first) }

    // Keyed on `refresh` as well: the Grant/Allow buttons leave for a system
    // screen, so the state changes while this composition is alive and the
    // checklist has to re-read it on resume (C4 F5).
    val checklist = remember(checkTick, refresh) { permissionsChecklist(context) { notifPermission.launch(it) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("Device id: ${i.first}")
        Text("Timezone: ${i.second} (edited on desktop)")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Device name") })
            TextButton(onClick = {
                scope.launch {
                    host.withEngine { it.db.kvSet("device", "name", name.trim()) }
                    bump()
                }
            }) { Text("Set") }
        }

        HorizontalDivider()
        Text("Permissions checklist", style = MaterialTheme.typography.titleMedium)
        for (item in checklist) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text((if (item.ok) "✓ " else "✗ ") + item.label, Modifier.padding(top = 12.dp, end = 8.dp))
                if (!item.ok) TextButton(onClick = { item.action(); checkTick += 1 }) { Text(item.actionLabel) }
            }
        }
        if (Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
            Text(
                "Samsung: add this app to Settings > Battery > Never sleeping apps," +
                    " or pings will stop after a few days.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) }
            }) { Text("Open battery settings") }
        }

        HorizontalDivider()
        DriveSection(host, refresh, bump)

        HorizontalDivider()
        OutlinedButton(onClick = { SyncWorker.syncNow(context) }) { Text("Sync now") }
        Text(
            when {
                role == "primary" -> "Snapshot role: primary (this device zips the cloud folder weekly)"
                // Before the first sync nothing has been claimed by anyone;
                // claiming otherwise is a guess about a cloud we never read (C4 F6).
                role == null -> "Snapshot role: not claimed yet (no sync has run)"
                else -> "Snapshot role: none (another device holds primary)"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { confirmRestore = true }) { Text("Restore cloud from local cache…") }
        if (confirmRestore) {
            AlertDialog(
                onDismissRequest = { confirmRestore = false },
                title = { Text("Restore cloud folder") },
                text = {
                    Text(
                        "Re-create any cloud files missing from this device's local cache" +
                            " (own event log, cached copies of other devices' logs, config" +
                            " history, surveys). Existing cloud files are never overwritten;" +
                            " extra lines go to restored/. A normal sync follows."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmRestore = false
                        SyncWorker.restoreNow(context)
                        scope.launch {
                            kotlinx.coroutines.delay(8000)
                            bump()
                        }
                    }) { Text("Restore") }
                },
                dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } },
            )
        }

        ScheduleSection(host, refresh)
        CrashSection()
    }
}

/** Last uncaught exception, if any (see [CrashLog]). */
@Composable
private fun CrashSection() {
    val context = LocalContext.current
    var crash by remember { mutableStateOf(CrashLog.read(context)) }
    val text = crash ?: return
    HorizontalDivider()
    Text("Last crash", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
    Text(text.lineSequence().take(12).joinToString("\n"), style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { CrashLog.share(context, text) }) { Text("Share") }
        TextButton(onClick = { CrashLog.clear(context); crash = null }) { Text("Dismiss") }
    }
}

fun permissionsChecklist(context: Context, requestNotif: (String) -> Unit): List<ChecklistItem> {
    val am = context.getSystemService(AlarmManager::class.java)
    val pm = context.getSystemService(PowerManager::class.java)
    val items = mutableListOf<ChecklistItem>()
    items.add(
        ChecklistItem(
            "Notifications", NotificationManagerCompat.from(context).areNotificationsEnabled(), "Grant"
        ) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestNotif(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    )
    if (Build.VERSION.SDK_INT >= 31) {
        items.add(
            // Name the consequence: without it pings drift, they do not stop
            // (measured ~10 min of slack on a 60-minute window) — C5 F5.
            ChecklistItem(
                if (am.canScheduleExactAlarms()) {
                    "Exact alarms"
                } else {
                    "Exact alarms — off: pings arrive minutes late, not on the second"
                },
                am.canScheduleExactAlarms(),
                "Allow",
            ) {
                context.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        )
    }
    items.add(
        ChecklistItem(
            "Battery optimization exempt", pm.isIgnoringBatteryOptimizations(context.packageName), "Exempt"
        ) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    )
    return items
}

/** "Show schedule" (§10.2): hidden behind an explicit action; re-read on
 * every [refresh] (e.g. after a sync applies a new config). */
@Composable
private fun ScheduleSection(host: EngineHost, refresh: Int) {
    var shown by remember { mutableStateOf(false) }
    HorizontalDivider()
    if (!shown) {
        TextButton(onClick = { shown = true }) { Text("Show schedule (next 48 h)…") }
        return
    }
    val lines by produceState<List<String>?>(null, refresh) {
        value = host.withEngine { engine ->
            engine.db.dueSchedule("9999").map {
                val name = engine.streamConfig(it.stream, parseUtc(it.scheduledUtc))
                    ?.str("name") ?: it.stream
                "${localDateTime(engine, parseUtc(it.scheduledUtc))}  $name" +
                    (it.suppressedReason?.let { r -> "  [$r]" } ?: "")
            }
        }
    }
    Text("Schedule (next 48 h)", style = MaterialTheme.typography.titleMedium)
    lines?.let { rows ->
        for (line in rows) Text(line, style = MaterialTheme.typography.bodySmall)
        if (rows.isEmpty()) Text("Nothing scheduled.")
    }
}
