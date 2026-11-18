package dev.k90fan.control;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

final class FanRules {
    static final String FAN = "/sys/devices/platform/soc/soc:xiaomi_fan/";
    static final String CHARGE_ENABLE = "/sys/class/xm_power/charger/charge_interface/charge_enable";
    static final String USB = "/sys/class/power_supply/usb/";
    static final int[] RPM = {0, 12000, 14000, 16000, 19800};
    static final int SYSTEM = 0;
    static final int MIN_RPM = 12000;
    static final int MAX_RPM = 19800;
    static final int STEP = 100;

    static boolean isGear(int value) {
        return value >= 1 && value < RPM.length;
    }

    static boolean custom(int value) {
        return value >= MIN_RPM && value <= MAX_RPM && value % STEP == 0;
    }

    static boolean valid(int value) {
        return value == SYSTEM || isGear(value) || custom(value);
    }

    static int legacy(int value) {
        return isGear(value) ? value : SYSTEM;
    }

    static int capped(int requested, int limit) {
        return isGear(limit) && isGear(requested) ? Math.min(requested, limit) : requested;
    }

    static int number(String value) {
        if (value == null) return -1;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static int readNumber(String path) {
        try (BufferedReader input = new BufferedReader(new FileReader(path))) {
            return number(input.readLine());
        } catch (IOException | SecurityException e) {
            return -1;
        }
    }

    static boolean cablePresent() {
        int present = readNumber(USB + "present");
        return (present < 0 ? readNumber(USB + "online") : present) == 1;
    }

    static boolean hasStopVote(BufferedReader votes) throws IOException {
        String line;
        while ((line = votes.readLine()) != null) {
            if ("buck eff_client:soc_limit 0".equals(line.trim())) return true;
        }
        return false;
    }

    static boolean activeProtection(int mode, int flags, boolean stopVote) {
        return mode == 2 && flags >= 0 && (flags & 16) != 0 && stopVote;
    }

    private FanRules() {}
}
