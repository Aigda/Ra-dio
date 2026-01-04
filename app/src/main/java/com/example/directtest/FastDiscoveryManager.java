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

import com.example.directtest.sync.DeviceState;
import com.example.directtest.sync.DeviceStateRepository;
import com.example.directtest.sync.SyncManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class FastDiscoveryManager {

    private static final String TAG = "FastDiscovery";

    // ==================== SERVICE NAMES ====================
    public static final String SERVICE_TYPE = "_wfd._tcp";
    private static final String MAIN_SERVICE_NAME = "WFD_Main";
    private static final String MSG_SLOT_PREFIX = "WFD_Msg";
    private static final String ACK_SERVICE_NAME = "WFD_Ack";
    private static final String SYNC_SERVICE_NAME = "WFD_Sync";  // SYNC сервис
    private static final int MAX_MSG_SLOTS = 3;
    private static final String APP_MARKER = "[WFD]";

    // ==================== TIMING CONSTANTS ====================
    private static final long INITIAL_BURST_INTERVAL = 300;
    private static final int INITIAL_BURST_COUNT = 5;
    private static final long BURST_INTERVAL = 600;
    private static final int BURST_COUNT = 8;

    private static final long FAST_INTERVAL = 3_000;
    private static final long NORMAL_INTERVAL = 8_000;
    private static final long FAST_MODE_DURATION = 30_000;

    private static final long HEARTBEAT_INTERVAL = 5_000;
    private static final long ACK_SERVICE_LIFETIME = 15_000;
    private static final long ACK_UPDATE_INTERVAL = 2_000;
    private static final long SLOT_TIMEOUT = 30_000;
    private static final long DEVICE_ONLINE_THRESHOLD = 20_000;
    private static final long CACHE_TTL = 120_000;

    private static final long MAX_MSG_AGE_SEC = 120;

    // SYNC timing
    private static final long SYNC_CHECK_INTERVAL = 10_000;   // Проверка каждые 10 сек
    private static final long SYNC_SERVICE_LIFETIME = 30_000; // SYNC сервис живёт 30 сек

    // ==================== STATE ====================
    private final Context context;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;

    private final String deviceId;
    private final String shortDeviceId;
    private final String sessionId;
    private String macAddress = "02:00:00:00:00:00";

    private WifiP2pDnsSdServiceInfo mainServiceInfo;
    private WifiP2pDnsSdServiceInfo ackServiceInfo;
    private WifiP2pDnsSdServiceInfo syncServiceInfo;  // SYNC сервис
    private final Map<Integer, SlotInfo> messageSlots = new ConcurrentHashMap<>();
    private final List<WifiP2pServiceRequest> serviceRequests = new ArrayList<>();

    private final Map<String, DiscoveredDevice> deviceCache = new ConcurrentHashMap<>();

    private final Map<String, PendingMessage> pendingMessages = new ConcurrentHashMap<>();
    private final Set<String> processedMessageIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final int MAX_PROCESSED_IDS = 100;

    // ACK tracking
    private final Map<String, Long> activeIncomingMessages = new ConcurrentHashMap<>();
    private final Set<String> pendingAcksToSend = Collections.synchronizedSet(new LinkedHashSet<>());

    // SYNC system
    private DeviceStateRepository stateRepository;
    private SyncManager syncManager;

    private final AtomicLong heartbeatSeq = new AtomicLong(0);
    private final AtomicInteger messageIdCounter = new AtomicInteger(0);
    private final AtomicInteger txtRecordsReceived = new AtomicInteger(0);
    private final AtomicInteger serviceResponsesReceived = new AtomicInteger(0);

    private volatile boolean isRunning = false;
    private volatile boolean discoveryInProgress = false;
    private int burstCount = 0;
    private boolean initialBurstPhase = true;
    private long lastNewDeviceTime = 0;
    private long currentInterval = NORMAL_INTERVAL;
    private long lastHeartbeatSentTime = 0;

    // Дедупликация TXT записей
    private final Map<String, Long> recentTxtRecords = new ConcurrentHashMap<>();
    private static final long TXT_DEDUP_WINDOW_MS = 2000;  // Игнорировать дубликаты в течение 2 сек
    private static final int MAX_RECENT_TXT_RECORDS = 50;

    // Трекинг обработанных ACK (чтобы не обрабатывать повторно)
    private final Set<String> processedAcks = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final int MAX_PROCESSED_ACKS = 50;

    private DiscoveryListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DiagnosticLogger log = DiagnosticLogger.getInstance();

    // ==================== INNER CLASSES ====================

    private static class SlotInfo {
        int slotIndex;
        WifiP2pDnsSdServiceInfo serviceInfo;
        String messageId;
        String targetDeviceId;
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

        PendingMessage(String id, String msg, String target, int slot) {
            this.messageId = id;
            this.message = msg;
            this.targetDeviceId = target;
            this.slotIndex = slot;
            this.sentAt = System.currentTimeMillis();
        }
    }

    // ==================== DISCOVERED DEVICE ====================

    public static class DiscoveredDevice {
        public String address;
        public String deviceId;
        public String name;
        public WifiP2pDevice device;
        public boolean hasOurApp;
        public long firstSeen;
        public long lastSeen;
        public int seenCount;

        // Heartbeat
        public long heartbeatSeq;
        public long prevHeartbeatSeq;
        public long lastHeartbeatReceived;

        // Session ID
        public String sessionId;

        // Online tracking for sync
        private boolean wasOnlineLastCheck;

        // Sent Messages
        public static class SentMessage {
            public String messageId;
            public String text;
            public long sentAt;
            public int slotIndex;
            public boolean acknowledged;
            public long ackReceivedAt;
            public List<String> ackBatch = new ArrayList<>();

            public SentMessage(String id, String text, int slot) {
                this.messageId = id;
                this.text = text;
                this.slotIndex = slot;
                this.sentAt = System.currentTimeMillis();
            }
        }
        public List<SentMessage> sentMessages = new ArrayList<>();

        // Received Messages
        public static class ReceivedMessage {
            public String messageId;
            public String text;
            public long receivedAt;
            public boolean ackSent;
            public long ackSentAt;
            public int ackSendCount;
            public boolean ackConfirmed;

            public ReceivedMessage(String id, String text) {
                this.messageId = id;
                this.text = text;
                this.receivedAt = System.currentTimeMillis();
            }
        }
        public List<ReceivedMessage> receivedMessages = new ArrayList<>();

        // Service info
        public String lastServiceName;
        public int lastSlotIndex = -1;
        public Set<String> currentVisibleMsgIds = new HashSet<>();

        public boolean isOnline() {
            return System.currentTimeMillis() - lastSeen < DEVICE_ONLINE_THRESHOLD;
        }

        public boolean checkAndUpdateOnlineTransition() {
            boolean currentlyOnline = isOnline();
            boolean justCameOnline = !wasOnlineLastCheck && currentlyOnline;
            wasOnlineLastCheck = currentlyOnline;
            return justCameOnline;
        }

        public String getShortId() {
            if (deviceId != null && deviceId.length() >= 8) {
                return deviceId.substring(0, 8);
            }
            return address != null ? address.replace(":", "").substring(0, Math.min(12, address.replace(":", "").length())) : "unknown";
        }

        public void addSentMessage(String msgId, String text, int slot) {
            SentMessage msg = new SentMessage(msgId, text, slot);
            sentMessages.add(0, msg);
            if (sentMessages.size() > 10) sentMessages.remove(sentMessages.size() - 1);
            lastSlotIndex = slot;
        }

        public void addReceivedMessage(String msgId, String text) {
            for (ReceivedMessage rm : receivedMessages) {
                if (rm.messageId.equals(msgId)) return;
            }
            ReceivedMessage msg = new ReceivedMessage(msgId, text);
            receivedMessages.add(0, msg);
            if (receivedMessages.size() > 10) receivedMessages.remove(receivedMessages.size() - 1);
        }

        public void markSentMessageAcked(String msgId, List<String> batchAcks) {
            for (SentMessage msg : sentMessages) {
                if (msg.messageId.equals(msgId)) {
                    msg.acknowledged = true;
                    msg.ackReceivedAt = System.currentTimeMillis();
                    if (batchAcks != null) {
                        for (String ack : batchAcks) {
                            if (!ack.equals(msgId) && !msg.ackBatch.contains(ack)) {
                                msg.ackBatch.add(ack);
                            }
                        }
                    }
                    break;
                }
            }
        }

        public void markReceivedMessageAckSent(String msgId) {
            for (ReceivedMessage msg : receivedMessages) {
                if (msg.messageId.equals(msgId)) {
                    msg.ackSent = true;
                    msg.ackSentAt = System.currentTimeMillis();
                    msg.ackSendCount++;
                    break;
                }
            }
        }

        public void markReceivedMessageAckConfirmed(String msgId) {
            for (ReceivedMessage msg : receivedMessages) {
                if (msg.messageId.equals(msgId)) {
                    msg.ackConfirmed = true;
                    break;
                }
            }
        }

        public void updateHeartbeat(long newSeq) {
            if (newSeq != heartbeatSeq) {
                prevHeartbeatSeq = heartbeatSeq;
                heartbeatSeq = newSeq;
                lastHeartbeatReceived = System.currentTimeMillis();
            }
        }

        public List<SentMessage> getLastSentMessages(int count) {
            List<SentMessage> result = new ArrayList<>();
            for (int i = 0; i < Math.min(count, sentMessages.size()); i++) {
                result.add(sentMessages.get(i));
            }
            return result;
        }

        public List<ReceivedMessage> getLastReceivedMessages(int count) {
            List<ReceivedMessage> result = new ArrayList<>();
            for (int i = 0; i < Math.min(count, receivedMessages.size()); i++) {
                result.add(receivedMessages.get(i));
            }
            return result;
        }

        public List<String> getPendingAckMessageIds() {
            List<String> result = new ArrayList<>();
            for (ReceivedMessage msg : receivedMessages) {
                if (!msg.ackConfirmed && currentVisibleMsgIds.contains(msg.messageId)) {
                    result.add(msg.messageId);
                }
            }
            return result;
        }

        public void clearHistory() {
            sentMessages.clear();
            receivedMessages.clear();
            currentVisibleMsgIds.clear();
            heartbeatSeq = 0;
            prevHeartbeatSeq = 0;
            lastHeartbeatReceived = 0;
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
        this.sessionId = UUID.randomUUID().toString().substring(0, 8);

        // Инициализация SYNC системы
        stateRepository = new DeviceStateRepository(context);
        stateRepository.initialize(deviceId, sessionId);

        syncManager = new SyncManager(stateRepository);
        syncManager.setCallback(new SyncManager.SyncCallback() {
            @Override
            public void onPublishSync(String targetDeviceId, List<String> mySentIds, List<String> myRecvIds) {
                registerSyncService(targetDeviceId, mySentIds, myRecvIds);
            }

            @Override
            public void onResendMessage(String targetDeviceId, String msgId, String text) {
                resendMessage(targetDeviceId, msgId, text);
            }

            @Override
            public void onSyncComplete(String deviceId) {
                log.success("SYNC complete with " + deviceId);
            }
        });

        log.divider("FastDiscoveryManager INIT");
        log.i("Device ID: " + shortDeviceId);
        log.i("Session ID: " + sessionId);
        log.i("Loaded " + stateRepository.getAll().size() + " saved devices");
        log.i("Android: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        log.i("Model: " + Build.MODEL);
    }

    private String generateDeviceId() {
        String androidId = android.provider.Settings.Secure.getString(
                context.getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID
        );
        if (androidId == null || androidId.isEmpty()) {
            androidId = "emu_" + Build.MODEL + "_" + Build.FINGERPRINT.hashCode();
        }
        return UUID.nameUUIDFromBytes(androidId.getBytes()).toString().replace("-", "");
    }

    // ==================== PUBLIC API ====================

    public void start(DiscoveryListener listener) {
        log.divider("START");
        this.listener = listener;

        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            log.error("WiFi P2P not supported!");
            notifyError("WiFi P2P not supported");
            return;
        }
        log.success("WifiP2pManager obtained");

        channel = manager.initialize(context, Looper.getMainLooper(), () -> {
            log.w("Channel disconnected!");
            if (isRunning) {
                handler.postDelayed(this::reconnect, 2000);
            }
        });
        log.success("Channel initialized");

        isRunning = true;
        burstCount = 0;
        initialBurstPhase = true;
        txtRecordsReceived.set(0);
        serviceResponsesReceived.set(0);

        setDeviceNameWithMarker();
        registerReceiver();
        initializeAndStart();

        notifyStatus("Starting...");
    }
    public void stop() {
        log.divider("STOP");
        isRunning = false;
        handler.removeCallbacksAndMessages(null);

        // Принудительное сохранение состояния
        if (stateRepository != null) {
            stateRepository.saveNow();  // Немедленное сохранение
            log.i("State saved");
        }

        if (receiver != null) {
            try { context.unregisterReceiver(receiver); } catch (Exception e) {}
            receiver = null;
        }

        if (manager != null && channel != null) {
            manager.clearLocalServices(channel, null);
            manager.clearServiceRequests(channel, null);
            manager.stopPeerDiscovery(channel, null);
            manager.removeGroup(channel, null);
        }

        messageSlots.clear();
        pendingMessages.clear();
        serviceRequests.clear();
        deviceCache.clear();
        activeIncomingMessages.clear();
        pendingAcksToSend.clear();
        processedMessageIds.clear();
        recentTxtRecords.clear();  // Очищаем кэш дедупликации
        processedAcks.clear();     // Очищаем обработанные ACK

        notifyStatus("Stopped");
    }
    public void clearAll() {
        log.divider("CLEAR ALL");

        handler.removeCallbacksAndMessages(null);

        for (SlotInfo slot : messageSlots.values()) {
            if (slot.serviceInfo != null && slot.isRegistered) {
                manager.removeLocalService(channel, slot.serviceInfo, null);
            }
        }
        messageSlots.clear();

        if (ackServiceInfo != null) {
            manager.removeLocalService(channel, ackServiceInfo, null);
            ackServiceInfo = null;
        }

        if (syncServiceInfo != null) {
            manager.removeLocalService(channel, syncServiceInfo, null);
            syncServiceInfo = null;
        }

        pendingMessages.clear();
        activeIncomingMessages.clear();
        pendingAcksToSend.clear();
        processedMessageIds.clear();
        recentTxtRecords.clear();  // Очищаем кэш дедупликации
        processedAcks.clear();     // Очищаем обработанные ACK

        for (DiscoveredDevice dd : deviceCache.values()) {
            dd.clearHistory();
        }

        messageIdCounter.set(0);
        txtRecordsReceived.set(0);
        serviceResponsesReceived.set(0);
        heartbeatSeq.set(0);

        // Очищаем состояния в репозитории
        if (stateRepository != null) {
            for (DeviceState state : stateRepository.getAll().values()) {
                state.clearMessages();
            }
            stateRepository.saveNow();  // Немедленное сохранение
        }

        if (syncManager != null) {
            syncManager.clearAllPending();
        }

        DiagnosticLogger.getInstance().clear();

        log.success("All cleared");
        log.divider("FRESH START");

        schedulePeriodicTasks();
        notifyStatus("Cleared. Devices: " + deviceCache.size());
    }

    public String sendMessage(String message, String targetDeviceId) {
        if (!isRunning) return null;

        int freeSlot = findFreeSlot();
        if (freeSlot < 0) freeSlot = releaseOldestSlot();

        String msgId = shortDeviceId + "_" + messageIdCounter.incrementAndGet();
        PendingMessage pending = new PendingMessage(msgId, message, targetDeviceId, freeSlot);
        pendingMessages.put(msgId, pending);

        // Сохранение в репозиторий для SYNC
        if (targetDeviceId != null) {
            DeviceState state = stateRepository.getOrCreate(targetDeviceId);
            state.addSentMessage(msgId, message);
            stateRepository.save();
            log.d("Saved to state repository: " + msgId + " -> " + targetDeviceId);
        }

        // Записываем в историю устройства (для UI)
        for (DiscoveredDevice dd : deviceCache.values()) {
            if (targetDeviceId != null) {
                if (targetDeviceId.equals(dd.deviceId) ||
                        (dd.deviceId != null && dd.deviceId.startsWith(targetDeviceId))) {
                    dd.addSentMessage(msgId, message, freeSlot);
                    break;
                }
            } else if (dd.hasOurApp) {
                dd.addSentMessage(msgId, message, freeSlot);
            }
        }

        registerMessageSlot(freeSlot, msgId, message, targetDeviceId);

        log.i("SEND MESSAGE: " + msgId + " slot=" + freeSlot +
                (targetDeviceId != null ? " to=" + targetDeviceId : " (broadcast)"));

        if (listener != null) {
            listener.onMessageSent(msgId, message, targetDeviceId);
        }
        return msgId;
    }

    public void forceRefresh() {
        if (!isRunning) return;
        log.divider("FORCE REFRESH");
        handler.removeCallbacks(discoveryRunnable);
        restartServiceDiscovery();
    }

    // ==================== GETTERS ====================

    public String getDeviceId() { return deviceId; }
    public String getShortDeviceId() { return shortDeviceId; }
    public String getSessionId() { return sessionId; }
    public String getMacAddress() { return macAddress; }
    public boolean isRunning() { return isRunning; }
    public int getDeviceCount() { return deviceCache.size(); }
    public long getHeartbeatSeq() { return heartbeatSeq.get(); }
    public long getLastHeartbeatSentTime() { return lastHeartbeatSentTime; }
    public int getTxtRecordsReceived() { return txtRecordsReceived.get(); }
    public int getServiceResponsesReceived() { return serviceResponsesReceived.get(); }
    public int getPendingAcksCount() { return pendingAcksToSend.size(); }

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

    public DeviceStateRepository getStateRepository() {
        return stateRepository;
    }

    public String getDiagnosticInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══ DEVICE INFO ═══\n");
        sb.append("My ID: ").append(shortDeviceId).append("\n");
        sb.append("Session: ").append(sessionId).append("\n");
        sb.append("MAC: ").append(macAddress).append("\n");
        sb.append("Heartbeat: ").append(heartbeatSeq.get()).append("\n");
        sb.append("TXT received: ").append(txtRecordsReceived.get()).append("\n");
        sb.append("Pending ACKs: ").append(pendingAcksToSend.size()).append("\n");
        sb.append("Active incoming: ").append(activeIncomingMessages.size()).append("\n");
        sb.append("Saved devices: ").append(stateRepository.getAll().size()).append("\n");
        sb.append("\n═══ DEVICES ═══\n");
        for (DiscoveredDevice dd : deviceCache.values()) {
            sb.append("• ").append(dd.name).append(" [").append(dd.getShortId()).append("]\n");
            sb.append("  Session: ").append(dd.sessionId).append("\n");
            sb.append("  Visible msgs: ").append(dd.currentVisibleMsgIds).append("\n");
            sb.append("  Pending ACKs: ").append(dd.getPendingAckMessageIds()).append("\n");

            // Добавляем инфо из репозитория
            DeviceState state = stateRepository.get(dd.deviceId);
            if (state != null) {
                sb.append("  [Repo] Sent: ").append(state.getSentMessageIds()).append("\n");
                sb.append("  [Repo] Recv: ").append(state.getRecvMessageIds()).append("\n");
            }
        }
        return sb.toString();
    }

    // ==================== DEVICE NAME MARKER ====================

    private void setDeviceNameWithMarker() {
        try {
            String markedName = APP_MARKER + shortDeviceId + " " + Build.MODEL;
            if (markedName.length() > 32) markedName = markedName.substring(0, 32);

            java.lang.reflect.Method setDeviceName = manager.getClass().getMethod(
                    "setDeviceName", WifiP2pManager.Channel.class, String.class, WifiP2pManager.ActionListener.class);

            String finalName = markedName;
            setDeviceName.invoke(manager, channel, markedName, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { log.success("Device name set: " + finalName); }
                @Override public void onFailure(int reason) { log.w("Failed to set device name"); }
            });
        } catch (Exception e) {
            log.w("setDeviceName not available");
        }
    }

    // ==================== INITIALIZATION ====================

    private void reconnect() {
        log.w("Reconnecting...");
        stop();
        start(listener);
    }

    private void initializeAndStart() {
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { clearAndSetup(); }
            @Override public void onFailure(int r) { clearAndSetup(); }
        });
    }

    private void clearAndSetup() {
        manager.clearLocalServices(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { clearRequestsAndSetup(); }
            @Override public void onFailure(int r) { clearRequestsAndSetup(); }
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
        log.divider("SETUP DISCOVERY");
        setupServiceListeners();
        registerMainService(() -> {
            addServiceRequests(() -> {
                startDiscoverySequence();
            });
        });
    }

    private void startDiscoverySequence() {
        manager.discoverPeers(channel, null);
        handler.postDelayed(() -> manager.discoverServices(channel, null), 500);
        schedulePeriodicTasks();
        handler.postDelayed(this::performBurstStep, INITIAL_BURST_INTERVAL);
    }

    private void restartServiceDiscovery() {
        manager.stopPeerDiscovery(channel, null);
        manager.clearServiceRequests(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                serviceRequests.clear();
                setupServiceListeners();
                addServiceRequests(() -> {
                    burstCount = 0;
                    initialBurstPhase = true;
                    handler.postDelayed(() -> performBurstStep(), 300);
                });
            }
            @Override
            public void onFailure(int r) {
                serviceRequests.clear();
                addServiceRequests(() -> {
                    burstCount = 0;
                    performBurstStep();
                });
            }
        });
    }

    // ==================== MAIN SERVICE ====================

    private void registerMainService(Runnable onComplete) {
        Map<String, String> record = buildMainServiceRecord();

        log.divider("REGISTER MAIN SERVICE");
        log.i("Record: " + record.toString());

        mainServiceInfo = WifiP2pDnsSdServiceInfo.newInstance(MAIN_SERVICE_NAME, SERVICE_TYPE, record);

        manager.addLocalService(channel, mainServiceInfo, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                log.success("MAIN SERVICE REGISTERED: " + shortDeviceId);
                onComplete.run();
            }
            @Override
            public void onFailure(int reason) {
                log.error("FAILED to register main service: " + reasonToString(reason));
                handler.postDelayed(() -> registerMainService(onComplete), 1000);
            }
        });
    }

    private Map<String, String> buildMainServiceRecord() {
        Map<String, String> record = new HashMap<>();
        record.put("id", shortDeviceId);
        record.put("t", String.valueOf(System.currentTimeMillis() / 1000));
        record.put("hb", String.valueOf(heartbeatSeq.get()));
        record.put("v", "4");  // Версия протокола

        record.put("sid", sessionId);

        String acks = buildAckString();
        if (!acks.isEmpty()) {
            record.put("ack", acks);
        }

        return record;
    }

    private String buildAckString() {
        StringBuilder sb = new StringBuilder();
        int count = 0;

        for (DiscoveredDevice dd : deviceCache.values()) {
            List<String> deviceAcks = dd.getPendingAckMessageIds();
            for (String msgId : deviceAcks) {
                if (count >= 5) break;
                if (sb.length() > 0) sb.append(",");
                sb.append(msgId);
                dd.markReceivedMessageAckSent(msgId);
                pendingAcksToSend.add(msgId);
                count++;
            }
            if (count >= 5) break;
        }

        return sb.toString();
    }

    private void updateMainService() {
        if (!isRunning || mainServiceInfo == null) return;

        heartbeatSeq.incrementAndGet();
        lastHeartbeatSentTime = System.currentTimeMillis();
        Map<String, String> record = buildMainServiceRecord();

        String acks = record.get("ack");
        if (acks != null && !acks.isEmpty()) {
            log.success("Including ACKs in HB: " + acks);
        }
        log.d("Updating main service, hb=" + heartbeatSeq.get());

        WifiP2pDnsSdServiceInfo newInfo = WifiP2pDnsSdServiceInfo.newInstance(
                MAIN_SERVICE_NAME, SERVICE_TYPE, record);

        manager.removeLocalService(channel, mainServiceInfo, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                manager.addLocalService(channel, newInfo, new WifiP2pManager.ActionListener() {
                    @Override public void onSuccess() {
                        mainServiceInfo = newInfo;
                        log.d("Main service updated OK");
                    }
                    @Override public void onFailure(int r) { mainServiceInfo = newInfo; }
                });
            }
            @Override
            public void onFailure(int r) {
                manager.addLocalService(channel, newInfo, null);
                mainServiceInfo = newInfo;
            }
        });
    }

    // ==================== ACK SERVICE ====================

    private void updateAckService() {
        if (!isRunning) return;

        Set<String> allAcks = new HashSet<>();
        for (DiscoveredDevice dd : deviceCache.values()) {
            allAcks.addAll(dd.getPendingAckMessageIds());
        }

        if (allAcks.isEmpty()) {
            if (ackServiceInfo != null) {
                manager.removeLocalService(channel, ackServiceInfo, null);
                ackServiceInfo = null;
                log.d("ACK service removed (no pending)");
            }
            return;
        }

        Map<String, String> record = new HashMap<>();
        record.put("id", shortDeviceId);
        record.put("t", String.valueOf(System.currentTimeMillis() / 1000));
        record.put("sid", sessionId);

        StringBuilder ackSb = new StringBuilder();
        int count = 0;
        for (String ack : allAcks) {
            if (count >= 5) break;
            if (ackSb.length() > 0) ackSb.append(",");
            ackSb.append(ack);
            count++;
        }
        record.put("ack", ackSb.toString());

        log.success("ACK Service update: " + ackSb.toString());

        WifiP2pDnsSdServiceInfo newAckService = WifiP2pDnsSdServiceInfo.newInstance(
                ACK_SERVICE_NAME, SERVICE_TYPE, record);

        if (ackServiceInfo != null) {
            manager.removeLocalService(channel, ackServiceInfo, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    manager.addLocalService(channel, newAckService, new WifiP2pManager.ActionListener() {
                        @Override public void onSuccess() {
                            ackServiceInfo = newAckService;
                            log.d("ACK service updated OK");
                        }
                        @Override public void onFailure(int r) {
                            ackServiceInfo = newAckService;
                        }
                    });
                }
                @Override
                public void onFailure(int r) {
                    manager.addLocalService(channel, newAckService, null);
                    ackServiceInfo = newAckService;
                }
            });
        } else {
            manager.addLocalService(channel, newAckService, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    ackServiceInfo = newAckService;
                    log.success("ACK service created: " + ackSb.toString());
                }
                @Override public void onFailure(int r) {
                    log.w("Failed to create ACK service");
                }
            });
        }
    }

    // ==================== SYNC SERVICE ====================

    private void registerSyncService(String targetDeviceId, List<String> mySentIds, List<String> myRecvIds) {
        String shortTargetId = targetDeviceId.length() > 8
                ? targetDeviceId.substring(0, 8)
                : targetDeviceId;

        Map<String, String> record = new HashMap<>();
        record.put("id", shortDeviceId);
        record.put("to", shortTargetId);
        record.put("sent", SyncManager.formatIdList(mySentIds));  // что я отправлял им
        record.put("recv", SyncManager.formatIdList(myRecvIds));  // что я получал от них
        record.put("t", String.valueOf(System.currentTimeMillis() / 1000));
        record.put("sid", sessionId);

        log.i("Publishing SYNC to " + shortTargetId +
                " | sent=" + mySentIds + " | recv=" + myRecvIds);

        WifiP2pDnsSdServiceInfo newSyncService = WifiP2pDnsSdServiceInfo.newInstance(
                SYNC_SERVICE_NAME, SERVICE_TYPE, record);

        // Удаляем предыдущий sync сервис
        if (syncServiceInfo != null) {
            manager.removeLocalService(channel, syncServiceInfo, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    addSyncService(newSyncService);
                }
                @Override
                public void onFailure(int r) {
                    addSyncService(newSyncService);
                }
            });
        } else {
            addSyncService(newSyncService);
        }
    }

    private void addSyncService(WifiP2pDnsSdServiceInfo service) {
        manager.addLocalService(channel, service, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                syncServiceInfo = service;
                log.success("SYNC service registered");

                // Удаляем через SYNC_SERVICE_LIFETIME
                handler.postDelayed(() -> {
                    if (syncServiceInfo == service) {
                        manager.removeLocalService(channel, syncServiceInfo, null);
                        syncServiceInfo = null;
                        log.d("SYNC service removed (timeout)");
                    }
                }, SYNC_SERVICE_LIFETIME);
            }
            @Override
            public void onFailure(int r) {
                log.w("Failed to register SYNC service: " + reasonToString(r));
            }
        });
    }

    private void handleSyncServiceRecord(Map<String, String> record, WifiP2pDevice device) {
        String senderId = record.get("id");
        String targetId = record.get("to");
        String theirSent = record.get("sent");
        String theirRecv = record.get("recv");
        String sid = record.get("sid");

        if (senderId == null || shortDeviceId.equals(senderId)) return;

        // Это SYNC для нас?
        if (targetId == null ||
                (!targetId.equalsIgnoreCase(shortDeviceId) &&
                        !shortDeviceId.startsWith(targetId))) {
            return;
        }

        log.i("SYNC received from " + senderId +
                " | theirSent=" + theirSent + " | theirRecv=" + theirRecv);

        // Проверяем session ID
        DeviceState state = stateRepository.get(senderId);
        if (state != null && sid != null) {
            if (state.lastSessionId != null && !sid.equals(state.lastSessionId)) {
                log.w("SYNC from old session, ignoring");
                return;
            }
        }

        List<String> theirSentIds = SyncManager.parseIdList(theirSent);
        List<String> theirRecvIds = SyncManager.parseIdList(theirRecv);

        syncManager.processIncomingSync(senderId, theirSentIds, theirRecvIds);
    }

    private void resendMessage(String targetDeviceId, String msgId, String text) {
        int slot = findFreeSlot();
        if (slot < 0) slot = releaseOldestSlot();

        log.i("RESENDING " + msgId + " to " + targetDeviceId + " slot=" + slot);
        registerMessageSlot(slot, msgId, text, targetDeviceId);
    }

    // ==================== MESSAGE SLOTS ====================

    private int findFreeSlot() {
        for (int i = 0; i < MAX_MSG_SLOTS; i++) {
            if (!messageSlots.containsKey(i)) return i;
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
            manager.removeLocalService(channel, slot.serviceInfo, null);
            log.d("Slot " + slotIndex + " released: " + slot.messageId);
        }
        if (slot != null && slot.messageId != null) {
            pendingMessages.remove(slot.messageId);
        }
    }

    private void registerMessageSlot(int slotIndex, String msgId, String message, String targetDeviceId) {
        if (messageSlots.containsKey(slotIndex)) releaseSlot(slotIndex);

        Map<String, String> record = new HashMap<>();
        record.put("id", shortDeviceId);
        record.put("mid", msgId);
        record.put("msg", truncateMessage(message, 100));
        record.put("s", String.valueOf(slotIndex));
        record.put("t", String.valueOf(System.currentTimeMillis() / 1000));

        record.put("sid", sessionId);

        if (targetDeviceId != null) {
            record.put("to", targetDeviceId.length() > 8 ? targetDeviceId.substring(0, 8) : targetDeviceId);
        }

        // Добавляем pending ACK в слот сообщения для дублирования
        String acks = buildAckString();
        if (!acks.isEmpty()) {
            record.put("ack", acks);
        }

        String serviceName = MSG_SLOT_PREFIX + slotIndex;
        WifiP2pDnsSdServiceInfo slotService = WifiP2pDnsSdServiceInfo.newInstance(serviceName, SERVICE_TYPE, record);

        SlotInfo slot = new SlotInfo(slotIndex);
        slot.serviceInfo = slotService;
        slot.messageId = msgId;
        slot.targetDeviceId = targetDeviceId;

        messageSlots.put(slotIndex, slot);

        manager.addLocalService(channel, slotService, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                slot.isRegistered = true;
                log.success("Message slot " + slotIndex + " registered: " + msgId);
                manager.discoverServices(channel, null);
            }
            @Override
            public void onFailure(int r) {
                log.error("Failed to register slot " + slotIndex);
            }
        });

        // Авто-освобождение
        handler.postDelayed(() -> {
            SlotInfo current = messageSlots.get(slotIndex);
            if (current != null && msgId.equals(current.messageId)) {
                boolean acked = false;
                for (DiscoveredDevice dd : deviceCache.values()) {
                    for (DiscoveredDevice.SentMessage sm : dd.sentMessages) {
                        if (sm.messageId.equals(msgId) && sm.acknowledged) {
                            acked = true;
                            break;
                        }
                    }
                    if (acked) break;
                }

                if (acked) {
                    releaseSlot(slotIndex);
                } else {
                    log.w("Slot " + slotIndex + " extended - no ACK for " + msgId);
                    handler.postDelayed(() -> releaseSlot(slotIndex), SLOT_TIMEOUT);
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
        log.divider("SETUP LISTENERS");

        WifiP2pManager.DnsSdTxtRecordListener txtListener = (fullDomain, record, device) -> {
            if (record == null || device == null) return;

            String senderId = record.get("id");
            if (shortDeviceId.equals(senderId)) return;

            String serviceName = extractServiceName(fullDomain);

            // ДЕДУПЛИКАЦИЯ: проверяем не обрабатывали ли мы эту запись недавно
            String dedupeKey = buildTxtDedupeKey(senderId, serviceName, record);
            if (isDuplicateTxt(dedupeKey)) {
                return;  // Пропускаем дубликат
            }

            int count = txtRecordsReceived.incrementAndGet();
            log.i("TXT #" + count + " | " + serviceName + " | from " + senderId);

            // Обрабатываем ACK из любого сервиса
            String acks = record.get("ack");
            if (acks != null && !acks.isEmpty()) {
                log.i("Found ACKs in " + serviceName + ": " + acks);
                DiscoveredDevice dd = getOrCreateDevice(device.deviceAddress, device);
                processReceivedAcks(acks, dd);
            }

            if (serviceName != null && MAIN_SERVICE_NAME.equalsIgnoreCase(serviceName)) {
                handleMainServiceRecord(record, device, serviceName);
            } else if (serviceName != null && serviceName.toUpperCase().startsWith(MSG_SLOT_PREFIX.toUpperCase())) {
                handleMessageSlotRecord(record, device, serviceName);
            } else if (serviceName != null && ACK_SERVICE_NAME.equalsIgnoreCase(serviceName)) {
                handleAckServiceRecord(record, device);
            } else if (serviceName != null && SYNC_SERVICE_NAME.equalsIgnoreCase(serviceName)) {
                handleSyncServiceRecord(record, device);
            }
        };

        WifiP2pManager.DnsSdServiceResponseListener serviceListener = (instanceName, regType, device) -> {
            serviceResponsesReceived.incrementAndGet();
            if (regType != null && regType.contains(SERVICE_TYPE)) {
                handleServiceDiscovered(device, instanceName);
            }
        };

        manager.setDnsSdResponseListeners(channel, serviceListener, txtListener);
        log.success("DNS-SD listeners configured");
    }

    // ==================== TXT DEDUPLICATION ====================

    /**
     * Построить ключ для дедупликации TXT записи
     */
    private String buildTxtDedupeKey(String senderId, String serviceName, Map<String, String> record) {
        StringBuilder sb = new StringBuilder();
        sb.append(senderId).append("|");
        sb.append(serviceName).append("|");

        // Добавляем ключевые поля которые определяют уникальность
        String msgId = record.get("mid");
        if (msgId != null) {
            sb.append("mid=").append(msgId).append("|");
        }

        String ack = record.get("ack");
        if (ack != null) {
            sb.append("ack=").append(ack).append("|");
        }

        String hb = record.get("hb");
        if (hb != null) {
            sb.append("hb=").append(hb).append("|");
        }

        String sent = record.get("sent");
        if (sent != null) {
            sb.append("sent=").append(sent).append("|");
        }

        String recv = record.get("recv");
        if (recv != null) {
            sb.append("recv=").append(recv);
        }

        return sb.toString();
    }

    /**
     * Проверить, является ли TXT запись дубликатом
     */
    private boolean isDuplicateTxt(String dedupeKey) {
        long now = System.currentTimeMillis();

        // Очищаем старые записи
        recentTxtRecords.entrySet().removeIf(entry ->
                now - entry.getValue() > TXT_DEDUP_WINDOW_MS);

        // Ограничиваем размер
        if (recentTxtRecords.size() >= MAX_RECENT_TXT_RECORDS) {
            // Удаляем самую старую
            String oldestKey = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, Long> entry : recentTxtRecords.entrySet()) {
                if (entry.getValue() < oldestTime) {
                    oldestTime = entry.getValue();
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey != null) {
                recentTxtRecords.remove(oldestKey);
            }
        }

        // Проверяем дубликат
        Long lastSeen = recentTxtRecords.get(dedupeKey);
        if (lastSeen != null && now - lastSeen < TXT_DEDUP_WINDOW_MS) {
            return true;  // Дубликат
        }

        // Запоминаем
        recentTxtRecords.put(dedupeKey, now);
        return false;
    }

    private void handleMainServiceRecord(Map<String, String> record, WifiP2pDevice device, String serviceName) {
        String senderId = record.get("id");
        if (senderId == null) return;

        DiscoveredDevice dd = getOrCreateDevice(device.deviceAddress, device);

        // Проверяем переход online ДО обновления lastSeen
        boolean justCameOnline = dd.checkAndUpdateOnlineTransition();

        dd.deviceId = senderId;
        dd.hasOurApp = true;
        dd.lastSeen = System.currentTimeMillis();
        dd.seenCount++;
        dd.lastServiceName = serviceName;

        String hbStr = record.get("hb");
        if (hbStr != null) {
            try { dd.updateHeartbeat(Long.parseLong(hbStr)); } catch (NumberFormatException e) {}
        }

        String sid = record.get("sid");
        if (sid != null && !sid.isEmpty()) {
            dd.sessionId = sid;
        }

        // Обновляем репозиторий
        DeviceState state = stateRepository.getOrCreate(senderId);
        state.name = dd.name;
        state.address = dd.address;
        if (sid != null) {
            state.lastSessionId = sid;
        }

        // Если устройство только что стало online - запускаем sync
        if (justCameOnline && dd.deviceId != null) {
            log.i("Device " + dd.getShortId() + " came online, triggering sync");
            syncManager.onDeviceBecameOnline(dd.deviceId);
        }

        notifyDeviceUpdated(dd);
    }

    private void handleMessageSlotRecord(Map<String, String> record, WifiP2pDevice device, String serviceName) {
        String senderId = record.get("id");
        String msgId = record.get("mid");
        String message = record.get("msg");
        String targetId = record.get("to");
        String slotStr = record.get("s");
        String sid = record.get("sid");
        String tStr = record.get("t");

        if (senderId == null || msgId == null) return;
        if (targetId != null && !targetId.equals(shortDeviceId)) return;

        DiscoveredDevice dd = getOrCreateDevice(device.deviceAddress, device);
        dd.deviceId = senderId;
        dd.hasOurApp = true;
        dd.lastSeen = System.currentTimeMillis();
        dd.lastServiceName = serviceName;

        if (slotStr != null) {
            try { dd.lastSlotIndex = Integer.parseInt(slotStr); } catch (NumberFormatException e) {}
        }

        // TTL фильтрация
        if (tStr != null) {
            try {
                long msgTsSec = Long.parseLong(tStr);
                long nowSec = System.currentTimeMillis() / 1000;
                long ageSec = nowSec - msgTsSec;
                if (ageSec > MAX_MSG_AGE_SEC) {
                    log.w("Ignoring old message slot " + msgId +
                            " from " + senderId + " age=" + ageSec + "s");
                    return;
                }
            } catch (NumberFormatException e) {}
        }

        // Session ID фильтрация
        if (sid != null) {
            if (dd.sessionId == null) {
                dd.sessionId = sid;
            } else if (!sid.equals(dd.sessionId)) {
                log.w("Ignoring stale message slot " + msgId +
                        " from " + senderId + " sid=" + sid +
                        " != currentSid=" + dd.sessionId);
                return;
            }
        } else {
            if (dd.sessionId != null) {
                log.w("Ignoring message slot " + msgId +
                        " from " + senderId + " without sid; deviceSid=" + dd.sessionId);
                return;
            }
        }

        // Обновляем список видимых сообщений
        dd.currentVisibleMsgIds.add(msgId);
        activeIncomingMessages.put(msgId, System.currentTimeMillis());

        // Добавляем сообщение если ещё не обработано
        if (!processedMessageIds.contains(msgId)) {
            processedMessageIds.add(msgId);
            if (processedMessageIds.size() > MAX_PROCESSED_IDS) {
                Iterator<String> it = processedMessageIds.iterator();
                if (it.hasNext()) { it.next(); it.remove(); }
            }

            dd.addReceivedMessage(msgId, message);

            // Сохранение в репозиторий для SYNC
            DeviceState state = stateRepository.getOrCreate(senderId);
            state.addRecvMessage(msgId, message);
            state.name = dd.name;
            state.address = dd.address;
            if (sid != null) {
                state.lastSessionId = sid;
            }
            stateRepository.save();

            log.success("MESSAGE RECEIVED: " + msgId + " from " + senderId);
            log.i("Content: " + message);

            if (listener != null) {
                handler.post(() -> listener.onMessageReceived(dd, msgId, message));
            }
        }

        notifyDeviceUpdated(dd);
    }

    private void handleAckServiceRecord(Map<String, String> record, WifiP2pDevice device) {
        String senderId = record.get("id");
        String acks = record.get("ack");
        String sid = record.get("sid");

        if (senderId == null || shortDeviceId.equals(senderId)) return;

        DiscoveredDevice dd = getOrCreateDevice(device.deviceAddress, device);
        dd.deviceId = senderId;
        dd.hasOurApp = true;
        dd.lastSeen = System.currentTimeMillis();

        // Session ID фильтрация
        if (sid != null) {
            if (dd.sessionId == null) {
                dd.sessionId = sid;
            } else if (!sid.equals(dd.sessionId)) {
                log.w("Ignoring ACK service from " + senderId +
                        " sid=" + sid + " != currentSid=" + dd.sessionId);
                return;
            }
        } else {
            if (dd.sessionId != null) {
                log.w("Ignoring ACK service from " + senderId +
                        " without sid; deviceSid=" + dd.sessionId);
                return;
            }
        }

        log.success("ACK SERVICE received from " + senderId + ": " + acks);

        if (acks != null && !acks.isEmpty()) {
            processReceivedAcks(acks, dd);
        }
    }
    private void processReceivedAcks(String acks, DiscoveredDevice sender) {
        String[] ackList = acks.split(",");
        List<String> ackBatch = new ArrayList<>();
        for (String ack : ackList) {
            ack = ack.trim();
            if (!ack.isEmpty()) ackBatch.add(ack);
        }

        for (String ack : ackBatch) {
            if (ack.startsWith(shortDeviceId + "_")) {

                // ДЕДУПЛИКАЦИЯ: проверяем не обработан ли уже этот ACK
                if (processedAcks.contains(ack)) {
                    continue;  // Уже обработано
                }

                // Проверяем есть ли pending message
                PendingMessage pm = pendingMessages.get(ack);
                if (pm == null) {
                    // Уже обработано ранее (слот освобождён)
                    continue;
                }

                // Помечаем как обработанный
                processedAcks.add(ack);
                if (processedAcks.size() > MAX_PROCESSED_ACKS) {
                    Iterator<String> it = processedAcks.iterator();
                    if (it.hasNext()) { it.next(); it.remove(); }
                }

                log.success("ACK received for " + ack + " from " + sender.getShortId());

                sender.markSentMessageAcked(ack, ackBatch);

                // Помечаем в репозитории
                if (sender.deviceId != null) {
                    DeviceState state = stateRepository.get(sender.deviceId);
                    if (state != null) {
                        state.markAcked(ack);
                        stateRepository.save();  // Теперь с debounce
                    }
                }

                pendingMessages.remove(ack);
                releaseSlot(pm.slotIndex);

                if (listener != null) {
                    String finalAck = ack;
                    handler.post(() -> listener.onAckReceived(sender, finalAck));
                }
            }
        }
    }

    private void handleServiceDiscovered(WifiP2pDevice device, String instanceName) {
        DiscoveredDevice dd = getOrCreateDevice(device.deviceAddress, device);
        dd.lastSeen = System.currentTimeMillis();

        if (instanceName != null) {
            String upper = instanceName.toUpperCase();
            if (upper.startsWith(MAIN_SERVICE_NAME.toUpperCase()) ||
                    upper.startsWith(MSG_SLOT_PREFIX.toUpperCase()) ||
                    upper.startsWith(ACK_SERVICE_NAME.toUpperCase()) ||
                    upper.startsWith(SYNC_SERVICE_NAME.toUpperCase())) {
                dd.hasOurApp = true;
            }
        }
        notifyDeviceUpdated(dd);
    }

    // ==================== VISIBILITY CHECK ====================

    private void checkMessageVisibility() {
        long now = System.currentTimeMillis();
        long timeout = 10_000;

        for (DiscoveredDevice dd : deviceCache.values()) {
            Set<String> toRemove = new HashSet<>();

            for (String msgId : dd.currentVisibleMsgIds) {
                Long lastSeen = activeIncomingMessages.get(msgId);
                if (lastSeen == null || now - lastSeen > timeout) {
                    toRemove.add(msgId);
                    dd.markReceivedMessageAckConfirmed(msgId);
                    pendingAcksToSend.remove(msgId);
                    log.success("ACK confirmed delivered for: " + msgId);
                }
            }

            dd.currentVisibleMsgIds.removeAll(toRemove);
            for (String msgId : toRemove) {
                activeIncomingMessages.remove(msgId);
            }
        }
    }

    // ==================== SERVICE REQUESTS ====================

    private void addServiceRequests(Runnable onComplete) {
        List<WifiP2pServiceRequest> requests = new ArrayList<>();
        requests.add(WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE));
        requests.add(WifiP2pDnsSdServiceRequest.newInstance());
        requests.add(WifiP2pServiceRequest.newInstance(WifiP2pServiceInfo.SERVICE_TYPE_ALL));

        addRequestsSequentially(requests, 0, onComplete);
    }

    private void addRequestsSequentially(List<WifiP2pServiceRequest> requests, int index, Runnable onComplete) {
        if (index >= requests.size()) {
            log.success("All service requests added: " + serviceRequests.size());
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
                addRequestsSequentially(requests, index + 1, onComplete);
            }
        });
    }

    // ==================== BURST DISCOVERY ====================

    private void performBurstStep() {
        if (!isRunning) return;

        burstCount++;
        discoveryInProgress = true;

        long interval = initialBurstPhase ? INITIAL_BURST_INTERVAL : BURST_INTERVAL;
        int maxCount = initialBurstPhase ? INITIAL_BURST_COUNT : BURST_COUNT;

        log.i("BURST step " + burstCount + "/" + maxCount);

        manager.discoverPeers(channel, null);

        handler.postDelayed(() -> {
            manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    discoveryInProgress = false;
                    if (burstCount < maxCount) {
                        handler.postDelayed(FastDiscoveryManager.this::performBurstStep, interval);
                    } else if (initialBurstPhase) {
                        initialBurstPhase = false;
                        burstCount = 0;
                        handler.postDelayed(FastDiscoveryManager.this::performBurstStep, BURST_INTERVAL);
                    } else {
                        onBurstComplete();
                    }
                }
                @Override
                public void onFailure(int reason) {
                    discoveryInProgress = false;
                    if (burstCount < maxCount) {
                        handler.postDelayed(FastDiscoveryManager.this::performBurstStep, interval * 2);
                    } else {
                        onBurstComplete();
                    }
                }
            });
        }, 100);
    }

    private void onBurstComplete() {
        notifyStatus("Devices: " + deviceCache.size() + " | TXT: " + txtRecordsReceived.get());
        currentInterval = NORMAL_INTERVAL;
        scheduleNextDiscovery();
    }

    // ==================== PERIODIC TASKS ====================

    private void schedulePeriodicTasks() {
        handler.removeCallbacks(heartbeatRunnable);
        handler.removeCallbacks(ackUpdateRunnable);
        handler.removeCallbacks(visibilityCheckRunnable);
        handler.removeCallbacks(onlineCheckRunnable);
        handler.removeCallbacks(syncCheckRunnable);

        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL);
        handler.postDelayed(ackUpdateRunnable, ACK_UPDATE_INTERVAL);
        handler.postDelayed(visibilityCheckRunnable, 5000);
        handler.postDelayed(onlineCheckRunnable, DEVICE_ONLINE_THRESHOLD / 2);
        handler.postDelayed(syncCheckRunnable, SYNC_CHECK_INTERVAL);
    }

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            updateMainService();
            handler.postDelayed(this, HEARTBEAT_INTERVAL);
        }
    };

    private final Runnable ackUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            updateAckService();
            handler.postDelayed(this, ACK_UPDATE_INTERVAL);
        }
    };

    private final Runnable visibilityCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            checkMessageVisibility();
            handler.postDelayed(this, 5000);
        }
    };

    private final Runnable onlineCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            cleanupExpiredDevices();
            handler.postDelayed(this, DEVICE_ONLINE_THRESHOLD / 2);
        }
    };

    private final Runnable syncCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            syncManager.checkSyncNeeded();
            handler.postDelayed(this, SYNC_CHECK_INTERVAL);
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
            performDiscovery();
        }
    };

    private void performDiscovery() {
        discoveryInProgress = true;
        manager.discoverPeers(channel, null);
        handler.postDelayed(() -> {
            manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() {
                    discoveryInProgress = false;
                    scheduleNextDiscovery();
                }
                @Override public void onFailure(int reason) {
                    discoveryInProgress = false;
                    scheduleNextDiscovery();
                }
            });
        }, 100);
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

            if (device.deviceName != null && device.deviceName.contains(APP_MARKER)) {
                dd.hasOurApp = true;
                String nameWithoutMarker = device.deviceName.replace(APP_MARKER, "");
                String[] parts = nameWithoutMarker.trim().split(" ", 2);
                if (parts.length > 0 && parts[0].length() >= 8) {
                    dd.deviceId = parts[0];
                }
                dd.name = parts.length > 1 ? parts[1] : nameWithoutMarker;
            }

            deviceCache.put(address, dd);
            notifyDeviceFound(dd);
            onNewDeviceFound();
        } else {
            dd.device = device;
            dd.lastSeen = System.currentTimeMillis();
            dd.seenCount++;
        }

        return dd;
    }

    private void onNewDeviceFound() {
        lastNewDeviceTime = System.currentTimeMillis();
        if (currentInterval != FAST_INTERVAL) {
            currentInterval = FAST_INTERVAL;
            handler.removeCallbacks(discoveryRunnable);
            scheduleNextDiscovery();
        }
    }

    private void cleanupExpiredDevices() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, DiscoveredDevice>> it = deviceCache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, DiscoveredDevice> entry = it.next();
            if (now - entry.getValue().lastSeen > CACHE_TTL) {
                notifyDeviceLost(entry.getValue());
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
                    if (!peers.getDeviceList().isEmpty()) {
                        handler.postDelayed(() -> manager.discoverServices(channel, null), 200);
                    }
                });
                break;

            case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
                WifiP2pGroup group = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP);
                if (group != null && group.isGroupOwner()) {
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
                }
                break;
        }
    }

    // ==================== HELPERS ====================

    private String extractServiceName(String fullDomain) {
        if (fullDomain == null) return null;
        int firstDot = fullDomain.indexOf('.');
        if (firstDot > 0) return fullDomain.substring(0, firstDot);
        return fullDomain;
    }

    private String reasonToString(int reason) {
        switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED: return "P2P_UNSUPPORTED";
            case WifiP2pManager.ERROR: return "ERROR";
            case WifiP2pManager.BUSY: return "BUSY";
            default: return "UNKNOWN(" + reason + ")";
        }
    }

    // ==================== NOTIFICATIONS ====================

    private void notifyDeviceFound(DiscoveredDevice d) {
        handler.post(() -> { if (listener != null) listener.onDeviceFound(d); });
    }

    private void notifyDeviceUpdated(DiscoveredDevice d) {
        handler.post(() -> { if (listener != null) listener.onDeviceUpdated(d); });
    }

    private void notifyDeviceLost(DiscoveredDevice d) {
        handler.post(() -> { if (listener != null) listener.onDeviceLost(d); });
    }

    private void notifyStatus(String status) {
        handler.post(() -> { if (listener != null) listener.onStatusChanged(status); });
    }

    private void notifyError(String message) {
        log.error(message);
        handler.post(() -> { if (listener != null) listener.onError(message); });
    }
}