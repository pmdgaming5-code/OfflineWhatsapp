package com.copperbars.offlinewhatsapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_WIFI_PERMISSION = 1001;
    private static final int REQ_NOTIFICATION_PERMISSION = 1002;
    private static final int SERVER_PORT = 45821;
    private static final String NOTIFICATION_CHANNEL_ID = "offline_chat";

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private WifiManager wifiManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final List<WifiP2pDevice> peers = new ArrayList<>();
    private ArrayAdapter<String> peerAdapter;

    private MessageStore store;
    private PeerConnection connection;

    private TextView status;
    private TextView peerStatus;
    private LinearLayout messagesLayout;
    private EditText messageInput;
    private boolean registered;
    private int notificationId = 2000;
    private ToneGenerator toneGenerator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        store = new MessageStore(this);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), () -> setStatus("Wi‑Fi Direct kanalı kesildi."));
        receiver = createReceiver();
        toneGenerator = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75);

        createNotificationChannel();
        buildUi();
        requestRequiredPermissions();
        loadMessages();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);

        TextView title = new TextView(this);
        title.setText("OfflineWhatsapp");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(13, 71, 161));
        title.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView info = new TextView(this);
        info.setText("%100 yerel mesajlaşma • İnternet gerekmez\nWi‑Fi internet erişimi olmasa da Wi‑Fi Direct kullanılabilir.");
        info.setTextSize(15);
        info.setPadding(0, 8, 0, 8);

        status = new TextView(this);
        status.setTextSize(15);
        status.setText("Durum: Hazırlanıyor…");

        peerStatus = new TextView(this);
        peerStatus.setText("Yakındaki cihazlar");

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);

        Button serverButton = new Button(this);
        serverButton.setText("SUNUCU BAŞLAT");
        serverButton.setOnClickListener(v -> startServerGroup());

        Button scanButton = new Button(this);
        scanButton.setText("CİHAZLARI TARA");
        scanButton.setOnClickListener(v -> discoverPeers());

        buttons.addView(serverButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(scanButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ListView list = new ListView(this);
        peerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(peerAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < peers.size()) connectToPeer(peers.get(position));
        });

        messagesLayout = new LinearLayout(this);
        messagesLayout.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(messagesLayout);

        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);

        messageInput = new EditText(this);
        messageInput.setHint("Mesaj yaz…");
        messageInput.setSingleLine(true);

        Button send = new Button(this);
        send.setText("GÖNDER");
        send.setOnClickListener(v -> sendMessage());

        composer.addView(messageInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        composer.addView(send, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(title);
        root.addView(info);
        root.addView(status);
        root.addView(buttons);
        root.addView(peerStatus);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 180));

        TextView chatLabel = new TextView(this);
        chatLabel.setText("Sohbet");
        chatLabel.setTextSize(18);
        chatLabel.setPadding(0, 12, 0, 6);
        root.addView(chatLabel);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(composer);

        setContentView(root);
    }

    private void loadMessages() {
        for (MessageStore.Message message : store.getAll()) {
            addMessageBubble(message.text, message.incoming);
        }
    }

    private BroadcastReceiver createReceiver() {
        return new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    setStatus(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                            ? "Durum: Wi‑Fi Direct açık"
                            : "Durum: Wi‑Fi Direct kapalı • Wi‑Fi'yi açın");
                } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    requestPeers();
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    requestConnectionInfo();
                }
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!registered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            filter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(receiver, filter);
            }
            registered = true;
        }
    }

    @Override
    protected void onPause() {
        if (registered) {
            unregisterReceiver(receiver);
            registered = false;
        }
        super.onPause();
    }

    private void requestRequiredPermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                handler.postDelayed(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION_PERMISSION), 500);
            }
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }

        if (!needed.isEmpty()) {
            requestPermissions(needed.toArray(new String[0]), REQ_WIFI_PERMISSION);
        } else {
            setStatus(initialStatus());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_WIFI_PERMISSION) {
            boolean ok = true;
            for (int r : grantResults) ok &= r == PackageManager.PERMISSION_GRANTED;
            if (ok) {
                setStatus(initialStatus());
                maybeRequestNotifications();
            } else {
                setStatus("Durum: Yakındaki cihazlar izni verilmedi");
            }
        } else if (requestCode == REQ_NOTIFICATION_PERMISSION) {
            setStatus(initialStatus());
        }
    }

    private void maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION_PERMISSION);
        }
    }

    private String initialStatus() {
        if (wifiManager != null && !wifiManager.isWifiEnabled()) {
            return "Durum: Wi‑Fi kapalı • İnternet gerekmez, Wi‑Fi'yi açın";
        }
        return "Durum: Hazır • İnternet gerekli değil";
    }

    @SuppressLint("MissingPermission")
    private void discoverPeers() {
        if (!hasWifiPermission()) {
            toast("Önce Yakındaki Cihazlar iznini ver.");
            requestRequiredPermissions();
            return;
        }
        if (wifiManager != null && !wifiManager.isWifiEnabled()) {
            setStatus("Tarama yapılamadı • İnternet değil, Wi‑Fi radyo bağlantısı gerekli");
            toast("Wi‑Fi'yi aç. İnternet bağlantısı gerekmiyor.");
            return;
        }

        manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                setStatus("Durum: Yerel cihaz aranıyor… İnternet kullanılmıyor");
                playSound(false);
            }

            @Override public void onFailure(int reason) {
                setStatus("Tarama başarısız: " + p2pError(reason));
                playErrorSound();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void requestPeers() {
        manager.requestPeers(channel, list -> {
            peers.clear();
            peers.addAll(list.getDeviceList());

            List<String> labels = new ArrayList<>();
            for (WifiP2pDevice d : peers) {
                labels.add(d.deviceName + "\n" + d.deviceAddress + "\n" + statusFor(d.status));
            }

            peerAdapter.clear();
            peerAdapter.addAll(labels);
            peerAdapter.notifyDataSetChanged();
            peerStatus.setText("Yakındaki cihazlar: " + peers.size());
        });
    }

    @SuppressLint("MissingPermission")
    private void connectToPeer(WifiP2pDevice peer) {
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = peer.deviceAddress;

        setStatus("Durum: " + peer.deviceName + " cihazına bağlanıyor…");
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                toast("Yerel Wi‑Fi Direct bağlantısı kuruluyor.");
                playSound(false);
            }

            @Override public void onFailure(int reason) {
                setStatus("Bağlantı başarısız: " + p2pError(reason));
                playErrorSound();
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void startServerGroup() {
        if (!hasWifiPermission()) {
            toast("Önce Yakındaki Cihazlar iznini ver.");
            requestRequiredPermissions();
            return;
        }
        if (wifiManager != null && !wifiManager.isWifiEnabled()) {
            setStatus("Sunucu açılamadı • Wi‑Fi kapalı. İnternet gerekmiyor; Wi‑Fi radyo açık olmalı.");
            toast("Wi‑Fi'yi aç; mobil veri veya internet gerekmez.");
            playErrorSound();
            return;
        }

        manager.createGroup(channel, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                setStatus("Durum: ✅ Offline sunucu grubu oluşturuldu");
                toast("Sunucu hazır. Diğer cihazda CİHAZLARI TARA.");
                playSound(false);
                notifyUser("Offline sunucu hazır", "Diğer cihaz Cihazları Tara ile bağlanabilir.");
                handler.postDelayed(MainActivity.this::requestConnectionInfo, 700);
            }

            @Override public void onFailure(int reason) {
                setStatus("Sunucu başlatılamadı: " + p2pError(reason));
                playErrorSound();
                notifyUser("Sunucu başlatılamadı", p2pError(reason));
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void requestConnectionInfo() {
        manager.requestConnectionInfo(channel, info -> {
            if (!info.groupFormed) {
                closeConnection();
                setStatus("Durum: Bağlantı yok • Yerel P2P bekleniyor");
                return;
            }

            InetAddress ownerAddress = info.groupOwnerAddress;
            if (ownerAddress == null) return;

            if (info.isGroupOwner) {
                setStatus("Durum: ✅ Offline sunucu aktif • " + ownerAddress.getHostAddress());
                if (connection == null) createServerConnection();
            } else {
                setStatus("Durum: ✅ Offline sunucuya bağlandı • " + ownerAddress.getHostAddress());
                if (connection == null) createClientConnection(ownerAddress.getHostAddress());
            }
        });
    }

    private void createServerConnection() {
        closeConnection();
        connection = new PeerConnection(connectionListener());
        connection.startServer();
        setStatus("Durum: Offline sunucu bekliyor • port " + SERVER_PORT);
    }

    private void createClientConnection(String host) {
        closeConnection();
        connection = new PeerConnection(connectionListener());
        connection.connectTo(host);
    }

    private PeerConnection.Listener connectionListener() {
        return new PeerConnection.Listener() {
            @Override public void onConnected() {
                setStatus("Durum: ✅ İnternetsiz mesajlaşmaya hazır");
                playSound(false);
                notifyUser("Bağlantı kuruldu", "OfflineWhatsapp artık doğrudan cihazla bağlı.");
            }

            @Override public void onMessage(String message) {
                store.add(message, true, System.currentTimeMillis());
                addMessageBubble(message, true);
                playIncomingSound();
                notifyUser("Yeni offline mesaj", message);
            }

            @Override public void onDisconnected() {
                setStatus("Durum: Bağlantı kesildi");
                playErrorSound();
            }

            @Override public void onError(String error) {
                setStatus("Hata: " + error);
                playErrorSound();
            }
        };
    }

    private void sendMessage() {
        String message = messageInput.getText().toString().trim();
        if (message.isEmpty()) return;

        if (connection == null) {
            toast("Önce iki cihazı Wi‑Fi Direct ile bağlayın.");
            playErrorSound();
            return;
        }

        connection.send(message);
        store.add(message, false, System.currentTimeMillis());
        addMessageBubble(message, false);
        messageInput.setText("");
        playSound(true);
    }

    private void addMessageBubble(String message, boolean incoming) {
        TextView bubble = new TextView(this);
        bubble.setText(message);
        bubble.setTextSize(16);
        bubble.setPadding(18, 12, 18, 12);
        bubble.setBackgroundColor(incoming ? Color.rgb(232, 245, 233) : Color.rgb(227, 242, 253));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = incoming ? Gravity.START : Gravity.END;
        params.setMargins(6, 5, 6, 5);
        messagesLayout.addView(bubble, params);
    }

    private boolean hasWifiPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void closeConnection() {
        if (connection != null) {
            connection.stop();
            connection = null;
        }
    }

    private void setStatus(String text) {
        if (status != null) status.setText(text);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static String statusFor(int status) {
        switch (status) {
            case WifiP2pDevice.AVAILABLE: return "Available";
            case WifiP2pDevice.INVITED: return "Invited";
            case WifiP2pDevice.CONNECTED: return "Connected";
            case WifiP2pDevice.FAILED: return "Failed";
            case WifiP2pDevice.UNAVAILABLE: return "Unavailable";
            default: return "Unknown";
        }
    }

    private static String p2pError(int reason) {
        switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED: return "P2P desteklenmiyor";
            case WifiP2pManager.BUSY: return "Wi‑Fi Direct meşgul; birkaç saniye sonra tekrar deneyin";
            case WifiP2pManager.ERROR: return "Wi‑Fi Direct genel hatası";
            default: return "kod " + reason;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Offline mesajlar",
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("İnternetsiz OfflineWhatsapp bağlantı ve mesaj bildirimleri");
            channel.enableVibration(true);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    private void notifyUser(String title, String text) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new android.app.Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                : new android.app.Notification.Builder(this);

        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(android.app.Notification.CATEGORY_MESSAGE)
                .setOnlyAlertOnce(false);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(notificationId++, builder.build());
    }

    private void playSound(boolean sent) {
        if (toneGenerator == null) return;
        toneGenerator.startTone(sent ? ToneGenerator.TONE_PROP_BEEP : ToneGenerator.TONE_PROP_ACK, 90);
    }

    private void playIncomingSound() {
        if (toneGenerator == null) return;
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 120);
    }

    private void playErrorSound() {
        if (toneGenerator == null) return;
        toneGenerator.startTone(ToneGenerator.TONE_SUP_ERROR, 180);
    }

    @Override
    protected void onDestroy() {
        closeConnection();
        if (toneGenerator != null) {
            toneGenerator.release();
            toneGenerator = null;
        }
        if (manager != null && channel != null && hasWifiPermission()) {
            manager.removeGroup(channel, null);
        }
        store.close();
        super.onDestroy();
    }
}
