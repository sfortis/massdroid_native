package net.asksakis.massdroidv2.tv.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.player.VolumeKeyAction
import net.asksakis.massdroidv2.domain.player.VolumeKeyController
import net.asksakis.massdroidv2.domain.player.VolumeKeyTarget
import net.asksakis.massdroidv2.domain.player.volumeKeyAction
import net.asksakis.massdroidv2.domain.player.volumeKeyTarget
import net.asksakis.massdroidv2.domain.repository.PlayerRepository
import net.asksakis.massdroidv2.domain.repository.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import net.asksakis.massdroidv2.tv.TvPlaybackService
import net.asksakis.massdroidv2.tv.ui.theme.MassDroidTvTheme

@AndroidEntryPoint
class TvMainActivity : ComponentActivity() {

    /** Long-press BACK anywhere jumps to the mini player bar (10-foot shortcut). */
    private val miniPlayerShortcut = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Inject lateinit var playerRepository: PlayerRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var volumeKeyController: VolumeKeyController

    /** This TV's own Sendspin player, so its volume keys are left to the system. */
    @Volatile private var sendspinClientId: String? = null
    private var lastCountedVolumeDownTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bring up the foreground Sendspin player service so the Shield registers
        // with MA as a synced speaker (the activity is foreground here, so the FGS
        // start is allowed).
        TvPlaybackService.start(this)
        lifecycleScope.launch { settingsRepository.sendspinClientId.collect { sendspinClientId = it } }
        setContent {
            MassDroidTvTheme {
                TvRoot(miniPlayerShortcut = miniPlayerShortcut)
                DevBuildBadge()
            }
        }
    }

    /**
     * The remote's volume keys control the selected player when it is a Music Assistant player
     * elsewhere, the way the phone's rocker does (the same [VolumeKeyController], with its pacing,
     * optimistic level and OSD), so the player needs no volume buttons of its own. When this TV
     * is the output, or nothing is selected, the keys go to the system as before: the TV's own
     * volume, or the receiver's over HDMI-CEC.
     */
    // ComponentActivity.dispatchKeyEvent carries a library-group restriction, so lint reads an
    // override calling super as reaching into AndroidX. Overriding it and delegating is exactly
    // what the method is for.
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return super.dispatchKeyEvent(event)
        }
        val target = volumeKeyTarget(playerRepository.selectedPlayer.value, sendspinClientId)
        if (target !is VolumeKeyTarget.RemotePlayer) return super.dispatchKeyEvent(event)

        val isDown = event.action == KeyEvent.ACTION_DOWN
        if (!isDown && event.action != KeyEvent.ACTION_UP) return true
        val action = volumeKeyAction(isDown, event.downTime, lastCountedVolumeDownTime)
        if (isDown) lastCountedVolumeDownTime = event.downTime
        when (action) {
            // A held key repeats far faster than a player can follow; the controller paces
            // the repeats, and must be told which events are repeats.
            VolumeKeyAction.STEP -> volumeKeyController.step(
                up = keyCode == KeyEvent.KEYCODE_VOLUME_UP,
                repeat = event.repeatCount > 0
            )
            VolumeKeyAction.FLUSH -> volumeKeyController.flush()
        }
        return true
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

/**
 * Dev-build marker, as on the phone: a small translucent bug icon in the top-right corner, shown
 * only on debug builds so a dev APK can be told apart from a release install that has the same
 * name and version. It sits in the margin right of the Home Settings button and is not
 * focusable, so the D-pad never lands on it. No-op on release.
 */
@Composable
private fun DevBuildBadge() {
    if (!net.asksakis.massdroidv2.tv.BuildConfig.DEBUG) return
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Icon(
            imageVector = Icons.Filled.BugReport,
            contentDescription = "Dev build",
            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.55f),
            modifier = Modifier
                .padding(end = 20.dp, top = 12.dp)
                .size(20.dp)
        )
    }
}
