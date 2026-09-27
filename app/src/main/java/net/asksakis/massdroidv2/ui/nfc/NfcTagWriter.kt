package net.asksakis.massdroidv2.ui.nfc

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.util.Log
import net.asksakis.massdroidv2.domain.nfc.NfcTagPayload

private const val TAG = "NfcWriter"

/** What became of holding a tag against the phone while it was waiting to write one. */
sealed interface NfcWriteResult {
    /**
     * @param tagId the tag's own serial, as hex. It is what tells two tags apart later, so
     * rewriting one replaces its record rather than adding a second.
     */
    data class Written(val tagId: String) : NfcWriteResult
    /** The tag is smaller than the instruction, so nothing was written to it. */
    data class TooSmall(val neededBytes: Int, val tagBytes: Int) : NfcWriteResult
    /** The tag was locked, either by its maker or by whoever wrote it last. */
    data object ReadOnly : NfcWriteResult
    /** A tag that cannot hold an NDEF message at all, so not one for this. */
    data object Unsupported : NfcWriteResult
    /** It was moved away mid-write, or the tag answered with an error. */
    data class Failed(val message: String?) : NfcWriteResult
}

/**
 * Writes a MassDroid instruction onto a tag held against the phone.
 *
 * Writing needs the app in front, because a tag goes to whoever is holding the reader, so
 * this binds to the activity that is showing the prompt and lets go with it. Reader mode is
 * used rather than the older foreground dispatch: it silences the platform's own scan sound,
 * which would otherwise fire on a tag the user is in the middle of writing, and it hands the
 * tag over directly instead of through an intent.
 *
 * The callback arrives on the main thread. The tag I/O itself is done on the reader thread
 * Android calls us on, which is where blocking work belongs.
 */
class NfcTagWriter(private val activity: Activity) {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    /** Whether this phone has an NFC chip at all. */
    val isAvailable: Boolean get() = adapter != null

    /** Whether NFC is switched on right now. The user turns it off in system settings. */
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    /**
     * Hold the reader for as long as the caller is showing a tag prompt.
     *
     * [payloadFor] is asked on every tag presented and decides what happens to it: an
     * instruction means write it, null means take the tag and do nothing with it. The
     * second case is the important one. Reader mode is what keeps the platform's own tag
     * dispatch from firing, so letting go of it while a tag is still against the phone
     * hands that tag straight to the dispatcher. Measured: a tag written at 10:13:57.104
     * had started playing its own album by 10:13:57.248, because the prompt stopped
     * reading the moment the write succeeded.
     */
    fun startWriting(
        payloadFor: () -> NfcTagPayload?,
        onResult: (NfcTagPayload, NfcWriteResult) -> Unit
    ) {
        val adapter = adapter ?: return
        adapter.enableReaderMode(
            activity,
            { tag ->
                val payload = payloadFor()
                if (payload == null) {
                    Log.d(TAG, "Tag held while not waiting for one; ignored")
                } else {
                    val result = write(tag, messageFor(payload))
                    Log.d(TAG, "Tag write: $result")
                    activity.runOnUiThread { onResult(payload, result) }
                }
            },
            READER_FLAGS,
            null
        )
    }

    fun stop() {
        adapter?.disableReaderMode(activity)
    }

    /**
     * One URI record, and nothing else.
     *
     * An Android Application Record would also fit and would pin the tag to one package,
     * which is exactly what makes it wrong here: a tag written by the release build would
     * then be refused by the debug build, and a tag written by either would be useless to
     * anyone whose app came from a different store listing. The scheme already sends the
     * tag to us and to nothing else.
     */
    private fun messageFor(payload: NfcTagPayload): NdefMessage =
        NdefMessage(arrayOf(NdefRecord.createUri(payload.toTagUri())))

    private fun write(tag: Tag, message: NdefMessage): NfcWriteResult {
        val bytes = message.toByteArray().size
        val tagId = tag.id.joinToString("") { "%02X".format(it) }
        Ndef.get(tag)?.let { ndef ->
            return try {
                ndef.connect()
                when {
                    !ndef.isWritable -> NfcWriteResult.ReadOnly
                    bytes > ndef.maxSize -> NfcWriteResult.TooSmall(bytes, ndef.maxSize)
                    else -> {
                        ndef.writeNdefMessage(message)
                        NfcWriteResult.Written(tagId)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Write failed: ${e.message}")
                NfcWriteResult.Failed(e.message)
            } finally {
                runCatching { ndef.close() }
            }
        }
        // A tag straight out of the packet holds no NDEF message yet, so there is nothing
        // for Ndef to attach to and it has to be formatted with the message in one go.
        val formatable = NdefFormatable.get(tag) ?: return NfcWriteResult.Unsupported
        return try {
            formatable.connect()
            formatable.format(message)
            NfcWriteResult.Written(tagId)
        } catch (e: Exception) {
            Log.w(TAG, "Format failed: ${e.message}")
            NfcWriteResult.Failed(e.message)
        } finally {
            runCatching { formatable.close() }
        }
    }

    private companion object {
        /**
         * Every tag technology an NDEF tag can speak, with the platform's own scan sound
         * turned off because the app is giving its own feedback.
         */
        const val READER_FLAGS = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
    }
}
