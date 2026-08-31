package pes.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pes.core.bool
import pes.core.str

/** Streams (read-only + test ping) and Settings (identity, checklist, schedule). */
@RunWith(AndroidJUnit4::class)
class StreamsSettingsTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun streamsListAndFireTestPing() {
        val t = TestHost()
        rule.setContent { MaterialTheme { StreamsScreen(t.host, 0) {} } }
        rule.awaitText("Streams")
        rule.onNodeWithText("Fixed times (fixed_times times_local=[\"12:00\",\"20:00\"])").assertIsDisplayed()
        rule.onNodeWithText("Disabled stream (fixed_interval interval_minutes=60) — disabled").assertIsDisplayed()
        assertEquals(1, rule.onAllNodesWithText("Fire test ping now").fetchSemanticsNodes().size)
        rule.onNodeWithText("Fire test ping now").performClick()
        rule.awaitText("Test ping", substring = true)
        val rows = t.host.call { it.db.sampleRows(stream = "fixed") }
        assertEquals(1, rows.size)
        val fired = t.events(rows[0].str("sample")).first { it.str("ev") == "fired" }
        assertTrue(fired.bool("test", false))
        assertEquals("fixed|2026-09-01T18:55:00Z", rows[0].str("sample"))
    }

    @Test
    fun settingsShowsIdentityChecklistAndSchedule() {
        val t = TestHost()
        rule.setContent { MaterialTheme { SettingsScreen(t.host, 0) {} } }
        rule.awaitText("Settings")
        rule.onNodeWithText("Device id: emu-test").assertIsDisplayed()
        rule.onNodeWithText("Timezone: America/Los_Angeles (edited on desktop)").assertIsDisplayed()
        rule.onNodeWithText("Permissions checklist").assertIsDisplayed()
        for (label in listOf("Notifications", "Exact alarms", "Battery optimization exempt")) {
            assertEquals(1, rule.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().size, label)
        }
        rule.onNodeWithText("Show schedule (next 48 h)…").performScrollTo().performClick()
        rule.awaitText("2026-09-01 12:00  Fixed times")
        rule.onNodeWithText("2026-09-01 20:00  Fixed times").performScrollTo().assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("off", substring = false).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun deviceNameIsSaved() {
        val t = TestHost()
        rule.setContent { MaterialTheme { SettingsScreen(t.host, 0) {} } }
        rule.awaitText("Device name")
        rule.onNodeWithText("Device name").performTextClearance()
        rule.onNodeWithText("Device name").performTextInput("pixel")
        rule.onNodeWithText("Set").performClick()
        rule.waitUntil(5_000) { t.host.call { it.db.kvGet("device", "name") } == "pixel" }
    }
}
