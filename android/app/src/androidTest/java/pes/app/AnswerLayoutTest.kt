package pes.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Answer-screen layout and control-type regressions found by the Tier 3 C1
 * charter (`android/tests/exploratory/findings/C1-answer-flow.md`): overflowing
 * tag suggestions, and spec §7's `choice.display` variants, which the Android
 * client used to ignore while the desktop honoured them.
 */
@RunWith(AndroidJUnit4::class)
class AnswerLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(t: TestHost) {
        rule.setContent { MaterialTheme { AnswerScreen(t.host, Seeds.PING, false) {} } }
        rule.awaitText("Submit")
    }

    /** C1 F1: a plain Row squeezed overflowing chips to 9 px and inflated the
     * row to 3× its height; every suggestion must stay readable and tappable. */
    @Test
    fun tagSuggestionsWrapInsteadOfSqueezing() {
        val t = TestHost().fire()
        val tags = listOf("work.writing", "commute", "cooking", "email", "reading", "exercise")
        t.host.call { it.db.bumpTags("dev.tags", tags, "2026-09-01T18:00:00Z") }
        show(t)
        val nodes = tags.map { rule.onNodeWithText(it).fetchSemanticsNode() }
        val screenW = rule.onRoot().fetchSemanticsNode().size.width.toFloat()
        for ((tag, n) in tags.zip(nodes)) {
            val b = n.boundsInRoot
            assertTrue(b.width > 100f, "suggestion '$tag' is only ${b.width} px wide")
            assertTrue(b.right <= screenW, "suggestion '$tag' runs off the right edge")
        }
        // Chips wrap onto more than one line rather than all being crushed into one.
        assertTrue(nodes.map { it.boundsInRoot.top }.distinct().size >= 2)
        // ...and the row does not inflate: no chip is taller than 2× the first.
        val h0 = nodes[0].boundsInRoot.height
        assertTrue(nodes.all { it.boundsInRoot.height <= h0 * 2 })
    }

    /** Suggestions still insert on tap after the layout change. */
    @Test
    fun wrappedSuggestionOnSecondLineStillInserts() {
        val t = TestHost().fire()
        t.host.call {
            it.db.bumpTags("dev.tags", listOf("work.writing", "commute", "cooking", "email", "reading", "exercise"), "2026-09-01T18:00:00Z")
        }
        show(t)
        rule.onNodeWithText("exercise").performClick()
        rule.onNode(hasText("exercise ", substring = true)).assertExists()
    }

    /** C1 F7 / spec §7: single-cardinality choice defaults to radio semantics,
     * multi to checkbox, so the two are distinguishable by more than a fill. */
    @Test
    fun choiceDefaultsToRadioForSingleAndCheckboxForMulti() {
        show(TestHost().fire())
        rule.onNodeWithText("home").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        rule.onNodeWithText("alone").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
    }

    @Test
    fun radioReplacesAndCheckboxToggles() {
        val t = TestHost().fire()
        show(t)
        rule.onNodeWithText("home").performScrollTo().performClick()
        rule.onNodeWithText("Somewhere else").performScrollTo().performClick()
        rule.onNodeWithText("alone").performScrollTo().performClick()
        rule.onNodeWithText("partner").performScrollTo().performClick()
        rule.onNodeWithText("alone").performScrollTo().performClick()
        rule.onNodeWithText("Submit").performClick() // pinned bar, no scrolling needed
        rule.waitUntil(5_000) { t.evTypes(Seeds.PING).contains("answered") }
        val a = t.events(Seeds.PING).last()["answers"]!!.jsonObject
        assertEquals("other", a["where"]!!.jsonPrimitive.content)
        assertEquals(listOf("partner"), a["with"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    /** `display: chips` keeps the compact chip layout but marks the selection. */
    @Test
    fun chipsDisplayShowsACheckOnTheSelectedChip() {
        val t = TestHost(survey = Seeds.survey(whereDisplay = "chips")).fire()
        show(t)
        assertTrue(rule.onAllNodesWithText("✓").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("home").performScrollTo().performClick()
        rule.onNodeWithText("✓").assertExists()
    }

    /** `display: dropdown` renders one control, not four rows. */
    @Test
    fun dropdownDisplayOpensAMenuAndPicks() {
        val t = TestHost(survey = Seeds.survey(whereDisplay = "dropdown")).fire()
        show(t)
        rule.onNodeWithText("Choose…").performScrollTo().assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Somewhere else").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Choose…").performClick()
        rule.awaitText("Somewhere else")
        rule.onNodeWithText("Somewhere else").performClick()
        rule.awaitText("Somewhere else") // now the button label
        rule.onNodeWithText("Submit").performClick() // pinned bar, no scrolling needed
        rule.waitUntil(5_000) { t.evTypes(Seeds.PING).contains("answered") }
        assertEquals(
            "other",
            t.events(Seeds.PING).last()["answers"]!!.jsonObject["where"]!!.jsonPrimitive.content,
        )
    }

    /** C6 F1/F3: Submit lives in a bar pinned below the scrolling fields, so it
     * is on screen without scrolling however long the survey is. */
    @Test
    fun submitIsAlwaysOnScreenWithoutScrolling() {
        show(TestHost().fire())
        rule.onNodeWithText("Submit").assertIsDisplayed()
        rule.onNodeWithText("for ping at 2026-09-01 12:00").assertIsDisplayed()
        // Scroll the fields to the bottom; the bar does not move.
        rule.onNodeWithText("Note").performScrollTo()
        rule.onNodeWithText("Submit").assertIsDisplayed()
    }

    /** C6 F3: the IME's "next" walks typable fields only — it used to land on a
     * radio row and, pressed again, select an option the user never chose. */
    @Test
    fun imeNextSkipsChoiceRowsAndNeverSelectsAnOption() {
        val t = TestHost().fire()
        show(t)
        rule.onNodeWithText("What are you doing? (space-separated)").performTextInput("x")
        repeat(3) { rule.onNodeWithText("What are you doing? (space-separated)").performImeAction() }
        for (option in listOf("home", "work", "out", "Somewhere else", "alone", "partner")) {
            rule.onNodeWithText(option).performScrollTo()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
        }
        rule.onNodeWithText("Submit").performClick()
        rule.waitUntil(5_000) { t.evTypes(Seeds.PING).contains("answered") }
        val a = t.events(Seeds.PING).last()["answers"]!!.jsonObject
        assertTrue("where" !in a, "a choice was selected without the user touching it: $a")
    }

    /** C6 F6: a rotation must not throw away a half-typed answer. */
    @Test
    fun typedDraftSurvivesARecomposition() {
        val t = TestHost().fire()
        val restart = androidx.compose.ui.test.junit4.StateRestorationTester(rule)
        restart.setContent { MaterialTheme { AnswerScreen(t.host, Seeds.PING, false) {} } }
        rule.awaitText("Submit")
        rule.onNodeWithText("What are you doing? (space-separated)").performTextInput("half_typed")
        rule.onNodeWithText("home").performScrollTo().performClick()

        restart.emulateSavedInstanceStateRestore()

        rule.awaitText("Submit")
        rule.onNode(hasText("half_typed", substring = true)).assertExists()
        rule.onNodeWithText("home").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }

    /** C6 blocked on adb (`input text` cannot type non-ASCII): unicode, emoji
     * and RTL text must be rejected as tags with a legible message and stored
     * verbatim in free text — never mangled, never a crash. */
    @Test
    fun unicodeAndEmojiAreRejectedAsTagsAndKeptInFreeText() {
        val t = TestHost().fire()
        show(t)
        val tags = rule.onNodeWithText("What are you doing? (space-separated)")
        tags.performTextInput("café")
        rule.onNodeWithText("Submit").performClick()
        rule.awaitText("letters, digits", substring = true)
        assertEquals(listOf("fired"), t.evTypes(Seeds.PING))

        tags.performTextClearance()
        tags.performTextInput("work")
        val note = "emoji 🙂 rtl مرحبا  trailing   "
        rule.onNodeWithText("Note").performScrollTo().performTextInput(note)
        rule.onNodeWithText("Submit").performClick()
        rule.waitUntil(5_000) { t.evTypes(Seeds.PING).contains("answered") }
        val a = t.events(Seeds.PING).last()["answers"]!!.jsonObject
        assertEquals(note, a["note"]!!.jsonPrimitive.content)
        assertEquals(listOf("work"), a["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    /** A 300-character tag exceeds the §7 charset limit and must be refused. */
    @Test
    fun anOverlongTagIsRefusedWithATruncatedMessage() {
        val t = TestHost().fire()
        show(t)
        rule.onNodeWithText("What are you doing? (space-separated)").performTextInput("a".repeat(300))
        rule.onNodeWithText("Submit").performClick()
        rule.awaitText("up to 64 characters", substring = true)
        val message = rule.onNodeWithText("up to 64 characters", substring = true)
            .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)
            ?.joinToString(" ").orEmpty()
        assertTrue(message.length < 120, "the error message echoed the whole tag: $message")
        assertEquals(listOf("fired"), t.evTypes(Seeds.PING))
    }
}
