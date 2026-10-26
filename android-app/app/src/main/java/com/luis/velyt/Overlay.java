package com.luis.velyt;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Botón flotante: panel (− velocidad + ⌄) o burbuja redonda. Se arrastra con el dedo. */
class Overlay {

    private static final int BG = 0xEB1C1C1E;

    private final SpeedService s;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final WindowManager.LayoutParams lp;
    private final int slop;

    private final LinearLayout panel;
    private final TextView minus, label, plus, bubble;
    private final ImageView minimize;

    private View current;
    boolean shown, minimized;

    Overlay(SpeedService s) {
        this.s = s;
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        prefs = s.getSharedPreferences("overlay", Context.MODE_PRIVATE);
        slop = ViewConfiguration.get(s).getScaledTouchSlop();
        minimized = prefs.getBoolean("min", false);

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics dm = s.getResources().getDisplayMetrics();
        lp.x = prefs.getInt("x", dm.widthPixels - dp(200));
        lp.y = prefs.getInt("y", (int) (dm.heightPixels * 0.42f));

        // Panel expandido
        panel = new LinearLayout(s);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(5), dp(5), dp(5), dp(5));
        panel.setBackground(round(BG, dp(28)));

        minus = circleButton("−", "Bajar velocidad");
        plus = circleButton("+", "Subir velocidad");

        label = new TextView(s);
        label.setTextColor(Color.WHITE);
        label.setTextSize(16);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER);
        label.setMinWidth(dp(58));
        label.setPadding(dp(4), 0, dp(4), 0);
        label.setContentDescription("Velocidad (tocar: volver a 1x)");

        minimize = new ImageView(s);
        minimize.setImageResource(R.drawable.ic_min);
        minimize.setAlpha(0.75f);
        minimize.setPadding(dp(6), dp(6), dp(6), dp(6));
        minimize.setContentDescription("Minimizar");

        panel.addView(minus, new LinearLayout.LayoutParams(dp(42), dp(42)));
        panel.addView(label, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(42)));
        panel.addView(plus, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(34), dp(42));
        mp.leftMargin = dp(2);
        panel.addView(minimize, mp);

        // Burbuja minimizada
        bubble = new TextView(s);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(13);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bb = new GradientDrawable();
        bb.setShape(GradientDrawable.OVAL);
        bb.setColor(BG);
        bb.setStroke(dp(2), 0x66FFFFFF);
        bubble.setBackground(bb);
        bubble.setContentDescription("Abrir controles de velocidad");
        bubble.setLayoutParams(new android.view.ViewGroup.LayoutParams(dp(54), dp(54)));
        bubble.setMinWidth(dp(54));
        bubble.setMinHeight(dp(54));

        minus.setOnTouchListener(new Drag(() -> s.step(-1)));
        plus.setOnTouchListener(new Drag(() -> s.step(+1)));
        label.setOnTouchListener(new Drag(s::reset));
        minimize.setOnTouchListener(new Drag(() -> setMinimized(true)));
        panel.setOnTouchListener(new Drag(null));
        bubble.setOnTouchListener(new Drag(() -> setMinimized(false)));

        setSpeed(1f);
    }

    // ---------- Mostrar / ocultar ----------

    void show() {
        if (shown) return;
        current = minimized ? bubble : panel;
        try {
            wm.addView(current, lp);
            shown = true;
            Log.i(SpeedService.TAG, "VYT_STATE overlay=shown mode=" + mode());
            current.post(this::reclamp);
            current.postDelayed(this::logPos, 250);
        } catch (Exception e) {
            Log.e(SpeedService.TAG, "VYT_ERR addView " + e);
        }
    }

    void hide() {
        if (!shown) return;
        try { wm.removeView(current); } catch (Exception ignored) { }
        shown = false;
        Log.i(SpeedService.TAG, "VYT_STATE overlay=hidden");
    }

    void setMinimized(boolean m) {
        if (m == minimized) return;
        int oldW = current != null ? current.getWidth() : 0;
        boolean wasShown = shown;
        if (wasShown) {
            try { wm.removeView(current); } catch (Exception ignored) { }
            shown = false;
        }
        minimized = m;
        // La burbuja queda en el borde derecho del panel, y el panel crece hacia la izquierda.
        int newW = dp(m ? 54 : 190);
        if (oldW > 0) lp.x = lp.x + oldW - newW;
        prefs.edit().putBoolean("min", m).apply();
        Log.i(SpeedService.TAG, "VYT_MODE " + mode());
        if (wasShown) show();
    }

    String mode() { return minimized ? "bubble" : "expanded"; }

    // ---------- Velocidad ----------

    void setSpeed(float v) {
        String t = fmt(v);
        label.setText(t);
        bubble.setText(t);
    }

    static String fmt(float v) {
        String t = String.format(Locale.US, "%.2f", v);
        while (t.endsWith("0")) t = t.substring(0, t.length() - 1);
        if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        return t + "x";
    }

    void flash() {
        View v = minimized ? bubble : label;
        v.animate().cancel();
        v.setAlpha(0.3f);
        v.animate().alpha(1f).setDuration(350).start();
    }

    /** Rectángulo del botón en pantalla (para no tocarlo al automatizar). */
    Rect screenRect() {
        if (!shown || current == null) return null;
        int[] l = new int[2];
        current.getLocationOnScreen(l);
        return new Rect(l[0], l[1], l[0] + current.getWidth(), l[1] + current.getHeight());
    }

    // ---------- Posición ----------

    void reclamp() {
        if (!shown || current == null) return;
        int[] c = clampXY(lp.x, lp.y);
        if (c[0] != lp.x || c[1] != lp.y) {
            lp.x = c[0];
            lp.y = c[1];
            try { wm.updateViewLayout(current, lp); } catch (Exception ignored) { }
        }
    }

    private int[] clampXY(int x, int y) {
        DisplayMetrics dm = s.getResources().getDisplayMetrics();
        int w = current != null && current.getWidth() > 0 ? current.getWidth() : dp(54);
        int hh = current != null && current.getHeight() > 0 ? current.getHeight() : dp(54);
        x = Math.max(0, Math.min(x, dm.widthPixels - w));
        y = Math.max(0, Math.min(y, dm.heightPixels - hh));
        return new int[]{x, y};
    }

    private void savePos() {
        prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply();
    }

    void logPos() {
        if (!shown) return;
        if (minimized) {
            Log.i(SpeedService.TAG, "VYT_POS mode=bubble bubble=" + center(bubble));
        } else {
            Log.i(SpeedService.TAG, "VYT_POS mode=expanded minus=" + center(minus) + " label=" + center(label)
                    + " plus=" + center(plus) + " min=" + center(minimize));
        }
    }

    private static String center(View v) {
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        return (l[0] + v.getWidth() / 2) + "," + (l[1] + v.getHeight() / 2);
    }

    // ---------- Arrastrar / tocar ----------

    private class Drag implements View.OnTouchListener {
        private final Runnable onTap;
        private float downX, downY;
        private int startX, startY;
        private boolean dragging;

        Drag(Runnable onTap) { this.onTap = onTap; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = false;
                    if (onTap != null) v.setAlpha(0.55f);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        dragging = true;
                        v.setAlpha(1f);
                    }
                    if (dragging && shown) {
                        int[] c = clampXY(startX + (int) dx, startY + (int) dy);
                        lp.x = c[0];
                        lp.y = c[1];
                        try { wm.updateViewLayout(current, lp); } catch (Exception ignored) { }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    v.setAlpha(1f);
                    if (dragging) {
                        savePos();
                        Log.i(SpeedService.TAG, "VYT_MOVED x=" + lp.x + " y=" + lp.y);
                        v.postDelayed(Overlay.this::logPos, 150);
                    } else if (onTap != null) {
                        onTap.run();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(1f);
                    return true;
            }
            return false;
        }
    }

    // ---------- Utilidades ----------

    private TextView circleButton(String text, String desc) {
        TextView b = new TextView(s);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(22);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x26FFFFFF);
        b.setBackground(g);
        b.setContentDescription(desc);
        return b;
    }

    private static GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    private int dp(float v) {
        return (int) (v * s.getResources().getDisplayMetrics().density + 0.5f);
    }
}
