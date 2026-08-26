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
    private static final int DB_VERSION = 1;

    public static final class Message {
        public final long id;
        public final String text;
        public final boolean incoming;
        public final long time;

        public Message(long id, String text, boolean incoming, long time) {
            this.id = id;
            this.text = text;
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
                "text TEXT NOT NULL," +
                "incoming INTEGER NOT NULL," +
                "time INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS messages");
        onCreate(db);
    }

    public synchronized void add(String text, boolean incoming, long time) {
        ContentValues values = new ContentValues();
        values.put("text", text);
        values.put("incoming", incoming ? 1 : 0);
        values.put("time", time);
        getWritableDatabase().insert("messages", null, values);
    }

    public synchronized List<Message> getAll() {
        List<Message> result = new ArrayList<>();
        try (Cursor c = getReadableDatabase().query(
                "messages",
                new String[]{"id", "text", "incoming", "time"},
                null, null, null, null, "id ASC")) {

            while (c.moveToNext()) {
                result.add(new Message(
                        c.getLong(0),
                        c.getString(1),
                        c.getInt(2) == 1,
                        c.getLong(3)
                ));
            }
        }
        return result;
    }
}
