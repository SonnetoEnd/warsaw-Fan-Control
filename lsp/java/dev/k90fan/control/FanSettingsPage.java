package dev.k90fan.control;

import android.content.Context;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.callMethod;

final class FanSettingsPage {
    private static final String PAGE = "dev.k90fan.control.page";
    private static final Executor CHANGES = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final Object cap;
    private final Object customRow;
    private final ClassLoader loader;
    private final Object telemetry;
    private final Runnable sample = this::sample;
    private int selected;
    private int lastCustom;
    private boolean applying;

    private FanSettingsPage(Context context, ClassLoader loader, Object cap, Object customRow, Object telemetry) {
        this.context = context;
        this.loader = loader;
        this.cap = cap;
        this.customRow = customRow;
        this.telemetry = telemetry;
    }

    static void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> fragment = loader.loadClass("com.android.settings.coolingfan.MiuiCoolingFanSettings");
        XC_MethodHook addRows = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                addPreferences(p.thisObject, loader);
            }
        };
        XposedBridge.hookMethod(fragment.getDeclaredMethod("onCreatePreferences", Bundle.class, String.class), addRows);
        XposedBridge.hookMethod(fragment.getDeclaredMethod("onCreate", Bundle.class), addRows);
        // The page inherits these lifecycle methods from the Settings dashboard.
        XposedBridge.hookMethod(fragment.getMethod("onResume"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!fragment.isInstance(p.thisObject)) return;
                FanSettingsPage page = pageOf(p.thisObject);
                if (page != null) {
                    page.readSelection();
                    page.sample();
                }
            }
        });
        XposedBridge.hookMethod(fragment.getMethod("onPause"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!fragment.isInstance(p.thisObject)) return;
                FanSettingsPage page = pageOf(p.thisObject);
                if (page != null) page.main.removeCallbacks(page.sample);
            }
        });
    }

    private static FanSettingsPage pageOf(Object fragment) {
        return (FanSettingsPage) XposedHelpers.getAdditionalInstanceField(fragment, PAGE);
    }

    private static void addPreferences(Object fragment, ClassLoader loader) throws ClassNotFoundException {
        Object controls = callMethod(fragment, "findPreference", "cooling_fan_settings");
        Object extras = callMethod(fragment, "findPreference", "fan_other_features_category");
        if (controls == null || extras == null || callMethod(fragment, "findPreference", FanLimits.LEGACY_GEAR) != null) return;
        Context context = (Context) callMethod(controls, "getContext");
        Object cap = preference(loader, "miuix.preference.DropDownPreference", context,
                FanLimits.LEGACY_GEAR, "风扇转速上限");
        callMethod(cap, "setEntries", (Object) new CharSequence[]{choice(0), choice(1), choice(2),
                choice(3), choice(4), "自定义"});
        callMethod(cap, "setEntryValues", (Object) new CharSequence[]{"0", "1", "2", "3", "4", "custom"});
        Object customRow = preference(loader, "miuix.preference.TextPreference", context,
                "k90_fan_custom_rpm", "自定义目标转速");
        Object telemetry = preference(loader, "androidx.preference.Preference", context,
                "k90_fan_readings", "实时转速");
        FanSettingsPage page = new FanSettingsPage(context, loader, cap, customRow, telemetry);

        Object fix = preference(loader, "com.android.settingslib.miuisettings.preference.SwitchPreference",
                context, FanModule.FIX_CHARGE, "80% 停充时退出极速充电散热");
        callMethod(fix, "setSummary", "电池保护暂停充电时生效。游戏、导航等散热需求继续由系统判断。");

        Class<?> listener = loader.loadClass("androidx.preference.Preference$OnPreferenceChangeListener");
        Object onChange = Proxy.newProxyInstance(loader, new Class<?>[]{listener}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "onPreferenceChange":
                    if (args[0] == fix)
                        return Settings.Global.putInt(context.getContentResolver(), FanModule.FIX_CHARGE,
                                Boolean.TRUE.equals(args[1]) ? 1 : 0);
                    if (args[0] == cap && !page.applying) {
                        String next = String.valueOf(args[1]);
                        if ("custom".equals(next)) page.chooseRpm();
                        else {
                            int gear = FanRules.number(next);
                            if (gear == FanRules.SYSTEM || FanRules.isGear(gear)) page.apply(gear);
                        }
                    }
                    // Commit the visible selection after successful driver readback.
                    return false;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "toString": return "K90 fan preference listener";
                default: return null;
            }
        });
        callMethod(cap, "setOnPreferenceChangeListener", onChange);
        callMethod(fix, "setOnPreferenceChangeListener", onChange);
        Class<?> onClick = loader.loadClass("androidx.preference.Preference$OnPreferenceClickListener");
        Object click = Proxy.newProxyInstance(loader, new Class<?>[]{onClick}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "onPreferenceClick": if (!page.applying) page.chooseRpm(); return true;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "toString": return "K90 fan limit click";
                default: return null;
            }
        });
        callMethod(customRow, "setOnPreferenceClickListener", click);
        callMethod(telemetry, "setSelectable", false);
        callMethod(controls, "addPreference", cap);
        callMethod(controls, "addPreference", customRow);
        callMethod(extras, "addPreference", fix);
        callMethod(extras, "addPreference", telemetry);
        page.readSelection();
        callMethod(fix, "setChecked", Settings.Global.getInt(context.getContentResolver(), FanModule.FIX_CHARGE, 1) == 1);

        FanSettingsPage previous = pageOf(fragment);
        if (previous != null) previous.main.removeCallbacks(previous.sample);
        XposedHelpers.setAdditionalInstanceField(fragment, PAGE, page);
        if (Boolean.TRUE.equals(callMethod(fragment, "isResumed"))) page.sample();
    }

    private static Object preference(ClassLoader loader, String type, Context context, String key, String title)
            throws ClassNotFoundException {
        Object pref = XposedHelpers.newInstance(loader.loadClass(type), context);
        callMethod(pref, "setPersistent", false);
        callMethod(pref, "setKey", key);
        callMethod(pref, "setTitle", title);
        return pref;
    }

    private static String rpm(int value) { return String.format(Locale.CHINA, "%,d rpm", value); }

    private static String choice(int value) {
        if (FanRules.custom(value)) return "自定义 · " + rpm(value);
        if (FanRules.isGear(value)) return value + " 档 · " + rpm(FanRules.RPM[value]);
        return "跟随系统";
    }

    private void readSelection() {
        if (!applying) showState(FanLimits.request(context.getContentResolver(), "read", 0));
    }

    private void showState(Bundle state) {
        selected = state.getInt("selection");
        lastCustom = state.getInt("last", FanRules.MAX_RPM);
        boolean custom = FanRules.custom(selected);
        callMethod(cap, "setValueIndex", custom ? 5 : selected);
        callMethod(customRow, "setText", rpm(lastCustom));
        callMethod(customRow, "setVisible", custom);
        String issue = state.getString("error", state.getString("restoreError"));
        CharSequence summary = issue != null ? FanLimits.errorText(issue)
                : state.getBoolean("needsSync") ? "等待驱动确认" : "";
        callMethod(cap, "setSummary", summary);
    }

    private void chooseRpm() throws ClassNotFoundException {
        Object builder = XposedHelpers.newInstance(loader.loadClass("miuix.appcompat.app.AlertDialog$Builder"), context);
        Context theme = (Context) callMethod(builder, "getContext");
        View picker = (View) XposedHelpers.newInstance(loader.loadClass("miuix.pickerwidget.widget.NumberPicker"), theme);
        int count = (FanRules.MAX_RPM - FanRules.MIN_RPM) / FanRules.STEP + 1;
        String[] values = new String[count];
        for (int i = 0; i < count; i++) values[i] = Integer.toString(FanRules.MIN_RPM + i * FanRules.STEP);
        callMethod(picker, "setMinValue", 0);
        callMethod(picker, "setMaxValue", count - 1);
        callMethod(picker, "setDisplayedValues", (Object) values);
        callMethod(picker, "setWrapSelectorWheel", false);
        callMethod(picker, "setLabel", "rpm");
        int start = FanRules.custom(selected) ? selected : lastCustom;
        callMethod(picker, "setValue", (start - FanRules.MIN_RPM) / FanRules.STEP);
        FrameLayout layout = new FrameLayout(theme);
        layout.addView(picker, new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER));
        DialogInterface.OnClickListener accept = (dialog, which) -> {
            picker.clearFocus();
            apply(FanRules.MIN_RPM + (int) callMethod(picker, "getValue") * FanRules.STEP);
        };
        callMethod(builder, "setTitle", "自定义目标上限");
        callMethod(builder, "setView", layout);
        callMethod(builder, "setPositiveButton", "应用", accept);
        callMethod(builder, "setNegativeButton", "取消", null);
        callMethod(builder, "setOnDismissListener", (DialogInterface.OnDismissListener) dialog -> readSelection());
        callMethod(builder, "show");
    }

    private void apply(int next) {
        applying = true;
        callMethod(cap, "setEnabled", false);
        callMethod(customRow, "setEnabled", false);
        callMethod(cap, "setSummary", "正在应用…");
        CHANGES.execute(() -> {
            Bundle state = FanLimits.request(context.getContentResolver(), "select", next);
            main.post(() -> {
                applying = false;
                callMethod(cap, "setEnabled", true);
                callMethod(customRow, "setEnabled", true);
                showState(state);
                String error = state.getString("error");
                if (error != null) Toast.makeText(context, FanLimits.errorText(error), Toast.LENGTH_LONG).show();
            });
        });
    }

    private void sample() {
        main.removeCallbacks(sample);
        int rpm = FanRules.readNumber(FanRules.FAN + "real_speed");
        int gear = FanRules.readNumber(FanRules.FAN + "target_level");
        String speed = rpm < 0 || rpm == 2 || rpm > 40000 ? "等待读数" : String.format(Locale.CHINA, "%,d rpm", rpm);
        String target = gear == 0 ? "目标停转" : gear > 0 && gear <= 4 ? "目标 " + gear + " 档" : "档位未知";
        callMethod(telemetry, "setSummary", speed + " · " + target);
        main.postDelayed(sample, 1500);
    }
}
