package io.github.sonnetoend.warsawfan;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.*;

import java.text.Collator;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppRulesActivity extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    // Gear 3 can still be displayed for a profile saved by v0.4.0 or v0.4.1.
    private static final String[] MODES = {"智能调频", "关闭风扇", "静谧模式", "高速强冷", "原厂 3 档", "狂暴模式"};
    private static final int[] MENU_GEARS = {FanPolicy.INHERIT, 1, 2, 4, 0};
    private static final String[] SORT = {"应用名称", "包名", "安装时间", "更新时间"};
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final LruCache<String, Bitmap> icons = new LruCache<>(80);
    private final Set<String> pendingIcons = new HashSet<>();
    private final List<Entry> installed = new ArrayList<>();
    private final List<Entry> visible = new ArrayList<>();
    private final Collator collator = Collator.getInstance(Locale.CHINA);
    private final Rows rows = new Rows();
    private SharedPreferences display;
    private FanPolicy rules;
    private FrameLayout root;
    private ListView list;
    private EditText search;
    private TextView empty;
    private PopupWindow popup;
    private boolean dark;
    private boolean ready;
    private boolean showSystem;
    private boolean reverse;
    private int sort;
    private int pageColor, cardColor, primary, secondary;
    private int restoreIndex, restoreOffset;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        pageColor = dark ? Color.BLACK : 0xfff5f5f5;
        cardColor = dark ? 0xff242424 : Color.WHITE;
        primary = dark ? 0xfff2f2f2 : 0xff161616;
        secondary = dark ? 0xff929292 : 0xff696969;
        display = getSharedPreferences("app_list_display", MODE_PRIVATE);
        showSystem = display.getBoolean("system", false);
        reverse = display.getBoolean("reverse", false);
        sort = display.getInt("sort", 0);
        rules = new FanPolicy(FanPolicy.PER_APP, false, AppRuleStore.read(this, null));
        root = new FrameLayout(this);
        root.setBackgroundColor(pageColor);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFocusableInTouchMode(true);
        root.addView(page, MATCH, MATCH);
        setContentView(root);
        configureWindow();

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        toolbar.setPadding(0, 0, dp(12), 0);
        page.addView(toolbar, MATCH, dp(48));
        action(toolbar, "rules_sort", "排序", this::sortMenu);
        action(toolbar, "rules_more", "更多选项", this::filterMenu);
        TextView title = text("应用配置", 32, primary);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(28), 0, 0, 0);
        title.setAccessibilityHeading(true);
        page.addView(title, MATCH, dp(58));
        addSearch(page);
        FrameLayout results = new FrameLayout(this);
        page.addView(results, new LinearLayout.LayoutParams(MATCH, 0, 1));
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setAdapter(rows);
        results.addView(list, MATCH, MATCH);
        empty = text("正在读取应用…", 15, secondary);
        empty.setGravity(Gravity.CENTER);
        results.addView(empty, MATCH, MATCH);
        list.setEmptyView(empty);

        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            int left = insets.getSystemWindowInsetLeft(), right = insets.getSystemWindowInsetRight();
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                left = bars.left;
                right = bars.right;
                bottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
            }
            page.setPadding(left, top, right, 0);
            list.setPadding(dp(12), 0, dp(12), bottom + dp(4));
            return insets;
        });
        root.requestApplyInsets();
        if (state != null) {
            search.setText(state.getString("query", ""));
            restoreIndex = state.getInt("index");
            restoreOffset = state.getInt("offset");
        }
        loader.execute(this::loadApps);
    }

    private void configureWindow() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setNavigationBarDividerColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
            window.setStatusBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(dark ? 0 : light, light);
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (!dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void addSearch(LinearLayout page) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(18), 0, dp(14), 0);
        box.setBackground(rounded(cardColor, 26));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, dp(46));
        params.setMargins(dp(12), dp(6), dp(12), dp(12));
        page.addView(box, params);
        box.addView(glyph("rules_search", secondary), dp(18), dp(18));
        search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("搜索");
        search.setTextSize(16);
        search.setTextColor(primary);
        search.setHintTextColor(secondary);
        search.setBackgroundColor(Color.TRANSPARENT);
        search.setPadding(dp(9), 0, 0, 0);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.addView(search, MATCH, MATCH);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (ready) { filter(); list.setSelection(0); }
            }
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        Map<String, Entry> apps = new HashMap<>();
        try {
            for (PackageInfo info : pm.getInstalledPackages(0)) {
                ApplicationInfo app = info.applicationInfo;
                if (app == null || getPackageName().equals(info.packageName)
                        || (app.flags & ApplicationInfo.FLAG_HAS_CODE) == 0) continue;
                CharSequence label = app.loadLabel(pm);
                apps.put(info.packageName, new Entry(info.packageName, label == null ? info.packageName : label.toString(),
                        app, info.firstInstallTime, info.lastUpdateTime));
            }
            for (String name : rules.apps.keySet()) apps.putIfAbsent(name, new Entry(name, name, null, 0, 0));
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                installed.addAll(apps.values());
                ready = true;
                empty.setText("没有匹配的应用");
                filter();
                list.setSelectionFromTop(restoreIndex, restoreOffset);
            });
        } catch (RuntimeException e) {
            runOnUiThread(() -> { if (!isDestroyed()) empty.setText("无法读取应用列表，请重新打开"); });
        }
    }

    private void filter() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        visible.clear();
        for (Entry app : installed) {
            boolean system = app.info != null && (app.info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            if ((!system || showSystem || rules.apps.containsKey(app.name)) && app.search.contains(query)) visible.add(app);
        }
        Comparator<Entry> by;
        switch (sort) {
            case 1: by = Comparator.comparing(app -> app.name); break;
            case 2: by = Comparator.comparingLong((Entry app) -> app.installed).reversed(); break;
            case 3: by = Comparator.comparingLong((Entry app) -> app.updated).reversed(); break;
            default: by = (a, b) -> collator.compare(a.label, b.label);
        }
        by = by.thenComparing(app -> app.name);
        visible.sort(reverse ? by.reversed() : by);
        rows.notifyDataSetChanged();
    }

    private void filterMenu(View anchor) {
        menu(anchor, new String[]{"显示系统应用"}, showSystem ? 0 : -1, index -> {
            showSystem = !showSystem;
            display.edit().putBoolean("system", showSystem).apply();
            filter();
        });
    }

    private void sortMenu(View anchor) {
        String[] choices = Arrays.copyOf(SORT, SORT.length + 1);
        choices[SORT.length] = reverse ? "倒序  ✓" : "倒序";
        menu(anchor, choices, sort, index -> {
            if (index == SORT.length) reverse = !reverse; else sort = index;
            display.edit().putInt("sort", sort).putBoolean("reverse", reverse).apply();
            filter();
            list.setSelection(0);
        });
    }

    private void modeMenu(View anchor, Entry app) {
        String[] names = new String[MENU_GEARS.length];
        int checked = -1;
        for (int i = 0; i < names.length; i++) {
            names[i] = MODES[MENU_GEARS[i] + 1];
            if (MENU_GEARS[i] == rules.appGear(app.name)) checked = i;
        }
        menu(anchor, names, checked, index -> {
            if (AppRuleStore.save(this, app.name, MENU_GEARS[index])) {
                rules = new FanPolicy(FanPolicy.PER_APP, false, AppRuleStore.read(this, null));
                filter();
            } else Toast.makeText(this, "未能保存应用规则", Toast.LENGTH_SHORT).show();
        });
    }

    private void menu(View anchor, String[] choices, int checked, java.util.function.IntConsumer select) {
        if (popup != null) popup.dismiss();
        View shade = new View(this);
        shade.setBackgroundColor(0x96000000);
        root.addView(shade, MATCH, MATCH);
        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.VERTICAL);
        items.setPadding(0, dp(6), 0, dp(6));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(items);
        int width = Math.min(dp(204), root.getWidth() - dp(24));
        PopupWindow window = new PopupWindow(scroll, width, WRAP, true);
        popup = window;
        window.setBackgroundDrawable(rounded(cardColor, 18));
        window.setOutsideTouchable(true);
        window.setElevation(dp(4));
        for (int i = 0; i < choices.length; i++) {
            final int index = i;
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setPadding(dp(20), 0, dp(16), 0);
            line.setBackground(ripple(0, 12));
            TextView label = text(choices[i], 16, primary);
            line.addView(label, new LinearLayout.LayoutParams(0, WRAP, 1));
            if (i == checked) line.addView(glyph("rules_check", dark ? 0xff84aaff : 0xff3474ef), dp(19), dp(19));
            items.addView(line, MATCH, dp(50));
            line.setOnClickListener(v -> { window.dismiss(); select.accept(index); });
        }
        window.setOnDismissListener(() -> { root.removeView(shade); popup = null; });
        int available = window.getMaxAvailableHeight(anchor);
        if (available > 0) window.setHeight(Math.min(dp(12 + 50 * choices.length), available));
        window.showAsDropDown(anchor, 0, dp(3), Gravity.END);
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Entry getItem(int pos) { return visible.get(pos); }
        @Override public long getItemId(int pos) { return pos; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            FrameLayout wrap = (FrameLayout) recycled;
            Row row;
            if (wrap == null) {
                wrap = new FrameLayout(AppRulesActivity.this);
                wrap.setPadding(0, 0, 0, dp(12));
                row = new Row();
                wrap.addView(row.card, MATCH, WRAP);
                wrap.setTag(row);
            } else row = (Row) wrap.getTag();
            Entry app = getItem(position);
            row.title.setText(app.label);
            row.pkg.setText(app.name);
            row.mode.setText(MODES[rules.appGear(app.name) + 1]);
            row.card.setOnClickListener(v -> modeMenu(v, app));
            Bitmap icon = icons.get(app.name);
            if (icon != null) row.icon.setImageBitmap(icon);
            else {
                row.icon.setImageDrawable(getPackageManager().getDefaultActivityIcon());
                loadIcon(app);
            }
            return wrap;
        }
    }

    private final class Row {
        final LinearLayout card = new LinearLayout(AppRulesActivity.this);
        final ImageView icon = new ImageView(AppRulesActivity.this);
        final TextView title = text("", 16, primary);
        final TextView pkg = text("", 14, secondary);
        final TextView mode = text("", 14, secondary);
        Row() {
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setMinimumHeight(dp(80));
            card.setPadding(dp(20), dp(12), dp(14), dp(12));
            card.setBackground(ripple(cardColor, 16));
            card.setFocusable(true);
            card.addView(icon, dp(40), dp(40));
            LinearLayout labels = new LinearLayout(AppRulesActivity.this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.setPadding(dp(18), 0, dp(8), 0);
            labels.addView(title);
            labels.addView(pkg);
            card.addView(labels, new LinearLayout.LayoutParams(0, WRAP, 1));
            card.addView(mode);
            LinearLayout.LayoutParams arrows = new LinearLayout.LayoutParams(dp(14), dp(17));
            arrows.setMarginStart(dp(5));
            card.addView(glyph("rules_chevrons", secondary), arrows);
        }
    }

    private void loadIcon(Entry app) {
        if (app.info == null || !pendingIcons.add(app.name)) return;
        loader.execute(() -> {
            Bitmap bitmap = null;
            try {
                Drawable icon = app.info.loadIcon(getPackageManager());
                int size = dp(40);
                bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                icon.setBounds(0, 0, size, size);
                icon.draw(new Canvas(bitmap));
            } catch (RuntimeException ignored) { /* App can be removed while the list is open. */ }
            final Bitmap loaded = bitmap;
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                if (loaded != null) icons.put(app.name, loaded);
                for (int i = 0; i < list.getChildCount(); i++) {
                    Row row = (Row) list.getChildAt(i).getTag();
                    if (loaded != null && row != null && app.name.contentEquals(row.pkg.getText())) row.icon.setImageBitmap(loaded);
                }
                if (loaded != null) pendingIcons.remove(app.name);
            });
        });
    }

    private int dp(float value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private ImageView glyph(String name, int color) {
        ImageView view = new ImageView(this);
        view.setImageResource(getResources().getIdentifier(name, "drawable", getPackageName()));
        view.setColorFilter(color);
        return view;
    }

    private void action(LinearLayout bar, String icon, String description, View.OnClickListener click) {
        ImageView button = glyph(icon, primary);
        button.setPadding(dp(9), dp(9), dp(9), dp(9));
        button.setContentDescription(description);
        button.setBackground(ripple(0, 24));
        button.setFocusable(true);
        button.setOnClickListener(click);
        bar.addView(button, dp(40), dp(40));
    }

    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private Drawable ripple(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(dark ? 0x33ffffff : 0x18000000),
                rounded(color, radius), rounded(Color.WHITE, radius));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("query", search.getText().toString());
        state.putInt("index", list.getFirstVisiblePosition());
        View first = list.getChildAt(0);
        state.putInt("offset", first == null ? 0 : first.getTop());
    }

    @Override protected void onDestroy() {
        if (popup != null) popup.dismiss();
        loader.shutdownNow();
        super.onDestroy();
    }

    private static final class Entry {
        final String name, label, search;
        final ApplicationInfo info;
        final long installed, updated;
        Entry(String name, String label, ApplicationInfo info, long installed, long updated) {
            this.name = name;
            this.label = label;
            this.info = info;
            this.installed = installed;
            this.updated = updated;
            search = (label + "\n" + name).toLowerCase(Locale.ROOT);
        }
    }
}
