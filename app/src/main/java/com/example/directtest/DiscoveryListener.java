package com.example.directtest;

import com.example.directtest.model.DiscoveredDevice;

/**
 * Интерфейс для получения уведомлений о событиях WiFi P2P Discovery.
 * Реализуется DiscoveryService для передачи событий в UI.
 */
public interface DiscoveryListener {

    /**
     * Обнаружено новое устройство
     * @param device информация об устройстве
     */
    void onDeviceFound(DiscoveredDevice device);

    /**
     * Информация об устройстве обновлена
     * @param device обновлённая информация
     */
    void onDeviceUpdated(DiscoveredDevice device);

    /**
     * Устройство потеряно (не отвечает долгое время)
     * @param device информация об устройстве
     */
    void onDeviceLost(DiscoveredDevice device);

    /**
     * Изменился онлайн-статус устройства
     * @param device устройство
     * @param isOnline true если онлайн
     */
    void onDeviceOnlineStatusChanged(DiscoveredDevice device, boolean isOnline);

    /**
     * Изменился общий статус discovery
     * @param status текстовое описание статуса
     */
    void onStatusChanged(String status);

    /**
     * Произошла ошибка
     * @param message описание ошибки
     */
    void onError(String message);

    /**
     * Сообщение отправлено
     * @param messageId уникальный идентификатор сообщения
     * @param message текст сообщения
     * @param targetDeviceId ID целевого устройства (null для broadcast)
     */
    void onMessageSent(String messageId, String message, String targetDeviceId);

    /**
     * Получено сообщение
     * @param device устройство-отправитель
     * @param messageId уникальный идентификатор сообщения
     * @param message текст сообщения
     */
    void onMessageReceived(DiscoveredDevice device, String messageId, String message);

    /**
     * Получено подтверждение доставки сообщения
     * @param device устройство, подтвердившее получение
     * @param ackedMessageId ID подтверждённого сообщения
     */
    void onAckReceived(DiscoveredDevice device, String ackedMessageId);
}