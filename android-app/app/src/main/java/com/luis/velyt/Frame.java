package com.luis.velyt;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.HardwareBuffer;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.util.Locale;

/** Foto del frame (y "con tiempo"): recorte del video en pantalla, guardado en Imágenes/VelocidadYT de L. */
@android.annotation.TargetApi(30)
final class Frame {

    static final class Info {
        long pos = -1, dur = -1;
        boolean playing, muted;
        String title = "YouTube";
    }

    static Info info(SpeedService s) {
        Info i = new Info();
        try {
            i.muted = s.audio != null && s.audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0;
            if (s.yt == null) return i;
            PlaybackState st = s.yt.getPlaybackState();
            if (st != null) {
                i.playing = st.getState() == PlaybackState.STATE_PLAYING;
                long p = st.getPosition();
                if (p >= 0 && i.playing && st.getLastPositionUpdateTime() > 0)
                    p += (long) ((SystemClock.elapsedRealtime() - st.getLastPositionUpdateTime()) * st.getPlaybackSpeed());
                i.pos = p;
            }
            MediaMetadata m = s.yt.getMetadata();
            if (m != null) {
                i.dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
                String t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
                if (t != null && !t.trim().isEmpty()) i.title = t.trim();
            }
            if (i.dur > 0 && i.pos > i.dur) i.pos = i.dur;
        } catch (Exception ignored) { }
        return i;
    }

    static String clock(long ms) {
        if (ms < 0) return "0:00";
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, x = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, x) : String.format(Locale.US, "%d:%02d", m, x);
    }

    private static String stamp(long ms) {
        if (ms < 0) return "";
        long s = ms / 1000, h = s / 3600, m = (s % 3600) / 60, x = s % 60;
        return h > 0 ? String.format(Locale.US, " %dh%02dm%02ds", h, m, x) : String.format(Locale.US, " %dm%02ds", m, x);
    }

    /** Zona del video en pantalla. */
    static Rect videoRect(SpeedService s) {
        Point sc = s.overlay.screen();
        boolean land = sc.x > sc.y;
        int line = (int) (3 * s.getResources().getDisplayMetrics().density); // línea roja bajo el video
        Rect p = s.automator.playerRect();
        if (p != null && p.width() > sc.x * 0.5f && p.height() > sc.y * 0.15f) {
            p = new Rect(p);
            if (!land && p.height() < sc.y * 0.6f) p.bottom -= line;
            return p;
        }
        if (land) return new Rect(0, 0, sc.x, sc.y);
        int top = s.overlay.statusBar();
        return new Rect(0, top, sc.x, top + sc.x * 9 / 16 - line);
    }

    static void save(SpeedService s, HardwareBuffer hb, ColorSpace cs, Rect area, boolean withTime, Info info) {
        new Thread(() -> {
            try {
                Bitmap hw = Bitmap.wrapHardwareBuffer(hb, cs);
                Bitmap full = hw.copy(Bitmap.Config.ARGB_8888, false);
                hb.close();
                Rect a = new Rect(area);
                if (!a.intersect(0, 0, full.getWidth(), full.getHeight())) a.set(0, 0, full.getWidth(), full.getHeight());
                Rect t = trim(full, a);
                Bitmap b = Bitmap.createBitmap(full, t.left, t.top, t.width(), t.height()).copy(Bitmap.Config.ARGB_8888, true);
                if (withTime) drawTime(b, info);
                String name = name(info, withTime);
                write(s, b, name);
                D.i("VYT_SHOT ok name=" + name + " size=" + b.getWidth() + "x" + b.getHeight());
                s.toast("Guardada");
            } catch (Exception e) {
                D.i("VYT_SHOT error " + e);
                s.toast("No se pudo guardar");
            }
        }).start();
    }

    // ---------- Recorte de franjas negras (solo si son parejas, como en los videos con bandas) ----------

    private static int lum(int c) { return (((c >> 16) & 255) * 3 + ((c >> 8) & 255) * 6 + (c & 255)) / 10; }

    private static boolean darkRow(Bitmap b, int y, int x0, int x1) {
        for (int x = x0; x < x1; x += 3) if (lum(b.getPixel(x, y)) > 14) return false;
        return true;
    }

    private static boolean darkCol(Bitmap b, int x, int y0, int y1) {
        for (int y = y0; y < y1; y += 3) if (lum(b.getPixel(x, y)) > 14) return false;
        return true;
    }

    static Rect trim(Bitmap b, Rect a) {
        int top = a.top, bottom = a.bottom, left = a.left, right = a.right;
        while (bottom - top > a.height() * 0.3f && darkRow(b, top, left, right)) top++;
        while (bottom - top > a.height() * 0.3f && darkRow(b, bottom - 1, left, right)) bottom--;
        int tolV = Math.max(4, a.height() / 50);
        if (Math.abs((top - a.top) - (a.bottom - bottom)) > tolV) { top = a.top; bottom = a.bottom; }
        while (right - left > a.width() * 0.3f && darkCol(b, left, top, bottom)) left++;
        while (right - left > a.width() * 0.3f && darkCol(b, right - 1, top, bottom)) right--;
        int tolH = Math.max(4, a.width() / 50);
        if (Math.abs((left - a.left) - (a.right - right)) > tolH) { left = a.left; right = a.right; }
        return new Rect(left, top, right, bottom);
    }

    // ---------- "Con tiempo": barra, punto rojo, reproducir/pausa, volumen y tiempo ----------

    static void drawTime(Bitmap b, Info i) {
        int w = b.getWidth(), h = b.getHeight();
        float u = Math.max(1f, Math.min(h / 100f, w / 180f));
        Canvas c = new Canvas(b);
        Paint g = new Paint();
        g.setShader(new LinearGradient(0, h - 24 * u, 0, h, 0x00000000, 0x8C000000, Shader.TileMode.CLAMP));
        c.drawRect(0, h - 24 * u, w, h, g);

        float m = 2.2f * u, barY = h - 9.8f * u, th = 0.6f * u;
        float frac = i.dur > 0 && i.pos >= 0 ? Math.min(1f, i.pos / (float) i.dur) : 0f;
        float x0 = m, x1 = w - m, xp = x0 + (x1 - x0) * frac;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0x4DFFFFFF);
        c.drawRoundRect(x0, barY - th / 2, x1, barY + th / 2, th / 2, th / 2, p);
        p.setColor(0xFFFF0033);
        c.drawRoundRect(x0, barY - th / 2, Math.max(x0 + th, xp), barY + th / 2, th / 2, th / 2, p);
        c.drawCircle(xp, barY, 1.2f * u, p);

        float r = 3.6f * u, cy = h - 4.4f * u;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(0x73000000);
        Paint wf = new Paint(Paint.ANTI_ALIAS_FLAG);
        wf.setColor(0xFFFFFFFF);
        Paint ws = new Paint(Paint.ANTI_ALIAS_FLAG);
        ws.setColor(0xFFFFFFFF);
        ws.setStyle(Paint.Style.STROKE);
        ws.setStrokeWidth(0.45f * u);
        ws.setStrokeCap(Paint.Cap.ROUND);

        // Reproducir (si está en pausa) o pausa (si se está reproduciendo), como YouTube.
        float cx = m + r;
        c.drawCircle(cx, cy, r, bg);
        if (i.playing) {
            float bw = 0.22f * r, bh = 0.85f * r;
            c.drawRoundRect(cx - 0.3f * r - bw / 2, cy - bh / 2, cx - 0.3f * r + bw / 2, cy + bh / 2, bw / 3, bw / 3, wf);
            c.drawRoundRect(cx + 0.3f * r - bw / 2, cy - bh / 2, cx + 0.3f * r + bw / 2, cy + bh / 2, bw / 3, bw / 3, wf);
        } else {
            Path t = new Path();
            t.moveTo(cx - 0.32f * r, cy - 0.48f * r);
            t.lineTo(cx - 0.32f * r, cy + 0.48f * r);
            t.lineTo(cx + 0.5f * r, cy);
            t.close();
            c.drawPath(t, wf);
        }

        // Volumen
        float vx = cx + 2 * r + 1.0f * u;
        c.drawCircle(vx, cy, r, bg);
        float k = 0.4f * r, sx = vx - 0.25f * r;
        Path sp = new Path();
        sp.moveTo(sx - k * 1.1f, cy - k * 0.45f);
        sp.lineTo(sx - k * 0.45f, cy - k * 0.45f);
        sp.lineTo(sx + k * 0.25f, cy - k * 1.1f);
        sp.lineTo(sx + k * 0.25f, cy + k * 1.1f);
        sp.lineTo(sx - k * 0.45f, cy + k * 0.45f);
        sp.lineTo(sx - k * 1.1f, cy + k * 0.45f);
        sp.close();
        c.drawPath(sp, wf);
        if (i.muted) {
            float ax = sx + k * 1.0f, d = k * 0.55f;
            c.drawLine(ax, cy - d, ax + 2 * d, cy + d, ws);
            c.drawLine(ax, cy + d, ax + 2 * d, cy - d, ws);
        } else {
            float ax = sx + k * 0.25f;
            c.drawArc(new RectF(ax - k * 0.7f, cy - k * 0.7f, ax + k * 0.7f, cy + k * 0.7f), -50, 100, false, ws);
            c.drawArc(new RectF(ax - k * 1.35f, cy - k * 1.35f, ax + k * 1.35f, cy + k * 1.35f), -50, 100, false, ws);
        }

        // Tiempo "2:51 / 30:09"
        String txt = clock(i.pos) + (i.dur > 0 ? " / " + clock(i.dur) : "");
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(0xFFFFFFFF);
        tp.setTextSize(2.7f * u);
        tp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        float tw = tp.measureText(txt), pad = 2.4f * u;
        float pl = vx + r + 1.0f * u;
        c.drawRoundRect(pl, cy - r, pl + tw + 2 * pad, cy + r, r, r, bg);
        c.drawText(txt, pl + pad, cy - (tp.ascent() + tp.descent()) / 2f, tp);
    }

    // ---------- Guardar ----------

    static String name(Info i, boolean withTime) {
        String t = i.title.replaceAll("[\\\\/:*?\"<>|\\n\\r\\t]", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > 60) t = t.substring(0, 60).trim();
        if (t.isEmpty()) t = "YouTube";
        return t + stamp(i.pos) + (withTime ? " (con tiempo)" : "") + ".png";
    }

    private static void write(SpeedService s, Bitmap b, String name) throws Exception {
        ContentResolver cr = s.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/VelocidadYT de L");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IllegalStateException("sin uri");
        try (OutputStream o = cr.openOutputStream(uri)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        cr.update(uri, v, null, null);
    }
}
