package com.copperbars.offlinewhatsapp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

public final class MessageStore extends SQLiteOpenHelper {
    private static final String DB_NAME = "offline_chat.db";
    private static final int DB_VERSION = 3;
    private static final String PROFILE_PREFS = "sinyalce_profile";
    private static final String NAME_KEY = "name";

    private final Context appContext;

    public static final class Message {
        public final long id;
        public final String kind;
        public final String text;
        public final String uri;
        public final String room;
        public final boolean incoming;
        public final long time;

        public Message(long id, String kind, String text, String uri,
                       String room, boolean incoming, long time) {
            this.id = id;
            this.kind = kind;
            this.text = text;
            this.uri = uri;
            this.room = room;
            this.incoming = incoming;
            this.time = time;
        }
    }

    public MessageStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        this.appContext = context.getApplicationContext();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "kind TEXT NOT NULL," +
                "text TEXT," +
                "uri TEXT," +
                "room TEXT NOT NULL," +
                "incoming INTEGER NOT NULL," +
                "time INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_messages_room_id ON messages(room, id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'text'");
            db.execSQL("ALTER TABLE messages ADD COLUMN uri TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN room TEXT NOT NULL DEFAULT 'Genel'");
        }
        if (oldVersion < 3) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_messages_room_id ON messages(room, id)");
        }
    }

    public synchronized void addText(String text, String room, boolean incoming, long time) {
        String safeRoom = normalizeRoom(room);
        String stored = text == null ? "" : text;
        if (!incoming) {
            String name = appContext.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE)
                    .getString(NAME_KEY, "Kullanıcı");
            if (name == null || name.trim().isEmpty()) name = "Kullanıcı";
            // Outgoing bubbles intentionally contain only the message text in the UI.
            stored = stored.trim();
        }
        add("text", stored, null, safeRoom, incoming, time);
    }

    public synchronized void addImage(String uri, String room, boolean incoming, long time) {
        add("image", null, uri, normalizeRoom(room), incoming, time);
    }

    /** Backward-compatible helper used by the legacy activity. */
    public synchronized void add(String text, boolean incoming, long time) {
        addText(text, "Genel", incoming, time);
    }

    public synchronized List<Message> getAll(String room) {
        return query(room);
    }

    public synchronized List<Message> getAll() {
        List<Message> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                "messages",
                new String[]{"id", "kind", "text", "uri", "room", "incoming", "time"},
                null, null, null, null, "id ASC")) {
            while (c.moveToNext()) result.add(readMessage(c));
        }
        return result;
    }

    private List<Message> query(String room) {
        List<Message> result = new ArrayList<>();
        String safeRoom = normalizeRoom(room);
        try (Cursor c = getReadableDatabase().query(
                "messages",
                new String[]{"id", "kind", "text", "uri", "room", "incoming", "time"},
                "room = ?", new String[]{safeRoom}, null, null, "id ASC")) {
            while (c.moveToNext()) result.add(readMessage(c));
        }
        return result;
    }

    private Message readMessage(Cursor c) {
        return new Message(
                c.getLong(0),
                c.getString(1),
                c.isNull(2) ? null : c.getString(2),
                c.isNull(3) ? null : c.getString(3),
                c.getString(4),
                c.getInt(5) == 1,
                c.getLong(6));
    }

    private void add(String kind, String text, String uri, String room,
                     boolean incoming, long time) {
        ContentValues values = new ContentValues();
        values.put("kind", kind);
        values.put("text", text);
        values.put("uri", uri);
        values.put("room", normalizeRoom(room));
        values.put("incoming", incoming ? 1 : 0);
        values.put("time", time);
        getWritableDatabase().insertOrThrow("messages", null, values);
    }

    private static String normalizeRoom(String room) {
        return room == null || room.trim().isEmpty() ? "Genel" : room.trim();
    }
}
