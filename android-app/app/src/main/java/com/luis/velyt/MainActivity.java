package com.luis.velyt;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Pantalla de configuración: solo dos permisos y listo. */
public class MainActivity extends Activity {

    private static final int INK = 0xFF1C1C1E, MUTED = 0xFF6E6E73, OK = 0xFF1E8E3E, ACCENT = 0xFF1B2A4A;

    private TextView a11yState, notifState;
    private TextView a11yBtn, notifBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(24), dp(40), dp(24), dp(24));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_launcher);
        root.addView(logo, new LinearLayout.LayoutParams(dp(64), dp(64)));

        TextView title = text("Velocidad YT", 26, INK, true);
        title.setPadding(0, dp(16), 0, dp(4));
        root.addView(title);
        TextView sub = text("Botón flotante para cambiar la velocidad en YouTube.", 15, MUTED, false);
        sub.setPadding(0, 0, 0, dp(28));
        root.addView(sub);

        a11yState = new TextView(this);
        a11yBtn = button("Activar");
        a11yBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(row("1", "Accesibilidad", "Para tocar el menú de velocidad por ti.", a11yState, a11yBtn));

        notifState = new TextView(this);
        notifBtn = button("Activar");
        notifBtn.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        root.addView(row("2", "Acceso a notificaciones", "Para saber cuándo hay un video en reproducción.", notifState, notifBtn));

        TextView hint = text("¿Interruptor bloqueado? Información de la app → ⋮ → Permitir ajustes restringidos.", 13, MUTED, false);
        hint.setPadding(0, dp(18), 0, dp(6));
        root.addView(hint);
        TextView info = link("Abrir información de la app");
        info.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName()))));
        root.addView(info);

        TextView diag = link("Copiar diagnóstico");
        diag.setTextColor(MUTED);
        diag.setOnClickListener(v -> copyDiag());
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dp.topMargin = dp(24);
        root.addView(diag, dp);

        TextView open = button("Abrir YouTube");
        open.setTextSize(16);
        open.setPadding(dp(18), dp(14), dp(18), dp(14));
        open.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage(SpeedService.YT);
            if (i != null) startActivity(i);
        });
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        op.topMargin = dp(32);
        root.addView(open, op);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.WHITE);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean a = accessibilityOn(), n = notificationsOn();
        setState(a11yState, a11yBtn, a);
        setState(notifState, notifBtn, n);
    }

    private void setState(TextView state, TextView btn, boolean on) {
        state.setText(on ? "✓ Activado" : "");
        state.setTextColor(OK);
        btn.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private boolean accessibilityOn() {
        String list = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(list)) return false;
        String me = new ComponentName(this, SpeedService.class).flattenToString();
        String meShort = new ComponentName(this, SpeedService.class).flattenToShortString();
        for (String s : list.split(":")) if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(meShort)) return true;
        return false;
    }

    private boolean notificationsOn() {
        String list = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return list != null && list.contains(getPackageName());
    }

    private void copyDiag() {
        String yt = "?";
        try { yt = getPackageManager().getPackageInfo(SpeedService.YT, 0).versionName; } catch (Exception ignored) { }
        String txt = "Velocidad YT " + BuildConfigLite.VERSION + " | Android " + android.os.Build.VERSION.RELEASE
                + " (API " + android.os.Build.VERSION.SDK_INT + ") | " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " | YouTube " + yt + "\n\n" + D.lines() + "\n\n--- pantalla en el último fallo ---\n" + D.tree;
        android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("diagnóstico", txt));
        android.widget.Toast.makeText(this, "Copiado", android.widget.Toast.LENGTH_SHORT).show();
    }

    // ---------- Vistas ----------

    private View row(String n, String title, String desc, TextView state, TextView btn) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(16), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF2F2F7);
        bg.setCornerRadius(dp(14));
        r.setBackground(bg);

        TextView num = text(n, 15, Color.WHITE, true);
        num.setGravity(Gravity.CENTER);
        GradientDrawable c = new GradientDrawable();
        c.setShape(GradientDrawable.OVAL);
        c.setColor(ACCENT);
        num.setBackground(c);
        r.addView(num, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(12), 0, dp(8), 0);
        col.addView(text(title, 16, INK, true));
        col.addView(text(desc, 13, MUTED, false));
        state.setTextSize(13);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(state);
        r.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(btn);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        r.setLayoutParams(lp);
        return r;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView button(String s) {
        TextView b = text(s, 14, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(9), dp(14), dp(9));
        GradientDrawable g = new GradientDrawable();
        g.setColor(ACCENT);
        g.setCornerRadius(dp(22));
        b.setBackground(g);
        return b;
    }

    private TextView link(String s) {
        TextView t = text(s, 14, ACCENT, true);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
