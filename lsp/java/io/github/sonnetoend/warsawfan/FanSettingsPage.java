package io.github.sonnetoend.warsawfan;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.callMethod;

final class FanSettingsPage {
    private static final String PAGE = FanModule.MODULE_PACKAGE + ".page";
    private static final Executor CHANGES = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    private final Object cap;
    private final Object customRow;
    private final ClassLoader loader;
    private final Object telemetry;
    private Object modeRow;
    private Object appsRow;
    private Object lockRow;
    private boolean observing;
    private final ContentObserver policyObserver = new ContentObserver(main) {
        @Override public void onChange(boolean selfChange) { readPolicy(); }
    };
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

    static void install(ClassLoader loader, FanHooks hooks) throws ReflectiveOperationException {
        Class<?> fragment = loader.loadClass("com.android.settings.coolingfan.MiuiCoolingFanSettings");
        Class<?> dropDown = loader.loadClass("miuix.preference.DropDownPreference");
        dropDown.getConstructor(Context.class);
        dropDown.getMethod("setEntries", CharSequence[].class);
        dropDown.getMethod("setEntryValues", CharSequence[].class);
        dropDown.getMethod("setValueIndex", int.class);
        dropDown.getMethod("setValue", String.class);
        dropDown.getMethod("getValue");
        dropDown.getMethod("getEntries");
        dropDown.getMethod("getEntryValues");
        Class<?> text = loader.loadClass("miuix.preference.TextPreference");
        text.getConstructor(Context.class);
        XposedHelpers.findMethodBestMatch(text, "setText", String.class);
        Class<?> toggle = loader.loadClass("com.android.settingslib.miuisettings.preference.SwitchPreference");
        toggle.getConstructor(Context.class);
        toggle.getMethod("setChecked", boolean.class);
        loader.loadClass("androidx.preference.Preference").getConstructor(Context.class);
        loader.loadClass("androidx.preference.Preference$OnPreferenceChangeListener");
        loader.loadClass("androidx.preference.Preference$OnPreferenceClickListener");
        installModeController(loader, hooks);
        XC_MethodHook addRows = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) throws Throwable {
                addPreferences(p.thisObject, loader, dropDown);
            }
        };
        hooks.hook(FanHooks.method(fragment, "onCreatePreferences", void.class, Bundle.class, String.class), addRows);
        hooks.hook(FanHooks.method(fragment, "onCreate", void.class, Bundle.class), addRows);
        // The page inherits these lifecycle methods from the Settings dashboard.
        hooks.hook(fragment.getMethod("onResume"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!fragment.isInstance(p.thisObject)) return;
                FanSettingsPage page = pageOf(p.thisObject);
                if (page != null) {
                    page.observePolicy();
                    page.readSelection();
                    page.sample();
                }
            }
        });
        hooks.hook(fragment.getMethod("onPause"), new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!fragment.isInstance(p.thisObject)) return;
                FanSettingsPage page = pageOf(p.thisObject);
                if (page != null) page.pause();
            }
        });
    }

    private static void installModeController(ClassLoader loader, FanHooks hooks) throws ReflectiveOperationException {
        Class<?> controller = loader.loadClass("com.android.settings.coolingfan.FanModeController");
        Class<?> preference = loader.loadClass("androidx.preference.Preference");
        FanHooks.readField(controller, "mContext", Context.class);
        hooks.hook(FanHooks.method(controller, "getCurrentMode", String.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                Context context = (Context) XposedHelpers.getObjectField(p.thisObject, "mContext");
                int mode = Settings.Secure.getInt(context.getContentResolver(), FanPolicySettings.MODE, FanPolicy.STOCK);
                p.setResult(FanPolicySettings.valueFor(mode, (String) p.getResult()));
            }
        });
        hooks.hook(FanHooks.method(controller, "onPreferenceChange", boolean.class, preference, Object.class),
                new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                int mode = FanPolicySettings.modeFor(p.args[1]);
                Context context = (Context) callMethod(p.args[0], "getContext");
                if (mode != FanPolicy.STOCK) {
                    p.setResult(Settings.Secure.putInt(context.getContentResolver(), FanPolicySettings.MODE, mode));
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!Boolean.TRUE.equals(p.getResult()) || FanPolicySettings.modeFor(p.args[1]) != FanPolicy.STOCK) return;
                Context context = (Context) callMethod(p.args[0], "getContext");
                if (Settings.Secure.getInt(context.getContentResolver(), FanPolicySettings.MODE, 0) != FanPolicy.STOCK)
                    p.setResult(Settings.Secure.putInt(context.getContentResolver(), FanPolicySettings.MODE, FanPolicy.STOCK));
            }
        });
        FanModule.deoptimizeIfPresent(controller, "updateState", preference);
        FanModule.deoptimizeIfPresent(controller, "updateFanModeState");
    }

    private static FanSettingsPage pageOf(Object fragment) {
        return (FanSettingsPage) XposedHelpers.getAdditionalInstanceField(fragment, PAGE);
    }

    private static void addPreferences(Object fragment, ClassLoader loader, Class<?> dropDown) throws ClassNotFoundException {
        Object controls = callMethod(fragment, "findPreference", "cooling_fan_settings");
        Object extras = callMethod(fragment, "findPreference", "fan_other_features_category");
        Object mode = callMethod(fragment, "findPreference", "fan_mode");
        if (controls == null || extras == null || !dropDown.isInstance(mode)
                || callMethod(fragment, "findPreference", FanLimits.LEGACY_GEAR) != null) return;
        Context context = (Context) callMethod(controls, "getContext");
        Object cap = preference(loader, "miuix.preference.DropDownPreference", context,
                FanLimits.LEGACY_GEAR, "风扇转速上限");
        int[] targets = FanRules.readGearTargets();
        callMethod(cap, "setEntries", (Object) new CharSequence[]{"跟随系统", gearLabel(1, targets), gearLabel(2, targets),
                gearLabel(3, targets), gearLabel(4, targets), "自定义"});
        callMethod(cap, "setEntryValues", (Object) new CharSequence[]{"0", "1", "2", "3", "4", "custom"});
        Object customRow = preference(loader, "miuix.preference.TextPreference", context,
                "k90_fan_custom_rpm", "自定义目标转速");
        Object telemetry = preference(loader, "androidx.preference.Preference", context,
                "k90_fan_readings", "实时转速");
        FanSettingsPage page = new FanSettingsPage(context, loader, cap, customRow, telemetry);
        page.modeRow = mode;
        page.appsRow = preference(loader, "androidx.preference.Preference", context,
                FanPolicySettings.APPS, "应用配置");
        page.lockRow = preference(loader, "com.android.settingslib.miuisettings.preference.SwitchPreference",
                context, FanPolicySettings.KEEP_ON_LOCK, "息屏时允许风扇继续运行");
        callMethod(page.lockRow, "setSummary", "锁屏后继续按当前场景和转速上限散热。");

        Object fix = preference(loader, "com.android.settingslib.miuisettings.preference.SwitchPreference",
                context, FanModule.FIX_CHARGE, "80% 停充时退出极速充电散热");
        callMethod(fix, "setSummary", "80% 保护停充或回落到 79% 仅有微小电流时，退出充电散热。游戏、导航等散热需求由系统判断。");

        Class<?> listener = loader.loadClass("androidx.preference.Preference$OnPreferenceChangeListener");
        Object onChange = Proxy.newProxyInstance(loader, new Class<?>[]{listener}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "onPreferenceChange":
                    if (args[0] == page.lockRow)
                        return Settings.Secure.putInt(context.getContentResolver(), FanPolicySettings.KEEP_ON_LOCK,
                                Boolean.TRUE.equals(args[1]) ? 1 : 0);
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
        callMethod(page.lockRow, "setOnPreferenceChangeListener", onChange);
        Class<?> onClick = loader.loadClass("androidx.preference.Preference$OnPreferenceClickListener");
        Object click = Proxy.newProxyInstance(loader, new Class<?>[]{onClick}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "onPreferenceClick":
                    if (args[0] == page.appsRow) callMethod(fragment, "startActivity", new Intent()
                            .setClassName(FanModule.MODULE_PACKAGE, AppRulesActivity.class.getName()));
                    else if (!page.applying) page.chooseRpm();
                    return true;
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == args[0];
                case "toString": return "K90 fan limit click";
                default: return null;
            }
        });
        callMethod(customRow, "setOnPreferenceClickListener", click);
        callMethod(page.appsRow, "setOnPreferenceClickListener", click);
        callMethod(telemetry, "setSelectable", false);
        if (!page.extendModes()) return;
        callMethod(controls, "addPreference", cap);
        callMethod(controls, "addPreference", customRow);
        callMethod(extras, "addPreference", fix);
        callMethod(extras, "addPreference", page.lockRow);
        callMethod(extras, "addPreference", page.appsRow);
        callMethod(extras, "addPreference", telemetry);
        page.readSelection();
        page.readPolicy();
        callMethod(fix, "setChecked", Settings.Global.getInt(context.getContentResolver(), FanModule.FIX_CHARGE, 1) == 1);

        FanSettingsPage previous = pageOf(fragment);
        if (previous != null) previous.pause();
        XposedHelpers.setAdditionalInstanceField(fragment, PAGE, page);
        if (Boolean.TRUE.equals(callMethod(fragment, "isResumed"))) {
            page.observePolicy();
            page.sample();
        }
    }

    private boolean extendModes() {
        Object entries = callMethod(modeRow, "getEntries");
        Object entryValues = callMethod(modeRow, "getEntryValues");
        if (!(entries instanceof CharSequence[]) || !(entryValues instanceof CharSequence[])) return false;
        CharSequence[] titles = (CharSequence[]) entries;
        CharSequence[] values = (CharSequence[]) entryValues;
        if (values.length == 0 || titles.length != values.length) return false;
        if (Arrays.asList(values).contains(FanPolicySettings.BURST_VALUE)) return true;
        try {
            Object adapter = XposedHelpers.getObjectField(modeRow, "mContentAdapter");
            if (adapter != null) {
                CharSequence[] summaries = (CharSequence[]) XposedHelpers.getObjectField(adapter, "mSummaries");
                CharSequence[] details = summaries == null ? new CharSequence[titles.length] : Arrays.copyOf(summaries, titles.length);
                callMethod(modeRow, "setSummaries", (Object) append(details, "在启用的场景使用 4 档", "按前台应用切换风扇档位"));
            }
        } catch (NoSuchFieldError | NoSuchMethodError | ClassCastException ignored) {
            // Some versions expose the entries and values without per-entry descriptions.
        }
        callMethod(modeRow, "setEntries", (Object) append(titles, FanPolicySettings.BURST_TITLE, FanPolicySettings.APPS_TITLE));
        callMethod(modeRow, "setEntryValues", (Object) append(values, FanPolicySettings.BURST_VALUE, FanPolicySettings.APPS_VALUE));
        return true;
    }

    private static CharSequence[] append(CharSequence[] values, String first, String second) {
        CharSequence[] result = Arrays.copyOf(values, values.length + 2, CharSequence[].class);
        result[values.length] = first;
        result[values.length + 1] = second;
        return result;
    }

    private void observePolicy() {
        if (!observing) {
            for (android.net.Uri uri : FanPolicySettings.URIS)
                context.getContentResolver().registerContentObserver(uri, false, policyObserver);
            observing = true;
        }
        readPolicy();
    }

    private void pause() {
        main.removeCallbacks(sample);
        if (observing) {
            context.getContentResolver().unregisterContentObserver(policyObserver);
            observing = false;
        }
    }

    private void readPolicy() {
        FanPolicy policy = FanPolicySettings.read(context.getContentResolver());
        String stock = Integer.toString(Settings.System.getInt(context.getContentResolver(), "fan_mode", -1));
        String choice = FanPolicySettings.valueFor(policy.mode, stock);
        if (!choice.equals(callMethod(modeRow, "getValue"))) callMethod(modeRow, "setValue", choice);
        callMethod(lockRow, "setChecked", policy.keepOnLock);
        callMethod(appsRow, "setVisible", policy.mode == FanPolicy.PER_APP);
        int count = policy.apps.size();
        callMethod(appsRow, "setSummary", count == 0 ? "为每个应用选择风扇模式" : "已为 " + count + " 个应用设置档位");
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

    private static String gearLabel(int gear, int[] targets) {
        return gear + " 档" + (gear < targets.length ? " · " + rpm(targets[gear]) : "");
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

    private void chooseRpm() {
        try {
            showRpmPicker();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError | XposedHelpers.InvocationTargetError e) {
            XposedBridge.log("warsaw Fans Control: custom RPM picker unavailable (" + e + ")");
            Toast.makeText(context, "当前系统无法打开自定义转速选择器", Toast.LENGTH_LONG).show();
            readSelection();
        }
    }

    private void showRpmPicker() throws ClassNotFoundException {
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
