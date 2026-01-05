package com.example.directtest;

/**
 * Централизованная конфигурация WiFi P2P Discovery.
 * Все константы, таймауты и лимиты в одном месте.
 */
public final class P2pConfig {

    private P2pConfig() {
        // Utility class
    }

    // ==================== SERVICE NAMES ====================

    /**
     * Тип DNS-SD сервиса
     */
    public static final String SERVICE_TYPE = "_wfd._tcp";

    /**
     * Имя основного сервиса (heartbeat)
     */
    public static final String MAIN_SERVICE_NAME = "WFD_Main";

    /**
     * Префикс для слотов сообщений (WFD_Msg0, WFD_Msg1, WFD_Msg2)
     */
    public static final String MSG_SLOT_PREFIX = "WFD_Msg";

    /**
     * Имя сервиса подтверждений
     */
    public static final String ACK_SERVICE_NAME = "WFD_Ack";

    /**
     * Имя сервиса синхронизации
     */
    public static final String SYNC_SERVICE_NAME = "WFD_Sync";

    /**
     * Маркер в имени устройства для идентификации нашего приложения
     */
    public static final String APP_MARKER = "[WFD]";

    // ==================== LIMITS ====================

    /**
     * Максимальное количество слотов для сообщений
     */
    public static final int MAX_MSG_SLOTS = 3;

    /**
     * Максимальное количество обработанных ID сообщений в памяти
     */
    public static final int MAX_PROCESSED_IDS = 100;

    /**
     * Максимальное количество обработанных ACK в памяти
     */
    public static final int MAX_PROCESSED_ACKS = 50;

    /**
     * Максимальное количество TXT записей для дедупликации
     */
    public static final int MAX_RECENT_TXT_RECORDS = 50;

    /**
     * Максимальная длина сообщения в TXT record
     */
    public static final int MAX_MESSAGE_LENGTH = 100;

    /**
     * Максимальное количество ACK в одной записи
     */
    public static final int MAX_ACKS_PER_RECORD = 5;

    // ==================== TIMING: BURST DISCOVERY ====================

    /**
     * Интервал между шагами начального burst (мс)
     */
    public static final long INITIAL_BURST_INTERVAL = 300;

    /**
     * Количество шагов начального burst
     */
    public static final int INITIAL_BURST_COUNT = 5;

    /**
     * Интервал между шагами основного burst (мс)
     */
    public static final long BURST_INTERVAL = 600;

    /**
     * Количество шагов основного burst
     */
    public static final int BURST_COUNT = 8;

    // ==================== TIMING: DISCOVERY ====================

    /**
     * Быстрый интервал discovery после обнаружения нового устройства (мс)
     */
    public static final long FAST_INTERVAL = 3_000;

    /**
     * Нормальный интервал discovery (мс)
     */
    public static final long NORMAL_INTERVAL = 8_000;

    /**
     * Длительность быстрого режима после обнаружения устройства (мс)
     */
    public static final long FAST_MODE_DURATION = 30_000;

    // ==================== TIMING: HEARTBEAT & SERVICES ====================

    /**
     * Интервал обновления heartbeat (мс)
     */
    public static final long HEARTBEAT_INTERVAL = 5_000;

    /**
     * Интервал обновления ACK сервиса (мс)
     */
    public static final long ACK_UPDATE_INTERVAL = 2_000;

    /**
     * Время жизни ACK сервиса (мс)
     */
    public static final long ACK_SERVICE_LIFETIME = 15_000;

    /**
     * Время жизни SYNC сервиса (мс)
     */
    public static final long SYNC_SERVICE_LIFETIME = 30_000;

    /**
     * Интервал проверки необходимости SYNC (мс)
     */
    public static final long SYNC_CHECK_INTERVAL = 10_000;

    // ==================== TIMING: MESSAGES ====================

    /**
     * Таймаут слота сообщения (мс)
     */
    public static final long SLOT_TIMEOUT = 30_000;

    /**
     * Максимальный возраст сообщения в секундах (TTL)
     */
    public static final long MAX_MSG_AGE_SEC = 120;

    /**
     * Окно дедупликации TXT записей (мс)
     */
    public static final long TXT_DEDUP_WINDOW_MS = 2_000;

    // ==================== TIMING: DEVICE STATUS ====================

    /**
     * Порог для определения онлайн-статуса устройства (мс)
     */
    public static final long DEVICE_ONLINE_THRESHOLD = 20_000;

    /**
     * Время жизни устройства в кэше (мс)
     */
    public static final long CACHE_TTL = 120_000;

    // ==================== PROTOCOL VERSION ====================

    /**
     * Версия протокола
     */
    public static final String PROTOCOL_VERSION = "4";
}