package com.copperbars.offlinewhatsapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
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

    private static final String PROFILE_PREFS = "sinyalce_profile";
    private static final String SESSION_PREFS = "sinyalce_service";
    private static final String NAME_KEY = "name";
    private static final int PORT = 45821;
    private static final int MAX_PACKET_BYTES = 12 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = MAX_PACKET_BYTES - 512;
    private static final int TYPE_HELLO = 1;
    private static final int TYPE_TEXT = 2;
    private static final int TYPE_IMAGE = 3;
    private static final int TYPE_ERROR = 4;
    private static final String UNIT = "\u001F";
    private static final String SERVICE_CHANNEL = "service";
    private static final String MESSAGE_CHANNEL = "message";
    private static final int SERVICE_NOTIFICATION = 4101;
    private static final int MESSAGE_NOTIFICATION = 4102;

    private final List<Client> clients = new CopyOnWriteArrayList<>();
    private volatile boolean running;
    private boolean serverMode;
    private String username = "Kullanıcı";
    private String room = "Genel";
    private String password = "";
    private String serverName = "Sinyalce Sunucusu";
    private String host = "";
    private ServerSocket serverSocket;
    private Client clientConnection;
    private NotificationManager notifications;
    private WifiP2pManager p2pManager;
    private WifiP2pManager.Channel p2pChannel;
    private SharedPreferences sessionPrefs;
    private MessageStore store;

    @Override
    public void onCreate() {
        super.onCreate();
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        sessionPrefs = getSharedPreferences(SESSION_PREFS, MODE_PRIVATE);
        store = new MessageStore(this);
        p2pManager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        if (p2pManager != null) {
            p2pChannel = p2pManager.initialize(this, getMainLooper(), () -> broadcastStatus("Wi‑Fi Direct service kanalı kesildi"));
        }
        refreshUsername();
        createChannels();
    }

    private void refreshUsername() {
        String value = getSharedPreferences(PROFILE_PREFS, MODE_PRIVATE).getString(NAME_KEY, "Kullanıcı");
        username = value == null || value.trim().isEmpty() ? "Kullanıcı" : value.trim();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        refreshUsername();
        if (intent == null) {
            restoreLastMode();
            return START_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            clearSavedSession();
            stopNetworking();
            stopWifiGroup();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START_SERVER.equals(action)) {
            serverMode = true;
            room = nonEmpty(intent.getStringExtra("room"), "Genel");
            password = valueOrEmpty(intent.getStringExtra("password"));
            serverName = nonEmpty(intent.getStringExtra("serverName"), "Sinyalce Sunucusu");
            host = "";
            saveServerSession();
            startForegroundNow("Sunucu hazırlanıyor • " + room);
            startServer();
            return START_STICKY;
        }

        if (ACTION_START_CLIENT.equals(action)) {
            serverMode = false;
            room = nonEmpty(intent.getStringExtra("room"), "Genel");
            password = valueOrEmpty(intent.getStringExtra("password"));
            host = valueOrEmpty(intent.getStringExtra("host"));
            saveClientSession();
            startForegroundNow("Bağlanıyor • " + room);
            if (!host.isEmpty()) startClient(host);
            return START_STICKY;
        }

        if (ACTION_SEND_TEXT.equals(action)) {
            sendText(intent.getStringExtra(EXTRA_TEXT));
            return START_STICKY;
        }

        if (ACTION_SEND_IMAGE.equals(action)) {
            Uri uri = getUriExtra(intent);
            if (uri != null) new Thread(() -> sendImage(uri), "SinyalceImageSender").start();
            return START_STICKY;
        }

        return START_STICKY;
    }

    private Uri getUriExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) return intent.getParcelableExtra(EXTRA_URI, Uri.class);
        return intent.getParcelableExtra(EXTRA_URI);
    }

    private void restoreLastMode() {
        room = nonEmpty(sessionPrefs.getString("room", "Genel"), "Genel");
        password = valueOrEmpty(sessionPrefs.getString("password", ""));
        String mode = sessionPrefs.getString("mode", "");
        if ("server".equals(mode)) {
            serverMode = true;
            serverName = nonEmpty(sessionPrefs.getString("server", "Sinyalce Sunucusu"), "Sinyalce Sunucusu");
            refreshUsername();
            startForegroundNow("Sunucu yeniden başlatılıyor • " + room);
            startServer();
        } else if ("client".equals(mode)) {
            serverMode = false;
            host = valueOrEmpty(sessionPrefs.getString("host", ""));
            refreshUsername();
            if (!host.isEmpty()) {
                startForegroundNow("Bağlantı yeniden kuruluyor • " + room);
                startClient(host);
            } else {
                stopSelf();
            }
        } else {
            stopSelf();
        }
    }

    private void saveServerSession() {
        sessionPrefs.edit().putString("mode", "server").putString("room", room).putString("password", password).putString("server", serverName).apply();
    }

    private void saveClientSession() {
        sessionPrefs.edit().putString("mode", "client").putString("room", room).putString("password", password).putString("host", host).apply();
    }

    private void clearSavedSession() {
        sessionPrefs.edit().clear().apply();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel service = new NotificationChannel(SERVICE_CHANNEL, "Sinyalce bağlantısı", NotificationManager.IMPORTANCE_LOW);
        service.setDescription("Aktif offline sunucu veya bağlantı");
        service.setShowBadge(false);
        notifications.createNotificationChannel(service);

        NotificationChannel message = new NotificationChannel(MESSAGE_CHANNEL, "Sinyalce mesajları", NotificationManager.IMPORTANCE_DEFAULT);
        message.setDescription("Uygulama arka plandayken gelen mesajlar");
        notifications.createNotificationChannel(message);
    }

    private void startForegroundNow(String text) {
        Intent launch = new Intent(this, SinyalceActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, SERVICE_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_wifi)
                .setContentTitle("Sinyalce")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(SERVICE_NOTIFICATION, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(SERVICE_NOTIFICATION, builder.build());
        }
    }

    private void startServer() {
        stopNetworking();
        running = true;
        try {
            serverSocket = new ServerSocket(PORT);
        } catch (Exception e) {
            broadcastStatus("Sunucu socket açılamadı: " + safe(e));
            return;
        }
        ensureWifiGroup();
        broadcastStatus("SUNUCU AKTİF • " + serverName + " • Oda: " + room);
        new Thread(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    Client client = new Client(socket);
                    clients.add(client);
                    client.start();
                } catch (Exception e) {
                    if (running) broadcastStatus("Sunucu bağlantı hatası: " + safe(e));
                }
            }
        }, "SinyalceAccept").start();
    }

    private void ensureWifiGroup() {
        if (p2pManager == null || p2pChannel == null) return;
        try {
            p2pManager.createGroup(p2pChannel, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { broadcastStatus("✅ Wi‑Fi Direct grubu aktif • Oda: " + room); }
                @Override public void onFailure(int reason) {
                    if (reason == WifiP2pManager.BUSY) broadcastStatus("Wi‑Fi Direct zaten meşgul • mevcut yerel grup kullanılabilir");
                    else broadcastStatus("Wi‑Fi Direct grubu açılamadı: " + reason);
                }
            });
        } catch (SecurityException e) {
            broadcastStatus("Yakındaki Cihazlar izni gerekli");
        }
    }

    private void stopWifiGroup() {
        if (p2pManager == null || p2pChannel == null) return;
        try { p2pManager.removeGroup(p2pChannel, null); } catch (Exception ignored) {}
    }

    private void startClient(String targetHost) {
        stopNetworking();
        running = true;
        new Thread(() -> {
            try {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(targetHost, PORT), 10000);
                clientConnection = new Client(socket);
                clientConnection.start();
                String hello = username + UNIT + room + UNIT + password;
                clientConnection.writePacket(TYPE_HELLO, hello.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                broadcastStatus("Bağlantı başarısız: " + safe(e));
            }
        }, "SinyalceClient").start();
    }

    private void sendText(String text) {
        if (text == null || text.trim().isEmpty()) return;
        refreshUsername();
        byte[] data = packText(username, text.trim());
        if (serverMode) {
            broadcastPacket(TYPE_TEXT, data, null);
        } else if (clientConnection != null && clientConnection.accepted) {
            clientConnection.writePacket(TYPE_TEXT, data);
        } else {
            broadcastStatus("Mesaj gönderilemedi • bağlantı yok");
        }
    }

    private void sendImage(Uri uri) {
        try {
            refreshUsername();
            String mime = getContentResolver().getType(uri);
            if (mime == null || !mime.startsWith("image/")) throw new IllegalStateException("Desteklenmeyen görsel türü");
            byte[] image;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("Görsel açılamadı");
                image = readAll(in, MAX_IMAGE_BYTES);
            }
            byte[] payload = packImage(username, mime, image);
            if (serverMode) {
                broadcastPacket(TYPE_IMAGE, payload, null);
            } else if (clientConnection != null && clientConnection.accepted) {
                clientConnection.writePacket(TYPE_IMAGE, payload);
            } else {
                throw new IllegalStateException("Bağlantı yok");
            }
        } catch (Exception e) {
            broadcastStatus("Görsel gönderilemedi: " + safe(e));
        }
    }

    private void handlePacket(Client from, int type, byte[] payload) {
        if (type == TYPE_HELLO) {
            String[] parts = new String(payload, StandardCharsets.UTF_8).split(UNIT, -1);
            if (!serverMode) {
                String server = parts.length > 0 ? parts[0] : "Sinyalce";
                String serverRoom = parts.length > 1 ? parts[1] : room;
                String result = parts.length > 2 ? parts[2] : "";
                from.accepted = room.equals(serverRoom) && "OK".equals(result);
                if (from.accepted) broadcastStatus("✅ " + server + " sunucusuna bağlandı • Oda: " + room);
                else broadcastStatus("Sunucu doğrulaması başarısız");
                return;
            }

            String requestedName = parts.length > 0 ? nonEmpty(parts[0], "Kullanıcı") : "Kullanıcı";
            String requestedRoom = parts.length > 1 ? parts[1] : "";
            String requestedPassword = parts.length > 2 ? parts[2] : "";
            if (!room.equals(requestedRoom) || !password.equals(requestedPassword)) {
                from.writePacket(TYPE_ERROR, "Oda adı veya şifre yanlış.".getBytes(StandardCharsets.UTF_8));
                from.close();
                return;
            }
            from.accepted = true;
            from.remoteName = requestedName;
            from.writePacket(TYPE_HELLO, (serverName + UNIT + room + UNIT + "OK").getBytes(StandardCharsets.UTF_8));
            broadcastStatus("✅ " + requestedName + " odaya katıldı");
            return;
        }

        if (type == TYPE_ERROR) {
            broadcastStatus(new String(payload, StandardCharsets.UTF_8));
            return;
        }
        if (!from.accepted) return;

        if (type == TYPE_TEXT) {
            ParsedText parsed = parseText(payload);
            String display = parsed.name + ": " + parsed.text;
            if (serverMode) broadcastPacket(TYPE_TEXT, payload, from);
            persistIncomingText(display);
            notifyIncomingText(display);
        } else if (type == TYPE_IMAGE) {
            try {
                ParsedImage image = parseImage(payload);
                Uri saved = saveIncomingImage(image.mime, image.bytes);
                if (serverMode) broadcastPacket(TYPE_IMAGE, payload, from);
                String display = image.name + " • 📷 Görsel gönderdi";
                persistIncomingText(display);
                notifyIncomingText(display);
                persistIncomingImage(saved);
                notifyIncomingImage(saved);
            } catch (Exception e) {
                broadcastStatus("Görsel alınamadı: " + safe(e));
            }
        }
    }

    private void persistIncomingText(String display) {
        if (!AppState.isForeground()) store.addText(display, room, true, System.currentTimeMillis());
    }

    private void persistIncomingImage(Uri uri) {
        if (!AppState.isForeground()) store.addImage(uri.toString(), room, true, System.currentTimeMillis());
    }

    private void notifyIncomingText(String display) {
        broadcast(ACTION_TEXT, i -> i.putExtra(EXTRA_TEXT, display).putExtra(EXTRA_ROOM, room));
        if (!AppState.isForeground()) showMessageNotification(display);
    }

    private void notifyIncomingImage(Uri uri) {
        broadcast(ACTION_IMAGE, i -> {
            i.putExtra(EXTRA_URI, uri);
            i.putExtra(EXTRA_ROOM, room);
        });
    }

    private void broadcastPacket(int type, byte[] payload, Client except) {
        for (Client client : clients) {
            if (client != except && client.accepted) client.writePacket(type, payload);
        }
    }

    private void showMessageNotification(String text) {
        Intent launch = new Intent(this, SinyalceActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 1, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, MESSAGE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(room)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pending);
        notifications.notify(MESSAGE_NOTIFICATION, builder.build());
    }

    private void broadcastStatus(String text) {
        broadcast(ACTION_STATUS, i -> i.putExtra(EXTRA_STATUS, text).putExtra(EXTRA_ROOM, room));
        startForegroundNow(text);
    }

    private interface Editor { void edit(Intent intent); }

    private void broadcast(String action, Editor editor) {
        Intent i = new Intent(action).setPackage(getPackageName());
        editor.edit(i);
        sendBroadcast(i);
    }

    private void stopNetworking() {
        running = false;
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) {}
            serverSocket = null;
        }
        for (Client client : clients) client.close();
        clients.clear();
        if (clientConnection != null) {
            clientConnection.close();
            clientConnection = null;
        }
    }

    @Override
    public void onDestroy() {
        stopNetworking();
        if (store != null) store.close();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private final class Client {
        final Socket socket;
        final DataInputStream in;
        final DataOutputStream out;
        volatile boolean accepted;
        volatile String remoteName = "Kullanıcı";

        Client(Socket socket) throws Exception {
            this.socket = socket;
            this.in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        void start() {
            Thread reader = new Thread(() -> {
                try {
                    while (running && !socket.isClosed()) {
                        int type = in.readInt();
                        int length = in.readInt();
                        if (length <= 0 || length > MAX_PACKET_BYTES) throw new IllegalStateException("Geçersiz paket boyutu");
                        byte[] payload = new byte[length];
                        in.readFully(payload);
                        handlePacket(this, type, payload);
                    }
                } catch (Exception ignored) {
                } finally {
                    close();
                }
            }, "SinyalceReader");
            reader.start();
        }

        synchronized void writePacket(int type, byte[] payload) {
            if (socket.isClosed()) return;
            try {
                out.writeInt(type);
                out.writeInt(payload.length);
                out.write(payload);
                out.flush();
            } catch (Exception e) {
                close();
            }
        }

        void close() {
            try { socket.close(); } catch (Exception ignored) {}
            clients.remove(this);
            if (this == clientConnection) clientConnection = null;
        }
    }

    private static byte[] packText(String name, String text) {
        return (nonEmpty(name, "Kullanıcı") + UNIT + text).getBytes(StandardCharsets.UTF_8);
    }

    private static ParsedText parseText(byte[] payload) {
        String raw = new String(payload, StandardCharsets.UTF_8);
        int cut = raw.indexOf(UNIT);
        if (cut < 0) return new ParsedText("Kullanıcı", raw);
        return new ParsedText(nonEmpty(raw.substring(0, cut), "Kullanıcı"), raw.substring(cut + UNIT.length()));
    }

    private static byte[] packImage(String name, String mime, byte[] image) {
        byte[] meta = (nonEmpty(name, "Kullanıcı") + UNIT + mime + UNIT).getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[meta.length + image.length];
        System.arraycopy(meta, 0, result, 0, meta.length);
        System.arraycopy(image, 0, result, meta.length, image.length);
        return result;
    }

    private static ParsedImage parseImage(byte[] payload) {
        int first = indexOf(payload, (byte) 0x1F, 0);
        int second = indexOf(payload, (byte) 0x1F, first + 1);
        if (first <= 0 || second <= first + 1) throw new IllegalStateException("Görsel üstbilgisi bozuk");
        String name = new String(payload, 0, first, StandardCharsets.UTF_8);
        String mime = new String(payload, first + 1, second - first - 1, StandardCharsets.UTF_8);
        byte[] bytes = new byte[payload.length - second - 1];
        System.arraycopy(payload, second + 1, bytes, 0, bytes.length);
        if (bytes.length > MAX_IMAGE_BYTES) throw new IllegalStateException("Görsel çok büyük");
        return new ParsedImage(nonEmpty(name, "Kullanıcı"), mime, bytes);
    }

    private static int indexOf(byte[] data, byte value, int start) {
        for (int i = Math.max(0, start); i < data.length; i++) if (data[i] == value) return i;
        return -1;
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

    private static byte[] readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > max) throw new IllegalStateException("Dosya çok büyük");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static final class ParsedText {
        final String name;
        final String text;
        ParsedText(String name, String text) { this.name = name; this.text = text; }
    }

    private static final class ParsedImage {
        final String name;
        final String mime;
        final byte[] bytes;
        ParsedImage(String name, String mime, byte[] bytes) { this.name = name; this.mime = mime; this.bytes = bytes; }
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String safe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
