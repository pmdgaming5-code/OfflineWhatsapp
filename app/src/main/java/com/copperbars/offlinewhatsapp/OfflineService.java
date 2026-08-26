package com.copperbars.offlinewhatsapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class OfflineService extends Service {
    public static final String ACTION_START_SERVER = "com.copperbars.offlinewhatsapp.START_SERVER";
    public static final String ACTION_START_CLIENT = "com.copperbars.offlinewhatsapp.START_CLIENT";
    public static final String ACTION_STOP = "com.copperbars.offlinewhatsapp.STOP";
    public static final String ACTION_SEND_TEXT = "com.copperbars.offlinewhatsapp.SEND_TEXT";
    public static final String ACTION_SEND_IMAGE = "com.copperbars.offlinewhatsapp.SEND_IMAGE";
    public static final String ACTION_STATUS = "com.copperbars.offlinewhatsapp.STATUS";
    public static final String ACTION_TEXT = "com.copperbars.offlinewhatsapp.TEXT";
    public static final String ACTION_IMAGE = "com.copperbars.offlinewhatsapp.IMAGE";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_ROOM = "room";
    public static final String EXTRA_STATUS = "status";

    private static final String PREFS = "sinyalce_service";
    private static final String P_MODE = "mode";
    private static final String P_ROOM = "room";
    private static final String P_PASSWORD = "password";
    private static final String P_SERVER = "server";
    private static final String P_HOST = "host";
    private static final String SERVICE_CHANNEL = "service";
    private static final String MESSAGE_CHANNEL = "message";
    private static final int SERVICE_NOTIFICATION = 4101;
    private static final int MESSAGE_NOTIFICATION = 4102;
    private static final int PORT = 45821;
    private static final int MAX_PACKET = 12 * 1024 * 1024;
    private static final int TYPE_HELLO = 1;
    private static final int TYPE_TEXT = 2;
    private static final int TYPE_IMAGE = 3;
    private static final int TYPE_ERROR = 4;

    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private boolean serverMode;
    private String room = "Genel";
    private String password = "";
    private String serverName = "Sinyalce Sunucusu";
    private ServerSocket serverSocket;
    private Client clientConnection;
    private NotificationManager notificationManager;
    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel wifiChannel;
    private SharedPreferences prefs;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        wifiP2pManager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        if (wifiP2pManager != null) {
            wifiChannel = wifiP2pManager.initialize(this, getMainLooper(), () -> broadcastStatus("Wi‑Fi Direct service kanalı kesildi"));
        }
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            restoreLastMode();
            return START_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            prefs.edit().clear().apply();
            stopNetworking();
            stopWifiGroup();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START_SERVER.equals(action)) {
            serverMode = true;
            room = nonEmpty(intent.getStringExtra("room"), "Genel");
            password = intent.getStringExtra("password");
            if (password == null) password = "";
            serverName = nonEmpty(intent.getStringExtra("serverName"), "Sinyalce Sunucusu");
            prefs.edit().putString(P_MODE, "server").putString(P_ROOM, room).putString(P_PASSWORD, password).putString(P_SERVER, serverName).apply();
            startForegroundNow("Sunucu aktifleşiyor • " + room);
            startServer();
            return START_STICKY;
        }
        if (ACTION_START_CLIENT.equals(action)) {
            serverMode = false;
            String host = intent.getStringExtra("host");
            room = nonEmpty(intent.getStringExtra("room"), "Genel");
            password = intent.getStringExtra("password");
            if (password == null) password = "";
            prefs.edit().putString(P_MODE, "client").putString(P_ROOM, room).putString(P_PASSWORD, password).putString(P_HOST, host == null ? "" : host).apply();
            startForegroundNow("Bağlanıyor • " + room);
            if (host != null && !host.isEmpty()) startClient(host);
            return START_STICKY;
        }
        if (ACTION_SEND_TEXT.equals(action)) {
            sendText(intent.getStringExtra(EXTRA_TEXT));
            return START_STICKY;
        }
        if (ACTION_SEND_IMAGE.equals(action)) {
            Uri uri = intent.getParcelableExtra(EXTRA_URI);
            if (uri != null) new Thread(() -> sendImage(uri), "SinyalceImageSender").start();
            return START_STICKY;
        }
        return START_STICKY;
    }

    private void restoreLastMode() {
        String mode = prefs.getString(P_MODE, "");
        room = nonEmpty(prefs.getString(P_ROOM, "Genel"), "Genel");
        password = prefs.getString(P_PASSWORD, "");
        if ("server".equals(mode)) {
            serverMode = true;
            serverName = nonEmpty(prefs.getString(P_SERVER, "Sinyalce Sunucusu"), "Sinyalce Sunucusu");
            startForegroundNow("Sunucu yeniden başlatılıyor • " + room);
            startServer();
        } else if ("client".equals(mode)) {
            serverMode = false;
            String host = prefs.getString(P_HOST, "");
            if (!host.isEmpty()) {
                startForegroundNow("Bağlantı yeniden kuruluyor • " + room);
                startClient(host);
            } else stopSelf();
        } else stopSelf();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel service = new NotificationChannel(SERVICE_CHANNEL, "Sinyalce bağlantısı", NotificationManager.IMPORTANCE_LOW);
        service.setDescription("Aktif offline sunucu veya bağlantı");
        service.setShowBadge(false);
        notificationManager.createNotificationChannel(service);
        NotificationChannel message = new NotificationChannel(MESSAGE_CHANNEL, "Sinyalce mesajları", NotificationManager.IMPORTANCE_DEFAULT);
        message.setDescription("Uygulama arka plandayken gelen mesajlar");
        notificationManager.createNotificationChannel(message);
    }

    private void startForegroundNow(String text) {
        Intent launch = new Intent(this, SinyalceActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, SERVICE_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_wifi)
                .setContentTitle("Sinyalce")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (Build.VERSION.SDK_INT >= 29) startForeground(SERVICE_NOTIFICATION, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(SERVICE_NOTIFICATION, builder.build());
    }

    private void startServer() {
        stopNetworking();
        running = true;
        try { serverSocket = new ServerSocket(PORT); }
        catch (Exception e) { broadcastStatus("Sunucu socket açılamadı: " + safe(e)); return; }
        ensureWifiGroup();
        broadcastStatus("SUNUCU AKTİF • " + serverName + " • Oda: " + room);
        new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    Client c = new Client(socket);
                    clients.add(c);
                    c.start();
                } catch (Exception e) {
                    if (running) broadcastStatus("Sunucu bağlantı hatası: " + safe(e));
                }
            }
        }, "SinyalceAccept").start();
    }

    private void ensureWifiGroup() {
        if (wifiP2pManager == null || wifiChannel == null) return;
        try {
            wifiP2pManager.removeGroup(wifiChannel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { createWifiGroup(); }
                @Override public void onFailure(int reason) { createWifiGroup(); }
            });
        } catch (Exception e) { createWifiGroup(); }
    }

    private void createWifiGroup() {
        if (!running || !serverMode || wifiP2pManager == null || wifiChannel == null) return;
        try {
            wifiP2pManager.createGroup(wifiChannel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { broadcastStatus("✅ Wi‑Fi Direct grubu aktif • Oda: " + room); }
                @Override public void onFailure(int reason) { broadcastStatus("Wi‑Fi Direct grubu açılamadı: kod " + reason); }
            });
        } catch (Exception e) { broadcastStatus("Wi‑Fi Direct grubu açılamadı: " + safe(e)); }
    }

    private void stopWifiGroup() {
        if (wifiP2pManager != null && wifiChannel != null) {
            try { wifiP2pManager.removeGroup(wifiChannel, null); } catch (Exception ignored) {}
        }
    }

    private void startClient(String host) {
        stopNetworking();
        running = true;
        new Thread(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(host, PORT), 10000);
                clientConnection = new Client(socket);
                clientConnection.start();
            } catch (Exception e) { broadcastStatus("Bağlantı başarısız: " + safe(e)); }
        }, "SinyalceClient").start();
    }

    private void sendText(String text) {
        if (text == null || text.trim().isEmpty()) return;
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (serverMode) broadcastPacket(TYPE_TEXT, data, null);
        else if (clientConnection != null) clientConnection.writePacket(TYPE_TEXT, data);
        else broadcastStatus("Mesaj gönderilemedi • bağlı cihaz yok");
    }

    private void sendImage(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Görsel açılamadı");
            String mime = getContentResolver().getType(uri);
            if (mime == null || !mime.startsWith("image/")) throw new IllegalStateException("Desteklenmeyen görsel türü");
            byte[] image = readAll(in, MAX_PACKET);
            byte[] payload = joinUtf8(mime, image);
            if (serverMode) broadcastPacket(TYPE_IMAGE, payload, null);
            else if (clientConnection != null) clientConnection.writePacket(TYPE_IMAGE, payload);
            else throw new IllegalStateException("Bağlantı yok");
        } catch (Exception e) { broadcastStatus("Görsel gönderilemedi: " + safe(e)); }
    }

    private void handlePacket(Client from, int type, byte[] payload) {
        if (type == TYPE_HELLO) {
            String[] p = new String(payload, StandardCharsets.UTF_8).split("\\u001F", -1);
            String requestedRoom = p.length > 0 ? p[0] : "";
            String requestedPassword = p.length > 1 ? p[1] : "";
            if (serverMode) {
                if (!room.equals(requestedRoom) || !password.equals(requestedPassword)) {
                    from.writePacket(TYPE_ERROR, "Oda adı veya şifre yanlış.".getBytes(StandardCharsets.UTF_8));
                    from.close();
                    return;
                }
                from.accepted = true;
                from.writePacket(TYPE_HELLO, (room + "\u001FOK").getBytes(StandardCharsets.UTF_8));
                broadcastStatus("Yeni cihaz odaya katıldı");
            } else {
                from.accepted = true;
                broadcastStatus("✅ Odaya bağlandı • " + room);
            }
            return;
        }
        if (type == TYPE_ERROR) {
            broadcastStatus(new String(payload, StandardCharsets.UTF_8));
            return;
        }
        if (!from.accepted && serverMode) return;
        if (type == TYPE_TEXT) {
            String value = new String(payload, StandardCharsets.UTF_8);
            if (serverMode) broadcastPacket(TYPE_TEXT, payload, from);
            notifyIncomingText(value);
        } else if (type == TYPE_IMAGE) {
            try {
                ParsedImage p = parseImage(payload);
                Uri saved = saveIncomingImage(p.mime, p.bytes);
                if (serverMode) broadcastPacket(TYPE_IMAGE, payload, from);
                notifyIncomingImage(saved);
            } catch (Exception e) { broadcastStatus("Görsel alınamadı: " + safe(e)); }
        }
    }

    private void broadcastPacket(int type, byte[] payload, Client except) {
        for (Client c : clients) if (c != except && c.accepted) c.writePacket(type, payload);
    }

    private Uri saveIncomingImage(String mime, byte[] bytes) throws Exception {
        ContentResolver resolver = getContentResolver();
        String ext = mime.endsWith("png") ? ".png" : mime.endsWith("webp") ? ".webp" : ".jpg";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "Sinyalce_" + System.currentTimeMillis() + ext);
        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Sinyalce");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IllegalStateException("Galeri kaydı oluşturulamadı");
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IllegalStateException("Galeri çıkışı açılamadı");
            out.write(bytes);
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        }
        return uri;
    }

    private void notifyIncomingText(String value) {
        broadcast(ACTION_TEXT, i -> i.putExtra(EXTRA_TEXT, value).putExtra(EXTRA_ROOM, room));
        if (!AppState.isForeground()) showMessageNotification(value);
    }

    private void notifyIncomingImage(Uri uri) {
        broadcast(ACTION_IMAGE, i -> i.putExtra(EXTRA_URI, uri).putExtra(EXTRA_ROOM, room));
        if (!AppState.isForeground()) showMessageNotification("📷 Görsel gönderildi");
    }

    private void showMessageNotification(String value) {
        Intent launch = new Intent(this, SinyalceActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 1, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, MESSAGE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(room)
                .setContentText(value)
                .setAutoCancel(true)
                .setContentIntent(pending)
                .build();
        notificationManager.notify(MESSAGE_NOTIFICATION, notification);
    }

    private void broadcastStatus(String value) {
        broadcast(ACTION_STATUS, i -> i.putExtra(EXTRA_STATUS, value).putExtra(EXTRA_ROOM, room));
        startForegroundNow(value);
    }

    private interface IntentEditor { void edit(Intent intent); }
    private void broadcast(String action, IntentEditor editor) {
        Intent i = new Intent(action);
        i.setPackage(getPackageName());
        editor.edit(i);
        sendBroadcast(i);
    }

    private void stopNetworking() {
        running = false;
        if (serverSocket != null) { try { serverSocket.close(); } catch (Exception ignored) {} serverSocket = null; }
        for (Client c : clients) c.close();
        clients.clear();
        if (clientConnection != null) { clientConnection.close(); clientConnection = null; }
    }

    @Override public void onDestroy() { stopNetworking(); stopWifiGroup(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }

    private final class Client {
        final Socket socket;
        DataInputStream in;
        DataOutputStream out;
        volatile boolean accepted;
        Client(Socket socket) {
            this.socket = socket;
            try {
                in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            } catch (Exception e) { close(); }
        }
        void start() {
            if (in == null || out == null) return;
            new Thread(() -> {
                try {
                    if (!serverMode) {
                        String hello = room + "\u001F" + password;
                        writePacket(TYPE_HELLO, hello.getBytes(StandardCharsets.UTF_8));
                    }
                    while (running && !socket.isClosed()) {
                        int type = in.readInt();
                        int len = in.readInt();
                        if (len <= 0 || len > MAX_PACKET) throw new IllegalStateException("Geçersiz paket");
                        byte[] payload = new byte[len];
                        in.readFully(payload);
                        handlePacket(this, type, payload);
                    }
                } catch (Exception ignored) { }
                finally { close(); }
            }, "SinyalceReader").start();
        }
        synchronized void writePacket(int type, byte[] payload) {
            if (out == null || socket.isClosed()) return;
            try { out.writeInt(type); out.writeInt(payload.length); out.write(payload); out.flush(); }
            catch (Exception e) { close(); }
        }
        void close() {
            try { socket.close(); } catch (Exception ignored) {}
            if (serverMode) clients.remove(this);
            if (!serverMode && this == clientConnection) clientConnection = null;
        }
    }

    private static byte[] readAll(InputStream input, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int n;
        while ((n = input.read(buffer)) != -1) {
            total += n;
            if (total > max) throw new IllegalStateException("Görsel 12 MB sınırını aşıyor");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static byte[] joinUtf8(String mime, byte[] bytes) {
        byte[] meta = (mime + "\u001F").getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[meta.length + bytes.length];
        System.arraycopy(meta, 0, result, 0, meta.length);
        System.arraycopy(bytes, 0, result, meta.length, bytes.length);
        return result;
    }

    private static ParsedImage parseImage(byte[] payload) {
        int sep = -1;
        for (int i = 0; i < payload.length; i++) if (payload[i] == 0x1F) { sep = i; break; }
        if (sep <= 0) throw new IllegalStateException("Görsel paketi bozuk");
        String mime = new String(payload, 0, sep, StandardCharsets.UTF_8);
        byte[] bytes = new byte[payload.length - sep - 1];
        System.arraycopy(payload, sep + 1, bytes, 0, bytes.length);
        return new ParsedImage(mime, bytes);
    }

    private static final class ParsedImage {
        final String mime;
        final byte[] bytes;
        ParsedImage(String mime, byte[] bytes) { this.mime = mime; this.bytes = bytes; }
    }

    private static String nonEmpty(String value, String fallback) { return value == null || value.trim().isEmpty() ? fallback : value.trim(); }
    private static String safe(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
}
