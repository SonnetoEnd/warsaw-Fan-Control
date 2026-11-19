package io.github.sonnetoend.warsawfan;

import android.app.Application;
import android.content.Context;

import java.lang.reflect.Member;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class FanModule implements IXposedHookLoadPackage {
    static final String MODULE_PACKAGE = "io.github.sonnetoend.warsawfan";
    static final String POWERKEEPER = "com.miui.powerkeeper";
    static final String SETTINGS = "com.android.settings";
    static final String SYSTEM_UI = "com.android.systemui";
    static final String FIX_CHARGE = "k90_fan_charge_limit_fix";
    static final String PROTECT_MODE = "security_pc_secure_protect_mode_key";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam pkg) {
        if (!pkg.packageName.equals(pkg.processName)
                || !(POWERKEEPER.equals(pkg.packageName) || SETTINGS.equals(pkg.packageName)
                || SYSTEM_UI.equals(pkg.packageName))) return;

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            private boolean installed;
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Context context = (Context) param.args[0];
                if (installed || !pkg.packageName.equals(context.getPackageName())) return;
                installed = true;
                if (FanRules.readNumber(FanRules.FAN + "fan_support") == 0) return;
                ClassLoader loader = context.getClassLoader();
                FanHooks hooks = new FanHooks();
                try {
                    if (POWERKEEPER.equals(pkg.packageName)) {
                        new PowerKeeperHooks(context).install(loader, hooks);
                    } else if (SETTINGS.equals(pkg.packageName)) {
                        FanSettingsPage.install(loader, hooks);
                    } else {
                        new FanTileHooks(context).install(loader, hooks);
                    }
                    XposedBridge.log("warsaw Fan Control: fan hooks active in " + pkg.packageName);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError
                        | XposedHelpers.ClassNotFoundError | XposedHelpers.InvocationTargetError mismatch) {
                    hooks.rollback();
                    XposedBridge.log("warsaw Fan Control: skipping fan hooks in " + pkg.packageName + " (" + mismatch + ")");
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

    static void deoptimizeIfPresent(Class<?> owner, String name, Class<?>... parameters) {
        try {
            deoptimize(owner.getDeclaredMethod(name, parameters));
        } catch (NoSuchMethodException ignored) {
            // Some firmware versions build the same fan policy with different helper methods.
        }
    }
}
