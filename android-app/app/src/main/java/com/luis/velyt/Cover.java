package com.luis.velyt;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.media.MediaMetadata;
import android.net.Uri;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Guarda la portada (miniatura) del video que se está viendo, en la mejor calidad que se consiga. */
@android.annotation.TargetApi(29)
final class Cover {

    private static final Pattern IN_URL = Pattern.compile("/vi(?:_webp)?/([A-Za-z0-9_-]{11})/");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    static void save(SpeedService s) {
        MediaMetadata m = null;
        try { if (s.yt != null) m = s.yt.getMetadata(); } catch (Exception ignored) { }
        if (m == null) { D.i("VYT_PORTADA sin_datos"); s.toast("Sin portada"); return; }
        final MediaMetadata md = m;
        final Frame.Info info = Frame.info(s);
        info.pos = -1;
        new Thread(() -> {
            try {
                Bitmap best = null;
                String src = "-", uri = null;
                for (String k : new String[]{MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
                    String u = md.getString(k);
                    if (u != null && !u.isEmpty()) { uri = u; break; }
                }
                // 1) Si se conoce el video, la portada original de YouTube (la más grande que exista).
                String id = null;
                if (uri != null) { Matcher x = IN_URL.matcher(uri); if (x.find()) id = x.group(1); }
                String mid = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
                if (id == null && mid != null && ID.matcher(mid).matches()) id = mid;
                if (id != null) {
                    for (String q : new String[]{"maxresdefault", "sddefault", "hqdefault"}) {
                        best = http("https://i.ytimg.com/vi/" + id + "/" + q + ".jpg");
                        if (best != null && best.getWidth() > 200) { src = q; break; }
                        best = null;
                    }
                }
                // 2) La dirección de imagen que informa YouTube.
                if (best == null && uri != null) {
                    if (uri.startsWith("http")) {
                        best = http(uri);
                    } else {
                        try (InputStream in = s.getContentResolver().openInputStream(Uri.parse(uri))) {
                            best = BitmapFactory.decodeStream(in);
                        } catch (Exception ignored) { }
                    }
                    if (best != null) src = "uri";
                }
                // 3) La imagen que YouTube muestra en su notificación.
                Bitmap b2 = null;
                for (String k : new String[]{MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART,
                        MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
                    b2 = md.getBitmap(k);
                    if (b2 != null) break;
                }
                if (b2 != null && (best == null || b2.getWidth() > best.getWidth())) { best = b2; src = "sesion"; }
                if (best == null) {
                    D.i("VYT_PORTADA sin_imagen datos=" + md.keySet());
                    s.toast("Sin portada");
                    return;
                }
                if (best.getConfig() != Bitmap.Config.ARGB_8888) best = best.copy(Bitmap.Config.ARGB_8888, false);
                // Quita franjas negras parejas (las portadas pequeñas de YouTube las traen).
                Rect t = Frame.trim(best, new Rect(0, 0, best.getWidth(), best.getHeight()));
                if (t.width() < best.getWidth() || t.height() < best.getHeight())
                    best = Bitmap.createBitmap(best, t.left, t.top, t.width(), t.height());
                String n = Frame.name(info, false);
                n = n.substring(0, n.length() - 4) + " (portada).png";
                Frame.write(s, best, n);
                D.i("VYT_PORTADA ok name=" + n + " size=" + best.getWidth() + "x" + best.getHeight() + " src=" + src);
                s.toast("Portada guardada");
            } catch (Exception e) {
                D.i("VYT_PORTADA error " + e);
                s.toast("No se pudo guardar");
            }
        }).start();
    }

    private static Bitmap http(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(7000);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) { return BitmapFactory.decodeStream(in); }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
