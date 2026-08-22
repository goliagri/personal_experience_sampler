package pes.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
import pes.Engine
import pes.core.bool
import pes.core.objList
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
    val lastSync: String?,
)

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

fun homeData(engine: Engine): HomeData {
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
        lastSync = engine.db.kvGet("sync_meta", "last_sync"),
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
    fun act(block: (Engine) -> Unit) {
        scope.launch {
            host.withEngine(block)
            bump()
        }
    }
    val data by produceState<HomeData?>(null, refresh) {
        value = host.withEngine { homeData(it) }
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
                            act { it.snooze(sampleId); Alarms.schedule(context, it.nextWake(it.clock.now())) }
                        }) { Text("Snooze") }
                        OutlinedButton(onClick = { act { it.skip(sampleId) } }) { Text("Skip") }
                    }
                }
            }
        }
        if (d.active.isEmpty()) Text("No active ping.", color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (d.backlogCount > 0) {
            TextButton(onClick = { push(Screen.Backlog) }) { Text("Backlog: ${d.backlogCount} expired ping(s)") }
        }

        HorizontalDivider()
        Text("Streams (today: fired / answered / expired)", style = MaterialTheme.typography.titleSmall)
        for (s in d.streams) {
            Text("${s.name}   ${s.fired} / ${s.answered} / ${s.expired}")
        }
        if (d.streams.isEmpty()) Text("No streams configured. Configure on desktop; they arrive at next sync.")

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                act { it.setQuiet(if (it.quietActive(it.clock.now())) null else "indefinite") }
            }) { Text(if (d.quietOn) "Quiet: on (tap to turn off)" else "Quiet: off") }
        }
        Text(
            "Last sync: ${d.lastSync ?: "never"}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )

        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { push(Screen.History) }) { Text("History") }
            TextButton(onClick = { push(Screen.Streams) }) { Text("Streams") }
            TextButton(onClick = { push(Screen.Settings) }) { Text("Settings") }
        }
    }
}
