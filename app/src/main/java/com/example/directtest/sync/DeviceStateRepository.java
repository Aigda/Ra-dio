package com.example.directtest.sync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.example.directtest.DiagnosticLogger;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Репозиторий для хранения состояний устройств.
 * Сохраняет и загружает данные из JSON файла.
 * Использует debounce для избежания частых записей.
 */
public class DeviceStateRepository {

    private static final String FILENAME = "device_states.json";
    private static final int VERSION = 1;

    // Debounce: сохранять не чаще чем раз в N миллисекунд
    private static final long SAVE_DEBOUNCE_MS = 2000;

    private final Context context;
    private final Map<String, DeviceState> states = new ConcurrentHashMap<>();
    private final DiagnosticLogger log = DiagnosticLogger.getInstance();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String myDeviceId;
    private String mySessionId;

    // Флаг что есть несохранённые изменения
    private volatile boolean dirty = false;

    // Время последнего сохранения
    private long lastSaveTime = 0;

    public DeviceStateRepository(Context context) {
        this.context = context.getApplicationContext();
    }

    // ==================== ИНИЦИАЛИЗАЦИЯ ====================

    /**
     * Инициализировать репозиторий с ID текущего устройства
     */
    public void initialize(String deviceId, String sessionId) {
        this.myDeviceId = deviceId;
        this.mySessionId = sessionId;
        load();
    }

    // ==================== ОПЕРАЦИИ С УСТРОЙСТВАМИ ====================

    /**
     * Получить или создать состояние для устройства
     */
    public DeviceState getOrCreate(String deviceId) {
        if (deviceId == null || deviceId.isEmpty()) {
            return new DeviceState();
        }

        DeviceState state = states.get(deviceId);
        if (state == null) {
            state = new DeviceState();
            state.deviceId = deviceId;
            state.firstSeen = System.currentTimeMillis();
            states.put(deviceId, state);
            log.i("[Repo] New device state created: " + deviceId);
        }
        state.lastSeen = System.currentTimeMillis();
        return state;
    }

    /**
     * Получить состояние устройства (null если не существует)
     */
    public DeviceState get(String deviceId) {
        if (deviceId == null) return null;
        return states.get(deviceId);
    }

    /**
     * Получить все состояния устройств
     */
    public Map<String, DeviceState> getAll() {
        return states;
    }

    /**
     * Удалить состояние устройства
     */
    public void remove(String deviceId) {
        states.remove(deviceId);
        markDirty();
    }

    /**
     * Очистить все состояния
     */
    public void clear() {
        states.clear();
        markDirty();
    }

    // ==================== СОХРАНЕНИЕ С DEBOUNCE ====================

    /**
     * Пометить что есть изменения для сохранения.
     * Фактическое сохранение произойдёт с debounce.
     */
    public void markDirty() {
        dirty = true;
        scheduleSave();
    }

    /**
     * Запланировать сохранение с debounce
     */
    public void scheduleSave() {
        handler.removeCallbacks(saveRunnable);

        long now = System.currentTimeMillis();
        long timeSinceLastSave = now - lastSaveTime;

        if (timeSinceLastSave >= SAVE_DEBOUNCE_MS) {
            // Можно сохранить сразу
            handler.post(saveRunnable);
        } else {
            // Отложить до конца debounce периода
            long delay = SAVE_DEBOUNCE_MS - timeSinceLastSave;
            handler.postDelayed(saveRunnable, delay);
        }
    }

    private final Runnable saveRunnable = new Runnable() {
        @Override
        public void run() {
            if (dirty) {
                saveNow();
            }
        }
    };

    /**
     * Немедленное сохранение (для критических моментов, например при остановке)
     */
    public void saveNow() {
        dirty = false;
        lastSaveTime = System.currentTimeMillis();

        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("myDeviceId", myDeviceId);
            root.put("mySessionId", mySessionId);
            root.put("savedAt", System.currentTimeMillis());

            JSONObject devicesJson = new JSONObject();
            for (Map.Entry<String, DeviceState> entry : states.entrySet()) {
                devicesJson.put(entry.getKey(), stateToJson(entry.getValue()));
            }
            root.put("devices", devicesJson);

            File file = new File(context.getFilesDir(), FILENAME);
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(root.toString(2));
            }

            log.d("[Repo] Saved " + states.size() + " device states");

        } catch (JSONException | IOException e) {
            log.e("[Repo] Save error: " + e.getMessage());
        }
    }

    /**
     * Для совместимости со старым кодом - вызывает markDirty()
     */
    public void save() {
        markDirty();
    }

    // ==================== ЗАГРУЗКА ====================

    /**
     * Загрузить состояния из файла
     */
    public void load() {
        File file = new File(context.getFilesDir(), FILENAME);
        if (!file.exists()) {
            log.i("[Repo] No saved state file");
            return;
        }

        try {
            StringBuilder sb = new StringBuilder();
            try (FileReader reader = new FileReader(file)) {
                char[] buf = new char[1024];
                int len;
                while ((len = reader.read(buf)) > 0) {
                    sb.append(buf, 0, len);
                }
            }

            JSONObject root = new JSONObject(sb.toString());

            // Проверяем версию
            int version = root.optInt("version", 0);
            if (version != VERSION) {
                log.w("[Repo] Version mismatch (file=" + version + ", current=" + VERSION + "), ignoring");
                return;
            }

            // Загружаем устройства
            JSONObject devicesJson = root.optJSONObject("devices");
            if (devicesJson != null) {
                for (Iterator<String> it = devicesJson.keys(); it.hasNext(); ) {
                    String deviceId = it.next();
                    try {
                        DeviceState state = jsonToState(devicesJson.getJSONObject(deviceId));
                        if (state != null && state.deviceId != null) {
                            states.put(deviceId, state);
                        }
                    } catch (JSONException e) {
                        log.w("[Repo] Failed to parse device " + deviceId + ": " + e.getMessage());
                    }
                }
            }

            log.success("[Repo] Loaded " + states.size() + " device states");

        } catch (JSONException | IOException e) {
            log.e("[Repo] Load error: " + e.getMessage());
        }
    }

    // ==================== JSON КОНВЕРТАЦИЯ ====================

    private JSONObject stateToJson(DeviceState state) throws JSONException {
        JSONObject json = new JSONObject();

        json.put("deviceId", state.deviceId);
        json.put("name", state.name);
        json.put("address", state.address);
        json.put("firstSeen", state.firstSeen);
        json.put("lastSeen", state.lastSeen);
        json.put("lastSessionId", state.lastSessionId);
        json.put("lastSentTime", state.lastSentTime);
        json.put("lastRecvTime", state.lastRecvTime);
        json.put("lastSyncSentTime", state.lastSyncSentTime);
        json.put("synced", state.synced);

        // Отправленные сообщения
        JSONArray sentArr = new JSONArray();
        for (DeviceState.MessageRecord m : state.sentMessages) {
            sentArr.put(messageToJson(m));
        }
        json.put("sentMessages", sentArr);

        // Полученные сообщения
        JSONArray recvArr = new JSONArray();
        for (DeviceState.MessageRecord m : state.recvMessages) {
            recvArr.put(messageToJson(m));
        }
        json.put("recvMessages", recvArr);

        return json;
    }

    private DeviceState jsonToState(JSONObject json) throws JSONException {
        DeviceState state = new DeviceState();

        state.deviceId = json.optString("deviceId", null);
        state.name = json.optString("name", null);
        state.address = json.optString("address", null);
        state.firstSeen = json.optLong("firstSeen", 0);
        state.lastSeen = json.optLong("lastSeen", 0);
        state.lastSessionId = json.optString("lastSessionId", null);
        state.lastSentTime = json.optLong("lastSentTime", 0);
        state.lastRecvTime = json.optLong("lastRecvTime", 0);
        state.lastSyncSentTime = json.optLong("lastSyncSentTime", 0);
        state.synced = json.optBoolean("synced", false);

        // Отправленные сообщения
        JSONArray sentArr = json.optJSONArray("sentMessages");
        if (sentArr != null) {
            for (int i = 0; i < sentArr.length(); i++) {
                try {
                    state.sentMessages.add(jsonToMessage(sentArr.getJSONObject(i)));
                } catch (JSONException e) {
                    // Пропускаем битые записи
                }
            }
        }

        // Полученные сообщения
        JSONArray recvArr = json.optJSONArray("recvMessages");
        if (recvArr != null) {
            for (int i = 0; i < recvArr.length(); i++) {
                try {
                    state.recvMessages.add(jsonToMessage(recvArr.getJSONObject(i)));
                } catch (JSONException e) {
                    // Пропускаем битые записи
                }
            }
        }

        return state;
    }

    private JSONObject messageToJson(DeviceState.MessageRecord m) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("msgId", m.msgId);
        json.put("text", m.text);
        json.put("timestamp", m.timestamp);
        json.put("acked", m.acked);
        json.put("ackTime", m.ackTime);
        return json;
    }

    private DeviceState.MessageRecord jsonToMessage(JSONObject json) throws JSONException {
        DeviceState.MessageRecord m = new DeviceState.MessageRecord();
        m.msgId = json.getString("msgId");
        m.text = json.optString("text", "");
        m.timestamp = json.optLong("timestamp", 0);
        m.acked = json.optBoolean("acked", false);
        m.ackTime = json.optLong("ackTime", 0);
        return m;
    }

    // ==================== DEBUG ====================

    /**
     * Получить путь к файлу состояний
     */
    public String getFilePath() {
        return new File(context.getFilesDir(), FILENAME).getAbsolutePath();
    }

    /**
     * Получить размер файла состояний
     */
    public long getFileSize() {
        File file = new File(context.getFilesDir(), FILENAME);
        return file.exists() ? file.length() : 0;
    }

    /**
     * Есть ли несохранённые изменения
     */
    public boolean isDirty() {
        return dirty;
    }

    @Override
    public String toString() {
        return "DeviceStateRepository{" +
                "devices=" + states.size() +
                ", dirty=" + dirty +
                ", myDeviceId='" + myDeviceId + '\'' +
                '}';
    }
}