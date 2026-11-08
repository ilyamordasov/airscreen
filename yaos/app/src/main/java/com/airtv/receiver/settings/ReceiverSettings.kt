/* FreeCast TV additions, 2026. GPL-3.0; see LICENSE. */
package com.airtv.receiver.settings

import com.airtv.receiver.airplay.AdvertisedDisplay
import com.airtv.receiver.airplay.DeviceIdentity

/** Deliberately bounded profiles: a 4K TV must not accidentally request a 4K stream. */
data class ReceiverSettings(
    val name: String = DEFAULT_NAME,
    val height: Int = 1080,
    val fps: Int = 30,
) {
    fun normalized() = copy(
        name = DeviceIdentity.serviceName(name, DEFAULT_NAME),
        height = if (height == 720) 720 else 1080,
        fps = if (fps == 60) 60 else 30,
    )

    fun display(): AdvertisedDisplay {
        val safe = normalized()
        return AdvertisedDisplay(if (safe.height == 720) 1280 else 1920, safe.height, safe.fps)
    }

    companion object { const val DEFAULT_NAME = "FreeCast TV" }
}
