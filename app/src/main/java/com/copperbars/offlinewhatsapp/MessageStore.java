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
    private static final int DB_VERSION = 2;

    public static final class Message {
        public final long id;
        public final String kind;
        public final String text;
        public final String uri;
        public final String room;
        public final boolean incoming;
        public final long time;

        public Message(long id, String kind, String text, String uri, String room, boolean incoming, long time) {
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
        super(context, DB_NAME, null, DB_VERSION);
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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'text'");
            db.execSQL("ALTER TABLE messages ADD COLUMN uri TEXT");
            db.execSQL("ALTER TABLE messages ADD COLUMN room TEXT NOT NULL DEFAULT 'Genel'");
        }
    }

    public synchronized void addText(String text, String room, boolean incoming, long time) {
        add("text", text, null, room, incoming, time);
    }

    public synchronized void addImage(String uri, String room, boolean incoming, long time) {
        add("image", null, uri, room, incoming, time);
    }

    private void add(String kind, String text, String uri, String room, boolean incoming, long time) {
        ContentValues values = new ContentValues();
        values.put("kind", kind);
        values.put("text", text);
        values.put("uri", uri);
        values.put("room", room);
        values.put("incoming", incoming ? 1 : 0);
        values.put("time", time);
        getWritableDatabase().insert("messages", null, values);
    }

    public synchronized List<Message> getAll(String room) {
        List<Message> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                "messages",
                new String[]{"id", "kind", "text", "uri", "room", "incoming", "time"},
                "room = ?", new String[]{room}, null, null, "id ASC")) {
            while (c.moveToNext()) {
                result.add(new Message(
                        c.getLong(0),
                        c.getString(1),
                        c.isNull(2) ? null : c.getString(2),
                        c.isNull(3) ? null : c.getString(3),
                        c.getString(4),
                        c.getInt(5) == 1,
                        c.getLong(6)
                ));
            }
        }
        return result;
    }
}
