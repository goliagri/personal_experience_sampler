package pes.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
import pes.Engine
import pes.core.bool
import pes.core.objList
import pes.core.fmtUtc
import pes.core.optStr
import pes.core.parseUtc
import pes.core.str

sealed class Screen {
    data object Home : Screen()
    data class Answer(val sampleId: String, val fromBacklog: Boolean) : Screen()
    data object Backlog : Screen()
    data object History : Screen()
    data object Streams : Screen()
    data object Settings : Screen()
}

/** Everything Home shows, read in one engine-thread hop. */
data class HomeData(
    val active: List<Pair<String, String>>, // sampleId -> label
    val backlogCount: Int,
    val streams: List<StreamLine>,
    val quietOn: Boolean,
    val quietUntil: String?, // "indefinite", a UTC instant, or null when off
    val calendar: List<DayCell>, // last 8 weeks, one cell per day
    val lastSync: String?,
    val syncError: String?, // last sync failure, still unresolved
    val notificationsBlocked: Boolean,
    val configIssues: List<String>,
)

/** One day of the Home calendar: [rate] is the answered share of the day's
 * answerable samples, or null when the day has none. */
data class DayCell(val day: java.time.LocalDate, val rate: Float?, val suppressedOnly: Boolean)

/** Last 8 weeks, one cell per day, coloured by answer rate (§10.2). Mirrors
 * the desktop's `HomeScreen._calendar`. */
fun pingCalendar(engine: Engine, now: Long): List<DayCell> {
    val tz = ZoneId.of(engine.configAt(now)?.str("timezone") ?: "UTC")
    val today = Instant.ofEpochSecond(now).atZone(tz).toLocalDate()
    val start = today.minusDays((today.dayOfWeek.value - 1 + 7 * 7).toLong())
    val perDay = mutableMapOf<java.time.LocalDate, MutableList<String>>()
    for (row in engine.db.sampleRows()) {
        val day = Instant.ofEpochSecond(parseUtc(row.str("scheduled_utc"))).atZone(tz).toLocalDate()
        if (day >= start) perDay.getOrPut(day) { mutableListOf() }.add(row.str("status"))
    }
    return (0 until 56).map { i ->
        val day = start.plusDays(i.toLong())
        val statuses = perDay[day].orEmpty()
        val answerable = statuses.filter { it != "suppressed" && it != "retracted" }
        when {
            statuses.isEmpty() -> DayCell(day, null, suppressedOnly = false)
            answerable.isEmpty() -> DayCell(day, null, suppressedOnly = true)
            else -> DayCell(day, answerable.count { it == "answered" }.toFloat() / answerable.size, false)
        }
    }
}

data class StreamLine(val id: String, val name: String, val fired: Int, val answered: Int, val expired: Int)

fun localTime(engine: Engine, epoch: Long): String {
    val tz = engine.configAt(epoch)?.str("timezone") ?: "UTC"
    val local = Instant.ofEpochSecond(epoch).atZone(ZoneId.of(tz))
    return "%02d:%02d".format(local.hour, local.minute)
}

fun localDateTime(engine: Engine, epoch: Long): String {
    val tz = engine.configAt(epoch)?.str("timezone") ?: "UTC"
    val local = Instant.ofEpochSecond(epoch).atZone(ZoneId.of(tz))
    return "%04d-%02d-%02d %02d:%02d".format(local.year, local.monthValue, local.dayOfMonth, local.hour, local.minute)
}

fun homeData(engine: Engine, notificationsBlocked: Boolean = false): HomeData {
    val now = engine.clock.now()
    val active = engine.activeSamples(now).map { row ->
        val id = row.str("sample")
        val name = engine.streamConfig(row.str("stream"), parseUtc(row.str("scheduled_utc")))
            ?.str("name") ?: row.str("stream")
        Pair(id, "$name — ping at ${localTime(engine, parseUtc(row.str("scheduled_utc")))}")
    }
    val config = engine.db.latestConfig()
    val tz = ZoneId.of(config?.str("timezone") ?: "UTC")
    val today = Instant.ofEpochSecond(now).atZone(tz).toLocalDate()
    val streams = (config?.objList("streams") ?: emptyList())
        .filter { it.bool("enabled", true) }
        .map { s ->
            val rows = engine.db.sampleRows(stream = s.str("id")).filter {
                Instant.ofEpochSecond(parseUtc(it.str("scheduled_utc"))).atZone(tz).toLocalDate() == today
            }
            StreamLine(
                s.str("id"), s.str("name"),
                fired = rows.count { it.bool("observed", false) },
                answered = rows.count { it.str("status") == "answered" },
                expired = rows.count { it.str("status") == "expired" },
            )
        }
    return HomeData(
        active = active,
        backlogCount = engine.backlog(now).size,
        streams = streams,
        quietOn = engine.quietActive(now),
        quietUntil = engine.quietState().optStr("quiet_until"),
        calendar = pingCalendar(engine, now),
        lastSync = engine.db.kvGet("sync_meta", "last_sync"),
        syncError = engine.db.kvGet("sync_meta", "last_sync_error")?.takeIf { it.isNotBlank() },
        notificationsBlocked = notificationsBlocked,
        configIssues = engine.configIssues(now),
    )
}

class MainActivity : ComponentActivity() {
    private var openSample by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openSample = intent.getStringExtra(EXTRA_SAMPLE)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openSample = intent.getStringExtra(EXTRA_SAMPLE)
    }

    @Composable
    fun App() {
        val host = pesHost()
        var stack by androidx.compose.runtime.remember { mutableStateOf<List<Screen>>(listOf(Screen.Home)) }
        var refresh by androidx.compose.runtime.remember { mutableIntStateOf(0) }
        val bump: () -> Unit = { refresh += 1 }

        // Pings fire on the engine thread while the activity may be paused;
        // re-query on every resume so Home never shows a stale "no active ping".
        val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
        androidx.compose.runtime.DisposableEffect(lifecycle) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
                if (ev == androidx.lifecycle.Lifecycle.Event.ON_RESUME) bump()
            }
            lifecycle.addObserver(obs)
            onDispose { lifecycle.removeObserver(obs) }
        }

        // A ping can fire while the user is *looking* at a screen — Home then
        // claims "No active ping" while the notification says otherwise
        // (Tier 3 charter C4 F4), and the quiet-mode auto-revert and a
        // finishing sync are invisible the same way. Re-query on a slow tick
        // while the activity is resumed; this is not the "no polling" rule,
        // which is about wakeups, and it stops the moment the screen does.
        androidx.compose.runtime.LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                while (true) {
                    kotlinx.coroutines.delay(10_000)
                    bump()
                }
            }
        }

        // A tapped notification routes straight to the Answer screen (§10.4:
        // notifications never lead to late samples, so fromBacklog = false).
        androidx.compose.runtime.LaunchedEffect(openSample) {
            openSample?.let { sample ->
                openSample = null
                stack = listOf(Screen.Home, Screen.Answer(sample, fromBacklog = false))
            }
        }

        val screen = stack.last()
        val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }
        val push: (Screen) -> Unit = { stack = stack + it }

        androidx.activity.compose.BackHandler(enabled = stack.size > 1) { pop() }

        MaterialTheme(
            colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) {
                androidx.compose.material3.darkColorScheme()
            } else {
                androidx.compose.material3.lightColorScheme()
            }
        ) {
            Scaffold { padding ->
                Column(Modifier.padding(padding).fillMaxSize()) {
                    when (screen) {
                        is Screen.Home -> HomeScreen(host, refresh, bump, push)
                        is Screen.Answer -> AnswerScreen(host, screen.sampleId, screen.fromBacklog, onDone = { bump(); pop() })
                        is Screen.Backlog -> BacklogScreen(host, refresh, push)
                        is Screen.History -> HistoryScreen(host, refresh, push)
                        is Screen.Streams -> StreamsScreen(host, refresh, bump)
                        is Screen.Settings -> SettingsScreen(host, refresh, bump)
                    }
                }
            }
        }
    }
}

@Composable
fun HomeScreen(host: EngineHost, refresh: Int, bump: () -> Unit, push: (Screen) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var notice by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    fun act(block: (Engine) -> Unit) {
        scope.launch {
            // A failing engine call must show up here, not kill the app (C5 F2).
            host.tryWithEngine(block).onFailure { notice = "That did not work: $it" }
            bump()
        }
    }
    val data by produceState<HomeData?>(null, refresh) {
        val blocked = !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        value = host.withEngine { homeData(it, blocked) }
    }
    val d = data ?: return
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Experience Sampler", style = MaterialTheme.typography.headlineSmall)

        for ((sampleId, label) in d.active) {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { push(Screen.Answer(sampleId, fromBacklog = false)) }) { Text("Answer") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                val refusal = host.withEngine {
                                    val r = it.snooze(sampleId)
                                    Alarms.schedule(context, it.nextWake(it.clock.now()))
                                    r
                                }
                                notice = refusal?.let { snoozeRefusalText(it) }
                                bump()
                            }
                        }) { Text("Snooze") }
                        OutlinedButton(onClick = { act { it.skip(sampleId) } }) { Text("Skip") }
                    }
                }
            }
        }
        if (d.active.isEmpty()) Text("No active ping.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (d.backlogCount > 0) {
            // Not all of them are expired — unobserved and skipped pings land
            // here too, and collapsing those into "expired" throws away exactly
            // the distinction the accounting model exists for (C3 F2).
            TextButton(onClick = { push(Screen.Backlog) }) { Text("Backlog: ${d.backlogCount} unanswered ping(s)") }
        }

        HorizontalDivider()
        Text("Streams (today: fired / answered / expired)", style = MaterialTheme.typography.titleSmall)
        for (s in d.streams) {
            Text("${s.name}   ${s.fired} / ${s.answered} / ${s.expired}")
        }
        if (d.streams.isEmpty()) Text("No streams configured. Configure on desktop; they arrive at next sync.")

        HorizontalDivider()
        // Quiet mode has two variants (preferences §2, spec §10.2): "until
        // turned off" and "for H:MM", which auto-reverts. Android used to
        // offer only the first, and showed a timed quiet synced from another
        // device as if it were indefinite (C4 F2/F3).
        var quietDialog by remember { mutableStateOf(false) }
        if (d.quietOn) {
            val until = d.quietUntil
            Text(
                if (until == null || until == "indefinite") {
                    "Quiet: on until turned off"
                } else {
                    "Quiet: on until ${localDateTime(host.engine, parseUtc(until))}"
                }
            )
            OutlinedButton(onClick = { act { it.setQuiet(null) } }) { Text("Turn off") }
        } else {
            Text("Quiet: off")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { act { it.setQuiet("indefinite") } }) {
                    Text("Until turned off")
                }
                OutlinedButton(onClick = { quietDialog = true }) { Text("For H:MM…") }
            }
        }
        if (quietDialog) {
            QuietForDialog(
                onDismiss = { quietDialog = false },
                onSet = { seconds ->
                    quietDialog = false
                    act { it.setQuiet(fmtUtc(it.clock.now() + seconds)) }
                },
            )
        }
        HorizontalDivider()
        PingCalendar(d.calendar)
        Text(
            "Last sync: ${d.lastSync ?: "never"}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        // A sync that has been failing for days used to look exactly like a
        // healthy one on the only screen seen daily (C5 F3), and a ping with
        // notifications switched off is logged correctly but never shown (C5 F4).
        d.syncError?.let {
            Text("Sync failing: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (d.configIssues.isNotEmpty()) {
            Text(
                "Config problems on this device — the rest of the config still runs:",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            for (issue in d.configIssues) {
                Text("• $issue", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (d.notificationsBlocked) {
            Text(
                "Notifications are off — pings are still recorded, but you will not be told.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { push(Screen.History) }) { Text("History") }
            TextButton(onClick = { push(Screen.Streams) }) { Text("Streams") }
            TextButton(onClick = { push(Screen.Settings) }) { Text("Settings") }
        }
    }
}

/** "Quiet for H:MM" (preferences §2): a duration, not a clock time — the
 * desktop asks for the same thing in a `simpledialog`. */
@Composable
private fun QuietForDialog(onDismiss: () -> Unit, onSet: (Long) -> Unit) {
    var raw by remember { mutableStateOf("1:00") }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Quiet mode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = raw,
                    onValueChange = { raw = it; error = null },
                    label = { Text("Duration (H:MM)") },
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val parts = raw.trim().split(":")
                val h = parts.getOrNull(0)?.toIntOrNull()
                val m = if (parts.size > 1) parts[1].toIntOrNull() else 0
                if (h == null || m == null || h < 0 || m < 0 || m > 59 || (h == 0 && m == 0)) {
                    error = "Enter a duration like 1:30"
                } else {
                    onSet(h * 3600L + m * 60L)
                }
            }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}


/** The ping calendar (§10.2): eight week-rows of seven day-cells, greener the
 * higher that day's answer rate. Cadence only — no survey content, per
 * PROJECT_PREFERENCES §2 ("the app should be fairly dumb"). */
@Composable
private fun PingCalendar(cells: List<DayCell>) {
    if (cells.isEmpty()) return
    val today = cells.lastOrNull()?.day
    Text("Last 8 weeks", style = MaterialTheme.typography.titleSmall)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for (week in 0 until 8) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (weekday in 0 until 7) {
                    val c = cells.getOrNull(week * 7 + weekday) ?: continue
                    val color = when {
                        c.suppressedOnly -> MaterialTheme.colorScheme.surfaceVariant
                        c.rate == null -> MaterialTheme.colorScheme.surfaceContainerHighest
                        else -> answerRateColors()[minOf(3, (c.rate * 4).toInt())]
                    }
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .size(16.dp)
                            .then(
                                if (c.day == today) {
                                    Modifier.border(1.dp, MaterialTheme.colorScheme.primary)
                                } else {
                                    Modifier
                                }
                            )
                            .background(color)
                            .semantics {
                                contentDescription = "${c.day}: " + when {
                                    c.suppressedOnly -> "all suppressed"
                                    c.rate == null -> "no pings"
                                    else -> "${(c.rate * 100).toInt()}% answered"
                                }
                            }
                    )
                }
            }
        }
    }
}

/**
 * Same four greens as the desktop calendar, so the two clients read alike —
 * but darkened rather than lightened in dark mode: on a dark surface the
 * light-theme ramp reads inverted, with the palest cell the brightest and the
 * top bucket sinking into the empty-cell grey (Tier 3 charter C6 F5).
 */
@Composable
private fun answerRateColors(): List<androidx.compose.ui.graphics.Color> =
    if (androidx.compose.foundation.isSystemInDarkTheme()) {
        listOf(
            androidx.compose.ui.graphics.Color(0xFF1B3A22),
            androidx.compose.ui.graphics.Color(0xFF2E7D32),
            androidx.compose.ui.graphics.Color(0xFF4CAF50),
            androidx.compose.ui.graphics.Color(0xFFA5D6A7),
        )
    } else {
        listOf(
            androidx.compose.ui.graphics.Color(0xFFC8E6C9),
            androidx.compose.ui.graphics.Color(0xFF81C784),
            androidx.compose.ui.graphics.Color(0xFF4CAF50),
            androidx.compose.ui.graphics.Color(0xFF2E7D32),
        )
    }
