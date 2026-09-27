package net.asksakis.massdroidv2.ui.nfc

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.domain.nfc.NfcTagPayload
import net.asksakis.massdroidv2.domain.nfc.NfcTapExecutor
import net.asksakis.massdroidv2.domain.nfc.NfcTapOutcome
import javax.inject.Inject

private const val TAG = "NfcTapActivity"

/**
 * Receives a scanned MassDroid tag, starts what it asks for, and disappears.
 *
 * It is its own activity rather than another branch of MainActivity because a tap is not a
 * request to look at the app. Android delivers a scanned tag to an activity and to nothing
 * else, so something has to come to the front; this one carries no layout, keeps its own
 * task, and leaves the main screen on whatever the user had open.
 *
 * The work runs on the activity's own scope. It outlives the phone going into a pocket,
 * which only stops the activity, and is given up if the user backs out of it, which is the
 * one case where cancelling is what they asked for.
 */
@AndroidEntryPoint
class NfcTapActivity : ComponentActivity() {

    @Inject lateinit var executor: NfcTapExecutor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /**
     * The tag dispatch system puts the record's uri in the intent's data, so the NDEF
     * message itself never has to be unpacked. A tag whose uri is not ours cannot reach
     * here through the manifest filter, and is still checked, because an intent can also
     * arrive from somewhere else entirely.
     */
    private fun handle(intent: Intent) {
        val payload = NfcTagPayload.parse(intent.data?.toString())
        if (payload == null) {
            Log.w(TAG, "Ignoring intent with data ${intent.data}")
            finish()
            return
        }
        lifecycleScope.launch {
            val outcome = executor.execute(payload)
            Log.d(TAG, "Tag ${payload.mediaUri} -> $outcome")
            Toast.makeText(this@NfcTapActivity, message(outcome), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun message(outcome: NfcTapOutcome): String = when (outcome) {
        is NfcTapOutcome.Started -> when {
            outcome.label != null && outcome.playerName != null ->
                "Playing ${outcome.label} on ${outcome.playerName}"
            outcome.playerName != null -> "Playing on ${outcome.playerName}"
            else -> "Playing"
        }
        NfcTapOutcome.NotConnected -> "Could not reach Music Assistant"
        is NfcTapOutcome.PlayerMissing -> "That speaker is not available"
        NfcTapOutcome.NoPlayer -> "Choose a player first"
        NfcTapOutcome.Blocked -> "Everything here is by an artist you blocked"
        is NfcTapOutcome.Failed -> "Could not start playback"
    }
}
