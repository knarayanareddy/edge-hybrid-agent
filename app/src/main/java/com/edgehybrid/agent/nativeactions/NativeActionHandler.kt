package com.edgehybrid.agent.nativeactions

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.edgehybrid.agent.data.local.NoteDao
import com.edgehybrid.agent.data.local.NoteEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@Singleton
class NativeActionHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val noteDao: NoteDao
) {
    private val flashlightMutex = Mutex()
    private var isTorchOn: Boolean = false

    init {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            cameraManager?.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    isTorchOn = enabled
                }
            }, null)
        } catch (_: Exception) {}
    }

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

    suspend fun setAlarm(hour: Int, minutes: Int, message: String): Boolean =
        withContext(Dispatchers.IO) {
            if (hour !in 0..23 || minutes !in 0..59) {
                false
            } else {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, hour)
                    .putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, message)
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                launchExternalIntent(intent)
            }
        }

    suspend fun launchCamera(): Boolean =
        withContext(Dispatchers.IO) {
            val captureIntent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            if (launchExternalIntent(captureIntent)) {
                true
            } else {
                val cameraAppIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_CAMERA)
                }
                launchExternalIntent(cameraAppIntent)
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
    suspend fun toggleFlashlight(targetState: Boolean? = null): Boolean = withContext(Dispatchers.IO) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return@withContext false

        flashlightMutex.withLock {
            try {
                val cameraId = findTorchCamera(cameraManager)
                    ?: cameraManager.cameraIdList.firstOrNull()
                    ?: "0"

                val desired = targetState ?: !isTorchOn
                cameraManager.setTorchMode(cameraId, desired)
                isTorchOn = desired
                desired
            } catch (_: Exception) {
                false
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

    suspend fun queryUpcomingEvents(daysAhead: Int = 1): String = withContext(Dispatchers.IO) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            val errObj = JSONObject().apply {
                put("permission_granted", false)
                put("message", "READ_CALENDAR permission is not granted yet. Please allow calendar access in Android settings.")
            }
            return@withContext errObj.toString()
        }

        val beginTime = System.currentTimeMillis()
        val endTime = beginTime + (daysAhead.coerceIn(1, 30) * 24 * 60 * 60 * 1000L)
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, beginTime)
        ContentUris.appendId(builder, endTime)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.EVENT_LOCATION
        )

        val eventsArray = JSONArray()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        try {
            val cursor = context.contentResolver.query(
                builder.build(),
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )

            cursor?.use {
                val titleCol = it.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginCol = it.getColumnIndex(CalendarContract.Instances.BEGIN)
                val endCol = it.getColumnIndex(CalendarContract.Instances.END)
                val locCol = it.getColumnIndex(CalendarContract.Instances.EVENT_LOCATION)

                var count = 0
                while (it.moveToNext() && count < 20) {
                    val title = if (titleCol >= 0) it.getString(titleCol) ?: "Untitled Event" else "Untitled"
                    val startMs = if (beginCol >= 0) it.getLong(beginCol) else 0L
                    val endMs = if (endCol >= 0) it.getLong(endCol) else 0L
                    val location = if (locCol >= 0) it.getString(locCol) ?: "" else ""

                    val eventObj = JSONObject().apply {
                        put("title", title)
                        put("start", if (startMs > 0) dateFormat.format(Date(startMs)) else "Unknown")
                        put("end", if (endMs > 0) dateFormat.format(Date(endMs)) else "Unknown")
                        if (location.isNotBlank()) put("location", location)
                    }
                    eventsArray.put(eventObj)
                    count++
                }
            }
        } catch (e: Exception) {
            val errObj = JSONObject().apply {
                put("error", e.message ?: "Failed to read calendar database")
            }
            return@withContext errObj.toString()
        }

        val resultObj = JSONObject().apply {
            put("days_ahead", daysAhead)
            put("events_count", eventsArray.length())
            put("events", eventsArray)
            if (eventsArray.length() == 0) {
                put("note", "No events scheduled in the next $daysAhead day(s)")
            }
        }
        resultObj.toString()
    }

    suspend fun shareToTelegram(text: String): Boolean = withContext(Dispatchers.IO) {
        val telegramIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            setPackage("org.telegram.messenger")
        }

        if (launchExternalIntent(telegramIntent)) {
            true
        } else {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://t.me/share/url?text=" + Uri.encode(text))
            )
            launchExternalIntent(webIntent)
        }
    }

    suspend fun searchContacts(query: String): String = withContext(Dispatchers.IO) {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPerm) {
            return@withContext JSONObject().apply {
                put("permission_granted", false)
                put("message", "READ_CONTACTS permission is required to search contacts on your phone.")
            }.toString()
        }

        val contacts = JSONArray()
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%$query%")

        try {
            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                var count = 0
                while (it.moveToNext() && count < 10) {
                    val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "Unknown" else "Unknown"
                    val number = if (numIdx >= 0) it.getString(numIdx) ?: "" else ""
                    contacts.put(JSONObject().apply {
                        put("name", name)
                        put("phone", number)
                    })
                    count++
                }
            }
        } catch (e: Exception) {
            return@withContext JSONObject().apply { put("error", e.message ?: "Failed to read contacts") }.toString()
        }

        JSONObject().apply {
            put("query", query)
            put("count", contacts.length())
            put("contacts", contacts)
            if (contacts.length() == 0) put("message", "No contacts found matching '$query'")
        }.toString()
    }

    suspend fun initiatePhoneCall(phoneNumber: String): Boolean = withContext(Dispatchers.IO) {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleanNumber"))
        launchExternalIntent(intent)
    }

    suspend fun draftEmail(recipient: String, subject: String, body: String): Boolean = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:" + Uri.encode(recipient.trim()))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        launchExternalIntent(intent)
    }

    suspend fun controlSpotify(query: String): Boolean = withContext(Dispatchers.IO) {
        val encoded = Uri.encode(query.trim())
        val spotifyIntent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encoded")).apply {
            setPackage("com.spotify.music")
        }
        if (launchExternalIntent(spotifyIntent)) {
            true
        } else {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/$encoded"))
            launchExternalIntent(webIntent)
        }
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