package pes.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pes.core.bool
import pes.core.str

/** Answer screen (spec §10.3–10.4; PROJECT_PREFERENCES: one page, fast, old
 * samples unmistakable). Screen-level: the engine is real, the clock fake. */
@RunWith(AndroidJUnit4::class)
class AnswerScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private var done = 0

    private fun show(t: TestHost, sample: String = Seeds.PING, fromBacklog: Boolean = false) {
        rule.setContent { MaterialTheme { AnswerScreen(t.host, sample, fromBacklog) { done += 1 } } }
        rule.awaitText("Submit")
    }

    private fun submit() {
        rule.onNodeWithText("Submit").performClick() // pinned bar, no scrolling needed
    }

    @Test
    fun rendersHeaderFieldsInOrderAndSubmitWithTime() {
        show(TestHost().fire())
        rule.onNodeWithText("Fixed times").assertIsDisplayed()
        rule.onNodeWithText("Scheduled 2026-09-01 12:00").assertIsDisplayed()
        rule.onNodeWithText("Snooze").assertIsDisplayed()
        rule.onNodeWithText("Skip").assertIsDisplayed()
        rule.onNodeWithText("What are you doing? (space-separated)").assertIsDisplayed()
        // Schema order is preserved top-to-bottom.
        // Read every label at ONE scroll offset: scrolling between reads moves
        // the others, and the claim is about their order in the page.
        val ys = listOf("What are you doing? (space-separated)", "Mood (1-7)", "Where", "With", "Note")
            .map { rule.onNodeWithText(it).fetchSemanticsNode().positionInRoot.y }
        assertEquals(ys.sorted(), ys)
        rule.onNodeWithText("Anything else?").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("for ping at 2026-09-01 12:00").assertIsDisplayed() // pinned bar
        assertTrue(rule.onAllNodesWithText("LATE", substring = true).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun submitFullSurveyWritesAnsweredEventAndReturns() {
        val t = TestHost().fire()
        show(t)
        rule.onNodeWithText("What are you doing? (space-separated)").performTextInput("coding deep_work")
        rule.onNodeWithText("Mood (1-7)").performScrollTo().performTextInput("5")
        rule.onNodeWithText("home").performScrollTo().performClick()
        rule.onNodeWithText("alone").performScrollTo().performClick()
        rule.onNodeWithText("friends").performScrollTo().performClick()
        rule.onNodeWithText("Note").performScrollTo().performTextInput("fine")
        submit()
        rule.waitUntil(5_000) { done == 1 }
        val ev = t.events(Seeds.PING).last()
        assertEquals("answered", ev.str("ev"))
        assertFalse(ev.bool("partial", false))
        val a = ev["answers"]!!.jsonObject
        assertEquals(listOf("coding", "deep_work"), a["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("5", a["mood"]!!.jsonPrimitive.content)
        assertEquals("home", a["where"]!!.jsonPrimitive.content)
        assertEquals(listOf("alone", "friends"), a["with"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("fine", a["note"]!!.jsonPrimitive.content)
        assertEquals("answered", t.sample(Seeds.PING)!!.str("status"))
    }

    @Test
    fun requiredFieldBlocksSubmitUntilFilled() {
        val t = TestHost(survey = Seeds.survey(requiredMood = true)).fire()
        show(t)
        submit()
        rule.awaitText("Required")
        assertEquals(listOf("fired"), t.evTypes(Seeds.PING))
        assertEquals(0, done)
        rule.onNodeWithText("Mood (1-7)").performScrollTo().performTextInput("4")
        submit()
        rule.waitUntil(5_000) { done == 1 }
        assertEquals(listOf("fired", "answered"), t.evTypes(Seeds.PING))
    }

    @Test
    fun numberValidationMessages() {
        val t = TestHost().fire()
        show(t)
        val mood = rule.onNodeWithText("Mood (1-7)")
        for ((input, message) in listOf("abc" to "Not a number", "2.5" to "Whole number required", "9" to "Max 7.0", "0" to "Min 1.0")) {
            mood.performScrollTo().performTextInput(input)
            submit()
            rule.awaitText(message)
            rule.onNodeWithText("Mood (1-7)").performScrollTo().performTextClearance()
        }
        assertEquals(listOf("fired"), t.evTypes(Seeds.PING))
    }

    @Test
    fun invalidAndCuratedTagsRejected() {
        val t = TestHost(survey = Seeds.survey(curatedTags = true)).fire()
        show(t)
        val tags = rule.onNodeWithText("What are you doing? (space-separated)")
        tags.performTextInput("bad!tag")
        submit()
        rule.awaitText("Invalid tag \"bad!tag\"", substring = true)
        tags.performTextClearance()
        tags.performTextInput("work sleep")
        submit()
        rule.awaitText("Not in curated list: sleep")
        assertEquals(listOf("fired"), t.evTypes(Seeds.PING))
    }

    @Test
    fun choiceSingleReplacesAndMultiToggles() {
        val t = TestHost().fire()
        show(t)
        rule.onNodeWithText("home").performScrollTo().performClick()
        rule.onNodeWithText("Somewhere else").performScrollTo().performClick() // single: replaces
        rule.onNodeWithText("alone").performScrollTo().performClick()
        rule.onNodeWithText("partner").performScrollTo().performClick()
        rule.onNodeWithText("alone").performScrollTo().performClick() // multi: toggles off
        submit()
        rule.waitUntil(5_000) { done == 1 }
        val a = t.events(Seeds.PING).last()["answers"]!!.jsonObject
        assertEquals("other", a["where"]!!.jsonPrimitive.content)
        assertEquals(listOf("partner"), a["with"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun lateSampleShowsBannerAndHidesSnoozeSkip() {
        val t = TestHost().fire().expire()
        show(t)
        rule.onNodeWithText("LATE — originally 2026-09-01 12:00, 3 h ago. This answer will be marked late.")
            .assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Snooze").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("What are you doing? (space-separated)").performTextInput("late_tag")
        submit()
        rule.waitUntil(5_000) { done == 1 }
        val row = t.sample(Seeds.PING)!!
        assertEquals("answered", row.str("status"))
        assertTrue(row.bool("late", false))
    }

    /** C2 F1: the route in does not decide lateness — the clock does. A ping
     * opened from History while it is still live must look live. */
    @Test
    fun historyRouteOnALivePingIsNotBannered() {
        show(TestHost().fire(), fromBacklog = true)
        assertTrue(rule.onAllNodesWithText("LATE", substring = true).fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Snooze").assertIsDisplayed()
    }

    /** C2 F2: a form left open across expiry must grow the banner and lose
     * Snooze/Skip, instead of storing `late: true` behind a live-looking screen. */
    @Test
    fun bannerAppearsWhenTheSampleExpiresWhileTheFormIsOpen() {
        val t = TestHost().fire()
        show(t)
        rule.onNodeWithText("Snooze").assertIsDisplayed()
        t.clock.epoch = Seeds.PING_EPOCH + 61 * 60
        rule.awaitText("LATE — originally", substring = true, timeoutMs = 20_000)
        assertTrue(rule.onAllNodesWithText("Snooze").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("Skip").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("What are you doing? (space-separated)").performTextInput("x")
        submit()
        rule.waitUntil(5_000) { done == 1 }
        assertTrue(t.sample(Seeds.PING)!!.bool("late", false))
    }

    @Test
    fun snoozeButtonLogsAndReturns() {
        val t = TestHost().fire()
        show(t)
        rule.onNodeWithText("Snooze").performClick()
        rule.waitUntil(5_000) { done == 1 }
        assertEquals(listOf("fired", "snoozed"), t.evTypes(Seeds.PING))
    }

    @Test
    fun skipButtonLogsAndReturns() {
        val t = TestHost().fire()
        show(t)
        rule.onNodeWithText("Skip").performClick()
        rule.waitUntil(5_000) { done == 1 }
        assertEquals(listOf("fired", "skipped"), t.evTypes(Seeds.PING))
    }

    @Test
    fun snoozeRefusalShowsNoticeAndStays() {
        val t = TestHost().fire()
        repeat(3) { t.host.call { it.snooze(Seeds.PING) } }
        show(t)
        rule.onNodeWithText("Snooze").performClick()
        rule.awaitText("Snooze refused: no snoozes left")
        assertEquals(0, done)
        assertEquals(3, t.evTypes(Seeds.PING).count { it == "snoozed" })
    }

    @Test
    fun unknownStreamRendersFailureNotBlank() {
        val t = TestHost().fire()
        rule.setContent { MaterialTheme { AnswerScreen(t.host, "nope|2026-09-01T19:00:00Z", false) { done += 1 } } }
        rule.awaitText("Can't answer this ping")
        rule.onNodeWithText("Stream 'nope' is not in this device's config yet", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Back").performClick()
        assertEquals(1, done)
    }

    @Test
    fun tagSuggestionChipAppendsToField() {
        val t = TestHost().fire()
        t.host.call { it.db.bumpTags("dev.tags", listOf("reading"), "2026-09-01T18:00:00Z") }
        show(t)
        rule.onNodeWithText("reading").performClick()
        rule.onNode(hasText("reading ", substring = true)).assertExists()
        submit()
        rule.waitUntil(5_000) { done == 1 }
        val a = t.events(Seeds.PING).last()["answers"]!!.jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("reading"))), a["tags"])
    }

    @Test
    fun sliderNumberRendersEndLabels() {
        show(TestHost(survey = Seeds.survey(slider = true)).fire())
        rule.onNodeWithText("Mood (1-7)").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("awful").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("great").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun quickPresentationShowsOnlyQuickFields() {
        // full_survey_every_n = 2: index 0 (12:00) is full, index 1 (20:00) quick.
        val t = TestHost(config = Seeds.config(fullEveryN = 2))
        t.at(Seeds.PING_EPOCH + 8 * 3600 + 3) // 20:00 local fired
        assertEquals(listOf("fired"), t.evTypes(Seeds.EVENING))
        show(t, sample = Seeds.EVENING)
        rule.onNodeWithText("What are you doing? (space-separated)").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Mood (1-7)").fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("Where").fetchSemanticsNodes().isEmpty())
    }
}
