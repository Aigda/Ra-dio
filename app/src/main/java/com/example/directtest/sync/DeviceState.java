package com.example.directtest.sync;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Состояние взаимодействия с конкретным устройством.
 * Хранит историю отправленных и полученных сообщений (до 3 последних).
 */
public class DeviceState {

    // Идентификация устройства
    public String deviceId;
    public String name;
    public String address;
    public long firstSeen;
    public long lastSeen;
    public String lastSessionId;

    // Время последней активности (для триггера синхронизации)
    public long lastSentTime;
    public long lastRecvTime;

    // Время последней отправки SYNC
    public long lastSyncSentTime;

    // Флаг: синхронизация завершена (все сообщения доставлены)
    public boolean synced;

    // История сообщений (максимум 3)
    public List<MessageRecord> sentMessages = new ArrayList<>();
    public List<MessageRecord> recvMessages = new ArrayList<>();

    private static final int MAX_MESSAGES = 3;

    // ==================== MESSAGE RECORD ====================

    public static class MessageRecord {
        public String msgId;
        public String text;
        public long timestamp;
        public boolean acked;
        public long ackTime;

        public MessageRecord() {}

        public MessageRecord(String msgId, String text) {
            this.msgId = msgId;
            this.text = text;
            this.timestamp = System.currentTimeMillis();
            this.acked = false;
            this.ackTime = 0;
        }

        @Override
        public String toString() {
            return "MessageRecord{" +
                    "msgId='" + msgId + '\'' +
                    ", acked=" + acked +
                    '}';
        }
    }

    // ==================== ОТПРАВЛЕННЫЕ СООБЩЕНИЯ ====================

    /**
     * Добавить отправленное сообщение в историю
     */
    public void addSentMessage(String msgId, String text) {
        // Проверяем дубликаты
        for (MessageRecord m : sentMessages) {
            if (m.msgId.equals(msgId)) {
                return;
            }
        }

        MessageRecord msg = new MessageRecord(msgId, text);
        sentMessages.add(0, msg);

        // Храним только MAX_MESSAGES последних
        while (sentMessages.size() > MAX_MESSAGES) {
            sentMessages.remove(sentMessages.size() - 1);
        }

        lastSentTime = System.currentTimeMillis();

        // Сбрасываем флаг синхронизации - есть новое сообщение
        synced = false;
    }

    /**
     * Получить список ID отправленных сообщений
     */
    public List<String> getSentMessageIds() {
        List<String> ids = new ArrayList<>();
        for (MessageRecord m : sentMessages) {
            ids.add(m.msgId);
        }
        return ids;
    }

    /**
     * Найти отправленное сообщение по ID
     */
    public MessageRecord findSentMessage(String msgId) {
        for (MessageRecord m : sentMessages) {
            if (m.msgId.equals(msgId)) {
                return m;
            }
        }
        return null;
    }

    /**
     * Пометить отправленное сообщение как подтверждённое
     */
    public void markAcked(String msgId) {
        for (MessageRecord m : sentMessages) {
            if (m.msgId.equals(msgId)) {
                m.acked = true;
                m.ackTime = System.currentTimeMillis();
                break;
            }
        }
    }

    // ==================== ПОЛУЧЕННЫЕ СООБЩЕНИЯ ====================

    /**
     * Добавить полученное сообщение в историю
     */
    public void addRecvMessage(String msgId, String text) {
        // Проверяем дубликаты
        for (MessageRecord m : recvMessages) {
            if (m.msgId.equals(msgId)) {
                return;
            }
        }

        MessageRecord msg = new MessageRecord(msgId, text);
        recvMessages.add(0, msg);

        while (recvMessages.size() > MAX_MESSAGES) {
            recvMessages.remove(recvMessages.size() - 1);
        }

        lastRecvTime = System.currentTimeMillis();

        // Сбрасываем флаг синхронизации - есть новое сообщение
        synced = false;
    }

    /**
     * Получить список ID полученных сообщений
     */
    public List<String> getRecvMessageIds() {
        List<String> ids = new ArrayList<>();
        for (MessageRecord m : recvMessages) {
            ids.add(m.msgId);
        }
        return ids;
    }

    /**
     * Проверить, есть ли полученное сообщение с таким ID
     */
    public boolean hasRecvMessage(String msgId) {
        for (MessageRecord m : recvMessages) {
            if (m.msgId.equals(msgId)) {
                return true;
            }
        }
        return false;
    }

    // ==================== СИНХРОНИЗАЦИЯ ====================

    /**
     * Найти сообщения, которые я отправлял, но другая сторона не получила.
     *
     * @param theirRecvIds - список msgId которые другая сторона получила от меня
     * @return список сообщений для повторной отправки
     */
    public List<MessageRecord> findUndeliveredSent(List<String> theirRecvIds) {
        List<MessageRecord> undelivered = new ArrayList<>();
        Set<String> theirSet = new HashSet<>(theirRecvIds);

        for (MessageRecord m : sentMessages) {
            if (!theirSet.contains(m.msgId)) {
                undelivered.add(m);
            }
        }
        return undelivered;
    }

    /**
     * Проверить, нужна ли синхронизация.
     * Возвращает true если:
     * - Есть неподтверждённое отправленное сообщение
     * - Или флаг synced == false (есть новые сообщения после последней синхронизации)
     */
    public boolean needsSync() {
        // Если уже синхронизированы - не нужно
        if (synced) {
            return false;
        }

        // Если нет истории - не нужно
        if (sentMessages.isEmpty() && recvMessages.isEmpty()) {
            return false;
        }

        // Если есть неподтверждённые - нужно
        for (MessageRecord m : sentMessages) {
            if (!m.acked) {
                return true;
            }
        }

        // Если есть полученные сообщения и не синхронизированы - нужно
        // (чтобы другая сторона узнала что мы получили)
        if (!recvMessages.isEmpty() && !synced) {
            return true;
        }

        return false;
    }

    /**
     * Пометить как синхронизированное
     */
    public void markSynced() {
        synced = true;
    }

    /**
     * Время последней активности (максимум из lastSentTime и lastRecvTime)
     */
    public long getLastActivityTime() {
        return Math.max(lastSentTime, lastRecvTime);
    }

    // ==================== UTILITIES ====================

    /**
     * Очистить историю сообщений
     */
    public void clearMessages() {
        sentMessages.clear();
        recvMessages.clear();
        lastSentTime = 0;
        lastRecvTime = 0;
        lastSyncSentTime = 0;
        synced = false;
    }

    @Override
    public String toString() {
        return "DeviceState{" +
                "deviceId='" + deviceId + '\'' +
                ", synced=" + synced +
                ", sent=" + getSentMessageIds() +
                ", recv=" + getRecvMessageIds() +
                '}';
    }
}