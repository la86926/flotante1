package com.luis.velyt;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cambia la velocidad dentro de la app oficial de YouTube.
 * 1) Si la sesión multimedia de YouTube acepta "cambiar velocidad", se usa directamente.
 * 2) Si no, se hace lo mismo que harías con el dedo: mostrar controles → engranaje →
 *    "Velocidad de reproducción" → elegir el valor → cerrar el menú.
 */
class Automator {

    interface Done { void done(Result r); }

    static class Result {
        final boolean ok; final float speed; final String via;
        Result(boolean ok, float speed, String via) { this.ok = ok; this.speed = speed; this.via = via; }
    }

    private static final Pattern VALUE = Pattern.compile("^\\s*(\\d+(?:[.,]\\d+)?)\\s*[x×]\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYER_ID = Pattern.compile("(watch_player|player_view|player_fragment|inline_player|player_container|youtube_controls|player_overlay)");
    private static final Pattern SETTINGS_ID = Pattern.compile("(player_settings|settings_button|player_control_settings)");
    private static final Pattern OVERFLOW_ID = Pattern.compile("(player_overflow|overflow_button)");
    private static final Pattern SETTINGS_WORD = Pattern.compile("(settings|configuraci|ajustes|configura[cç][aã]o|configurações)");
    private static final Pattern MORE_WORD = Pattern.compile("(more options|más opciones|mas opciones|mais opções)");
    private static final Pattern SPEED_WORD = Pattern.compile("(playback speed|velocidad|speed|velocidade)");
    private static final Pattern SPEED_PHRASE = Pattern.compile("^(playback speed|velocidad de reproducci|velocidade de reprodu|velocidad$|speed$)");
    private static final Pattern ADDITIONAL_WORD = Pattern.compile("(additional settings|configuración adicional|configuracion adicional|ajustes adicionales|más configuraci|configurações adicionais)");
    private static final Pattern INC_WORD = Pattern.compile("(aumentar|increase|incrementar|subir|faster|más rápid|mais rápid)");
    private static final Pattern DEC_WORD = Pattern.compile("(disminuir|reducir|decrease|bajar|slower|más lent|diminuir)");

    private final SpeedService s;
    private final Handler h;

    boolean busy;
    private Done done;
    private float target;
    private long deadline;
    private int playerTaps;
    private boolean triedAdditional;
    private int buttonRounds;
    private int panelReads;
    private int runId;
    private String menuItemText;
    private Rect menuItemBounds;
    private java.util.Set<Integer> beforeWindows;

    Automator(SpeedService s) {
        this.s = s;
        this.h = s.h;
    }

    /** Velocidad directa por la sesión multimedia (sin tocar nada). null = aún no se sabe. */
    private Boolean sessionWorks;

    void trySession(float t, MediaController c, java.util.function.Consumer<Boolean> cb) {
        if (c == null || Build.VERSION.SDK_INT < 29 || Boolean.FALSE.equals(sessionWorks)) { cb.accept(false); return; }
        try {
            PlaybackState st = c.getPlaybackState();
            if (st == null || Math.abs(st.getPlaybackSpeed() - t) < 0.02f) { cb.accept(false); return; }
            c.getTransportControls().setPlaybackSpeed(t);
            h.postDelayed(() -> {
                PlaybackState s2 = c.getPlaybackState();
                boolean ok = s2 != null && Math.abs(s2.getPlaybackSpeed() - t) < 0.02f;
                sessionWorks = ok;
                Log.i(SpeedService.TAG, "VYT_SESSION_SPEED " + (ok ? "si" : "no"));
                cb.accept(ok);
            }, 600);
        } catch (Exception e) {
            sessionWorks = false;
            cb.accept(false);
        }
    }

    void setSpeed(float t, Done d) {
        busy = true;
        done = d;
        target = t;
        playerTaps = 0;
        triedAdditional = false;
        buttonRounds = 0;
        panelReads = 0;
        menuItemText = null;
        menuItemBounds = null;
        beforeWindows = null;
        learned = null;
        runId++;
        deadline = SystemClock.uptimeMillis() + 10000;
        stepStart();
    }

    /** Recuerda dónde aparece el menú de YouTube, para tapar solo esa zona la próxima vez. */
    private Rect learned;

    private void learn(Snap sn) {
        Rect r = sn.menuRect(beforeWindows);
        if (r == null || r.isEmpty()) return;
        if (learned == null) learned = new Rect(r); else learned.union(r);
    }

    Rect playerRect() { return snap().player(); }

    // ---------- Pasos ----------

    private void stepStart() {
        Snap sn = snap();
        if (sn.panelOpen()) { stepPanel(); return; }
        stepSettings();
    }

    private void stepSettings() {
        if (!s.ytForeground) { finish(false, s.target, "fuera_de_youtube"); return; }
        poll(playerTaps == 0 ? 200 : 900, () -> {
            Snap sn = snap();
            if (sn.panelOpen()) { stepPanel(); return true; }
            AccessibilityNodeInfo g = sn.settings();
            if (g != null && !g.isVisibleToUser()) Log.i(SpeedService.TAG, "VYT_GEAR invisible");
            if (g != null && g.isVisibleToUser()) {
                beforeWindows = sn.windowIds();
                if (click(g)) { stepWaitMenu(); return true; }
            }
            return false;
        }, () -> {
            if (playerTaps < 2) {
                playerTaps++;
                tapPlayer(snap());
                h.postDelayed(this::stepSettings, 450);
            } else {
                Snap sn = snap();
                AccessibilityNodeInfo g = sn.settings();
                beforeWindows = sn.windowIds();
                if (g != null && click(g)) stepWaitMenu();
                else fail("sin_boton_configuracion");
            }
        });
    }

    private void stepWaitMenu() {
        poll(1600, () -> {
            Snap sn = snap();
            if (sn.panelOpen()) { stepPanel(); return true; }
            AccessibilityNodeInfo item = sn.speedItem(beforeWindows);
            if (item != null) {
                learn(sn);
                menuItemText = String.valueOf(item.getText() != null ? item.getText() : item.getContentDescription());
                menuItemBounds = new Rect();
                item.getBoundsInScreen(menuItemBounds);
                if (click(item)) { stepWaitPanel(); return true; }
            }
            return false;
        }, () -> {
            AccessibilityNodeInfo more = snap().additional();
            if (!triedAdditional && more != null && click(more)) {
                triedAdditional = true;
                stepWaitMenu();
            } else {
                fail("sin_opcion_velocidad");
            }
        });
    }

    private void stepWaitPanel() {
        poll(1600, () -> {
            if (snap().panelOpen()) { stepPanel(); return true; }
            return false;
        }, () -> fail("sin_panel_velocidad"));
    }

    private void stepPanel() {
        if (!busy) return;
        Snap sn = snap();
        // El panel puede estar aún dibujándose: esperar a poder leer la velocidad.
        if (sn.current() == null && panelReads < 5) {
            panelReads++;
            h.postDelayed(this::stepPanel, 200);
            return;
        }
        learn(sn);
        float avail = sn.maxAvailable();
        float goal = target;
        if (!Float.isNaN(avail) && goal > avail + 0.001f) goal = avail;
        Float cur = sn.current();
        Log.i(SpeedService.TAG, String.format(Locale.US, "VYT_PANEL cur=%s max=%s goal=%.2f",
                cur, Float.isNaN(avail) ? "?" : String.valueOf(avail), goal));
        if (cur != null && Math.abs(cur - goal) < 0.02f) { closeAndFinish(goal, "ya_estaba"); return; }

        final float g = goal;
        AccessibilityNodeInfo p = sn.preset(g);
        if (p != null && click(p)) {
            verify(g, "preset", () -> tryBar(g));
            return;
        }
        tryBar(g);
    }

    private void tryBar(float goal) {
        Snap sn = snap();
        AccessibilityNodeInfo bar = sn.seekbar();
        if (bar != null && bar.getRangeInfo() != null && hasSetProgress(bar)) {
            float v = sn.barValueFor(bar, goal);
            if (!Float.isNaN(v)) {
                Bundle b = new Bundle();
                b.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, v);
                if (bar.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), b)) {
                    verify(goal, "barra", () -> tryButtons(goal));
                    return;
                }
            }
        }
        tryButtons(goal);
    }

    private void tryButtons(float goal) {
        Snap sn = snap();
        Float cur = sn.current();
        AccessibilityNodeInfo inc = sn.increase(), dec = sn.decrease();
        if (cur == null || (inc == null && dec == null)) {
            // Sin forma de leer o de pulsar: se acepta lo que haya quedado.
            if (cur != null) closeAndFinish(cur, "aproximado");
            else fail("sin_controles");
            return;
        }
        AccessibilityNodeInfo btn = goal > cur ? inc : dec;
        if (btn == null || buttonRounds >= 3) { closeAndFinish(cur, "limite"); return; }
        buttonRounds++;
        final float before = cur;
        click(btn);
        h.postDelayed(() -> {
            if (!busy) return;
            Snap s2 = snap();
            Float c2 = s2.current();
            if (c2 == null) { fail("sin_lectura"); return; }
            float stepSize = Math.abs(c2 - before);
            if (stepSize < 0.001f) { closeAndFinish(c2, "limite"); return; } // ya no sube/baja más
            if (Math.abs(c2 - goal) < 0.02f) { closeAndFinish(goal, "botones"); return; }
            int n = Math.round(Math.abs(goal - c2) / stepSize);
            AccessibilityNodeInfo b2 = goal > c2 ? s2.increase() : s2.decrease();
            if (b2 == null) { closeAndFinish(c2, "limite"); return; }
            clickTimes(b2, Math.min(n, 120), () -> verify(goal, "botones", () -> tryButtons(goal)));
        }, 250);
    }

    private void clickTimes(AccessibilityNodeInfo n, int times, Runnable then) {
        if (!busy) return;
        if (times <= 0) { then.run(); return; }
        click(n);
        h.postDelayed(() -> clickTimes(n, times - 1, then), 45);
    }

    private void verify(float goal, String via, Runnable fallback) {
        h.postDelayed(() -> {
            if (!busy) return;
            Float cur = snap().current();
            if (cur == null) { closeAndFinish(goal, via + "_sin_lectura"); return; }
            if (Math.abs(cur - goal) < 0.02f) closeAndFinish(goal, via);
            else fallback.run();
        }, 350);
    }

    private void closeAndFinish(float speed, String via) {
        closeMenus(0, () -> finish(true, speed, via));
    }

    private void fail(String why) {
        closeMenus(0, () -> finish(false, s.target, why));
    }

    /** Pulsa "atrás" solo mientras haya un menú de YouTube abierto (nunca de más). */
    private void closeMenus(int count, Runnable then) {
        Snap sn = snap();
        if (count < 3 && (sn.panelOpen() || sn.menuStillOpen(menuItemText, menuItemBounds))) {
            s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            h.postDelayed(() -> closeMenus(count + 1, then), 320);
        } else {
            then.run();
        }
    }

    private void finish(boolean ok, float speed, String via) {
        if (!busy) return;
        if (learned != null) {
            s.overlay.saveRegion(learned);
            Log.i(SpeedService.TAG, "VYT_LEARN rect=" + learned.toShortString());
        }
        busy = false;
        runId++;
        Done d = done;
        done = null;
        if (d != null) d.done(new Result(ok, speed, via));
    }

    private void poll(long timeoutMs, BooleanSupplier attempt, Runnable onTimeout) {
        final long end = SystemClock.uptimeMillis() + timeoutMs;
        final int id = runId;
        h.post(new Runnable() {
            @Override public void run() {
                if (!busy || id != runId) return;
                if (SystemClock.uptimeMillis() > deadline) { fail("tiempo"); return; }
                boolean moved;
                try { moved = attempt.getAsBoolean(); } catch (Exception e) { moved = false; }
                if (moved) return;
                if (SystemClock.uptimeMillis() > end) { onTimeout.run(); return; }
                h.postDelayed(this, 120);
            }
        });
    }

    // ---------- Acciones ----------

    private boolean click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        for (int i = 0; i < 5 && c != null; i++) {
            if (c.isClickable() && c.isEnabled()) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            c = c.getParent();
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        return tap(r.exactCenterX(), r.exactCenterY());
    }

    private void tapPlayer(Snap sn) {
        if (!s.ytForeground) return; // nunca tocar otra app
        DisplayMetrics dm = s.getResources().getDisplayMetrics();
        Rect p = sn.player();
        if (p == null || p.isEmpty()) {
            boolean landscape = dm.widthPixels > dm.heightPixels;
            p = landscape ? new Rect(0, 0, dm.widthPixels, dm.heightPixels)
                    : new Rect(0, 0, dm.widthPixels, (int) (dm.widthPixels * 9f / 16f) + dp(30));
        }
        // Lejos del centro (ahí está pausa/reproducir), del botón flotante y de la
        // zona tapada (Android 16+ no deja pasar toques por debajo de la cubierta).
        Rect ov = s.overlay.screenRect();
        Rect cv = s.overlay.coverRect();
        float[][] spots = {{0.2f, 0.35f}, {0.2f, 0.12f}, {0.8f, 0.12f}, {0.8f, 0.35f}, {0.06f, 0.5f}, {0.94f, 0.5f}};
        float x = p.left + p.width() * 0.2f, y = p.top + p.height() * 0.35f;
        for (float[] sp : spots) {
            int cx = (int) (p.left + p.width() * sp[0]), cy = (int) (p.top + p.height() * sp[1]);
            if ((ov != null && ov.contains(cx, cy)) || (cv != null && cv.contains(cx, cy))) continue;
            x = cx;
            y = cy;
            break;
        }
        Log.i(SpeedService.TAG, "VYT_TAP player=" + p.toShortString() + " at=" + (int) x + "," + (int) y
                + " cover=" + (cv != null ? cv.toShortString() : "-") + " ok=" + tap(x, y));
    }

    private boolean tap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                .build();
        return s.dispatchGesture(g, null, null);
    }

    private static boolean hasSetProgress(AccessibilityNodeInfo n) {
        for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) {
            if (a.getId() == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()) return true;
        }
        return false;
    }

    private int dp(float v) {
        return (int) (v * s.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------- Lectura de la pantalla de YouTube ----------

    private Snap snap() { return new Snap(); }

    static Float parseValue(CharSequence cs) {
        if (cs == null) return null;
        String t = cs.toString().trim();
        if (t.equalsIgnoreCase("normal")) return 1f;
        Matcher m = VALUE.matcher(t);
        if (!m.matches()) return null;
        try { return Float.parseFloat(m.group(1).replace(',', '.')); } catch (Exception e) { return null; }
    }

    private static String lower(CharSequence c) {
        return c == null ? "" : c.toString().toLowerCase(Locale.ROOT);
    }

    private class Snap {
        final List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        private Rect playerRect;
        private boolean playerDone;

        Snap() {
            List<AccessibilityNodeInfo> roots = new ArrayList<>();
            try {
                for (AccessibilityWindowInfo w : s.getWindows()) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null && SpeedService.YT.equals(String.valueOf(r.getPackageName()))) roots.add(r);
                }
            } catch (Exception ignored) { }
            if (roots.isEmpty()) {
                AccessibilityNodeInfo a = s.getRootInActiveWindow();
                if (a != null && SpeedService.YT.equals(String.valueOf(a.getPackageName()))) roots.add(a);
            }
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>(roots);
            while (!q.isEmpty() && nodes.size() < 2500) {
                AccessibilityNodeInfo n = q.poll();
                nodes.add(n);
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) q.add(c);
                }
            }
        }

        /** Zona que ocupa el menú de velocidad / de opciones en pantalla. */
        Rect menuRect(java.util.Set<Integer> before) {
            DisplayMetrics dm = s.getResources().getDisplayMetrics();
            float screenArea = (float) dm.widthPixels * dm.heightPixels;
            Rect u = null;
            if (before != null) {
                try {
                    for (AccessibilityWindowInfo w : s.getWindows()) {
                        if (before.contains(w.getId())) continue;
                        AccessibilityNodeInfo r = w.getRoot();
                        if (r == null || !SpeedService.YT.equals(String.valueOf(r.getPackageName()))) continue;
                        Rect b = new Rect();
                        w.getBoundsInScreen(b);
                        if ((float) b.width() * b.height() > screenArea * 0.7f) continue; // ventana a pantalla completa
                        if (u == null) u = b; else u.union(b);
                    }
                } catch (Exception ignored) { }
            }
            if (u != null) return u;
            List<AccessibilityNodeInfo> seeds = new ArrayList<>(values());
            AccessibilityNodeInfo it = speedItem(before);
            if (it != null) seeds.add(it);
            for (AccessibilityNodeInfo n : seeds) {
                Rect best = new Rect();
                n.getBoundsInScreen(best);
                AccessibilityNodeInfo c = n.getParent();
                for (int i = 0; i < 8 && c != null; i++) {
                    Rect b = new Rect();
                    c.getBoundsInScreen(b);
                    if ((float) b.width() * b.height() > screenArea * 0.7f) break;
                    best = b;
                    c = c.getParent();
                }
                if (u == null) u = best; else u.union(best);
            }
            return u;
        }

        Rect player() {
            if (playerDone) return playerRect;
            playerDone = true;
            for (AccessibilityNodeInfo n : nodes) {
                String id = lower(n.getViewIdResourceName());
                if (!id.isEmpty() && PLAYER_ID.matcher(id).find() && n.isVisibleToUser()) {
                    Rect r = new Rect();
                    n.getBoundsInScreen(r);
                    if (r.width() > dp(120) && r.height() > dp(60)) { playerRect = r; break; }
                }
            }
            return playerRect;
        }

        private boolean inPlayerArea(AccessibilityNodeInfo n) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            Rect p = player();
            if (p != null) return p.contains(r.centerX(), r.centerY());
            DisplayMetrics dm = s.getResources().getDisplayMetrics();
            if (dm.widthPixels > dm.heightPixels) return true; // pantalla completa
            return r.centerY() < dm.heightPixels * 0.45f;
        }

        AccessibilityNodeInfo settings() {
            AccessibilityNodeInfo t2 = null, t3 = null, t4 = null;
            for (AccessibilityNodeInfo n : nodes) {
                String id = lower(n.getViewIdResourceName());
                String d = lower(n.getContentDescription());
                if (!id.isEmpty() && SETTINGS_ID.matcher(id).find() && inPlayerArea(n)) return n;
                if (t2 == null && !d.isEmpty() && SETTINGS_WORD.matcher(d).find() && inPlayerArea(n)) t2 = n;
                if (t3 == null && !id.isEmpty() && OVERFLOW_ID.matcher(id).find() && inPlayerArea(n)) t3 = n;
                if (t4 == null && !d.isEmpty() && MORE_WORD.matcher(d).find() && inPlayerArea(n)) t4 = n;
            }
            return t2 != null ? t2 : t3 != null ? t3 : t4;
        }

        java.util.Set<Integer> windowIds() {
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            for (AccessibilityNodeInfo n : nodes) ids.add(n.getWindowId());
            return ids;
        }

        /**
         * Opción "Velocidad de reproducción" de la hoja de opciones.
         * Se prefiere la frase exacta y lo que apareció en una ventana nueva (la hoja),
         * para no confundirla con, por ejemplo, un título de video que diga "speed".
         */
        AccessibilityNodeInfo speedItem(java.util.Set<Integer> before) {
            AccessibilityNodeInfo best = null;
            int bestScore = 0;
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser()) continue;
                CharSequence t = n.getText();
                if (t == null) t = n.getContentDescription();
                String l = lower(t).trim();
                if (l.isEmpty() || l.length() > 60 || parseValue(t) != null) continue;
                if (!SPEED_WORD.matcher(l).find()) continue;
                int score = 1;
                if (SPEED_PHRASE.matcher(l).find()) score = 3;
                boolean fresh = before != null && !before.contains(n.getWindowId());
                if (fresh) score += 4;
                if (score < 3) continue; // palabra suelta en la ventana del video: no
                if (score > bestScore) { best = n; bestScore = score; }
            }
            return best;
        }

        AccessibilityNodeInfo additional() {
            for (AccessibilityNodeInfo n : nodes) {
                String l = lower(n.getText() != null ? n.getText() : n.getContentDescription());
                if (!l.isEmpty() && ADDITIONAL_WORD.matcher(l).find() && n.isVisibleToUser()) return n;
            }
            return null;
        }

        List<AccessibilityNodeInfo> valueNodes() {
            List<AccessibilityNodeInfo> out = new ArrayList<>();
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser()) continue;
                Float v = parseValue(n.getText());
                if (v == null) v = parseValue(n.getContentDescription());
                if (v != null && v > 0.05f && v <= 16f) out.add(n);
            }
            return out;
        }

        Float valueOf(AccessibilityNodeInfo n) {
            Float v = parseValue(n.getText());
            return v != null ? v : parseValue(n.getContentDescription());
        }

        private List<AccessibilityNodeInfo> valuesCache;

        private List<AccessibilityNodeInfo> values() {
            if (valuesCache == null) valuesCache = valueNodes();
            return valuesCache;
        }

        /** ¿Está cerca (en vertical) de algún texto de velocidad tipo "1.25x"? */
        private boolean nearValues(AccessibilityNodeInfo n) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            for (AccessibilityNodeInfo v : values()) {
                Rect vr = new Rect();
                v.getBoundsInScreen(vr);
                if (Math.abs(vr.centerY() - r.centerY()) < dp(260)) return true;
            }
            return false;
        }

        /** Barra de velocidad (nunca la barra de tiempo del video). */
        AccessibilityNodeInfo seekbar() {
            if (values().isEmpty()) return null;
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser()) continue;
                String c = lower(n.getClassName());
                if (!(c.contains("seekbar") || c.contains("slider") || n.getRangeInfo() != null)) continue;
                String id = lower(n.getViewIdResourceName());
                if (id.contains("time") || id.contains("progress") || id.contains("scrub")) continue;
                AccessibilityNodeInfo.RangeInfo ri = n.getRangeInfo();
                if (ri != null && ri.getMax() > 1000f) continue; // milisegundos/segundos de video
                if (nearValues(n)) return n;
            }
            return null;
        }

        AccessibilityNodeInfo increase() { return byWord(INC_WORD, "+"); }

        AccessibilityNodeInfo decrease() {
            AccessibilityNodeInfo n = byWord(DEC_WORD, "−");
            return n != null ? n : byWord(DEC_WORD, "-");
        }

        private AccessibilityNodeInfo byWord(Pattern p, String sym) {
            if (values().isEmpty()) return null;
            for (AccessibilityNodeInfo n : nodes) {
                if (!n.isVisibleToUser()) continue;
                String d = lower(n.getContentDescription());
                String t = n.getText() == null ? "" : n.getText().toString().trim();
                if (((!d.isEmpty() && p.matcher(d).find()) || t.equals(sym)) && nearValues(n)) return n;
            }
            return null;
        }

        /** ¿Sigue abierta la hoja de opciones donde tocamos "Velocidad de reproducción"? */
        boolean menuStillOpen(String text, Rect bounds) {
            if (text == null || bounds == null) return false;
            for (AccessibilityNodeInfo n : nodes) {
                CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
                if (t == null || !text.equals(t.toString()) || !n.isVisibleToUser()) continue;
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.equals(bounds)) return true;
            }
            return false;
        }

        private boolean clickableUp(AccessibilityNodeInfo n) {
            AccessibilityNodeInfo c = n;
            for (int i = 0; i < 3 && c != null; i++) {
                if (c.isClickable()) return true;
                c = c.getParent();
            }
            return false;
        }

        boolean panelOpen() {
            if (values().isEmpty()) return false;
            if (seekbar() != null) return true;
            if (increase() != null || decrease() != null) return true;
            List<Float> distinct = new ArrayList<>();
            for (AccessibilityNodeInfo n : values()) {
                Float v = valueOf(n);
                boolean seen = false;
                for (Float f : distinct) if (Math.abs(f - v) < 0.001f) seen = true;
                if (!seen) distinct.add(v);
            }
            return distinct.size() >= 2;
        }

        /** Velocidad actual mostrada por YouTube, si se puede leer. */
        Float current() {
            List<AccessibilityNodeInfo> vals = valueNodes();
            for (AccessibilityNodeInfo n : vals) if (n.isSelected() || n.isChecked()) return valueOf(n);
            for (AccessibilityNodeInfo n : vals) if (!clickableUp(n)) return valueOf(n);
            if (vals.size() == 1) return valueOf(vals.get(0));
            AccessibilityNodeInfo bar = seekbar();
            if (bar != null && bar.getRangeInfo() != null) {
                AccessibilityNodeInfo.RangeInfo ri = bar.getRangeInfo();
                if (ri.getMin() >= 0.05f && ri.getMax() <= 4.5f) return ri.getCurrent();
            }
            return null;
        }

        AccessibilityNodeInfo preset(float goal) {
            for (AccessibilityNodeInfo n : valueNodes()) {
                Float v = valueOf(n);
                if (v != null && Math.abs(v - goal) < 0.001f && clickableUp(n)) return n;
            }
            return null;
        }

        /** Máxima velocidad que ofrece YouTube en este panel (NaN si no se sabe). */
        float maxAvailable() {
            AccessibilityNodeInfo bar = seekbar();
            if (bar != null && bar.getRangeInfo() != null) {
                AccessibilityNodeInfo.RangeInfo ri = bar.getRangeInfo();
                if (ri.getMin() >= 0.05f && ri.getMax() <= 4.5f && ri.getMax() >= 1.5f) return ri.getMax();
                Float cur = current();
                if (cur != null && ri.getType() == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT) {
                    float m = cur + (ri.getMax() - ri.getCurrent()) * 0.05f;
                    if (m >= 1.5f && m <= 16f) return Math.round(m * 100f) / 100f;
                }
                return Float.NaN;
            }
            if (increase() != null) return Float.NaN; // los botones dirán hasta dónde llega
            float max = Float.NaN;
            for (AccessibilityNodeInfo n : valueNodes()) {
                Float v = valueOf(n);
                if (v != null && clickableUp(n) && (Float.isNaN(max) || v > max)) max = v;
            }
            return max;
        }

        /** Valor de la barra que corresponde a la velocidad pedida. */
        float barValueFor(AccessibilityNodeInfo bar, float goal) {
            AccessibilityNodeInfo.RangeInfo ri = bar.getRangeInfo();
            float min = ri.getMin(), max = ri.getMax();
            float v;
            if (min >= 0.05f && max <= 4.5f && max >= 1.5f) {
                v = goal;                               // la barra ya está en "x"
            } else {
                Float cur = current();
                float perSpeed;
                if (ri.getType() == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT) {
                    float avail = maxAvailable();
                    float top = Float.isNaN(avail) ? 2f : avail;
                    perSpeed = 100f / (top - 0.25f);
                } else {
                    perSpeed = 20f;                     // pasos de 0.05x
                }
                if (cur != null) v = ri.getCurrent() + (goal - cur) * perSpeed;
                else v = min + (goal - 0.25f) * perSpeed;
            }
            return Math.max(min, Math.min(max, v));
        }
    }
}
