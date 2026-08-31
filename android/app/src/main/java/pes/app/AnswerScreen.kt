package pes.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
import pes.core.normalizeTag
import pes.core.splitTags
import pes.core.str
import pes.core.TAG_RE

data class AnswerData(
    val streamName: String,
    val scheduled: Long,
    val scheduledLabel: String,
    val expiresAt: Long,
    val loadedAt: Long,
    val fields: List<JsonObject>,
    val status: String,
    val suggestions: Map<String, List<String>>, // fieldId -> recent tags
)

/** The Answer screen's load outcome: never silently blank — a missing
 * stream/survey or a loader crash renders as a message instead. */
sealed class AnswerLoad {
    data object Loading : AnswerLoad()
    data class Failed(val message: String) : AnswerLoad()
    data class Ready(val data: AnswerData) : AnswerLoad()
}

fun answerLoad(engine: Engine, sampleId: String): AnswerLoad = try {
    answerData(engine, sampleId)
} catch (e: Exception) {
    AnswerLoad.Failed("Could not load this ping: $e")
}

private fun answerData(engine: Engine, sampleId: String): AnswerLoad {
    val streamId = sampleId.substringBefore("|")
    val scheduled = parseUtc(sampleId.substringAfter("|"))
    val stream = engine.streamConfig(streamId, scheduled)
        ?: engine.streamConfig(streamId, engine.clock.now())
        ?: return AnswerLoad.Failed(
            "Stream '$streamId' is not in this device's config yet." +
                " Run Sync now in Settings, then reopen this ping."
        )
    val ref = stream.obj("survey")
    val survey = engine.db.survey(ref.str("id"), ref.int("version"))
        ?: return AnswerLoad.Failed(
            "Survey ${ref.str("id")}@${ref.int("version")} has not synced to" +
                " this device yet. Run Sync now in Settings, then reopen this ping."
        )
    val fields = presentedFields(survey, engine.isFullSurvey(sampleId))
    val now = engine.clock.now()
    val expiryS = engine.effectiveSettings(streamId, scheduled).int("expiry_minutes") * 60L
    val suggestions = fields.filter { it.str("type") == "tags" }.associate { f ->
        val vocab = f.optStr("vocab") ?: "${survey.str("id")}.${f.str("id")}"
        f.str("id") to engine.db.suggestTags(vocab, "", limit = 12)
    }
    return AnswerLoad.Ready(
        AnswerData(
            streamName = stream.str("name"),
            scheduled = scheduled,
            scheduledLabel = localDateTime(engine, scheduled),
            expiresAt = scheduled + expiryS,
            loadedAt = now,
            fields = fields,
            status = engine.db.sampleRow(sampleId)?.str("status") ?: "pending",
            suggestions = suggestions,
        )
    )
}

/**
 * Single vertically scrolling page (§10.3): header with stream name and
 * scheduled time (LATE banner when applicable), fields in schema order, tags
 * first focused, Submit at the bottom with the scheduled time repeated next
 * to it; Snooze and Skip as secondary actions at the top. No animations.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnswerScreen(
    host: EngineHost,
    sampleId: String,
    @Suppress("UNUSED_PARAMETER") fromBacklog: Boolean,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val load by produceState<AnswerLoad>(AnswerLoad.Loading, sampleId) {
        value = host.withEngine { answerLoad(it, sampleId) }
    }
    val d = when (val l = load) {
        is AnswerLoad.Loading -> {
            Text("Loading…", Modifier.padding(16.dp))
            return
        }
        is AnswerLoad.Failed -> {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Can't answer this ping", style = MaterialTheme.typography.titleMedium)
                Text(l.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = onDone) { Text("Back") }
            }
            return
        }
        is AnswerLoad.Ready -> l.data
    }
    // Keyed on the sample: navigating to a different ping must start from a
    // clean form, not inherit the previous one's half-typed values. Saveable,
    // so a rotation or a font-scale change does not throw the answer away
    // (Tier 3 charter C6 F6) — an answer is a minute of the owner's day and
    // the ping cannot be re-asked.
    val values = rememberSaveable(sampleId, saver = stringMapSaver) { mutableStateMapOf<String, String>() }
    val multi = rememberSaveable(sampleId, saver = stringSetMapSaver) { mutableStateMapOf<String, Set<String>>() }
    var errors by remember(sampleId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var notice by remember(sampleId) { mutableStateOf<String?>(null) }
    val firstTagsFocus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    // The IME's "next" used to walk into the choice rows and, pressed again,
    // silently select their first option — an answer the user never gave
    // (Tier 3 charter C6 F3). Chain the typable fields explicitly instead.
    val typable = remember(sampleId, d.fields) {
        d.fields.withIndex().filter { (_, f) ->
            f.str("type") == "text" || f.str("type") == "tags" ||
                (f.str("type") == "number" && f.optStr("display") != "slider")
        }.map { it.index }
    }
    val focusers = remember(sampleId, d.fields) { typable.associateWith { FocusRequester() } }
    fun imeFor(index: Int): ImeAction =
        if (typable.lastOrNull() == index) ImeAction.Done else ImeAction.Next
    fun advance(index: Int) {
        val next = typable.firstOrNull { it > index }
        if (next == null) focusManager.clearFocus() else focusers[next]?.requestFocus()
    }
    fun focusMod(index: Int): Modifier =
        focusers[index]?.let { Modifier.focusRequester(it) } ?: Modifier

    // Lateness is the clock's business, not the route's: a form left open
    // across expiry must grow the banner and lose Snooze/Skip, and a ping
    // opened from History while it is still live must NOT be banner-ed
    // (Tier 3 charter C2 F1/F2; the desktop client decides the same way).
    var now by remember(sampleId) { mutableStateOf(d.loadedAt) }
    LaunchedEffect(sampleId) {
        while (true) {
            kotlinx.coroutines.delay(5_000)
            now = host.withEngine { it.clock.now() }
        }
    }
    val late = now >= d.expiresAt
    val lateAgo = ((now - d.scheduled) / 3600).let { h ->
        if (h > 0) "$h h ago" else "${(now - d.scheduled) / 60} min ago"
    }

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
                    val tags = splitTags(values[id].orEmpty())
                    val bad = tags.filter { !it.matches(TAG_RE) }
                    val curated = f["curated"] as? JsonArray
                    // Tags are folded on ingest, so the curated list must be
                    // compared folded too or a capitalised entry never matches.
                    val allowed = curated?.map { normalizeTag((it as JsonPrimitive).content) }?.toSet()
                    val notAllowed = if (allowed != null) tags.filter { it !in allowed } else emptyList()
                    when {
                        bad.isNotEmpty() ->
                            errs[id] = "Invalid tag \"${bad.first().take(24)}${if (bad.first().length > 24) "…" else ""}\"" +
                                " — letters, digits, . _ - only, up to 64 characters"
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

    // Two-part frame: the fields scroll, Submit sits in a bar pinned above the
    // keyboard. `adjustResize` (manifest) already shrinks the window for the
    // IME, so ANY `imePadding()` here subtracts the keyboard a second time —
    // that is what collapsed the form to a strip (Tier 3 charter C6 F1/F2);
    // and a Submit at the end of the scroll was always one gesture away (C1 F3).
    Column(Modifier.fillMaxSize()) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(d.streamName, style = MaterialTheme.typography.headlineSmall)
        Text("Scheduled ${d.scheduledLabel}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (late) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    "LATE — originally ${d.scheduledLabel}, $lateAgo. This answer will be marked late.",
                    Modifier.padding(8.dp).fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        if (!late) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        host.tryWithEngine {
                            val r = it.snooze(sampleId)
                            Alarms.schedule(context, it.nextWake(it.clock.now()))
                            r
                        }.onSuccess { refusal ->
                            if (refusal == null) onDone() else notice = snoozeRefusalText(refusal)
                        }.onFailure { notice = "Could not snooze: $it" }
                    }
                }) { Text("Snooze") }
                OutlinedButton(onClick = {
                    scope.launch {
                        host.tryWithEngine { it.skip(sampleId) }
                            .onSuccess { onDone() }
                            .onFailure { notice = "Could not skip: $it" }
                    }
                }) { Text("Skip") }
            }
        }
        // Outside the Snooze/Skip block: a save failure has to be visible on a
        // late sample too.
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        for ((i, f) in d.fields.withIndex()) {
            val id = f.str("id")
            val err = errors[id]
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (f.str("type")) {
                    "text" -> {
                        val single = !f.bool("multiline", false)
                        OutlinedTextField(
                            value = values[id].orEmpty(),
                            onValueChange = { values[id] = it },
                            label = { Text(f.str("label")) },
                            singleLine = single,
                            isError = err != null,
                            keyboardOptions = if (single) {
                                KeyboardOptions(imeAction = imeFor(i))
                            } else {
                                KeyboardOptions.Default
                            },
                            keyboardActions = KeyboardActions(
                                onNext = { advance(i) },
                                onDone = { focusManager.clearFocus() },
                            ),
                            modifier = focusMod(i).fillMaxWidth(),
                        )
                    }
                    "tags" -> {
                        val modifier = if (i == 0) {
                            focusMod(i).fillMaxWidth().focusRequester(firstTagsFocus)
                        } else {
                            focusMod(i).fillMaxWidth()
                        }
                        OutlinedTextField(
                            value = values[id].orEmpty(),
                            onValueChange = { values[id] = it },
                            label = { Text("${f.str("label")} (space-separated)") },
                            isError = err != null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                autoCorrectEnabled = false,
                                imeAction = imeFor(i),
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { advance(i) },
                                onDone = { focusManager.clearFocus() },
                            ),
                            modifier = modifier,
                        )
                        val prefix = values[id].orEmpty().substringAfterLast(" ")
                        val shown = d.suggestions[id].orEmpty()
                            .filter { prefix.isEmpty() || it.startsWith(prefix) }
                            .take(6)
                        if (shown.isNotEmpty()) {
                            // FlowRow, not Row: a Row squeezes overflowing chips to slivers.
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
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
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = if (f.bool("integer", false)) {
                                        KeyboardType.Number
                                    } else {
                                        KeyboardType.Decimal
                                    },
                                    imeAction = imeFor(i),
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { advance(i) },
                                    onDone = { focusManager.clearFocus() },
                                ),
                                modifier = focusMod(i).fillMaxWidth(),
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
                        // spec §7: display ∈ radio | checkbox | dropdown | chips | yesno,
                        // defaulting by cardinality — same rule as the desktop client.
                        val display = f.optStr("display") ?: if (single) "radio" else "checkbox"
                        val pick = { value: String ->
                            val selected = value in multi[id].orEmpty()
                            multi[id] = when {
                                single -> setOf(value)
                                selected -> multi[id].orEmpty() - value
                                else -> multi[id].orEmpty() + value
                            }
                        }
                        when (display) {
                            "dropdown" -> {
                                var open by remember { mutableStateOf(false) }
                                val chosen = multi[id].orEmpty().firstOrNull()
                                OutlinedButton(onClick = { open = true }) {
                                    Text(options.firstOrNull { it.first == chosen }?.second ?: "Choose…")
                                }
                                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                    for ((value, label) in options) {
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = { pick(value); open = false },
                                        )
                                    }
                                }
                            }
                            "chips", "yesno" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for ((value, label) in options) {
                                    val selected = value in multi[id].orEmpty()
                                    FilterChip(
                                        selected = selected,
                                        onClick = { pick(value) },
                                        label = { Text(label) },
                                        leadingIcon = if (selected) {
                                            { Text("✓") }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                            else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for ((value, label) in options) {
                                    val selected = value in multi[id].orEmpty()
                                    Row(
                                        Modifier.fillMaxWidth().selectable(
                                            selected = selected,
                                            role = if (single) Role.RadioButton else Role.Checkbox,
                                            onClick = { pick(value) },
                                        ),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        if (single) {
                                            RadioButton(selected = selected, onClick = null)
                                        } else {
                                            Checkbox(checked = selected, onCheckedChange = null)
                                        }
                                        Text(label, Modifier.padding(start = 4.dp))
                                    }
                                }
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

    }
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = {
                val (answers, errs) = collect()
                errors = errs
                if (errs.isEmpty()) {
                    scope.launch {
                        // Keep the form (and the typed answer) if the write
                        // fails; never take the process down with it (C5 F2).
                        host.tryWithEngine {
                            it.answer(sampleId, answers)
                            Alarms.schedule(context, it.nextWake(it.clock.now()))
                        }.onSuccess {
                            SyncWorker.syncNow(context)
                            onDone()
                        }.onFailure {
                            notice = "Could not save this answer: $it. Nothing was lost — try again."
                        }
                    }
                }
            }) { Text("Submit") }
            Text(
                "for ping at ${d.scheduledLabel}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    // Focus the tags field (§10.3) *and* keep the top of the page in view:
    // requesting focus scrolls the field into the IME-shortened viewport, which
    // pushed the stream name, the scheduled time and the LATE banner off the
    // top — the one thing §10.4 says must be unmissable (Tier 3 charter C6 F8).
    LaunchedEffect(d.fields.firstOrNull()?.optStr("type")) {
        if (d.fields.firstOrNull()?.str("type") == "tags") {
            runCatching { firstTagsFocus.requestFocus() }
            scroll.scrollTo(0)
        }
    }
}


/** Savers for the answer draft, so it survives a configuration change. */
private val stringMapSaver = androidx.compose.runtime.saveable.Saver<
    androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>, List<String>,
>(
    save = { map -> map.entries.flatMap { listOf(it.key, it.value) } },
    restore = { flat ->
        mutableStateMapOf<String, String>().apply {
            flat.chunked(2).forEach { if (it.size == 2) put(it[0], it[1]) }
        }
    },
)

private val stringSetMapSaver = androidx.compose.runtime.saveable.Saver<
    androidx.compose.runtime.snapshots.SnapshotStateMap<String, Set<String>>, List<String>,
>(
    save = { map -> map.entries.flatMap { listOf(it.key, it.value.joinToString("\u0000")) } },
    restore = { flat ->
        mutableStateMapOf<String, Set<String>>().apply {
            flat.chunked(2).forEach {
                if (it.size == 2) {
                    put(it[0], it[1].split("\u0000").filter { v -> v.isNotEmpty() }.toSet())
                }
            }
        }
    },
)
