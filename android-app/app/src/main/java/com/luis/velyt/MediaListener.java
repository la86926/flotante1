package com.luis.velyt;

import android.service.notification.NotificationListenerService;

/**
 * No lee notificaciones. Solo existe porque Android exige este permiso
 * para saber qué app está reproduciendo un video (sesiones multimedia).
 */
public class MediaListener extends NotificationListenerService {
}
