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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
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

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable uiUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            if (adapter != null) {
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
        Button btnRefresh = findViewById(R.id.btn_refresh);
        Button btnClear = findViewById(R.id.btn_clear);
        Button btnLog = findViewById(R.id.btn_log);

        RecyclerView recyclerView = findViewById(R.id.recycler_devices);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DeviceAdapter(devices, this::onDeviceClick);
        recyclerView.setAdapter(adapter);

        btnRefresh.setOnClickListener(v -> {
            if (discoveryManager != null) {
                discoveryManager.forceRefresh();
                Toast.makeText(this, "Обновление...", Toast.LENGTH_SHORT).show();
            }
        });

        btnClear.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Очистка")
                    .setMessage("Очистить все сообщения, подтверждения и логи?")
                    .setPositiveButton("Да", (d, w) -> {
                        if (discoveryManager != null) {
                            discoveryManager.clearAll();
                            adapter.notifyDataSetChanged();
                            Toast.makeText(this, "Очищено", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Нет", null)
                    .show();
        });

        btnLog.setOnClickListener(v -> startActivity(new Intent(this, DiagnosticActivity.class)));

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
        int online = 0, withApp = 0;
        for (FastDiscoveryManager.DiscoveredDevice d : devices) {
            if (d.isOnline()) online++;
            if (d.hasOurApp) withApp++;
        }

        tvStatus.setText(String.format(Locale.getDefault(),
                "ID:%s | Dev:%d On:%d App:%d\nHB#%d | TXT:%d | ACK:%d",
                discoveryManager.getShortDeviceId(),
                total, online, withApp,
                discoveryManager.getHeartbeatSeq(),
                discoveryManager.getTxtRecordsReceived(),
                discoveryManager.getPendingAcksCount()));
    }

    private void checkAndRequestPermissions() {
        List<String> perms = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }

        if (perms.isEmpty()) {
            startDiscovery();
        } else {
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int r : results) if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
            if (allGranted) startDiscovery();
            else tvStatus.setText("Permissions required");
        }
    }

    private void startDiscovery() {
        tvStatus.setText("Starting...");
        discoveryManager = new FastDiscoveryManager(this);
        discoveryManager.start(new FastDiscoveryManager.DiscoveryListener() {
            @Override public void onDeviceFound(FastDiscoveryManager.DiscoveredDevice d) {
                runOnUiThread(() -> updateDevice(d));
            }
            @Override public void onDeviceUpdated(FastDiscoveryManager.DiscoveredDevice d) {
                runOnUiThread(() -> updateDevice(d));
            }
            @Override public void onDeviceLost(FastDiscoveryManager.DiscoveredDevice d) {
                runOnUiThread(() -> { devices.remove(d); adapter.notifyDataSetChanged(); });
            }
            @Override public void onDeviceOnlineStatusChanged(FastDiscoveryManager.DiscoveredDevice d, boolean on) {}
            @Override public void onStatusChanged(String s) {}
            @Override public void onError(String m) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show());
            }
            @Override public void onMessageSent(String id, String msg, String target) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "→ " + id, Toast.LENGTH_SHORT).show();
                    adapter.notifyDataSetChanged();
                });
            }
            @Override public void onMessageReceived(FastDiscoveryManager.DiscoveredDevice d, String id, String msg) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "← " + d.getShortId() + ": " + msg, Toast.LENGTH_LONG).show();
                    adapter.notifyDataSetChanged();
                });
            }
            @Override public void onAckReceived(FastDiscoveryManager.DiscoveredDevice d, String ackId) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "✓ " + ackId, Toast.LENGTH_SHORT).show();
                    adapter.notifyDataSetChanged();
                });
            }
        });
    }

    private void updateDevice(FastDiscoveryManager.DiscoveredDevice device) {
        int idx = -1;
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).address.equals(device.address)) { idx = i; break; }
        }
        if (idx >= 0) devices.set(idx, device); else devices.add(device);
        adapter.notifyDataSetChanged();
    }

    /**
     * Нажатие на устройство — отправка сообщения
     */
    private void onDeviceClick(FastDiscoveryManager.DiscoveredDevice device) {
        if (discoveryManager == null) {
            Toast.makeText(this, "Сервис не запущен", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!device.hasOurApp) {
            Toast.makeText(this, "На устройстве нет приложения", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!device.isOnline()) {
            Toast.makeText(this, "Устройство оффлайн", Toast.LENGTH_SHORT).show();
            return;
        }

        // Генерируем уникальное сообщение
        String text = "Hello @" + System.currentTimeMillis() % 10000;
        String msgId = discoveryManager.sendMessage(text, device.deviceId);

        Toast.makeText(this, "→ " + device.getShortId() + "\n" + msgId, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiHandler.removeCallbacksAndMessages(null);
        if (discoveryManager != null) discoveryManager.stop();
    }

    // ==================== ADAPTER ====================

    static class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.VH> {

        private final List<FastDiscoveryManager.DiscoveredDevice> list;
        private final OnClick listener;

        interface OnClick { void onClick(FastDiscoveryManager.DiscoveredDevice d); }

        DeviceAdapter(List<FastDiscoveryManager.DiscoveredDevice> list, OnClick l) {
            this.list = list;
            this.listener = l;
        }

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_device, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            FastDiscoveryManager.DiscoveredDevice d = list.get(position);
            long now = System.currentTimeMillis();

            // Header
            h.tvName.setText(d.name != null ? d.name : "Unknown");
            h.tvStatus.setText(d.isOnline() ? "● ONLINE" : "○ OFFLINE");
            h.tvStatus.setTextColor(d.isOnline() ? 0xFF4CAF50 : 0xFF9E9E9E);
            h.tvName.setTextColor(d.isOnline() ? 0xFF2E7D32 : 0xFF757575);

            // ID
            String id = d.deviceId != null ? d.deviceId.substring(0, Math.min(8, d.deviceId.length())) : "?";
            h.tvId.setText(String.format("ID: %s | MAC: %s", id, d.address));

            // App status
            h.tvApp.setText(String.format("%s | Seen: %dx | %ds ago",
                    d.hasOurApp ? "✅App" : "❌NoApp", d.seenCount, (now - d.lastSeen) / 1000));

            // Heartbeat
            if (d.lastHeartbeatReceived > 0) {
                h.tvHb.setText(String.format(Locale.getDefault(), "💓 HB#%d←#%d (%ds)",
                        d.heartbeatSeq, d.prevHeartbeatSeq, (now - d.lastHeartbeatReceived) / 1000));
            } else {
                h.tvHb.setText("💓 No HB");
            }

            // RECEIVED messages + ACK
            List<FastDiscoveryManager.DiscoveredDevice.ReceivedMessage> recv = d.getLastReceivedMessages(3);
            bindReceivedMessage(h.tvRecv1, h.tvRecv1Ack, recv.size() > 0 ? recv.get(0) : null, now);
            bindReceivedMessage(h.tvRecv2, h.tvRecv2Ack, recv.size() > 1 ? recv.get(1) : null, now);
            bindReceivedMessage(h.tvRecv3, h.tvRecv3Ack, recv.size() > 2 ? recv.get(2) : null, now);

            // SENT messages + ACK
            List<FastDiscoveryManager.DiscoveredDevice.SentMessage> sent = d.getLastSentMessages(3);
            bindSentMessage(h.tvSent1, h.tvSent1Ack, sent.size() > 0 ? sent.get(0) : null, now);
            bindSentMessage(h.tvSent2, h.tvSent2Ack, sent.size() > 1 ? sent.get(1) : null, now);
            bindSentMessage(h.tvSent3, h.tvSent3Ack, sent.size() > 2 ? sent.get(2) : null, now);

            // Service info
            h.tvService.setText(String.format("📡 Svc: %s | Slot: %s | Visible: %d | Pending: %d",
                    d.lastServiceName != null ? d.lastServiceName : "-",
                    d.lastSlotIndex >= 0 ? String.valueOf(d.lastSlotIndex) : "-",
                    d.currentVisibleMsgIds.size(),
                    d.getPendingAckMessageIds().size()));

            h.itemView.setOnClickListener(v -> listener.onClick(d));
            h.itemView.setBackgroundColor(d.hasOurApp && d.isOnline() ? 0x0800FF00 : 0x00000000);
        }

        private void bindReceivedMessage(TextView tvMsg, TextView tvAck,
                                         FastDiscoveryManager.DiscoveredDevice.ReceivedMessage m, long now) {
            if (m == null) {
                tvMsg.setVisibility(View.GONE);
                tvAck.setVisibility(View.GONE);
                return;
            }
            tvMsg.setVisibility(View.VISIBLE);
            tvAck.setVisibility(View.VISIBLE);

            long ago = (now - m.receivedAt) / 1000;
            tvMsg.setText(String.format("↓ [%s] \"%s\" (%ds)", m.messageId, truncate(m.text, 20), ago));

            if (m.ackConfirmed) {
                tvAck.setText(String.format("  ↪ ACK ✓ delivered (sent %dx)", m.ackSendCount));
                tvAck.setTextColor(0xFF4CAF50);
            } else if (m.ackSent) {
                long ackAgo = (now - m.ackSentAt) / 1000;
                tvAck.setText(String.format("  ↪ ACK sending... %dx (%ds)", m.ackSendCount, ackAgo));
                tvAck.setTextColor(0xFFFF9800);
            } else {
                tvAck.setText("  ↪ ACK pending");
                tvAck.setTextColor(0xFF9E9E9E);
            }
        }

        private void bindSentMessage(TextView tvMsg, TextView tvAck,
                                     FastDiscoveryManager.DiscoveredDevice.SentMessage m, long now) {
            if (m == null) {
                tvMsg.setVisibility(View.GONE);
                tvAck.setVisibility(View.GONE);
                return;
            }
            tvMsg.setVisibility(View.VISIBLE);
            tvAck.setVisibility(View.VISIBLE);

            long ago = (now - m.sentAt) / 1000;
            String ackIcon = m.acknowledged ? "✓" : "⏳";
            tvMsg.setText(String.format("↑ %s [%s] \"%s\" (%ds) S:%d",
                    ackIcon, m.messageId, truncate(m.text, 15), ago, m.slotIndex));
            tvMsg.setTextColor(m.acknowledged ? 0xFF4CAF50 : 0xFFFF9800);

            if (m.acknowledged) {
                long ackAgo = (now - m.ackReceivedAt) / 1000;
                String batch = m.ackBatch.isEmpty() ? "" : " (+" + String.join(",", m.ackBatch) + ")";
                tvAck.setText(String.format("  ↩ ACK received (%ds)%s", ackAgo, batch));
                tvAck.setTextColor(0xFF2196F3);
            } else {
                tvAck.setText("  ↩ waiting ACK...");
                tvAck.setTextColor(0xFF9E9E9E);
            }
        }

        private String truncate(String s, int max) {
            if (s == null) return "";
            return s.length() <= max ? s : s.substring(0, max) + "…";
        }

        @Override public int getItemCount() { return list.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView tvName, tvStatus, tvId, tvApp, tvHb;
            TextView tvRecv1, tvRecv1Ack, tvRecv2, tvRecv2Ack, tvRecv3, tvRecv3Ack;
            TextView tvSent1, tvSent1Ack, tvSent2, tvSent2Ack, tvSent3, tvSent3Ack;
            TextView tvService;

            VH(View v) {
                super(v);
                tvName = v.findViewById(R.id.tv_device_name);
                tvStatus = v.findViewById(R.id.tv_status_indicator);
                tvId = v.findViewById(R.id.tv_device_id);
                tvApp = v.findViewById(R.id.tv_app_status);
                tvHb = v.findViewById(R.id.tv_heartbeat_info);

                tvRecv1 = v.findViewById(R.id.tv_received_1);
                tvRecv1Ack = v.findViewById(R.id.tv_received_1_ack);
                tvRecv2 = v.findViewById(R.id.tv_received_2);
                tvRecv2Ack = v.findViewById(R.id.tv_received_2_ack);
                tvRecv3 = v.findViewById(R.id.tv_received_3);
                tvRecv3Ack = v.findViewById(R.id.tv_received_3_ack);

                tvSent1 = v.findViewById(R.id.tv_sent_1);
                tvSent1Ack = v.findViewById(R.id.tv_sent_1_ack);
                tvSent2 = v.findViewById(R.id.tv_sent_2);
                tvSent2Ack = v.findViewById(R.id.tv_sent_2_ack);
                tvSent3 = v.findViewById(R.id.tv_sent_3);
                tvSent3Ack = v.findViewById(R.id.tv_sent_3_ack);

                tvService = v.findViewById(R.id.tv_service_info);
            }
        }
    }
}