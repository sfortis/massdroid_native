package net.asksakis.massdroidv2.data.nfc

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NfcTagStore"
private const val STORE_FILE = "nfc_tags.json"

/**
 * What this phone wrote onto one tag.
 *
 * The tag itself carries the same instruction, so nothing here is needed to act on a tap.
 * This exists so the user can see a list of the tags they made and what each one does,
 * which a tag held in the hand cannot tell them.
 */
@Serializable
data class NfcTagRecord(
    /** The tag's serial as hex, which is what identifies the physical tag. */
    val tagId: String,
    val mediaUri: String,
    val playerId: String?,
    val label: String,
    val playerName: String?,
    /** The level the tag sets before it starts, or null when it leaves the volume alone. */
    val volume: Int? = null,
    val writtenAtMs: Long
)

@Serializable
private data class NfcTagRegistry(val tags: List<NfcTagRecord> = emptyList())

/**
 * The record of tags written on this phone, kept as one JSON file.
 *
 * A file rather than Room, because this is a short list read whole and written whole, and
 * the same shape as the proximity config next to it. Losing it costs nothing that matters:
 * the tags keep working, they just stop being listed.
 */
@Singleton
class NfcTagStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json
) {
    private val file: File get() = File(context.filesDir, STORE_FILE)
    private val _tags = MutableStateFlow<List<NfcTagRecord>>(emptyList())
    val tags: StateFlow<List<NfcTagRecord>> = _tags.asStateFlow()
    private val mutex = Mutex()

    /**
     * Fill the list for the screen. A file that cannot be read leaves the list empty here,
     * which is only what is shown; it never becomes what gets written, because a write
     * reads the file itself.
     */
    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _tags.value = try {
                readFile()
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the tag record: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * Record a tag, replacing whatever that same physical tag held before. A tag is written
     * over in place, so keeping the old entry would list a tag that no longer does that.
     */
    suspend fun remember(record: NfcTagRecord) = update { tags ->
        tags.filterNot { it.tagId == record.tagId } + record
    }

    suspend fun forget(tagId: String) = update { tags -> tags.filterNot { it.tagId == tagId } }

    /**
     * Apply a change to what is on disk.
     *
     * The starting point is read from the file inside the lock rather than taken from the
     * published list, which may never have been loaded, or may have been emptied by a read
     * that failed. Building on that list would have written a registry holding only the new
     * record and dropped every tag written before it. A file that is present and cannot be
     * parsed aborts the write for the same reason: what is not understood is not overwritten.
     */
    private suspend fun update(transform: (List<NfcTagRecord>) -> List<NfcTagRecord>) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val updated = transform(readFile()).sortedByDescending { it.writtenAtMs }
                    file.writeText(json.encodeToString(NfcTagRegistry.serializer(), NfcTagRegistry(updated)))
                    _tags.value = updated
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save tag record: ${e.message}")
                }
            }
        }
    }

    /** The registry as it stands on disk. An absent file is an empty one; a broken file throws. */
    private fun readFile(): List<NfcTagRecord> =
        if (file.exists()) {
            json.decodeFromString<NfcTagRegistry>(file.readText()).tags
        } else {
            emptyList()
        }
}
