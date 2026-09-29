package com.edgehybrid.agent.hardware.spen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * BroadcastReceiver capturing Samsung S Pen Remote button intents and forwarding to SPenController.
 */
@AndroidEntryPoint
class SPenReceiver : BroadcastReceiver() {

    @Inject
    lateinit var sPenController: SPenController

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            "com.samsung.android.service.aircommand.action.BUTTON_CLICK",
            "com.samsung.android.airbutton.action.CLICK" -> {
                val clickType = intent.getIntExtra("click_type", 1)
                when (clickType) {
                    1 -> sPenController.onSingleClick()
                    2 -> sPenController.onDoubleClick()
                    3 -> sPenController.onLongPress()
                }
            }
            "com.samsung.android.service.aircommand.action.AIR_GESTURE" -> {
                val direction = intent.getStringExtra("gesture_direction") ?: "UNKNOWN"
                sPenController.onAirGesture(direction)
            }
        }
    }
}
