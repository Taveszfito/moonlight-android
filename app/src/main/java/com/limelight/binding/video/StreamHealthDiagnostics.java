package com.limelight.binding.video;

import android.content.Context;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small, low-overhead persistent event log for stream stalls. It deliberately
 * records only abnormal conditions so enabling it cannot become a new source
 * of streaming stutter.
 */
final class StreamHealthDiagnostics {
    private static final long MAX_LOG_BYTES = 512 * 1024L;
    private static final long EVENT_THROTTLE_MS = 1_000L;

    private final File logFile;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Stream health log");
        thread.setDaemon(true);
        return thread;
    });
    private volatile long lastEventMs;

    StreamHealthDiagnostics(Context context) {
        File directory = new File(context.getFilesDir(), "diagnostics");
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        logFile = new File(directory, "stream-health.log");
        event("session", "stream diagnostics started");
    }

    void event(String category, String message) {
        long now = SystemClock.elapsedRealtime();
        if (!"session".equals(category) && now - lastEventMs < EVENT_THROTTLE_MS) {
            return;
        }
        lastEventMs = now;
        final String line = String.format(Locale.US, "%s +%dms [%s] %s%n",
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date()),
                now, category, message);
        writer.execute(() -> append(line));
    }

    private void append(String line) {
        try {
            if (logFile.length() > MAX_LOG_BYTES) {
                File previous = new File(logFile.getParentFile(), "stream-health.previous.log");
                //noinspection ResultOfMethodCallIgnored
                previous.delete();
                //noinspection ResultOfMethodCallIgnored
                logFile.renameTo(previous);
            }
            try (FileOutputStream output = new FileOutputStream(logFile, true)) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // Diagnostics must never interfere with video playback.
        }
    }
}
