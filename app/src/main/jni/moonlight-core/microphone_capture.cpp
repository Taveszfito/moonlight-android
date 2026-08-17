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
constexpr int kBluetoothMicOpusBytes = 71;
constexpr int kMaxOpusBytes = 248; // 4 bytes remain in the control packet

std::mutex encoder_mutex;
OpusEncoder* encoder = nullptr;
OpusDecoder* bluetooth_decoder = nullptr;
OpusDecoder* bluetooth_monitor_decoder = nullptr;
std::mutex bluetooth_monitor_mutex;
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

bool ensure_bluetooth_monitor_decoder() {
    if (bluetooth_monitor_decoder != nullptr) return true;
    int error = OPUS_OK;
    bluetooth_monitor_decoder = opus_decoder_create(kSampleRate, 1, &error);
    if (bluetooth_monitor_decoder == nullptr || error != OPUS_OK) {
        bluetooth_monitor_decoder = nullptr;
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
    // An empty packet explicitly requests Opus packet-loss concealment. The
    // Bluetooth transport can bunch 10 ms mic frames or briefly drop one;
    // emitting PLC keeps the re-encoded 20 ms host stream continuous.
    if (length < 0 || length > 248) return -2;

    std::lock_guard lock {encoder_mutex};
    if (!ensure_encoder() || !ensure_bluetooth_decoder()) return -3;
    std::array<opus_int16, kBluetoothMicFrameSamples> decoded {};
    int samples = 0;
    if (length == 0) {
        samples = opus_decode(bluetooth_decoder, nullptr, 0,
                decoded.data(), kBluetoothMicFrameSamples, 0);
    } else {
        auto* packet = env->GetByteArrayElements(opus, nullptr);
        if (packet == nullptr) return -4;
        samples = opus_decode(bluetooth_decoder,
                reinterpret_cast<const unsigned char*>(packet), length,
                decoded.data(), kBluetoothMicFrameSamples, 0);
        env->ReleaseByteArrayElements(opus, packet, JNI_ABORT);
    }
    if (samples <= 0 || samples > kBluetoothMicFrameSamples) return -5;
    if (bluetooth_pcm_samples + samples > kFrameSamples) bluetooth_pcm_samples = 0;
    std::copy_n(decoded.data(), samples, bluetooth_pcm.data() + bluetooth_pcm_samples);
    bluetooth_pcm_samples += samples;
    if (bluetooth_pcm_samples < kFrameSamples) return 1;
    bluetooth_pcm_samples = 0;
    return encode_and_send(bluetooth_pcm.data());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_limelight_dualsense_DualSenseMicrophoneNative_forwardBluetoothOpus(
        JNIEnv* env, jclass, jbyteArray opus) {
    if (opus == nullptr || env->GetArrayLength(opus) != kBluetoothMicOpusBytes) return -1;

    // Apollo's microphone control stream uses the same 48 kHz/10 ms Opus
    // representation the DualSense sends over Bluetooth. Forward it intact;
    // decoding it and encoding a second time causes avoidable robotic artefacts
    // and alters the controller's packet cadence.
    std::array<std::uint8_t, 4 + kBluetoothMicOpusBytes> frame {};
    auto* packet = env->GetByteArrayElements(opus, nullptr);
    if (packet == nullptr) return -2;
    frame[0] = static_cast<std::uint8_t>(sequence >> 8);
    frame[1] = static_cast<std::uint8_t>(sequence & 0xff);
    frame[2] = 1;
    frame[3] = 0;
    std::copy_n(reinterpret_cast<const std::uint8_t*>(packet),
                kBluetoothMicOpusBytes, frame.data() + 4);
    env->ReleaseByteArrayElements(opus, packet, JNI_ABORT);
    ++sequence;
    return LiSendRawControlStreamPacket(0x3003, frame.data(), frame.size());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_limelight_dualsense_DualSenseMicrophoneNative_bluetoothOpusFrameSamples(
        JNIEnv* env, jclass, jbyteArray opus) {
    if (opus == nullptr) return 0;
    const jsize length = env->GetArrayLength(opus);
    if (length <= 0 || length > 248) return 0;
    auto* packet = env->GetByteArrayElements(opus, nullptr);
    if (packet == nullptr) return 0;
    const int samples = opus_packet_get_nb_samples(
            reinterpret_cast<const unsigned char*>(packet), length, kSampleRate);
    env->ReleaseByteArrayElements(opus, packet, JNI_ABORT);
    return samples > 0 ? samples : 0;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_com_limelight_dualsense_DualSenseMicrophoneNative_decodeBluetoothOpusForMonitor(
        JNIEnv* env, jclass, jbyteArray opus) {
    if (opus == nullptr) return nullptr;
    const jsize length = env->GetArrayLength(opus);
    if (length < 0 || length > 248) return nullptr;

    // Keep this decoder entirely independent of Bluetooth-to-host forwarding.
    // The monitor is a diagnostic tap, never part of the controller transport.
    std::lock_guard lock {bluetooth_monitor_mutex};
    if (!ensure_bluetooth_monitor_decoder()) return nullptr;
    // A tagged DualSense Bluetooth microphone packet is one 10 ms, mono
    // Opus frame. Do not let a damaged packet expand the diagnostic timeline
    // into an arbitrary 60 ms frame: that makes captured audio run fast and
    // disguises the transport defect we are trying to measure.
    std::array<opus_int16, kBluetoothMicFrameSamples> decoded {};
    int samples = 0;
    if (length == 0) {
        // Packet-loss concealment keeps the diagnostic's PCM timeline aligned
        // with the controller's actual 10 ms transport clock.
        samples = opus_decode(bluetooth_monitor_decoder, nullptr, 0,
                decoded.data(), static_cast<int>(decoded.size()), 0);
    } else {
        auto* packet = env->GetByteArrayElements(opus, nullptr);
        if (packet == nullptr) return nullptr;
        samples = opus_decode(bluetooth_monitor_decoder,
                reinterpret_cast<const unsigned char*>(packet), length,
                decoded.data(), static_cast<int>(decoded.size()), 0);
        env->ReleaseByteArrayElements(opus, packet, JNI_ABORT);
    }
    if (samples <= 0 || samples > static_cast<int>(decoded.size())) return nullptr;

    jshortArray result = env->NewShortArray(samples);
    if (result != nullptr) {
        env->SetShortArrayRegion(result, 0, samples,
                reinterpret_cast<const jshort*>(decoded.data()));
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_dualsense_DualSenseMicrophoneNative_resetBluetoothOpusMonitor(
        JNIEnv*, jclass) {
    std::lock_guard lock {bluetooth_monitor_mutex};
    if (bluetooth_monitor_decoder != nullptr) {
        opus_decoder_ctl(bluetooth_monitor_decoder, OPUS_RESET_STATE);
    }
}
