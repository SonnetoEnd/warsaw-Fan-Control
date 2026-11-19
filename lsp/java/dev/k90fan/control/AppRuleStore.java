package dev.k90fan.control;

import android.content.Context;
import android.content.SharedPreferences;

final class AppRuleStore {
    private static SharedPreferences preferences(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("fan_app_rules", Context.MODE_PRIVATE);
    }

    static synchronized String read(Context context, String legacy) {
        SharedPreferences prefs = preferences(context);
        String saved = prefs.getString("rules", null);
        if (saved != null) return saved;
        if (legacy == null || legacy.isEmpty()) return "";
        prefs.edit().putString("rules", legacy).commit();
        return legacy;
    }

    static synchronized boolean save(Context context, String name, int gear) {
        FanPolicy current = new FanPolicy(FanPolicy.PER_APP, false, read(context, null));
        if (!preferences(context).edit().putString("rules", current.withRule(name, gear)).commit()) return false;
        context.getContentResolver().notifyChange(FanPolicySettings.RULES_URI, null);
        return true;
    }

    private AppRuleStore() {}
}
