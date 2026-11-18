#!/system/bin/sh
# Invoked by the module UID through its own su grant.
umask 077
apk=$1
rpm=$2
bb=/data/adb/ksu/bin/busybox
cap=/sys/module/k90_fan_cap/parameters/ceiling_rpm
legacy=/data/adb/modules/k90_ultra_fan_control

finish() {
    echo "K90_FAN_RESULT=$1"
    [ "$1" = ok ] && exit 0
    exit 1
}

[ "$(id -u)" = 0 ] || finish root_required
[ -x "$bb" ] || finish kernel_su_required
case "$rpm" in
    0) ;;
    1[2-9][0-9]00) [ "$rpm" -le 19800 ] || finish invalid_limit ;;
    *) finish invalid_limit ;;
esac
exec 9>/data/adb/k90-fan-lsp.lock || finish busy
"$bb" flock -xn 9 || finish busy
if [ -f "$legacy/limit.conf" ] && [ ! -e "$legacy/disable" ] && [ ! -e "$legacy/remove" ] &&
   [ "$(cat "$legacy/limit.conf")" != 0 ]; then
    finish legacy_active
fi
if [ "$rpm" = 0 ] && [ ! -d /sys/module/k90_fan_cap ]; then finish ok; fi
work=$("$bb" mktemp -d /data/adb/k90-fan-lsp.XXXXXXXX) || finish config_write_failed
trap '"$bb" rm -f "$work/fanctl.sh" "$work/k90_fan_cap.ko" "$work/.fan.lock" "$work/limit.conf"; "$bb" rmdir "$work"' EXIT
"$bb" unzip -p "$apk" assets/fanctl.sh > "$work/fanctl.sh" || finish driver_asset
if [ "$rpm" = 0 ]; then
    output=$("$bb" sh "$work/fanctl.sh" reset 2>&1)
    result=$?
    # An inert helper can remain loaded when unloading is unavailable.
    if [ ! -d /sys/module/k90_fan_cap ] || [ "$(cat "$cap" 2>/dev/null)" = 0 ]; then finish ok; fi
else
    "$bb" unzip -p "$apk" assets/k90_fan_cap.ko > "$work/k90_fan_cap.ko" || finish driver_asset
    output=$("$bb" sh "$work/fanctl.sh" set "$rpm" 2>&1)
    result=$?
    if [ "$result" = 0 ] && [ "$(cat "$cap" 2>/dev/null)" = "$rpm" ]; then finish ok; fi
fi
case "$output" in
    unsupported_*|fan_unavailable|kernel_*|driver_state|busy|config_write_failed|rollback_failed) finish "$output" ;;
    *) finish driver_state ;;
esac
