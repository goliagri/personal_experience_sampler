package pes.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import pes.core.optStr

/**
 * Home's quiet-mode control and ping calendar — both gaps found by the Tier 3
 * C4 charter (`findings/C4-config-surfaces.md`): Android could only set quiet
 * mode indefinitely, showed a timed quiet synced from another device as if it
 * were indefinite, and had no calendar at all despite spec §10.2.
 */
@RunWith(AndroidJUnit4::class)
class HomeQuietCalendarTest {
    @get:Rule
    val rule = createComposeRule()

    private fun home(t: TestHost) {
        rule.setContent {
            var refresh by androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableIntStateOf(0)
            }
            MaterialTheme { HomeScreen(t.host, refresh, { refresh += 1 }) {} }
        }
        rule.awaitText("Quiet: off")
    }

    @Test
    fun quietForADurationSetsAnEndInstantAndShowsIt() {
        val t = TestHost()
        home(t)
        rule.onNodeWithText("For H:MM…").performScrollTo().performClick()
        rule.awaitText("Duration (H:MM)")
        rule.onNodeWithText("Duration (H:MM)").performTextClearance()
        rule.onNodeWithText("Duration (H:MM)").performTextInput("1:30")
        rule.onNodeWithText("Set").performClick()

        rule.waitUntil(5_000) {
            t.host.call { it.quietState().optStr("quiet_until") } != null
        }
        val until = t.host.call { it.quietState().optStr("quiet_until") }
        assertEquals("2026-09-01T20:25:00Z", until) // NOW 18:55Z + 1:30
        rule.awaitText("Quiet: on until 2026-09-01 13:25")
    }

    @Test
    fun quietForRejectsJunkAndKeepsTheDialogOpen() {
        val t = TestHost()
        home(t)
        rule.onNodeWithText("For H:MM…").performScrollTo().performClick()
        rule.awaitText("Duration (H:MM)")
        rule.onNodeWithText("Duration (H:MM)").performTextClearance()
        rule.onNodeWithText("Duration (H:MM)").performTextInput("soon")
        rule.onNodeWithText("Set").performClick()
        rule.awaitText("Enter a duration like 1:30")
        assertEquals(null, t.host.call { it.quietState().optStr("quiet_until") })
    }

    @Test
    fun indefiniteQuietSaysSoAndTurnsOff() {
        val t = TestHost()
        home(t)
        rule.onNodeWithText("Until turned off").performScrollTo().performClick()
        rule.awaitText("Quiet: on until turned off")
        rule.onNodeWithText("Turn off").performClick()
        rule.awaitText("Quiet: off")
    }

    /** A timed quiet set on another device must not read as indefinite. */
    @Test
    fun aSyncedTimedQuietShowsItsEndTime() {
        val t = TestHost()
        t.host.call { it.setQuiet("2026-09-01T21:00:00Z") }
        rule.setContent { MaterialTheme { HomeScreen(t.host, 0, {}) {} } }
        rule.awaitText("Quiet: on until 2026-09-01 14:00")
        assertTrue(rule.onAllNodesWithText("Until turned off").fetchSemanticsNodes().isEmpty())
    }

    /** §10.2: eight week-rows of seven day-cells, cadence only. */
    @Test
    fun pingCalendarRendersEightWeeksAndScoresTheAnsweredDay() {
        val t = TestHost().fire()
        t.host.call { it.answer(Seeds.PING, Seeds.json("""{"tags":["x"]}""")) }
        rule.setContent { MaterialTheme { HomeScreen(t.host, 0, {}) {} } }
        rule.awaitText("Last 8 weeks")
        rule.onNodeWithText("Last 8 weeks").performScrollTo().assertIsDisplayed()
        val cells = rule.onAllNodesWithContentDescription(": ", substring = true).fetchSemanticsNodes()
        assertEquals(56, cells.size)
        // Exactly one day has any answer rate at all, and it is the seeded day.
        val scored = rule.onAllNodesWithContentDescription("% answered", substring = true)
            .fetchSemanticsNodes()
        assertEquals(1, scored.size)
        assertEquals(
            1,
            rule.onAllNodesWithContentDescription("2026-09-01: ", substring = true)
                .fetchSemanticsNodes().size,
        )
        assertTrue(
            rule.onAllNodesWithContentDescription("2026-09-01: 0% answered").fetchSemanticsNodes().isEmpty(),
        )
    }
}
