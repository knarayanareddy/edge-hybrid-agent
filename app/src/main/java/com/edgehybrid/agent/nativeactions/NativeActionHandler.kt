package com.edgehybrid.agent.nativeactions

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.edgehybrid.agent.data.local.NoteDao
import com.edgehybrid.agent.data.local.NoteEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

@Singleton
class NativeActionHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val noteDao: NoteDao
) {
    private val flashlightMutex = Mutex()
    private var isTorchOn: Boolean = false

    suspend fun createCalendarEvent(
        title: String,
        startTime: Long? = null,
        endTime: Long? = null,
        description: String? = null,
        location: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, title)

        startTime?.let { intent.putExtra(CalendarContract.Events.DTSTART, it) }
        endTime?.let { intent.putExtra(CalendarContract.Events.DTEND, it) }
        description?.let { intent.putExtra(CalendarContract.Events.DESCRIPTION, it) }
        location?.let { intent.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }

        launchExternalIntent(intent)
    }

    suspend fun createQuickNote(title: String, content: String): Long =
        withContext(Dispatchers.IO) {
            noteDao.insertNote(
                NoteEntity(
                    title = title,
                    content = content
                )
            )
        }

    suspend fun setTimer(seconds: Int, message: String): Boolean =
        withContext(Dispatchers.IO) {
            if (seconds <= 0) {
                false
            } else {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, message)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)

                launchExternalIntent(intent)
            }
        }

    suspend fun prepareSms(phone: String, message: String): ActionConfirmation =
        withContext(Dispatchers.IO) {
            ActionConfirmation(
                id = UUID.randomUUID().toString(),
                tool = NativeTool.SEND_SMS.toolName,
                summary = "Send an SMS to $phone",
                params = mapOf(
                    "phone" to phone,
                    "message" to message
                )
            )
        }

    suspend fun sendSms(confirmation: ActionConfirmation): Boolean =
        withContext(Dispatchers.IO) {
            if (confirmation.tool != NativeTool.SEND_SMS.toolName) {
                false
            } else {
                val phone = confirmation.params["phone"] as? String
                val message = confirmation.params["message"] as? String

                if (phone == null || message == null) {
                    false
                } else {
                    launchExternalIntent(
                        Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone"))
                            .putExtra("sms_body", message)
                    )
                }
            }
        }

    @SuppressLint("MissingPermission")
    suspend fun toggleFlashlight(): Boolean = withContext(Dispatchers.IO) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager

        if (cameraManager == null) {
            false
        } else {
            flashlightMutex.withLock {
                try {
                    val cameraId = findTorchCamera(cameraManager)

                    if (cameraId == null) {
                        false
                    } else {
                        val enabled = !isTorchOn
                        cameraManager.setTorchMode(cameraId, enabled)
                        isTorchOn = enabled
                        enabled
                    }
                } catch (_: CameraAccessException) {
                    false
                } catch (_: IllegalArgumentException) {
                    false
                } catch (_: SecurityException) {
                    false
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun findTorchCamera(cameraManager: CameraManager): String? {
        var fallback: String? = null

        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = try {
                cameraManager.getCameraCharacteristics(cameraId)
            } catch (_: CameraAccessException) {
                null
            } ?: continue

            if (characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) {
                continue
            }

            if (characteristics.get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
            ) {
                return cameraId
            }

            if (fallback == null) {
                fallback = cameraId
            }
        }

        return fallback
    }

    private fun launchExternalIntent(intent: Intent): Boolean {
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}