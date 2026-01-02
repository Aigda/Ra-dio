package com.example.directtest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class FastDiscoveryManager {

    private static final String TAG = "FastDiscovery";

    // ==================== SERVICE NAMES ====================
    public static final String SERVICE_TYPE = "_wfd._tcp";
    private static final String MAIN_SERVICE_NAME = "WFD_Main";
    private static final String MSG_SLOT_PREFIX = "WFD_Msg";
    private static final int MAX_MSG_SLOTS = 3;

    // ==================== TIMING CONSTANTS ====================
    // Агрессивный burst на старте
    private static final long INITIAL_BURST_INTERVAL = 250;   // Первые запросы очень быстро
    private static final int INITIAL_BURST_COUNT = 3;
    private static final long BURST_INTERVAL = 500;
    private static final int BURST_COUNT = 5;

    // Интервалы работы
    private static final long FAST_INTERVAL = 3_000;
    private static final long NORMAL_INTERVAL = 10_000;
    private static final long FAST_MODE_DURATION = 30_000;

    // Service updates
    private static final long HEARTBEAT_INTERVAL = 3_000;     // Heartbeat каждые 3 сек
    private static final long ACK_BATCH_INTERVAL = 500;       // ACK батч каждые 500мс
    private static final long SLOT_TIMEOUT = 30_000;          // Слот живёт 30 сек
    private static final long DEVICE_ONLINE_THRESHOLD = 15_000; // 15 сек для "онлайн"
    private static final long CACHE_TTL = 120_000;

    // ==================== STATE ====================
    private final Context context;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;

    private final String deviceId;
    private final String shortDeviceId;
    private String macAddress = "02:00:00:00:00:00";

    // Services
    private WifiP2pDnsSdServiceInfo mainServiceInfo;
    private final Map<Integer, SlotInfo> messageSlots = new ConcurrentHashMap<>();
    private final List<WifiP2pServiceRequest> serviceRequests = new ArrayList<>();

    // Device cache
    private final Map<String, DiscoveredDevice> deviceCache = new ConcurrentHashMap<>();

    // Message tracking
    private final Map<String, PendingMessage> pendingMessages = new ConcurrentHashMap<>();
    private final Set<String> pendingAcks = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> processedMessageIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final int MAX_PROCESSED_IDS = 100;

    // Counters
    private final AtomicLong heartbeatSeq = new AtomicLong(0);
    private final AtomicInteger messageIdCounter = new AtomicInteger(0);

    // State flags
    private volatile boolean isRunning = false;
    private volatile boolean discoveryInProgress = false;
    private int burstCount = 0;
    private boolean initialBurstPhase = true;
    private long lastNewDeviceTime = 0;
    private long currentInterval = NORMAL_INTERVAL;

    private DiscoveryListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ==================== INNER CLASSES ====================

    private static class SlotInfo {
        int slotIndex;
        WifiP2pDnsSdServiceInfo serviceInfo;
        String messageId;
        String targetDeviceId; // null = broadcast
        long createdAt;
        boolean isRegistered;

        SlotInfo(int index) {
            this.slotIndex = index;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private static class PendingMessage {
        String messageId;
        String message;
        String targetDeviceId;
        int slotIndex;
        long sentAt;
        int retryCount;

        PendingMessage(String id, String msg, String target, int slot) {
            this.messageId = id;
            this.message = msg;
            this.targetDeviceId = target;
            this.slotIndex = slot;
            this.sentAt = System.currentTimeMillis();
            this.retryCount = 0;
        }
    }

    public static class DiscoveredDevice {
        public String address;
        public String deviceId;
        public String name;
        public WifiP2pDevice device;
        public boolean hasOurApp;
        public long firstSeen;
        public long lastSeen;
        public long lastHeartbeat;
        public long heartbeatSeq;
        public int seenCount;

        // Message state
        public String lastReceivedMsgId;
        public String lastReceivedMsg;
        public long lastReceivedTime;
        public String lastSentMsgId;
        public long lastAckTime;
        public Set<String> ackedMessageIds = new HashSet<>();

        public boolean isOnline() {
            return System.currentTimeMillis() - lastSeen < DEVICE_ONLINE_THRESHOLD;
        }

        public String getShortId() {
            if (deviceId != null && deviceId.length() >= 8) {
                return deviceId.substring(0, 8);
            }
            return address != null ? address.replace(":", "").substring(0, 8) : "unknown";
        }
    }

    // ==================== LISTENER ====================

    public interface DiscoveryListener {
        void onDeviceFound(DiscoveredDevice device);
        void onDeviceUpdated(DiscoveredDevice device);
        void onDeviceLost(DiscoveredDevice device);
        void onDeviceOnlineStatusChanged(DiscoveredDevice device, boolean isOnline);
        void onStatusChanged(String status);
        void onError(String message);
        void onMessageSent(String messageId, String message, String targetDeviceId);
        void onMessageReceived(DiscoveredDevice device, String messageId, String message);
        void onAckReceived(DiscoveredDevice device, String ackedMessageId);
    }

    // ==================== CONSTRUCTOR ====================

    public FastDiscoveryManager(Context context) {
        this.context = context.getApplicationContext();
        this.deviceId = generateDeviceId();
        this.shortDeviceId = deviceId.substring(0, 8);
    }

    private String generateDeviceId() {
        // Используем стабильный ID на основе Android ID
        String androidId = android.provider.Settings.Secure.getString(
                context.getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID
        );
        return UUID.nameUUIDFromBytes(androidId.getBytes()).toString().replace("-", "");
    }

    // ==================== PUBLIC API ====================

    public void start(DiscoveryListener listener) {
        this.listener = listener;

        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            notifyError("WiFi P2P not supported");
            return;
        }

        channel = manager.initialize(context, Looper.getMainLooper(), () -> {
            Log.w(TAG, "Channel disconnected");
            if (isRunning) {
                handler.postDelayed(this::reconnect, 2000);
            }
        });

        isRunning = true;
        burstCount = 0;
        initialBurstPhase = true;

        registerReceiver();
        initializeAndStart();

        notifyStatus("Starting...");
    }

    public void stop() {
        isRunning = false;
        handler.removeCallbacksAndMessages(null);

        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception e) {
                Log.w(TAG, "Receiver not registered");
            }
            receiver = null;
        }

        if (manager != null && channel != null) {
            // Удаляем все сервисы
            manager.clearLocalServices(channel, null);
            manager.clearServiceRequests(channel, null);
            manager.stopPeerDiscovery(channel, null);
            manager.removeGroup(channel, null);
        }

        messageSlots.clear();
        pendingMessages.clear();
        pendingAcks.clear();
        serviceRequests.clear();
        deviceCache.clear();

        notifyStatus("Stopped");
    }

    /**
     * Отправить сообщение (broadcast или конкретному устройству)
     */
    public String sendMessage(String message, String targetDeviceId) {
        if (!isRunning) return null;

        // Найти свободный слот
        int freeSlot = findFreeSlot();
        if (freeSlot < 0) {
            // Все слоты заняты - освобождаем самый старый
            freeSlot = releaseOldestSlot();
        }

        String msgId = shortDeviceId + "_" + messageIdCounter.incrementAndGet();

        PendingMessage pending = new PendingMessage(msgId, message, targetDeviceId, freeSlot);
        pendingMessages.put(msgId, pending);

        // Регистрируем слот с сообщением
        registerMessageSlot(freeSlot, msgId, message, targetDeviceId);

        Log.d(TAG, "Sending message " + msgId + " in slot " + freeSlot +
                (targetDeviceId != null ? " to " + targetDeviceId : " (broadcast)"));

        if (listener != null) {
            listener.onMessageSent(msgId, message, targetDeviceId);
        }

        return msgId;
    }

    /**
     * Broadcast сообщение всем
     */
    public String broadcastMessage(String message) {
        return sendMessage(message, null);
    }

    /**
     * Принудительное обновление (новый burst)
     */
    public void forceRefresh() {
        if (!isRunning) return;
        handler.removeCallbacks(discoveryRunnable);
        burstCount = 0;
        initialBurstPhase = true;
        performBurstStep();
    }

    // ==================== GETTERS ====================

    public String getDeviceId() { return deviceId; }
    public String getShortDeviceId() { return shortDeviceId; }
    public String getMacAddress() { return macAddress; }
    public boolean isRunning() { return isRunning; }
    public int getDeviceCount() { return deviceCache.size(); }
    public int getOnlineDeviceCount() {
        int count = 0;
        for (DiscoveredDevice d : deviceCache.values()) {
            if (d.isOnline()) count++;
        }
        return count;
    }

    public List<DiscoveredDevice> getAllDevices() {
        return new ArrayList<>(deviceCache.values());
    }

    public List<DiscoveredDevice> getOnlineDevices() {
        List<DiscoveredDevice> result = new ArrayList<>();
        for (DiscoveredDevice d : deviceCache.values()) {
            if (d.isOnline() && d.hasOurApp) {
                result.add(d);
            }
        }
        return result;
    }

    // ==================== INITIALIZATION ====================

    private void reconnect() {
        Log.d(TAG, "Reconnecting...");
        stop();
        start(listener);
    }

    private void initializeAndStart() {
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() { clearAndSetup(); }
            @Override
            public void onFailure(int r) { clearAndSetup(); }
        });
    }

    private void clearAndSetup() {
        manager.clearLocalServices(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() { clearRequestsAndSetup(); }
            @Override
            public void onFailure(int r) { clearRequestsAndSetup(); }
        });
    }

    private void clearRequestsAndSetup() {
        manager.clearServiceRequests(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                serviceRequests.clear();
                setupDiscovery();
            }
            @Override
            public void onFailure(int r) {
                serviceRequests.clear();
                setupDiscovery();
            }
        });
    }

    private void setupDiscovery() {
        // 1. Регистрируем основной сервис
        registerMainService(() -> {
            // 2. Настраиваем listeners
            setupServiceListeners();
            // 3. Добавляем service requests
            addServiceRequests(() -> {
                // 4. Сразу делаем первый быстрый запрос
                doImmediateDiscovery();
                // 5. Запускаем периодические задачи
                schedulePeriodicTasks();
                // 6. Начинаем агрессивный burst
                handler.postDelayed(this::performBurstStep, INITIAL_BURST_INTERVAL);
            });
        });
    }

    /**
     * Мгновенный первый запрос сразу при старте
     */
    private void doImmediateDiscovery() {
        Log.d(TAG, "Immediate discovery on start");
        manager.discoverPeers(channel, null);
        manager.discoverServices(channel, null);
    }

    // ==================== MAIN SERVICE ====================

    private void registerMainService(Runnable onComplete) {
        Map<String, String> record = buildMainServiceRecord();

        mainServiceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                MAIN_SERVICE_NAME,
                SERVICE_TYPE,
                record
        );

        manager.addLocalService(channel, mainServiceInfo, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, "Main service registered: " + shortDeviceId);
                onComplete.run();
            }
            @Override
            public void onFailure(int reason) {
                Log.w(TAG, "Failed to register main service: " + reason);
                // Retry after delay
                handler.postDelayed(() -> registerMainService(onComplete), 1000);
            }
        });
    }

    private Map<String, String> buildMainServiceRecord() {
        Map<String, String> record = new HashMap<>();
        // Короткие ключи для экономии места (255 байт лимит)
        record.put("id", shortDeviceId);           // device id
        record.put("t", String.valueOf(System.currentTimeMillis() / 1000)); // timestamp
        record.put("hb", String.valueOf(heartbeatSeq.get())); // heartbeat seq
        record.put("v", "2");                       // version

        // Добавляем pending ACKs (до 5 штук за раз)
        String acks = buildAckString();
        if (!acks.isEmpty()) {
            record.put("ack", acks);
        }

        return record;
    }

    private String buildAckString() {
        if (pendingAcks.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        int count = 0;
        synchronized (pendingAcks) {
            Iterator<String> it = pendingAcks.iterator();
            while (it.hasNext() && count < 5) {
                if (sb.length() > 0) sb.append(",");
                sb.append(it.next());
                it.remove();
                count++;
            }
        }
        return sb.toString();
    }

    /**
     * Обновление только основного сервиса (без удаления слотов)
     */
    private void updateMainService() {
        if (!isRunning || mainServiceInfo == null) return;

        heartbeatSeq.incrementAndGet();
        Map<String, String> record = buildMainServiceRecord();

        WifiP2pDnsSdServiceInfo newInfo = WifiP2pDnsSdServiceInfo.newInstance(
                MAIN_SERVICE_NAME,
                SERVICE_TYPE,
                record
        );

        // Удаляем старый, добавляем новый
        manager.removeLocalService(channel, mainServiceInfo, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                manager.addLocalService(channel, newInfo, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        mainServiceInfo = newInfo;
                        Log.d(TAG, "Main service updated, hb=" + heartbeatSeq.get());
                    }
                    @Override
                    public void onFailure(int r) {
                        Log.w(TAG, "Failed to re-add main service: " + r);
                        mainServiceInfo = newInfo; // Assume it worked
                    }
                });
            }
            @Override
            public void onFailure(int r) {
                // Try adding anyway
                manager.addLocalService(channel, newInfo, null);
                mainServiceInfo = newInfo;
            }
        });
    }

    // ==================== MESSAGE SLOTS ====================

    private int findFreeSlot() {
        for (int i = 0; i < MAX_MSG_SLOTS; i++) {
            if (!messageSlots.containsKey(i)) {
                return i;
            }
        }
        return -1;
    }

    private int releaseOldestSlot() {
        int oldestSlot = 0;
        long oldestTime = Long.MAX_VALUE;

        for (Map.Entry<Integer, SlotInfo> entry : messageSlots.entrySet()) {
            if (entry.getValue().createdAt < oldestTime) {
                oldestTime = entry.getValue().createdAt;
                oldestSlot = entry.getKey();
            }
        }

        releaseSlot(oldestSlot);
        return oldestSlot;
    }

    private void releaseSlot(int slotIndex) {
        SlotInfo slot = messageSlots.remove(slotIndex);
        if (slot != null && slot.serviceInfo != null && slot.isRegistered) {
            manager.removeLocalService(channel, slot.serviceInfo, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.d(TAG, "Slot " + slotIndex + " released");
                }
                @Override
                public void onFailure(int r) {
                    Log.w(TAG, "Failed to release slot " + slotIndex);
                }
            });
        }

        // Удаляем pending message для этого слота
        if (slot != null && slot.messageId != null) {
            pendingMessages.remove(slot.messageId);
        }
    }

    private void registerMessageSlot(int slotIndex, String msgId, String message, String targetDeviceId) {
        // Сначала освобождаем слот если занят
        if (messageSlots.containsKey(slotIndex)) {
            releaseSlot(slotIndex);
        }

        Map<String, String> record = new HashMap<>();
        record.put("id", shortDeviceId);
        record.put("mid", msgId);
        record.put("msg", truncateMessage(message, 150)); // Ограничиваем размер
        record.put("s", String.valueOf(slotIndex));
        record.put("t", String.valueOf(System.currentTimeMillis() / 1000));

        if (targetDeviceId != null) {
            record.put("to", targetDeviceId.length() > 8 ? targetDeviceId.substring(0, 8) : targetDeviceId);
        }

        String serviceName = MSG_SLOT_PREFIX + slotIndex;
        WifiP2pDnsSdServiceInfo slotService = WifiP2pDnsSdServiceInfo.newInstance(
                serviceName,
                SERVICE_TYPE,
                record
        );

        SlotInfo slot = new SlotInfo(slotIndex);
        slot.serviceInfo = slotService;
        slot.messageId = msgId;
        slot.targetDeviceId = targetDeviceId;
        slot.isRegistered = false;

        messageSlots.put(slotIndex, slot);

        manager.addLocalService(channel, slotService, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                slot.isRegistered = true;
                Log.d(TAG, "Message slot " + slotIndex + " registered: " + msgId);

                // Trigger immediate discovery so others see it fast
                manager.discoverServices(channel, null);
            }
            @Override
            public void onFailure(int r) {
                Log.w(TAG, "Failed to register slot " + slotIndex + ": " + r);
            }
        });

        // Авто-освобождение слота через timeout
        handler.postDelayed(() -> {
            if (messageSlots.containsKey(slotIndex)) {
                SlotInfo current = messageSlots.get(slotIndex);
                if (current != null && msgId.equals(current.messageId)) {
                    releaseSlot(slotIndex);
                }
            }
        }, SLOT_TIMEOUT);
    }

    private String truncateMessage(String msg, int maxLen) {
        if (msg == null) return "";
        return msg.length() <= maxLen ? msg : msg.substring(0, maxLen);
    }

    // ==================== SERVICE LISTENERS ====================

    private void setupServiceListeners() {
        WifiP2pManager.DnsSdTxtRecordListener txtListener = (fullDomain, record, device) -> {
            if (record == null || device == null) return;

            String senderId = record.get("id");

            // Игнорируем свои записи
            if (shortDeviceId.equals(senderId)) return;

            String serviceName = extractServiceName(fullDomain);

            Log.d(TAG, "TXT from " + device.deviceName + " service=" + serviceName + " data=" + record);

            // Обрабатываем в зависимости от типа сервиса
            if (MAIN_SERVICE_NAME.equals(serviceName)) {
                handleMainServiceRecord(record, device);
            } else if (serviceName != null && serviceName.startsWith(MSG_SLOT_PREFIX)) {
                handleMessageSlotRecord(record, device);
            }
        };

        WifiP2pManager.DnsSdServiceResponseListener serviceListener = (instanceName, regType, device) -> {
            if (regType != null && regType.contains(SERVICE_TYPE)) {
                handleServiceDiscovered(device, instanceName);
            }
        };

        manager.setDnsSdResponseListeners(channel, serviceListener, txtListener);
    }

    private void handleMainServiceRecord(Map<String, String> record, WifiP2pDevice device) {
        String senderId = record.get("id");
        if (senderId == null) return;

        DiscoveredDevice dd = getOrCreateDevice(device.deviceAddress, device);
        dd.deviceId = senderId;
        dd.hasOurApp = true;
        dd.lastSeen = System.currentTimeMillis();
        dd.seenCount++;

        // Heartbeat
        String hbStr = record.get("hb");
        if (hbStr != null) {
            try {
                dd.heartbeatSeq = Long.parseLong(hbStr);
                dd.lastHeartbeat = System.currentTimeMillis();
            } catch (NumberFormatException e) {}
        }

        // Process ACKs
        String acks = record.get("ack");
        if (acks != null && !acks.isEmpty()) {
            processReceivedAcks(acks, dd);
        }

        boolean wasOnline = dd.isOnline();
        notifyDeviceUpdated(dd);

        // Notify online status change
        if (!wasOnline && dd.isOnline() && listener != null) {
            handler.post(() -> listener.onDeviceOnlineStatusChanged(dd, true));
        }
    }

    private void handleMessageSlotRecord(Map<String, String> record, WifiP2pDevice device) {
        String senderId = record.get("id");
        String msgId = record.get("mid");
        String message = record.get("msg");
        String targetId = record.get("to");

        if (senderId == null || msgId == null) return;

        // Проверяем, адресовано ли нам
        if (targetId != null && !targetId.equals(shortDeviceId)) {
            return; // Не нам
        }

        // Проверяем дубликаты
        if (processedMessageIds.contains(msgId)) {
            return;
        }

        // Добавляем в обработанные
        addToProcessedIds(msgId);

        DiscoveredDevice dd = getOrCreateDevice(device.deviceAddress, device);
        dd.deviceId = senderId;
        dd.hasOurApp = true;
        dd.lastSeen = System.currentTimeMillis();
        dd.lastReceivedMsgId = msgId;
        dd.lastReceivedMsg = message;
        dd.lastReceivedTime = System.currentTimeMillis();

        // Добавляем ACK в очередь
        pendingAcks.add(msgId);

        Log.d(TAG, "MESSAGE received: " + msgId + " from " + senderId + ": " + message);

        notifyDeviceUpdated(dd);

        if (listener != null) {
            handler.post(() -> listener.onMessageReceived(dd, msgId, message));
        }
    }

    private void processReceivedAcks(String acks, DiscoveredDevice sender) {
        String[] ackList = acks.split(",");
        for (String ack : ackList) {
            ack = ack.trim();
            if (ack.isEmpty()) continue;

            // Проверяем, наше ли это сообщение (начинается с нашего ID)
            if (ack.startsWith(shortDeviceId + "_")) {
                sender.ackedMessageIds.add(ack);
                sender.lastAckTime = System.currentTimeMillis();

                // Удаляем из pending
                PendingMessage pm = pendingMessages.remove(ack);
                if (pm != null) {
                    releaseSlot(pm.slotIndex);
                    Log.d(TAG, "ACK received for " + ack + " from " + sender.getShortId());

                    if (listener != null) {
                        DiscoveredDevice finalSender = sender;
                        String finalAck = ack;
                        handler.post(() -> listener.onAckReceived(finalSender, finalAck));
                    }
                }
            }
        }
    }

    private void addToProcessedIds(String msgId) {
        processedMessageIds.add(msgId);
        // Ограничиваем размер
        if (processedMessageIds.size() > MAX_PROCESSED_IDS) {
            Iterator<String> it = processedMessageIds.iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    private void handleServiceDiscovered(WifiP2pDevice device, String instanceName) {
        DiscoveredDevice dd = getOrCreateDevice(device.deviceAddress, device);
        dd.lastSeen = System.currentTimeMillis();

        if (instanceName != null &&
                (instanceName.startsWith(MAIN_SERVICE_NAME) || instanceName.startsWith(MSG_SLOT_PREFIX))) {
            dd.hasOurApp = true;
        }

        notifyDeviceUpdated(dd);
    }

    // ==================== SERVICE REQUESTS ====================

    private void addServiceRequests(Runnable onComplete) {
        List<WifiP2pServiceRequest> requests = new ArrayList<>();

        // Запрос конкретно нашего типа сервиса (приоритетный)
        requests.add(WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE));

        // Общий Bonjour запрос
        requests.add(WifiP2pDnsSdServiceRequest.newInstance());

        // Запрос всех типов сервисов
        requests.add(WifiP2pServiceRequest.newInstance(WifiP2pServiceInfo.SERVICE_TYPE_ALL));

        addRequestsSequentially(requests, 0, onComplete);
    }

    private void addRequestsSequentially(List<WifiP2pServiceRequest> requests, int index, Runnable onComplete) {
        if (index >= requests.size()) {
            onComplete.run();
            return;
        }

        WifiP2pServiceRequest req = requests.get(index);
        manager.addServiceRequest(channel, req, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                serviceRequests.add(req);
                addRequestsSequentially(requests, index + 1, onComplete);
            }
            @Override
            public void onFailure(int reason) {
                Log.w(TAG, "Failed to add service request: " + reason);
                addRequestsSequentially(requests, index + 1, onComplete);
            }
        });
    }

    // ==================== BURST DISCOVERY ====================

    private void performBurstStep() {
        if (!isRunning) return;

        burstCount++;
        discoveryInProgress = true;

        long interval;
        int maxCount;

        if (initialBurstPhase) {
            interval = INITIAL_BURST_INTERVAL;
            maxCount = INITIAL_BURST_COUNT;
        } else {
            interval = BURST_INTERVAL;
            maxCount = BURST_COUNT;
        }

        manager.discoverPeers(channel, null);

        manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                Log.d(TAG, (initialBurstPhase ? "Initial " : "") + "Burst " + burstCount + "/" + maxCount);
                discoveryInProgress = false;

                if (burstCount < maxCount) {
                    handler.postDelayed(FastDiscoveryManager.this::performBurstStep, interval);
                } else if (initialBurstPhase) {
                    // Переходим к обычному burst
                    initialBurstPhase = false;
                    burstCount = 0;
                    handler.postDelayed(FastDiscoveryManager.this::performBurstStep, BURST_INTERVAL);
                } else {
                    onBurstComplete();
                }
            }

            @Override
            public void onFailure(int reason) {
                Log.w(TAG, "Burst failed: " + reason);
                discoveryInProgress = false;

                if (burstCount < maxCount) {
                    handler.postDelayed(FastDiscoveryManager.this::performBurstStep, interval * 2);
                } else {
                    onBurstComplete();
                }
            }
        });
    }

    private void onBurstComplete() {
        notifyStatus("Scanning... " + deviceCache.size() + " devices (" + getOnlineDeviceCount() + " online)");
        currentInterval = NORMAL_INTERVAL;
        scheduleNextDiscovery();
    }

    // ==================== PERIODIC TASKS ====================

    private void schedulePeriodicTasks() {
        // Heartbeat/Main service update
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL);

        // ACK batch sending (embedded in heartbeat now)

        // Online status checker
        handler.postDelayed(onlineCheckRunnable, DEVICE_ONLINE_THRESHOLD / 2);
    }

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            updateMainService();
            handler.postDelayed(this, HEARTBEAT_INTERVAL);
        }
    };

    private final Runnable onlineCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;

            long now = System.currentTimeMillis();
            for (DiscoveredDevice dd : deviceCache.values()) {
                boolean wasOnline = dd.lastSeen > now - DEVICE_ONLINE_THRESHOLD - 5000; // With buffer
                boolean isOnline = dd.isOnline();

                if (wasOnline && !isOnline && listener != null) {
                    handler.post(() -> listener.onDeviceOnlineStatusChanged(dd, false));
                }
            }

            cleanupExpiredDevices();
            handler.postDelayed(this, DEVICE_ONLINE_THRESHOLD / 2);
        }
    };

    private void scheduleNextDiscovery() {
        handler.removeCallbacks(discoveryRunnable);
        handler.postDelayed(discoveryRunnable, currentInterval);
    }

    private final Runnable discoveryRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            adaptInterval();
            performDiscovery();
        }
    };

    private void adaptInterval() {
        long timeSinceNewDevice = System.currentTimeMillis() - lastNewDeviceTime;

        if (currentInterval == FAST_INTERVAL && timeSinceNewDevice > FAST_MODE_DURATION) {
            currentInterval = NORMAL_INTERVAL;
            Log.d(TAG, "Switching to normal interval");
            notifyStatus("Scanning... " + deviceCache.size() + " devices");
        }
    }

    private void performDiscovery() {
        discoveryInProgress = true;

        manager.discoverPeers(channel, null);

        manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                discoveryInProgress = false;
                scheduleNextDiscovery();
            }
            @Override
            public void onFailure(int reason) {
                Log.w(TAG, "Discovery failed: " + reason);
                discoveryInProgress = false;
                scheduleNextDiscovery();
            }
        });
    }

    // ==================== DEVICE MANAGEMENT ====================

    private DiscoveredDevice getOrCreateDevice(String address, WifiP2pDevice device) {
        DiscoveredDevice dd = deviceCache.get(address);

        if (dd == null) {
            dd = new DiscoveredDevice();
            dd.address = address;
            dd.name = device.deviceName;
            dd.device = device;
            dd.firstSeen = System.currentTimeMillis();
            dd.lastSeen = System.currentTimeMillis();
            dd.seenCount = 1;

            deviceCache.put(address, dd);

            Log.d(TAG, "NEW device: " + dd.name + " [" + address + "]");
            notifyDeviceFound(dd);
            onNewDeviceFound();
        }

        // Update device reference
        dd.device = device;
        if (device.deviceName != null && !device.deviceName.isEmpty()) {
            dd.name = device.deviceName;
        }

        return dd;
    }

    private void onNewDeviceFound() {
        lastNewDeviceTime = System.currentTimeMillis();

        if (currentInterval != FAST_INTERVAL) {
            currentInterval = FAST_INTERVAL;
            Log.d(TAG, "Switching to fast interval");

            handler.removeCallbacks(discoveryRunnable);
            scheduleNextDiscovery();
        }
    }

    private void cleanupExpiredDevices() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, DiscoveredDevice>> it = deviceCache.entrySet().iterator();

        while (it.hasNext()) {
            Map.Entry<String, DiscoveredDevice> entry = it.next();
            DiscoveredDevice dd = entry.getValue();

            if (now - dd.lastSeen > CACHE_TTL) {
                Log.d(TAG, "LOST: " + dd.name);
                notifyDeviceLost(dd);
                it.remove();
            }
        }
    }

    // ==================== BROADCAST RECEIVER ====================

    private void registerReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        filter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                handleBroadcast(intent);
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    private void handleBroadcast(Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION:
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    notifyError("WiFi P2P is disabled");
                }
                break;

            case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION:
                manager.requestPeers(channel, peers -> {
                    for (WifiP2pDevice device : peers.getDeviceList()) {
                        getOrCreateDevice(device.deviceAddress, device);
                    }
                    // Trigger service discovery to get TXT records
                    if (!peers.getDeviceList().isEmpty() && !discoveryInProgress) {
                        manager.discoverServices(channel, null);
                    }
                });
                break;

            case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
                WifiP2pGroup group = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP);
                if (group != null && group.isGroupOwner()) {
                    Log.w(TAG, "Became GO unexpectedly, removing group");
                    manager.removeGroup(channel, null);
                }
                break;

            case WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION:
                int dState = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1);
                if (dState == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED) {
                    discoveryInProgress = false;
                }
                break;

            case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION:
                WifiP2pDevice thisDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (thisDevice != null) {
                    macAddress = thisDevice.deviceAddress;
                    Log.d(TAG, "This device MAC: " + macAddress);
                }
                break;
        }
    }

    // ==================== HELPERS ====================

    private String extractServiceName(String fullDomain) {
        if (fullDomain == null) return null;
        int firstDot = fullDomain.indexOf('.');
        if (firstDot > 0) {
            return fullDomain.substring(0, firstDot);
        }
        return fullDomain;
    }

    // ==================== NOTIFICATIONS ====================

    private void notifyDeviceFound(DiscoveredDevice d) {
        handler.post(() -> {
            if (listener != null) listener.onDeviceFound(d);
        });
    }

    private void notifyDeviceUpdated(DiscoveredDevice d) {
        handler.post(() -> {
            if (listener != null) listener.onDeviceUpdated(d);
        });
    }

    private void notifyDeviceLost(DiscoveredDevice d) {
        handler.post(() -> {
            if (listener != null) listener.onDeviceLost(d);
        });
    }

    private void notifyStatus(String status) {
        handler.post(() -> {
            if (listener != null) listener.onStatusChanged(status);
        });
    }

    private void notifyError(String message) {
        handler.post(() -> {
            if (listener != null) listener.onError(message);
        });
    }
}