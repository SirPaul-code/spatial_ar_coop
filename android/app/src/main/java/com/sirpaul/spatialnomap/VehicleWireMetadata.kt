package com.sirpaul.spatialnomap

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

object VehicleWireMetadata {
    const val PREFIX = "AUTO:CAR:"

    data class Parsed(
        val owner: String,
        val label: String,
        val axis: FloatArray?,
    )

    fun encode(owner: String, label: String, axis: FloatArray?): String {
        val cleanOwner = owner.replace('|', '_').take(24).ifBlank { "Peer" }
        val code = when (label.uppercase()) {
            "BUS" -> 'B'
            "TRUCK" -> 'T'
            else -> 'C'
        }
        val unit = axis?.let(VehicleGeometryPolicy::horizontalUnit)
        if (unit == null) return "$PREFIX$cleanOwner|${code}---"
        var deg = Math.toDegrees(atan2(unit[2].toDouble(), unit[0].toDouble())).roundToInt()
        deg = ((deg % 180) + 180) % 180
        return "$PREFIX$cleanOwner|$code${deg.toString().padStart(3, '0')}"
    }

    fun decode(raw: String): Parsed {
        val payload = raw.removePrefix(PREFIX)
        val marker = payload.lastIndexOf('|')
        if (marker < 0 || marker + 5 != payload.length) {
            return Parsed(payload.ifBlank { "Peer" }, "CAR", null)
        }
        val owner = payload.substring(0, marker).ifBlank { "Peer" }
        val suffix = payload.substring(marker + 1)
        val label = when (suffix.firstOrNull()) {
            'B' -> "BUS"
            'T' -> "TRUCK"
            else -> "CAR"
        }
        val deg = suffix.drop(1).toIntOrNull()
        val axis = deg?.let {
            val rad = it.coerceIn(0, 179) * PI / 180.0
            floatArrayOf(cos(rad).toFloat(), 0f, sin(rad).toFloat())
        }
        return Parsed(owner, label, axis)
    }
}
