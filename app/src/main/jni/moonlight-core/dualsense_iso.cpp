#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>
#include <vector>

#include "opus.h"

#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>

namespace {
  constexpr std::size_t bytes_per_usb_frame = 384;  // 48 frames * 4 ch * 16-bit
  constexpr int packets_per_urb = 6;
  constexpr int urb_count = 4;
  constexpr std::size_t bytes_per_urb = bytes_per_usb_frame * packets_per_urb;
  constexpr std::size_t start_buffer_bytes = bytes_per_usb_frame * 12;
  constexpr std::size_t max_queue_bytes = bytes_per_usb_frame * 48;

  struct urb_context_t {
    usbdevfs_urb *urb = nullptr;
    std::uint8_t *data = nullptr;
    bool submitted = false;
  };

  std::mutex state_mutex;
  std::condition_variable state_changed;
  std::deque<std::uint8_t> pcm_queue;
  std::thread stream_thread;
  std::atomic_bool running {false};
  int stream_fd = -1;
  int endpoint_address = -1;
  std::atomic_uint64_t underruns {0};
  std::atomic_uint64_t dropped_bytes {0};

  std::mutex bt_speaker_mutex;
  OpusEncoder *bt_speaker_encoder = nullptr;

  bool ensure_bt_speaker_encoder() {
    if (bt_speaker_encoder) return true;
    int error = OPUS_OK;
    bt_speaker_encoder = opus_encoder_create(48000, 2, OPUS_APPLICATION_AUDIO, &error);
    if (!bt_speaker_encoder || error != OPUS_OK) {
      bt_speaker_encoder = nullptr;
      return false;
    }
    opus_encoder_ctl(bt_speaker_encoder, OPUS_SET_BITRATE(160000));
    opus_encoder_ctl(bt_speaker_encoder, OPUS_SET_VBR(0));
    opus_encoder_ctl(bt_speaker_encoder, OPUS_SET_COMPLEXITY(0));
    opus_encoder_ctl(bt_speaker_encoder, OPUS_SET_EXPERT_FRAME_DURATION(OPUS_FRAMESIZE_10_MS));
    return true;
  }

  void resample_speaker_512_to_480(const std::int16_t *input, std::int16_t *output) {
    // The native wireless report clock consumes 480 samples for every 512
    // samples arriving on the four-channel USB-compatible stream. This exact
    // 15/16 transport-rate conversion mirrors the controller bridge protocol.
    for (int output_frame = 0; output_frame < 480; ++output_frame) {
      const int numerator = output_frame * 16;
      const int source_frame = numerator / 15;
      const int fraction = numerator % 15;
      const int next_frame = std::min(source_frame + 1, 511);
      for (int channel = 0; channel < 2; ++channel) {
        const auto first = static_cast<std::int32_t>(input[source_frame * 2 + channel]);
        const auto second = static_cast<std::int32_t>(input[next_frame * 2 + channel]);
        output[output_frame * 2 + channel] = static_cast<std::int16_t>(
          (first * (15 - fraction) + second * fraction) / 15);
      }
    }
  }

  void free_context(urb_context_t *context) {
    if (!context) return;
    std::free(context->data);
    std::free(context->urb);
    delete context;
  }

  urb_context_t *allocate_context() {
    const auto urb_size = sizeof(usbdevfs_urb) + packets_per_urb * sizeof(usbdevfs_iso_packet_desc);
    auto *context = new urb_context_t;
    context->urb = static_cast<usbdevfs_urb *>(std::calloc(1, urb_size));
    context->data = static_cast<std::uint8_t *>(std::malloc(bytes_per_urb));
    if (!context->urb || !context->data) {
      free_context(context);
      return nullptr;
    }

    context->urb->type = USBDEVFS_URB_TYPE_ISO;
    context->urb->endpoint = static_cast<unsigned char>(endpoint_address);
    context->urb->flags = USBDEVFS_URB_ISO_ASAP;
    context->urb->buffer = context->data;
    context->urb->buffer_length = bytes_per_urb;
    context->urb->number_of_packets = packets_per_urb;
    context->urb->usercontext = context;
    for (int packet = 0; packet < packets_per_urb; ++packet) {
      context->urb->iso_frame_desc[packet].length = bytes_per_usb_frame;
    }
    return context;
  }

  void fill_context(urb_context_t &context) {
    std::unique_lock lock {state_mutex};
    if (pcm_queue.size() < bytes_per_urb) {
      ++underruns;
    }
    for (std::size_t index = 0; index < bytes_per_urb; ++index) {
      if (pcm_queue.empty()) {
        context.data[index] = 0;
      } else {
        context.data[index] = pcm_queue.front();
        pcm_queue.pop_front();
      }
    }
  }

  void stream_loop() {
    std::vector<urb_context_t *> contexts;
    contexts.reserve(urb_count);
    for (int index = 0; index < urb_count; ++index) {
      if (auto *context = allocate_context()) contexts.push_back(context);
    }
    if (contexts.empty()) {
      running.store(false);
      return;
    }

    {
      std::unique_lock lock {state_mutex};
      state_changed.wait_for(lock, std::chrono::milliseconds(250), [] {
        return !running.load() || pcm_queue.size() >= start_buffer_bytes;
      });
    }

    while (running.load(std::memory_order_acquire)) {
      void *completed = nullptr;
      while (ioctl(stream_fd, USBDEVFS_REAPURBNDELAY, &completed) == 0 && completed != nullptr) {
        auto *urb = static_cast<usbdevfs_urb *>(completed);
        if (auto *context = static_cast<urb_context_t *>(urb->usercontext)) context->submitted = false;
        completed = nullptr;
      }

      bool submitted_any = false;
      for (auto *context : contexts) {
        if (context->submitted) continue;
        fill_context(*context);
        if (ioctl(stream_fd, USBDEVFS_SUBMITURB, context->urb) == 0) {
          context->submitted = true;
          submitted_any = true;
        }
      }
      if (!submitted_any) usleep(250);
    }

    for (auto *context : contexts) {
      if (context->submitted) ioctl(stream_fd, USBDEVFS_DISCARDURB, context->urb);
    }
    auto outstanding = static_cast<int>(std::count_if(contexts.begin(), contexts.end(),
      [](const urb_context_t *context) { return context->submitted; }));
    while (outstanding > 0) {
      void *completed = nullptr;
      if (ioctl(stream_fd, USBDEVFS_REAPURB, &completed) == 0 && completed != nullptr) {
        auto *urb = static_cast<usbdevfs_urb *>(completed);
        auto *context = static_cast<urb_context_t *>(urb->usercontext);
        if (context && context->submitted) {
          context->submitted = false;
          --outstanding;
        }
      } else if (errno != EINTR) {
        break;
      }
    }
    for (auto *context : contexts) free_context(context);
  }

  void stop_stream() {
    running.store(false, std::memory_order_release);
    state_changed.notify_all();
    if (stream_thread.joinable()) stream_thread.join();
    auto lock = std::lock_guard {state_mutex};
    pcm_queue.clear();
    stream_fd = -1;
    endpoint_address = -1;
  }
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_limelight_dualsense_DualSenseIsoNative_start(
  JNIEnv *, jclass, jint fd, jint interface_number, jint alternate_setting, jint endpoint
) {
  stop_stream();
  if (fd < 0 || endpoint < 0) return -1;

  usbdevfs_setinterface set_interface {};
  set_interface.interface = interface_number;
  set_interface.altsetting = alternate_setting;
  if (ioctl(fd, USBDEVFS_SETINTERFACE, &set_interface) < 0) return -errno;

  stream_fd = fd;
  endpoint_address = endpoint;
  underruns.store(0);
  dropped_bytes.store(0);
  running.store(true, std::memory_order_release);
  stream_thread = std::thread {stream_loop};
  return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_limelight_dualsense_DualSenseIsoNative_push(JNIEnv *env, jclass, jbyteArray pcm) {
  if (!running.load(std::memory_order_acquire) || pcm == nullptr) return -1;
  const auto length = env->GetArrayLength(pcm);
  if (length <= 0) return -2;
  auto *bytes = env->GetByteArrayElements(pcm, nullptr);
  if (!bytes) return -3;

  {
    auto lock = std::lock_guard {state_mutex};
    const auto required = pcm_queue.size() + static_cast<std::size_t>(length);
    if (required > max_queue_bytes) {
      const auto overflow = required - max_queue_bytes;
      for (std::size_t index = 0; index < overflow; ++index) pcm_queue.pop_front();
      dropped_bytes.fetch_add(overflow, std::memory_order_relaxed);
    }
    for (jsize index = 0; index < length; ++index) {
      pcm_queue.push_back(static_cast<std::uint8_t>(bytes[index]));
    }
  }
  env->ReleaseByteArrayElements(pcm, bytes, JNI_ABORT);
  state_changed.notify_one();
  return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_dualsense_DualSenseIsoNative_stop(JNIEnv *, jclass) {
  stop_stream();
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_limelight_dualsense_DualSenseIsoNative_diagnostics(JNIEnv *env, jclass) {
  std::size_t queued_bytes;
  {
    auto lock = std::lock_guard {state_mutex};
    queued_bytes = pcm_queue.size();
  }
  const jlong values[] = {
    static_cast<jlong>(running.load()),
    static_cast<jlong>(queued_bytes),
    static_cast<jlong>(underruns.load()),
    static_cast<jlong>(dropped_bytes.load()),
  };
  auto result = env->NewLongArray(4);
  env->SetLongArrayRegion(result, 0, 4, values);
  return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_limelight_dualsense_DualSenseBtAudioNative_encodeSpeaker(
  JNIEnv *env, jclass, jbyteArray pcm
) {
  constexpr int input_frames_per_chunk = 512;
  constexpr int output_frames_per_chunk = 480;
  constexpr int chunks = 1;
  constexpr int bytes_per_opus_chunk = 200;
  constexpr int input_bytes = input_frames_per_chunk * 2 * sizeof(std::int16_t) * chunks;
  if (!pcm || env->GetArrayLength(pcm) != input_bytes) return nullptr;

  auto *bytes = env->GetByteArrayElements(pcm, nullptr);
  if (!bytes) return nullptr;
  std::uint8_t encoded[chunks * bytes_per_opus_chunk] {};
  bool success = true;
  {
    auto lock = std::lock_guard {bt_speaker_mutex};
    success = ensure_bt_speaker_encoder();
    if (success) {
      const auto *samples = reinterpret_cast<const std::int16_t *>(bytes);
      std::int16_t resampled[output_frames_per_chunk * 2];
      for (int chunk = 0; chunk < chunks; ++chunk) {
        const auto *chunk_samples = samples + chunk * input_frames_per_chunk * 2;
        resample_speaker_512_to_480(chunk_samples, resampled);
        const auto written = opus_encode(bt_speaker_encoder, resampled,
          output_frames_per_chunk, encoded + chunk * bytes_per_opus_chunk,
          bytes_per_opus_chunk);
        if (written <= 0) {
          success = false;
          break;
        }
        if (written < bytes_per_opus_chunk) {
          std::memset(encoded + chunk * bytes_per_opus_chunk + written, 0,
            bytes_per_opus_chunk - written);
        }
      }
    }
  }
  env->ReleaseByteArrayElements(pcm, bytes, JNI_ABORT);
  if (!success) return nullptr;
  auto result = env->NewByteArray(sizeof(encoded));
  env->SetByteArrayRegion(result, 0, sizeof(encoded),
    reinterpret_cast<const jbyte *>(encoded));
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_dualsense_DualSenseBtAudioNative_resetSpeaker(JNIEnv *, jclass) {
  auto lock = std::lock_guard {bt_speaker_mutex};
  if (bt_speaker_encoder) opus_encoder_ctl(bt_speaker_encoder, OPUS_RESET_STATE);
}
