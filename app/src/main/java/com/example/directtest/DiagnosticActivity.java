package com.example.directtest;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;

public class DiagnosticActivity extends AppCompatActivity implements DiagnosticLogger.LogListener {

    private TextView tvLogs;
    private ScrollView scrollView;
    private DiagnosticLogger logger;
    private boolean autoScroll = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostic);

        tvLogs = findViewById(R.id.tv_logs);
        scrollView = findViewById(R.id.scroll_view);

        Button btnClear = findViewById(R.id.btn_clear);
        Button btnSave = findViewById(R.id.btn_save);
        Button btnShare = findViewById(R.id.btn_share);
        Button btnBack = findViewById(R.id.btn_back);

        logger = DiagnosticLogger.getInstance();

        // Загружаем существующие логи
        tvLogs.setText(logger.getLogsAsText());
        scrollToBottom();

        // Кнопки
        btnClear.setOnClickListener(v -> {
            logger.clear();
            tvLogs.setText("");
            Toast.makeText(this, "Логи очищены", Toast.LENGTH_SHORT).show();
        });

        btnSave.setOnClickListener(v -> saveToFile());

        btnShare.setOnClickListener(v -> shareLogs());

        btnBack.setOnClickListener(v -> finish());

        // Авто-скролл по клику
        scrollView.setOnClickListener(v -> {
            autoScroll = !autoScroll;
            Toast.makeText(this,
                    autoScroll ? "Авто-скролл ВКЛ" : "Авто-скролл ВЫКЛ",
                    Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        logger.addListener(this);
        // Обновляем логи
        tvLogs.setText(logger.getLogsAsText());
        scrollToBottom();
    }

    @Override
    protected void onPause() {
        super.onPause();
        logger.removeListener(this);
    }

    @Override
    public void onLogAdded(DiagnosticLogger.LogEntry entry) {
        tvLogs.append(entry.toString() + "\n");
        if (autoScroll) {
            scrollToBottom();
        }
    }

    private void scrollToBottom() {
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    private void saveToFile() {
        try {
            File file = logger.saveToFile(this);
            Toast.makeText(this,
                    "Сохранено:\n" + file.getAbsolutePath(),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this,
                    "Ошибка сохранения: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void shareLogs() {
        try {
            File file = logger.saveToFile(this);

            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, "Diagnostic Logs");
            intent.putExtra(Intent.EXTRA_TEXT, logger.getLogsAsText());

            // Если нужно прикрепить файл:
            // Uri uri = FileProvider.getUriForFile(this,
            //     getPackageName() + ".fileprovider", file);
            // intent.putExtra(Intent.EXTRA_STREAM, uri);
            // intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            startActivity(Intent.createChooser(intent, "Поделиться логами"));
        } catch (Exception e) {
            Toast.makeText(this,
                    "Ошибка: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }
}