package com.edgehybrid.agent.tool

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import com.edgehybrid.agent.data.local.NoteDao
import com.edgehybrid.agent.data.local.NoteEntity
import com.edgehybrid.agent.data.local.SecureKeyStore
import com.edgehybrid.agent.data.model.FunctionDefinition
import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.data.model.ToolDefinition
import com.edgehybrid.agent.nativeactions.NativeActionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class ToolExecutionOutcome(
    val content: String,
    val isError: Boolean
)

interface SkillLoader {
    suspend fun listTools(): List<ToolDefinition>

    suspend fun execute(call: ModelToolCall): ToolExecutionOutcome
}

class SkillExecutionException(message: String) : RuntimeException(message)

@Singleton
class BuiltInSkillLoader @Inject constructor(
    private val httpClient: HttpClient,
    private val nativeActionHandler: NativeActionHandler,
    private val noteDao: NoteDao,
    private val keyStore: SecureKeyStore,
    @ApplicationContext private val context: Context
) : SkillLoader {

    override suspend fun listTools(): List<ToolDefinition> =
        listOf(
            // 1. Weather
            ToolDefinition(
                function = FunctionDefinition(
                    name = WEATHER_TOOL_NAME,
                    description = "Get current weather conditions and temperature for any city in the world.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("location", buildJsonObject {
                                put("type", "string")
                                put("description", "City name, for example Amsterdam, Tokyo, or New York.")
                            })
                        })
                        put("required", buildJsonArray { add("location") })
                        put("additionalProperties", false)
                    }
                )
            ),
            // 2. Temperature Conversion
            ToolDefinition(
                function = FunctionDefinition(
                    name = CONVERSION_TOOL_NAME,
                    description = "Convert a numeric temperature between Celsius, Fahrenheit, Kelvin, and Rankine.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("value", buildJsonObject {
                                put("type", "number")
                                put("description", "Temperature value to convert.")
                            })
                            put("from", buildJsonObject {
                                put("type", "string")
                                put("description", "Source unit: C, F, K, or R.")
                            })
                            put("to", buildJsonObject {
                                put("type", "string")
                                put("description", "Target unit: C, F, K, or R.")
                            })
                        })
                        put("required", buildJsonArray {
                            add("value")
                            add("from")
                            add("to")
                        })
                        put("additionalProperties", false)
                    }
                )
            ),
            // 3. Wikipedia Search & Research
            ToolDefinition(
                function = FunctionDefinition(
                    name = WIKIPEDIA_TOOL_NAME,
                    description = "Search Wikipedia for detailed factual information about companies, historical events, scientific concepts, places, or famous entities.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("query", buildJsonObject {
                                put("type", "string")
                                put("description", "The subject, company, person, or concept to research (e.g. 'Achmea', 'ASML', 'Amsterdam').")
                            })
                        })
                        put("required", buildJsonArray { add("query") })
                        put("additionalProperties", false)
                    }
                )
            ),
            // 4. Currency Conversion
            ToolDefinition(
                function = FunctionDefinition(
                    name = CURRENCY_TOOL_NAME,
                    description = "Convert currency amounts in real-time using current exchange rates (EUR, USD, GBP, JPY, INR, CAD, AUD, etc.).",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("amount", buildJsonObject {
                                put("type", "number")
                                put("description", "The numeric monetary amount to convert.")
                            })
                            put("from", buildJsonObject {
                                put("type", "string")
                                put("description", "3-letter source currency code, e.g. USD, EUR, GBP.")
                            })
                            put("to", buildJsonObject {
                                put("type", "string")
                                put("description", "3-letter target currency code, e.g. EUR, JPY, INR.")
                            })
                        })
                        put("required", buildJsonArray {
                            add("amount")
                            add("from")
                            add("to")
                        })
                        put("additionalProperties", false)
                    }
                )
            ),
            // 5. Scientific Calculator & Math
            ToolDefinition(
                function = FunctionDefinition(
                    name = MATH_TOOL_NAME,
                    description = "Evaluate mathematical expressions, formulas, percentages, exponents, powers, and roots.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("expression", buildJsonObject {
                                put("type", "string")
                                put("description", "Mathematical expression to evaluate, e.g. '(150 * 0.21) + 45' or 'sqrt(144) * 5'.")
                            })
                        })
                        put("required", buildJsonArray { add("expression") })
                        put("additionalProperties", false)
                    }
                )
            ),
            // 6. World Clock & Timezone
            ToolDefinition(
                function = FunctionDefinition(
                    name = WORLD_TIME_TOOL_NAME,
                    description = "Look up the current local time, date, day of week, and UTC timezone offset for any major world city.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("city", buildJsonObject {
                                put("type", "string")
                                put("description", "City name, e.g. Amsterdam, Tokyo, London, New York, San Francisco.")
                            })
                        })
                        put("required", buildJsonArray { add("city") })
                        put("additionalProperties", false)
                    }
                )
            ),
            // 7. Device Status
            ToolDefinition(
                function = FunctionDefinition(
                    name = DEVICE_STATUS_TOOL_NAME,
                    description = "Get device hardware status including battery percentage, charging state, available RAM, and network connection type.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {})
                        put("additionalProperties", false)
                    }
                )
            ),
            // 8. Flashlight
            ToolDefinition(
                function = FunctionDefinition(
                    name = FLASHLIGHT_TOOL_NAME,
                    description = "Toggle the device physical camera flashlight / torch on or off.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {})
                        put("additionalProperties", false)
                    }
                )
            ),
            // 9. Countdown Timer
            ToolDefinition(
                function = FunctionDefinition(
                    name = TIMER_TOOL_NAME,
                    description = "Set an on-device countdown timer or alarm.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("seconds", buildJsonObject {
                                put("type", "integer")
                                put("description", "Duration of the timer in seconds.")
                            })
                            put("message", buildJsonObject {
                                put("type", "string")
                                put("description", "Label or description for the timer.")
                            })
                        })
                        put("required", buildJsonArray {
                            add("seconds")
                            add("message")
                        })
                        put("additionalProperties", false)
                    }
                )
            ),
            // 10. Quick Note Creation
            ToolDefinition(
                function = FunctionDefinition(
                    name = CREATE_NOTE_TOOL_NAME,
                    description = "Save a quick note or reminder to the secure on-device database.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("title", buildJsonObject {
                                put("type", "string")
                                put("description", "Title of the note.")
                            })
                            put("content", buildJsonObject {
                                put("type", "string")
                                put("description", "Body content or details of the note.")
                            })
                        })
                        put("required", buildJsonArray {
                            add("title")
                            add("content")
                        })
                        put("additionalProperties", false)
                    }
                )
            ),
            // 11. Quick Note Listing
            ToolDefinition(
                function = FunctionDefinition(
                    name = LIST_NOTES_TOOL_NAME,
                    description = "Retrieve recent notes saved locally on the device.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {})
                        put("additionalProperties", false)
                    }
                )
            ),
            // 12. Create Calendar Event
            ToolDefinition(
                function = FunctionDefinition(
                    name = CREATE_CALENDAR_EVENT_TOOL_NAME,
                    description = "Schedule or add a new event or meeting to Google Calendar on the device.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("title", buildJsonObject {
                                put("type", "string")
                                put("description", "Title or subject of the meeting/event.")
                            })
                            put("description", buildJsonObject {
                                put("type", "string")
                                put("description", "Optional notes, agenda, or description for the event.")
                            })
                            put("location", buildJsonObject {
                                put("type", "string")
                                put("description", "Optional event location or meeting room.")
                            })
                            put("duration_minutes", buildJsonObject {
                                put("type", "integer")
                                put("description", "Duration in minutes (defaults to 60).")
                            })
                        })
                        put("required", buildJsonArray { add("title") })
                        put("additionalProperties", false)
                    }
                )
            ),
            // 13. Query Calendar Events
            ToolDefinition(
                function = FunctionDefinition(
                    name = QUERY_CALENDAR_EVENTS_TOOL_NAME,
                    description = "Query upcoming events, meetings, and appointments from Google Calendar on the device.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("days_ahead", buildJsonObject {
                                put("type", "integer")
                                put("description", "Number of days ahead to search (default 1 for today, 7 for this week).")
                            })
                        })
                        put("additionalProperties", false)
                    }
                )
            ),
            // 14. Telegram Messaging
            ToolDefinition(
                function = FunctionDefinition(
                    name = SEND_TELEGRAM_MESSAGE_TOOL_NAME,
                    description = "Send a message, note, meeting summary, or alert to Telegram. Dispatches via Telegram Bot API or opens Telegram directly.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("message", buildJsonObject {
                                put("type", "string")
                                put("description", "Text message or summary to send.")
                            })
                            put("chat_id", buildJsonObject {
                                put("type", "string")
                                put("description", "Optional Telegram chat ID or channel username.")
                            })
                        })
                        put("required", buildJsonArray { add("message") })
                        put("additionalProperties", false)
                    }
                )
            ),
            // 15. Search Contacts
            ToolDefinition(
                function = FunctionDefinition(
                    name = SEARCH_CONTACTS_TOOL_NAME,
                    description = "Search your device contacts by name to find their phone numbers and details.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("query", buildJsonObject {
                                put("type", "string")
                                put("description", "Name or name fragment of the contact to find.")
                            })
                        })
                        put("required", buildJsonArray { add("query") })
                        put("additionalProperties", false)
                    }
                )
            ),
            // 16. Initiate Phone Call
            ToolDefinition(
                function = FunctionDefinition(
                    name = INITIATE_PHONE_CALL_TOOL_NAME,
                    description = "Open the phone dialer with a specific phone number ready to call.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("phone_number", buildJsonObject {
                                put("type", "string")
                                put("description", "Phone number to dial.")
                            })
                        })
                        put("required", buildJsonArray { add("phone_number") })
                        put("additionalProperties", false)
                    }
                )
            ),
            // 17. Draft Email
            ToolDefinition(
                function = FunctionDefinition(
                    name = DRAFT_EMAIL_TOOL_NAME,
                    description = "Draft and prepare an email with a recipient, subject, and body content.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("recipient", buildJsonObject {
                                put("type", "string")
                                put("description", "Recipient email address.")
                            })
                            put("subject", buildJsonObject {
                                put("type", "string")
                                put("description", "Subject line of the email.")
                            })
                            put("body", buildJsonObject {
                                put("type", "string")
                                put("description", "Body content or meeting notes.")
                            })
                        })
                        put("required", buildJsonArray {
                            add("recipient")
                            add("subject")
                            add("body")
                        })
                        put("additionalProperties", false)
                    }
                )
            ),
            // 18. Control Spotify
            ToolDefinition(
                function = FunctionDefinition(
                    name = CONTROL_SPOTIFY_TOOL_NAME,
                    description = "Search and launch music playback on Spotify for any song, artist, album, or playlist.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("query", buildJsonObject {
                                put("type", "string")
                                put("description", "Search query, for example artist name, track title, or genre.")
                            })
                        })
                        put("required", buildJsonArray { add("query") })
                        put("additionalProperties", false)
                    }
                )
            ),
            // 19. Live Web Search
            ToolDefinition(
                function = FunctionDefinition(
                    name = LIVE_WEB_SEARCH_TOOL_NAME,
                    description = "Perform a live, privacy-preserving web search for real-time information, news, and technical topics.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("query", buildJsonObject {
                                put("type", "string")
                                put("description", "Search query terms.")
                            })
                        })
                        put("required", buildJsonArray { add("query") })
                        put("additionalProperties", false)
                    }
                )
            ),
            // 20. Extract Webpage Content
            ToolDefinition(
                function = FunctionDefinition(
                    name = EXTRACT_WEBPAGE_TOOL_NAME,
                    description = "Fetch, extract, and read clean text content from any public HTTP or HTTPS web page URL.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("url", buildJsonObject {
                                put("type", "string")
                                put("description", "Full URL of the web page to extract (e.g. https://example.com).")
                            })
                        })
                        put("required", buildJsonArray { add("url") })
                        put("additionalProperties", false)
                    }
                )
            )
        )

    override suspend fun execute(call: ModelToolCall): ToolExecutionOutcome =
        try {
            when (call.function.name) {
                WEATHER_TOOL_NAME -> executeWeather(call.function.arguments)
                CONVERSION_TOOL_NAME -> executeTemperatureConversion(call.function.arguments)
                WIKIPEDIA_TOOL_NAME -> executeWikipediaSearch(call.function.arguments)
                CURRENCY_TOOL_NAME -> executeCurrencyConversion(call.function.arguments)
                MATH_TOOL_NAME -> executeMath(call.function.arguments)
                WORLD_TIME_TOOL_NAME -> executeWorldTime(call.function.arguments)
                DEVICE_STATUS_TOOL_NAME -> executeDeviceStatus()
                FLASHLIGHT_TOOL_NAME -> executeFlashlight()
                TIMER_TOOL_NAME -> executeTimer(call.function.arguments)
                CREATE_NOTE_TOOL_NAME -> executeCreateNote(call.function.arguments)
                LIST_NOTES_TOOL_NAME -> executeListNotes()
                CREATE_CALENDAR_EVENT_TOOL_NAME -> executeCreateCalendarEvent(call.function.arguments)
                QUERY_CALENDAR_EVENTS_TOOL_NAME -> executeQueryCalendarEvents(call.function.arguments)
                SEND_TELEGRAM_MESSAGE_TOOL_NAME -> executeTelegramMessage(call.function.arguments)
                SEARCH_CONTACTS_TOOL_NAME -> executeSearchContacts(call.function.arguments)
                INITIATE_PHONE_CALL_TOOL_NAME -> executeInitiatePhoneCall(call.function.arguments)
                DRAFT_EMAIL_TOOL_NAME -> executeDraftEmail(call.function.arguments)
                CONTROL_SPOTIFY_TOOL_NAME -> executeControlSpotify(call.function.arguments)
                LIVE_WEB_SEARCH_TOOL_NAME -> executeLiveWebSearch(call.function.arguments)
                EXTRACT_WEBPAGE_TOOL_NAME -> executeExtractWebpage(call.function.arguments)
                else -> throw SkillExecutionException("Unsupported skill: ${call.function.name}")
            }
        } catch (e: Exception) {
            ToolExecutionOutcome(
                content = buildJsonObject {
                    put("error", e.message ?: "Tool execution failed")
                    put("tool", call.function.name)
                }.toString(),
                isError = true
            )
        }

    // 1. Weather Implementation
    private suspend fun executeWeather(arguments: JsonObject): ToolExecutionOutcome {
        val location = arguments["location"]?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw SkillExecutionException("Weather location is required")

        val geocodingResponse = httpClient.get("https://geocoding-api.open-meteo.com/v1/search") {
            parameter("name", location)
            parameter("count", 1)
            parameter("language", "en")
            parameter("format", "json")
        }

        if (geocodingResponse.status.value !in 200..299) {
            throw SkillExecutionException("Weather lookup failed HTTP ${geocodingResponse.status.value}")
        }

        val geocoding = geocodingResponse.body<JsonObject>()
        val locationResult = geocoding["results"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw SkillExecutionException("No location found for '$location'")

        val lat = locationResult["latitude"]?.jsonPrimitive?.doubleOrNull
        val lon = locationResult["longitude"]?.jsonPrimitive?.doubleOrNull
        if (lat == null || lon == null) throw SkillExecutionException("Coordinates unavailable")

        val weatherResponse = httpClient.get("https://api.open-meteo.com/v1/forecast") {
            parameter("latitude", lat)
            parameter("longitude", lon)
            parameter("current", "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m")
            parameter("timezone", "auto")
        }

        val weather = weatherResponse.body<JsonObject>()
        val current = weather["current"]?.jsonObject
            ?: throw SkillExecutionException("No current weather data")

        val temp = current["temperature_2m"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val result = buildJsonObject {
            put("location", locationResult["name"]?.jsonPrimitive?.content ?: location)
            put("country", locationResult["country"]?.jsonPrimitive?.content ?: "")
            put("temperature_c", roundToOneDecimal(temp))
            put("apparent_temperature_c", roundToOneDecimal(current["apparent_temperature"]?.jsonPrimitive?.doubleOrNull ?: temp))
            put("relative_humidity_percent", current["relative_humidity_2m"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
            put("wind_speed_kmh", current["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull ?: 0.0)
            put("observed_at", current["time"]?.jsonPrimitive?.content ?: "")
        }

        return ToolExecutionOutcome(result.toString(), false)
    }

    // 2. Temperature Conversion
    private fun executeTemperatureConversion(arguments: JsonObject): ToolExecutionOutcome {
        val value = arguments["value"]?.jsonPrimitive?.doubleOrNull
            ?: throw SkillExecutionException("Temperature value must be numeric")
        val source = arguments["from"]?.jsonPrimitive?.content?.trim()?.uppercase(Locale.ROOT)
            ?: throw SkillExecutionException("Source temperature unit required")
        val target = arguments["to"]?.jsonPrimitive?.content?.trim()?.uppercase(Locale.ROOT)
            ?: throw SkillExecutionException("Target temperature unit required")

        val sourceKelvin = when (source) {
            "C", "CELSIUS" -> value + 273.15
            "F", "FAHRENHEIT" -> (value - 32.0) * 5.0 / 9.0 + 273.15
            "K", "KELVIN" -> value
            "R", "RANKINE" -> (value - 491.67) * 5.0 / 9.0
            else -> throw SkillExecutionException("Unsupported source unit: $source")
        }

        val converted = when (target) {
            "C", "CELSIUS" -> sourceKelvin - 273.15
            "F", "FAHRENHEIT" -> (sourceKelvin - 273.15) * 9.0 / 5.0 + 32.0
            "K", "KELVIN" -> sourceKelvin
            "R", "RANKINE" -> (sourceKelvin * 9.0 / 5.0) + 491.67
            else -> throw SkillExecutionException("Unsupported target unit: $target")
        }

        val result = buildJsonObject {
            put("value", roundToOneDecimal(value))
            put("from", source)
            put("to", target)
            put("result", roundToOneDecimal(converted))
        }
        return ToolExecutionOutcome(result.toString(), false)
    }

    // 3. Wikipedia Search
    private suspend fun executeWikipediaSearch(arguments: JsonObject): ToolExecutionOutcome {
        val query = arguments["query"]?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw SkillExecutionException("Wikipedia query is required")

        val encoded = URLEncoder.encode(query.replace(' ', '_'), StandardCharsets.UTF_8.name())
        val url = "https://en.wikipedia.org/api/rest_v1/page/summary/$encoded"

        val response = httpClient.get(url)
        if (response.status.value !in 200..299) {
            return ToolExecutionOutcome(
                buildJsonObject {
                    put("query", query)
                    put("found", false)
                    put("message", "No Wikipedia summary found for '$query'")
                }.toString(),
                false
            )
        }

        val body = response.body<JsonObject>()
        val title = body["title"]?.jsonPrimitive?.content ?: query
        val description = body["description"]?.jsonPrimitive?.content ?: ""
        val extract = body["extract"]?.jsonPrimitive?.content ?: ""

        val result = buildJsonObject {
            put("query", query)
            put("title", title)
            put("description", description)
            put("summary", extract)
            put("found", true)
        }
        return ToolExecutionOutcome(result.toString(), false)
    }

    // 4. Currency Conversion
    private suspend fun executeCurrencyConversion(arguments: JsonObject): ToolExecutionOutcome {
        val amount = arguments["amount"]?.jsonPrimitive?.doubleOrNull
            ?: throw SkillExecutionException("Monetary amount must be a number")
        val from = arguments["from"]?.jsonPrimitive?.content?.trim()?.uppercase(Locale.ROOT)
            ?: throw SkillExecutionException("Source currency code required (e.g. USD)")
        val to = arguments["to"]?.jsonPrimitive?.content?.trim()?.uppercase(Locale.ROOT)
            ?: throw SkillExecutionException("Target currency code required (e.g. EUR)")

        val response = httpClient.get("https://open.er-api.com/v6/latest/$from")
        if (response.status.value !in 200..299) {
            throw SkillExecutionException("Currency rate service returned HTTP ${response.status.value}")
        }

        val body = response.body<JsonObject>()
        val rates = body["rates"]?.jsonObject
            ?: throw SkillExecutionException("Unable to parse exchange rates")

        val rate = rates[to]?.jsonPrimitive?.doubleOrNull
            ?: throw SkillExecutionException("Unsupported currency rate for: $to")

        val converted = amount * rate
        val result = buildJsonObject {
            put("amount", amount)
            put("from", from)
            put("to", to)
            put("rate", rate)
            put("result", roundToOneDecimal(converted))
        }
        return ToolExecutionOutcome(result.toString(), false)
    }

    // 5. Scientific Math Evaluator
    private fun executeMath(arguments: JsonObject): ToolExecutionOutcome {
        val expr = arguments["expression"]?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw SkillExecutionException("Math expression is required")

        val answer = evaluateMathExpression(expr)
        val result = buildJsonObject {
            put("expression", expr)
            put("result", answer)
        }
        return ToolExecutionOutcome(result.toString(), false)
    }

    // 6. World Time & Timezone
    private fun executeWorldTime(arguments: JsonObject): ToolExecutionOutcome {
        val city = arguments["city"]?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw SkillExecutionException("City name is required")

        val cityLower = city.lowercase(Locale.ROOT)
        val zone = when {
            cityLower.contains("amsterdam") -> ZoneId.of("Europe/Amsterdam")
            cityLower.contains("london") -> ZoneId.of("Europe/London")
            cityLower.contains("new york") -> ZoneId.of("America/New_York")
            cityLower.contains("tokyo") -> ZoneId.of("Asia/Tokyo")
            cityLower.contains("paris") -> ZoneId.of("Europe/Paris")
            cityLower.contains("berlin") -> ZoneId.of("Europe/Berlin")
            cityLower.contains("sydney") -> ZoneId.of("Australia/Sydney")
            cityLower.contains("singapore") -> ZoneId.of("Asia/Singapore")
            cityLower.contains("dubai") -> ZoneId.of("Asia/Dubai")
            cityLower.contains("san francisco") || cityLower.contains("los angeles") -> ZoneId.of("America/Los_Angeles")
            cityLower.contains("chicago") -> ZoneId.of("America/Chicago")
            cityLower.contains("delhi") || cityLower.contains("mumbai") || cityLower.contains("bangalore") -> ZoneId.of("Asia/Kolkata")
            else -> ZoneId.getAvailableZoneIds().firstOrNull { it.lowercase().contains(cityLower) }
                ?.let(ZoneId::of) ?: ZoneId.systemDefault()
        }

        val now = ZonedDateTime.now(zone)
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
        val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

        val result = buildJsonObject {
            put("city", city)
            put("timezone", zone.id)
            put("time", now.format(timeFmt))
            put("date", now.format(dateFmt))
            put("day_of_week", now.dayOfWeek.name)
            put("utc_offset", now.offset.toString())
        }
        return ToolExecutionOutcome(result.toString(), false)
    }

    // 7. Device Status
    private fun executeDeviceStatus(): ToolExecutionOutcome {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager?.getMemoryInfo(memInfo)
        val availMemMb = memInfo.availMem / (1024 * 1024)
        val totalMemMb = memInfo.totalMem / (1024 * 1024)

        val connManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = connManager?.activeNetwork
        val caps = connManager?.getNetworkCapabilities(activeNetwork)
        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

        val result = buildJsonObject {
            put("battery_percent", batteryPct)
            put("is_charging", isCharging)
            put("available_ram_mb", availMemMb)
            put("total_ram_mb", totalMemMb)
            put("network", when {
                isWifi -> "WiFi"
                isCellular -> "Cellular 5G/4G"
                else -> "Offline"
            })
        }
        return ToolExecutionOutcome(result.toString(), false)
    }

    // 8. Flashlight
    private suspend fun executeFlashlight(): ToolExecutionOutcome {
        val state = nativeActionHandler.toggleFlashlight()
        val result = buildJsonObject {
            put("flashlight_enabled", state)
            put("status", if (state) "Flashlight turned ON" else "Flashlight turned OFF")
        }
        return ToolExecutionOutcome(result.toString(), false)
    }

    // 9. Timer
    private suspend fun executeTimer(arguments: JsonObject): ToolExecutionOutcome {
        val seconds = arguments["seconds"]?.jsonPrimitive?.intOrNull ?: 60
        val message = arguments["message"]?.jsonPrimitive?.content ?: "Agent Timer"
        val success = nativeActionHandler.setTimer(seconds, message)
        val result = buildJsonObject {
            put("timer_started", success)
            put("seconds", seconds)
            put("label", message)
        }
        return ToolExecutionOutcome(result.toString(), false)
    }

    // 10. Create Quick Note
    private suspend fun executeCreateNote(arguments: JsonObject): ToolExecutionOutcome {
        val title = arguments["title"]?.jsonPrimitive?.content ?: "Note"
        val content = arguments["content"]?.jsonPrimitive?.content ?: ""
        val id = noteDao.insertNote(NoteEntity(title = title, content = content))
        val result = buildJsonObject {
            put("note_id", id)
            put("title", title)
            put("status", "Note saved securely on device")
        }
        return ToolExecutionOutcome(result.toString(), false)
    }

    // 11. List Quick Notes
    private suspend fun executeListNotes(): ToolExecutionOutcome {
        val notes = noteDao.getRecentNotes(10)
        val result = buildJsonObject {
            put("total_notes", notes.size)
            put("notes", buildJsonArray {
                notes.forEach { note ->
                    add(buildJsonObject {
                        put("id", note.id)
                        put("title", note.title)
                        put("content", note.content)
                    })
                }
            })
        }
        return ToolExecutionOutcome(result.toString(), false)
    }

    private fun roundToOneDecimal(value: Double): Double =
        kotlin.math.round(value * 100.0) / 100.0

    private fun evaluateMathExpression(expr: String): Double {
        val clean = expr.replace(" ", "").lowercase(Locale.ROOT)
        var pos = -1
        var ch = -1

        fun nextChar() {
            ch = if (++pos < clean.length) clean[pos].code else -1
        }

        fun eat(charToEat: Int): Boolean {
            while (ch == ' '.code) nextChar()
            if (ch == charToEat) {
                nextChar()
                return true
            }
            return false
        }

        fun parseFactor(): Double {
            if (eat('+'.code)) return +parseFactor()
            if (eat('-'.code)) return -parseFactor()

            var x: Double
            val startPos = pos
            if (eat('('.code)) {
                x = parseFactor()
                while (true) {
                    when {
                        eat('+'.code) -> x += parseFactor()
                        eat('-'.code) -> x -= parseFactor()
                        eat('*'.code) -> x *= parseFactor()
                        eat('/'.code) -> x /= parseFactor()
                        else -> break
                    }
                }
                eat(')'.code)
            } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                x = clean.substring(startPos, pos).toDouble()
            } else if (ch in 'a'.code..'z'.code) {
                while (ch in 'a'.code..'z'.code) nextChar()
                val func = clean.substring(startPos, pos)
                x = parseFactor()
                x = when (func) {
                    "sqrt" -> kotlin.math.sqrt(x)
                    "abs" -> kotlin.math.abs(x)
                    "round" -> kotlin.math.round(x)
                    "sin" -> kotlin.math.sin(Math.toRadians(x))
                    "cos" -> kotlin.math.cos(Math.toRadians(x))
                    "tan" -> kotlin.math.tan(Math.toRadians(x))
                    else -> throw IllegalArgumentException("Unknown function: $func")
                }
            } else {
                throw IllegalArgumentException("Unexpected character: " + ch.toChar())
            }

            if (eat('^'.code)) x = Math.pow(x, parseFactor())
            return x
        }

        fun parseTerm(): Double {
            var x = parseFactor()
            while (true) {
                when {
                    eat('*'.code) -> x *= parseFactor()
                    eat('/'.code) -> {
                        val denom = parseFactor()
                        if (denom == 0.0) throw ArithmeticException("Division by zero")
                        x /= denom
                    }
                    eat('%'.code) -> x %= parseFactor()
                    else -> return x
                }
            }
        }

        fun parseExpression(): Double {
            var x = parseTerm()
            while (true) {
                when {
                    eat('+'.code) -> x += parseTerm()
                    eat('-'.code) -> x -= parseTerm()
                    else -> return x
                }
            }
        }

        nextChar()
        return parseExpression()
    }

    // 12. Create Calendar Event
    private suspend fun executeCreateCalendarEvent(arguments: JsonObject): ToolExecutionOutcome {
        val title = arguments["title"]?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw SkillExecutionException("Calendar event title is required")
        val description = arguments["description"]?.jsonPrimitive?.content?.trim()
        val location = arguments["location"]?.jsonPrimitive?.content?.trim()
        val durationMinutes = arguments["duration_minutes"]?.jsonPrimitive?.intOrNull ?: 60

        val startTime = System.currentTimeMillis() + 3600_000L
        val endTime = startTime + (durationMinutes * 60_000L)

        val success = nativeActionHandler.createCalendarEvent(
            title = title,
            startTime = startTime,
            endTime = endTime,
            description = description,
            location = location
        )

        val result = buildJsonObject {
            put("title", title)
            put("event_created", success)
            put("status", if (success) "Google Calendar event editor opened on device" else "Failed to launch Google Calendar")
        }
        return ToolExecutionOutcome(result.toString(), !success)
    }

    // 13. Query Calendar Events
    private suspend fun executeQueryCalendarEvents(arguments: JsonObject): ToolExecutionOutcome {
        val daysAhead = arguments["days_ahead"]?.jsonPrimitive?.intOrNull ?: 1
        val outcomeJson = nativeActionHandler.queryUpcomingEvents(daysAhead)
        return ToolExecutionOutcome(outcomeJson, false)
    }

    // 14. Telegram Messaging
    private suspend fun executeTelegramMessage(arguments: JsonObject): ToolExecutionOutcome {
        val message = arguments["message"]?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw SkillExecutionException("Telegram message content is required")
        val explicitChatId = arguments["chat_id"]?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotEmpty)

        val botToken = keyStore.getTelegramBotToken().trim()
        val chatId = explicitChatId ?: keyStore.getTelegramChatId().trim()

        if (botToken.isNotBlank() && chatId.isNotBlank()) {
            val url = "https://api.telegram.org/bot$botToken/sendMessage"
            val response = httpClient.post(url) {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("chat_id", chatId)
                    put("text", message)
                }.toString())
            }
            val isSuccess = response.status.value in 200..299
            val result = buildJsonObject {
                put("sent_via", "telegram_bot_api")
                put("chat_id", chatId)
                put("http_status", response.status.value)
                put("status", if (isSuccess) "Message delivered to Telegram" else "Telegram API returned error code ${response.status.value}")
            }
            return ToolExecutionOutcome(result.toString(), !isSuccess)
        } else {
            val launched = nativeActionHandler.shareToTelegram(message)
            val result = buildJsonObject {
                put("sent_via", "android_telegram_intent")
                put("opened_telegram_app", launched)
                put("status", if (launched) "Telegram app opened with message ready to send" else "Could not open Telegram")
                if (botToken.isBlank()) {
                    put("tip", "To send messages in the background automatically, enter a Telegram Bot Token in Settings.")
                }
            }
            return ToolExecutionOutcome(result.toString(), !launched)
        }
    }

    // 15. Search Contacts
    private suspend fun executeSearchContacts(arguments: JsonObject): ToolExecutionOutcome {
        val query = arguments["query"]?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw SkillExecutionException("Search query is required")
        val json = nativeActionHandler.searchContacts(query)
        return ToolExecutionOutcome(json, false)
    }

    // 16. Initiate Phone Call
    private suspend fun executeInitiatePhoneCall(arguments: JsonObject): ToolExecutionOutcome {
        val phone = arguments["phone_number"]?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw SkillExecutionException("Phone number is required")
        val success = nativeActionHandler.initiatePhoneCall(phone)
        val result = buildJsonObject {
            put("phone_number", phone)
            put("dialer_opened", success)
            put("status", if (success) "Phone dialer opened with $phone" else "Failed to open phone dialer")
        }
        return ToolExecutionOutcome(result.toString(), !success)
    }

    // 17. Draft Email
    private suspend fun executeDraftEmail(arguments: JsonObject): ToolExecutionOutcome {
        val recipient = arguments["recipient"]?.jsonPrimitive?.content?.trim() ?: ""
        val subject = arguments["subject"]?.jsonPrimitive?.content?.trim() ?: ""
        val body = arguments["body"]?.jsonPrimitive?.content?.trim() ?: ""
        val success = nativeActionHandler.draftEmail(recipient, subject, body)
        val result = buildJsonObject {
            put("recipient", recipient)
            put("subject", subject)
            put("email_draft_opened", success)
            put("status", if (success) "Email client opened with draft" else "Failed to open email client")
        }
        return ToolExecutionOutcome(result.toString(), !success)
    }

    // 18. Control Spotify
    private suspend fun executeControlSpotify(arguments: JsonObject): ToolExecutionOutcome {
        val query = arguments["query"]?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw SkillExecutionException("Music query is required")
        val success = nativeActionHandler.controlSpotify(query)
        val result = buildJsonObject {
            put("query", query)
            put("spotify_launched", success)
            put("status", if (success) "Spotify search launched for '$query'" else "Failed to launch Spotify")
        }
        return ToolExecutionOutcome(result.toString(), !success)
    }

    // 19. Live Web Search
    private suspend fun executeLiveWebSearch(arguments: JsonObject): ToolExecutionOutcome {
        val query = arguments["query"]?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw SkillExecutionException("Search query is required")
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"

        try {
            val response = httpClient.get(url)
            val body = response.body<JsonObject>()
            val abstractText = body["AbstractText"]?.jsonPrimitive?.content?.trim() ?: ""
            val abstractSource = body["AbstractSource"]?.jsonPrimitive?.content ?: "DuckDuckGo"
            val abstractUrl = body["AbstractURL"]?.jsonPrimitive?.content ?: ""
            val heading = body["Heading"]?.jsonPrimitive?.content ?: query

            val relatedArray = body["RelatedTopics"]?.jsonArray
            val relatedSnippets = mutableListOf<String>()
            relatedArray?.forEach { elem ->
                if (elem is JsonObject && elem.containsKey("Text")) {
                    elem["Text"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
                        if (relatedSnippets.size < 5) relatedSnippets.add(it)
                    }
                }
            }

            val result = buildJsonObject {
                put("query", query)
                put("heading", heading)
                put("summary", abstractText.ifBlank { relatedSnippets.joinToString("\n• ") })
                put("source", abstractSource)
                if (abstractUrl.isNotBlank()) put("url", abstractUrl)
            }
            return ToolExecutionOutcome(result.toString(), false)
        } catch (e: Exception) {
            val fallback = buildJsonObject {
                put("query", query)
                put("error", "Web search failed: ${e.message}")
            }
            return ToolExecutionOutcome(fallback.toString(), true)
        }
    }

    // 20. Extract Webpage Content
    private suspend fun executeExtractWebpage(arguments: JsonObject): ToolExecutionOutcome {
        val urlStr = arguments["url"]?.jsonPrimitive?.content?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw SkillExecutionException("URL is required")

        try {
            val response = httpClient.get(urlStr)
            val raw = response.bodyAsText()
            val noScript = raw.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
            val cleanText = noScript.replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(2000)

            val result = buildJsonObject {
                put("url", urlStr)
                put("status", response.status.value)
                put("content_preview", cleanText)
            }
            return ToolExecutionOutcome(result.toString(), false)
        } catch (e: Exception) {
            val result = buildJsonObject {
                put("url", urlStr)
                put("error", e.message ?: "Failed to fetch webpage")
            }
            return ToolExecutionOutcome(result.toString(), true)
        }
    }

    companion object {
        const val WEATHER_TOOL_NAME = "get_current_weather"
        const val CONVERSION_TOOL_NAME = "convert_temperature"
        const val WIKIPEDIA_TOOL_NAME = "search_wikipedia"
        const val CURRENCY_TOOL_NAME = "convert_currency"
        const val MATH_TOOL_NAME = "calculate_math"
        const val WORLD_TIME_TOOL_NAME = "get_world_time"
        const val DEVICE_STATUS_TOOL_NAME = "get_device_status"
        const val FLASHLIGHT_TOOL_NAME = "toggle_flashlight"
        const val TIMER_TOOL_NAME = "set_timer"
        const val CREATE_NOTE_TOOL_NAME = "create_quick_note"
        const val LIST_NOTES_TOOL_NAME = "list_quick_notes"
        const val CREATE_CALENDAR_EVENT_TOOL_NAME = "create_calendar_event"
        const val QUERY_CALENDAR_EVENTS_TOOL_NAME = "query_calendar_events"
        const val SEND_TELEGRAM_MESSAGE_TOOL_NAME = "send_telegram_message"
        const val SEARCH_CONTACTS_TOOL_NAME = "search_contacts"
        const val INITIATE_PHONE_CALL_TOOL_NAME = "initiate_phone_call"
        const val DRAFT_EMAIL_TOOL_NAME = "draft_email"
        const val CONTROL_SPOTIFY_TOOL_NAME = "control_spotify"
        const val LIVE_WEB_SEARCH_TOOL_NAME = "live_web_search"
        const val EXTRACT_WEBPAGE_TOOL_NAME = "extract_webpage_content"
    }
}