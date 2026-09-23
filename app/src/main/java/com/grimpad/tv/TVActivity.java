package com.grimpad.tv;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
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

    private LinearLayout setupLayout, dashLayout, slotsContainer;
    private EditText serverInput, mobileUrlInput;
    private Button connectBtn;
    private TextView wsStatusText, connectedCount, seriesCount,
                     x360Count, serverAddrText, mobileUrlDisplay;

    private final View[]     cardViews = new View[15];
    private final TextView[] numViews  = new TextView[15];
    private final TextView[] typeViews = new TextView[15];
    private final TextView[] axesViews = new TextView[15];

    private OkHttpClient httpClient;
    private WebSocket ws;
    private String currentUrl = "ws://localhost:8765";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable reconnectTask;
    private boolean destroyed = false;

    static class Slot {
        boolean active, is360;
        float lx, ly, rx, ry;
    }
    private final Slot[] slots = new Slot[15];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(R.layout.activity_tv);

        for (int i = 0; i < 15; i++) slots[i] = new Slot();

        setupLayout    = findViewById(R.id.setupLayout);
        dashLayout     = findViewById(R.id.dashLayout);
        slotsContainer = findViewById(R.id.slotsContainer);
        serverInput    = findViewById(R.id.serverInput);
        mobileUrlInput = findViewById(R.id.mobileUrlInput);
        connectBtn     = findViewById(R.id.connectBtn);
        wsStatusText   = findViewById(R.id.wsStatusText);
        connectedCount = findViewById(R.id.connectedCount);
        seriesCount    = findViewById(R.id.seriesCount);
        x360Count      = findViewById(R.id.x360Count);
        serverAddrText = findViewById(R.id.serverAddrText);
        mobileUrlDisplay = findViewById(R.id.mobileUrlDisplay);

        buildSlotGrid();

        connectBtn.setOnClickListener(v -> startDash());
        Button settingsBtn = findViewById(R.id.settingsBtn);
        if (settingsBtn != null)
            settingsBtn.setOnClickListener(v -> goSetup());

        httpClient = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build();

        // Auto-connect to localhost after 1.5s
        mainHandler.postDelayed(this::autoConnect, 1500);
    }

    private void autoConnect() {
        if (destroyed) return;
        if (serverInput != null) serverInput.setText("localhost:8765");
        startDash();
    }

    private void buildSlotGrid() {
        if (slotsContainer == null) return;
        slotsContainer.removeAllViews();
        int dp = (int) getResources().getDisplayMetrics().density;
        for (int row = 0; row < 3; row++) {
            LinearLayout rowL = new LinearLayout(this);
            rowL.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            rp.setMargins(0, 0, 0, row < 2 ? 8*dp : 0);
            rowL.setLayoutParams(rp);
            for (int col = 0; col < 5; col++) {
                int i = row * 5 + col;
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setGravity(Gravity.CENTER);
                card.setPadding(8*dp, 8*dp, 8*dp, 8*dp);
                card.setBackgroundResource(R.drawable.card_empty);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                cp.setMargins(0, 0, col < 4 ? 8*dp : 0, 0);
                card.setLayoutParams(cp);
                TextView num = new TextView(this);
                num.setText("P"+(i+1));
                num.setTextSize(22);
                num.setTextColor(0xFF2A2A3E);
                num.setTypeface(android.graphics.Typeface.MONOSPACE,
                    android.graphics.Typeface.BOLD);
                num.setGravity(Gravity.CENTER);
                TextView type = new TextView(this);
                type.setText("EMPTY");
                type.setTextSize(9);
                type.setTextColor(0xFF333333);
                type.setTypeface(android.graphics.Typeface.MONOSPACE);
                type.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                tp.setMargins(0, 4*dp, 0, 0);
                type.setLayoutParams(tp);
                TextView axes = new TextView(this);
                axes.setText("LX:0 LY:0\nRX:0 RY:0");
                axes.setTextSize(8);
                axes.setTextColor(0xFF222222);
                axes.setTypeface(android.graphics.Typeface.MONOSPACE);
                axes.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
                ap.setMargins(0, 6*dp, 0, 0);
                axes.setLayoutParams(ap);
                card.addView(num); card.addView(type); card.addView(axes);
                rowL.addView(card);
                cardViews[i]=card; numViews[i]=num;
                typeViews[i]=type; axesViews[i]=axes;
            }
            slotsContainer.addView(rowL);
        }
    }

    private void startDash() {
        if (destroyed) return;
        String addr = "";
        if (serverInput != null) addr = serverInput.getText().toString().trim();
        if (addr.isEmpty()) addr = "localhost:8765";
        String mu = "";
        if (mobileUrlInput != null) mu = mobileUrlInput.getText().toString().trim();
        if (mu.isEmpty()) mu = "https://grimpad.vercel.app";
        currentUrl = "ws://" + addr;
        if (serverAddrText != null) serverAddrText.setText(addr);
        if (mobileUrlDisplay != null) mobileUrlDisplay.setText(mu);
        if (setupLayout != null) setupLayout.setVisibility(View.GONE);
        if (dashLayout != null) dashLayout.setVisibility(View.VISIBLE);
        wsConnect();
    }

    private void goSetup() {
        disconnectWS();
        if (setupLayout != null) setupLayout.setVisibility(View.VISIBLE);
        if (dashLayout != null) dashLayout.setVisibility(View.GONE);
    }

    private void wsConnect() {
        if (destroyed) return;
        setStatus("Connecting...", false);
        try {
            ws = httpClient.newWebSocket(
                new Request.Builder().url(currentUrl).build(),
                new WebSocketListener() {
                    @Override public void onOpen(@NonNull WebSocket w, @NonNull Response r) {
                        w.send("{\"type\":\"dashboard_listen\"}");
                        mainHandler.post(() -> setStatus("● " + currentUrl.replace("ws://",""), true));
                    }
                    @Override public void onMessage(@NonNull WebSocket w, @NonNull String text) {
                        try {
                            JSONObject m = new JSONObject(text);
                            if ("gamepad_state".equals(m.optString("type"))) {
                                JSONArray gps = m.getJSONArray("gamepads");
                                mainHandler.post(() -> updateSlots(gps));
                            }
                        } catch (Exception ignored) {}
                    }
                    @Override public void onClosed(@NonNull WebSocket w, int c, @NonNull String r) {
                        mainHandler.post(() -> onDisc());
                    }
                    @Override public void onFailure(@NonNull WebSocket w, @NonNull Throwable t,
                            Response r) {
                        mainHandler.post(() -> {
                            setStatus("○ Server not found — retrying...", false);
                            onDisc();
                        });
                    }
                });
        } catch (Exception e) {
            setStatus("○ Error: " + e.getMessage(), false);
            onDisc();
        }
    }

    private void onDisc() {
        if (destroyed) return;
        clearSlots();
        if (reconnectTask != null) mainHandler.removeCallbacks(reconnectTask);
        reconnectTask = this::wsConnect;
        mainHandler.postDelayed(reconnectTask, 4000);
    }

    private void disconnectWS() {
        if (reconnectTask != null) mainHandler.removeCallbacks(reconnectTask);
        if (ws != null) { try { ws.cancel(); } catch (Exception ignored) {} ws = null; }
    }

    private void setStatus(String t, boolean ok) {
        if (wsStatusText == null) return;
        wsStatusText.setText(t);
        wsStatusText.setTextColor(ok ? 0xFF10B981 : 0xFFEF4444);
    }

    private void clearSlots() {
        for (int i = 0; i < 15; i++) { slots[i].active=false; updateCard(i); }
        updateStats();
    }

    private void updateSlots(JSONArray gamepads) {
        for (int i = 0; i < 15; i++) slots[i].active = false;
        try {
            for (int g = 0; g < gamepads.length(); g++) {
                JSONObject gp = gamepads.getJSONObject(g);
                int idx = gp.getInt("index");
                if (idx < 0 || idx >= 15) continue;
                slots[idx].active = true;
                slots[idx].is360 = gp.optString("id","").contains("028e");
                JSONArray ax = gp.optJSONArray("axes");
                if (ax != null && ax.length() >= 4) {
                    slots[idx].lx=(float)ax.getDouble(0);
                    slots[idx].ly=(float)ax.getDouble(1);
                    slots[idx].rx=(float)ax.getDouble(2);
                    slots[idx].ry=(float)ax.getDouble(3);
                }
            }
        } catch (Exception ignored) {}
        for (int i = 0; i < 15; i++) updateCard(i);
        updateStats();
    }

    private void updateCard(int i) {
        if (cardViews[i] == null) return;
        Slot s = slots[i];
        if (s.active) {
            cardViews[i].setBackgroundResource(
                s.is360 ? R.drawable.card_active_360 : R.drawable.card_active);
            numViews[i].setTextColor(s.is360 ? 0xFFF5C518 : 0xFF10B981);
            typeViews[i].setText(s.is360 ? "XBOX 360" : "SERIES X/S");
            typeViews[i].setTextColor(0xFF888888);
            axesViews[i].setText(String.format(
                "LX:%.1f LY:%.1f\nRX:%.1f RY:%.1f",
                s.lx, s.ly, s.rx, s.ry));
            axesViews[i].setTextColor(s.is360 ? 0xFF5A4A10 : 0xFF3A6A4A);
        } else {
            cardViews[i].setBackgroundResource(R.drawable.card_empty);
            numViews[i].setTextColor(0xFF2A2A3E);
            typeViews[i].setText("EMPTY");
            typeViews[i].setTextColor(0xFF333333);
            axesViews[i].setText("LX:0 LY:0\nRX:0 RY:0");
            axesViews[i].setTextColor(0xFF222222);
        }
    }

    private void updateStats() {
        int total=0, series=0, x360=0;
        for (Slot s : slots) {
            if (s.active) { total++; if(s.is360) x360++; else series++; }
        }
        if (connectedCount != null) connectedCount.setText(String.valueOf(total));
        if (seriesCount != null) seriesCount.setText(String.valueOf(series));
        if (x360Count != null) x360Count.setText(String.valueOf(x360));
    }

    @Override public boolean onKeyDown(int k, KeyEvent e) {
        if (k==KeyEvent.KEYCODE_BACK &&
            dashLayout != null &&
            dashLayout.getVisibility()==View.VISIBLE) {
            goSetup(); return true;
        }
        return super.onKeyDown(k, e);
    }

    @Override protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
        disconnectWS();
        try { httpClient.dispatcher().executorService().shutdown(); } catch (Exception ignored) {}
    }
}
