#!/usr/bin/env bash
# Prueba de punta a punta en un emulador Android real:
#   app Velocidad YT  +  "YouTube de prueba" (imita el menú de velocidad de YouTube).
# Uso: bash ci/emulator-test.sh <VelocidadYT.apk> <fakeyt.apk> <carpeta-salida>
set -uo pipefail

APP="$1"; FAKE="$2"; OUT="${3:-shots}"
mkdir -p "$OUT"
# Guarda toda la salida de la prueba y, pase lo que pase, el registro al terminar.
exec > >(tee -a "$OUT/salida.txt") 2>&1
trap 'adb logcat -d -v brief -s VYT:I FAKEYT:I > "$OUT/logcat.txt" 2>/dev/null; adb logcat -d -v brief AndroidRuntime:E "*:S" > "$OUT/crash.txt" 2>/dev/null' EXIT
echo "inicio $(date -u)"
YT=com.google.android.youtube
PASS=0

shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
MARK=0
# Todo el registro (no se borra nunca; cada paso deja una marca).
alllogs() { adb logcat -d -v brief -s VYT:I FAKEYT:I; }
# Solo lo ocurrido desde la última marca.
logs() { alllogs | awk -v m="VYT_MARK_$MARK" 'f; index($0, m) {f=1}'; }
save_logs() { alllogs > "$OUT/logcat.txt"; }
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
clear_logs() {
  MARK=$((MARK+1))
  adb shell log -p i -t VYT "VYT_MARK_$MARK"
  local i; for ((i=0; i<20; i++)); do alllogs | grep -q "VYT_MARK_$MARK" && return 0; sleep 0.1; done
}
wait_for() { # patrón, segundos
  local pat="$1" t="${2:-20}" i
  for ((i=0; i<t*4; i++)); do
    if logs | grep -qE "$pat"; then return 0; fi
    sleep 0.25
  done
  return 1
}
last() { logs | grep -E "$1" | tail -1; }
last_any() { alllogs | grep -E "$1" | tail -1; }
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
  local i; for ((i=0; i<32; i++)); do alllogs | grep -q "VYT_POS mode=expanded" && break; sleep 0.25; done
  sleep 0.3
  local P; P=$(last_any "VYT_POS mode=expanded")
  [[ -n "$P" ]] || fail "no se publicó la posición de los botones"
  read -r MX MY <<<"$(xy minus "$P")"
  read -r LX LY <<<"$(xy label "$P")"
  read -r PX PY <<<"$(xy plus "$P")"
  read -r NX NY <<<"$(xy min "$P")"
  read -r CX CY <<<"$(xy close "$P")"
}
# El menú de YouTube debe abrirse y cerrarse SIEMPRE con la cubierta puesta (que no se vea).
cover_ok() {
  logs | grep -E "VYT_COVER (on|off)|FAKEYT dialogs=" | awk '
    /VYT_COVER on/  { c=1; ons++ }
    /VYT_COVER off/ { c=0 }
    /dialogs=[1-9]/ { opened++; if (!c) bad=1 }
    /dialogs=0/     { if (!c) bad=1 }
    END { if (ons==0 || opened==0 || bad || c) exit 1 }' \
    || fail "el menú de YouTube se vio (no estaba tapado): $(logs | grep -E 'VYT_COVER|dialogs=' | tr '\n' ' ')"
}
last_state_is() {
  local st; st=$(last "VYT_STATE overlay=")
  [[ "$st" == *"overlay=$1"* ]]
}
expect_speed() { # objetivo con 2 decimales, segundos
  local s="$1" t="${2:-25}"
  wait_for "VYT_APPLY target=$s result=$s ok=true" "$t" || fail "no llegó a ${s}x  [$(last VYT_APPLY)]"
  local f; f=$(last "FAKEYT speed=")
  [[ "$f" == *"speed=$s"* ]] || fail "YouTube de prueba quedó en otra velocidad: $f (esperado $s)"
  no_decoys
  menus_closed
  cover_ok
}

echo "== Instalación"
adb install -r "$APP" || fail "no se pudo instalar Velocidad YT"
adb install -r "$FAKE" || fail "no se pudo instalar la YouTube de prueba"
adb logcat -G 16M || true
adb logcat -c
clear_logs
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
wait_for "VYT_COVER on" 8 || fail "no se puso la cubierta"
sleep 0.4
shot 02b-durante-el-cambio-tapado
expect_speed 1.25
ok "1.25x aplicado en YouTube, sin que se vea el menú, y menú cerrado"

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
read -r SW SH <<<"$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr 'x' ' ')"
TX=$((BX > SW / 2 ? BX - SW / 3 : BX + SW / 3)); TY=$((BY < SH / 2 ? BY + SH / 4 : BY - SH / 4))
echo "pantalla ${SW}x${SH}: arrastrar burbuja $BX,$BY → $TX,$TY"
adb shell input swipe "$BX" "$BY" "$TX" "$TY" 600
wait_for "VYT_MOVED" 5 || fail "la burbuja no se pudo arrastrar"
logs | grep -q "VYT_MODE expanded" && fail "arrastrar no debe abrir la burbuja"
sleep 0.5
read -r B2X B2Y <<<"$(xy bubble "$(last 'VYT_POS mode=bubble')")"
DX=$(( B2X - BX )); DY=$(( B2Y - BY ))
(( ${DX#-} > SW / 5 && ${DY#-} > SH / 6 )) || fail "la burbuja no se movió lo suficiente ($BX,$BY → $B2X,$B2Y)"
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

echo "== 10. X → tocar fuera cancela"
clear_logs
read_pos
tap "$CX" "$CY"
wait_for "VYT_MODE close" 5 || fail "la X no abrió las opciones"
wait_for "VYT_POS mode=close" 5 || fail "no se publicó la posición de las opciones"
P=$(last "VYT_POS mode=close")
read -r OX OY <<<"$(xy next "$P")"
read -r RX RY <<<"$(xy reopen "$P")"
shot 07-x-opciones
tap 8 30
wait_for "VYT_MODE expanded" 5 || fail "tocar fuera no cerró las opciones"
no_decoys
ok "la X muestra las dos opciones y tocar fuera las cancela"

echo "== 11. X → Próximo video"
clear_logs
read_pos
tap "$CX" "$CY"
wait_for "VYT_POS mode=close" 5 || fail "no se abrieron las opciones"
P=$(last "VYT_POS mode=close"); read -r OX OY <<<"$(xy next "$P")"
tap "$OX" "$OY"
wait_for "VYT_DISMISS video" 5 || fail "Próximo video no respondió"
sleep 3
last_state_is hidden || fail "el botón no se quitó con Próximo video"
shot 08-cerrado-este-video
clear_logs
adb shell am start -W -n $YT/com.test.fakeyt.PlayerActivity --es video "Video 2" >/dev/null
wait_for "VYT_RESTORE otro_video" 10 || fail "no detectó el video nuevo"
wait_for "VYT_STATE overlay=shown" 10 || fail "no reapareció en el video siguiente"
ok "Próximo video: se oculta y vuelve en el siguiente video"

echo "== 12. X → Al reabrir YouTube"
clear_logs
read_pos
tap "$CX" "$CY"
wait_for "VYT_POS mode=close" 5 || fail "no se abrieron las opciones"
P=$(last "VYT_POS mode=close"); read -r RX RY <<<"$(xy reopen "$P")"
tap "$RX" "$RY"
wait_for "VYT_DISMISS reabrir" 5 || fail "Al reabrir YouTube no respondió"
adb shell am start -W -n $YT/com.test.fakeyt.PlayerActivity --es video "Video 3" >/dev/null
sleep 4
last_state_is hidden || fail "reapareció en otro video aunque se eligió Al reabrir YouTube"
clear_logs
adb shell input keyevent KEYCODE_HOME
wait_for "VYT_RESTORE reabrir" 10 || fail "no se reactivó al salir de YouTube"
adb shell am start -W -n $YT/com.test.fakeyt.PlayerActivity >/dev/null
wait_for "VYT_STATE overlay=shown" 10 || fail "no reapareció al reabrir YouTube"
ok "Al reabrir YouTube: sigue oculto en otros videos y vuelve al reabrir YouTube"

echo "== 13. Salir de YouTube → desaparece; volver → reaparece"
clear_logs
adb shell input keyevent KEYCODE_HOME
wait_for "VYT_STATE overlay=hidden" 10 || fail "el botón siguió visible fuera de YouTube"
shot 07-fuera-de-youtube
clear_logs
adb shell am start -W -n $YT/com.test.fakeyt.PlayerActivity >/dev/null
wait_for "VYT_STATE overlay=shown" 10 || fail "no reapareció al volver al video"
ok "se oculta fuera de YouTube y reaparece al volver"

echo "== 14. Cerrar el video → desaparece"
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
