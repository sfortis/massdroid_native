package net.asksakis.massdroidv2.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * The Music Assistant server the user signed in to, by its `server_id`.
 *
 * Automatic reconnects go to the saved address on whatever network the phone is on, so a
 * different machine can answer there. The client sends the token, and after a refused token the
 * password, only to this server; an explicit sign-in trusts the server that answers it.
 */
interface TrustedServerStore {
    val trustedServerId: Flow<String?>
    suspend fun setTrustedServerId(serverId: String?)
}
