package net.asksakis.massdroidv2.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import net.asksakis.massdroidv2.data.util.AccountNoticeReporter
import net.asksakis.massdroidv2.data.util.DatabaseResetInfo
import net.asksakis.massdroidv2.data.util.DatabaseResetReporter
import net.asksakis.massdroidv2.data.util.ProviderHealthReporter
import javax.inject.Inject

/**
 * Exposes app-level, view-agnostic notices to the single global Snackbar host in [MassDroidApp].
 * Surfaces [ProviderHealthReporter.searchDegraded] (bulk MA resolution timed out because a music
 * provider is slow/rate-limited) and [AccountNoticeReporter.permissionDenied] (server rejected an
 * admin-gated command for a non-admin account, MA 2.9.0+), so no individual screen needs its own
 * failure plumbing, plus [DatabaseResetReporter.reset] (a migration failed and the listening
 * history was rebuilt empty).
 */
@HiltViewModel
class AppNoticesViewModel @Inject constructor(
    reporter: ProviderHealthReporter,
    accountNoticeReporter: AccountNoticeReporter,
    private val databaseResetReporter: DatabaseResetReporter
) : ViewModel() {
    val searchDegraded: SharedFlow<Unit> = reporter.searchDegraded
    val permissionDenied: SharedFlow<Unit> = accountNoticeReporter.permissionDenied

    /**
     * Set when a failed migration wiped the listening history. Shown as a dialog
     * rather than a snackbar: it needs to stay on screen long enough to be read
     * and screenshotted, since the version it names is what makes the bug fixable.
     */
    val databaseReset: StateFlow<DatabaseResetInfo?> = databaseResetReporter.reset

    fun acknowledgeDatabaseReset() = databaseResetReporter.acknowledge()
}
