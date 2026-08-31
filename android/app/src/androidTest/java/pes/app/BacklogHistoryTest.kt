package pes.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pes.core.str

/** Backlog and History (spec §10.2, §10.4): late answers only from here. */
@RunWith(AndroidJUnit4::class)
class BacklogHistoryTest {
    @get:Rule
    val rule = createComposeRule()

    private val pushed = mutableListOf<Screen>()

    @Test
    fun backlogGroupsByStreamWithOriginalTimeAndAnswer() {
        val t = TestHost().fire().expire()
        rule.setContent { MaterialTheme { BacklogScreen(t.host, 0) { pushed += it } } }
        rule.awaitText("Backlog")
        rule.onNodeWithText("These pings have expired. Answers will be marked late.").assertIsDisplayed()
        rule.onNodeWithText("Fixed times").assertIsDisplayed()
        rule.onNodeWithText("2026-09-01 12:00").assertIsDisplayed()
        rule.onNodeWithText("expired").assertIsDisplayed()
        rule.onNodeWithText("Answer").performClick()
        assertEquals(listOf<Screen>(Screen.Answer(Seeds.PING, fromBacklog = true)), pushed)
    }

    @Test
    fun backlogEmptyState() {
        val t = TestHost()
        rule.setContent { MaterialTheme { BacklogScreen(t.host, 0) { pushed += it } } }
        rule.awaitText("Backlog is empty.")
    }

    @Test
    fun backlogExcludesSamplesOlderThanWindow() {
        val t = TestHost().fire()
        t.at(Seeds.PING_EPOCH + 13 * 3600) // backlog_hours 12: 12:00 falls out; 20:00 (unobserved) stays
        rule.setContent { MaterialTheme { BacklogScreen(t.host, 0) { pushed += it } } }
        rule.awaitText("2026-09-01 20:00")
        rule.onNodeWithText("unobserved").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("2026-09-01 12:00").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun historyListsStatusesFiltersAndActions() {
        val t = TestHost().fire().expire()
        rule.setContent { MaterialTheme { HistoryScreen(t.host, 0) { pushed += it } } }
        rule.awaitText("History")
        rule.onNodeWithText("2026-09-01 12:00  Fixed times").assertIsDisplayed()
        rule.onNodeWithText("Answer late").performClick()
        assertEquals(listOf<Screen>(Screen.Answer(Seeds.PING, fromBacklog = true)), pushed)

        rule.onNodeWithText("answered").performClick() // filter chip
        rule.awaitText("Nothing yet.")
        rule.onNodeWithText("expired").performClick()
        rule.awaitText("2026-09-01 12:00  Fixed times")
    }

    @Test
    fun historyRetractsAnAnsweredSample() {
        val t = TestHost().fire()
        t.host.call { it.answer(Seeds.PING, Seeds.json("""{"tags":["x"]}""")) }
        rule.setContent { MaterialTheme { HistoryScreen(t.host, 0) { pushed += it } } }
        rule.awaitText("Retract")
        rule.onNodeWithText("Retract").performClick()
        rule.awaitText("retracted")
        assertEquals("retracted", t.sample(Seeds.PING)!!.str("status"))
        assertEquals("answered", t.sample(Seeds.PING)!!.str("prior_status"))
        assertTrue(rule.onAllNodesWithText("Retract").fetchSemanticsNodes().isEmpty())
    }
}
