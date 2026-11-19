package io.github.sonnetoend.warsawfan;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

public final class FanLimitProvider extends ContentProvider {
    private SharedPreferences limits;
    private volatile int confirmed = -1;
    private volatile String restoreError;

    @Override public boolean onCreate() {
        Context storage = getContext().createDeviceProtectedStorageContext();
        limits = storage.getSharedPreferences("fan_limits", Context.MODE_PRIVATE);
        return true;
    }

    @Override public Bundle call(String method, String argument, Bundle args) {
        int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID && uid != Process.myUid() && uid != 0)
            throw new SecurityException("Only the system fan settings may manage fan limits");
        if ("readApps".equals(method)) {
            long identity = Binder.clearCallingIdentity();
            try {
                Bundle result = new Bundle();
                result.putString("rules", AppRuleStore.read(getContext(), argument));
                return result;
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        int legacy = FanRules.legacy(args == null ? 0 : args.getInt("legacy", 0));
        if ("read".equals(method)) return state(legacy);
        if (!"select".equals(method) && !"sync".equals(method))
            throw new IllegalArgumentException("Unsupported fan limit operation");
        // Files, provider notifications and su must use the module application's identity.
        long identity = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                int previous = limits.getInt("selection", legacy);
                boolean sync = "sync".equals(method);
                int next = sync ? previous : (args == null ? -1 : args.getInt("selection", -1));
                if (!FanRules.valid(next)) throw new IllegalArgumentException("Invalid fan limit");
                return change(legacy, previous, next, sync);
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private Bundle state(int legacy) {
        int selection = limits.getInt("selection", legacy);
        Bundle result = new Bundle();
        result.putInt("selection", selection);
        result.putInt("last", limits.getInt("last", FanRules.isGear(selection) ?
                FanRules.RPM[selection] : FanRules.MAX_RPM));
        result.putBoolean("needsSync", limits.getBoolean("pending", false)
                || (FanRules.custom(selection) && confirmed != selection));
        if (restoreError != null) result.putString("restoreError", restoreError);
        return result;
    }

    private Bundle failure(int legacy, String error) {
        Bundle result = state(legacy);
        result.putString("error", error);
        return result;
    }

    private Bundle change(int legacy, int previous, int next, boolean sync) {
        boolean pending = limits.getBoolean("pending", false);
        boolean hardware = pending || FanRules.custom(previous) || FanRules.custom(next);
        if (sync && !pending && (!FanRules.custom(next) || confirmed == next)) return state(legacy);
        if (hardware) {
            // Recover the last committed selection if the process dies between the kernel write and commit.
            if (!limits.edit().putInt("selection", previous).putBoolean("pending", true).commit())
                return failure(legacy, "save_failed");
            String error = RootFanDriver.apply(getContext(), FanRules.custom(next) ? next : 0);
            if (error != null) {
                boolean untouched = "root_required".equals(error) || error.startsWith("unsupported_")
                        || "legacy_active".equals(error) || "kernel_su_required".equals(error)
                        || "busy".equals(error) || "fan_unavailable".equals(error);
                if (!pending && untouched) limits.edit().remove("pending").commit();
                else confirmed = -1;
                if (sync) restoreError = error;
                return failure(legacy, error);
            }
        }
        SharedPreferences.Editor edit = limits.edit().putInt("selection", next).remove("pending");
        if (FanRules.custom(next)) edit.putInt("last", next);
        int last = limits.getInt("last", -1);
        if (!edit.commit()) {
            SharedPreferences.Editor undo = limits.edit().putInt("selection", previous).putBoolean("pending", hardware);
            if (last < 0) undo.remove("last"); else undo.putInt("last", last);
            undo.commit();
            if (hardware && RootFanDriver.apply(getContext(), FanRules.custom(previous) ? previous : 0) == null)
                limits.edit().remove("pending").commit();
            confirmed = -1;
            return failure(legacy, "save_failed");
        }
        confirmed = next;
        restoreError = null;
        if (!sync || pending) getContext().getContentResolver().notifyChange(FanLimits.URI, null);
        return state(legacy);
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] columns, String filter, String[] args, String order) {
        throw new UnsupportedOperationException();
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String filter, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String filter, String[] args) {
        throw new UnsupportedOperationException();
    }
}
