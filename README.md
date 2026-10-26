<p align="center"><img src="logo.svg" width="96" alt="Velocidad YT"></p>

# Velocidad YT

App Android con un botón flotante para subir o bajar la velocidad de los videos en la app oficial de YouTube, sin entrar al menú.

- Aparece solo cuando hay un video en YouTube; se oculta al salir.
- `−` / `+` cambian de 0.25x en 0.25x (de 0.25x a 4x; hasta donde tu YouTube lo permita).
- Tocar el número vuelve a 1x. `⌄` lo minimiza en una burbuja. Ambos se arrastran con el dedo.

## Instalar

1. Descarga **VelocidadYT.apk** desde [Releases](https://github.com/la86926/flotante1/releases/latest) e instálalo.
2. Abre la app y activa los dos permisos (Accesibilidad y Acceso a notificaciones).
3. Si Android bloquea el interruptor: Información de la app → ⋮ → *Permitir ajustes restringidos*.

## Cómo se prueba

Cada cambio se compila y se prueba en emuladores Android 11 y 14 con una "YouTube de prueba" (`android-app/fakeyt`) que imita el menú de velocidad. Solo si pasa la prueba se publica el APK.

Sin los secretos `ANDROID_KEYSTORE_B64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` se firma con una clave de prueba: para instalar una versión nueva hay que desinstalar la anterior.
