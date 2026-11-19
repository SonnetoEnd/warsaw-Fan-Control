package io.github.sonnetoend.warsawfan;

public final class FanHooksTest {
    private static class Base {
        private CharSequence title;
        private void inherited() {}
    }

    private static final class Variant extends Base {
        private static boolean shared;
        private final int fixed = 1;
        private int gear;
        private String state() { return "ready"; }
        private static void changed() {}
    }

    private interface Probe { void run() throws ReflectiveOperationException; }

    private static void rejected(Probe probe) throws ReflectiveOperationException {
        try {
            probe.run();
        } catch (NoSuchFieldException | NoSuchMethodException expected) {
            return;
        }
        throw new AssertionError("Accepted an incompatible OEM member");
    }

    public static void main(String[] args) throws ReflectiveOperationException {
        Variant variant = new Variant();
        FanHooks.writeField(Variant.class, "title", String.class).set(variant, "fan");
        if (!"fan".equals(FanHooks.readField(Variant.class, "title", CharSequence.class).get(variant)))
            throw new AssertionError("Inherited writable field");
        FanHooks.writeField(Variant.class, "gear", int.class).setInt(variant, 4);
        if (variant.gear != 4) throw new AssertionError("Primitive field");
        FanHooks.method(Variant.class, "state", CharSequence.class);
        rejected(() -> FanHooks.method(Variant.class, "state", int.class));
        rejected(() -> FanHooks.method(Variant.class, "changed", void.class));
        rejected(() -> FanHooks.method(Variant.class, "inherited", void.class));
        rejected(() -> FanHooks.writeField(Variant.class, "fixed", int.class));
        rejected(() -> FanHooks.readField(Variant.class, "gear", boolean.class));
        rejected(() -> FanHooks.writeField(Variant.class, "title", int.class));
        rejected(() -> FanHooks.readField(Variant.class, "shared", boolean.class));
        rejected(() -> FanHooks.readField(Variant.class, "missing", boolean.class));
        System.out.println("Fan interface compatibility tests passed");
    }
}
