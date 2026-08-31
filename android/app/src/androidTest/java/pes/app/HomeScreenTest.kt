package pes.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

/** Home (spec §10.2): active card, backlog link, stream counts, quiet toggle. */
@RunWith(AndroidJUnit4::class)
class HomeScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val pushed = mutableListOf<Screen>()

    private fun show(t: TestHost) {
        rule.setContent {
            var refresh by remember { mutableIntStateOf(0) }
            MaterialTheme { HomeScreen(t.host, refresh, { refresh += 1 }) { pushed += it } }
        }
        rule.awaitText("Experience Sampler")
    }

    @Test
    fun idleHomeListsEnabledStreamsOnly() {
        show(TestHost())
        rule.onNodeWithText("No active ping.").assertIsDisplayed()
        rule.onNodeWithText("Fixed times   0 / 0 / 0").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Disabled stream", substring = true).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Quiet: off").assertIsDisplayed()
        rule.onNodeWithText("Last sync: never").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Backlog", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun noConfigShowsSetupHint() {
        show(TestHost(config = Seeds.config(streams = false)))
        rule.onNodeWithText("No streams configured. Configure on desktop; they arrive at next sync.").assertIsDisplayed()
    }

    @Test
    fun activeCardOffersAnswerSnoozeSkip() {
        val t = TestHost().fire()
        show(t)
        rule.onNodeWithText("Fixed times — ping at 12:00").assertIsDisplayed()
        rule.onNodeWithText("Fixed times   1 / 0 / 0").assertIsDisplayed()
        rule.onNodeWithText("Answer").performClick()
        assertEquals(listOf<Screen>(Screen.Answer(Seeds.PING, fromBacklog = false)), pushed)

        rule.onNodeWithText("Skip").performClick()
        rule.awaitText("No active ping.")
        assertEquals(listOf("fired", "skipped"), t.evTypes(Seeds.PING))
    }

    @Test
    fun snoozeFromHomeAndRefusalNotice() {
        val t = TestHost().fire()
        repeat(3) { t.host.call { it.snooze(Seeds.PING) } }
        show(t)
        rule.onNodeWithText("Snooze").performClick()
        rule.awaitText("Snooze refused: no snoozes left")
        assertEquals(3, t.evTypes(Seeds.PING).count { it == "snoozed" })
    }

    @Test
    fun expiredSampleShowsBacklogLinkNotCard() {
        val t = TestHost().fire().expire()
        show(t)
        rule.onNodeWithText("No active ping.").assertIsDisplayed()
        rule.onNodeWithText("Fixed times   1 / 0 / 1").assertIsDisplayed()
        rule.onNodeWithText("Backlog: 1 unanswered ping(s)").performClick()
        assertEquals(listOf<Screen>(Screen.Backlog), pushed)
    }

    @Test
    fun quietToggleFlipsEngineState() {
        val t = TestHost()
        show(t)
        rule.onNodeWithText("Until turned off").performClick()
        rule.awaitText("Quiet: on until turned off")
        assertTrue(t.host.call { it.quietActive(it.clock.now()) })
        rule.onNodeWithText("Turn off").performClick()
        rule.awaitText("Quiet: off")
        assertTrue(!t.host.call { it.quietActive(it.clock.now()) })
    }

    @Test
    fun navigationLinks() {
        show(TestHost())
        rule.onNodeWithText("History").performClick()
        rule.onNodeWithText("Streams").performClick()
        rule.onNodeWithText("Settings").performClick()
        assertEquals(listOf(Screen.History, Screen.Streams, Screen.Settings), pushed)
    }
}
