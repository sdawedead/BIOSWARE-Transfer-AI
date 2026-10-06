package com.biosware.transferbridge;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    static final String PREFS = "bridge_prefs";
    static final String KEY_BOSS = "boss_name";
    static final String KEY_SERVER = "server_url";
    static final String DEFAULT_SERVER = "http://100.113.74.107:8066";

    private SharedPreferences prefs;
    private EditText bossInput;
    private EditText serverInput;
    private TextView statusView;
    private TextView lastView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 36, 36, 48);
        root.setBackgroundColor(Color.rgb(246, 249, 251));
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Práčička Bridge 🚐");
        title.setTextSize(28);
        title.setTextColor(Color.rgb(12, 31, 44));
        title.setPadding(0, 0, 0, 8);
        root.addView(title);

        TextView intro = new TextView(this);
        intro.setText("Messenger notifikácia od vybraného človeka → Práčička na serveri.");
        intro.setTextSize(16);
        intro.setTextColor(Color.DKGRAY);
        intro.setPadding(0, 0, 0, 24);
        root.addView(intro);
        TextView bossLabel = label("Meno šéfa v Messengeri");
        root.addView(bossLabel);
        bossInput = new EditText(this);
        bossInput.setHint("napr. Ján Novák");
        bossInput.setSingleLine(true);
        bossInput.setText(prefs.getString(KEY_BOSS, "Travel Hub"));
        root.addView(bossInput, fieldParams());

        TextView serverLabel = label("Adresa Práčičky");
        root.addView(serverLabel);
        serverInput = new EditText(this);
        serverInput.setSingleLine(true);
        serverInput.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        serverInput.setText(prefs.getString(KEY_SERVER, DEFAULT_SERVER));
        root.addView(serverInput, fieldParams());

        Button save = new Button(this);
        save.setText("Uložiť nastavenie");
        save.setOnClickListener(v -> saveSettings());
        root.addView(save, buttonParams());

        Button access = new Button(this);
        access.setText("Povoliť prístup k notifikáciám");
        access.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(access, buttonParams());
        Button test = new Button(this);
        test.setText("Otestovať spojenie so serverom");
        test.setOnClickListener(v -> testServer());
        root.addView(test, buttonParams());

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setPadding(0, 24, 0, 8);
        root.addView(statusView);

        lastView = new TextView(this);
        lastView.setTextSize(14);
        lastView.setTextColor(Color.DKGRAY);
        root.addView(lastView);

        TextView note = new TextView(this);
        note.setText("Bridge sleduje iba notifikácie aplikácie Messenger a iba tie, ktorých názov odosielateľa obsahuje nastavené meno. Správu neodošle ako jazdu bez tvojej kontroly v Práčičke.");
        note.setTextColor(Color.GRAY);
        note.setPadding(0, 28, 0, 0);
        root.addView(note);

        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(15);
        v.setTextColor(Color.rgb(25, 52, 68));
        v.setPadding(0, 14, 0, 4);
        return v;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, 10);
        return p;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 8, 0, 4);
        return p;
    }

    private void saveSettings() {
        String boss = bossInput.getText().toString().trim();
        String server = normalizeServer(serverInput.getText().toString());
        prefs.edit().putString(KEY_BOSS, boss).putString(KEY_SERVER, server).apply();
        serverInput.setText(server);
        Toast.makeText(this, "Nastavenie uložené", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private String normalizeServer(String value) {
        String s = value == null ? "" : value.trim();
        if (s.isEmpty()) s = DEFAULT_SERVER;
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private boolean listenerEnabled() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    private void refreshStatus() {
        boolean access = listenerEnabled();
        String boss = prefs.getString(KEY_BOSS, "Travel Hub").trim();
        String state = access ? "✅ Notifikácie povolené" : "⚠️ Notifikácie nepovolené";
        if (boss.isEmpty()) state += "\n⚠️ Meno šéfa nie je nastavené";
        else state += "\n👤 Filter: " + boss;
        statusView.setText(state);

        String lastStatus = prefs.getString("last_status", "Zatiaľ nič neodoslané");
        String lastTime = prefs.getString("last_time", "");
        lastView.setText(lastTime.isEmpty() ? lastStatus : lastStatus + "\n" + lastTime);
    }

    private void testServer() {
        saveSettings();
        final String server = normalizeServer(serverInput.getText().toString());
        statusView.setText("Testujem spojenie…");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(server + "/api/state");
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(4000);
                connection.setRequestMethod("GET");
                int code = connection.getResponseCode();
                final boolean ok = code >= 200 && code < 300;
                runOnUiThread(() -> {
                    Toast.makeText(this, ok ? "Server je dostupný" : "Server odpovedal " + code, Toast.LENGTH_LONG).show();
                    refreshStatus();
                });
            } catch (Exception e) {
                String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
                runOnUiThread(() -> {
                    Toast.makeText(this, "Spojenie zlyhalo: " + msg, Toast.LENGTH_LONG).show();
                    refreshStatus();
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        }).start();
    }
}
