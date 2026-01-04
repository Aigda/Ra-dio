package com.example.directtest.sync;

import com.example.directtest.DiagnosticLogger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Менеджер синхронизации сообщений между устройствами.
 *
 * Логика работы:
 * 1. Каждую минуту (или при переходе устройства в online) проверяем needsSync()
 * 2. Если нужна синхронизация - публикуем SYNC с историей сообщений
 * 3. При получении SYNC - сравниваем и переотправляем недостающие
 * 4. При успешной синхронизации - ставим флаг synced = true
 */
public class SyncManager {

    private static final long SYNC_INACTIVITY_THRESHOLD = 60_000;  // 1 минута
    private static final long SYNC_INTERVAL = 60_000;              // Повторять каждую минуту

    private final DeviceStateRepository repository;
    private final DiagnosticLogger log = DiagnosticLogger.getInstance();

    private SyncCallback callback;

    // Pending sync запросы (чтобы не спамить)
    private final Set<String> pendingSyncRequests = new HashSet<>();

    // ==================== CALLBACK INTERFACE ====================

    public interface SyncCallback {
        void onPublishSync(String targetDeviceId, List<String> mySentIds, List<String> myRecvIds);
        void onResendMessage(String targetDeviceId, String msgId, String text);
        void onSyncComplete(String deviceId);
    }

    // ==================== CONSTRUCTOR ====================

    public SyncManager(DeviceStateRepository repository) {
        this.repository = repository;
    }

    public void setCallback(SyncCallback callback) {
        this.callback = callback;
    }

    // ==================== ПРОВЕРКА ТРИГГЕРОВ ====================

    /**
     * Вызывается периодически (каждые 10 сек).
     * Проверяет, нужна ли синхронизация для каждого устройства.
     */
    public void checkSyncNeeded() {
        long now = System.currentTimeMillis();

        Map<String, DeviceState> allStates = repository.getAll();

        for (DeviceState state : allStates.values()) {
            // Главная проверка: нужна ли вообще синхронизация?
            if (!state.needsSync()) {
                continue;
            }

            boolean shouldSync = false;

            // Триггер 1: Прошло больше минуты с последней активности
            long lastActivity = state.getLastActivityTime();
            if (lastActivity > 0 && now - lastActivity > SYNC_INACTIVITY_THRESHOLD) {
                // Но не чаще чем раз в минуту
                if (now - state.lastSyncSentTime > SYNC_INTERVAL) {
                    shouldSync = true;
                    log.d("[Sync] Trigger: inactivity for " + state.deviceId);
                }
            }

            // Триггер 2: Есть неподтверждённые сообщения и прошёл интервал
            if (!shouldSync && hasUnackedMessages(state)) {
                if (now - state.lastSyncSentTime > SYNC_INTERVAL) {
                    shouldSync = true;
                    log.d("[Sync] Trigger: unacked messages for " + state.deviceId);
                }
            }

            if (shouldSync && callback != null) {
                publishSync(state);
            }
        }
    }

    /**
     * Проверить есть ли неподтверждённые сообщения
     */
    private boolean hasUnackedMessages(DeviceState state) {
        for (DeviceState.MessageRecord m : state.sentMessages) {
            if (!m.acked) {
                return true;
            }
        }
        return false;
    }

    /**
     * Вызывается когда устройство стало online после offline
     */
    public void onDeviceBecameOnline(String deviceId) {
        DeviceState state = repository.get(deviceId);
        if (state == null) {
            return;
        }

        // Запускаем синхронизацию только если нужно
        if (state.needsSync() && callback != null) {
            log.i("[Sync] Device " + deviceId + " came online, triggering sync");
            publishSync(state);
        }
    }

    // ==================== ПУБЛИКАЦИЯ SYNC ====================

    private void publishSync(DeviceState state) {
        state.lastSyncSentTime = System.currentTimeMillis();

        List<String> mySentIds = state.getSentMessageIds();
        List<String> myRecvIds = state.getRecvMessageIds();

        log.i("[Sync] Publishing SYNC for " + state.deviceId +
                " | sent=" + mySentIds + " | recv=" + myRecvIds);

        if (callback != null) {
            callback.onPublishSync(state.deviceId, mySentIds, myRecvIds);
        }
    }

    // ==================== ОБРАБОТКА ВХОДЯЩЕГО SYNC ====================

    /**
     * Обработать SYNC от другого устройства.
     *
     * @param senderId - ID устройства, приславшего SYNC
     * @param theirSentIds - что они нам отправляли (их sent)
     * @param theirRecvIds - что они от нас получили (их recv)
     */
    public void processIncomingSync(String senderId, List<String> theirSentIds, List<String> theirRecvIds) {
        DeviceState state = repository.get(senderId);
        if (state == null) {
            log.w("[Sync] No state for device " + senderId);
            return;
        }

        log.i("[Sync] Processing SYNC from " + senderId);
        log.d("[Sync] theirRecvIds=" + theirRecvIds);
        log.d("[Sync] mySentIds=" + state.getSentMessageIds());

        // Находим сообщения, которые я отправлял, но другая сторона не получила
        List<DeviceState.MessageRecord> undelivered = state.findUndeliveredSent(theirRecvIds);

        if (!undelivered.isEmpty()) {
            log.w("[Sync] Found " + undelivered.size() + " undelivered messages to " + senderId);

            for (DeviceState.MessageRecord msg : undelivered) {
                log.i("[Sync] Resending: " + msg.msgId);
                if (callback != null) {
                    callback.onResendMessage(senderId, msg.msgId, msg.text);
                }
            }
        } else {
            log.d("[Sync] All messages delivered to " + senderId);

            // Все сообщения доставлены - помечаем как acked
            for (String msgId : theirRecvIds) {
                state.markAcked(msgId);
            }

            // Помечаем как синхронизированное
            state.markSynced();

            log.success("[Sync] All messages to " + senderId + " confirmed delivered");

            if (callback != null) {
                callback.onSyncComplete(senderId);
            }
        }

        // Очищаем pending запросы для этого устройства
        clearPendingRequest(senderId);
    }

    // ==================== PENDING REQUESTS ====================

    public void clearPendingRequest(String deviceId) {
        pendingSyncRequests.removeIf(key -> key.startsWith(deviceId));
    }

    public void clearAllPending() {
        pendingSyncRequests.clear();
    }

    // ==================== STATIC HELPERS ====================

    public static List<String> parseIdList(String idsString) {
        List<String> result = new ArrayList<>();
        if (idsString == null || idsString.isEmpty()) {
            return result;
        }
        String[] parts = idsString.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    public static String formatIdList(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }
}