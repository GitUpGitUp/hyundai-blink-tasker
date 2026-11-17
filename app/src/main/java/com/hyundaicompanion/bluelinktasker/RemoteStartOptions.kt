package com.hyundaicompanion.bluelinktasker

import android.content.Intent
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import org.json.JSONObject

/**
 * Hyundai US remote-start climate payload (aligned with bluelinky US ICE/EV start).
 *
 * [heatedFeatures]: 0=Off, 1=Steering wheel + rear defog, 2=Rear window only, 3=Steering wheel only.
 * Seat keys: driverSeat, passengerSeat, rearLeftSeat, rearRightSeat → API field names internally.
 * Seat status: 0 Off … 8 High heat (see bluelinky seatStatusMap).
 */
data class RemoteStartOptions(
    val durationMinutes: Int = 10,
    val temperatureF: Int = 70,
    val climateOn: Boolean = false,
    val defrost: Boolean = false,
    /** 0–3 for Hyundai US */
    val heatedFeatures: Int = 0,
    val seatDriver: Int? = null,
    val seatPassenger: Int? = null,
    val seatRearLeft: Int? = null,
    val seatRearRight: Int? = null,
) {
    init {
        require(durationMinutes in VALID_DURATION) { "duration must be 1–30" }
        require(temperatureF in VALID_TEMPERATURE) { "temperature must be 60–90 °F" }
        require(heatedFeatures in VALID_HEATED_FEATURES) { "heatedFeatures must be 0–3" }
    }

    fun validatedSeatClimate(): Map<String, Int> {
        val map = LinkedHashMap<String, Int>()
        seatDriver?.takeIf { it in VALID_SEAT_STATUS }?.let { map[API_DRIVER] = it }
        seatPassenger?.takeIf { it in VALID_SEAT_STATUS }?.let { map[API_PASSENGER] = it }
        seatRearLeft?.takeIf { it in VALID_SEAT_STATUS }?.let { map[API_REAR_LEFT] = it }
        seatRearRight?.takeIf { it in VALID_SEAT_STATUS }?.let { map[API_REAR_RIGHT] = it }
        return map
    }

    companion object {
        private val VALID_DURATION = 1..30
        private val VALID_TEMPERATURE = 60..90
        private val VALID_HEATED_FEATURES = 0..3
        val VALID_SEAT_STATUS = 0..8

        private val startOptionsJson = GsonBuilder().setStrictness(Strictness.STRICT).create()

        private const val API_DRIVER = "drvSeatHeatState"
        private const val API_PASSENGER = "astSeatHeatState"
        private const val API_REAR_LEFT = "rlSeatHeatState"
        private const val API_REAR_RIGHT = "rrSeatHeatState"

        fun fromSecurePrefs(prefs: SecurePrefs): RemoteStartOptions =
            RemoteStartOptions(
                durationMinutes = prefs.rsDurationMinutes.coerceIn(VALID_DURATION),
                temperatureF = prefs.rsTempF.coerceIn(VALID_TEMPERATURE),
                climateOn = prefs.rsClimateOn,
                defrost = prefs.rsDefrost,
                heatedFeatures = prefs.rsHeatedFeatures.coerceIn(VALID_HEATED_FEATURES),
                seatDriver = prefs.rsSeatDriver.takeIf { it in VALID_SEAT_STATUS },
                seatPassenger = prefs.rsSeatPassenger.takeIf { it in VALID_SEAT_STATUS },
                seatRearLeft = prefs.rsSeatRearLeft.takeIf { it in VALID_SEAT_STATUS },
                seatRearRight = prefs.rsSeatRearRight.takeIf { it in VALID_SEAT_STATUS },
            )

        /**
         * The JSON extra overrides saved defaults field by field. Legacy extras are used
         * when the JSON extra is absent.
         */
        fun mergeFromIntent(intent: Intent, prefs: SecurePrefs): RemoteStartOptions {
            var o = fromSecurePrefs(prefs)
            if (intent.hasExtra(EXTRA_START_OPTIONS)) {
                return o.mergeStartJson(intent.getStringExtra(EXTRA_START_OPTIONS))
            }
            if (intent.hasExtra(EXTRA_START_DURATION)) {
                o = o.copy(durationMinutes = intent.getIntExtra(EXTRA_START_DURATION, o.durationMinutes).coerceIn(VALID_DURATION))
            }
            if (intent.hasExtra(EXTRA_START_TEMP)) {
                o = o.copy(temperatureF = intent.getIntExtra(EXTRA_START_TEMP, o.temperatureF).coerceIn(VALID_TEMPERATURE))
            }
            if (intent.hasExtra(EXTRA_START_CLIMATE)) {
                o = o.copy(climateOn = readBoolExtra(intent, EXTRA_START_CLIMATE, o.climateOn))
            }
            if (intent.hasExtra(EXTRA_START_DEFROST)) {
                o = o.copy(defrost = readBoolExtra(intent, EXTRA_START_DEFROST, o.defrost))
            }
            if (intent.hasExtra(EXTRA_START_HEATED)) {
                o = o.copy(heatedFeatures = intent.getIntExtra(EXTRA_START_HEATED, o.heatedFeatures).coerceIn(VALID_HEATED_FEATURES))
            }
            if (intent.hasExtra(EXTRA_START_SEAT_JSON)) {
                o = o.mergeSeatJson(intent.getStringExtra(EXTRA_START_SEAT_JSON))
            } else {
                if (intent.hasExtra(EXTRA_SEAT_DRIVER)) {
                    o = o.copy(seatDriver = intent.getIntExtra(EXTRA_SEAT_DRIVER, -1).takeIf { it in VALID_SEAT_STATUS })
                }
                if (intent.hasExtra(EXTRA_SEAT_PASSENGER)) {
                    o = o.copy(seatPassenger = intent.getIntExtra(EXTRA_SEAT_PASSENGER, -1).takeIf { it in VALID_SEAT_STATUS })
                }
                if (intent.hasExtra(EXTRA_SEAT_REAR_LEFT)) {
                    o = o.copy(seatRearLeft = intent.getIntExtra(EXTRA_SEAT_REAR_LEFT, -1).takeIf { it in VALID_SEAT_STATUS })
                }
                if (intent.hasExtra(EXTRA_SEAT_REAR_RIGHT)) {
                    o = o.copy(seatRearRight = intent.getIntExtra(EXTRA_SEAT_REAR_RIGHT, -1).takeIf { it in VALID_SEAT_STATUS })
                }
            }
            return o
        }

        private fun RemoteStartOptions.mergeStartJson(json: String?): RemoteStartOptions {
            if (json.isNullOrBlank()) return this

            val fields = try {
                startOptionsJson.fromJson(json, JsonObject::class.java) ?: return this
            } catch (_: JsonParseException) {
                return this
            }

            fun validInt(key: String, range: IntRange): Int? =
                (fields[key] as? JsonPrimitive)?.takeIf { it.isNumber }
                    ?.asString?.toIntOrNull()?.takeIf { it in range }

            fun boolean(key: String): Boolean? =
                (fields[key] as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean

            return copy(
                durationMinutes = validInt("duration", VALID_DURATION) ?: durationMinutes,
                temperatureF = validInt("temp", VALID_TEMPERATURE) ?: temperatureF,
                climateOn = boolean("climate") ?: climateOn,
                defrost = boolean("defrost") ?: defrost,
                heatedFeatures = validInt("heated", VALID_HEATED_FEATURES) ?: heatedFeatures,
                seatDriver = validInt("driverSeat", VALID_SEAT_STATUS) ?: seatDriver,
                seatPassenger = validInt("passengerSeat", VALID_SEAT_STATUS) ?: seatPassenger,
                seatRearLeft = validInt("rearLeftSeat", VALID_SEAT_STATUS) ?: seatRearLeft,
                seatRearRight = validInt("rearRightSeat", VALID_SEAT_STATUS) ?: seatRearRight,
            )
        }

        private fun readBoolExtra(intent: Intent, key: String, fallback: Boolean): Boolean {
            if (!intent.hasExtra(key)) return fallback
            return when (val raw = intent.extras?.get(key)) {
                is Boolean -> raw
                is Int -> raw != 0
                is String -> {
                    val s = raw.lowercase().trim()
                    when (s) {
                        "1", "true", "yes", "on" -> true
                        "0", "false", "no", "off" -> false
                        else -> fallback
                    }
                }
                else -> fallback
            }
        }

        private fun RemoteStartOptions.mergeSeatJson(json: String?): RemoteStartOptions {
            if (json.isNullOrBlank()) return this
            return try {
                val jo = JSONObject(json.trim())
                var o = this
                jo.optIntOrNull("driverSeat")?.let { if (it in VALID_SEAT_STATUS) o = o.copy(seatDriver = it) }
                jo.optIntOrNull("passengerSeat")?.let { if (it in VALID_SEAT_STATUS) o = o.copy(seatPassenger = it) }
                jo.optIntOrNull("rearLeftSeat")?.let { if (it in VALID_SEAT_STATUS) o = o.copy(seatRearLeft = it) }
                jo.optIntOrNull("rearRightSeat")?.let { if (it in VALID_SEAT_STATUS) o = o.copy(seatRearRight = it) }
                o
            } catch (_: Exception) {
                this
            }
        }

        private fun JSONObject.optIntOrNull(key: String): Int? {
            if (!has(key)) return null
            return optInt(key)
        }

        const val EXTRA_START_OPTIONS = "start_options"
        const val EXTRA_START_DURATION = "start_duration"
        const val EXTRA_START_TEMP = "start_temp"
        const val EXTRA_START_CLIMATE = "start_climate"
        const val EXTRA_START_DEFROST = "start_defrost"
        const val EXTRA_START_HEATED = "start_heated"
        const val EXTRA_START_SEAT_JSON = "start_seat_json"
        const val EXTRA_SEAT_DRIVER = "seat_driver"
        const val EXTRA_SEAT_PASSENGER = "seat_passenger"
        const val EXTRA_SEAT_REAR_LEFT = "seat_rear_left"
        const val EXTRA_SEAT_REAR_RIGHT = "seat_rear_right"
    }
}
