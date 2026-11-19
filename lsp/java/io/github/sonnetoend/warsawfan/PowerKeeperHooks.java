package io.github.sonnetoend.warsawfan;

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
import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

final class PowerKeeperHooks {
    private final ContentResolver resolver;
    private final BatteryManager battery;
    private volatile Handler fan;
    private ContentObserver observer;
    private Field chargeService;
    private Method readChargePath;
    private Field fastFlag;
    private Field chargeScene;
    private ContentResolver currentUser;
    private FanPolicy policy = FanPolicy.DEFAULT;
    private String lastForeground;
    private int sceneGear = FanPolicy.INHERIT;
    private volatile int maxGear;
    private volatile boolean held;
    private boolean initialSync;
    private boolean loadLimit;
    private boolean loadPolicy;
    private boolean restoreInFlight;
    private final Runnable refresh = this::refreshSafely;

    PowerKeeperHooks(Context context) {
        resolver = context.getContentResolver();
        battery = context.getSystemService(BatteryManager.class);
        maxGear = FanRules.legacy(Settings.Global.getInt(resolver, FanLimits.LEGACY_GEAR, 0));
    }

    void install(ClassLoader loader, FanHooks hooks) throws ReflectiveOperationException {
        Class<?> handler = loader.loadClass("q.h").asSubclass(Handler.class);
        Class<?> snapshot = loader.loadClass("q.h$h");
        Method init = FanHooks.method(handler, "B", void.class);
        Method evaluate = FanHooks.method(handler, "X", void.class, snapshot);
        Method scene = FanHooks.method(handler, "W", void.class, String.class, snapshot);
        Method setGear = FanHooks.method(handler, "A", void.class, int.class, snapshot);
        setGear.setAccessible(true);
        Method write = FanHooks.method(handler, "K", boolean.class, String.class, String.class);
        Method message = FanHooks.method(handler, "handleMessage", void.class, Message.class);
        Method publish = XposedHelpers.findMethodExact(Settings.System.class, "putIntForUser",
                ContentResolver.class, String.class, int.class, int.class);
        Method refreshPolicy = FanHooks.method(handler, "C", void.class);
        FanHooks.readField(handler, "f", ContentResolver.class);
        for (String field : new String[]{"I", "S", "K"}) FanHooks.readField(handler, field, boolean.class);
        FanHooks.readField(handler, "o", int.class);
        FanHooks.readField(handler, "k", int.class);
        FanHooks.writeField(handler, "j", int.class);
        Field locked = FanHooks.writeField(snapshot, "g", boolean.class);
        Field range = FanHooks.writeField(snapshot, "b", int.class);
        Field foreground = FanHooks.readField(snapshot, "e", String.class);
        Field stockMode = FanHooks.writeField(snapshot, "a", int.class);
        try {
            FanHooks.readField(handler, "y", boolean.class);
            FanHooks.readField(handler, "B", boolean.class);
            chargeService = FanHooks.readField(handler, "e", Object.class);
            fastFlag = FanHooks.writeField(snapshot, "j", boolean.class);
            chargeScene = FanHooks.readField(snapshot, "n", boolean.class);
            Method readPath = chargeService.getType().getMethod("getMiChargePath", String.class);
            if (readPath.getReturnType() == String.class) readChargePath = readPath;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            // Charging telemetry is optional. The OEM fan policy can still be extended.
        }

        hooks.hook(init, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                attach((Handler) p.thisObject);
            }
        });
        hooks.hook(message, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (((Message) p.args[0]).what == 7) {
                    policy = FanPolicy.DEFAULT;
                    lastForeground = null;
                    loadPolicy = true;
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                int what = ((Message) p.args[0]).what;
                if (what == -9 && policy.mode == FanPolicy.PER_APP) {
                    // Stock records a new foreground package and returns without evaluating the fan.
                    XposedHelpers.callMethod(p.thisObject, "C");
                }
                // Policy reload, fast type, user switch, main switch, rapid scene, battery/plug.
                if (what == 1 || what == 5 || what == 7 || what == 12 || what == 16 || what == 21) queueRefresh();
            }
        });
        hooks.hook(evaluate, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws IllegalAccessException {
                Object state = p.args[0];
                boolean screenLocked = locked.getBoolean(state);
                String name = (String) foreground.get(state);
                if (name != null && !name.isEmpty() && !FanPolicy.isLockSurface(name)) lastForeground = name;
                if (screenLocked && policy.keepOnLock && FanPolicy.isLockSurface(name)) name = lastForeground;
                sceneGear = policy.requestedGear(name);
                if (policy.keepOnLock && screenLocked) {
                    p.setObjectExtra("restoreLock", Boolean.TRUE);
                    locked.setBoolean(state, false);
                }
                // A configured app is an eligible scene even in the OEM's selected-scenes range.
                if (policy.mode == FanPolicy.PER_APP && sceneGear != FanPolicy.INHERIT
                        && range.getInt(state) != 1) {
                    p.setObjectExtra("restoreRange", range.getInt(state));
                    range.setInt(state, 1);
                }
                if (held && fastFlag.getBoolean(state) && chargeScene.getBoolean(state)) {
                    p.setObjectExtra("restoreFast", Boolean.TRUE);
                    fastFlag.setBoolean(state, false);
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) throws IllegalAccessException {
                sceneGear = FanPolicy.INHERIT;
                if (Boolean.TRUE.equals(p.getObjectExtra("restoreFast"))) fastFlag.setBoolean(p.args[0], true);
                if (Boolean.TRUE.equals(p.getObjectExtra("restoreLock"))) locked.setBoolean(p.args[0], true);
                Integer originalRange = (Integer) p.getObjectExtra("restoreRange");
                if (originalRange != null) range.setInt(p.args[0], originalRange);
            }
        });
        hooks.hook(scene, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                Object state = p.args[1];
                // X has finished eligibility checks. Give OEM accounting the actual screen and range.
                if (policy.keepOnLock) locked.setBoolean(state, XposedHelpers.getBooleanField(p.thisObject, "K"));
                if (policy.mode == FanPolicy.PER_APP) range.setInt(state, XposedHelpers.getIntField(p.thisObject, "k"));
                // A null scene includes OEM microphone, earpiece, lock and out-of-range stops.
                if (p.args[0] == null) return;
                int gear = sceneGear;
                if (gear == FanPolicy.INHERIT) {
                    // An unconfigured app uses the OEM smart temperature curve in the selected scene.
                    if (policy.mode == FanPolicy.PER_APP && stockMode.getInt(state) != -1) {
                        p.setObjectExtra("originalMode", stockMode.getInt(state));
                        stockMode.setInt(state, -1);
                    }
                    return;
                }
                // Start the next stock temperature curve from its entry thresholds after a fixed override.
                XposedHelpers.setIntField(p.thisObject, "j", Integer.MIN_VALUE);
                if (gear != XposedHelpers.getIntField(p.thisObject, "o")) setGear.invoke(p.thisObject, gear, state);
                p.setResult(null);
            }
            @Override protected void afterHookedMethod(MethodHookParam p) throws IllegalAccessException {
                Integer original = (Integer) p.getObjectExtra("originalMode");
                if (original != null) stockMode.setInt(p.args[1], original);
            }
        });
        hooks.hook(write, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!"target_level".equals(p.args[0])) return;
                int requested = FanRules.number((String) p.args[1]);
                int applied = FanRules.capped(requested, maxGear);
                if (requested != applied) p.args[1] = Integer.toString(applied);
            }
        });
        // Keep the public gear consistent with the driver. The private requested
        // gear stays available to the stock temperature hysteresis and self-clean timer.
        hooks.hook(publish, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if ("fan_level".equals(p.args[1])) p.args[2] = FanRules.capped((int) p.args[2], maxGear);
            }
        });
        FanModule.deoptimize(setGear, evaluate, message, refreshPolicy);
        FanModule.deoptimizeIfPresent(handler, "x", String.class);
        for (String caller : new String[]{"L", "n", "e"}) FanModule.deoptimizeIfPresent(handler, caller);
    }

    private void attach(Handler owner) {
        if (fan == owner) return;
        if (fan != null) {
            fan.removeCallbacks(refresh);
            resolver.unregisterContentObserver(observer);
        }
        fan = owner;
        currentUser = (ContentResolver) XposedHelpers.getObjectField(owner, "f");
        policy = FanPolicy.DEFAULT;
        lastForeground = null;
        initialSync = true;
        loadLimit = true;
        loadPolicy = true;
        held = false;
        observer = new ContentObserver(owner) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                if (uri == null || FanLimits.URI.equals(uri)) loadLimit = true;
                if (uri == null) loadPolicy = true;
                else for (Uri setting : FanPolicySettings.URIS) {
                    if (setting.equals(uri)) loadPolicy = true;
                }
                queueRefresh();
            }
        };
        resolver.registerContentObserver(FanLimits.URI, false, observer);
        resolver.registerContentObserver(Settings.Global.getUriFor(FanModule.FIX_CHARGE), false, observer);
        resolver.registerContentObserver(Settings.Secure.getUriFor(FanModule.PROTECT_MODE), false, observer);
        // PowerKeeper serves several Android users. The OEM resolver reads USER_CURRENT on each switch.
        for (Uri setting : FanPolicySettings.URIS) {
            XposedHelpers.callMethod(resolver, "registerContentObserver", setting, false, observer, -1);
        }
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
            Object miCharge = chargeService.get(fan);
            if (miCharge == null) return false;
            String flags = (String) readChargePath.invoke(miCharge, "smart_chg");
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
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
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
        if (loadLimit) {
            readLimit();
            loadPolicy = true;
        }
        boolean policyChanged = loadPolicy;
        if (loadPolicy) {
            policy = FanPolicySettings.read(currentUser);
            loadPolicy = false;
        }
        int mode = Settings.Secure.getInt(resolver, FanModule.PROTECT_MODE, 1);
        boolean watching = readChargePath != null && mode == 2 && Settings.Global.getInt(resolver, FanModule.FIX_CHARGE, 1) == 1
                && XposedHelpers.getBooleanField(fan, "I") && XposedHelpers.getBooleanField(fan, "y")
                && XposedHelpers.getBooleanField(fan, "B") && FanRules.cablePresent();
        held = watching && limitStoppedCharge(mode);
        if (previousHold && !held) XposedBridge.log("warsaw Fans Control: released rapid-charge cooling");
        boolean updateCap = maxGear != previousMax || (initialSync && FanRules.isGear(maxGear));
        if (held != previousHold || updateCap || policyChanged) {
            // C rebuilds the snapshot from live OEM state and arbitrates all scenes.
            XposedHelpers.callMethod(fan, "C");
            if (updateCap) applyCapToRunningFan();
        }
        initialSync = false;
        if (watching || loadLimit) fan.postDelayed(refresh, 5000);
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
