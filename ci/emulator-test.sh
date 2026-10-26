#!/usr/bin/env bash
# Prueba de punta a punta en un emulador Android real:
#   app Velocidad YT  +  "YouTube de prueba" (imita el menú de velocidad de YouTube).
# Uso: bash ci/emulator-test.sh <VelocidadYT.apk> <fakeyt.apk> <carpeta-salida>
set -uo pipefail

APP="$1"; FAKE="$2"; OUT="${3:-shots}"
mkdir -p "$OUT"
YT=com.google.android.youtube
PASS=0

shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
logs() { adb logcat -d -v brief -s VYT:I FAKEYT:I; }
save_logs() { logs >> "$OUT/logcat.txt"; }
fail() {
  echo "::error::FALLO: $*"
  echo "FALLO: $*" > "$OUT/resultado.txt"
  save_logs
  adb logcat -d -v brief AndroidRuntime:E '*:S' >> "$OUT/crash.txt" || true
  adb logcat -d -v brief -t 3000 > "$OUT/logcat-completo.txt" || true
  adb shell dumpsys accessibility > "$OUT/dumpsys-accessibility.txt" 2>&1 || true
  adb shell settings get secure enabled_accessibility_services > "$OUT/a11y-settings.txt" 2>&1 || true
  adb shell dumpsys window windows > "$OUT/dumpsys-window.txt" 2>&1 || true
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1 || true
  shot "fallo"
  exit 1
}
ok() { PASS=$((PASS+1)); echo "✔ $*"; }
clear_logs() { save_logs; adb logcat -c; }
wait_for() { # patrón, segundos
  local pat="$1" t="${2:-20}" i
  for ((i=0; i<t*4; i++)); do
    if logs | grep -qE "$pat"; then return 0; fi
    sleep 0.25
  done
  return 1
}
last() { logs | grep -E "$1" | tail -1; }
xy() { echo "$2" | grep -oE "$1=[0-9]+,[0-9]+" | head -1 | cut -d= -f2 | tr ',' ' '; }
tap() { adb shell input tap "$1" "$2"; }
no_decoys() {
  if logs | grep -qE "FAKEYT (decoy|pause|seek|wrong)"; then
    fail "tocó algo que no debía: $(logs | grep -E 'FAKEYT (decoy|pause|seek|wrong)' | head -3)"
  fi
}
menus_closed() {
  sleep 1
  local d; d=$(last "FAKEYT dialogs=")
  [[ -z "$d" || "$d" == *"dialogs=0"* ]] || fail "quedó un menú abierto ($d)"
}
read_pos() {
  wait_for "VYT_POS mode=expanded" 8 || fail "no se publicó la posición de los botones"
  local P; P=$(last "VYT_POS mode=expanded")
  read -r MX MY <<<"$(xy minus "$P")"
  read -r LX LY <<<"$(xy label "$P")"
  read -r PX PY <<<"$(xy plus "$P")"
  read -r NX NY <<<"$(xy min "$P")"
}
expect_speed() { # objetivo con 2 decimales, segundos
  local s="$1" t="${2:-25}"
  wait_for "VYT_APPLY target=$s result=$s ok=true" "$t" || fail "no llegó a ${s}x  [$(last VYT_APPLY)]"
  local f; f=$(last "FAKEYT speed=")
  [[ "$f" == *"speed=$s"* ]] || fail "YouTube de prueba quedó en otra velocidad: $f (esperado $s)"
  no_decoys
  menus_closed
}

echo "== Instalación"
adb install -r "$APP" || fail "no se pudo instalar Velocidad YT"
adb install -r "$FAKE" || fail "no se pudo instalar la YouTube de prueba"
adb logcat -c
adb shell cmd notification allow_listener com.luis.velyt/com.luis.velyt.MediaListener || true
adb shell settings put secure enabled_accessibility_services com.luis.velyt/com.luis.velyt.SpeedService
adb shell settings put secure accessibility_enabled 1
wait_for "VYT_READY" 30 || fail "el servicio de accesibilidad no arrancó"
ok "app instalada y servicio activo"

echo "== 1. Sin video: no debe aparecer"
adb shell am start -W -n $YT/com.test.fakeyt.HomeActivity >/dev/null
sleep 5
logs | grep -q "overlay=shown" && fail "el botón apareció sin ningún video"
shot 01-inicio-sin-boton
ok "en el inicio (sin video) el botón no aparece"

echo "== 2. Al abrir un video: aparece solo"
clear_logs
adb shell am start -W -n $YT/com.test.fakeyt.PlayerActivity >/dev/null
wait_for "VYT_STATE overlay=shown" 15 || fail "el botón no apareció al reproducir el video"
read_pos
sleep 0.5
shot 02-video-con-boton
ok "con video aparece el botón flotante"

echo "== 3. + una vez → 1.25x (valor rápido del panel)"
clear_logs
tap "$PX" "$PY"
expect_speed 1.25
ok "1.25x aplicado en YouTube y menú cerrado"

echo "== 4. + seis veces seguidas → 2.75x (barra del panel)"
clear_logs
for i in 1 2 3 4 5 6; do tap "$PX" "$PY"; sleep 0.15; done
expect_speed 2.75 35
shot 03-275x
ok "2.75x aplicado"

echo "== 5. + seis veces más → tope 4x"
clear_logs
for i in 1 2 3 4 5 6; do tap "$PX" "$PY"; sleep 0.15; done
expect_speed 4.00 35
ok "4x aplicado (no pasa de 4x)"

echo "== 6. − quince veces → mínimo 0.25x"
clear_logs
for i in $(seq 1 15); do tap "$MX" "$MY"; sleep 0.15; done
expect_speed 0.25 40
ok "0.25x aplicado"

echo "== 7. Tocar el número → vuelve a 1x"
clear_logs
tap "$LX" "$LY"
expect_speed 1.00
ok "volvió a 1x"

echo "== 8. Minimizar → burbuja, arrastrar, abrir"
clear_logs
tap "$NX" "$NY"
wait_for "VYT_MODE bubble" 5 || fail "no se minimizó"
wait_for "VYT_POS mode=bubble" 5 || fail "no se publicó la posición de la burbuja"
read -r BX BY <<<"$(xy bubble "$(last 'VYT_POS mode=bubble')")"
shot 04-burbuja
TX=$((BX > 400 ? BX - 300 : BX + 300)); TY=$((BY + 350))
adb shell input swipe "$BX" "$BY" "$TX" "$TY" 600
wait_for "VYT_MOVED" 5 || fail "la burbuja no se pudo arrastrar"
logs | grep -q "VYT_MODE expanded" && fail "arrastrar no debe abrir la burbuja"
sleep 0.5
read -r B2X B2Y <<<"$(xy bubble "$(last 'VYT_POS mode=bubble')")"
DX=$(( B2X - BX )); DY=$(( B2Y - BY ))
(( ${DX#-} > 150 && ${DY#-} > 150 )) || fail "la burbuja no se movió lo suficiente ($BX,$BY → $B2X,$B2Y)"
shot 05-burbuja-movida
tap "$B2X" "$B2Y"
wait_for "VYT_MODE expanded" 5 || fail "tocar la burbuja no abrió los controles"
ok "minimizar, arrastrar y volver a abrir funcionan"

echo "== 9. Con los controles reabiertos, + sigue funcionando"
clear_logs
read_pos
tap "$PX" "$PY"
expect_speed 1.25
shot 06-tras-reabrir
ok "funciona después de mover y reabrir"

echo "== 10. Salir de YouTube → desaparece; volver → reaparece"
clear_logs
adb shell input keyevent KEYCODE_HOME
wait_for "VYT_STATE overlay=hidden" 10 || fail "el botón siguió visible fuera de YouTube"
shot 07-fuera-de-youtube
clear_logs
adb shell am start -W -n $YT/com.test.fakeyt.PlayerActivity >/dev/null
wait_for "VYT_STATE overlay=shown" 10 || fail "no reapareció al volver al video"
ok "se oculta fuera de YouTube y reaparece al volver"

echo "== 11. Cerrar el video → desaparece"
clear_logs
adb shell input keyevent KEYCODE_BACK
wait_for "FAKEYT player closed" 10 || fail "no se cerró el video de prueba"
wait_for "VYT_STATE overlay=hidden" 10 || fail "el botón siguió visible después de cerrar el video"
sleep 2
shot 08-video-cerrado
ok "al cerrar el video el botón desaparece"

save_logs
adb logcat -d -v brief AndroidRuntime:E '*:S' > "$OUT/crash.txt" || true
if grep -q "com.luis.velyt" "$OUT/crash.txt"; then fail "hubo un cierre inesperado de la app"; fi
echo "TODAS LAS PRUEBAS PASARON ($PASS)"
echo "TODAS LAS PRUEBAS PASARON ($PASS)" > "$OUT/resultado.txt"
