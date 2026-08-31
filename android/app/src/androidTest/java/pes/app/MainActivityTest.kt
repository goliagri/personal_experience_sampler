package pes.app

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The real activity over the real app host: navigation plumbing only (the
 * on-device pytest suite covers end-to-end state). */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun launchesToHome() {
        ActivityScenario.launch(MainActivity::class.java).use {
            rule.awaitText("Experience Sampler")
            rule.awaitText("Quiet", substring = true)
        }
    }

    @Test
    fun notificationIntentRoutesToAnswerScreenAndBackReturnsHome() {
        val intent = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(EXTRA_SAMPLE, "nope|2026-09-01T19:00:00Z")
        ActivityScenario.launch<MainActivity>(intent).use {
            // An unknown sample must render a message, never a blank screen.
            rule.awaitText("Can't answer this ping")
            rule.onNodeWithText("Back").performClick()
            rule.awaitText("Quiet", substring = true)
        }
    }

    @Test
    fun snoozeRefusalTexts() {
        assertEquals("Snooze refused: no snoozes left", snoozeRefusalText("max_snoozes"))
        assertEquals("Snooze refused: too close to expiry", snoozeRefusalText("near_expiry"))
        assertEquals("Snooze refused: odd", snoozeRefusalText("odd"))
    }
}
