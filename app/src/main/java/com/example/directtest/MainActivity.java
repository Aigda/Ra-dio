package com.example.directtest;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

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

        discoveryManager = new FastDiscoveryManager(this);

        discoveryManager.start(new FastDiscoveryManager.DiscoveryListener() {
            @Override public void onDeviceFound(FastDiscoveryManager.DiscoveredDevice device) {
                runOnUiThread(() -> { updateOrAddDevice(device); tvStatus.setText("Найдено: " + devices.size()); });
            }
            @Override public void onDeviceUpdated(FastDiscoveryManager.DiscoveredDevice device) {
                runOnUiThread(() -> { updateOrAddDevice(device); adapter.notifyDataSetChanged(); });
            }
            @Override public void onDeviceLost(FastDiscoveryManager.DiscoveredDevice device) {
                runOnUiThread(() -> {
                    devices.remove(device);
                    adapter.notifyDataSetChanged();
                    tvStatus.setText("Найдено: " + devices.size());
                });
            }
            @Override public void onDeviceOnlineStatusChanged(FastDiscoveryManager.DiscoveredDevice device, boolean isOnline) {
                runOnUiThread(() -> { updateOrAddDevice(device); adapter.notifyDataSetChanged(); });
            }
            @Override public void onStatusChanged(String status) { runOnUiThread(() -> tvStatus.setText(status)); }
            @Override public void onError(String message) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Ошибка: " + message, Toast.LENGTH_SHORT).show());
            }
            @Override public void onMessageSent(String messageId, String message, String targetDeviceId) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Отправлено: " + messageId, Toast.LENGTH_SHORT).show());
            }
            @Override public void onMessageReceived(FastDiscoveryManager.DiscoveredDevice device, String messageId, String message) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Получено от " + device.getShortId() + ": " + message, Toast.LENGTH_LONG).show());
            }
            @Override public void onAckReceived(FastDiscoveryManager.DiscoveredDevice device, String ackedMessageId) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Доставлено → " + ackedMessageId, Toast.LENGTH_SHORT).show());
            }
        });

        btnSendBroadcast.setOnClickListener(v -> {
            String text = "Broadcast " + System.currentTimeMillis();
            String msgId = discoveryManager.broadcastMessage(text);
            Toast.makeText(this, "Broadcast: " + msgId, Toast.LENGTH_SHORT).show();
        });

        btnRefresh.setOnClickListener(v -> {
            discoveryManager.forceRefresh();
            Toast.makeText(this, "Принудительный поиск...", Toast.LENGTH_SHORT).show();
        });
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
        if (discoveryManager != null) discoveryManager.stop();
    }

    // Адаптер
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

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(android.R.layout.simple_list_item_2, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
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

        @Override public int getItemCount() { return list.size(); }

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