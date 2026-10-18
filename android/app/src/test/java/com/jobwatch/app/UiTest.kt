package com.jobwatch.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Runs the real app screens on the PC (Robolectric) and saves screenshots to build/screenshots. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h880dp-xxhdpi")
class UiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    companion object {
        val requested: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

        // A fake LinkedIn that serves the saved results page, so UI tests send no real requests.
        // Set before the activity starts, since opening the app runs a check.
        init {
            val sample = UiTest::class.java.getResource("/sample_page.html")!!.readText()
            Http.fake = { url -> requested += url; 200 to (if ("start=0" in url) sample else "") }
        }
    }

    // Compose's captureToImage() waits for a frame that never comes under Robolectric,
    // so draw the window (or a dialog's window) into a bitmap directly.
    private fun screenshot(name: String, view: View = compose.activity.window.decorView) {
        compose.waitForIdle()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The check that opening the app starts (no alarm was set yet) has finished: the fake page's 10 jobs are in. */
    private fun firstCheckDone() = compose.waitUntil(15_000) { Store.load(compose.activity).jobs.size >= 10 }

    /** Tap refresh once it's there (it's a spinner while a check runs), and wait for that check to finish. */
    private fun refresh() {
        val activity = compose.activity
        compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Check now").fetchSemanticsNodes().isNotEmpty() }
        val before = Store.load(activity).rounds.size
        compose.onNodeWithContentDescription("Check now").performClick()
        compose.waitUntil(15_000) { Store.load(activity).rounds.let { it.size > before && it[0].finishedAt > 0 } }
    }

    @Test
    fun fiveSearchesAtMostAndANewOneGoesNext() {
        val activity = compose.activity
        compose.onNodeWithText("Job Watch").assertIsDisplayed()
        compose.onNodeWithText("Amman, Jordan", substring = true).assertIsDisplayed()
        screenshot("1-main")
        firstCheckDone()

        // Five searches is the most: instead of the form, a note says so.
        val list = compose.onNode(hasScrollToIndexAction()) // the main list (the filter chips scroll too)
        list.performScrollToNode(hasText("Remove one to add another", substring = true))
        screenshot("2a-five-searches")

        // Remove one, and the form is back.
        val remove = hasContentDescription("Stop watching data analyst @ Saudi Arabia")
        list.performScrollToNode(remove)
        compose.onNode(remove).performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.waitForIdle()
        // As if every search had had its turn, so the new one is the longest waiting.
        Store.update(activity) { d -> d.copy(searches = d.searches.map { it.copy(lastTry = System.currentTimeMillis()) }) }
        list.performScrollToNode(hasText("Add search"))
        compose.onNode(hasSetTextAction() and hasText("Position")).performTextInput("python developer")
        compose.onNode(hasSetTextAction() and hasText("Location")).performTextInput("Dubai")
        screenshot("2-adding")
        compose.onNodeWithText("Add search").performClick()
        compose.waitForIdle()
        assertTrue("search saved", Store.load(activity).searches.any { it.label == "python developer @ Dubai" })
        list.performScrollToNode(hasText("python developer"))
        compose.onNodeWithText("python developer").assertIsDisplayed()

        // LinkedIn was asked moments ago, so the new search waits for the 2-minute mark, and the app says when.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Next one at", substring = true).fetchSemanticsNodes().isNotEmpty() }
        fun status() = Store.load(activity).searches.first { it.keywords == "python developer" }.status
        assertEquals("Not checked yet", status())

        // At the 2-minute mark (what the alarm runs), it's first in line.
        minutesPass(activity, 2)
        Checker.tick(activity)
        assertTrue(status(), status().startsWith("Checked") || status().startsWith("Checking"))
        list.performScrollToNode(hasText("JOBS", substring = true))
        screenshot("2b-after-check")
    }

    @Test
    fun alertsScreenOpensAndShowsEmailFields() {
        compose.onNodeWithContentDescription("Email and WhatsApp alerts").performClick()
        compose.onNodeWithText("Alerts").assertIsDisplayed()
        compose.onNodeWithText("Email me each round of new jobs").assertIsDisplayed()
        compose.onAllNodes(isToggleable())[0].performClick() // the email switch
        compose.onNodeWithText("Send to").assertIsDisplayed()
        compose.onNodeWithText("Send test").assertIsDisplayed()
        screenshot("3-alerts")
    }

    @Test
    fun newDotStaysUntilThatJobIsOpened() {
        val activity = compose.activity
        firstCheckDone() // it found the fake LinkedIn page's 10 jobs, all new
        compose.waitForIdle()
        assertEquals(10, Store.load(activity).jobs.size)
        assertTrue(Store.load(activity).jobs.all { it.isNew })

        // Leaving the app (as when a job opens in LinkedIn) and coming back keeps every dot.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertTrue(Store.load(activity).jobs.all { it.isNew })

        // Opening one job clears only its dot, and opens it.
        val jobs = Store.load(activity).jobs
        val job = jobs.first { j -> jobs.count { it.title == j.title } == 1 }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(job.title))
        compose.onNode(hasClickAction() and hasText(job.title)).performClick()
        compose.waitForIdle()
        val after = Store.load(activity).jobs
        assertFalse(after.first { it.id == job.id }.isNew)
        assertEquals(9, after.count { it.isNew })
        val opened = generateSequence { shadowOf(activity).nextStartedActivity }.mapNotNull { it.dataString }.toList()
        assertTrue(opened.toString(), job.url in opened)
        screenshot("6-dots")
    }

    @Test
    fun aboutShowsTheVersion() {
        compose.onNodeWithContentDescription("About Job Watch").performClick()
        compose.onNodeWithText("Version 1.11 (build 12)").assertIsDisplayed()
        compose.onNodeWithText("jobs found so far", substring = true).assertIsDisplayed()
        screenshot("7-about", ShadowDialog.getLatestDialog().window!!.decorView)
    }

    @Test
    fun recentChecksListEachRoundAndWhatItFound() {
        val activity = compose.activity
        // The round that opening the app started (no alarm was set yet) has finished.
        compose.waitUntil(15_000) { (Store.load(activity).rounds.firstOrNull()?.finishedAt ?: 0L) > 0L }
        compose.onNodeWithText("Recent checks").performClick()
        compose.onNodeWithText(Checker.TRIGGER_OPENED, substring = true).assertIsDisplayed()
        compose.onNodeWithText("software engineer @ Amman, Jordan: 10 new", substring = true).assertIsDisplayed()
        screenshot("13-history", ShadowDialog.getLatestDialog().window!!.decorView)
    }

    @Test
    fun backgroundChecksCanBeStopped() {
        compose.onNode(isToggleable() and hasText("Check every 10 minutes", substring = true)).performClick()
        compose.onNodeWithText("Off: nothing runs in the background", substring = true).assertIsDisplayed()
        assertFalse(Store.load(compose.activity).checksOn)
        assertEquals("alarm cancelled", 0L, Scheduler.nextCheckAt(compose.activity))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Background checks stopped", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("8-stopped")
    }

    @Test
    fun jobsCanBeMarkedReadOrNewOneByOneOrTogether() {
        val activity = compose.activity
        firstCheckDone() // the fake LinkedIn page's 10 jobs, all new
        compose.waitForIdle()
        fun newCount() = Store.load(activity).jobs.count { it.isNew }
        assertEquals(10, newCount())
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText(Store.load(activity).jobs.first().title))

        // One at a time: tap a job's dot.
        compose.onAllNodesWithContentDescription("Mark as read")[0].performClick()
        compose.waitForIdle()
        assertEquals(9, newCount())
        compose.onAllNodesWithContentDescription("Mark as new")[0].performClick()
        compose.waitForIdle()
        assertEquals(10, newCount())

        // Several at once: long-press to start selecting, then use the bar at the bottom.
        val jobs = Store.load(activity).jobs
        val job = jobs.first { j -> jobs.count { it.title == j.title } == 1 }
        list.performScrollToKey("job:${job.id}")
        compose.onNode(hasClickAction() and hasText(job.title)).performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithText("10 selected").assertIsDisplayed()
        screenshot("10-selecting")
        compose.onNodeWithText("Mark as read").performClick()
        compose.waitForIdle()
        assertEquals(0, newCount())
        compose.onNodeWithText("Select all").assertDoesNotExist() // the selection ends after marking

        // And back to new for two of them.
        compose.onNode(hasClickAction() and hasText(job.title)).performTouchInput { longClick() }
        val other = jobs.first { j -> j.id != job.id && jobs.count { it.title == j.title } == 1 }
        list.performScrollToKey("job:${other.id}")
        compose.onNode(hasClickAction() and hasText(other.title)).performClick() // while selecting, a tap selects
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithText("Mark as new").performClick()
        compose.waitForIdle()
        assertEquals(setOf(job.id, other.id), Store.load(activity).jobs.filter { it.isNew }.map { it.id }.toSet())
        assertTrue("selecting never opened a job", generateSequence { shadowOf(activity).nextStartedActivity }
            .none { it.dataString?.startsWith("https://www.linkedin.com/jobs/view/") == true })
    }

    @Test
    fun selectedJobsCanBeDeletedAndTheDeleteUndone() {
        val activity = compose.activity
        firstCheckDone() // the fake LinkedIn page's 10 jobs
        compose.waitForIdle()
        val jobs = Store.load(activity).jobs
        val (first, second) = jobs.filter { j -> jobs.count { it.title == j.title } == 1 }.take(2)
        val list = compose.onNode(hasScrollToIndexAction())

        list.performScrollToKey("job:${first.id}")
        compose.onNode(hasClickAction() and hasText(first.title)).performTouchInput { longClick() }
        list.performScrollToKey("job:${second.id}")
        compose.onNode(hasClickAction() and hasText(second.title)).performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        screenshot("11-delete")

        compose.onNodeWithContentDescription("Delete selected jobs").performClick()
        compose.waitForIdle()
        assertEquals(8, Store.load(activity).jobs.size)
        compose.onNodeWithText("Deleted 2 jobs").assertIsDisplayed()
        compose.onNodeWithText(first.title).assertDoesNotExist()
        screenshot("12-deleted")

        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals("back where they were", jobs.map { it.id }, Store.load(activity).jobs.map { it.id })
    }

    @Test
    fun remoteBoardsCardShowsAndTurnsOff() {
        val card = isToggleable() and hasText(RemoteBoards.LABEL)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(card)
        compose.onNode(card and hasText("software engineer and data analyst", substring = true)).assertIsDisplayed()
        screenshot("9-remote-boards")
        compose.onNode(card).performClick()
        compose.waitForIdle()
        assertFalse(Store.load(compose.activity).remoteOn)
        compose.onNode(card and hasText("Off")).assertIsDisplayed()
    }

    @Test
    fun jobSitesCardTakesAJoobleKey() {
        val activity = compose.activity
        val list = compose.onNode(hasScrollToIndexAction())
        list.performScrollToNode(hasText("Jooble API key"))
        compose.onNodeWithText("Jooble: Add your free API key below to turn it on", substring = true).assertExists()
        compose.onNode(hasSetTextAction() and hasText("Jooble API key")).performTextInput("KEY-123")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()
        assertEquals("KEY-123", Store.load(activity).joobleKey)
        list.performScrollToNode(hasText("Change Jooble key"))
        screenshot("10-job-sites")
    }

    @Test
    fun comingBackToTheAppDoesNotCheckAgain() {
        val activity = compose.activity
        firstCheckDone()
        assertTrue("that check set the 10-minute alarm", Scheduler.isScheduled(activity))
        fun linkedInRequests() = requested.count { "linkedin.com" in it }
        val before = linkedInRequests()

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED) // switch to another app...
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) // ...and come back
        compose.waitForIdle()
        Thread.sleep(500) // a check would start on a thread straight away
        assertEquals("an alarm is on its way: coming back is no reason to check", before, linkedInRequests())
        compose.onNodeWithText("Last check", substring = true).assertIsDisplayed()
    }

    @Test
    fun slowDownIsExplainedAndRefreshKeepsTwoMinutesBetweenRequests() {
        val activity = compose.activity
        firstCheckDone()
        val normal = Http.fake
        fun linkedInRequests() = requested.count { "linkedin.com" in it }

        // Right after the first check, a tap doesn't ask LinkedIn again; it says when the next search goes.
        val before = linkedInRequests()
        refresh()
        assertEquals(before, linkedInRequests())
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Next one at", substring = true).fetchSemanticsNodes().isNotEmpty() }

        // Two minutes on, LinkedIn refuses: the card says so, and when it will try again.
        minutesPass(activity, 2)
        Http.fake = { url -> requested += url; 429 to "" }
        refresh()
        assertTrue(Store.load(activity).limited)
        compose.onNode(isToggleable() and hasText("LinkedIn asked to slow down", substring = true)).assertIsDisplayed()
        screenshot("5-slowed-down")

        // Two minutes later a tap tries again without waiting out the pause, and once LinkedIn answers, all is normal.
        Http.fake = normal
        minutesPass(activity, 2)
        refresh()
        assertFalse(Store.load(activity).limited)
        compose.onNodeWithText("Last check", substring = true).assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "+night")
    fun darkMode() {
        compose.onNodeWithText("Job Watch").assertIsDisplayed()
        screenshot("4-main-dark")
    }
}
