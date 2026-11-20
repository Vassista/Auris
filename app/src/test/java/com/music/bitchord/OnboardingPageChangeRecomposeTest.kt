package com.music.bitchord

import android.app.Application
import android.content.ComponentName
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.RecomposeScope
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.tooling.CompositionObserver
import androidx.compose.runtime.tooling.CompositionObserverHandle
import androidx.compose.runtime.tooling.CompositionRegistrationObserver
import androidx.compose.runtime.tooling.ObservableComposition
import androidx.compose.runtime.tooling.observe
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.music.bitchord.auth.AuthStore
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.PlaybackService
import com.music.bitchord.ui.AndroidAppUiHost
import com.music.bitchord.ui.AppUi
import com.music.bitchord.ui.onboarding.SetupWizard
import com.music.bitchord.ui.player.AndroidPlayerHost
import com.music.bitchord.ui.player.PlayerPlatform
import com.music.bitchord.ui.theme.BitChordTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * A page change in the first-run flow must not recompose the flow, or the
 * pages, once per frame of the slide.
 *
 * Renders the real [SetupWizard], lets the sheet arrive, then taps Continue
 * through the first pages and counts, frame by frame over the slide, which
 * composable bodies ran. The probe is the one [RootRecomposeOnPageAppendTest]
 * uses (see its notes on Compose 1.10's re-read entries, which are not runs).
 *
 * What it found, and guards: the frame the tap lands in and the one after it
 * compose the incoming page (100–190 scopes), the two frames where the
 * transition ends drop the outgoing one, and every frame of the slide between
 * them composes nothing. The page-change jank was never composition: it was
 * the GPU compositing what sat under the opaque sheet on every frame — see
 * [com.music.bitchord.ui.onboarding.SetupSheetCornerRadius].
 */
@OptIn(ExperimentalComposeRuntimeApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
class OnboardingPageChangeRecomposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Before fun install() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        PlayerPlatform.install(AndroidPlayerHost(app))
        AppUi.install(AndroidAppUiHost)
        val auth = AuthStore(app)
        // Private-set in the app; the flow reads it for the sign-in state.
        BitChordApplication::class.java.getDeclaredField("authStore").apply { isAccessible = true }.set(null, auth)
        AppSettings.init(app, auth)
        Downloads.init(app)
        Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app, PlaybackService::class.java))
    }

    private class Counter : CompositionObserver {
        val runsByFunction = HashMap<String, Int>()
        var runs = 0

        override fun onBeginComposition(composition: ObservableComposition) {}
        override fun onScopeEnter(scope: RecomposeScope) {
            if (!enteredToRun()) return
            runs++
            val function = callerOfComposer()
            runsByFunction[function] = (runsByFunction[function] ?: 0) + 1
        }
        override fun onReadInScope(scope: RecomposeScope, value: Any) {}
        override fun onScopeExit(scope: RecomposeScope) {}
        override fun onEndComposition(composition: ObservableComposition) {}
        override fun onScopeInvalidated(scope: RecomposeScope, value: Any?) {}
        override fun onScopeDisposed(scope: RecomposeScope) {}

        fun runsOf(function: String) = runsByFunction["$FLOW.$function"] ?: 0
        fun pageRuns(function: String) = runsByFunction["$PAGES.$function"] ?: 0

        fun clear() {
            runsByFunction.clear()
            runs = 0
        }

        private fun callerOfComposer(): String = walker.walk { frames ->
            frames.filter { !it.className.startsWith(RUNTIME) && !it.className.startsWith(TEST) }
                .findFirst()
                .map { it.className + "." + it.methodName.substringBefore('-') }
                .orElse("")
        }

        private fun enteredToRun(): Boolean = walker.walk { frames ->
            frames.filter { it.className == COMPOSER }.findFirst().map { it.methodName }.orElse("")
        } != "recomposeToGroupEnd"

        companion object {
            const val COMPOSER = "androidx.compose.runtime.ComposerImpl"
            const val RUNTIME = "androidx.compose.runtime."
            const val FLOW = "com.music.bitchord.ui.onboarding.OnboardingFlowKt"
            const val PAGES = "com.music.bitchord.ui.onboarding.OnboardingPagesKt"
            val TEST: String = OnboardingPageChangeRecomposeTest::class.java.name
            val walker: StackWalker = StackWalker.getInstance()
        }
    }

    private fun recomposerOf(composition: Composition): Recomposer {
        var c: Any? = composition
        repeat(6) {
            if (c is Recomposer) return c as Recomposer
            c = c!!.javaClass.declaredFields.firstNotNullOfOrNull { f ->
                f.isAccessible = true
                f.get(c) as? CompositionContext
            }
        }
        error("no recomposer")
    }

    private data class Slide(val frames: Int, val total: Int, val wizard: Int, val outgoing: Int, val busyFrames: Int)

    @Test fun `a page change does not recompose the flow per frame of the slide`() {
        val counter = Counter()
        var recomposer: Recomposer? = null
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val composition = currentComposer.composition
            SideEffect { if (recomposer == null) recomposer = recomposerOf(composition) }
            BitChordTheme(darkTheme = true) {
                SetupWizard(onFinished = {})
            }
        }
        // The sheet's arrival, and the frame-pace probe's first window.
        repeat(SETTLE_FRAMES) { compose.mainClock.advanceTimeByFrame() }
        compose.waitForIdle()

        val handles = mutableListOf<CompositionObserverHandle>()
        val reg = recomposer!!.observe(object : CompositionRegistrationObserver {
            override fun onCompositionRegistered(composition: ObservableComposition) {
                handles += composition.setObserver(counter)
            }
            override fun onCompositionUnregistered(composition: ObservableComposition) {}
        })

        val outgoing = listOf("NameStep", "SoundStep", "FeelStep")
        val incomingTitle = listOf("How should it sound?", "Make it yours", "Where should the music come from?")
        val slides = outgoing.mapIndexed { index, page ->
            counter.clear()
            compose.onNodeWithText("Continue").performSemanticsAction(SemanticsActions.OnClick)
            // Frame by frame: the frame the tap lands in composes the incoming
            // page, which a page change has to do once. Every frame after it
            // is the slide, and should cost no composition at all.
            val perFrame = ArrayList<Int>()
            repeat(SLIDE_FRAMES) {
                val before = counter.runs
                compose.mainClock.advanceTimeByFrame()
                compose.waitForIdle()
                perFrame += counter.runs - before
            }
            compose.onNodeWithText(incomingTitle[index]).assertExists()
            val composing = perFrame.indexOfFirst { it > 0 }
            val afterFirst = if (composing < 0) emptyList() else perFrame.drop(composing + 1)
            val slide = Slide(
                frames = SLIDE_FRAMES,
                total = counter.runs,
                wizard = counter.runsOf("SetupWizard"),
                outgoing = counter.pageRuns(page),
                busyFrames = afterFirst.count { it > 0 },
            )
            println(
                "PROBE onboarding leave=$page perFrame=$perFrame wizard=${slide.wizard} outgoing=${slide.outgoing} " +
                    "busyAfterFirst=${slide.busyFrames} " +
                    "top=${counter.runsByFunction.entries.sortedByDescending { it.value }.take(10)}",
            )
            // Let the pace probe's window run out before the next tap.
            repeat(SETTLE_FRAMES) { compose.mainClock.advanceTimeByFrame() }
            compose.waitForIdle()
            slide
        }
        reg.dispose()
        handles.forEach { it.dispose() }

        slides.forEachIndexed { i, s ->
            assertTrue(
                "slide ${outgoing[i]} recomposed on ${s.busyFrames} frames after the incoming page",
                s.busyFrames <= SETTLE_PASSES,
            )
        }
    }

    private companion object {
        /** ~2 s at 60 Hz: the sheet's 520 ms rise, plus the 1.6 s frame-pace window. */
        const val SETTLE_FRAMES = 130
        /** ~600 ms: longer than the slide's spring takes to settle. */
        const val SLIDE_FRAMES = 36
        /**
         * Frames after the incoming page's first that may compose: its second
         * pass, and the transition's end dropping the outgoing page.
         */
        const val SETTLE_PASSES = 3
    }
}
