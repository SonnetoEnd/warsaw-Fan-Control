package dev.k90fan.control;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class PowerKeeperHooks {
    private final ContentResolver resolver;
    private final BatteryManager battery;
    private volatile Handler fan;
    private ContentObserver observer;
    private volatile int maxGear;
    private volatile boolean held;
    private boolean initialSync;
    private boolean loadLimit;
    private boolean restoreInFlight;
    private final Runnable refresh = this::refreshSafely;

    PowerKeeperHooks(Context context) {
        resolver = context.getContentResolver();
        battery = context.getSystemService(BatteryManager.class);
        maxGear = FanRules.legacy(Settings.Global.getInt(resolver, FanLimits.LEGACY_GEAR, 0));
    }

    void install(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> handler = loader.loadClass("q.h");
        Class<?> snapshot = loader.loadClass("q.h$h");
        Method init = handler.getDeclaredMethod("B");
        Method evaluate = handler.getDeclaredMethod("X", snapshot);
        Method write = handler.getDeclaredMethod("K", String.class, String.class);
        Method message = handler.getDeclaredMethod("handleMessage", Message.class);
        Method publish = XposedHelpers.findMethodExact(Settings.System.class, "putIntForUser",
                ContentResolver.class, String.class, int.class, int.class);
        handler.getDeclaredMethod("C");
        for (String field : new String[]{"I", "y", "B", "S", "o", "e"}) handler.getDeclaredField(field);
        Field fast = XposedHelpers.findField(snapshot, "j");
        Field chargeSceneEnabled = XposedHelpers.findField(snapshot, "n");

        XposedBridge.hookMethod(init, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                attach((Handler) p.thisObject);
            }
        });
        XposedBridge.hookMethod(message, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                int what = ((Message) p.args[0]).what;
                // Policy reload, fast type, user switch, main switch, rapid scene, battery/plug.
                if (what == 1 || what == 5 || what == 7 || what == 12 || what == 16 || what == 21) queueRefresh();
            }
        });
        XposedBridge.hookMethod(evaluate, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws IllegalAccessException {
                Object state = p.args[0];
                if (held && fast.getBoolean(state) && chargeSceneEnabled.getBoolean(state)) {
                    p.setObjectExtra("restoreFast", Boolean.TRUE);
                    fast.setBoolean(state, false);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) throws IllegalAccessException {
                if (Boolean.TRUE.equals(p.getObjectExtra("restoreFast"))) fast.setBoolean(p.args[0], true);
            }
        });
        XposedBridge.hookMethod(write, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!"target_level".equals(p.args[0])) return;
                int requested = FanRules.number((String) p.args[1]);
                int applied = FanRules.capped(requested, maxGear);
                if (requested != applied) p.args[1] = Integer.toString(applied);
            }
        });
        // Keep the public gear consistent with the driver. The private requested
        // gear stays available to the stock temperature hysteresis and self-clean timer.
        XposedBridge.hookMethod(publish, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if ("fan_level".equals(p.args[1])) p.args[2] = FanRules.capped((int) p.args[2], maxGear);
            }
        });
        deoptimizeFanCallers(handler, snapshot);
    }

    private static void deoptimizeFanCallers(Class<?> handler, Class<?> snapshot) {
        // Precompiled stock methods can inline the gear writer or the Settings publication.
        // LSPosed exposes this extension beyond the API 82 compile-time stubs.
        Method deopt;
        try {
            deopt = XposedBridge.class.getMethod("deoptimizeMethod", Member.class);
        } catch (NoSuchMethodException e) {
            return;
        }
        try {
            deopt.invoke(null, handler.getDeclaredMethod("A", int.class, snapshot));
            deopt.invoke(null, handler.getDeclaredMethod("x", String.class));
            for (String name : new String[]{"L", "n", "C", "e"}) deopt.invoke(null, handler.getDeclaredMethod(name));
        } catch (ReflectiveOperationException e) {
            XposedBridge.log(e);
        }
    }

    private void attach(Handler owner) {
        if (fan == owner) return;
        if (fan != null) {
            fan.removeCallbacks(refresh);
            resolver.unregisterContentObserver(observer);
        }
        fan = owner;
        initialSync = true;
        loadLimit = true;
        held = false;
        observer = new ContentObserver(owner) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                if (uri == null || FanLimits.URI.equals(uri)) loadLimit = true;
                queueRefresh();
            }
        };
        resolver.registerContentObserver(FanLimits.URI, false, observer);
        resolver.registerContentObserver(Settings.Global.getUriFor(FanModule.FIX_CHARGE), false, observer);
        resolver.registerContentObserver(Settings.Secure.getUriFor(FanModule.PROTECT_MODE), false, observer);
        queueRefresh();
    }

    private void queueRefresh() {
        if (fan == null) return;
        fan.removeCallbacks(refresh);
        fan.post(refresh);
    }

    private boolean limitStoppedCharge(int mode) {
        try (BufferedReader votes = new BufferedReader(new FileReader(FanRules.CHARGE_ENABLE))) {
            FanRules.BuckVote vote = FanRules.buckVote(votes);
            if (vote == FanRules.BuckVote.OTHER) return false;
            // smart_chg itself is restricted to the vendor service on this ROM.
            Object miCharge = XposedHelpers.getObjectField(fan, "e");
            if (miCharge == null) return false;
            String flags = (String) XposedHelpers.callMethod(miCharge, "getMiChargePath", "smart_chg");
            int level = -1;
            int currentUa = Integer.MIN_VALUE;
            if (vote == FanRules.BuckVote.ONLINE_OFF && battery != null) {
                level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                currentUa = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            }
            boolean stop = FanRules.activeProtection(mode, FanRules.number(flags), vote, level, currentUa, held);
            if (stop && !held) {
                String detail = vote == FanRules.BuckVote.ONLINE_OFF ? ", " + level + "%, " + currentUa + " uA" : "";
                XposedBridge.log("warsaw Fans Control: paused rapid-charge cooling (" + vote + detail + ")");
            }
            return stop;
        } catch (IOException | RuntimeException | XposedHelpers.InvocationTargetError e) {
            return false;
        }
    }

    private void refreshSafely() {
        try {
            refresh();
        } catch (RuntimeException | LinkageError | XposedHelpers.InvocationTargetError e) {
            // This callback is posted to the vendor's Handler, outside the Xposed hook dispatcher.
            held = false;
            XposedBridge.log(e);
        }
    }

    private void refresh() {
        int previousMax = maxGear;
        boolean previousHold = held;
        if (loadLimit) readLimit();
        int mode = Settings.Secure.getInt(resolver, FanModule.PROTECT_MODE, 1);
        boolean watching = mode == 2 && Settings.Global.getInt(resolver, FanModule.FIX_CHARGE, 1) == 1
                && XposedHelpers.getBooleanField(fan, "I") && XposedHelpers.getBooleanField(fan, "y")
                && XposedHelpers.getBooleanField(fan, "B") && FanRules.cablePresent();
        held = watching && limitStoppedCharge(mode);
        if (previousHold && !held) XposedBridge.log("warsaw Fans Control: released rapid-charge cooling");
        boolean updateCap = maxGear != previousMax || (initialSync && FanRules.isGear(maxGear));
        if (held != previousHold || updateCap) {
            // C rebuilds the snapshot from live OEM state and arbitrates all scenes.
            XposedHelpers.callMethod(fan, "C");
            if (updateCap) applyCapToRunningFan();
        }
        initialSync = false;
        if (watching) fan.postDelayed(refresh, 5000);
    }

    private void readLimit() {
        Bundle state = FanLimits.request(resolver, "read", 0);
        // A subsequent OEM event can retry a provider that was unavailable during boot.
        loadLimit = state.containsKey("error");
        if (loadLimit) return;
        maxGear = state.getInt("selection");
        if (state.getBoolean("needsSync") && !restoreInFlight) {
            restoreInFlight = true;
            Handler owner = fan;
            new Thread(() -> {
                try {
                    Bundle restored = FanLimits.request(resolver, "sync", 0);
                    String error = restored.getString("error");
                    if (error != null) XposedBridge.log("K90Fan: restoring saved limit: " + error);
                } finally {
                    owner.post(() -> restoreInFlight = false);
                }
            }, "K90Fan-Restore").start();
        }
    }

    private void applyCapToRunningFan() {
        int requested = XposedHelpers.getIntField(fan, "o");
        boolean cleaning = XposedHelpers.getBooleanField(fan, "S");
        if (requested < 1 || requested > 4 || (!cleaning && !XposedHelpers.getBooleanField(fan, "I"))) return;
        int physical = FanRules.readNumber(FanRules.FAN + "target_level");
        int applied = FanRules.capped(requested, maxGear);
        if (physical <= 0 || physical == applied) return;
        Object ok = XposedHelpers.callMethod(fan, "K", "target_level", Integer.toString(applied));
        if (Boolean.TRUE.equals(ok) && !cleaning) {
            XposedHelpers.callStaticMethod(Settings.System.class, "putIntForUser",
                    resolver, "fan_level", applied, -2);
        }
    }
}
