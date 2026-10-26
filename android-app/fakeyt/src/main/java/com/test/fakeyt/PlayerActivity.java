package com.test.fakeyt;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

/**
 * Imita la pantalla de un video de YouTube: reproductor con controles que se ocultan,
 * engranaje → hoja de opciones → panel de velocidad (texto, −/+, barra y valores rápidos).
 * Incluye señuelos (título con "Speed", "Más opciones", barra de tiempo, pausa) que la app
 * NO debe tocar; si los toca, queda registrado y la prueba falla.
 */
public class PlayerActivity extends Activity {

    static final String T = "FAKEYT";

    private final Handler h = new Handler(Looper.getMainLooper());
    private MediaSession session;
    private float speed = 1f;
    private boolean playing = true;
    private int dialogs = 0;

    private FrameLayout controls;
    private TextView panelValue;
    private SeekBar panelBar;
    private boolean updatingBar;
    private final Runnable hideControls = () -> controls.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        session = new MediaSession(this, "fakeyt");
        session.setActive(true);
        publish();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        int w = getResources().getDisplayMetrics().widthPixels;
        FrameLayout player = new FrameLayout(this);
        player.setId(R.id.player_view);
        player.setBackgroundColor(Color.BLACK);
        player.setContentDescription("Reproductor de video");
        player.setOnClickListener(v -> toggleControls());
        root.addView(player, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, w * 9 / 16));

        // Barra de tiempo del video (señuelo: no debe moverse)
        SeekBar time = new SeekBar(this);
        time.setId(R.id.time_bar);
        time.setMax(600000);
        time.setProgress(120000);
        time.setContentDescription("Barra de progreso");
        time.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { if (user) Log.i(T, "FAKEYT seek"); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        player.addView(time, tp);

        controls = new FrameLayout(this);
        controls.setVisibility(View.GONE);
        player.addView(controls, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageButton gear = new ImageButton(this);
        gear.setImageResource(android.R.drawable.ic_menu_manage);
        gear.setContentDescription("Configuración");
        gear.setOnClickListener(v -> openSheet());
        controls.addView(gear, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END));

        TextView pause = new TextView(this);
        pause.setText("II");
        pause.setTextSize(28);
        pause.setTextColor(Color.WHITE);
        pause.setGravity(Gravity.CENTER);
        pause.setContentDescription("Pausar video");
        pause.setOnClickListener(v -> { Log.i(T, "FAKEYT pause"); playing = !playing; publish(); });
        controls.addView(pause, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));

        // Debajo del video: título con la palabra "Speed" y videos relacionados (señuelos)
        TextView title = new TextView(this);
        title.setText("Speed run: cómo resolver ecuaciones rápido");
        title.setTextSize(18);
        title.setPadding(dp(16), dp(12), dp(16), dp(12));
        title.setOnClickListener(v -> Log.i(T, "FAKEYT decoy title"));
        root.addView(title);

        for (int i = 1; i <= 3; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setPadding(dp(16), dp(10), dp(8), dp(10));
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView rt = new TextView(this);
            rt.setText("Video relacionado " + i);
            row.addView(rt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            ImageButton more = new ImageButton(this);
            more.setImageResource(android.R.drawable.ic_menu_more);
            more.setContentDescription("Más opciones");
            more.setOnClickListener(v -> Log.i(T, "FAKEYT decoy more"));
            row.addView(more, new LinearLayout.LayoutParams(dp(44), dp(44)));
            root.addView(row);
        }

        setContentView(root);
        Log.i(T, "FAKEYT player open");
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f).build());
        session.setActive(false);
        session.release();
        Log.i(T, "FAKEYT player closed");
        super.onDestroy();
    }

    private void toggleControls() {
        h.removeCallbacks(hideControls);
        if (controls.getVisibility() == View.VISIBLE) {
            controls.setVisibility(View.GONE);
        } else {
            controls.setVisibility(View.VISIBLE);
            h.postDelayed(hideControls, 3000);
        }
    }

    private void publish() {
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, 120000, speed)
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)
                .build());
    }

    private void setSpeed(float v) {
        v = Math.round(v * 20f) / 20f;
        v = Math.max(0.25f, Math.min(4f, v));
        speed = v;
        if (panelValue != null) panelValue.setText(String.format(Locale.US, "%.2fx", v));
        if (panelBar != null) {
            updatingBar = true;
            panelBar.setProgress(Math.round((v - 0.25f) / 0.05f));
            updatingBar = false;
        }
        publish();
        Log.i(T, String.format(Locale.US, "FAKEYT speed=%.2f", v));
    }

    // ---------- Hoja de opciones ----------

    private void openSheet() {
        h.removeCallbacks(hideControls);
        controls.setVisibility(View.GONE);
        Dialog d = bottomDialog();
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundColor(Color.WHITE);
        list.setPadding(0, dp(8), 0, dp(16));
        list.addView(sheetRow("Calidad", "Automática", () -> Log.i(T, "FAKEYT wrong quality")));
        list.addView(sheetRow("Subtítulos", "Desactivados", () -> Log.i(T, "FAKEYT wrong captions")));
        list.addView(sheetRow("Velocidad de reproducción", fmtRow(speed), () -> { d.dismiss(); openSpeedPanel(); }));
        list.addView(sheetRow("Configuración adicional", "", () -> Log.i(T, "FAKEYT wrong additional")));
        d.setContentView(list);
        showDialog(d);
    }

    private static String fmtRow(float v) {
        String t = String.format(Locale.US, "%.2f", v);
        while (t.endsWith("0")) t = t.substring(0, t.length() - 1);
        if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        return t.equals("1") ? "Normal" : t + "x";
    }

    private View sheetRow(String title, String value, Runnable onClick) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(20), dp(14), dp(20), dp(14));
        r.setClickable(true);
        r.setOnClickListener(v -> onClick.run());
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(16);
        r.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!value.isEmpty()) {
            TextView v = new TextView(this);
            v.setText(value);
            r.addView(v);
        }
        return r;
    }

    // ---------- Panel de velocidad ----------

    private void openSpeedPanel() {
        Dialog d = bottomDialog();
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackgroundColor(Color.WHITE);
        p.setPadding(dp(20), dp(16), dp(20), dp(20));

        TextView title = new TextView(this);
        title.setText("Velocidad de reproducción");
        title.setTextSize(15);
        p.addView(title);

        panelValue = new TextView(this);
        panelValue.setTextSize(24);
        panelValue.setGravity(Gravity.CENTER);
        panelValue.setPadding(0, dp(12), 0, dp(8));
        p.addView(panelValue);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView dec = new TextView(this);
        dec.setText("−");
        dec.setTextSize(26);
        dec.setGravity(Gravity.CENTER);
        dec.setContentDescription("Disminuir velocidad");
        dec.setOnClickListener(v -> setSpeed(speed - 0.05f));
        row.addView(dec, new LinearLayout.LayoutParams(dp(48), dp(48)));

        panelBar = new SeekBar(this);
        panelBar.setMax(75);
        panelBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int pr, boolean user) { if (!updatingBar) setSpeed(0.25f + pr * 0.05f); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        row.addView(panelBar, new LinearLayout.LayoutParams(0, dp(48), 1f));

        TextView inc = new TextView(this);
        inc.setText("+");
        inc.setTextSize(26);
        inc.setGravity(Gravity.CENTER);
        inc.setContentDescription("Aumentar velocidad");
        inc.setOnClickListener(v -> setSpeed(speed + 0.05f));
        row.addView(inc, new LinearLayout.LayoutParams(dp(48), dp(48)));
        p.addView(row);

        LinearLayout presets = new LinearLayout(this);
        for (float v : new float[]{1f, 1.25f, 1.5f, 2f}) {
            Button bt = new Button(this);
            bt.setText(v == 1f ? "1.0x" : v == 2f ? "2.0x" : String.format(Locale.US, "%sx", Float.toString(v)));
            final float val = v;
            bt.setOnClickListener(x -> setSpeed(val));
            presets.addView(bt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        p.addView(presets);

        d.setContentView(p);
        d.setOnDismissListener(x -> { panelValue = null; panelBar = null; dialogs--; Log.i(T, "FAKEYT dialogs=" + dialogs); });
        dialogs++;
        Log.i(T, "FAKEYT dialogs=" + dialogs);
        d.show();
        placeBottom(d);
        setSpeed(speed); // pinta valores iniciales
    }

    private Dialog bottomDialog() {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        return d;
    }

    private void showDialog(Dialog d) {
        d.setOnDismissListener(x -> { dialogs--; Log.i(T, "FAKEYT dialogs=" + dialogs); });
        dialogs++;
        Log.i(T, "FAKEYT dialogs=" + dialogs);
        d.show();
        placeBottom(d);
    }

    private void placeBottom(Dialog d) {
        Window win = d.getWindow();
        if (win == null) return;
        win.setGravity(Gravity.BOTTOM);
        win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
