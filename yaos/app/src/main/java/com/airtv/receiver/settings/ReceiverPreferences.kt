/* FreeCast TV additions, 2026. GPL-3.0; see LICENSE. */
package com.airtv.receiver.settings

import android.content.Context

object ReceiverPreferences {
    private const val FILE = "freecast_receiver"

    fun load(context: Context): ReceiverSettings {
        val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return runCatching {
            val savedName = preferences.getString("name", null)
            ReceiverSettings(
                name = savedName?.takeUnless { it == "FreeCast TV" } ?: ReceiverSettings.DEFAULT_NAME,
                height = preferences.getInt("height", 1080),
                fps = preferences.getInt("fps", 30),
            ).normalized()
        }.getOrDefault(ReceiverSettings())
    }

    fun save(context: Context, settings: ReceiverSettings): ReceiverSettings {
        val safe = settings.normalized()
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("name", safe.name)
            .putInt("height", safe.height)
            .putInt("fps", safe.fps)
            .apply()
        return safe
    }
}
