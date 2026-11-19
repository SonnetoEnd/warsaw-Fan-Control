package io.github.sonnetoend.warsawfan;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

// Used by the two OEM processes and by the module's own provider.
final class FanLimits {
    static final Uri URI = Uri.parse("content://" + FanModule.MODULE_PACKAGE + ".limits");
    static final String LEGACY_GEAR = "k90_fan_max_gear";

    static Bundle request(ContentResolver resolver, String method, int selection) {
        int legacy = FanRules.legacy(Settings.Global.getInt(resolver, LEGACY_GEAR, FanRules.SYSTEM));
        Bundle args = new Bundle();
        args.putInt("legacy", legacy);
        args.putInt("selection", selection);
        try {
            Bundle result = resolver.call(URI, method, null, args);
            if (result != null) return result;
        } catch (RuntimeException ignored) {
            // Settings may start before Android makes the module provider available.
        }
        Bundle unavailable = new Bundle();
        unavailable.putInt("selection", legacy);
        unavailable.putInt("last", FanRules.isGear(legacy) ? FanRules.RPM[legacy] : FanRules.MAX_RPM);
        unavailable.putString("error", "provider_unavailable");
        return unavailable;
    }

    static String errorText(String code) {
        switch (code) {
            case "root_required": return "请在 Root 管理器中给「warsaw Fans Control」授权后重试";
            case "unsupported_kernel":
            case "unsupported_driver":
            case "unsupported_device":
            case "fan_unavailable": return "当前内核或风扇驱动不在自定义转速的适配范围内";
            case "legacy_active": return "请先停用旧版 SukiSU 风扇限速模块并重启";
            case "kernel_su_required": return "找不到 SukiSU / KernelSU 的 BusyBox";
            case "provider_unavailable": return "模块服务尚未就绪，请重新打开风扇设置";
            case "busy": return "上一次风扇设置仍在应用，请稍后重试";
            case "timeout": return "Root 操作超时，请重新选择上限";
            case "save_failed": return "未能保存设置，请重试";
            default: return "驱动未确认此次设置（" + code + "）";
        }
    }

    private FanLimits() {}
}
