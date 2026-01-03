package com.example.directtest;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import com.example.directtest.R;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 1001;

    private FastDiscoveryManager discoveryManager;
    private DeviceAdapter adapter;
    private final List<FastDiscoveryManager.DiscoveredDevice> devices = new ArrayList<>();

    private TextView tvStatus;
    private Button btnSendBroadcast;
    private Button btnRefresh;
    private Button btnLog;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (adapter != null) {
                adapter.updateManagerInfo(discoveryManager);
                adapter.notifyDataSetChanged();
            }
            updateStatusBar();
            uiHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        btnSendBroadcast = findViewById(R.id.btn_broadcast);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnLog = findViewById(R.id.btn_log);

        RecyclerView recyclerView = findViewById(R.id.recycler_devices);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DeviceAdapter(devices, this::onDeviceClick);
        recyclerView.setAdapter(adapter);

        btnSendBroadcast.setOnClickListener(v -> {
            if (discoveryManager != null) {
                String text = "Broadcast #" + System.currentTimeMillis() % 10000;
                String msgId = discoveryManager.broadcastMessage(text);
                Toast.makeText(this, "Broadcast: " + msgId, Toast.LENGTH_SHORT).show();
            }
        });

        btnRefresh.setOnClickListener(v -> {
            if (discoveryManager != null) {
                discoveryManager.forceRefresh();
                Toast.makeText(this, "Обновление...", Toast.LENGTH_SHORT).show();
            }
        });

        btnLog.setOnClickListener(v -> {
            Intent intent = new Intent(this, DiagnosticActivity.class);
            startActivity(intent);
        });

        checkAndRequestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        uiHandler.post(uiUpdateRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(uiUpdateRunnable);
    }

    private void updateStatusBar() {
        if (discoveryManager == null) return;

        int total = devices.size();
        int online = 0;
        int withApp = 0;

        for (FastDiscoveryManager.DiscoveredDevice d : devices) {
            if (d.isOnline()) online++;
            if (d.hasOurApp) withApp++;
        }

        long hbSeq = discoveryManager.getHeartbeatSeq();
        long hbAgo = (System.currentTimeMillis() - discoveryManager.getLastHeartbeatSentTime()) / 1000;

        String status = String.format(Locale.getDefault(),
                "Dev:%d On:%d App:%d | HB#%d (%ds) | TXT:%d SVC:%d",
                total, online, withApp,
                hbSeq, hbAgo,
                discoveryManager.getTxtRecordsReceived(),
                discoveryManager.getServiceResponsesReceived());

        tvStatus.setText(status);
    }

    // ==================== PERMISSIONS ====================

    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }

        if (permissionsNeeded.isEmpty()) {
            startDiscovery();
        } else {
            tvStatus.setText("Требуются разрешения...");
            ActivityCompat.requestPermissions(this,
                    permissionsNeeded.toArray(new String[0]),
                    PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = grantResults.length > 0;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            if (allGranted) {
                startDiscovery();
            } else {
                tvStatus.setText("❌ Разрешения не выданы");
            }
        }
    }

    // ==================== DISCOVERY ====================

    private void startDiscovery() {
        tvStatus.setText("Запуск...");

        try {
            discoveryManager = new FastDiscoveryManager(this);

            discoveryManager.start(new FastDiscoveryManager.DiscoveryListener() {
                @Override
                public void onDeviceFound(FastDiscoveryManager.DiscoveredDevice device) {
                    runOnUiThread(() -> {
                        updateOrAddDevice(device);
                    });
                }

                @Override
                public void onDeviceUpdated(FastDiscoveryManager.DiscoveredDevice device) {
                    runOnUiThread(() -> {
                        updateOrAddDevice(device);
                    });
                }

                @Override
                public void onDeviceLost(FastDiscoveryManager.DiscoveredDevice device) {
                    runOnUiThread(() -> {
                        devices.remove(device);
                        adapter.notifyDataSetChanged();
                    });
                }

                @Override
                public void onDeviceOnlineStatusChanged(FastDiscoveryManager.DiscoveredDevice device, boolean isOnline) {
                    runOnUiThread(() -> adapter.notifyDataSetChanged());
                }

                @Override
                public void onStatusChanged(String status) {
                    // Статус обновляется в uiUpdateRunnable
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this, "Ошибка: " + message, Toast.LENGTH_SHORT).show()
                    );
                }

                @Override
                public void onMessageSent(String messageId, String message, String targetDeviceId) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "→ " + messageId, Toast.LENGTH_SHORT).show();
                        adapter.notifyDataSetChanged();
                    });
                }

                @Override
                public void onMessageReceived(FastDiscoveryManager.DiscoveredDevice device,
                                              String messageId, String message) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                                "← " + device.getShortId() + ": " + message,
                                Toast.LENGTH_LONG).show();
                        adapter.notifyDataSetChanged();
                    });
                }

                @Override
                public void onAckReceived(FastDiscoveryManager.DiscoveredDevice device, String ackedMessageId) {
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this,
                                "✓ ACK: " + ackedMessageId, Toast.LENGTH_SHORT).show();
                        adapter.notifyDataSetChanged();
                    });
                }
            });

        } catch (Exception e) {
            tvStatus.setText("❌ Ошибка: " + e.getMessage());
        }
    }

    private void updateOrAddDevice(FastDiscoveryManager.DiscoveredDevice device) {
        int index = -1;
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).address.equals(device.address)) {
                index = i;
                break;
            }
        }
        if (index >= 0) {
            devices.set(index, device);
        } else {
            devices.add(device);
        }
        adapter.notifyDataSetChanged();
    }

    private void onDeviceClick(FastDiscoveryManager.DiscoveredDevice device) {
        if (discoveryManager == null) return;

        if (!device.hasOurApp) {
            Toast.makeText(this, "Устройство без приложения", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!device.isOnline()) {
            Toast.makeText(this, "Устройство оффлайн", Toast.LENGTH_SHORT).show();
            return;
        }

        String text = "Hello @" + System.currentTimeMillis() % 10000;
        String msgId = discoveryManager.sendMessage(text, device.deviceId);
        Toast.makeText(this, "→ " + device.getShortId() + ": " + msgId, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        if (discoveryManager != null) {
            discoveryManager.stop();
        }
    }

    // ==================== ADAPTER ====================

    static class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {

        private final List<FastDiscoveryManager.DiscoveredDevice> list;
        private final OnDeviceClickListener listener;
        private long myHeartbeatSeq = 0;
        private long myLastHbSentTime = 0;

        interface OnDeviceClickListener {
            void onClick(FastDiscoveryManager.DiscoveredDevice device);
        }

        DeviceAdapter(List<FastDiscoveryManager.DiscoveredDevice> list, OnDeviceClickListener l) {
            this.list = list;
            this.listener = l;
        }

        public void updateManagerInfo(FastDiscoveryManager manager) {
            if (manager != null) {
                myHeartbeatSeq = manager.getHeartbeatSeq();
                myLastHbSentTime = manager.getLastHeartbeatSentTime();
            }
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_device, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder h, int position) {
            FastDiscoveryManager.DiscoveredDevice dev = list.get(position);
            long now = System.currentTimeMillis();

            // === Заголовок ===
            String displayName = dev.name != null && !dev.name.isEmpty() ? dev.name : "Unknown";
            h.tvDeviceName.setText(displayName);

            if (dev.isOnline()) {
                h.tvStatusIndicator.setText("● ONLINE");
                h.tvStatusIndicator.setTextColor(0xFF4CAF50);
                h.tvDeviceName.setTextColor(0xFF2E7D32);
            } else {
                h.tvStatusIndicator.setText("○ OFFLINE");
                h.tvStatusIndicator.setTextColor(0xFF9E9E9E);
                h.tvDeviceName.setTextColor(0xFF757575);
            }

            // === ID и адрес ===
            String deviceId = dev.deviceId != null ? dev.deviceId : "unknown";
            String shortId = deviceId.length() > 8 ? deviceId.substring(0, 8) : deviceId;
            h.tvDeviceId.setText(String.format("ID: %s | MAC: %s", shortId, dev.address));

            // === Статус приложения ===
            String appStatus = dev.hasOurApp ? "✅ App" : "❌ No App";
            long lastSeenAgo = (now - dev.lastSeen) / 1000;
            h.tvAppStatus.setText(String.format(Locale.getDefault(),
                    "%s | Seen: %dx | %ds ago",
                    appStatus, dev.seenCount, lastSeenAgo));

            // === Heartbeat отправленный (наш) ===
            if (myLastHbSentTime > 0) {
                long hbSentAgo = (now - myLastHbSentTime) / 1000;
                h.tvHeartbeatSent.setText(String.format(Locale.getDefault(),
                        "↑ My HB#%d sent %ds ago", myHeartbeatSeq, hbSentAgo));
            } else {
                h.tvHeartbeatSent.setText("↑ My HB: not sent yet");
            }

            // === Heartbeat полученный (от устройства) ===
            if (dev.lastHeartbeatReceived > 0) {
                long hbRecvAgo = (now - dev.lastHeartbeatReceived) / 1000;
                h.tvHeartbeatReceived.setText(String.format(Locale.getDefault(),
                        "↓ HB#%d ← #%d (%ds ago)",
                        dev.heartbeatSeq, dev.prevHeartbeatSeq, hbRecvAgo));

                // Цвет в зависимости от свежести
                if (hbRecvAgo < 10) {
                    h.tvHeartbeatReceived.setTextColor(0xFF4CAF50); // зелёный
                } else if (hbRecvAgo < 30) {
                    h.tvHeartbeatReceived.setTextColor(0xFFFF9800); // оранжевый
                } else {
                    h.tvHeartbeatReceived.setTextColor(0xFF757575); // серый
                }
            } else {
                h.tvHeartbeatReceived.setText("↓ No HB received");
                h.tvHeartbeatReceived.setTextColor(0xFF757575);
            }

            // === Отправленные сообщения ===
            FastDiscoveryManager.DiscoveredDevice.SentMessage lastSent = dev.getLastSentMessage();
            if (lastSent != null) {
                long sentAgo = (now - lastSent.sentAt) / 1000;
                String ackIcon = lastSent.acknowledged ? "✓" : "⏳";
                String targetStr = lastSent.targetDeviceId != null ?
                        "→" + lastSent.targetDeviceId.substring(0, Math.min(8, lastSent.targetDeviceId.length())) :
                        "→ALL";

                h.tvMessagesSent.setText(String.format(Locale.getDefault(),
                        "%s [%s] %s \"%s\" (%ds) S:%d",
                        ackIcon,
                        lastSent.messageId,
                        targetStr,
                        truncate(lastSent.text, 15),
                        sentAgo,
                        lastSent.slotIndex));

                // Цвет в зависимости от ACK
                if (lastSent.acknowledged) {
                    h.tvMessagesSent.setTextColor(0xFF4CAF50);
                } else if (sentAgo > 10) {
                    h.tvMessagesSent.setTextColor(0xFFFF5722); // красный - долго без ACK
                } else {
                    h.tvMessagesSent.setTextColor(0xFFFF9800); // оранжевый - ожидание
                }

                // Показываем количество сообщений
                int pending = dev.getPendingMessagesCount();
                int total = dev.sentMessages.size();
                if (total > 1) {
                    h.tvMessagesSent.append(String.format(" [%d/%d]", pending, total));
                }
            } else {
                h.tvMessagesSent.setText("↑ No messages sent");
                h.tvMessagesSent.setTextColor(0xFF757575);
            }

            // === Полученные сообщения ===
            FastDiscoveryManager.DiscoveredDevice.ReceivedMessage lastRecv = dev.getLastReceivedMessage();
            if (lastRecv != null) {
                long recvAgo = (now - lastRecv.receivedAt) / 1000;
                h.tvMessagesReceived.setText(String.format(Locale.getDefault(),
                        "↓ [%s] \"%s\" (%ds ago)",
                        lastRecv.messageId,
                        truncate(lastRecv.text, 20),
                        recvAgo));

                int totalRecv = dev.receivedMessages.size();
                if (totalRecv > 1) {
                    h.tvMessagesReceived.append(String.format(" [total: %d]", totalRecv));
                }
                h.tvMessagesReceived.setTextColor(0xFF2196F3);
            } else {
                h.tvMessagesReceived.setText("↓ No messages received");
                h.tvMessagesReceived.setTextColor(0xFF757575);
            }

            // === ACKs ===
            if (!dev.ackedMessageIds.isEmpty()) {
                StringBuilder ackSb = new StringBuilder();
                int count = 0;
                for (String ackId : dev.ackedMessageIds) {
                    if (count > 0) ackSb.append(", ");
                    ackSb.append(ackId);
                    count++;
                    if (count >= 3) {
                        ackSb.append("...");
                        break;
                    }
                }
                long ackAgo = dev.lastAckTime > 0 ? (now - dev.lastAckTime) / 1000 : -1;
                h.tvAcksInfo.setText(String.format(Locale.getDefault(),
                        "✓ %d ACKs: %s (%ds)",
                        dev.ackedMessageIds.size(),
                        ackSb.toString(),
                        ackAgo));
                h.tvAcksInfo.setTextColor(0xFF4CAF50);
            } else {
                h.tvAcksInfo.setText("No ACKs");
                h.tvAcksInfo.setTextColor(0xFF757575);
            }

            // === Service Info ===
            String serviceName = dev.lastServiceName != null ? dev.lastServiceName : "-";
            String slotInfo = dev.lastSlotIndex >= 0 ? String.valueOf(dev.lastSlotIndex) : "-";
            h.tvServiceInfo.setText(String.format("📡 Svc: %s | Slot: %s", serviceName, slotInfo));

            // Клик
            h.itemView.setOnClickListener(v -> listener.onClick(dev));

            // Фон в зависимости от статуса
            if (dev.hasOurApp && dev.isOnline()) {
                h.itemView.setBackgroundColor(0x0800FF00); // легкий зелёный
            } else if (dev.hasOurApp) {
                h.itemView.setBackgroundColor(0x08FFFF00); // легкий жёлтый
            } else {
                h.itemView.setBackgroundColor(0x00000000); // прозрачный
            }
        }

        private String truncate(String s, int max) {
            if (s == null) return "";
            return s.length() <= max ? s : s.substring(0, max) + "…";
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvDeviceName, tvStatusIndicator;
            TextView tvDeviceId, tvAppStatus;
            TextView tvHeartbeatSent, tvHeartbeatReceived;
            TextView tvMessagesSent, tvMessagesReceived;
            TextView tvAcksInfo;
            TextView tvServiceInfo;

            ViewHolder(View itemView) {
                super(itemView);
                tvDeviceName = itemView.findViewById(R.id.tv_device_name);
                tvStatusIndicator = itemView.findViewById(R.id.tv_status_indicator);
                tvDeviceId = itemView.findViewById(R.id.tv_device_id);
                tvAppStatus = itemView.findViewById(R.id.tv_app_status);
                tvHeartbeatSent = itemView.findViewById(R.id.tv_heartbeat_sent);
                tvHeartbeatReceived = itemView.findViewById(R.id.tv_heartbeat_received);
                tvMessagesSent = itemView.findViewById(R.id.tv_messages_sent);
                tvMessagesReceived = itemView.findViewById(R.id.tv_messages_received);
                tvAcksInfo = itemView.findViewById(R.id.tv_acks_info);
                tvServiceInfo = itemView.findViewById(R.id.tv_service_info);
            }
        }
    }
}