package com.airtv.receiver.link

import java.net.URI

/** A page chosen by the owner on the phone, not a guessed or scraped media URL. */
object SharedPage {
    fun parse(input: String): String? {
        val value = input.trim()
        if (value.isEmpty() || value.length > 2048 || value.any { it.code < 32 || it.code == 127 }) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        return value
    }
}
