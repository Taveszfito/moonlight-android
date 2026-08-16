#include <jni.h>

#include <array>
#include <algorithm>
#include <cstdint>
#include <mutex>

#include "Limelight.h"
#include "opus.h"

extern "C" int LiSendRawControlStreamPacket(uint16_t packetType, const void* data, int length);

namespace {
constexpr int kSampleRate = 48000;
constexpr int kFrameSamples = 960; // 20 ms mono
constexpr int kBluetoothMicFrameSamples = 480; // 10 ms mono
constexpr int kMaxOpusBytes = 248; // 4 bytes remain in the control packet

std::mutex encoder_mutex;
OpusEncoder* encoder = nullptr;
OpusDecoder* bluetooth_decoder = nullptr;
std::array<opus_int16, kFrameSamples> bluetooth_pcm {};
int bluetooth_pcm_samples = 0;
std::uint16_t sequence = 0;

bool ensure_encoder() {
    if (encoder != nullptr) return true;
    int error = OPUS_OK;
    encoder = opus_encoder_create(kSampleRate, 1, OPUS_APPLICATION_VOIP, &error);
    if (encoder == nullptr || error != OPUS_OK) {
        encoder = nullptr;
        return false;
    }
    opus_encoder_ctl(encoder, OPUS_SET_BITRATE(64000));
    opus_encoder_ctl(encoder, OPUS_SET_VBR(1));
    opus_encoder_ctl(encoder, OPUS_SET_COMPLEXITY(8));
    opus_encoder_ctl(encoder, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(encoder, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(encoder, OPUS_SET_PACKET_LOSS_PERC(5));
    opus_encoder_ctl(encoder, OPUS_SET_EXPERT_FRAME_DURATION(OPUS_FRAMESIZE_20_MS));
    return true;
}

bool ensure_bluetooth_decoder() {
    if (bluetooth_decoder != nullptr) return true;
    int error = OPUS_OK;
    bluetooth_decoder = opus_decoder_create(kSampleRate, 1, &error);
    if (bluetooth_decoder == nullptr || error != OPUS_OK) {
        bluetooth_decoder = nullptr;
        return false;
    }
    return true;
}

int encode_and_send(const opus_int16* pcm) {
    std::array<std::uint8_t, 4 + kMaxOpusBytes> frame {};
    const int encoded = opus_encode(encoder, pcm, kFrameSamples,
            frame.data() + 4, kMaxOpusBytes);
    if (encoded <= 0 || encoded > kMaxOpusBytes) return -4;
    frame[0] = static_cast<std::uint8_t>(sequence >> 8);
    frame[1] = static_cast<std::uint8_t>(sequence & 0xff);
    frame[2] = 1;
    frame[3] = 0;
    ++sequence;
    return LiSendRawControlStreamPacket(0x3003, frame.data(), 4 + encoded);
}
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_limelight_dualsense_DualSenseMicrophoneNative_start(JNIEnv*, jclass) {
    std::lock_guard lock {encoder_mutex};
    return ensure_encoder() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_limelight_dualsense_DualSenseMicrophoneNative_startBluetooth(JNIEnv*, jclass) {
    std::lock_guard lock {encoder_mutex};
    bluetooth_pcm_samples = 0;
    return ensure_encoder() && ensure_bluetooth_decoder() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_dualsense_DualSenseMicrophoneNative_stop(JNIEnv*, jclass) {
    std::lock_guard lock {encoder_mutex};
    if (encoder != nullptr) {
        opus_encoder_destroy(encoder);
        encoder = nullptr;
    }
    if (bluetooth_decoder != nullptr) {
        opus_decoder_destroy(bluetooth_decoder);
        bluetooth_decoder = nullptr;
    }
    bluetooth_pcm_samples = 0;
    sequence = 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_limelight_dualsense_DualSenseMicrophoneNative_encodeAndSend(
        JNIEnv* env, jclass, jshortArray samples) {
    if (samples == nullptr || env->GetArrayLength(samples) != kFrameSamples) return -1;

    std::lock_guard lock {encoder_mutex};
    if (!ensure_encoder()) return -2;

    auto* pcm = env->GetShortArrayElements(samples, nullptr);
    if (pcm == nullptr) return -3;

    const int result = encode_and_send(reinterpret_cast<const opus_int16*>(pcm));
    env->ReleaseShortArrayElements(samples, pcm, JNI_ABORT);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_limelight_dualsense_DualSenseMicrophoneNative_decodeBluetoothAndSend(
        JNIEnv* env, jclass, jbyteArray opus) {
    if (opus == nullptr) return -1;
    const auto length = env->GetArrayLength(opus);
    if (length <= 0 || length > 248) return -2;

    std::lock_guard lock {encoder_mutex};
    if (!ensure_encoder() || !ensure_bluetooth_decoder()) return -3;
    auto* packet = env->GetByteArrayElements(opus, nullptr);
    if (packet == nullptr) return -4;
    std::array<opus_int16, kBluetoothMicFrameSamples> decoded {};
    const int samples = opus_decode(bluetooth_decoder,
            reinterpret_cast<const unsigned char*>(packet), length,
            decoded.data(), kBluetoothMicFrameSamples, 0);
    env->ReleaseByteArrayElements(opus, packet, JNI_ABORT);
    if (samples <= 0 || samples > kBluetoothMicFrameSamples) return -5;
    if (bluetooth_pcm_samples + samples > kFrameSamples) bluetooth_pcm_samples = 0;
    std::copy_n(decoded.data(), samples, bluetooth_pcm.data() + bluetooth_pcm_samples);
    bluetooth_pcm_samples += samples;
    if (bluetooth_pcm_samples < kFrameSamples) return 1;
    bluetooth_pcm_samples = 0;
    return encode_and_send(bluetooth_pcm.data());
}
