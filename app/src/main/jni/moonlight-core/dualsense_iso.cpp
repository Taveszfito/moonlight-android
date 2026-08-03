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
