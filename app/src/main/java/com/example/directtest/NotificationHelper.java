package com.example.directtest;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

public class NotificationHelper {

    // Константы
    public static final String CHANNEL_ID = "wifi_direct_service";
    public static final int NOTIFICATION_ID = 1001;

    // Action для остановки сервиса
    public static final String ACTION_STOP_SERVICE = "com.example.directtest.STOP_SERVICE";

    private final Context context;
    private final NotificationManager notificationManager;

    // Текущее состояние
    private int totalDevices = 0;
    private int onlineWithApp = 0;

    public NotificationHelper(Context context) {
        this.context = context;
        this.notificationManager = (NotificationManager)
                context.getSystemService(Context.NOTIFICATION_SERVICE);

        createNotificationChannel();
    }

    /**
     * Создание канала уведомлений (Android 8+)
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "WiFi Direct Service",
                    NotificationManager.IMPORTANCE_LOW  // Без звука
            );
            channel.setDescription("Shows WiFi Direct connection status");
            channel.setShowBadge(false);
            channel.enableLights(false);
            channel.enableVibration(false);

            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * Создать уведомление для Foreground Service
     */
    public Notification buildForegroundNotification() {
        return buildNotification();
    }

    /**
     * Обновить статус и уведомление
     */
    public void updateStatus(int totalDevices, int onlineWithApp) {
        this.totalDevices = totalDevices;
        this.onlineWithApp = onlineWithApp;

        Notification notification = buildNotification();
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    /**
     * Построить уведомление с текущим статусом
     */
    private Notification buildNotification() {
        // Intent для открытия MainActivity при нажатии
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent openPendingIntent = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Intent для остановки сервиса
        Intent stopIntent = new Intent(context, DiscoveryService.class);
        stopIntent.setAction(ACTION_STOP_SERVICE);

        PendingIntent stopPendingIntent = PendingIntent.getService(
                context, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // Выбор иконки и текста
        int iconRes;
        String title;
        String text;

        if (onlineWithApp > 0) {
            // Зелёный - есть устройства онлайн
            iconRes = R.drawable.ic_service_green;
            title = "WiFi Direct Active";
            text = String.format("Online: %d device%s with app",
                    onlineWithApp, onlineWithApp == 1 ? "" : "s");
        } else {
            // Красный - нет устройств
            iconRes = R.drawable.ic_service_red;
            title = "WiFi Direct";
            if (totalDevices > 0) {
                text = String.format("Found %d device%s (no app)",
                        totalDevices, totalDevices == 1 ? "" : "s");
            } else {
                text = "Searching for devices...";
            }
        }

        // Построение уведомления
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(iconRes)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)                    // Нельзя смахнуть
                .setOnlyAlertOnce(true)              // Не пищать при обновлении
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Stop",
                        stopPendingIntent
                );

        // Для Android 12+ - показывать сразу
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(
                    NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
            );
        }

        return builder.build();
    }

    /**
     * Проверить, есть ли устройства онлайн
     */
    public boolean hasOnlineDevices() {
        return onlineWithApp > 0;
    }

    /**
     * Получить текущее количество устройств онлайн
     */
    public int getOnlineWithAppCount() {
        return onlineWithApp;
    }

    /**
     * Получить общее количество устройств
     */
    public int getTotalDevicesCount() {
        return totalDevices;
    }
}