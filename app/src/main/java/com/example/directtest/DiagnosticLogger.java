package com.example.directtest;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class DiagnosticLogger {

    private static DiagnosticLogger instance;

    private final List<LogEntry> logs = new ArrayList<>();
    private final List<LogListener> listeners = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault());

    private static final int MAX_LOGS = 500;

    public static class LogEntry {
        public final long timestamp;
        public final String time;
        public final String level;
        public final String message;

        LogEntry(String level, String message) {
            this.timestamp = System.currentTimeMillis();
            this.time = new SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
            this.level = level;
            this.message = message;
        }

        @Override
        public String toString() {
            return time + " [" + level + "] " + message;
        }
    }

    public interface LogListener {
        void onLogAdded(LogEntry entry);
    }

    private DiagnosticLogger() {}

    public static synchronized DiagnosticLogger getInstance() {
        if (instance == null) {
            instance = new DiagnosticLogger();
        }
        return instance;
    }

    // ==================== LOGGING METHODS ====================

    public void d(String message) {
        addLog("D", message);
    }

    public void i(String message) {
        addLog("I", message);
    }

    public void w(String message) {
        addLog("W", message);
    }

    public void e(String message) {
        addLog("E", message);
    }

    public void success(String message) {
        addLog("✅", message);
    }

    public void error(String message) {
        addLog("❌", message);
    }

    public void divider(String title) {
        addLog("══", "════════ " + title + " ════════");
    }

    private void addLog(String level, String message) {
        LogEntry entry = new LogEntry(level, message);

        synchronized (logs) {
            logs.add(entry);
            // Ограничиваем размер
            while (logs.size() > MAX_LOGS) {
                logs.remove(0);
            }
        }

        // Уведомляем слушателей в UI потоке
        handler.post(() -> {
            for (LogListener listener : listeners) {
                listener.onLogAdded(entry);
            }
        });

        // Также выводим в стандартный лог
        android.util.Log.d("DiagLog", entry.toString());
    }

    // ==================== LISTENERS ====================

    public void addListener(LogListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(LogListener listener) {
        listeners.remove(listener);
    }

    // ==================== GET LOGS ====================

    public List<LogEntry> getAllLogs() {
        synchronized (logs) {
            return new ArrayList<>(logs);
        }
    }

    public String getLogsAsText() {
        StringBuilder sb = new StringBuilder();
        synchronized (logs) {
            for (LogEntry entry : logs) {
                sb.append(entry.toString()).append("\n");
            }
        }
        return sb.toString();
    }

    public void clear() {
        synchronized (logs) {
            logs.clear();
        }
    }

    // ==================== SAVE TO FILE ====================

    public File saveToFile(Context context) throws IOException {
        File dir = new File(context.getExternalFilesDir(null), "logs");
        if (!dir.exists()) {
            dir.mkdirs();
        }

        String filename = "diag_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date()) + ".txt";
        File file = new File(dir, filename);

        try (FileWriter writer = new FileWriter(file)) {
            writer.write("=== Diagnostic Log ===\n");
            writer.write("Device: " + android.os.Build.MODEL + "\n");
            writer.write("Android: " + android.os.Build.VERSION.RELEASE + "\n");
            writer.write("Time: " + new Date().toString() + "\n");
            writer.write("========================\n\n");
            writer.write(getLogsAsText());
        }

        return file;
    }

    public String getLogDirectory(Context context) {
        File dir = new File(context.getExternalFilesDir(null), "logs");
        return dir.getAbsolutePath();
    }
}