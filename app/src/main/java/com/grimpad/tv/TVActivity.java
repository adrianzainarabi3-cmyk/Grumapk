package com.grimpad.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

public class TVActivity extends Activity {

    private TextView statusView;
    private TextView countView;
    private final TextView[] slotViews = new TextView[15];
    private final Handler h = new Handler(Looper.getMainLooper());
    private OkHttpClient client;
    private WebSocket ws;
    private boolean destroyed = false;
    private Runnable retryTask;

    static class Slot {
        boolean active; boolean is360;
    }
    private final Slot[] slots = new Slot[15];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        for (int i = 0; i < 15; i++) slots[i] = new Slot();

        // Build UI entirely in Java - no XML dependency
        buildUI();

        client = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build();

        // Auto connect after 500ms
        h.postDelayed(this::connect, 500);
    }

    private void buildUI() {
        int dp = (int) getResources().getDisplayMetrics().density;

        // Root
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF060612);
        root.setPadding(20*dp, 16*dp, 20*dp, 16*dp);

        // Header row
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        headerP.setMargins(0, 0, 0, 16*dp);
        header.setLayoutParams(headerP);

        // Title
        TextView title = new TextView(this);
        title.setText("GRIMPAD TV");
        title.setTextSize(28);
        title.setTextColor(0xFF10B981);
        title.setTypeface(android.graphics.Typeface.MONOSPACE,
            android.graphics.Typeface.BOLD);
        title.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams titleP = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(titleP);
        header.addView(title);

        // Status
        statusView = new TextView(this);
        statusView.setText("○ Connecting...");
        statusView.setTextSize(12);
        statusView.setTextColor(0xFFEF4444);
        statusView.setTypeface(android.graphics.Typeface.MONOSPACE);
        statusView.setBackgroundColor(0xFF1A1A2E);
        statusView.setPadding(12*dp, 6*dp, 12*dp, 6*dp);
        header.addView(statusView);

        root.addView(header);

        // Connected count
        countView = new TextView(this);
        countView.setText("0 / 15 controllers connected");
        countView.setTextSize(11);
        countView.setTextColor(0xFF2A4A3A);
        countView.setTypeface(android.graphics.Typeface.MONOSPACE);
        LinearLayout.LayoutParams countP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        countP.setMargins(0, 0, 0, 12*dp);
        countView.setLayoutParams(countP);
        root.addView(countView);

        // Slot grid - 3 rows of 5
        LinearLayout.LayoutParams gridP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setLayoutParams(gridP);

        for (int row = 0; row < 3; row++) {
            LinearLayout rowL = new LinearLayout(this);
            rowL.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            rowP.setMargins(0, 0, 0, row < 2 ? 8*dp : 0);
            rowL.setLayoutParams(rowP);

            for (int col = 0; col < 5; col++) {
                int i = row * 5 + col;
                TextView slot = new TextView(this);
                slot.setText("P" + (i+1) + "\nEMPTY");
                slot.setTextSize(14);
                slot.setTextColor(0xFF2A2A3E);
                slot.setTypeface(android.graphics.Typeface.MONOSPACE,
                    android.graphics.Typeface.BOLD);
                slot.setGravity(Gravity.CENTER);
                slot.setBackgroundResource(R.drawable.card_empty);
                LinearLayout.LayoutParams slotP = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                slotP.setMargins(0, 0, col < 4 ? 8*dp : 0, 0);
                slot.setLayoutParams(slotP);
                rowL.addView(slot);
                slotViews[i] = slot;
            }
            grid.addView(rowL);
        }
        root.addView(grid);

        // Bottom hint
        TextView hint = new TextView(this);
        hint.setText("📱 Open grimpad.vercel.app on mobile → JOIN GAME → enter this TV's IP");
        hint.setTextSize(10);
        hint.setTextColor(0xFF1A3A2A);
        hint.setTypeface(android.graphics.Typeface.MONOSPACE);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(0xFF0A0A1A);
        hint.setPadding(12*dp, 8*dp, 12*dp, 8*dp);
        LinearLayout.LayoutParams hintP = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        hintP.setMargins(0, 10*dp, 0, 0);
        hint.setLayoutParams(hintP);
        root.addView(hint);

        setContentView(root);
    }

    private void connect() {
        if (destroyed) return;
        setStatus("Connecting to localhost:8765...", false);
        try {
            ws = client.newWebSocket(
                new Request.Builder().url("ws://localhost:8765").build(),
                new WebSocketListener() {
                    @Override public void onOpen(@NonNull WebSocket w, @NonNull Response r) {
                        w.send("{\"type\":\"dashboard_listen\"}");
                        h.post(() -> setStatus("● localhost:8765", true));
                    }
                    @Override public void onMessage(@NonNull WebSocket w, @NonNull String t) {
                        try {
                            JSONObject m = new JSONObject(t);
                            if ("gamepad_state".equals(m.optString("type"))) {
                                JSONArray gps = m.getJSONArray("gamepads");
                                h.post(() -> updateSlots(gps));
                            }
                        } catch (Exception ignored) {}
                    }
                    @Override public void onClosed(@NonNull WebSocket w, int c, @NonNull String r) {
                        h.post(() -> retry());
                    }
                    @Override public void onFailure(@NonNull WebSocket w, @NonNull Throwable t,
                            Response r) {
                        h.post(() -> {
                            setStatus("○ No server — retrying...", false);
                            retry();
                        });
                    }
                });
        } catch (Exception e) {
            setStatus("○ Error — retrying...", false);
            retry();
        }
    }

    private void retry() {
        if (destroyed) return;
        clearSlots();
        if (retryTask != null) h.removeCallbacks(retryTask);
        retryTask = this::connect;
        h.postDelayed(retryTask, 4000);
    }

    private void setStatus(String t, boolean ok) {
        if (statusView == null) return;
        statusView.setText(t);
        statusView.setTextColor(ok ? 0xFF10B981 : 0xFFEF4444);
    }

    private void clearSlots() {
        for (int i = 0; i < 15; i++) {
            slots[i].active = false;
            updateSlot(i);
        }
        updateCount();
    }

    private void updateSlots(JSONArray gamepads) {
        for (int i = 0; i < 15; i++) slots[i].active = false;
        try {
            for (int g = 0; g < gamepads.length(); g++) {
                JSONObject gp = gamepads.getJSONObject(g);
                int idx = gp.getInt("index");
                if (idx >= 0 && idx < 15) {
                    slots[idx].active = true;
                    slots[idx].is360 = gp.optString("id","").contains("028e");
                }
            }
        } catch (Exception ignored) {}
        for (int i = 0; i < 15; i++) updateSlot(i);
        updateCount();
    }

    private void updateSlot(int i) {
        if (slotViews[i] == null) return;
        Slot s = slots[i];
        if (s.active) {
            slotViews[i].setBackgroundResource(
                s.is360 ? R.drawable.card_active_360 : R.drawable.card_active);
            slotViews[i].setTextColor(s.is360 ? 0xFFF5C518 : 0xFF10B981);
            slotViews[i].setText("P"+(i+1)+"\n"+(s.is360?"360":"SERIES"));
        } else {
            slotViews[i].setBackgroundResource(R.drawable.card_empty);
            slotViews[i].setTextColor(0xFF2A2A3E);
            slotViews[i].setText("P"+(i+1)+"\nEMPTY");
        }
    }

    private void updateCount() {
        if (countView == null) return;
        int total = 0;
        for (Slot s : slots) if (s.active) total++;
        countView.setText(total + " / 15 controllers connected");
        countView.setTextColor(total > 0 ? 0xFF10B981 : 0xFF2A4A3A);
    }

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        return super.onKeyDown(k, e);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
        if (retryTask != null) h.removeCallbacks(retryTask);
        if (ws != null) { try { ws.cancel(); } catch (Exception ignored) {} }
        try { client.dispatcher().executorService().shutdown(); } catch (Exception ignored) {}
    }
}
