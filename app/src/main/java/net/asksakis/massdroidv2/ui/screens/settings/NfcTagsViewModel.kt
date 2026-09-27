package net.asksakis.massdroidv2.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.asksakis.massdroidv2.data.nfc.NfcTagRecord
import net.asksakis.massdroidv2.data.nfc.NfcTagStore
import javax.inject.Inject

/**
 * The tags this phone has written.
 *
 * Forgetting one only drops it from this list. The tag in the drawer still carries its
 * instruction and still works, which is said on screen so nobody expects otherwise.
 */
@HiltViewModel
class NfcTagsViewModel @Inject constructor(
    private val store: NfcTagStore
) : ViewModel() {

    val tags: StateFlow<List<NfcTagRecord>> = store.tags

    init {
        viewModelScope.launch { store.load() }
    }

    fun forget(tagId: String) {
        viewModelScope.launch { store.forget(tagId) }
    }
}
