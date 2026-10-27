package com.luis.velyt;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Registro breve en memoria para "Copiar diagnóstico". */
final class D {
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    static volatile String tree = "";
    private static final SimpleDateFormat F = new SimpleDateFormat("HH:mm:ss", Locale.US);

    static void i(String m) {
        Log.i(SpeedService.TAG, m);
        synchronized (LINES) {
            LINES.add(F.format(new Date()) + " " + m);
            while (LINES.size() > 250) LINES.poll();
        }
    }

    static String lines() {
        synchronized (LINES) { return String.join("\n", LINES); }
    }
}
