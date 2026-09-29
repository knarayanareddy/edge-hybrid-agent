package com.edgehybrid.agent.hardware.spen

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Controller routing Samsung S Pen hardware gestures to agent capabilities.
 */
@Singleton
class SPenController @Inject constructor() {

    private val _events = MutableSharedFlow<SPenEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SPenEvent> = _events.asSharedFlow()

    fun onSingleClick() {
        _events.tryEmit(SPenEvent.SingleClick)
    }

    fun onDoubleClick() {
        _events.tryEmit(SPenEvent.DoubleClick)
    }

    fun onLongPress() {
        _events.tryEmit(SPenEvent.LongPress)
    }

    fun onAirGesture(direction: String) {
        _events.tryEmit(SPenEvent.AirGesture(direction))
    }
}
