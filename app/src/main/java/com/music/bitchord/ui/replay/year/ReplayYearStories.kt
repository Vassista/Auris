package com.music.bitchord.ui.replay.year

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.player.FallbackMeshPalette
import com.music.bitchord.ui.player.MeshGradientBackground
import com.music.bitchord.ui.player.MeshPalette
import com.music.bitchord.ui.player.artworkPalette
import com.music.bitchord.ui.replay.BACK_ZONE
import com.music.bitchord.ui.replay.StoryFrame
import com.music.bitchord.ui.replay.rotated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * The Replay year story: the year told as a run of loud, animated cards, each
 * scored with the best part of a song that belongs to it.
 *
 * It keeps everything the ordinary Replay story taught (see
 * [com.music.bitchord.ui.replay.ReplayStories]): the fixed 9:16 frame, the
 * artwork mesh rotated to a new colour per card, tap right for on and left for
 * back, hold to stop the clock, swipe, the share button on every card. What it
 * adds is motion and sound: the cards don't slide past, they arrive, and each
 * card's big moment is timed to land with its clip's chorus.
 *
 * ## How it stays fast
 *
 * The one clock per card ([SlideClock]) drives every animation on it and the
 * progress bar both, so holding the card freezes the scene and the audio
 * together. The clock is read in layer and draw lambdas, so a card full of
 * moving parts redraws without composing anything; only a counting number
 * and a title being typed recompose, and only when what they show changes.
 *
 * Everything shown is worked out before the first card: the year itself (see
 * [ReplayYear]), every card's colours, and the opening card's best part, behind
 * a short "building" screen. The other best parts are looked up while the
 * story plays, and each next card's clip is loaded and parked at its start on
 * the second player while the current card runs.
 */
@Composable
fun ReplayYearStories(
    /** The year, or null while it is still being read; the story builds until it arrives. */
    year: ReplayYear?,
    onClose: () -> Unit,
    /** Shares the card on screen, in its own colours. */
    onShare: (YearSlide, CardLook) -> Unit,
    /** Holds everything while something (the share sheet) is up in front. */
    paused: Boolean = false,
    /** The app's own music has been started again under the story: the clips step aside. */
    othersPlaying: Boolean = false,
    /** When the story was asked for, on [SystemClock.elapsedRealtime], for the open-time log. */
    openedAt: Long = SystemClock.elapsedRealtime(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val reduceMotion by AppSettings.reduceAnimation.collectAsStateWithLifecycle()

    var index by remember { mutableIntStateOf(0) }
    var forward by remember { mutableStateOf(true) }
    var held by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(readMuted(context)) }
    var progress by remember { mutableFloatStateOf(0f) }

    // Off screen (home, another app, the screen locked) the story stops where
    // it is, music and all, as if a finger were on the card, and carries on
    // from there on coming back. Closing the app tears it down and lets go.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val onScreen by lifecycle.currentStateFlow.collectAsState()
    val hidden = !onScreen.isAtLeast(Lifecycle.State.STARTED)

    // ── The soundtrack ──
    val player = remember { YearClipPlayer(context.applicationContext, scope) }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(muted, othersPlaying) { player.muted = muted || othersPlaying }
    val starts = remember { mutableStateMapOf<String, BestPartStart>() }
    // Each card's colours, by slide, worked out while the story builds.
    val looks = remember { mutableStateMapOf<YearSlide, CardLook>() }

    // Every card's best part is looked up at once, three at a time and in the
    // order the cards are told, so the first ones are ready first; every
    // card's colours alongside. The story waits for the opening card's best
    // part and the colours (or [BUILD_MAX_MS], whichever is sooner) behind a
    // short "building" screen; the rest arrive while it plays.
    LaunchedEffect(year) {
        year ?: return@LaunchedEffect
        val slides = year.slides
        val songs = slides.mapNotNull { year.clips[it] }.distinctBy { it.videoId }
        val gate = Semaphore(LOOKUPS_AT_ONCE)
        songs.forEach { song ->
            BestParts.known(song.videoId)?.let { starts[song.videoId] = it }
            launch {
                if (song.videoId in starts) return@launch
                gate.withPermit { starts[song.videoId] = BestParts.startFor(context.applicationContext, song) }
            }
        }
        val colours = launch { loadLooks(context, year, looks) }
        val first = year.clips[slides.first()]
        val began = SystemClock.elapsedRealtime()
        withTimeoutOrNull(BUILD_MAX_MS) {
            while (first != null && first.videoId !in starts) delay(50)
            colours.join()
        }
        clipOf(player, first, starts, slides.first())?.let(player::prime)
        delay((BUILD_MIN_MS - (SystemClock.elapsedRealtime() - began)).coerceAtLeast(0))
        ready = true
        Log.i(TAG, "story ready in ${SystemClock.elapsedRealtime() - openedAt} ms, ${slides.size} cards")
    }

    if (year == null || !ready) {
        StoryShell { Building() }
        return
    }

    val slides = year.slides
    val slide = slides[index.coerceIn(0, slides.lastIndex)]

    LaunchedEffect(index) {
        player.play(clipOf(player, year.clips[slide], starts, slide))
        // The next card's clip waits on the deck that is still fading this one's
        // predecessor out, then loads; by the time this card ends it is ready.
        slides.getOrNull(index + 1)?.let { next ->
            delay(PRIME_AFTER_MS)
            player.prime(clipOf(player, year.clips[next], starts, next))
        }
    }
    LaunchedEffect(held, paused, hidden) { player.hold(held || paused || hidden) }

    // The card's haptics, written down by its own animations as it composes
    // (see [SlideClock.beat]) and struck here as its clock passes each one, so
    // every landing is felt on the frame it is seen. Off the same clock as the
    // pictures: holding the card holds them too, and coming back plays them again.
    val beats = remember(index) { HapticBeats() }
    val haptics = rememberHaptics()
    LaunchedEffect(index, beats) {
        var last = -1f
        snapshotFlow { progress * PAGE_MS }.collect { now ->
            val hit = beats.strongestBetween(last, now)
            last = now
            // With motion reduced nothing slides or counts, so only the landings are felt.
            if (hit != null && !(reduceMotion && hit == Haptic.Tick)) haptics.play(hit)
        }
    }

    fun goTo(target: Int) {
        if (target !in slides.indices) return
        forward = target > index
        // Reset in the same frame the card changes: the new card's clock reads
        // this, and a reset that waited a frame would show it already finished.
        progress = 0f
        index = target
    }

    fun step(ahead: Boolean) {
        if (ahead && index == slides.lastIndex) {
            onClose()
            return
        }
        goTo(index + if (ahead) 1 else -1)
    }

    LaunchedEffect(index, held, paused, hidden) {
        if (held || paused || hidden) return@LaunchedEffect
        val remaining = ((1f - progress) * PAGE_MS).toInt().coerceAtLeast(0)
        animate(progress, 1f, animationSpec = tween(remaining, easing = LinearEasing)) { value, _ -> progress = value }
        // The last card waits out its time and then closes, as the Replay story does.
        if (index == slides.lastIndex) onClose() else goTo(index + 1)
    }

    val look = looks[slide] ?: CardLook.of(FallbackMeshPalette.rotated(slide.hue))

    StoryShell(
        gestures = Modifier
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        // A hold, not every touch: a tap that paused the clip
                        // and started it again would stutter the music on
                        // every card turned.
                        val hold: Job = scope.launch {
                            delay(HOLD_AFTER_MS)
                            held = true
                        }
                        tryAwaitRelease()
                        hold.cancel()
                        held = false
                    },
                    // See ReplayStories: without it every press is a tap.
                    onLongPress = {},
                    onTap = { offset -> step(offset.x >= size.width * BACK_ZONE) },
                )
            }
            .pointerInput(Unit) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onHorizontalDrag = { change, amount ->
                        dragged += amount
                        change.consume()
                    },
                    onDragEnd = {
                        if (abs(dragged) > SWIPE_PX) step(ahead = dragged < 0)
                    },
                )
            },
        backdrop = { YearBackdrop(slide, look.palette) },
    ) {
        AnimatedContent(
            targetState = index,
            transitionSpec = { transitionInto(slides[targetState], forward, reduceMotion) },
            label = "year-slide",
        ) { shown ->
            val live = shown == index
            val clock = remember(live, reduceMotion, beats) {
                // A card on its way out stays as it finished rather
                // than replaying its entrance as it leaves.
                if (live) SlideClock({ progress * PAGE_MS }, reduceMotion, beats)
                else SlideClock({ Float.MAX_VALUE / 4f }, reduceMotion)
            }
            val shownSlide = slides[shown]
            CompositionLocalProvider(LocalSlideClock provides clock) {
                YearSlideContent(shownSlide, year, looks[shownSlide] ?: look)
            }
        }

        YearChrome(
            year = year.year,
            count = slides.size,
            current = index,
            progress = { progress },
            muted = muted,
            onMute = {
                muted = !muted
                writeMuted(context, muted)
            },
            onClose = onClose,
        )

        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 18.dp)
                .size(44.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.18f))
                .clickable { onShare(slide, look) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.IosShare,
                contentDescription = stringResource(R.string.share_my_replay),
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * A card's colours: its mesh, and the accent and confetti drawn from it.
 * Worked out once per card when the story opens.
 */
class CardLook(val palette: MeshPalette, val accent: Color, val confetti: List<Color>) {
    companion object {
        fun of(palette: MeshPalette) = CardLook(palette, accentOf(palette), confettiOf(palette))
    }
}

/**
 * Every card's [CardLook], into [into]: each distinct artwork's palette once,
 * then turned to each card's own hue.
 */
private suspend fun loadLooks(context: Context, year: ReplayYear, into: MutableMap<YearSlide, CardLook>) {
    val bySlide = year.slides.associateWith { year.artworkFor(it) }
    val palettes = HashMap<String, MeshPalette>()
    val gate = Semaphore(LOOKUPS_AT_ONCE)
    kotlinx.coroutines.coroutineScope {
        bySlide.values.filterNotNull().distinct().map { url ->
            launch { gate.withPermit { artworkPalette(context, url)?.let { synchronized(palettes) { palettes[url] = it } } } }
        }
    }
    val looks = withContext(Dispatchers.Default) {
        bySlide.mapValues { (slide, url) ->
            CardLook.of((url?.let { palettes[it] } ?: FallbackMeshPalette).rotated(slide.hue))
        }
    }
    into.putAll(looks)
}

/**
 * The black surround, the 9:16 frame and its dark card, with [backdrop] behind
 * and [content] over it, and the gestures on the card. The building screen and
 * the story are both drawn in it, so the one turns into the other in place.
 */
@Composable
private fun StoryShell(
    gestures: Modifier = Modifier,
    backdrop: @Composable () -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        StoryFrame {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF17171A))
                    .then(gestures),
            ) {
                backdrop()
                content()
            }
        }
    }
}

/**
 * How a card arrives. Not one slide for all of them: the run alternates between
 * zooming through, rising up and sweeping across, so turning a card feels like
 * a cut rather than a page.
 */
private fun transitionInto(slide: YearSlide, forward: Boolean, reduceMotion: Boolean): ContentTransform {
    if (reduceMotion) return fadeIn(tween(220)) togetherWith fadeOut(tween(180))
    val enterMs = 420
    val exitMs = 320
    return when (slide.ordinal % 3) {
        0 -> (scaleIn(tween(enterMs), initialScale = if (forward) 0.82f else 1.18f) + fadeIn(tween(enterMs))) togetherWith
            (scaleOut(tween(exitMs), targetScale = if (forward) 1.2f else 0.85f) + fadeOut(tween(exitMs)))
        1 -> (slideInVertically(tween(enterMs)) { if (forward) it / 2 else -it / 2 } + fadeIn(tween(enterMs))) togetherWith
            (slideOutVertically(tween(exitMs)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(exitMs)))
        else -> (slideInHorizontally(tween(enterMs)) { if (forward) it else -it } + fadeIn(tween(enterMs / 2))) togetherWith
            (slideOutHorizontally(tween(exitMs)) { if (forward) -it / 3 else it / 3 } + fadeOut(tween(exitMs)))
    }
}

/**
 * The clip for [slide], started early enough that the chorus's first word lands
 * on the card's reveal. Its best part is whatever is known by now; a song still
 * being looked up starts at the fallback.
 */
private fun clipOf(
    player: YearClipPlayer,
    song: Song?,
    starts: Map<String, BestPartStart>,
    slide: YearSlide,
): YearClipPlayer.Clip? {
    song ?: return null
    val best = starts[song.videoId] ?: BestParts.known(song.videoId)
        ?: BestPartStart(BestPart.fallbackMs(0), BestPartStart.Source.FALLBACK)
    // The best part starts a quarter-second before its line is sung (see
    // BestPart.snap), so the word itself is at startMs + 250.
    val seek = (best.startMs + SNAP_LEAD_MS - slide.revealAtMs).coerceAtLeast(0)
    return player.clipFor(song, seek)
}

@Composable
private fun Building() {
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) { clock.animateTo(1f, tween(BUILD_MAX_MS.toInt(), easing = LinearEasing)) }
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CompositionLocalProvider(
            LocalSlideClock provides remember { SlideClock({ clock.value * BUILD_MAX_MS }, reduceMotion = false) },
        ) {
            OrbitRings(start = 0, color = Color.White, modifier = Modifier.size(160.dp))
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.replay_year_building),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.W800,
                color = Color.White,
                modifier = Modifier.breathe(LocalSlideClock.current, 0, amount = 0.04f, periodMs = 1_400f),
            )
        }
    }
}

/**
 * The cross, the progress segments, and the name — the Replay story's chrome,
 * with a mute button beside the cross since this one has a soundtrack.
 */
@Composable
private fun YearChrome(
    year: Int,
    count: Int,
    current: Int,
    progress: () -> Float,
    muted: Boolean,
    onMute: () -> Unit,
    onClose: () -> Unit,
    /**
     * For the shared picture: only the name, where it sits on the story. The
     * buttons and segments keep their room, invisible, so the card below them
     * is laid out exactly as it was seen.
     */
    still: Boolean = false,
) {
    val controls = if (still) Modifier.alpha(0f) else Modifier
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp).then(controls),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = stringResource(if (muted) R.string.replay_year_unmute else R.string.replay_year_mute),
                tint = Color.White,
                modifier = Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onMute).padding(4.dp),
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.close_replay),
                tint = Color.White,
                modifier = Modifier.size(30.dp).clip(CircleShape).clickable(onClick = onClose).padding(4.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = controls) {
            repeat(count) { i ->
                YearSegment(
                    fraction = when {
                        i < current -> Full
                        i > current -> Empty
                        else -> progress
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Replay ${year % 100}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W700,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            // The mark and the word together, as the Replay story carries them.
            Icon(
                painter = painterResource(R.drawable.ic_note),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(width = 12.dp, height = 17.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = "Auris",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.W700,
                color = Color.White.copy(alpha = 0.9f),
            )
        }
    }
}

private val Full: () -> Float = { 1f }
private val Empty: () -> Float = { 0f }

/**
 * One progress segment, its fill read while drawing: the running one redraws
 * each frame without the chrome around it composing again.
 */
@Composable
private fun YearSegment(fraction: () -> Float, modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .height(2.5.dp)
            .clip(RoundedCornerShape(2.dp))
            .drawBehind {
                drawRect(Color.White.copy(alpha = 0.28f))
                val filled = fraction().coerceIn(0f, 1f)
                if (filled > 0f) drawRect(Color.White, size = Size(size.width * filled, size.height))
            },
    )
}

/** The same backdrop as the ordinary Replay story, card for card. */
@Composable
private fun YearBackdrop(slide: YearSlide, palette: MeshPalette) {
    MeshGradientBackground(palette = palette, trackKey = slide.name, animated = false)
    Box(
        Modifier
            .fillMaxSize()
            .background(Scrim),
    )
}

private val Scrim = Brush.verticalGradient(
    0.0f to Color.Black.copy(alpha = 0.45f),
    0.35f to Color.Black.copy(alpha = 0.10f),
    1.0f to Color.Black.copy(alpha = 0.34f),
)

/**
 * [slide] as it stands when its time on the story is up, everything landed:
 * the backdrop, the card and the name above it, without the buttons, the
 * progress or the share button. What the shared picture is made from.
 */
@Composable
internal fun YearCardStill(year: ReplayYear, slide: YearSlide, look: CardLook, modifier: Modifier = Modifier) {
    val clock = remember { SlideClock({ PAGE_MS }, reduceMotion = false) }
    Box(modifier.background(Color(0xFF17171A))) {
        YearBackdrop(slide, look.palette)
        CompositionLocalProvider(LocalSlideClock provides clock) {
            YearSlideContent(slide, year, look)
        }
        YearChrome(
            year = year.year,
            count = year.slides.size,
            current = year.slides.indexOf(slide),
            progress = Full,
            muted = false,
            onMute = {},
            onClose = {},
            still = true,
        )
    }
}

private fun prefs(context: Context) = context.getSharedPreferences("replay_year", Context.MODE_PRIVATE)

private fun readMuted(context: Context): Boolean =
    runCatching { prefs(context).getBoolean("muted", false) }.getOrDefault(false)

private fun writeMuted(context: Context, muted: Boolean) {
    runCatching { prefs(context).edit().putBoolean("muted", muted).apply() }
}

private const val TAG = "ReplayYear"

/** How long a card holds before moving on. About the length of a chorus. */
private const val PAGE_MS = 15_000f

/** The "building" screen shows at least this long, so it reads as a moment rather than a flicker… */
private const val BUILD_MIN_MS = 1_200L

/** …and never longer than this, whatever is still being looked up. */
private const val BUILD_MAX_MS = 2_500L

private const val LOOKUPS_AT_ONCE = 3
private const val PRIME_AFTER_MS = 1_200L
private const val SNAP_LEAD_MS = 250L
private const val SWIPE_PX = 120f

/** How long a finger has to stay down before the card holds. */
private const val HOLD_AFTER_MS = 180L
