package com.limelight.binding.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.os.Process;

import com.limelight.LimeLog;
import com.limelight.dualsense.DualSenseAudioBridge;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;

import java.util.concurrent.ArrayBlockingQueue;

public class AndroidAudioRenderer implements AudioRenderer {

    private final Context context;
    private final boolean enableAudioFx;

    private AudioTrack track;
    private int channelCount;
    private final ArrayBlockingQueue<short[]> audioWriteQueue = new ArrayBlockingQueue<>(4);
    private volatile boolean audioWriterRunning;
    private Thread audioWriterThread;

    public AndroidAudioRenderer(Context context, boolean enableAudioFx) {
        this.context = context;
        this.enableAudioFx = enableAudioFx;
    }

    private AudioTrack createAudioTrack(int channelConfig, int sampleRate, int bufferSize, boolean lowLatency) {
        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME);
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Use FLAG_LOW_LATENCY on L through N
            if (lowLatency) {
                attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder trackBuilder = new AudioTrack.Builder()
                    .setAudioFormat(format)
                    .setAudioAttributes(attributesBuilder.build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize);

            // Use PERFORMANCE_MODE_LOW_LATENCY on O and later
            if (lowLatency) {
                trackBuilder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }

            return trackBuilder.build();
        }
        else {
            return new AudioTrack(attributesBuilder.build(),
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        }
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        int channelConfig;
        int bytesPerFrame;

        switch (audioConfiguration.channelCount)
        {
            case 2:
                channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
                break;
            case 4:
                channelConfig = AudioFormat.CHANNEL_OUT_QUAD;
                break;
            case 6:
                channelConfig = AudioFormat.CHANNEL_OUT_5POINT1;
                break;
            case 8:
                // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND isn't available until Android 6.0,
                // yet the CHANNEL_OUT_SIDE_LEFT and CHANNEL_OUT_SIDE_RIGHT constants were added
                // in 5.0, so just hardcode the constant so we can work on Lollipop.
                channelConfig = 0x000018fc; // AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
                break;
            default:
                LimeLog.severe("Decoder returned unhandled channel count");
                return -1;
        }
        channelCount = audioConfiguration.channelCount;

        LimeLog.info("Audio channel config: "+String.format("0x%X", channelConfig));

        bytesPerFrame = audioConfiguration.channelCount * samplesPerFrame * 2;

        // We're not supposed to request less than the minimum
        // buffer size for our buffer, but it appears that we can
        // do this on many devices and it lowers audio latency.
        // We'll try the small buffer size first and if it fails,
        // use the recommended larger buffer size.

        for (int i = 0; i < 4; i++) {
            boolean lowLatency;
            int bufferSize;

            // We will try:
            // 1) Small buffer, low latency mode
            // 2) Large buffer, low latency mode
            // 3) Small buffer, standard mode
            // 4) Large buffer, standard mode

            switch (i) {
                case 0:
                case 1:
                    lowLatency = true;
                    break;
                case 2:
                case 3:
                    lowLatency = false;
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            switch (i) {
                case 0:
                case 2:
                    bufferSize = bytesPerFrame * 2;
                    break;

                case 1:
                case 3:
                    // Try the larger buffer size
                    bufferSize = Math.max(AudioTrack.getMinBufferSize(sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT),
                            bytesPerFrame * 2);

                    // Round to next frame
                    bufferSize = (((bufferSize + (bytesPerFrame - 1)) / bytesPerFrame) * bytesPerFrame);
                    break;
                default:
                    // Unreachable
                    throw new IllegalStateException();
            }

            // Skip low latency options if hardware sample rate doesn't match the content
            if (AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC) != sampleRate && lowLatency) {
                continue;
            }

            // Skip low latency options when using audio effects, since low latency mode
            // precludes the use of the audio effect pipeline (as of Android 13).
            if (enableAudioFx && lowLatency) {
                continue;
            }

            try {
                track = createAudioTrack(channelConfig, sampleRate, bufferSize, lowLatency);
                track.play();

                // Successfully created working AudioTrack. We're done here.
                LimeLog.info("Audio track configuration: "+bufferSize+" "+lowLatency);
                break;
            } catch (Exception e) {
                // Try to release the AudioTrack if we got far enough
                e.printStackTrace();
                try {
                    if (track != null) {
                        track.release();
                        track = null;
                    }
                } catch (Exception ignored) {}
            }
        }

        if (track == null) {
            // Couldn't create any audio track for playback
            return -2;
        }

        startAudioWriter();

        return 0;
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        // With a wired DualSense headset attached, the native USB ISO stream
        // becomes the audio sink. Do this before AudioTrack.write() so game
        // audio does not leak to the phone/tablet speakers in parallel.
        if (DualSenseAudioBridge.routeStandardStreamAudio(audioData, channelCount)) {
            // Do not let already-decoded phone audio leak out after the physical
            // DualSense headset route takes ownership.
            audioWriteQueue.clear();
            return;
        }
        // Only queue up to 40 ms of pending audio data in addition to what AudioTrack is buffering for us.
        if (MoonBridge.getPendingAudioDuration() < 40) {
            // Keep AudioTrack blocking and exact on its own audio-priority thread.
            // A non-blocking write may accept only part of this PCM block; dropping
            // the remainder creates clicks and eventually an AudioTrack underrun.
            // The bounded queue keeps that vendor blocking away from the native
            // stream decoder and from the independent DualSense HID sender.
            short[] queuedAudio = audioData.clone();
            if (!audioWriteQueue.offer(queuedAudio)) {
                audioWriteQueue.poll();
                audioWriteQueue.offer(queuedAudio);
                LimeLog.warning("Android audio writer dropped one stale PCM block");
            }
        }
        else {
            LimeLog.info("Too much pending audio data: " + MoonBridge.getPendingAudioDuration() +" ms");
        }
    }

    @Override
    public void start() {
        if (enableAudioFx) {
            // Open an audio effect control session to allow equalizers to apply audio effects
            Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            i.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_GAME);
            context.sendBroadcast(i);
        }
    }

    @Override
    public void stop() {
        if (enableAudioFx) {
            // Close our audio effect control session when we're stopping
            Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
            i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
            context.sendBroadcast(i);
        }
    }

    @Override
    public void cleanup() {
        audioWriterRunning = false;
        if (audioWriterThread != null) {
            audioWriterThread.interrupt();
        }
        // Immediately drop all pending data
        track.pause();
        track.flush();

        if (audioWriterThread != null) {
            try {
                audioWriterThread.join(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            audioWriterThread = null;
        }
        audioWriteQueue.clear();

        track.release();
    }

    private void startAudioWriter() {
        audioWriteQueue.clear();
        audioWriterRunning = true;
        audioWriterThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            while (audioWriterRunning && !Thread.currentThread().isInterrupted()) {
                final short[] pcm;
                try {
                    pcm = audioWriteQueue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                int offset = 0;
                while (audioWriterRunning && offset < pcm.length) {
                    // The three-argument overload is blocking on every supported
                    // Android version and never silently discards a partial tail.
                    int written = track.write(pcm, offset, pcm.length - offset);
                    if (written <= 0) {
                        LimeLog.warning("Android audio writer failed: " + written);
                        break;
                    }
                    offset += written;
                }
            }
        }, "AndroidAudioTrackWriter");
        audioWriterThread.setDaemon(true);
        audioWriterThread.start();
    }
}
