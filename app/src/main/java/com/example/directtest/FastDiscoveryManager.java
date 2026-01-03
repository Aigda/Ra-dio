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
    private static final long SERVICE_RESTART_INTERVAL = 15_000;
    private static final long SLOT_TIMEOUT = 30_000;
    private static final long DEVICE_ONLINE_THRESHOLD = 20_000;
    private static final long CACHE_TTL = 120_000;

    // ==================== STATE ====================
    private final Context context;
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;

    private final String deviceId;
    private final String shortDeviceId;
    private String macAddress = "02:00:00:00:00:00";

    private WifiP2pDnsSdServiceInfo mainServiceInfo;
    private final Map<Integer, SlotInfo> messageSlots = new ConcurrentHashMap<>();
    private final List<WifiP2pServiceRequest> serviceRequests = new ArrayList<>();

    private final Map<String, DiscoveredDevice> deviceCache = new ConcurrentHashMap<>();

    private final Map<String, PendingMessage> pendingMessages = new ConcurrentHashMap<>();
    private final Set<String> pendingAcks = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> processedMessageIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private static final int MAX_PROCESSED_IDS = 100;

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

    private DiscoveryListener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final DiagnosticLogger log = DiagnosticLogger.getInstance();

    // Хранение отправленных ACK для повторной отправки
    private final Map<String, Long> sentAcks = new ConcurrentHashMap<>();
    private static final long ACK_RESEND_DURATION = 30_000; // Повторять ACK 30 секунд

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

        // ==================== HEARTBEAT ====================
        public long heartbeatSeq;
        public long prevHeartbeatSeq;
        public long lastHeartbeatReceived;

        // ==================== SENT MESSAGES ====================
        public static class SentMessage {
            public String messageId;
            public String targetDeviceId;
            public String text;
            public long sentAt;
            public int slotIndex;
            public boolean acknowledged;
            public long ackReceivedAt;

            public SentMessage(String id, String target, String text, int slot) {
                this.messageId = id;
                this.targetDeviceId = target;
                this.text = text;
                this.slotIndex = slot;
                this.sentAt = System.currentTimeMillis();
                this.acknowledged = false;
            }
        }
        public List<SentMessage> sentMessages = new ArrayList<>();

        // ==================== RECEIVED MESSAGES ====================
        public static class ReceivedMessage {
            public String messageId;
            public String text;
            public long receivedAt;

            public ReceivedMessage(String id, String text) {
                this.messageId = id;
                this.text = text;
                this.receivedAt = System.currentTimeMillis();
            }
        }
        public List<ReceivedMessage> receivedMessages = new ArrayList<>();

        // ==================== ACKS ====================
        public Set<String> ackedMessageIds = new HashSet<>();
        public long lastAckTime;
        public String lastAckedMessageId;

        // ==================== SERVICE INFO ====================
        public String lastServiceName;
        public int lastSlotIndex = -1;

        // ==================== LEGACY ====================
        public String lastReceivedMsgId;
        public String lastReceivedMsg;
        public long lastReceivedTime;
        public String lastSentMsgId;

        // ==================== METHODS ====================

        public boolean isOnline() {
            return System.currentTimeMillis() - lastSeen < DEVICE_ONLINE_THRESHOLD;
        }

        public String getShortId() {
            if (deviceId != null && deviceId.length() >= 8) {
                return deviceId.substring(0, 8);
            }
            return address != null ? address.replace(":", "").substring(0, Math.min(12, address.replace(":", "").length())) : "unknown";
        }

        public void addSentMessage(String msgId, String targetId, String text, int slot) {
            SentMessage msg = new SentMessage(msgId, targetId, text, slot);
            sentMessages.add(0, msg);
            if (sentMessages.size() > 10) {
                sentMessages.remove(sentMessages.size() - 1);
            }
            lastSentMsgId = msgId;
            lastSlotIndex = slot;
        }

        public void addReceivedMessage(String msgId, String text) {
            ReceivedMessage msg = new ReceivedMessage(msgId, text);
            receivedMessages.add(0, msg);
            if (receivedMessages.size() > 10) {
                receivedMessages.remove(receivedMessages.size() - 1);
            }
            lastReceivedMsgId = msgId;
            lastReceivedMsg = text;
            lastReceivedTime = System.currentTimeMillis();
        }

        public void markMessageAcked(String msgId) {
            ackedMessageIds.add(msgId);
            lastAckedMessageId = msgId;
            lastAckTime = System.currentTimeMillis();

            for (SentMessage msg : sentMessages) {
                if (msg.messageId.equals(msgId)) {
                    msg.acknowledged = true;
                    msg.ackReceivedAt = System.currentTimeMillis();
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

        public SentMessage getLastSentMessage() {
            return sentMessages.isEmpty() ? null : sentMessages.get(0);
        }

        public ReceivedMessage getLastReceivedMessage() {
            return receivedMessages.isEmpty() ? null : receivedMessages.get(0);
        }

        public int getPendingMessagesCount() {
            int count = 0;
            for (SentMessage msg : sentMessages) {
                if (!msg.acknowledged) count++;
            }
            return count;
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

        log.divider("FastDiscoveryManager INIT");
        log.i("Device ID: " + shortDeviceId);
        log.i("Full ID: " + deviceId);
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

        if (receiver != null) {
            try {
                context.unregisterReceiver(receiver);
                log.i("Receiver unregistered");
            } catch (Exception e) {
                log.w("Receiver not registered: " + e.getMessage());
            }
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
        pendingAcks.clear();
        serviceRequests.clear();
        deviceCache.clear();

        log.success("Stopped");
        notifyStatus("Stopped");
    }

    public String sendMessage(String message, String targetDeviceId) {
        if (!isRunning) return null;

        int freeSlot = findFreeSlot();
        if (freeSlot < 0) freeSlot = releaseOldestSlot();

        String msgId = shortDeviceId + "_" + messageIdCounter.incrementAndGet();
        PendingMessage pending = new PendingMessage(msgId, message, targetDeviceId, freeSlot);
        pendingMessages.put(msgId, pending);

        // Записываем в историю устройства
        if (targetDeviceId != null) {
            for (DiscoveredDevice dd : deviceCache.values()) {
                if (targetDeviceId.equals(dd.deviceId) ||
                        (dd.deviceId != null && dd.deviceId.startsWith(targetDeviceId))) {
                    dd.addSentMessage(msgId, targetDeviceId, message, freeSlot);
                    break;
                }
            }
        } else {
            // Broadcast - добавляем ко всем устройствам с приложением
            for (DiscoveredDevice dd : deviceCache.values()) {
                if (dd.hasOurApp) {
                    dd.addSentMessage(msgId, null, message, freeSlot);
                }
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

    public String broadcastMessage(String message) {
        return sendMessage(message, null);
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
    public String getMacAddress() { return macAddress; }
    public boolean isRunning() { return isRunning; }
    public int getDeviceCount() { return deviceCache.size(); }
    public long getHeartbeatSeq() { return heartbeatSeq.get(); }
    public long getLastHeartbeatSentTime() { return lastHeartbeatSentTime; }
    public int getTxtRecordsReceived() { return txtRecordsReceived.get(); }
    public int getServiceResponsesReceived() { return serviceResponsesReceived.get(); }

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
            if (d.isOnline() && d.hasOurApp) result.add(d);
        }
        return result;
    }

    public String getDiagnosticInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("═══ DEVICE INFO ═══\n");
        sb.append("My ID: ").append(shortDeviceId).append("\n");
        sb.append("MAC: ").append(macAddress).append("\n");
        sb.append("Running: ").append(isRunning).append("\n");
        sb.append("Main service: ").append(mainServiceInfo != null ? "REGISTERED" : "NOT registered").append("\n");
        sb.append("Heartbeat: ").append(heartbeatSeq.get()).append("\n");

        sb.append("\n═══ STATISTICS ═══\n");
        sb.append("TXT records received: ").append(txtRecordsReceived.get()).append("\n");
        sb.append("Service responses: ").append(serviceResponsesReceived.get()).append("\n");
        sb.append("Service requests: ").append(serviceRequests.size()).append("\n");

        sb.append("\n═══ CACHE ═══\n");
        sb.append("Total devices: ").append(deviceCache.size()).append("\n");
        sb.append("Online devices: ").append(getOnlineDeviceCount()).append("\n");

        sb.append("\n═══ DEVICES ═══\n");
        for (DiscoveredDevice dd : deviceCache.values()) {
            sb.append("────────────────────\n");
            sb.append("Name: ").append(dd.name).append("\n");
            sb.append("Address: ").append(dd.address).append("\n");
            sb.append("Device ID: ").append(dd.deviceId != null ? dd.deviceId : "null").append("\n");
            sb.append("hasOurApp: ").append(dd.hasOurApp).append("\n");
            sb.append("isOnline: ").append(dd.isOnline()).append("\n");
            sb.append("seenCount: ").append(dd.seenCount).append("\n");
            sb.append("lastSeen: ").append((System.currentTimeMillis() - dd.lastSeen) / 1000).append("s ago\n");
            sb.append("HB: #").append(dd.heartbeatSeq).append(" ← #").append(dd.prevHeartbeatSeq).append("\n");
            sb.append("Sent msgs: ").append(dd.sentMessages.size()).append("\n");
            sb.append("Recv msgs: ").append(dd.receivedMessages.size()).append("\n");
            sb.append("ACKs: ").append(dd.ackedMessageIds.size()).append("\n");
        }

        return sb.toString();
    }

    // ==================== DEVICE NAME MARKER ====================

    private void setDeviceNameWithMarker() {
        try {
            String markedName = APP_MARKER + shortDeviceId + " " + Build.MODEL;
            if (markedName.length() > 32) markedName = markedName.substring(0, 32);

            log.i("Setting device name: " + markedName);

            java.lang.reflect.Method setDeviceName = manager.getClass().getMethod(
                    "setDeviceName",
                    WifiP2pManager.Channel.class,
                    String.class,
                    WifiP2pManager.ActionListener.class
            );

            String finalName = markedName;
            setDeviceName.invoke(manager, channel, markedName, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    log.success("Device name set: " + finalName);
                }
                @Override
                public void onFailure(int reason) {
                    log.w("Failed to set device name: " + reasonToString(reason));
                }
            });
        } catch (Exception e) {
            log.w("setDeviceName not available: " + e.getMessage());
        }
    }

    // ==================== INITIALIZATION ====================

    private void reconnect() {
        log.w("Reconnecting...");
        stop();
        start(listener);
    }

    private void initializeAndStart() {
        log.i("initializeAndStart");
        manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                log.d("removeGroup: success");
                clearAndSetup();
            }
            @Override public void onFailure(int r) {
                log.d("removeGroup: failed (" + reasonToString(r) + ")");
                clearAndSetup();
            }
        });
    }

    private void clearAndSetup() {
        manager.clearLocalServices(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                log.d("clearLocalServices: success");
                clearRequestsAndSetup();
            }
            @Override public void onFailure(int r) {
                log.d("clearLocalServices: failed");
                clearRequestsAndSetup();
            }
        });
    }

    private void clearRequestsAndSetup() {
        manager.clearServiceRequests(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                log.d("clearServiceRequests: success");
                serviceRequests.clear();
                setupDiscovery();
            }
            @Override public void onFailure(int r) {
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
        log.i("Starting discovery sequence");

        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                log.d("Initial discoverPeers: started");
            }
            @Override
            public void onFailure(int reason) {
                log.e("Initial discoverPeers: FAILED - " + reasonToString(reason));
            }
        });

        handler.postDelayed(() -> {
            manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    log.d("Initial discoverServices: started");
                }
                @Override
                public void onFailure(int reason) {
                    log.e("Initial discoverServices: FAILED - " + reasonToString(reason));
                }
            });
        }, 500);

        schedulePeriodicTasks();
        handler.postDelayed(this::performBurstStep, INITIAL_BURST_INTERVAL);
    }

    // ==================== SERVICE RESTART ====================

    private void restartServiceDiscovery() {
        log.divider("RESTART SERVICE DISCOVERY");

        manager.stopPeerDiscovery(channel, null);

        manager.clearServiceRequests(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                serviceRequests.clear();
                log.d("Service requests cleared");

                setupServiceListeners();

                addServiceRequests(() -> {
                    burstCount = 0;
                    initialBurstPhase = true;

                    handler.postDelayed(() -> {
                        performBurstStep();
                    }, 300);
                });
            }
            @Override
            public void onFailure(int r) {
                log.w("Failed to clear requests: " + reasonToString(r));
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
        log.i("Service name: " + MAIN_SERVICE_NAME);
        log.i("Service type: " + SERVICE_TYPE);
        log.i("Record: " + record.toString());

        mainServiceInfo = WifiP2pDnsSdServiceInfo.newInstance(
                MAIN_SERVICE_NAME,
                SERVICE_TYPE,
                record
        );

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
        record.put("v", "2");

        String acks = buildAckString();
        if (!acks.isEmpty()) {
            record.put("ack", acks);
            log.success("Including ACKs in service: " + acks);  // ← ДОБАВИТЬ
        } else {
            log.d("No pending ACKs to send (queue size: " + pendingAcks.size() + ")");  // ← ДОБАВИТЬ
        }

        return record;
    }



    private String buildAckString() {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        long now = System.currentTimeMillis();

        // 1. Добавляем новые ACK из очереди
        synchronized (pendingAcks) {
            Iterator<String> it = pendingAcks.iterator();
            while (it.hasNext() && count < 3) {
                String ack = it.next();
                if (sb.length() > 0) sb.append(",");
                sb.append(ack);

                // Перемещаем в sentAcks для повторной отправки
                sentAcks.put(ack, now);
                it.remove();
                count++;
            }
        }

        // 2. Добавляем недавно отправленные ACK для надёжности (повторная отправка)
        for (Map.Entry<String, Long> entry : sentAcks.entrySet()) {
            if (now - entry.getValue() > ACK_RESEND_DURATION) {
                // ACK устарел, пропускаем (будет удалён в cleanup)
                continue;
            }

            // Если ещё не в строке и есть место
            if (count < 3 && !sb.toString().contains(entry.getKey())) {
                if (sb.length() > 0) sb.append(",");
                sb.append(entry.getKey());
                count++;
            }
        }

        // 3. Очистка устаревших ACK
        sentAcks.entrySet().removeIf(e -> now - e.getValue() > ACK_RESEND_DURATION);

        if (sb.length() > 0) {
            log.i("ACK string: " + sb.toString() + " (pending: " + pendingAcks.size() + ", sent: " + sentAcks.size() + ")");
        }

        return sb.toString();
    }

    private void updateMainService() {
        if (!isRunning || mainServiceInfo == null) return;

        heartbeatSeq.incrementAndGet();
        lastHeartbeatSentTime = System.currentTimeMillis();
        Map<String, String> record = buildMainServiceRecord();

        log.d("Updating main service, hb=" + heartbeatSeq.get());

        WifiP2pDnsSdServiceInfo newInfo = WifiP2pDnsSdServiceInfo.newInstance(
                MAIN_SERVICE_NAME,
                SERVICE_TYPE,
                record
        );

        manager.removeLocalService(channel, mainServiceInfo, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                manager.addLocalService(channel, newInfo, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        mainServiceInfo = newInfo;
                        log.d("Main service updated OK");
                    }
                    @Override
                    public void onFailure(int r) {
                        mainServiceInfo = newInfo;
                    }
                });
            }
            @Override
            public void onFailure(int r) {
                manager.addLocalService(channel, newInfo, null);
                mainServiceInfo = newInfo;
            }
        });
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
        record.put("msg", truncateMessage(message, 150));
        record.put("s", String.valueOf(slotIndex));
        record.put("t", String.valueOf(System.currentTimeMillis() / 1000));

        if (targetDeviceId != null) {
            record.put("to", targetDeviceId.length() > 8 ? targetDeviceId.substring(0, 8) : targetDeviceId);
        }

        String serviceName = MSG_SLOT_PREFIX + slotIndex;
        WifiP2pDnsSdServiceInfo slotService = WifiP2pDnsSdServiceInfo.newInstance(
                serviceName, SERVICE_TYPE, record);

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
                log.success("Message slot " + slotIndex + " registered: " + msgId);
                manager.discoverServices(channel, null);
            }
            @Override
            public void onFailure(int r) {
                log.error("Failed to register slot " + slotIndex);
            }
        });

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
        log.divider("SETUP LISTENERS");

        WifiP2pManager.DnsSdTxtRecordListener txtListener = (fullDomain, record, device) -> {
            int count = txtRecordsReceived.incrementAndGet();

            log.i("════════════════════════════════");
            log.success("TXT RECORD #" + count);
            log.i("Domain: " + fullDomain);
            log.i("Device: " + (device != null ? device.deviceName + " [" + device.deviceAddress + "]" : "null"));
            log.i("Record: " + (record != null ? record.toString() : "null"));

            if (record == null || device == null) {
                log.w("SKIP: null");
                return;
            }

            String senderId = record.get("id");
            log.i("Sender ID: " + senderId + " | My ID: " + shortDeviceId);

            if (shortDeviceId.equals(senderId)) {
                log.w("SKIP: own record");
                return;
            }

            String serviceName = extractServiceName(fullDomain);
            log.i("Service name: " + serviceName);

            // ✅ ИСПРАВЛЕНИЕ: Используем equalsIgnoreCase для регистронезависимого сравнения
            if (serviceName != null && MAIN_SERVICE_NAME.equalsIgnoreCase(serviceName)) {
                log.success(">>> MAIN SERVICE → hasOurApp=true");
                handleMainServiceRecord(record, device, serviceName);
            } else if (serviceName != null && serviceName.toUpperCase().startsWith(MSG_SLOT_PREFIX.toUpperCase())) {
                log.success(">>> MESSAGE SLOT → hasOurApp=true");
                handleMessageSlotRecord(record, device, serviceName);
            } else {
                log.w("Unknown service: " + serviceName);
            }
        };

        WifiP2pManager.DnsSdServiceResponseListener serviceListener = (instanceName, regType, device) -> {
            int count = serviceResponsesReceived.incrementAndGet();

            log.i("════════════════════════════════");
            log.i("SERVICE RESPONSE #" + count);
            log.i("Instance: " + instanceName);
            log.i("RegType: " + regType);
            log.i("Device: " + (device != null ? device.deviceName + " [" + device.deviceAddress + "]" : "null"));

            if (regType != null && regType.contains(SERVICE_TYPE)) {
                log.success("Our service type matched");
                handleServiceDiscovered(device, instanceName);
            }
        };

        manager.setDnsSdResponseListeners(channel, serviceListener, txtListener);
        log.success("DNS-SD listeners configured");
    }

    private void handleMainServiceRecord(Map<String, String> record, WifiP2pDevice device, String serviceName) {
        String senderId = record.get("id");
        if (senderId == null) return;

        DiscoveredDevice dd = getOrCreateDevice(device.deviceAddress, device);
        boolean wasHasApp = dd.hasOurApp;

        dd.deviceId = senderId;
        dd.hasOurApp = true;
        dd.lastSeen = System.currentTimeMillis();
        dd.seenCount++;
        dd.lastServiceName = serviceName;

        log.success("Device " + dd.name + " hasOurApp: " + wasHasApp + " → TRUE (via TXT)");

        String hbStr = record.get("hb");
        if (hbStr != null) {
            try {
                long newHb = Long.parseLong(hbStr);
                dd.updateHeartbeat(newHb);
            } catch (NumberFormatException e) {}
        }

        String acks = record.get("ack");
        if (acks != null && !acks.isEmpty()) {
            processReceivedAcks(acks, dd);
        }

        notifyDeviceUpdated(dd);
    }

    private void handleMessageSlotRecord(Map<String, String> record, WifiP2pDevice device, String serviceName) {
        String senderId = record.get("id");
        String msgId = record.get("mid");
        String message = record.get("msg");
        String targetId = record.get("to");
        String slotStr = record.get("s");

        if (senderId == null || msgId == null) return;
        if (targetId != null && !targetId.equals(shortDeviceId)) {
            log.d("Message not for us: to=" + targetId + ", my id=" + shortDeviceId);
            return;
        }
        if (processedMessageIds.contains(msgId)) {
            log.d("Message already processed: " + msgId);
            return;
        }

        addToProcessedIds(msgId);

        DiscoveredDevice dd = getOrCreateDevice(device.deviceAddress, device);
        dd.deviceId = senderId;
        dd.hasOurApp = true;
        dd.lastSeen = System.currentTimeMillis();
        dd.lastServiceName = serviceName;

        if (slotStr != null) {
            try {
                dd.lastSlotIndex = Integer.parseInt(slotStr);
            } catch (NumberFormatException e) {}
        }

        dd.addReceivedMessage(msgId, message);

        // ← ДОБАВИТЬ ЛОГИРОВАНИЕ
        pendingAcks.add(msgId);
        log.success("MESSAGE RECEIVED: " + msgId + " from " + senderId);
        log.success("ACK queued for: " + msgId + " (queue size: " + pendingAcks.size() + ")");
        log.i("Content: " + message);

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

            if (ack.startsWith(shortDeviceId + "_")) {
                sender.markMessageAcked(ack);

                PendingMessage pm = pendingMessages.remove(ack);
                if (pm != null) {
                    releaseSlot(pm.slotIndex);
                    log.success("ACK received for " + ack);

                    if (listener != null) {
                        String finalAck = ack;
                        handler.post(() -> listener.onAckReceived(sender, finalAck));
                    }
                }
            }
        }
    }

    private void addToProcessedIds(String msgId) {
        processedMessageIds.add(msgId);
        if (processedMessageIds.size() > MAX_PROCESSED_IDS) {
            Iterator<String> it = processedMessageIds.iterator();
            if (it.hasNext()) { it.next(); it.remove(); }
        }
    }

    private void handleServiceDiscovered(WifiP2pDevice device, String instanceName) {
        DiscoveredDevice dd = getOrCreateDevice(device.deviceAddress, device);
        dd.lastSeen = System.currentTimeMillis();

        // ✅ ИСПРАВЛЕНИЕ: Регистронезависимое сравнение
        if (instanceName != null) {
            String upperInstance = instanceName.toUpperCase();
            if (upperInstance.startsWith(MAIN_SERVICE_NAME.toUpperCase()) ||
                    upperInstance.startsWith(MSG_SLOT_PREFIX.toUpperCase())) {
                if (!dd.hasOurApp) {
                    dd.hasOurApp = true;
                    dd.lastServiceName = instanceName;
                    log.success("Device " + dd.name + " hasOurApp → true (via service name)");
                }
            }
        }
        notifyDeviceUpdated(dd);
    }

    // ==================== SERVICE REQUESTS ====================

    private void addServiceRequests(Runnable onComplete) {
        log.i("Adding service requests...");

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
                log.d("Service request " + (index + 1) + " added");
                addRequestsSequentially(requests, index + 1, onComplete);
            }
            @Override
            public void onFailure(int reason) {
                log.w("Failed to add request " + (index + 1) + ": " + reasonToString(reason));
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

        log.i("BURST step " + burstCount + "/" + maxCount + (initialBurstPhase ? " (initial)" : ""));

        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                log.d("discoverPeers: started");
            }
            @Override
            public void onFailure(int reason) {
                log.e("discoverPeers: FAILED - " + reasonToString(reason));
            }
        });

        handler.postDelayed(() -> {
            manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    log.d("discoverServices: started");
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
                    log.e("discoverServices: FAILED - " + reasonToString(reason));
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
        String status = "Devices: " + deviceCache.size() +
                " | TXT: " + txtRecordsReceived.get() +
                " | SVC: " + serviceResponsesReceived.get();
        log.i("Burst complete. " + status);
        notifyStatus(status);
        currentInterval = NORMAL_INTERVAL;
        scheduleNextDiscovery();
    }

    // ==================== PERIODIC TASKS ====================

    private void schedulePeriodicTasks() {
        log.i("Scheduling periodic tasks");
        handler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL);
        handler.postDelayed(onlineCheckRunnable, DEVICE_ONLINE_THRESHOLD / 2);
        handler.postDelayed(serviceRestartRunnable, SERVICE_RESTART_INTERVAL);
    }

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            updateMainService();
            handler.postDelayed(this, HEARTBEAT_INTERVAL);
        }
    };

    private final Runnable serviceRestartRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;

            if (txtRecordsReceived.get() == 0 && deviceCache.size() > 0) {
                log.w("No TXT records received, restarting service discovery...");
                restartServiceDiscovery();
            }

            handler.postDelayed(this, SERVICE_RESTART_INTERVAL);
        }
    };

    private final Runnable onlineCheckRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;

            long now = System.currentTimeMillis();
            for (DiscoveredDevice dd : deviceCache.values()) {
                boolean wasOnline = dd.lastSeen > now - DEVICE_ONLINE_THRESHOLD - 5000;
                boolean isOnline = dd.isOnline();

                if (wasOnline && !isOnline && listener != null) {
                    log.i("Device went offline: " + dd.name);
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
            log.d("Switching to normal interval");
        }
    }

    private void performDiscovery() {
        discoveryInProgress = true;
        log.d("Periodic discovery...");

        manager.discoverPeers(channel, null);

        handler.postDelayed(() -> {
            manager.discoverServices(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    discoveryInProgress = false;
                    scheduleNextDiscovery();
                }
                @Override
                public void onFailure(int reason) {
                    log.w("Periodic discovery failed: " + reasonToString(reason));
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
                log.success("NEW DEVICE (marker): " + dd.name + " [" + address + "] hasOurApp=TRUE");
            } else {
                log.success("NEW DEVICE: " + dd.name + " [" + address + "] hasOurApp=false");
            }

            deviceCache.put(address, dd);
            notifyDeviceFound(dd);
            onNewDeviceFound();
        } else {
            dd.device = device;
            dd.lastSeen = System.currentTimeMillis();
            dd.seenCount++;

            if (!dd.hasOurApp && device.deviceName != null && device.deviceName.contains(APP_MARKER)) {
                dd.hasOurApp = true;
                String nameWithoutMarker = device.deviceName.replace(APP_MARKER, "");
                String[] parts = nameWithoutMarker.trim().split(" ", 2);
                if (parts.length > 0 && parts[0].length() >= 8) {
                    dd.deviceId = parts[0];
                }
                dd.name = parts.length > 1 ? parts[1] : nameWithoutMarker;
                log.success("DEVICE UPDATED (marker): " + dd.name + " hasOurApp → TRUE");
            } else if (device.deviceName != null && !device.deviceName.isEmpty() && !dd.hasOurApp) {
                dd.name = device.deviceName;
            }
        }

        return dd;
    }

    private void onNewDeviceFound() {
        lastNewDeviceTime = System.currentTimeMillis();
        if (currentInterval != FAST_INTERVAL) {
            currentInterval = FAST_INTERVAL;
            log.d("Switching to fast interval");
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
                log.i("DEVICE LOST: " + dd.name);
                notifyDeviceLost(dd);
                it.remove();
            }
        }
    }

    // ==================== BROADCAST RECEIVER ====================

    private void registerReceiver() {
        log.i("Registering broadcast receiver");

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

        log.success("Broadcast receiver registered");
    }

    private void handleBroadcast(Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION:
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    log.success("WiFi P2P ENABLED");
                } else {
                    log.error("WiFi P2P DISABLED");
                    notifyError("WiFi P2P is disabled");
                }
                break;

            case WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION:
                log.d("PEERS_CHANGED");
                manager.requestPeers(channel, peers -> {
                    log.i("Peers available: " + peers.getDeviceList().size());
                    for (WifiP2pDevice device : peers.getDeviceList()) {
                        log.d("Peer: " + device.deviceName + " [" + device.deviceAddress + "]");
                        getOrCreateDevice(device.deviceAddress, device);
                    }
                    if (!peers.getDeviceList().isEmpty()) {
                        handler.postDelayed(() -> {
                            manager.discoverServices(channel, null);
                        }, 200);
                    }
                });
                break;

            case WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION:
                WifiP2pGroup group = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP);
                if (group != null && group.isGroupOwner()) {
                    log.w("Became GO, removing group");
                    manager.removeGroup(channel, null);
                }
                break;

            case WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION:
                int dState = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1);
                if (dState == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED) {
                    log.d("Discovery STARTED");
                } else if (dState == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED) {
                    log.d("Discovery STOPPED");
                    discoveryInProgress = false;
                }
                break;

            case WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION:
                WifiP2pDevice thisDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (thisDevice != null) {
                    macAddress = thisDevice.deviceAddress;
                    log.i("This device: " + thisDevice.deviceName + " [" + macAddress + "]");
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