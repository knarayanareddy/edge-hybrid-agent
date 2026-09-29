package com.edgehybrid.agent.hardware.spen

sealed class SPenEvent {
    data object SingleClick : SPenEvent()
    data object DoubleClick : SPenEvent()
    data object LongPress : SPenEvent()
    data class AirGesture(val direction: String) : SPenEvent()
}
