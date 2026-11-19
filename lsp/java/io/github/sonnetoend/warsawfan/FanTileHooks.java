package io.github.sonnetoend.warsawfan;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setBooleanField;
import static de.robv.android.xposed.XposedHelpers.setIntField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;

final class FanTileHooks {
    private final Context context;
    private final ContentResolver resolver;
    private WeakReference<Object> adapter = new WeakReference<>(null);

    FanTileHooks(Context context) {
        this.context = context;
        resolver = (ContentResolver) callMethod(context, "getContentResolverForUser",
                XposedHelpers.getStaticObjectField(UserHandle.class, "CURRENT"));
    }

    void install(ClassLoader loader, FanHooks hooks) throws ReflectiveOperationException {
        Class<?> detail = loader.loadClass("com.android.systemui.qs.QSDetailContent").asSubclass(View.class);
        Class<?> item = loader.loadClass(detail.getName() + "$Item");
        Class<?> choice = loader.loadClass(detail.getName() + "$SelectableItem").asSubclass(item);
        Class<?> fan = loader.loadClass("com.android.systemui.qs.tiles.CoolingFanTile$CoolingFanDetailAdapter");
        Method update = FanHooks.method(fan, "updateItems", void.class);
        Method click = FanHooks.method(fan, "onDetailItemClick", void.class, item);
        Method setItems = FanHooks.method(detail, "setItems", void.class, Array.newInstance(item, 0).getClass());
        FanHooks.method(detail, "getSuffix", String.class);
        FanHooks.method(detail, "setItemClicked", void.class, boolean.class);
        FanHooks.readField(fan, "detailView", detail);
        choice.getDeclaredConstructor();
        for (String field : new String[]{"tag", "title", "contentDescription"})
            FanHooks.writeField(choice, field, String.class);
        for (String field : new String[]{"isForceSingle", "selectable", "selected"})
            FanHooks.writeField(choice, field, boolean.class);
        FanHooks.writeField(choice, "iconRes", int.class);
        FanHooks.writeField(choice, "icon2Res", int.class);
        int checkmark = drawable("ic_qs_coolingfan_mode_selected");
        int burstIcon = drawable("ic_qs_coolingfan_mode_performance");
        int appsIcon = drawable("ic_qs_coolingfan_mode_smart");

        hooks.hook(update, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (adapter.get() != p.thisObject) adapter = new WeakReference<>(p.thisObject);
            }
        });
        hooks.hook(setItems, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                Object[] original = (Object[]) p.args[0];
                Object owner = adapter.get();
                if (owner == null || original == null || original.length == 0
                        || getObjectField(owner, "detailView") != p.thisObject
                        || !"coolingfan".equals(callMethod(p.thisObject, "getSuffix"))) return;
                int mode = Settings.Secure.getInt(resolver, FanPolicySettings.MODE, FanPolicy.STOCK);
                Object[] expanded = Arrays.copyOf(original, original.length + 2);
                expanded[original.length] = row(choice, FanPolicySettings.BURST_VALUE, FanPolicySettings.BURST_TITLE,
                        burstIcon, mode == FanPolicy.BURST, checkmark);
                expanded[original.length + 1] = row(choice, FanPolicySettings.APPS_VALUE, FanPolicySettings.APPS_TITLE,
                        appsIcon, mode == FanPolicy.PER_APP, checkmark);
                if (mode == FanPolicy.BURST || mode == FanPolicy.PER_APP) {
                    for (Object stock : original) {
                        if (!choice.isInstance(stock)) continue;
                        setBooleanField(stock, "selected", false);
                        setIntField(stock, "icon2Res", 0);
                    }
                }
                p.args[0] = expanded;
            }
        });
        hooks.hook(click, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!choice.isInstance(p.args[0])) return;
                int mode = FanPolicySettings.modeFor(getObjectField(p.args[0], "tag"));
                if (Settings.Secure.getInt(resolver, FanPolicySettings.MODE, FanPolicy.STOCK) != mode
                        && !Settings.Secure.putInt(resolver, FanPolicySettings.MODE, mode)) {
                    Toast.makeText(context, "未能保存风扇模式", Toast.LENGTH_SHORT).show();
                    p.setResult(null);
                    return;
                }
                if (mode != FanPolicy.STOCK) {
                    callMethod(p.thisObject, "updateItems");
                    Object view = getObjectField(p.thisObject, "detailView");
                    if (view != null) callMethod(view, "setItemClicked", true);
                    p.setResult(null);
                }
            }
        });
        ContentObserver changes = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                Object owner = adapter.get();
                if (owner == null) return;
                View view = (View) getObjectField(owner, "detailView");
                if (view != null && view.isAttachedToWindow() && "coolingfan".equals(callMethod(view, "getSuffix")))
                    callMethod(owner, "updateItems");
            }
        };
        FanModule.deoptimize(update);
        callMethod(context.getContentResolver(), "registerContentObserver",
                Settings.Secure.getUriFor(FanPolicySettings.MODE), false, changes, -1);
    }

    private int drawable(String name) {
        return context.getResources().getIdentifier(name, "drawable", FanModule.SYSTEM_UI);
    }

    private static Object row(Class<?> choice, String value, String title, int icon, boolean selected, int checkmark) {
        Object row = XposedHelpers.newInstance(choice);
        setObjectField(row, "tag", value);
        setObjectField(row, "title", title);
        setObjectField(row, "contentDescription", title);
        setBooleanField(row, "isForceSingle", true);
        setBooleanField(row, "selectable", true);
        setBooleanField(row, "selected", selected);
        setIntField(row, "iconRes", icon);
        setIntField(row, "icon2Res", selected ? checkmark : 0);
        return row;
    }
}
