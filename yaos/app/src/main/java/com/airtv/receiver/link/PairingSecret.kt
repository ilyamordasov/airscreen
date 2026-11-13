package com.airtv.receiver.link

import android.content.Context
import java.security.SecureRandom

object PairingSecret {
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences("shortcut_pairing", Context.MODE_PRIVATE)
        val saved = prefs.getString("secret", null)
        if (saved != null) return saved
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val secret = bytes.joinToString("") { "%02x".format(it) }
        check(prefs.edit().putString("secret", secret).commit()) { "Не удалось сохранить код сопряжения" }
        return secret
    }
}
