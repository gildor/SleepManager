package com.med.sleepmanager.history

import org.json.JSONObject

/** Versioned payloads keep database layout independent of optional future measurements. */
internal object BehaviorHistoryCodec {
    fun encode(observation: BehaviorObservation): String = JSONObject().apply {
        put("version", 1)
        put("event", observation.event.name)
        put("state", observation.state.name)
        observation.bootCount?.let { put("boot", it) }
        put("context", JSONObject().apply {
            observation.context.lidClosed?.let { put("lid", it) }
            observation.context.externalDisplayConnected?.let { put("displayConnected", it) }
            observation.context.externalDisplayActive?.let { put("displayActive", it) }
            observation.context.externalPowerConnected?.let { put("power", it) }
        })
        observation.battery?.let { battery ->
            put("battery", JSONObject().apply {
                battery.percent?.let { put("percent", it) }
                battery.chargeUah?.let { put("chargeUah", it) }
                battery.capacityUah?.let { put("capacityUah", it) }
                put("source", battery.source)
                battery.normalizationFullUah?.let { put("normalizationFullUah", it) }
                put("status", battery.status.name)
            })
        }
    }.toString()

    fun decode(
        payload: String,
        sequence: Long,
        runId: String,
        wallTimeMs: Long,
        elapsedTimeMs: Long
    ): BehaviorObservation {
        val json = JSONObject(payload)
        require(json.getInt("version") == 1) { "Unsupported history payload" }
        val context = json.getJSONObject("context")
        return BehaviorObservation(
            sequence = sequence,
            runId = runId,
            wallTimeMs = wallTimeMs,
            elapsedTimeMs = elapsedTimeMs,
            bootCount = json.intOrNull("boot"),
            event = BehaviorEvent.valueOf(json.getString("event")),
            state = BehaviorState.valueOf(json.getString("state")),
            context = BehaviorContext(
                lidClosed = context.booleanOrNull("lid"),
                externalDisplayConnected = context.booleanOrNull("displayConnected"),
                externalDisplayActive = context.booleanOrNull("displayActive"),
                externalPowerConnected = context.booleanOrNull("power")
            ),
            battery = json.optJSONObject("battery")?.let {
                BehaviorBattery(
                    percent = it.intOrNull("percent"),
                    chargeUah = it.longOrNull("chargeUah"),
                    capacityUah = it.longOrNull("capacityUah"),
                    source = it.getString("source"),
                    normalizationFullUah = it.longOrNull("normalizationFullUah"),
                    status = BatteryChargeStatus.valueOf(it.getString("status"))
                )
            }
        )
    }

    private fun JSONObject.intOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) getInt(key) else null

    private fun JSONObject.longOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) getLong(key) else null

    private fun JSONObject.booleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) getBoolean(key) else null
}
