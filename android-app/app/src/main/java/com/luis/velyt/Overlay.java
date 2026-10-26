package com.luis.velyt;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.Region;
import android.hardware.display.DisplayManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Botón flotante con tres formas: panel (− velocidad + ⌄ ×), burbuja redonda y
 * la tarjeta de la X ("Volver a mostrar"). También pone la "cubierta": una foto de la
 * pantalla encima de todo mientras se toca el menú de YouTube, para que no se vea.
 */
class Overlay {

    private static final int BG = 0xEB1C1C1E;
    private static final int MODE_PANEL = 0, MODE_BUBBLE = 1, MODE_CLOSE = 2;

    private final SpeedService s;
    private final WindowManager wm;
    private final SharedPreferences prefs;
    private final WindowManager.LayoutParams lp;
    private final int slop;

    private final LinearLayout panel, closeCard;
    private final TextView minus, label, plus, bubble, close, optNext, optReopen;
    private final ImageView minimize;

    private View current;
    private int mode;
    boolean shown;

    // Cubierta
    private View coverView;
    boolean covered;
    private int lastSW, lastSH;

    Overlay(SpeedService s) {
        this.s = s;
        wm = (WindowManager) s.getSystemService(Context.WINDOW_SERVICE);
        prefs = s.getSharedPreferences("overlay", Context.MODE_PRIVATE);
        slop = ViewConfiguration.get(s).getScaledTouchSlop();
        mode = MODE_BUBBLE; // cada video empieza en burbuja

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        DisplayMetrics dm = s.getResources().getDisplayMetrics();
        lp.x = prefs.getInt("x", dm.widthPixels - dp(250));
        lp.y = prefs.getInt("y", (int) (dm.heightPixels * 0.42f));

        // ----- Panel -----
        panel = new LinearLayout(s);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setGravity(Gravity.CENTER_VERTICAL);
        panel.setPadding(dp(5), dp(5), dp(5), dp(5));
        panel.setBackground(round(BG, dp(29)));

        minus = circleButton("−", 24, "Bajar velocidad");
        plus = circleButton("+", 24, "Subir velocidad");

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
        minimize.setScaleType(ImageView.ScaleType.CENTER);
        minimize.setAlpha(0.85f);
        minimize.setContentDescription("Minimizar");

        close = new TextView(s);
        close.setText("×");
        close.setTextColor(0xD9FFFFFF);
        close.setTextSize(24);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setContentDescription("Cerrar");

        panel.addView(minus, new LinearLayout.LayoutParams(dp(46), dp(46)));
        panel.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(46)));
        panel.addView(plus, new LinearLayout.LayoutParams(dp(46), dp(46)));
        LinearLayout.LayoutParams sep = new LinearLayout.LayoutParams(dp(1), dp(24));
        sep.leftMargin = dp(6);
        View line = new View(s);
        line.setBackgroundColor(0x33FFFFFF);
        panel.addView(line, sep);
        panel.addView(minimize, new LinearLayout.LayoutParams(dp(42), dp(46)));
        panel.addView(close, new LinearLayout.LayoutParams(dp(42), dp(46)));

        // ----- Burbuja -----
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
        bubble.setMinWidth(dp(56));
        bubble.setMinHeight(dp(56));

        // ----- Tarjeta de la X -----
        closeCard = new LinearLayout(s);
        closeCard.setOrientation(LinearLayout.VERTICAL);
        closeCard.setPadding(dp(14), dp(10), dp(14), dp(14));
        closeCard.setBackground(round(BG, dp(18)));
        TextView q = new TextView(s);
        q.setText("Volver a mostrar");
        q.setTextColor(0x99FFFFFF);
        q.setTextSize(12);
        q.setPadding(dp(2), 0, 0, dp(8));
        closeCard.addView(q);
        optNext = option("Próximo video");
        optReopen = option("Al reabrir YouTube");
        closeCard.addView(optNext, fullWidth(0));
        closeCard.addView(optReopen, fullWidth(dp(8)));

        // ----- Toques -----
        minus.setOnTouchListener(new Drag(() -> s.step(-1)));
        plus.setOnTouchListener(new Drag(() -> s.step(+1)));
        label.setOnTouchListener(new Drag(s::reset));
        minimize.setOnTouchListener(new Drag(() -> setMode(MODE_BUBBLE)));
        close.setOnTouchListener(new Drag(() -> setMode(MODE_CLOSE)));
        line.setOnTouchListener(new Drag(null));
        panel.setOnTouchListener(new Drag(null));
        bubble.setOnTouchListener(new Drag(() -> setMode(MODE_PANEL)));
        optNext.setOnClickListener(v -> { setMode(MODE_PANEL); s.dismiss(false); });
        optReopen.setOnClickListener(v -> { setMode(MODE_PANEL); s.dismiss(true); });
        closeCard.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { setMode(MODE_PANEL); return true; }
            return false;
        });

        setSpeed(1f);
        Point sc = screen();
        lastSW = sc.x;
        lastSH = sc.y;
    }

    void toBubble() { setMode(MODE_BUBBLE); }

    Point screen() {
        Point p = new Point();
        try {
            ((DisplayManager) s.getSystemService(Context.DISPLAY_SERVICE))
                    .getDisplay(Display.DEFAULT_DISPLAY).getRealSize(p);
        } catch (Exception e) {
            DisplayMetrics dm = s.getResources().getDisplayMetrics();
            p.set(dm.widthPixels, dm.heightPixels);
        }
        return p;
    }

    /** Al girar el teléfono, el botón queda en el mismo lugar relativo de la pantalla. */
    void rotated() {
        Point sc = screen();
        if (lastSW > 0 && (sc.x != lastSW || sc.y != lastSH)) {
            View v = current != null ? current : viewFor(mode);
            int w = v.getWidth() > 0 ? v.getWidth() : dp(56);
            int hh = v.getHeight() > 0 ? v.getHeight() : dp(56);
            float fx = (lp.x + w / 2f) / lastSW, fy = (lp.y + hh / 2f) / lastSH;
            lp.x = Math.round(fx * sc.x - w / 2f);
            lp.y = Math.round(fy * sc.y - hh / 2f);
            lastSW = sc.x;
            lastSH = sc.y;
            uncover(); // la foto ya no corresponde
            if (shown) {
                try { wm.updateViewLayout(current, lp); } catch (Exception ignored) { }
            }
            reclamp();
            savePos();
            Log.i(SpeedService.TAG, "VYT_ROTATED " + sc.x + "x" + sc.y);
            if (shown) current.postDelayed(this::logPos, 300);
        }
    }

    // ---------- Mostrar / ocultar ----------

    private View viewFor(int m) {
        return m == MODE_BUBBLE ? bubble : m == MODE_CLOSE ? closeCard : panel;
    }

    void show() {
        if (shown) return;
        current = viewFor(mode);
        if (mode == MODE_CLOSE) lp.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        else lp.flags &= ~WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        try {
            wm.addView(current, lp);
            shown = true;
            Log.i(SpeedService.TAG, "VYT_STATE overlay=shown mode=" + modeName());
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
        if (mode == MODE_CLOSE) mode = MODE_PANEL;
        Log.i(SpeedService.TAG, "VYT_STATE overlay=hidden");
    }

    private void setMode(int m) {
        if (m == mode) return;
        View old = viewFor(mode);
        int oldW = old.getWidth();
        boolean wasShown = shown;
        if (wasShown) {
            try { wm.removeView(current); } catch (Exception ignored) { }
            shown = false;
        }
        View nv = viewFor(m);
        int newW = nv.getWidth() > 0 ? nv.getWidth() : dp(m == MODE_BUBBLE ? 56 : m == MODE_CLOSE ? 190 : 250);
        // Mantiene alineado el borde derecho (donde está la X / la burbuja).
        if (oldW > 0) lp.x = lp.x + oldW - newW;
        mode = m;
        Log.i(SpeedService.TAG, "VYT_MODE " + modeName());
        if (wasShown) show();
        if (coverView != null) coverView.invalidate();
    }

    private String modeName() {
        return mode == MODE_BUBBLE ? "bubble" : mode == MODE_CLOSE ? "close" : "expanded";
    }

    // ---------- Cubierta (para que no se vea el menú de YouTube) ----------
    //
    // Se tapa SOLO la zona donde sale el menú, nunca el video:
    //  - Video arriba (vertical): foto de la parte de abajo (título, comentarios: no se mueve).
    //  - Video a pantalla completa: tarjeta oscura con la velocidad sobre la zona del menú.
    // El botón queda visible y tocable por un hueco con su misma forma.

    void cover(Runnable then) {
        if (covered || !shown) { then.run(); return; }
        Point sc = screen();
        boolean land = sc.x > sc.y;
        Rect player = s.automator.playerRect();
        Rect learned = region(land);
        Rect r;
        boolean top = player != null && player.height() < sc.y * 0.6f && player.bottom < sc.y * 0.65f;
        if (top) {
            r = new Rect(0, player.bottom, sc.x, sc.y);
            if (learned != null) r.union(learned);
        } else if (learned != null) {
            r = new Rect(learned);
        } else {
            r = new Rect((int) (sc.x * 0.15f), (int) (sc.y * 0.25f), (int) (sc.x * 0.85f), sc.y);
        }
        if (!r.intersect(0, 0, sc.x, sc.y)) { then.run(); return; }
        String info = " top=" + r.top + " playerBottom=" + (player != null ? player.bottom : -1)
                + " src=" + (learned != null ? "learned" : "default");
        if (top && Build.VERSION.SDK_INT >= 30) shot(r, info, then);
        else { addCover(null, r, "card" + info); then.run(); }
    }

    @android.annotation.TargetApi(30)
    private void shot(Rect r, String info, Runnable then) {
        try {
            s.takeScreenshot(Display.DEFAULT_DISPLAY, s.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult res) {
                            Bitmap bm = null;
                            try {
                                HardwareBuffer hb = res.getHardwareBuffer();
                                Bitmap hw = Bitmap.wrapHardwareBuffer(hb, res.getColorSpace());
                                bm = hw != null ? hw.copy(Bitmap.Config.ARGB_8888, false) : null;
                                hb.close();
                            } catch (Exception e) {
                                Log.e(SpeedService.TAG, "VYT_ERR cover " + e);
                            }
                            addCover(bm, r, (bm != null ? "shot" : "card") + info);
                            then.run();
                        }

                        @Override
                        public void onFailure(int code) {
                            addCover(null, r, "card" + info);
                            then.run();
                        }
                    });
        } catch (Exception e) {
            addCover(null, r, "card" + info);
            then.run();
        }
    }

    private void addCover(Bitmap bm, Rect r, String desc) {
        if (covered || !s.ytForeground) return; // nunca dos cubiertas, ni sobre otra app
        CoverView v = new CoverView(bm, r);
        WindowManager.LayoutParams c = new WindowManager.LayoutParams(
                r.width(), r.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        c.gravity = Gravity.TOP | Gravity.START;
        c.x = r.left;
        c.y = r.top;
        if (Build.VERSION.SDK_INT >= 28) {
            c.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
        try {
            wm.addView(v, c);
            coverView = v;
            covered = true;
            Log.i(SpeedService.TAG, "VYT_COVER on mode=" + desc);
        } catch (Exception e) {
            Log.e(SpeedService.TAG, "VYT_ERR addCover " + e);
        }
    }

    void uncover() {
        if (!covered) return;
        try { wm.removeView(coverView); } catch (Exception ignored) { }
        covered = false;
        coverView = null; // sin recycle(): la vista puede estar dibujándose aún
        Log.i(SpeedService.TAG, "VYT_COVER off");
    }

    /** Forma exacta del botón (para el hueco de la cubierta). */
    private Path holePath() {
        Path p = new Path();
        Rect r = screenRect();
        if (r == null) return p;
        RectF f = new RectF(r);
        if (mode == MODE_BUBBLE) {
            p.addOval(f, Path.Direction.CW);
        } else {
            float rad = mode == MODE_CLOSE ? dp(18) : Math.min(dp(29), f.height() / 2f);
            p.addRoundRect(f, rad, rad, Path.Direction.CW);
        }
        return p;
    }

    void saveRegion(Rect r) {
        Point sc = screen();
        prefs.edit().putString(sc.x > sc.y ? "reg_land" : "reg_port", r.flattenToString()).apply();
    }

    private Rect region(boolean land) {
        String v = prefs.getString(land ? "reg_land" : "reg_port", null);
        return v == null ? null : Rect.unflattenFromString(v);
    }

    private class CoverView extends View {
        private final Bitmap bm;
        private final Rect r;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint tx = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] loc = new int[2];

        CoverView(Bitmap bm, Rect r) {
            super(s);
            this.bm = bm;
            this.r = new Rect(r);
            bg.setColor(0xFF1C1C1E);
            tx.setColor(Color.WHITE);
            tx.setTextAlign(Paint.Align.CENTER);
            tx.setTypeface(Typeface.DEFAULT_BOLD);
            tx.setTextSize(34 * s.getResources().getDisplayMetrics().scaledDensity);
        }

        @Override
        protected void onDraw(Canvas c) {
            getLocationOnScreen(loc);
            c.save();
            c.translate(-loc[0], -loc[1]); // a partir de aquí, coordenadas de pantalla
            Path hole = holePath();
            if (Build.VERSION.SDK_INT >= 26) c.clipOutPath(hole);
            else c.clipPath(hole, Region.Op.DIFFERENCE);
            if (bm != null) {
                c.drawBitmap(bm, r, r, null);
            } else {
                c.drawRoundRect(new RectF(r), dp(20), dp(20), bg);
                c.drawText(fmt(s.target), r.exactCenterX(), r.exactCenterY() - (tx.descent() + tx.ascent()) / 2f, tx);
            }
            c.restore();
        }
    }

    // ---------- Velocidad ----------

    void setSpeed(float v) {
        String t = fmt(v);
        label.setText(t);
        bubble.setText(t);
        if (coverView != null) coverView.invalidate();
    }

    static String fmt(float v) {
        String t = String.format(Locale.US, "%.2f", v);
        while (t.endsWith("0")) t = t.substring(0, t.length() - 1);
        if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        return t + "x";
    }

    void flash() {
        View v = mode == MODE_BUBBLE ? bubble : label;
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
        Point sc = screen();
        int w = current != null && current.getWidth() > 0 ? current.getWidth() : dp(56);
        int hh = current != null && current.getHeight() > 0 ? current.getHeight() : dp(56);
        x = Math.max(0, Math.min(x, sc.x - w));
        y = Math.max(0, Math.min(y, sc.y - hh));
        return new int[]{x, y};
    }

    private void savePos() {
        prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply();
    }

    void logPos() {
        if (!shown) return;
        if (mode == MODE_BUBBLE) {
            Log.i(SpeedService.TAG, "VYT_POS mode=bubble bubble=" + center(bubble));
        } else if (mode == MODE_CLOSE) {
            Log.i(SpeedService.TAG, "VYT_POS mode=close next=" + center(optNext) + " reopen=" + center(optReopen));
        } else {
            Log.i(SpeedService.TAG, "VYT_POS mode=expanded minus=" + center(minus) + " label=" + center(label)
                    + " plus=" + center(plus) + " min=" + center(minimize) + " close=" + center(close));
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
                    // En los botones se tolera un poco más de movimiento antes de arrastrar,
                    // para que un toque con el dedo algo inclinado no se pierda.
                    float limit = onTap != null ? slop * 2.5f : slop;
                    if (!dragging && Math.hypot(dx, dy) > limit) {
                        dragging = true;
                        v.setAlpha(onTap != null ? 0.85f : 1f);
                    }
                    if (dragging && shown) {
                        int[] c = clampXY(startX + (int) dx, startY + (int) dy);
                        lp.x = c[0];
                        lp.y = c[1];
                        try { wm.updateViewLayout(current, lp); } catch (Exception ignored) { }
                        if (coverView != null) coverView.invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    v.setAlpha(v == minimize ? 0.85f : 1f);
                    if (dragging) {
                        savePos();
                        Log.i(SpeedService.TAG, "VYT_MOVED x=" + lp.x + " y=" + lp.y);
                        v.postDelayed(Overlay.this::logPos, 150);
                    } else if (onTap != null) {
                        onTap.run();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    v.setAlpha(v == minimize ? 0.85f : 1f);
                    return true;
            }
            return false;
        }
    }

    // ---------- Utilidades ----------

    private TextView circleButton(String text, int sp, String desc) {
        TextView b = new TextView(s);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(sp);
        b.setGravity(Gravity.CENTER);
        b.setIncludeFontPadding(false);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x26FFFFFF);
        b.setBackground(g);
        b.setContentDescription(desc);
        return b;
    }

    private TextView option(String text) {
        TextView t = new TextView(s);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(11), dp(16), dp(11));
        t.setBackground(round(0x26FFFFFF, dp(14)));
        return t;
    }

    private static LinearLayout.LayoutParams fullWidth(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = top;
        return p;
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
