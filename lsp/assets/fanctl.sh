#!/system/bin/sh

MODDIR=${0%/*}
FAN=/sys/devices/platform/soc/soc:xiaomi_fan
MODULE_SYS=/sys/module/k90_fan_cap
CAP=$MODULE_SYS/parameters/ceiling_rpm
TARGET=$MODULE_SYS/parameters/target_rpm
DRIVER_NOTE=/sys/module/mca_pwm_fan/notes/.note.gnu.build-id
KERNEL_RELEASE=6.6.142-android15-8-4k-Haruhi-26w37f
KERNEL_BTF_HASH=8ee2c5a827427fca02281944010b99b732d9b8c060e0e21cdd9f2a9ed2a7cc5f
DRIVER_NOTE_HASH=2e8167ea1563d68f705e2a2a4d4d51890d01ff8d3731dce44507bfc54212bd76
CONFIG=$MODDIR/limit.conf
BUSYBOX=/data/adb/ksu/bin/busybox

fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

read_number() {
    [ -r "$1" ] && IFS= read -r number < "$1" 2>/dev/null || return 1
    case "$number" in
        '' | *[!0-9]* | 0?* | ????????*) return 1 ;;
    esac
    printf '%s\n' "$number"
}

sha256() {
    digest=$("$BUSYBOX" sha256sum "$1" 2>/dev/null) || return 1
    printf '%s\n' "${digest%% *}"
}

valid_limit() {
    case "$1" in
        1[2-9][0-9]00) [ "$1" -le 19800 ] ;;
        *) return 1 ;;
    esac
}

stored_limit() {
    saved=$(read_number "$CONFIG")
    if valid_limit "$saved"; then
        printf '%s\n' "$saved"
    else
        printf '0\n'
    fi
}

compatibility() {
    if [ "$(/system/bin/getprop ro.product.device)" != warsaw ]; then
        printf 'unsupported_device\n'
    elif [ "$(read_number "$FAN/fan_support")" != 1 ]; then
        printf 'fan_unavailable\n'
    elif [ "$(uname -r)" != "$KERNEL_RELEASE" ]; then
        printf 'unsupported_kernel\n'
    elif [ "$(sha256 "$DRIVER_NOTE")" != "$DRIVER_NOTE_HASH" ]; then
        printf 'unsupported_driver\n'
    elif [ -e "$MODDIR/disable" ] || [ -e "$MODDIR/remove" ]; then
        printf 'disabled\n'
    else
        printf 'ready\n'
    fi
}

status() {
    result=$(compatibility)
    rpm=$(read_number "$FAN/real_speed") || rpm=null
    level=$(read_number "$FAN/target_level") || level=null
    cap=0
    target=null

    if [ "$rpm" = 2 ] || { [ "$rpm" != null ] && [ "$rpm" -gt 40000 ]; }; then
        rpm=null
    fi
    if [ -d "$MODULE_SYS" ]; then
        cap=$(read_number "$CAP") || cap=null
        target=$(read_number "$TARGET") || target=null
        if [ "$cap" = null ] || [ "$target" = null ]; then
            result=driver_state
        fi
    elif [ "$result" = ready ]; then
        case "$level" in
            0) target=0 ;;
            1) target=12000 ;;
            2) target=14000 ;;
            3) target=16000 ;;
            4) target=19800 ;;
        esac
    fi
    printf '{"compatibility":"%s","rpm":%s,"level":%s,"target":%s,"cap":%s,"saved":%s}\n' \
        "$result" "$rpm" "$level" "$target" "$cap" "$(stored_limit)"
}

lock_settings() {
    exec 9>"$MODDIR/.fan.lock" || fail config_write_failed
    "$BUSYBOX" flock -xn 9 || fail busy
}

ensure_driver() {
    result=$(compatibility)
    [ "$result" = ready ] || fail "$result"
    if [ ! -d "$MODULE_SYS" ]; then
        [ "$(sha256 /sys/kernel/btf/vmlinux)" = "$KERNEL_BTF_HASH" ] || fail unsupported_kernel
        /system/bin/insmod "$MODDIR/k90_fan_cap.ko" 2>/dev/null || fail kernel_load_failed
    fi
    [ -w "$CAP" ] && [ -r "$TARGET" ] || fail driver_state
}

write_cap() {
    printf '%s\n' "$1" 2>/dev/null > "$CAP" && [ "$(read_number "$CAP")" = "$1" ]
}

change_limit() {
    desired=$1
    lock_settings
    old=$(read_number "$CAP") || old=0
    temp_config=$("$BUSYBOX" mktemp "$MODDIR/.limit.XXXXXX") || fail config_write_failed
    trap '"$BUSYBOX" rm -f -- "$temp_config"' EXIT
    printf '%s\n' "$desired" > "$temp_config" || fail config_write_failed

    if [ "$desired" != 0 ]; then
        ensure_driver
    fi
    if [ -d "$MODULE_SYS" ]; then
        write_cap "$desired" || fail kernel_write_failed
    fi
    if ! "$BUSYBOX" mv -f -- "$temp_config" "$CONFIG"; then
        if [ -d "$MODULE_SYS" ]; then
            write_cap "$old" || fail rollback_failed
        fi
        fail config_write_failed
    fi
    if [ "$desired" = 0 ] && [ -d "$MODULE_SYS" ]; then
        /system/bin/rmmod k90_fan_cap 2>/dev/null || fail kernel_unload_failed
    fi
    status
}

[ "$(id -u)" = 0 ] || fail root_required
[ -x "$BUSYBOX" ] || fail kernel_su_required
case "$1" in
    status) status ;;
    set)
        [ "$#" -eq 2 ] && valid_limit "$2" || fail invalid_limit
        change_limit "$2"
        ;;
    reset) change_limit 0 ;;
    boot)
        limit=$(stored_limit)
        [ "$limit" != 0 ] || exit 0
        [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || exit 0
        lock_settings
        limit=$(stored_limit)
        [ "$limit" != 0 ] || exit 0
        ensure_driver
        write_cap "$limit" || fail kernel_write_failed
        ;;
    *) fail invalid_command ;;
esac
