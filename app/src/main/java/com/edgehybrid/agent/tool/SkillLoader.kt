package com.edgehybrid.agent.tool

import com.edgehybrid.agent.data.model.ModelToolCall
import com.edgehybrid.agent.data.model.ToolDefinition
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.content
import kotlinx.serialization.json.doubleOrNull
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
    private val httpClient: HttpClient
) : SkillLoader {

    override suspend fun listTools(): List<ToolDefinition> =
        listOf(
            ToolDefinition(
                function = com.edgehybrid.agent.data.model.FunctionDefinition(
                    name = WEATHER_TOOL_NAME,
                    description = "Get current weather conditions for a city using Celsius.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("location", buildJsonObject {
                                put("type", "string")
                                put("description", "City name, for example Tokyo or London.")
                            })
                        })
                        put("required", buildJsonObject {
                            put("0", "location")
                        })
                        put("additionalProperties", false)
                    }
                )
            ),
            ToolDefinition(
                function = com.edgehybrid.agent.data.model.FunctionDefinition(
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
                                put(
                                    "description",
                                    "Source unit: C, F, K, or R."
                                )
                            })
                            put("to", buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Target unit: C, F, K, or R."
                                )
                            })
                        })
                        put("required", buildJsonObject {
                            put("0", "value")
                            put("1", "from")
                            put("2", "to")
                        })
                        put("additionalProperties", false)
                    }
                )
            )
        )

    override suspend fun execute(call: ModelToolCall): ToolExecutionOutcome =
        when (call.function.name) {
            WEATHER_TOOL_NAME -> executeWeather(call.function.arguments)
            CONVERSION_TOOL_NAME -> executeTemperatureConversion(call.function.arguments)
            else -> throw SkillExecutionException(
                "Unsupported built-in skill: ${call.function.name}"
            )
        }

    private suspend fun executeWeather(arguments: JsonObject): ToolExecutionOutcome {
        val location = arguments["location"]
            ?.jsonPrimitive
            ?.content
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw SkillExecutionException("Weather location is required")

        val geocodingResponse = httpClient.get(
            "https://geocoding-api.open-meteo.com/v1/search"
        ) {
            parameter("name", location)
            parameter("count", 1)
            parameter("language", "en")
            parameter("format", "json")
        }

        if (geocodingResponse.status.value !in 200..299) {
            throw SkillExecutionException(
                "Weather location lookup failed with HTTP ${geocodingResponse.status.value}: " +
                    geocodingResponse.bodyAsText().take(256)
            )
        }

        val geocoding = geocodingResponse.body<JsonObject>()
        val locationResult = geocoding["results"]
            ?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?: throw SkillExecutionException("No weather location was found for $location")

        val latitude = locationResult["latitude"]?.jsonPrimitive?.doubleOrNull
        val longitude = locationResult["longitude"]?.jsonPrimitive?.doubleOrNull
        if (latitude == null || longitude == null) {
            throw SkillExecutionException("Weather location coordinates were unavailable")
        }

        val weatherResponse = httpClient.get(
            "https://api.open-meteo.com/v1/forecast"
        ) {
            parameter("latitude", latitude)
            parameter("longitude", longitude)
            parameter(
                "current",
                "temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m"
            )
            parameter("timezone", "auto")
        }

        if (weatherResponse.status.value !in 200..299) {
            throw SkillExecutionException(
                "Weather request failed with HTTP ${weatherResponse.status.value}: " +
                    weatherResponse.bodyAsText().take(256)
            )
        }

        val weather = weatherResponse.body<JsonObject>()
        val current = weather["current"]?.jsonObject
            ?: throw SkillExecutionException("Weather provider returned no current conditions")

        val temperatureCelsius = current["temperature_2m"]?.jsonPrimitive?.doubleOrNull
            ?: throw SkillExecutionException("Weather provider returned no temperature")

        val result = buildJsonObject {
            put("location", locationResult["name"]?.jsonPrimitive?.content ?: location)
            put("country", locationResult["country"]?.jsonPrimitive?.content ?: "")
            put("temperature_c", roundToOneDecimal(temperatureCelsius))
            put(
                "apparent_temperature_c",
                current["apparent_temperature"]?.jsonPrimitive?.doubleOrNull
                    ?.let(::roundToOneDecimal) ?: temperatureCelsius
            )
            put(
                "relative_humidity_percent",
                current["relative_humidity_2m"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            )
            put(
                "weather_code",
                current["weather_code"]?.jsonPrimitive?.content ?: "unknown"
            )
            put(
                "wind_speed_kmh",
                current["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            )
            put("observed_at", current["time"]?.jsonPrimitive?.content ?: "")
        }

        return ToolExecutionOutcome(
            content = result.toString(),
            isError = false
        )
    }

    private fun executeTemperatureConversion(arguments: JsonObject): ToolExecutionOutcome {
        val value = arguments["value"]?.jsonPrimitive?.doubleOrNull
            ?: throw SkillExecutionException("Temperature value must be numeric")

        val source = arguments["from"]?.jsonPrimitive?.content
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?: throw SkillExecutionException("Source temperature unit is required")

        val target = arguments["to"]?.jsonPrimitive?.content
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?: throw SkillExecutionException("Target temperature unit is required")

        val sourceKelvin = when (source) {
            "C", "CELSIUS" -> value + 273.15
            "F", "FAHRENHEIT" -> (value - 32.0) * 5.0 / 9.0 + 273.15
            "K", "KELVIN" -> value
            "R", "RANKINE" -> (value - 491.67) * 5.0 / 9.0
            else -> throw SkillExecutionException("Unsupported source temperature unit: $source")
        }

        val converted = when (target) {
            "C", "CELSIUS" -> sourceKelvin - 273.15
            "F", "FAHRENHEIT" -> (sourceKelvin - 273.15) * 9.0 / 5.0 + 32.0
            "K", "KELVIN" -> sourceKelvin
            "R", "RANKINE" -> (sourceKelvin * 9.0 / 5.0) + 491.67
            else -> throw SkillExecutionException("Unsupported target temperature unit: $target")
        }

        val result = buildJsonObject {
            put("value", roundToOneDecimal(value))
            put("from", source)
            put("to", target)
            put("result", roundToOneDecimal(converted))
        }

        return ToolExecutionOutcome(
            content = result.toString(),
            isError = false
        )
    }

    private fun roundToOneDecimal(value: Double): Double =
        kotlin.math.round(value * 10.0) / 10.0

    companion object {
        const val WEATHER_TOOL_NAME = "get_current_weather"
        const val CONVERSION_TOOL_NAME = "convert_temperature"
    }
}