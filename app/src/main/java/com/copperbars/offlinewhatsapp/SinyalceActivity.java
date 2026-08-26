package com.copperbars.offlinewhatsapp;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.location.LocationManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class SinyalceActivity extends Activity {
    private static final int REQ_NEARBY = 1001;
    private static final int REQ_NOTIFY = 1002;
    private static final int PICK_IMAGE = 2001;

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver p2pReceiver;
    private BroadcastReceiver serviceReceiver;
    private boolean p2pRegistered;
    private boolean serviceRegistered;
    private final List<WifiP2pDevice> peers = new ArrayList<>();
    private ArrayAdapter<String> peerAdapter;
    private MessageStore store;
    private ToneGenerator tone;

    private TextView status;
    private TextView roomTitle;
    private TextView peerCount;
    private LinearLayout messageList;
    private ScrollView messageScroll;
    private EditText input;
    private String currentRoom = "Genel";
    private String pendingRoom = "Genel";
    private String pendingPassword = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new MessageStore(this);
        tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70);
        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager != null) {
            channel = manager.initialize(this, getMainLooper(),
                    () -> setStatus("Wi‑Fi Direct kanalı kesildi"));
        }
        p2pReceiver = createP2pReceiver();
        serviceReceiver = createServiceReceiver();
        buildUi();
        requestPermissionsIfNeeded();
        loadRoom(currentRoom);
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppState.setForeground(true);
        registerReceivers();
    }

    @Override
    protected void onStop() {
        AppState.setForeground(false);
        unregisterReceivers();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        try { tone.release(); } catch (Exception ignored) {}
        if (store != null) store.close();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(244, 247, 251));

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(16, 12, 10, 12);
        bar.setBackgroundColor(Color.rgb(17, 76, 160));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("Sinyalce", 24, Color.WHITE, true);
        roomTitle = text("• Genel", 14, Color.WHITE, false);
        titleBox.addView(title);
        titleBox.addView(roomTitle);
        bar.addView(titleBox, new LinearLayout.LayoutParams(0, -2, 1));

        ImageButton info = new ImageButton(this);
        info.setImageResource(android.R.drawable.ic_menu_info_details);
        info.setColorFilter(Color.WHITE);
        info.setBackgroundColor(Color.TRANSPARENT);
        info.setContentDescription("Bilgi");
        info.setOnClickListener(v -> toast("Sinyalce internet veya bulut kullanmaz; Wi‑Fi Direct ile yerel bağlantı kurar."));
        bar.addView(info, new LinearLayout.LayoutParams(50, 50));
        root.addView(bar);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(10, 8, 10, 4);
        status = text("Hazırlanıyor…", 14, Color.DKGRAY, false);
        top.addView(status);

        LinearLayout actions = new LinearLayout(this);
        Button server = new Button(this);
        server.setText("SUNUCU AÇ");
        server.setOnClickListener(v -> showServerDialog());
        Button scan = new Button(this);
        scan.setText("YAKINDAKİLER");
        scan.setOnClickListener(v -> discoverPeers());
        Button stop = new Button(this);
        stop.setText("DURDUR");
        stop.setOnClickListener(v -> stopOfflineService());
        actions.addView(server, new LinearLayout.LayoutParams(0, 50, 1));
        actions.addView(scan, new LinearLayout.LayoutParams(0, 50, 1));
        actions.addView(stop, new LinearLayout.LayoutParams(0, 50, 1));
        top.addView(actions);
        root.addView(top);

        peerCount = text("Yakındaki cihazlar: 0", 13, Color.GRAY, false);
        peerCount.setPadding(12, 2, 12, 2);
        root.addView(peerCount);

        ListView list = new ListView(this);
        peerAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(peerAdapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (pos >= 0 && pos < peers.size()) showJoinDialog(peers.get(pos));
        });
        root.addView(list, new LinearLayout.LayoutParams(-1, 125));

        messageScroll = new ScrollView(this);
        messageList = new LinearLayout(this);
        messageList.setOrientation(LinearLayout.VERTICAL);
        messageList.setPadding(8, 8, 8, 8);
        messageScroll.addView(messageList);
        root.addView(messageScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(6, 6, 6, 6);
        composer.setBackgroundColor(Color.WHITE);

        ImageButton image = new ImageButton(this);
        image.setImageResource(android.R.drawable.ic_menu_gallery);
        image.setBackgroundColor(Color.TRANSPARENT);
        image.setContentDescription("Görsel gönder");
        image.setOnClickListener(v -> pickImage());
        composer.addView(image, new LinearLayout.LayoutParams(48, 48));

        input = new EditText(this);
        input.setHint("Mesaj yaz…");
        input.setSingleLine(true);
        composer.addView(input, new LinearLayout.LayoutParams(0, 50, 1));

        Button send = new Button(this);
        send.setText("GÖNDER");
        send.setOnClickListener(v -> sendText());
        composer.addView(send, new LinearLayout.LayoutParams(-2, 50));

        root.addView(composer);
        setContentView(root);
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private BroadcastReceiver createP2pReceiver() {
        return new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                    int s = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    setStatus(s == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                            ? "Wi‑Fi Direct açık • internet gerekmez"
                            : "Wi‑Fi Direct kapalı");
                } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    requestPeers();
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    requestPeers();
                    requestConnectionInfo();
                }
            }
        };
    }

    private BroadcastReceiver createServiceReceiver() {
        return new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (OfflineService.ACTION_STATUS.equals(action)) {
                    setStatus(intent.getStringExtra(OfflineService.EXTRA_STATUS));
                } else if (OfflineService.ACTION_TEXT.equals(action)) {
                    String room = intent.getStringExtra(OfflineService.EXTRA_ROOM);
                    String value = intent.getStringExtra(OfflineService.EXTRA_TEXT);
                    if (room == null || room.isEmpty()) room = currentRoom;
                    if (!room.equals(currentRoom)) loadRoom(room);
                    else addTextBubble(value, true);
                    beep(true);
                } else if (OfflineService.ACTION_IMAGE.equals(action)) {
                    String room = intent.getStringExtra(OfflineService.EXTRA_ROOM);
                    Uri uri = Build.VERSION.SDK_INT >= 33
                            ? intent.getParcelableExtra(OfflineService.EXTRA_URI, Uri.class)
                            : intent.getParcelableExtra(OfflineService.EXTRA_URI);
                    if (room == null || room.isEmpty()) room = currentRoom;
                    if (!room.equals(currentRoom)) loadRoom(room);
                    else if (uri != null) addImageBubble(uri, true);
                    beep(true);
                }
            }
        };
    }

    private void registerReceivers() {
        if (manager == null || channel == null) return;
        if (!p2pRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
            f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(p2pReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(p2pReceiver, f);
            p2pRegistered = true;
        }
        if (!serviceRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(OfflineService.ACTION_STATUS);
            f.addAction(OfflineService.ACTION_TEXT);
            f.addAction(OfflineService.ACTION_IMAGE);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(serviceReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(serviceReceiver, f);
            serviceRegistered = true;
        }
    }

    private void unregisterReceivers() {
        if (p2pRegistered) {
            try { unregisterReceiver(p2pReceiver); } catch (Exception ignored) {}
            p2pRegistered = false;
        }
        if (serviceRegistered) {
            try { unregisterReceiver(serviceReceiver); } catch (Exception ignored) {}
            serviceRegistered = false;
        }
    }

    private void requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, REQ_NEARBY);
            return;
        }
        if (Build.VERSION.SDK_INT < 33
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQ_NEARBY);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFY);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        requestPermissionsIfNeeded();
        if (requestCode == REQ_NEARBY && !allGranted(grantResults)) {
            setStatus("Yakındaki Cihazlar izni verilmedi");
        }
    }

    private boolean allGranted(int[] grantResults) {
        if (grantResults == null || grantResults.length == 0) return false;
        for (int result : grantResults) if (result != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private boolean nearbyGranted() {
        return Build.VERSION.SDK_INT >= 33
                ? checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
                : checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean locationModeEnabled() {
        if (Build.VERSION.SDK_INT < 28) return true;
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return lm != null && lm.isLocationEnabled();
    }

    private void discoverPeers() {
        if (manager == null || channel == null) return;
        if (!nearbyGranted()) {
            requestPermissionsIfNeeded();
            return;
        }
        if (!locationModeEnabled()) {
            setStatus("Yakındaki cihazları taramak için Konum hizmetini açın; uygulama konumunuzu kullanmaz.");
            toast("Ayarlar > Konum bölümünü açın.");
            return;
        }

        try {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { setStatus("Yerel cihazlar aranıyor…"); beep(false); }
                @Override public void onFailure(int reason) { setStatus("Tarama başarısız: " + error(reason)); beep(false); }
            });
        } catch (SecurityException e) {
            setStatus("Yakındaki Cihazlar izni gerekli");
        }
    }

    private void requestPeers() {
        if (manager == null || channel == null || !nearbyGranted()) return;
        if (!locationModeEnabled()) return;
        try {
            manager.requestPeers(channel, list -> {
                peers.clear();
                peers.addAll(list.getDeviceList());
                List<String> names = new ArrayList<>();
                for (WifiP2pDevice d : peers) {
                    names.add(d.deviceName + "\n" + d.deviceAddress + " • " + deviceStatus(d.status));
                }
                peerAdapter.clear();
                peerAdapter.addAll(names);
                peerAdapter.notifyDataSetChanged();
                peerCount.setText("Yakındaki cihazlar: " + peers.size());
            });
        } catch (SecurityException ignored) {}
    }

    private void showServerDialog() {
        LinearLayout box = fieldsBox();
        EditText name = field("Sunucu adı");
        EditText room = field("Oda adı");
        EditText password = field("Şifre (isteğe bağlı)");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(name);
        box.addView(room);
        box.addView(password);

        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Sinyalce sunucusu")
                .setView(box)
                .setNegativeButton("VAZGEÇ", null)
                .setPositiveButton("BAŞLAT", null)
                .create();

        dialog.setOnShowListener(x -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            String r = room.getText().toString().trim();
            if (n.isEmpty() || r.isEmpty()) {
                toast("Sunucu ve oda adı gerekli.");
                return;
            }
            currentRoom = r;
            loadRoom(r);
            Intent i = new Intent(this, OfflineService.class)
                    .setAction(OfflineService.ACTION_START_SERVER);
            i.putExtra("serverName", n);
            i.putExtra("room", r);
            i.putExtra("password", password.getText().toString());
            startServiceCompat(i);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void showJoinDialog(WifiP2pDevice device) {
        LinearLayout box = fieldsBox();
        EditText room = field("Oda adı");
        room.setText("Genel");
        EditText password = field("Şifre (varsa)");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(room);
        box.addView(password);

        new android.app.AlertDialog.Builder(this)
                .setTitle(device.deviceName + " • Odaya katıl")
                .setView(box)
                .setNegativeButton("VAZGEÇ", null)
                .setPositiveButton("BAĞLAN", (d, w) -> {
                    pendingRoom = room.getText().toString().trim().isEmpty()
                            ? "Genel" : room.getText().toString().trim();
                    pendingPassword = password.getText().toString();
                    connect(device);
                }).show();
    }

    private LinearLayout fieldsBox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(20, 0, 20, 0);
        return l;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        return e;
    }

    private void connect(WifiP2pDevice device) {
        if (manager == null || channel == null || !nearbyGranted()) {
            requestPermissionsIfNeeded();
            return;
        }
        if (!locationModeEnabled()) {
            toast("Wi‑Fi Direct taraması için Konum hizmetini açın.");
            return;
        }

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.groupOwnerIntent = 0;
        setStatus("Yerel bağlantı kuruluyor…");

        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { setStatus("Wi‑Fi Direct bağlantısı kuruluyor…"); }
                @Override public void onFailure(int reason) { setStatus("Bağlantı başarısız: " + error(reason)); }
            });
        } catch (SecurityException e) {
            setStatus("Yakındaki Cihazlar izni gerekli");
        }
    }

    private void requestConnectionInfo() {
        if (manager == null || channel == null || !nearbyGranted()) return;
        try {
            manager.requestConnectionInfo(channel, info -> {
                if (!info.groupFormed || info.groupOwnerAddress == null) return;
                if (info.isGroupOwner) {
                    setStatus("✅ Offline sunucu grubu aktif");
                    return;
                }

                currentRoom = pendingRoom;
                loadRoom(currentRoom);
                Intent i = new Intent(this, OfflineService.class)
                        .setAction(OfflineService.ACTION_START_CLIENT);
                i.putExtra("host", info.groupOwnerAddress.getHostAddress());
                i.putExtra("room", pendingRoom);
                i.putExtra("password", pendingPassword);
                startServiceCompat(i);
            });
        } catch (SecurityException ignored) {}
    }

    private void sendText() {
        String value = input.getText().toString().trim();
        if (value.isEmpty()) return;
        Intent i = new Intent(this, OfflineService.class)
                .setAction(OfflineService.ACTION_SEND_TEXT);
        i.putExtra(OfflineService.EXTRA_TEXT, value);
        startServiceCompat(i);
        store.addText(value, currentRoom, false, System.currentTimeMillis());
        addTextBubble(value, false);
        input.setText("");
        beep(false);
    }

    private void pickImage() {
        Intent pick;
        if (Build.VERSION.SDK_INT >= 33) {
            pick = new Intent(MediaStore.ACTION_PICK_IMAGES);
            pick.setType("image/*");
        } else {
            pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.setType("image/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
        }
        pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(pick, PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_IMAGE || result != RESULT_OK || data == null || data.getData() == null) return;

        Uri uri = data.getData();
        if (Build.VERSION.SDK_INT < 33) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (Exception ignored) {}
        }

        Intent i = new Intent(this, OfflineService.class)
                .setAction(OfflineService.ACTION_SEND_IMAGE);
        i.setData(uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.putExtra(OfflineService.EXTRA_URI, uri);
        startServiceCompat(i);

        store.addImage(uri.toString(), currentRoom, false, System.currentTimeMillis());
        addImageBubble(uri, false);
    }

    private void loadRoom(String room) {
        currentRoom = room == null || room.isEmpty() ? "Genel" : room;
        if (roomTitle != null) roomTitle.setText("• " + currentRoom);
        if (messageList == null) return;
        messageList.removeAllViews();
        for (MessageStore.Message m : store.getAll(currentRoom)) {
            if ("image".equals(m.kind) && m.uri != null) addImageBubble(Uri.parse(m.uri), m.incoming);
            else addTextBubble(m.text == null ? "" : m.text, m.incoming);
        }
        scrollBottom();
    }

    private void addTextBubble(String value, boolean incoming) {
        TextView bubble = text(value, 16, Color.rgb(28, 38, 50), false);
        bubble.setPadding(16, 12, 16, 12);
        bubble.setBackgroundColor(incoming ? Color.WHITE : Color.rgb(218, 236, 255));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.gravity = incoming ? Gravity.START : Gravity.END;
        p.setMargins(6, 4, 6, 4);
        messageList.addView(bubble, p);
        scrollBottom();
    }

    private void addImageBubble(Uri uri, boolean incoming) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(8, 8, 8, 8);
        card.setBackgroundColor(incoming ? Color.WHITE : Color.rgb(218, 236, 255));

        ImageView image = new ImageView(this);
        image.setImageURI(uri);
        image.setAdjustViewBounds(true);
        card.addView(image, new LinearLayout.LayoutParams(310, 310));

        LinearLayout buttons = new LinearLayout(this);
        Button copy = new Button(this);
        copy.setText("KOPYALA");
        copy.setOnClickListener(v -> copyImage(uri));
        Button open = new Button(this);
        open.setText("AÇ");
        open.setOnClickListener(v -> openImage(uri));
        buttons.addView(copy, new LinearLayout.LayoutParams(0, 48, 1));
        buttons.addView(open, new LinearLayout.LayoutParams(0, 48, 1));
        card.addView(buttons);

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(330, -2);
        p.gravity = incoming ? Gravity.START : Gravity.END;
        p.setMargins(6, 4, 6, 4);
        messageList.addView(card, p);
        scrollBottom();
    }

    private void copyImage(Uri uri) {
        android.content.ClipboardManager cb =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newUri(getContentResolver(), "Sinyalce görsel", uri));
        toast("Görsel panoya kopyalandı");
    }

    private void openImage(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            toast("Görsel açılamadı");
        }
    }

    private void stopOfflineService() {
        Intent i = new Intent(this, OfflineService.class).setAction(OfflineService.ACTION_STOP);
        startServiceCompat(i);
        setStatus("Offline bağlantı durduruldu");
    }

    private void scrollBottom() {
        if (messageScroll != null) messageScroll.post(() -> messageScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private void startServiceCompat(Intent intent) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
    }

    private void setStatus(String value) {
        if (status != null && value != null) status.setText(value);
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private void beep(boolean incoming) {
        try {
            tone.startTone(incoming ? ToneGenerator.TONE_PROP_BEEP2 : ToneGenerator.TONE_PROP_BEEP,
                    incoming ? 150 : 100);
        } catch (Exception ignored) {}
    }

    private String deviceStatus(int value) {
        switch (value) {
            case WifiP2pDevice.AVAILABLE: return "Uygun";
            case WifiP2pDevice.INVITED: return "Davet";
            case WifiP2pDevice.CONNECTED: return "Bağlı";
            case WifiP2pDevice.FAILED: return "Hatalı";
            case WifiP2pDevice.UNAVAILABLE: return "Meşgul";
            default: return "Bekliyor";
        }
    }

    private String error(int reason) {
        switch (reason) {
            case WifiP2pManager.P2P_UNSUPPORTED: return "P2P desteklenmiyor";
            case WifiP2pManager.BUSY: return "Wi‑Fi Direct meşgul";
            case WifiP2pManager.ERROR: return "Wi‑Fi Direct hatası";
            default: return "kod " + reason;
        }
    }
}
