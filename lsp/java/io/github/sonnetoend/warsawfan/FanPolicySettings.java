package io.github.sonnetoend.warsawfan;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

final class FanPolicySettings {
    static final String MODE = "k90_fan_policy";
    static final String KEEP_ON_LOCK = "k90_fan_keep_on_lock";
    static final String APPS = "k90_fan_app_rules";
    static final Uri RULES_URI = FanLimits.URI.buildUpon().appendPath("apps").build();
    static final String BURST_VALUE = "k90_burst";
    static final String APPS_VALUE = "k90_apps";
    static final String BURST_TITLE = "狂暴模式";
    static final String APPS_TITLE = "按应用自定义";
    static final Uri[] URIS = {Settings.Secure.getUriFor(MODE),
            Settings.Secure.getUriFor(KEEP_ON_LOCK), RULES_URI};

    static FanPolicy read(ContentResolver resolver) {
        String rules = Settings.Secure.getString(resolver, APPS); // v0.4.0
        try {
            Bundle result = resolver.call(FanLimits.URI, "readApps", rules, null);
            if (result != null) rules = result.getString("rules", rules);
        } catch (RuntimeException ignored) {
            // The module provider can be starting during boot. The fan handler retries when it attaches.
        }
        return new FanPolicy(Settings.Secure.getInt(resolver, MODE, FanPolicy.STOCK),
                Settings.Secure.getInt(resolver, KEEP_ON_LOCK, 0) == 1,
                rules);
    }

    static int modeFor(Object choice) {
        if (BURST_VALUE.equals(choice)) return FanPolicy.BURST;
        if (APPS_VALUE.equals(choice)) return FanPolicy.PER_APP;
        return FanPolicy.STOCK;
    }

    static String valueFor(int mode, String stock) {
        if (mode == FanPolicy.BURST) return BURST_VALUE;
        if (mode == FanPolicy.PER_APP) return APPS_VALUE;
        return stock;
    }

    private FanPolicySettings() {}
}
