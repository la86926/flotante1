package com.luis.velyt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Al tocar la notificación, vuelve a mostrar el botón flotante. No hace nada más. */
public class RestoreReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        SpeedService s = SpeedService.instance;
        if (s != null) s.h.post(s::restore);
    }
}
