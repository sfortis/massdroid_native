package net.asksakis.massdroidv2.tv.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import net.asksakis.massdroidv2.tv.TvPlaybackService
import net.asksakis.massdroidv2.tv.ui.theme.MassDroidTvTheme

@AndroidEntryPoint
class TvMainActivity : ComponentActivity() {

    /** Long-press BACK anywhere jumps to the mini player bar (10-foot shortcut). */
    private val miniPlayerShortcut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bring up the foreground Sendspin player service so the Shield registers
        // with MA as a synced speaker (the activity is foreground here, so the FGS
        // start is allowed).
        TvPlaybackService.start(this)
        setContent {
            MassDroidTvTheme {
                TvRoot(miniPlayerShortcut = miniPlayerShortcut)
            }
        }
    }

    // BACK is tracked manually so a long press can be told apart from a tap: the tap
    // fires the normal back dispatch on key-UP (unless the long press consumed it).
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            miniPlayerShortcut.tryEmit(Unit)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.flags and KeyEvent.FLAG_CANCELED_LONG_PRESS == 0) {
                onBackPressedDispatcher.onBackPressed()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
