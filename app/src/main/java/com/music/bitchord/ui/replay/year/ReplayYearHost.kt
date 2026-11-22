package com.music.bitchord.ui.replay.year

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.ui.components.menu.AppSheet

/**
 * The year story and its share sheet, as the app mounts them over everything.
 *
 * A function of its own rather than more of the app's body, which is already
 * as large as one JVM method is allowed to be. Back unwinds them in the order
 * they opened: the share sheet first, then the story.
 *
 * Nothing here reads or plays anything until [year] is set: the year's numbers
 * are read when the story opens, not before, and dropped when it closes.
 *
 * ## The app's own music
 *
 * The story has its own soundtrack, so whatever was playing pauses as it
 * opens, and starts again as it closes, from where it was. Only if the story
 * paused it: music that was already stopped stays stopped. Started again
 * under the story (from the notification, say), the app's music wins: the
 * clips go quiet, and closing the story leaves the music alone.
 */
@Composable
fun ReplayYearHost(
    /** The year whose story is open, or null while it is closed. */
    year: Int?,
    onClose: () -> Unit,
    /** The app's own player, paused while the story plays. */
    mainPlayer: Player?,
) {
    var sharing by remember { mutableStateOf<Pair<YearSlide, CardLook>?>(null) }
    var story by remember { mutableStateOf<ReplayYear?>(null) }
    var openedAt by remember { mutableLongStateOf(0L) }
    // Saved, so a story the activity was rebuilt under still hands the music back.
    var resumeOnClose by rememberSaveable { mutableStateOf(false) }
    var othersPlaying by remember { mutableStateOf(false) }
    val player by rememberUpdatedState(mainPlayer)

    LaunchedEffect(year) {
        story = null
        sharing = null
        year ?: return@LaunchedEffect
        openedAt = SystemClock.elapsedRealtime()
        story = loadReplayYear(year)
        // A year with nothing in it (the files went between the row and the tap).
        if (story == null) onClose()
    }

    // Paused for the story, and the listener's own play wins over it.
    DisposableEffect(year != null, mainPlayer) {
        val main = mainPlayer
        if (year == null || main == null) return@DisposableEffect onDispose {}
        if (main.isPlaying) {
            main.pause()
            resumeOnClose = true
        }
        othersPlaying = false
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    othersPlaying = true
                    resumeOnClose = false
                } else if (main.playbackState != Player.STATE_BUFFERING) {
                    othersPlaying = false
                }
            }
        }
        main.addListener(listener)
        onDispose { main.removeListener(listener) }
    }

    if (year == null) return

    val close: () -> Unit = {
        sharing = null
        if (resumeOnClose) {
            resumeOnClose = false
            val main = player
            val party = ListenTogether.state.value
            // A party that has paused itself meanwhile is not restarted here.
            val partyPaused = party.controlsLocked && !party.playback.isPlaying
            if (main != null && !main.isPlaying && !partyPaused) main.play()
        }
        onClose()
    }

    BackHandler(enabled = sharing == null, onBack = close)
    BackHandler(enabled = sharing != null) { sharing = null }

    ReplayYearStories(
        year = story,
        onClose = close,
        onShare = { slide, look -> sharing = slide to look },
        paused = sharing != null,
        othersPlaying = othersPlaying,
        openedAt = openedAt,
    )

    // Its own sheet: a card of the year story is shared as that card, as it
    // stood when it finished, not as one of the ordinary Replay's posters.
    val loaded = story
    AppSheet(item = sharing?.takeIf { loaded != null }, onDismiss = { sharing = null }) { (slide, look) ->
        loaded?.let { ReplayYearShareSheet(year = it, slide = slide, look = look, onDismiss = { sharing = null }) }
    }
}
