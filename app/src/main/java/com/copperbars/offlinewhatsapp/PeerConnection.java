package com.copperbars.offlinewhatsapp;

import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public final class PeerConnection {
    public interface Listener {
        void onConnected();
        void onMessage(String message);
        void onDisconnected();
        void onError(String error);
    }

    private static final int PORT = 45821;
    private static final int MAX_MESSAGE_BYTES = 32 * 1024;

    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean closed;
    private ServerSocket serverSocket;
    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;

    public PeerConnection(Listener listener) {
        this.listener = listener;
    }

    public synchronized void stop() {
        closed = true;
        closeQuietly(input);
        closeQuietly(output);
        closeQuietly(socket);
        closeQuietly(serverSocket);
        input = null;
        output = null;
        socket = null;
        serverSocket = null;
    }

    public void startServer() {
        stop();
        closed = false;

        new Thread(() -> {
            try {
                ServerSocket ss = new ServerSocket(PORT);
                synchronized (PeerConnection.this) {
                    if (closed) {
                        closeQuietly(ss);
                        return;
                    }
                    serverSocket = ss;
                }
                Socket s = ss.accept();
                synchronized (PeerConnection.this) {
                    if (closed) {
                        closeQuietly(s);
                        return;
                    }
                    socket = s;
                    input = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                    output = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                }
                startReader();
                notifyMain(Listener::onConnected);
            } catch (Exception e) {
                if (!closed) notifyMain(l -> l.onError("Sunucu hatası: " + safeMessage(e)));
            }
        }, "OfflineWhatsappServer").start();
    }

    public void connectTo(String host) {
        stop();
        closed = false;

        new Thread(() -> {
            try {
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, PORT), 8000);
                synchronized (PeerConnection.this) {
                    if (closed) {
                        closeQuietly(s);
                        return;
                    }
                    socket = s;
                    input = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                    output = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
                }
                startReader();
                notifyMain(Listener::onConnected);
            } catch (Exception e) {
                if (!closed) notifyMain(l -> l.onError("Bağlantı hatası: " + safeMessage(e)));
            }
        }, "OfflineWhatsappClient").start();
    }

    public void send(String text) {
        final DataOutputStream out;
        synchronized (this) {
            out = output;
        }
        if (out == null || closed) {
            notifyMain(l -> l.onError("Bağlantı yok."));
            return;
        }

        new Thread(() -> {
            try {
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                if (bytes.length == 0 || bytes.length > MAX_MESSAGE_BYTES) {
                    notifyMain(l -> l.onError("Mesaj çok uzun."));
                    return;
                }
                synchronized (PeerConnection.this) {
                    out.writeInt(bytes.length);
                    out.write(bytes);
                    out.flush();
                }
            } catch (Exception e) {
                notifyMain(l -> l.onError("Mesaj gönderilemedi: " + safeMessage(e)));
                disconnect();
            }
        }, "OfflineWhatsappSender").start();
    }

    private void startReader() {
        Thread readerThread = new Thread(() -> {
            try {
                while (!closed) {
                    int length = input.readInt();
                    if (length <= 0 || length > MAX_MESSAGE_BYTES) {
                        throw new IllegalStateException("Geçersiz mesaj boyutu");
                    }
                    byte[] bytes = new byte[length];
                    input.readFully(bytes);
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    notifyMain(l -> l.onMessage(text));
                }
            } catch (Exception e) {
                if (!closed) notifyMain(Listener::onDisconnected);
            }
        }, "OfflineWhatsappReader");
        readerThread.start();
    }

    private void disconnect() {
        stop();
        notifyMain(Listener::onDisconnected);
    }

    private void notifyMain(Consumer<Listener> action) {
        main.post(() -> action.accept(listener));
    }

    private static String safeMessage(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
