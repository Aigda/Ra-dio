package com.example.directtest.model;

import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;

/**
 * Информация о слоте сообщения.
 * Используется для управления DNS-SD сервисами сообщений.
 */
public class SlotInfo {

    /** Индекс слота (0, 1, 2) */
    public int slotIndex;

    /** Зарегистрированный DNS-SD сервис */
    public WifiP2pDnsSdServiceInfo serviceInfo;

    /** ID сообщения в этом слоте */
    public String messageId;

    /** ID целевого устройства (null для broadcast) */
    public String targetDeviceId;

    /** Время создания слота (мс) */
    public long createdAt;

    /** Флаг: сервис успешно зарегистрирован */
    public boolean isRegistered;

    /**
     * Создать информацию о слоте
     * @param index индекс слота
     */
    public SlotInfo(int index) {
        this.slotIndex = index;
        this.createdAt = System.currentTimeMillis();
        this.isRegistered = false;
    }

    /**
     * Получить возраст слота в миллисекундах
     * @return время с момента создания
     */
    public long getAge() {
        return System.currentTimeMillis() - createdAt;
    }

    @Override
    public String toString() {
        return "SlotInfo{" +
                "slot=" + slotIndex +
                ", msgId='" + messageId + '\'' +
                ", target='" + targetDeviceId + '\'' +
                ", registered=" + isRegistered +
                ", age=" + (getAge() / 1000) + "s" +
                '}';
    }
}