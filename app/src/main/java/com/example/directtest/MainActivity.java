package com.example.directtest;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        btnSendBroadcast = findViewById(R.id.btn_broadcast);
        btnRefresh = findViewById(R.id.btn_refresh);

        RecyclerView recyclerView = findViewById(R.id.recycler_devices);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new DeviceAdapter(devices, this::onDeviceClick);
        recyclerView.setAdapter(adapter);

        Button btnLog = findViewById(R.id.btn_log);
        btnLog.setOnClickListener(v -> {
            Intent intent = new Intent(this, DiagnosticActivity.class);
            startActivity(intent);
        });

        btnSendBroadcast.setOnClickListener(v -> {
            if (discoveryManager != null) {
                String text = "Broadcast " + System.currentTimeMillis();
                String msgId = discoveryManager.broadcastMessage(text);
                Toast.makeText(this, "Broadcast: " + msgId, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Сервис не запущен", Toast.LENGTH_SHORT).show();
            }

        });

        btnRefresh.setOnClickListener(v -> {
            if (discoveryManager != null) {
                discoveryManager.forceRefresh();
                Toast.makeText(this, "Принудительный поиск...", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Сервис не запущен", Toast.LENGTH_SHORT).show();
            }
        });

        // ✅ Сначала проверяем разрешения!
        checkAndRequestPermissions();
    }

    // ==================== PERMISSIONS ====================

    private void checkAndRequestPermissions() {
        List<String> permissionsNeeded = new ArrayList<>();

        // Location - нужен для WiFi P2P на Android 6+
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        // Android 13+ - NEARBY_WIFI_DEVICES
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }

        if (permissionsNeeded.isEmpty()) {
            // Все разрешения есть - запускаем
            startDiscovery();
        } else {
            // Запрашиваем разрешения
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
                Toast.makeText(this,
                        "WiFi P2P требует разрешения на местоположение",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    // ==================== DISCOVERY ====================

    private void startDiscovery() {
        tvStatus.setText("Запуск WiFi P2P...");

        try {
            discoveryManager = new FastDiscoveryManager(this);

            discoveryManager.start(new FastDiscoveryManager.DiscoveryListener() {
                @Override
                public void onDeviceFound(FastDiscoveryManager.DiscoveredDevice device) {
                    runOnUiThread(() -> {
                        updateOrAddDevice(device);
                        tvStatus.setText("Найдено: " + devices.size());
                    });
                }

                @Override
                public void onDeviceUpdated(FastDiscoveryManager.DiscoveredDevice device) {
                    runOnUiThread(() -> {
                        updateOrAddDevice(device);
                        adapter.notifyDataSetChanged();
                    });
                }

                @Override
                public void onDeviceLost(FastDiscoveryManager.DiscoveredDevice device) {
                    runOnUiThread(() -> {
                        devices.remove(device);
                        adapter.notifyDataSetChanged();
                        tvStatus.setText("Найдено: " + devices.size());
                    });
                }

                @Override
                public void onDeviceOnlineStatusChanged(FastDiscoveryManager.DiscoveredDevice device, boolean isOnline) {
                    runOnUiThread(() -> {
                        updateOrAddDevice(device);
                        adapter.notifyDataSetChanged();
                    });
                }

                @Override
                public void onStatusChanged(String status) {
                    runOnUiThread(() -> tvStatus.setText(status));
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this, "Ошибка: " + message, Toast.LENGTH_SHORT).show()
                    );
                }

                @Override
                public void onMessageSent(String messageId, String message, String targetDeviceId) {
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this, "Отправлено: " + messageId, Toast.LENGTH_SHORT).show()
                    );
                }

                @Override
                public void onMessageReceived(FastDiscoveryManager.DiscoveredDevice device,
                                              String messageId, String message) {
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this,
                                    "Получено от " + device.getShortId() + ": " + message,
                                    Toast.LENGTH_LONG).show()
                    );
                }

                @Override
                public void onAckReceived(FastDiscoveryManager.DiscoveredDevice device, String ackedMessageId) {
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this,
                                    "Доставлено → " + ackedMessageId, Toast.LENGTH_SHORT).show()
                    );
                }
            });

        } catch (Exception e) {
            tvStatus.setText("❌ Ошибка: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ==================== DEVICE MANAGEMENT ====================

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
        if (discoveryManager == null) {
            Toast.makeText(this, "Сервис не запущен", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!device.isOnline()) {
            Toast.makeText(this, "Устройство не в сети", Toast.LENGTH_SHORT).show();
            return;
        }
        String text = "Привет " + device.getShortId() + " " + System.currentTimeMillis();
        String msgId = discoveryManager.sendMessage(text, device.deviceId);
        Toast.makeText(this, "→ " + device.getShortId() + "\n" + msgId, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (discoveryManager != null) {
            discoveryManager.stop();
        }
    }

    // ==================== ADAPTER ====================

    static class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {

        private final List<FastDiscoveryManager.DiscoveredDevice> list;
        private final OnDeviceClickListener listener;

        interface OnDeviceClickListener {
            void onClick(FastDiscoveryManager.DiscoveredDevice device);
        }

        DeviceAdapter(List<FastDiscoveryManager.DiscoveredDevice> list, OnDeviceClickListener l) {
            this.list = list;
            this.listener = l;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(android.R.layout.simple_list_item_2, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            FastDiscoveryManager.DiscoveredDevice dev = list.get(position);

            String status = dev.isOnline() ? "● онлайн" : "○ оффлайн";
            String line1 = dev.name != null && !dev.name.isEmpty() ? dev.name : "Без имени";
            String line2 = String.format(Locale.getDefault(),
                    "%s  •  %s  •  %s",
                    dev.getShortId(),
                    status,
                    dev.hasOurApp ? "есть приложение" : "нет приложения"
            );

            holder.text1.setText(line1);
            holder.text2.setText(line2);

            int color = dev.isOnline() ? 0xff2e7d32 : 0xff757575;
            holder.text1.setTextColor(color);

            holder.itemView.setOnClickListener(v -> listener.onClick(dev));
        }

        @Override
        public int getItemCount() {
            return list.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView text1, text2;

            ViewHolder(View itemView) {
                super(itemView);
                text1 = itemView.findViewById(android.R.id.text1);
                text2 = itemView.findViewById(android.R.id.text2);
            }
        }
    }
}