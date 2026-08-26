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
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
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

    private static final String CHANNEL_ID = "service";
    private static final String CHAT_CHANNEL_ID = "chat";
    private static final int NOTIFICATION_ID = 4101;
    private static final int CHAT_NOTIFICATION_ID = 4102;
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
    private Thread acceptThread;
    private NotificationManager notifications;

    @Override
    public void onCreate() {
        super.onCreate();
        notifications = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createChannels();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            stopNetworking();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START_SERVER.equals(action)) {
            room = nonEmpty(intent.getStringExtra("room"), "Genel");
            password = intent.getStringExtra("password");
            if (password == null) password = "";
            serverName = nonEmpty(intent.getStringExtra("serverName"), "Sinyalce Sunucusu");
            serverMode = true;
            startForegroundNow("Sunucu hazırlanıyor • " + room);
            startServer();
            return START_STICKY;
        }

        if (ACTION_START_CLIENT.equals(action)) {
            String host = intent.getStringExtra("host");
            room = nonEmpty(intent.getStringExtra("room"), "Genel");
            password = intent.getStringExtra("password");
            if (password == null) password = "";
            serverMode = false;
            startForegroundNow("Bağlanıyor • " + room);
            if (host != null) startClient(host);
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

    private void startForegroundNow(String text) {
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_wifi)
                .setContentTitle("Sinyalce")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        startForeground(NOTIFICATION_ID, b.build());
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel service = new NotificationChannel(CHANNEL_ID, "Sinyalce bağlantısı", NotificationManager.IMPORTANCE_LOW);
        service.setDescription("Aktif offline bağlantı veya sunucu durumu");
        service.setShowBadge(false);
        notifications.createNotificationChannel(service);

        NotificationChannel chat = new NotificationChannel(CHAT_CHANNEL_ID, "Yeni mesajlar", NotificationManager.IMPORTANCE_DEFAULT);
        chat.setDescription("Uygulama arka plandayken gelen mesajlar");
        notifications.createNotificationChannel(chat);
    }

    private void startServer() {
        stopNetworking();
        running = true;
        try {
            serverSocket = new ServerSocket(PORT);
        } catch (Exception e) {
            broadcastStatus("Sunucu açılamadı: " + safe(e));
            return;
        }
        broadcastStatus("SUNUCU AKTİF • " + serverName + " • Oda: " + room);
        acceptThread = new Thread(() -> {
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
        }, "SinyalceAccept");
        acceptThread.start();
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
            } catch (Exception e) {
                broadcastStatus("Bağlantı başarısız: " + safe(e));
            }
        }, "SinyalceClient").start();
    }

    private void sendText(String text) {
        if (text == null || text.trim().isEmpty()) return;
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        if (serverMode) {
            broadcastPacket(TYPE_TEXT, data, null);
        } else if (clientConnection != null) {
            clientConnection.writePacket(TYPE_TEXT, data);
        }
        broadcastStatus("Mesaj gönderildi");
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
            broadcastStatus("Görsel gönderildi");
        } catch (Exception e) {
            broadcastStatus("Görsel gönderilemedi: " + safe(e));
        }
    }

    private void handlePacket(Client from, int type, byte[] payload) {
        if (type == TYPE_HELLO) {
            String hello = new String(payload, StandardCharsets.UTF_8);
            String[] p = hello.split("\\u001F", -1);
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
                broadcastStatus("Yeni cihaz katıldı");
            } else {
                from.accepted = true;
                broadcastStatus("Bağlantı aktif • Oda: " + room);
            }
            return;
        }

        if (type == TYPE_ERROR) {
            broadcastStatus(new String(payload, StandardCharsets.UTF_8));
            return;
        }

        if (!from.accepted && serverMode) return;

        if (type == TYPE_TEXT) {
            String text = new String(payload, StandardCharsets.UTF_8);
            if (serverMode) {
                broadcastPacket(TYPE_TEXT, payload, from);
                notifyIncomingText(text);
            } else {
                notifyIncomingText(text);
            }
        } else if (type == TYPE_IMAGE) {
            try {
                ParsedImage p = parseImage(payload);
                Uri saved = saveIncomingImage(p.mime, p.bytes);
                if (serverMode) broadcastPacket(TYPE_IMAGE, payload, from);
                notifyIncomingImage(saved);
            } catch (Exception e) {
                broadcastStatus("Görsel alınamadı: " + safe(e));
            }
        }
    }

    private void broadcastPacket(int type, byte[] payload, Client except) {
        for (Client c : clients) {
            if (c != except && c.accepted) c.writePacket(type, payload);
        }
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

    private void notifyIncomingText(String text) {
        broadcast(ACTION_TEXT, intent -> intent.putExtra(EXTRA_TEXT, text).putExtra(EXTRA_ROOM, room));
        if (!MainActivity.isAppForeground()) showMessageNotification(text);
    }

    private void notifyIncomingImage(Uri uri) {
        broadcast(ACTION_IMAGE, intent -> intent.putExtra(EXTRA_URI, uri).putExtra(EXTRA_ROOM, room));
        if (!MainActivity.isAppForeground()) showMessageNotification("📷 Görsel gönderildi");
    }

    private void showMessageNotification(String text) {
        Intent launch = new Intent(this, MainActivity.class);
        launch.putExtra("openRoom", room);
        PendingIntent pending = PendingIntent.getActivity(this, 1, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = new Notification.Builder(this, CHAT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(room)
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pending);
        notifications.notify(CHAT_NOTIFICATION_ID, b.build());
    }

    private void broadcastStatus(String text) {
        broadcast(ACTION_STATUS, intent -> intent.putExtra(EXTRA_STATUS, text).putExtra(EXTRA_ROOM, room));
        startForegroundNow(text);
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
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) {}
            serverSocket = null;
        }
        for (Client c : clients) c.close();
        clients.clear();
        if (clientConnection != null) {
            clientConnection.close();
            clientConnection = null;
        }
    }

    @Override
    public void onDestroy() {
        stopNetworking();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

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
            Thread reader = new Thread(() -> {
                try {
                    String hello = room + "\u001F" + password;
                    writePacket(TYPE_HELLO, hello.getBytes(StandardCharsets.UTF_8));
                    while (running && !socket.isClosed()) {
                        int type = in.readInt();
                        int len = in.readInt();
                        if (len <= 0 || len > MAX_PACKET) throw new IllegalStateException("Geçersiz paket");
                        byte[] payload = new byte[len];
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
            if (out == null || socket.isClosed()) return;
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
            if (serverMode) clients.remove(this);
            if (!serverMode && this == clientConnection) clientConnection = null;
        }
    }

    private static byte[] readAll(InputStream in, int max) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
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
        if (sep <= 0) throw new IllegalStateException("Görsel üstbilgisi bozuk");
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

    private static String nonEmpty(String v, String fallback) {
        return v == null || v.trim().isEmpty() ? fallback : v.trim();
    }

    private static String safe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
