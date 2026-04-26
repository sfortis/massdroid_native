package net.asksakis.massdroidv2.auto

import android.content.Context
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.asFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Observes Android Auto projection state via androidx.car.app CarConnection.
 *
 * Emits true when CONNECTION_TYPE_PROJECTION is active (phone projecting to a head unit via DHU,
 * USB Auto, or wireless AA) and false otherwise. AAOS native (CONNECTION_TYPE_NATIVE) is treated
 * as not-projecting because the app is shipped for AA on this branch.
 */
class AaProjectionObserver(context: Context) {

    private val carConnection = CarConnection(context.applicationContext)

    val isProjecting: Flow<Boolean> = carConnection.type
        .asFlow()
        .map { it == CarConnection.CONNECTION_TYPE_PROJECTION }
        .distinctUntilChanged()
}
