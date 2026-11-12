#include <jni.h>
#include <stdint.h>
#include <vector>
#include "ALACDecoder.h"
#include "ALACBitUtilities.h"

struct DecoderState {
    ALACDecoder decoder;
    uint32_t frames;
};

extern "C" JNIEXPORT jlong JNICALL
Java_com_airtv_receiver_media_AlacDecoder_nativeCreate(JNIEnv *, jobject, jint frames) {
    if (frames < 1 || frames > 8192) return 0;
    auto *state = new DecoderState();
    state->frames = static_cast<uint32_t>(frames);
    uint8_t cookie[] = {
        0, 0, 0, 0, 0, 16, 40, 10,
        14, 2, 0, 255, 0, 0, 16, 0,
        0, 2, 128, 0, 0, 0, 0xac, 0x44
    };
    cookie[0] = static_cast<uint8_t>(frames >> 24);
    cookie[1] = static_cast<uint8_t>(frames >> 16);
    cookie[2] = static_cast<uint8_t>(frames >> 8);
    cookie[3] = static_cast<uint8_t>(frames);
    if (state->decoder.Init(cookie, sizeof(cookie)) != 0) {
        delete state;
        return 0;
    }
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_airtv_receiver_media_AlacDecoder_nativeDecode(JNIEnv *env, jobject, jlong handle,
                                                        jbyteArray input, jint length, jbyteArray output) {
    auto *state = reinterpret_cast<DecoderState *>(handle);
    if (!state || length <= 0 || length > env->GetArrayLength(input) ||
        env->GetArrayLength(output) < static_cast<jsize>(state->frames * 4)) return -1;
    std::vector<uint8_t> encoded(static_cast<size_t>(length));
    env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte *>(encoded.data()));
    if (env->ExceptionCheck()) return -1;
    std::vector<uint8_t> pcm(static_cast<size_t>(state->frames) * 4);
    BitBuffer bits;
    BitBufferInit(&bits, encoded.data(), static_cast<uint32_t>(length));
    uint32_t decoded = 0;
    if (state->decoder.Decode(&bits, pcm.data(), state->frames, 2, &decoded) != 0 ||
        decoded > state->frames) return -1;
    env->SetByteArrayRegion(output, 0, static_cast<jsize>(decoded * 4),
                            reinterpret_cast<const jbyte *>(pcm.data()));
    return static_cast<jint>(decoded * 4);
}

extern "C" JNIEXPORT void JNICALL
Java_com_airtv_receiver_media_AlacDecoder_nativeDestroy(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<DecoderState *>(handle);
}
