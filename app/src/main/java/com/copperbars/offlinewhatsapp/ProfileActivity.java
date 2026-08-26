package com.copperbars.offlinewhatsapp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class ProfileActivity extends Activity {
    public static final String PREFS = "sinyalce_profile";
    public static final String KEY_NAME = "name";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(32, 48, 32, 32);
        root.setBackgroundColor(Color.rgb(244, 247, 251));

        TextView title = new TextView(this);
        title.setText("Sinyalce");
        title.setTextSize(32);
        title.setTextColor(Color.rgb(17, 76, 160));
        title.setGravity(Gravity.CENTER);

        TextView subtitle = new TextView(this);
        subtitle.setText("Mesajlarda görünecek ismini yaz");
        subtitle.setTextSize(17);
        subtitle.setGravity(Gravity.CENTER);

        EditText name = new EditText(this);
        name.setHint("İsmin");
        name.setSingleLine(true);
        name.setText(getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, ""));

        Button continueButton = new Button(this);
        continueButton.setText("DEVAM ET");
        continueButton.setOnClickListener(v -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) {
                name.setError("İsim gerekli");
                return;
            }
            if (value.length() > 32) {
                name.setError("En fazla 32 karakter");
                return;
            }
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_NAME, value).apply();
            startActivity(new Intent(this, SinyalceActivity.class));
            finish();
        });

        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        root.addView(subtitle, new LinearLayout.LayoutParams(-1, -2));
        root.addView(name, new LinearLayout.LayoutParams(-1, -2));
        root.addView(continueButton, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }
}
