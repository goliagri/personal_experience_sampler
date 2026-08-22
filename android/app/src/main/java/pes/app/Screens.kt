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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import pes.Engine
import pes.core.bool
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

data class HistoryRow(val sampleId: String, val label: String, val status: String, val answered: Boolean)

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
                    HistoryRow(
                        it.str("sample"),
                        "${localDateTime(engine, parseUtc(it.str("scheduled_utc")))}  $name",
                        it.str("status"),
                        it.str("status") == "answered",
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
                    Text(row.status, style = MaterialTheme.typography.bodySmall, color = statusColor(row.status))
                }
                if (row.answered) {
                    TextButton(onClick = {
                        scope.launch {
                            host.withEngine { it.retract(row.sampleId) }
                            localBump += 1
                        }
                    }) { Text("Retract") }
                } else if (row.status in listOf("expired", "unobserved", "skipped", "pending")) {
                    TextButton(onClick = { push(Screen.Answer(row.sampleId, fromBacklog = true)) }) {
                        Text("Answer late")
                    }
                }
            }
        }
        if (r.isEmpty()) Text("Nothing yet.")
    }
}

@Composable
private fun statusColor(status: String) = when (status) {
    "answered" -> MaterialTheme.colorScheme.primary
    "expired" -> MaterialTheme.colorScheme.tertiary
    "retracted", "suppressed" -> MaterialTheme.colorScheme.outline
    else -> MaterialTheme.colorScheme.onSurfaceVariant
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
                StreamRow(s.str("id"), s.str("name"), s.bool("enabled", true), p.str("type"))
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
                        val sample = host.withEngine {
                            val id = it.fireTestPing(row.id)
                            Alarms.schedule(context, it.nextWake(it.clock.now()))
                            id
                        }
                        status = "Test ping fired: $sample"
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
    val i = info ?: return
    var name by remember(i.third) { mutableStateOf(i.third ?: i.first) }

    val checklist = remember(checkTick) { permissionsChecklist(context) { notifPermission.launch(it) } }

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
        DriveSection(host, bump)

        HorizontalDivider()
        OutlinedButton(onClick = { SyncWorker.syncNow(context) }) { Text("Sync now") }

        ScheduleSection(host)
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
            ChecklistItem("Exact alarms", am.canScheduleExactAlarms(), "Allow") {
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

/** "Show schedule" (§10.2): hidden behind an explicit action. */
@Composable
private fun ScheduleSection(host: EngineHost) {
    var shown by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    HorizontalDivider()
    if (!shown) {
        TextButton(onClick = {
            scope.launch {
                lines = host.withEngine { engine ->
                    engine.db.dueSchedule("9999").map {
                        "${localDateTime(engine, parseUtc(it.scheduledUtc))}  ${it.stream}" +
                            (it.suppressedReason?.let { r -> "  [$r]" } ?: "")
                    }
                }
                shown = true
            }
        }) { Text("Show schedule (next 48 h)…") }
    } else {
        Text("Schedule (next 48 h)", style = MaterialTheme.typography.titleMedium)
        for (line in lines) Text(line, style = MaterialTheme.typography.bodySmall)
        if (lines.isEmpty()) Text("Nothing scheduled.")
    }
}
