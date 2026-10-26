package com.luis.velyt;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.Locale;

public class SpeedService extends AccessibilityService {

    static final String YT = "com.google.android.youtube";
    static final String TAG = "VYT";
    static final float MIN = 0.25f, MAX = 4f, STEP = 0.25f;

    final Handler h = new Handler(Looper.getMainLooper());
    Overlay overlay;
    Automator automator;
    MediaSessionManager msm;
    AudioManager audio;
    MediaController yt;
    boolean sessionPerm;
    boolean ytForeground;
    long lastAudio;
    float target = 1f;
    boolean pending;
    boolean scheduled;
    boolean covering;
    long leftYtAt;
    float lastSessionSpeed = -1f;
    boolean connected;

    // Cerrado con la X
    boolean hideVideo;        // hasta el próximo video
    boolean hideUntilReopen;  // hasta salir de YouTube y volver
    String hiddenKey;
    long inactiveSince;

    private final MediaController.Callback controllerCb = new MediaController.Callback() {
        @Override public void onPlaybackStateChanged(PlaybackState s) { onSessionState(s); evaluate(); }
        @Override public void onMetadataChanged(MediaMetadata m) { evaluate(); }
        @Override public void onSessionDestroyed() { setController(null); evaluate(); }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsCb = this::onSessions;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            periodic();
            h.postDelayed(this, 1000);
        }
    };

    private final Runnable applyRun = this::apply;
    private final Runnable hideRun = () -> { hidePending = false; if (overlay != null) overlay.hide(); };
    private final Runnable uncoverRun = () -> { if (overlay != null) overlay.uncover(); };
    private boolean hidePending;

    @Override
    protected void onServiceConnected() {
        connected = true;
        overlay = new Overlay(this);
        automator = new Automator(this);
        msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        trySessions();
        h.post(tick);
        Log.i(TAG, "VYT_READY");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (!connected) return;
        int t = e.getEventType();
        if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || t == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (!updateForeground() && t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getPackageName() != null) {
                String p = e.getPackageName().toString();
                if (!p.equals(getPackageName()) && !p.equals("com.android.systemui")) setYtForeground(YT.equals(p));
            }
            evaluate();
        }
    }

    @Override public void onInterrupt() { }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (overlay != null) h.postDelayed(overlay::reclamp, 300);
    }

    @Override
    public void onDestroy() {
        connected = false;
        h.removeCallbacksAndMessages(null);
        if (overlay != null) { overlay.uncover(); overlay.hide(); }
        if (msm != null && sessionPerm) {
            try { msm.removeOnActiveSessionsChangedListener(sessionsCb); } catch (Exception ignored) { }
        }
        setController(null);
        super.onDestroy();
    }

    // ---------- ¿Hay un video en YouTube? ----------

    void trySessions() {
        if (sessionPerm || msm == null) return;
        try {
            ComponentName cn = new ComponentName(this, MediaListener.class);
            msm.addOnActiveSessionsChangedListener(sessionsCb, cn, h);
            sessionPerm = true;
            onSessions(msm.getActiveSessions(cn));
            Log.i(TAG, "VYT_SESSIONS ok");
        } catch (SecurityException ex) {
            sessionPerm = false;
        }
    }

    void onSessions(List<MediaController> list) {
        MediaController found = null;
        if (list != null) {
            for (MediaController c : list) {
                if (YT.equals(c.getPackageName())) { found = c; break; }
            }
        }
        setController(found);
        evaluate();
    }

    void setController(MediaController c) {
        if (yt != null && c != null && yt.getSessionToken().equals(c.getSessionToken())) return;
        if (yt != null) {
            try { yt.unregisterCallback(controllerCb); } catch (Exception ignored) { }
        }
        yt = c;
        lastSessionSpeed = -1f;
        if (yt != null) {
            yt.registerCallback(controllerCb, h);
            onSessionState(yt.getPlaybackState());
        }
    }

    void onSessionState(PlaybackState s) {
        if (s == null || automator == null) return;
        float sp = s.getPlaybackSpeed();
        if (sp <= 0f) return;
        // Si la velocidad cambió desde YouTube (no por nosotros), la reflejamos.
        if (lastSessionSpeed > 0f && Math.abs(sp - lastSessionSpeed) > 0.01f && !automator.busy && !scheduled) {
            target = clamp(sp);
            overlay.setSpeed(target);
        }
        lastSessionSpeed = sp;
    }

    boolean videoActive() {
        if (yt != null) {
            PlaybackState s = yt.getPlaybackState();
            if (s == null) return false;
            switch (s.getState()) {
                case PlaybackState.STATE_PLAYING:
                case PlaybackState.STATE_PAUSED:
                case PlaybackState.STATE_BUFFERING:
                case PlaybackState.STATE_CONNECTING:
                case PlaybackState.STATE_FAST_FORWARDING:
                case PlaybackState.STATE_REWINDING:
                    return true;
                default:
                    return false;
            }
        }
        // Sin sesión de YouTube visible: se usa el audio como respaldo.
        return System.currentTimeMillis() - lastAudio < 5000;
    }

    /** Identifica el video actual (título + duración), si YouTube lo informa. */
    String videoKey() {
        if (yt == null) return null;
        try {
            MediaMetadata m = yt.getMetadata();
            if (m == null) return null;
            String t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (t == null) return null;
            return t + "|" + m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        } catch (Exception e) {
            return null;
        }
    }

    void periodic() {
        trySessions();
        if (audio != null && audio.isMusicActive()) lastAudio = System.currentTimeMillis();
        updateForeground();
        evaluate();
    }

    /** Devuelve true si pudo determinar la app en primer plano. */
    boolean updateForeground() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                if (!w.isActive() && !w.isFocused()) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || r.getPackageName() == null) continue;
                String p = r.getPackageName().toString();
                if (p.equals(getPackageName())) continue;
                setYtForeground(YT.equals(p));
                return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    void setYtForeground(boolean b) {
        if (b == ytForeground) return;
        ytForeground = b;
        Log.i(TAG, "VYT_FG youtube=" + b);
    }

    void evaluate() {
        if (overlay == null) return;
        boolean active = videoActive();

        long nowMs = System.currentTimeMillis();
        if (ytForeground) leftYtAt = 0;
        else if (leftYtAt == 0) leftYtAt = nowMs;
        // Fuera de YouTube: nunca dejar la foto encima de otra app.
        if (!ytForeground && overlay.covered) overlay.uncover();
        // "Al reabrir YouTube": solo cuenta si se salió de YouTube de verdad (1.5 s).
        if (hideUntilReopen && !ytForeground && nowMs - leftYtAt > 1500) {
            hideUntilReopen = false;
            Log.i(TAG, "VYT_RESTORE reabrir");
        }
        if (hideVideo) {
            long now = System.currentTimeMillis();
            String k = videoKey();
            if (hiddenKey == null && k != null) hiddenKey = k;
            if (!active) {
                if (inactiveSince == 0) inactiveSince = now;
                if (now - inactiveSince > 2000) { hideVideo = false; Log.i(TAG, "VYT_RESTORE video_terminado"); }
            } else {
                inactiveSince = 0;
            }
            if (hideVideo && k != null && hiddenKey != null && !k.equals(hiddenKey)) {
                hideVideo = false;
                Log.i(TAG, "VYT_RESTORE otro_video");
            }
        }

        boolean dismissed = hideVideo || hideUntilReopen;
        boolean want = ytForeground && !dismissed && (active || automator.busy);
        if (want) {
            h.removeCallbacks(hideRun);
            hidePending = false;
            overlay.show();
        } else if (dismissed || automator.busy) {
            h.removeCallbacks(hideRun);
            hidePending = false;
            overlay.hide();
        } else if (overlay.shown && !hidePending) {
            // Un pequeño margen evita que parpadee (y que se pierdan toques)
            // cuando YouTube cambia de estado por un instante.
            hidePending = true;
            h.postDelayed(hideRun, ytForeground ? 1500 : 600);
        }
    }

    // ---------- Botones ----------

    static float clamp(float v) {
        v = Math.round(v * 100f) / 100f;
        return Math.max(MIN, Math.min(MAX, v));
    }

    void step(int dir) {
        float n = dir > 0
                ? (float) (Math.floor(target / STEP + 1e-3) * STEP + STEP)
                : (float) (Math.ceil(target / STEP - 1e-3) * STEP - STEP);
        n = clamp(n);
        if (Math.abs(n - target) < 0.001f) { overlay.flash(); return; }
        target = n;
        overlay.setSpeed(target);
        schedule();
    }

    void reset() {
        target = 1f;
        overlay.setSpeed(target);
        schedule();
    }

    /** X: "Próximo video" (untilReopen=false) o "Al reabrir YouTube" (true). */
    void dismiss(boolean untilReopen) {
        if (untilReopen) {
            hideUntilReopen = true;
        } else {
            hideVideo = true;
            hiddenKey = videoKey();
            inactiveSince = 0;
        }
        Log.i(TAG, "VYT_DISMISS " + (untilReopen ? "reabrir" : "video"));
        evaluate();
    }

    void schedule() {
        scheduled = true;
        h.removeCallbacks(applyRun);
        h.postDelayed(applyRun, 400);
    }

    void apply() {
        scheduled = false;
        if (automator.busy || covering) { pending = true; return; }
        pending = false;
        h.removeCallbacks(uncoverRun);
        // Primero se tapa la pantalla con una foto de ella misma; luego se toca el menú.
        covering = true;
        overlay.cover(() -> { covering = false; runAutomation(); });
    }

    private void runAutomation() {
        final float t = target;
        automator.setSpeed(t, yt, r -> {
            Log.i(TAG, String.format(Locale.US, "VYT_APPLY target=%.2f result=%.2f ok=%b via=%s",
                    t, r.speed, r.ok, r.via));
            if (r.ok && Math.abs(target - t) < 0.001f) {
                target = clamp(r.speed);
                overlay.setSpeed(target);
            } else if (!r.ok) {
                overlay.flash();
            }
            if (pending) {
                pending = false;
                if (Math.abs(target - t) > 0.001f) { runAutomation(); return; }
            }
            // Se destapa cuando YouTube terminó de cerrar su menú.
            h.postDelayed(uncoverRun, 250);
            evaluate();
        });
    }
}
