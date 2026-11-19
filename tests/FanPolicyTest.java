package dev.k90fan.control;

public final class FanPolicyTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String game = "com.example.game";
        String settings = "com.android.settings";
        FanPolicy stored = new FanPolicy(FanPolicy.PER_APP, true,
                game + "=2\ninvalid\n" + settings + "=0\nmalformed/path=4\nbad=9\n" + game + "=4");
        check(stored.apps.size() == 2 && stored.keepOnLock, "ignore malformed entries, preserve other rules");
        check(stored.requestedGear(game) == 4 && stored.requestedGear(settings) == 0, "foreground burst and off");
        check(stored.requestedGear("com.example") == FanPolicy.INHERIT, "match the whole package");
        check(stored.requestedGear(null) == FanPolicy.INHERIT, "no foreground at boot");
        for (int gear = 0; gear <= 4; gear++) {
            FanPolicy edited = new FanPolicy(FanPolicy.PER_APP, false, stored.withRule(game, gear));
            check(edited.requestedGear(game) == gear && edited.requestedGear(settings) == 0, "save any stock gear");
            check(FanRules.capped(edited.requestedGear(game), 2) == Math.min(gear, 2), "cap applies after profile");
        }
        String afterRemoval = stored.withRule(game, FanPolicy.INHERIT);
        check(afterRemoval.equals(settings + "=0"), "inherit removes exactly one rule");
        check(new FanPolicy(FanPolicy.STOCK, true, afterRemoval).requestedGear(settings) == FanPolicy.INHERIT,
                "disabling per-app overrides retains saved rules");
        check(new FanPolicy(FanPolicy.BURST, false, afterRemoval).requestedGear(settings) == 4, "burst mode");
        check(new FanPolicy(77, false, afterRemoval).requestedGear(settings) == FanPolicy.INHERIT, "unknown mode");
        check(FanPolicy.DEFAULT.apps.isEmpty() && !FanPolicy.DEFAULT.keepOnLock, "fresh and upgraded defaults");
        check(FanPolicy.isLockSurface("com.android.systemui") && FanPolicy.isLockSurface("com.miui.aod")
                && !FanPolicy.isLockSurface("com.miui.home"), "lock UI and actual launcher have distinct roles");
        System.out.println("Fan policy tests passed");
    }
}
