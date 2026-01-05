package com.example.directtest.model;

/**
 * Информация о сообщении, ожидающем подтверждения.
 * Хранится до получения ACK или истечения таймаута.
 */
public class PendingMessage {

    /** Уникальный ID сообщения (формат: deviceId_sessionId_counter) */
    public String messageId;

    /** Текст сообщения */
    public String message;

    /** ID целевого устройства (null для broadcast) */
    public String targetDeviceId;

    /** Индекс слота, в котором опубликовано сообщение */
    public int slotIndex;

    /** Время отправки (мс) */
    public long sentAt;

    /**
     * Создать pending сообщение
     * @param id ID сообщения
     * @param msg текст сообщения
     * @param target ID целевого устройства
     * @param slot индекс слота
     */
    public PendingMessage(String id, String msg, String target, int slot) {
        this.messageId = id;
        this.message = msg;
        this.targetDeviceId = target;
        this.slotIndex = slot;
        this.sentAt = System.currentTimeMillis();
    }

    /**
     * Получить возраст сообщения в миллисекундах
     * @return время с момента отправки
     */
    public long getAge() {
        return System.currentTimeMillis() - sentAt;
    }

    /**
     * Проверить, истёк ли таймаут ожидания
     * @param timeoutMs таймаут в миллисекундах
     * @return true если таймаут истёк
     */
    public boolean isExpired(long timeoutMs) {
        return getAge() > timeoutMs;
    }

    @Override
    public String toString() {
        return "PendingMessage{" +
                "msgId='" + messageId + '\'' +
                ", target='" + targetDeviceId + '\'' +
                ", slot=" + slotIndex +
                ", age=" + (getAge() / 1000) + "s" +
                '}';
    }
}