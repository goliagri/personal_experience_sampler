package pes.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import pes.Engine
import pes.core.bool
import pes.core.int
import pes.core.obj
import pes.core.objList
import pes.core.optStr
import pes.core.parseUtc
import pes.core.presentedFields
import pes.core.str

private val TAG_RE = Regex("[A-Za-z0-9_.\\-]{1,64}")

data class AnswerData(
    val streamName: String,
    val scheduled: Long,
    val scheduledLabel: String,
    val late: Boolean,
    val lateAgo: String,
    val fields: List<JsonObject>,
    val status: String,
    val suggestions: Map<String, List<String>>, // fieldId -> recent tags
)

fun answerData(engine: Engine, sampleId: String): AnswerData? {
    val streamId = sampleId.substringBefore("|")
    val scheduled = parseUtc(sampleId.substringAfter("|"))
    val stream = engine.streamConfig(streamId, scheduled)
        ?: engine.streamConfig(streamId, engine.clock.now()) ?: return null
    val ref = stream.obj("survey")
    val survey = engine.db.survey(ref.str("id"), ref.int("version")) ?: return null
    val fields = presentedFields(survey, engine.isFullSurvey(sampleId))
    val now = engine.clock.now()
    val expiryS = engine.effectiveSettings(streamId, scheduled).int("expiry_minutes") * 60L
    val late = scheduled + expiryS <= now
    val agoH = (now - scheduled) / 3600
    val agoM = ((now - scheduled) % 3600) / 60
    val suggestions = fields.filter { it.str("type") == "tags" }.associate { f ->
        val vocab = f.optStr("vocab") ?: "${survey.str("id")}.${f.str("id")}"
        f.str("id") to engine.db.suggestTags(vocab, "", limit = 12)
    }
    return AnswerData(
        streamName = stream.str("name"),
        scheduled = scheduled,
        scheduledLabel = localDateTime(engine, scheduled),
        late = late,
        lateAgo = if (agoH > 0) "$agoH h ago" else "$agoM min ago",
        fields = fields,
        status = engine.db.sampleRow(sampleId)?.str("status") ?: "pending",
        suggestions = suggestions,
    )
}

/**
 * Single vertically scrolling page (§10.3): header with stream name and
 * scheduled time (LATE banner when applicable), fields in schema order, tags
 * first focused, Submit at the bottom with the scheduled time repeated next
 * to it; Snooze and Skip as secondary actions at the top. No animations.
 */
@Composable
fun AnswerScreen(host: EngineHost, sampleId: String, fromBacklog: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data by produceState<AnswerData?>(null, sampleId) {
        value = host.withEngine { answerData(it, sampleId) }
    }
    val d = data ?: return
    val values = remember { mutableStateMapOf<String, String>() }
    val multi = remember { mutableStateMapOf<String, Set<String>>() }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val firstTagsFocus = remember { FocusRequester() }

    fun collect(): Pair<JsonObject, Map<String, String>> {
        val out = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        val errs = mutableMapOf<String, String>()
        for (f in d.fields) {
            val id = f.str("id")
            val required = f.bool("required", false)
            when (f.str("type")) {
                "text" -> {
                    val v = values[id].orEmpty()
                    if (v.isEmpty()) {
                        if (required) errs[id] = "Required"
                    } else {
                        out[id] = JsonPrimitive(v)
                    }
                }
                "number" -> {
                    val v = values[id].orEmpty().trim()
                    if (v.isEmpty()) {
                        if (required) errs[id] = "Required"
                    } else {
                        val n = v.toDoubleOrNull()
                        val integer = f.bool("integer", false)
                        val min = (f["min"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                        val max = (f["max"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                        when {
                            n == null -> errs[id] = "Not a number"
                            integer && n != Math.floor(n) -> errs[id] = "Whole number required"
                            min != null && n < min -> errs[id] = "Min $min"
                            max != null && n > max -> errs[id] = "Max $max"
                            else -> out[id] = if (integer) JsonPrimitive(n.toLong()) else JsonPrimitive(n)
                        }
                    }
                }
                "tags" -> {
                    val tags = values[id].orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    val bad = tags.filter { !it.matches(TAG_RE) }
                    val curated = f["curated"] as? JsonArray
                    val allowed = curated?.map { (it as JsonPrimitive).content }?.toSet()
                    val notAllowed = if (allowed != null) tags.filter { it !in allowed } else emptyList()
                    when {
                        bad.isNotEmpty() -> errs[id] = "Invalid tag: ${bad.first()}"
                        notAllowed.isNotEmpty() -> errs[id] = "Not in curated list: ${notAllowed.first()}"
                        tags.isEmpty() && required -> errs[id] = "Required"
                        tags.isNotEmpty() -> out[id] = JsonArray(tags.map { JsonPrimitive(it) })
                    }
                }
                "choice" -> {
                    val single = f.optStr("cardinality") != "multi"
                    val selected = multi[id].orEmpty()
                    when {
                        selected.isEmpty() && required -> errs[id] = "Required"
                        selected.isNotEmpty() ->
                            out[id] = if (single) {
                                JsonPrimitive(selected.first())
                            } else {
                                JsonArray(selected.sorted().map { JsonPrimitive(it) })
                            }
                    }
                }
            }
        }
        return Pair(JsonObject(out), errs)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(d.streamName, style = MaterialTheme.typography.headlineSmall)
        Text("Scheduled ${d.scheduledLabel}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (d.late || fromBacklog) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    "LATE — originally ${d.scheduledLabel}, ${d.lateAgo}. This answer will be marked late.",
                    Modifier.padding(8.dp).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        if (!d.late && !fromBacklog) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        host.withEngine {
                            it.snooze(sampleId)
                            Alarms.schedule(context, it.nextWake(it.clock.now()))
                        }
                        onDone()
                    }
                }) { Text("Snooze") }
                OutlinedButton(onClick = {
                    scope.launch {
                        host.withEngine { it.skip(sampleId) }
                        onDone()
                    }
                }) { Text("Skip") }
            }
        }

        for ((i, f) in d.fields.withIndex()) {
            val id = f.str("id")
            val err = errors[id]
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (f.str("type")) {
                    "text" -> OutlinedTextField(
                        value = values[id].orEmpty(),
                        onValueChange = { values[id] = it },
                        label = { Text(f.str("label")) },
                        singleLine = !f.bool("multiline", false),
                        isError = err != null,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    "tags" -> {
                        val modifier = if (i == 0) Modifier.fillMaxWidth().focusRequester(firstTagsFocus) else Modifier.fillMaxWidth()
                        OutlinedTextField(
                            value = values[id].orEmpty(),
                            onValueChange = { values[id] = it },
                            label = { Text("${f.str("label")} (space-separated)") },
                            isError = err != null,
                            modifier = modifier,
                        )
                        val prefix = values[id].orEmpty().substringAfterLast(" ")
                        val shown = d.suggestions[id].orEmpty()
                            .filter { prefix.isEmpty() || it.startsWith(prefix) }
                            .take(6)
                        if (shown.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (tag in shown) {
                                    FilterChip(
                                        selected = false,
                                        onClick = {
                                            val head = values[id].orEmpty().substringBeforeLast(" ", "")
                                            values[id] = (if (head.isEmpty()) "$tag " else "$head $tag ")
                                        },
                                        label = { Text(tag) },
                                    )
                                }
                            }
                        }
                    }
                    "number" -> {
                        val min = (f["min"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                        val max = (f["max"] as? JsonPrimitive)?.content?.toDoubleOrNull()
                        if (f.optStr("display") == "slider" && min != null && max != null) {
                            val current = values[id]?.toFloatOrNull() ?: min.toFloat()
                            Text(f.str("label") + (values[id]?.let { ": $it" } ?: ""))
                            Slider(
                                value = current,
                                onValueChange = {
                                    values[id] = if (f.bool("integer", false)) {
                                        it.toInt().toString()
                                    } else {
                                        "%.1f".format(it)
                                    }
                                },
                                valueRange = min.toFloat()..max.toFloat(),
                                steps = if (f.bool("integer", false)) (max - min).toInt() - 1 else 0,
                            )
                            val labels = f["end_labels"] as? JsonArray
                            if (labels != null && labels.size == 2) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text((labels[0] as JsonPrimitive).content, style = MaterialTheme.typography.bodySmall)
                                    Text((labels[1] as JsonPrimitive).content, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = values[id].orEmpty(),
                                onValueChange = { values[id] = it },
                                label = { Text(f.str("label")) },
                                singleLine = true,
                                isError = err != null,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    "choice" -> {
                        Text(f.str("label"))
                        val single = f.optStr("cardinality") != "multi"
                        val options = (f["options"] as? JsonArray)?.map { opt ->
                            if (opt is JsonObject) {
                                Pair(opt.str("value"), opt.optStr("label") ?: opt.str("value"))
                            } else {
                                Pair((opt as JsonPrimitive).content, opt.content)
                            }
                        }.orEmpty()
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for ((value, label) in options) {
                                val selected = value in multi[id].orEmpty()
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        multi[id] = when {
                                            single -> setOf(value)
                                            selected -> multi[id].orEmpty() - value
                                            else -> multi[id].orEmpty() + value
                                        }
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                }
                f.optStr("help")?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (err != null) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                val (answers, errs) = collect()
                errors = errs
                if (errs.isEmpty()) {
                    scope.launch {
                        host.withEngine {
                            it.answer(sampleId, answers)
                            Alarms.schedule(context, it.nextWake(it.clock.now()))
                        }
                        SyncWorker.syncNow(context)
                        onDone()
                    }
                }
            }) { Text("Submit") }
            Text(
                "for ping at ${d.scheduledLabel}",
                Modifier.padding(top = 12.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    androidx.compose.runtime.LaunchedEffect(d.fields.firstOrNull()?.optStr("type")) {
        if (d.fields.firstOrNull()?.str("type") == "tags") {
            runCatching { firstTagsFocus.requestFocus() }
        }
    }
}
