package io.github.sonnetoend.warsawfan;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

final class FanHooks {
    private final ArrayList<XC_MethodHook.Unhook> installed = new ArrayList<>();

    void hook(Method method, XC_MethodHook callback) {
        installed.add(XposedBridge.hookMethod(method, callback));
    }

    void rollback() {
        for (int i = installed.size() - 1; i >= 0; i--) {
            try {
                installed.get(i).unhook();
            } catch (RuntimeException | LinkageError e) {
                XposedBridge.log(e);
            }
        }
        installed.clear();
    }

    static Method method(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameters);
        int flags = method.getModifiers();
        if (Modifier.isStatic(flags) || Modifier.isAbstract(flags) || !result.isAssignableFrom(method.getReturnType()))
            throw new NoSuchMethodException(owner.getName() + "." + name + " must return " + result.getName());
        return method;
    }

    static Field readField(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
        Field field = instanceField(owner, name);
        if (!type.isAssignableFrom(field.getType())) throw new NoSuchFieldException(field + " must provide " + type.getName());
        return field;
    }

    static Field writeField(Class<?> owner, String name, Class<?> value) throws NoSuchFieldException {
        Field field = instanceField(owner, name);
        if (Modifier.isFinal(field.getModifiers()) || !field.getType().isAssignableFrom(value))
            throw new NoSuchFieldException(field + " must accept " + value.getName());
        return field;
    }

    private static Field instanceField(Class<?> owner, String name) throws NoSuchFieldException {
        for (Class<?> parent = owner; parent != null; parent = parent.getSuperclass()) {
            Field field;
            try {
                field = parent.getDeclaredField(name);
            } catch (NoSuchFieldException absent) {
                continue;
            }
            if (Modifier.isStatic(field.getModifiers())) break;
            field.setAccessible(true);
            return field;
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }
}
