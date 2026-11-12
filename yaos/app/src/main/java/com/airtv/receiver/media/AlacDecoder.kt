package com.airtv.receiver.media

/** Apple's open-source ALAC decoder, returning 44.1 kHz stereo PCM16. */
internal class AlacDecoder(framesPerPacket: Int) {
    private var handle = nativeCreate(framesPerPacket)
    val output = ByteArray(framesPerPacket * 4)

    init { require(handle != 0L) { "ALAC decoder initialization failed" } }

    fun decode(input: ByteArray, length: Int): Int = nativeDecode(handle, input, length, output)

    fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0
    }

    private external fun nativeCreate(framesPerPacket: Int): Long
    private external fun nativeDecode(handle: Long, input: ByteArray, length: Int, output: ByteArray): Int
    private external fun nativeDestroy(handle: Long)

    companion object { init { System.loadLibrary("alacjni") } }
}
