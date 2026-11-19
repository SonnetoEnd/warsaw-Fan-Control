package io.github.sonnetoend.warsawfan;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.FileInputStream;
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

    static int[] readGearTargets() {
        // Device-tree cells are big-endian duty/RPM pairs, starting with the stopped fan.
        try (DataInputStream input = new DataInputStream(new FileInputStream(FAN + "of_node/duty_speed_map"))) {
            int[] targets = new int[RPM.length];
            for (int gear = 0; gear < targets.length; gear++) {
                int duty = input.readInt();
                int target = input.readInt();
                if (gear == 0 ? duty != 0 || target != 0 : duty <= 0 || target <= targets[gear - 1])
                    return new int[0];
                targets[gear] = target;
            }
            return targets;
        } catch (IOException | SecurityException unavailable) {
            return new int[0];
        }
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

    enum BuckVote { OTHER, SOC_LIMIT_OFF, ONLINE_OFF }

    static BuckVote buckVote(BufferedReader votes) throws IOException {
        BuckVote result = BuckVote.OTHER;
        String line;
        while ((line = votes.readLine()) != null) {
            switch (line.trim()) {
                case "buck eff_client:soc_limit 0": return BuckVote.SOC_LIMIT_OFF;
                case "buck eff_client:online 0": result = BuckVote.ONLINE_OFF; break;
            }
        }
        return result;
    }

    static boolean activeProtection(int mode, int flags, BuckVote vote, int level, int currentUa,
                                    boolean previouslyHeld) {
        if (mode != 2 || flags < 0 || (flags & 16) == 0) return false;
        if (vote == BuckVote.SOC_LIMIT_OFF) return true;
        if (vote != BuckVote.ONLINE_OFF || (level != 79 && level != 80)) return false;
        // A disabled buck can also mean that a separate charging path is in use.
        // Check net battery current in both directions. The cast also handles the API's MIN_VALUE sentinel.
        int maxIdleCurrentUa = previouslyHeld ? 200_000 : 100_000;
        return Math.abs((long) currentUa) <= maxIdleCurrentUa;
    }

    private FanRules() {}
}
