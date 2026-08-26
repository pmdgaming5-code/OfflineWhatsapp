package com.copperbars.offlinewhatsapp;

public final class AppState {
    private static volatile boolean foreground;
    private AppState() {}

    public static boolean isForeground() {
        return foreground;
    }

    public static void setForeground(boolean value) {
        foreground = value;
    }
}
