package com.airtv.receiver.airplay

import java.security.MessageDigest

/** Stable identity values advertised over Bonjour. */
object DeviceIdentity {

    /** Reserve 13 bytes for the MAC@ prefix of the RAOP DNS-SD instance. */
    private const val MAX_SERVICE_NAME_BYTES = 50

    /**
     * Derives a deterministic, locally administered unicast MAC address from [seed]
     * (normally Settings.Secure.ANDROID_ID). iOS remembers paired receivers by this id.
     */
    fun macAddressFrom(seed: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        val octets = digest.copyOf(6)
        // clear the multicast bit, set the locally-administered bit
        octets[0] = ((octets[0].toInt() and 0xFE) or 0x02).toByte()
        return octets.joinToString(":") { "%02x".format(it) }
    }

    fun serviceName(deviceName: String, fallback: String): String {
        fun clean(value: String) = value.filterNot { it.isISOControl() }.replace('.', ' ').replace('\\', ' ').trim()
        val name = clean(deviceName).ifEmpty { clean(fallback).ifEmpty { "FreeCast TV" } }
        val result = StringBuilder()
        var index = 0
        var bytes = 0
        while (index < name.length) {
            val codePoint = name.codePointAt(index)
            if (codePoint in 0xD800..0xDFFF) { index++; continue }
            val piece = String(Character.toChars(codePoint))
            val nextBytes = piece.toByteArray(Charsets.UTF_8).size
            if (bytes + nextBytes > MAX_SERVICE_NAME_BYTES) break
            result.append(piece)
            bytes += nextBytes
            index += Character.charCount(codePoint)
        }
        return result.toString().trim()
    }
}
