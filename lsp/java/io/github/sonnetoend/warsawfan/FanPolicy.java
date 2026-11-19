package io.github.sonnetoend.warsawfan;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

final class FanPolicy {
    static final int STOCK = 0;
    static final int BURST = 1;
    static final int PER_APP = 2;
    static final int INHERIT = -1;
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*");
    static final FanPolicy DEFAULT = new FanPolicy(STOCK, false, "");
    final int mode;
    final boolean keepOnLock;
    final Map<String, Integer> apps;

    FanPolicy(int mode, boolean keepOnLock, String rules) {
        this.mode = mode == BURST || mode == PER_APP ? mode : STOCK;
        this.keepOnLock = keepOnLock;
        Map<String, Integer> parsed = new TreeMap<>();
        if (rules != null && !rules.isEmpty()) {
            for (String line : rules.split("\n")) {
                int separator = line.indexOf('=');
                if (separator <= 0) continue;
                String name = line.substring(0, separator);
                int gear = FanRules.number(line.substring(separator + 1));
                if (validRule(name, gear)) parsed.put(name, gear);
            }
        }
        apps = Collections.unmodifiableMap(parsed);
    }

    int appGear(String name) {
        return name == null ? INHERIT : apps.getOrDefault(name, INHERIT);
    }

    int requestedGear(String name) {
        if (mode == BURST) return 4;
        return mode == PER_APP ? appGear(name) : INHERIT;
    }

    static boolean isLockSurface(String name) {
        return "com.android.systemui".equals(name) || "com.miui.aod".equals(name);
    }

    String withRule(String name, int gear) {
        if (!validRule(name, gear == INHERIT ? 0 : gear)) throw new IllegalArgumentException("Invalid app rule");
        Map<String, Integer> updated = new TreeMap<>(apps);
        if (gear == INHERIT) updated.remove(name);
        else updated.put(name, gear);
        StringBuilder result = new StringBuilder();
        for (Map.Entry<String, Integer> entry : updated.entrySet()) {
            if (result.length() > 0) result.append('\n');
            result.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return result.toString();
    }

    private static boolean validRule(String name, int gear) {
        return name != null && PACKAGE.matcher(name).matches() && (gear == 0 || FanRules.isGear(gear));
    }
}
