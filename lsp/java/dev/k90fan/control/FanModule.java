package dev.k90fan.control;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class FanModule implements IXposedHookLoadPackage {
    static final String POWERKEEPER = "com.miui.powerkeeper";
    static final String SETTINGS = "com.android.settings";
    static final String SYSTEM_UI = "com.android.systemui";
    static final String FIX_CHARGE = "k90_fan_charge_limit_fix";
    static final String PROTECT_MODE = "security_pc_secure_protect_mode_key";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam pkg) {
        if (!"warsaw".equals(Build.DEVICE) || !"OS4.0.8.0.XHPCNXM".equals(Build.VERSION.INCREMENTAL)
                || !pkg.packageName.equals(pkg.processName)
                || !(POWERKEEPER.equals(pkg.packageName) || SETTINGS.equals(pkg.packageName)
                || SYSTEM_UI.equals(pkg.packageName))) return;

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            private boolean installed;
            @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Context context = (Context) param.args[0];
                if (installed || !pkg.packageName.equals(context.getPackageName())) return;
                installed = true;
                if (POWERKEEPER.equals(pkg.packageName)) {
                    if (context.getPackageManager().getPackageInfo(POWERKEEPER, 0).getLongVersionCode() != 40200) {
                        XposedBridge.log("K90Fan: unsupported PowerKeeper version");
                        return;
                    }
                    new PowerKeeperHooks(context).install(context.getClassLoader());
                } else if (SETTINGS.equals(pkg.packageName)) {
                    FanSettingsPage.install(context.getClassLoader());
                } else {
                    new FanTileHooks(context).install(context.getClassLoader());
                }
            }
        });
    }

    static void deoptimize(Member... callers) {
        try {
            Method deopt = XposedBridge.class.getMethod("deoptimizeMethod", Member.class);
            for (Member caller : callers) deopt.invoke(null, caller);
        } catch (NoSuchMethodException ignored) {
            // Optional LSPosed extension to the API 82 stubs.
        } catch (ReflectiveOperationException e) {
            XposedBridge.log(e);
        }
    }
}
