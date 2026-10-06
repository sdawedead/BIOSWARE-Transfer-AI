package com.biosware.transferbridge;

import android.app.Notification;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MessengerNotificationService extends NotificationListenerService {
    private static final String TAG = "PracickaBridge";
    private static final String MESSENGER_PACKAGE = "com.facebook.orca";
    private static final String QUEUE_KEY = "pending_queue";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        executor.execute(this::flushQueue);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || !MESSENGER_PACKAGE.equals(sbn.getPackageName())) return;

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String boss = prefs.getString(MainActivity.KEY_BOSS, "").trim();
        if (boss.isEmpty()) return;

        Bundle extras = sbn.getNotification().extras;
        String sender = textOf(extras.getCharSequence(Notification.EXTRA_TITLE));
        String body = textOf(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        if (body.isEmpty()) body = textOf(extras.getCharSequence(Notification.EXTRA_TEXT));
        if (sender.isEmpty() || body.isEmpty()) return;

        String senderLower = sender.toLowerCase(Locale.ROOT);
        String bossLower = boss.toLowerCase(Locale.ROOT);
        if (!senderLower.contains(bossLower)) return;

        final String sourceKey = sbn.getKey() + "|" + sbn.getPostTime() + "|" + body.hashCode();
        final String finalSender = sender;
        final String finalBody = body;
        executor.execute(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("source", "Messenger");
                payload.put("sourceKey", sourceKey);
                payload.put("sender", finalSender);
                payload.put("text", finalBody);
                flushQueue();
                if (!send(payload)) enqueue(payload);
            } catch (Exception e) {
                Log.e(TAG, "Cannot prepare payload", e);
            }
        });
    }

    private String textOf(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private String serverUrl() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        String value = prefs.getString(MainActivity.KEY_SERVER, MainActivity.DEFAULT_SERVER).trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private boolean send(JSONObject payload) {
        HttpURLConnection connection = null;
        try {
            byte[] raw = payload.toString().getBytes(StandardCharsets.UTF_8);
            URL url = new URL(serverUrl() + "/api/inbox");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(raw.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(raw);
            }
            int code = connection.getResponseCode();
            boolean ok = code >= 200 && code < 300;
            updateStatus(ok ? "✅ Posledná správa odoslaná do Práčičky" : "⚠️ Server odpovedal HTTP " + code);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "Send failed", e);
            updateStatus("⚠️ Správa čaká vo fronte: " + e.getClass().getSimpleName());
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private synchronized void enqueue(JSONObject payload) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        JSONArray queue;
        try {
            queue = new JSONArray(prefs.getString(QUEUE_KEY, "[]"));
        } catch (Exception e) {
            queue = new JSONArray();
        }
        queue.put(payload);
        JSONArray trimmed = new JSONArray();
        int start = Math.max(0, queue.length() - 100);
        for (int i = start; i < queue.length(); i++) {
            trimmed.put(queue.opt(i));
        }
        prefs.edit().putString(QUEUE_KEY, trimmed.toString()).apply();
    }

    private synchronized void flushQueue() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        JSONArray queue;
        try {
            queue = new JSONArray(prefs.getString(QUEUE_KEY, "[]"));
        } catch (Exception e) {
            queue = new JSONArray();
        }
        if (queue.length() == 0) return;

        JSONArray remaining = new JSONArray();
        for (int i = 0; i < queue.length(); i++) {
            JSONObject item = queue.optJSONObject(i);
            if (item == null || !send(item)) remaining.put(item);
        }
        prefs.edit().putString(QUEUE_KEY, remaining.toString()).apply();
    }

    private void updateStatus(String message) {
        String when = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(new Date());
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
                .edit()
                .putString("last_status", message)
                .putString("last_time", when)
                .apply();
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
