package dev.k90fan.control;

import java.io.BufferedReader;
import java.io.StringReader;

public final class FanRulesTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static boolean hold(int mode, String flags, String votes) throws Exception {
        try (BufferedReader input = new BufferedReader(new StringReader(votes))) {
            return FanRules.activeProtection(mode, FanRules.number(flags), FanRules.hasStopVote(input));
        }
    }

    public static void main(String[] args) throws Exception {
        String capVote = "buck eff_client:soc_limit 0\n";
        check(hold(2, "16\n", capVote), "wired cap reached");
        check(hold(2, "18", "wl_buck eff_client:other 0\n" + capVote), "other votes and smart-charge flags");
        check(!hold(2, "16", "buck eff_client:wire_qc 0\n"), "charger still delivering fast charge");
        check(!hold(2, "16", "buck eff_client:term_recharge 0\n"), "natural full");
        check(!hold(2, "16", "buck eff_client:soc_limit 1\n"), "charging resumed on the same cable");
        check(!hold(1, "16", capVote), "user left 80% mode");
        check(!hold(2, "0", capVote), "protection cleared before vote was refreshed");
        check(!hold(2, "error", capVote), "unavailable smart-charge state");
        check(!hold(2, "16", "wl_buck eff_client:soc_limit 0\n"), "wireless-only vote");
        for (int limit = 1; limit <= 4; limit++) {
            for (int demand = 0; demand <= 4; demand++) {
                check(FanRules.capped(demand, limit) == Math.min(demand, limit), "stock demand and gear cap");
            }
        }
        check(FanRules.capped(3, 1) == 1 && FanRules.capped(3, 4) == 3, "raise cap with a standing request");
        check(FanRules.capped(0, 1) == 0, "off stays off");
        check(FanRules.capped(4, 0) == 4 && FanRules.capped(3, 99) == 3, "invalid saved cap");
        check(FanRules.capped(103, 1) == 103, "leave non-gear commands alone");
        check(FanRules.legacy(-1) == 0 && FanRules.legacy(3) == 3, "fresh and existing installations");
        for (int rpm = 12000; rpm <= 19800; rpm += 100) {
            check(FanRules.valid(rpm) && FanRules.custom(rpm), "every 100 RPM choice");
            for (int gear = 0; gear <= 4; gear++) {
                check(FanRules.capped(gear, rpm) == gear && FanRules.capped(gear, 0) == gear,
                        "driver applies custom ceilings and the OEM chooses the gear");
            }
        }
        for (int invalid : new int[]{-1, 5, 11900, 12001, 19900, 20500}) {
            check(!FanRules.valid(invalid), "invalid setpoint");
        }
        System.out.println("Fan charge protection and gear tests passed");
    }
}
