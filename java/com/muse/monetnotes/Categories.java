package com.muse.monetnotes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 自定义分类的存储（categories.json）与选择 / 管理对话框。 */
public class Categories {
    private static final String FILE_NAME = "categories.json";
    private static final String ICON_NONE = "\u0001none";
    private static List<String> cache;
    private static java.util.Map<String, String> iconCache;

    public static synchronized List<String> load(Context ctx) {
        if (cache != null) return cache;
        cache = new ArrayList<>();
        iconCache = new java.util.HashMap<>();
        File f = new File(ctx.getFilesDir(), FILE_NAME);
        if (f.exists()) {
            try {
                FileInputStream in = new FileInputStream(f);
                byte[] buf = new byte[(int) f.length()];
                int read = in.read(buf);
                in.close();
                if (read > 0) {
                    JSONArray arr = new JSONArray(new String(buf, 0, read, StandardCharsets.UTF_8));
                    for (int i = 0; i < arr.length(); i++) {
                        Object el = arr.get(i);
                        if (el instanceof JSONObject) {
                            JSONObject o = (JSONObject) el;
                            String nm = o.optString("n", "");
                            if (nm.isEmpty()) continue;
                            cache.add(nm);
                            String ic = o.optString("i", "");
                            if (!ic.isEmpty()) iconCache.put(nm, ic);
                        } else {
                            cache.add(String.valueOf(el));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        } else {
            cache.add(Lang.t("工作")); iconCache.put(Lang.t("工作"), "💼");
            cache.add(Lang.t("生活")); iconCache.put(Lang.t("生活"), "🏠");
            cache.add(Lang.t("学习")); iconCache.put(Lang.t("学习"), "📚");
            cache.add(Lang.t("灵感")); iconCache.put(Lang.t("灵感"), "💡");
            save(ctx);
        }
        // 老版本升级：四个预置分类若还没设过图标，补上默认图标
        String[][] presets = {{Lang.t("工作"), "💼"}, {Lang.t("生活"), "🏠"}, {Lang.t("学习"), "📚"}, {Lang.t("灵感"), "💡"}};
        boolean migrated = false;
        for (String[] pr : presets) {
            if (cache.contains(pr[0]) && !iconCache.containsKey(pr[0])) {
                iconCache.put(pr[0], pr[1]);
                migrated = true;
            }
        }
        if (migrated) save(ctx);
        return cache;
    }

    public static synchronized void save(Context ctx) {
        if (cache == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (String s : cache) {
                JSONObject o = new JSONObject();
                o.put("n", s);
                String ic = iconCache == null ? "" : iconCache.getOrDefault(s, "");
                if (!ic.isEmpty()) o.put("i", ic);
                arr.put(o);
            }
            FileOutputStream out = new FileOutputStream(new File(ctx.getFilesDir(), FILE_NAME));
            out.write(arr.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
        } catch (Exception ignored) {
        }
    }

    public static boolean add(Context ctx, String name) {
        name = name.trim();
        if (name.isEmpty()) return false;
        List<String> list = load(ctx);
        if (list.contains(name)) return false;
        list.add(name);
        save(ctx);
        return true;
    }

    /** 重命名分类，并同步更新所有笔记的分类字段。 */
    public static void rename(Context ctx, String oldName, String newName) {
        newName = newName.trim();
        if (newName.isEmpty() || newName.equals(oldName)) return;
        List<String> list = load(ctx);
        int idx = list.indexOf(oldName);
        if (idx < 0 || list.contains(newName)) return;
        list.set(idx, newName);
        if (iconCache != null && iconCache.containsKey(oldName)) iconCache.put(newName, iconCache.remove(oldName));
        save(ctx);
        for (Note n : NoteStore.load(ctx)) {
            if (oldName.equals(n.category)) n.category = newName;
        }
        NoteStore.save(ctx);
    }

    /** 删除分类，该分类下的笔记变为未分类。 */
    public static void delete(Context ctx, String name) {
        load(ctx).remove(name);
        if (iconCache != null) iconCache.remove(name);
        save(ctx);
        for (Note n : NoteStore.load(ctx)) {
            if (name.equals(n.category)) n.category = "";
        }
        NoteStore.save(ctx);
    }

    /** 把分类移动到目标分类之前（长按拖动排序用）。 */
    public static void moveBefore(Context ctx, String moving, String target) {
        List<String> list = load(ctx);
        int from = list.indexOf(moving);
        if (from < 0) return;
        list.remove(from);
        int to = list.indexOf(target);
        if (to < 0) to = list.size();
        list.add(to, moving);
        save(ctx);
    }

    /** 分类前置图标：未设置时默认 📁，可设为任意 emoji / 文字，或明确无图标。 */
    public static String iconOf(Context ctx, String name) {
        load(ctx);
        String ic = iconCache == null ? null : iconCache.get(name);
        if (ic == null || ic.isEmpty()) return "📁";
        if (ICON_NONE.equals(ic)) return "";
        return ic;
    }

    public static String label(Context ctx, String name) {
        String ic = iconOf(ctx, name);
        return ic.isEmpty() ? name : ic + " " + name;
    }

    public static void setIcon(Context ctx, String name, String icon) {
        load(ctx);
        if (iconCache == null) iconCache = new java.util.HashMap<>();
        if (icon == null) iconCache.remove(name);
        else iconCache.put(name, icon);
        save(ctx);
    }

    private static final String[] ICON_PRESETS = {
            "📁", "💼", "🏠", "📚", "💡", "❤️", "⭐", "🔥",
            "📌", "📝", "🎨", "🎵", "🏃", "💰", "🌱", "✈️",
            "🍔", "🐱", "🌙", "☀️", "🎮", "⚽", "🚗", "💻",
            "📷", "🎁", "🔬", "⚖️", "🏥", "🛒", "🐶", "🌸"};

    /** 图标选择器：预置 emoji 网格 + 无图标 / 默认 / 自定义输入。关闭时回调 onDone（仅一次）。 */
    public static void showIconPicker(Activity act, String category, Runnable onDone) {
        final boolean[] fired = {false};
        Runnable done = () -> { if (!fired[0]) { fired[0] = true; if (onDone != null) onDone.run(); } };
        android.widget.LinearLayout rootBox = new android.widget.LinearLayout(act);
        rootBox.setOrientation(android.widget.LinearLayout.VERTICAL);
        rootBox.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 8));
        android.widget.ScrollView scroll = new android.widget.ScrollView(act);
        android.widget.LinearLayout grid = new android.widget.LinearLayout(act);
        grid.setOrientation(android.widget.LinearLayout.VERTICAL);
        scroll.addView(grid);
        rootBox.addView(scroll, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(act, 300)));
        final AlertDialog[] dlgHolder = new AlertDialog[1];
        for (int r = 0; r < ICON_PRESETS.length; r += 6) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(act);
            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
            for (int c = 0; c < 6 && r + c < ICON_PRESETS.length; c++) {
                final String ic = ICON_PRESETS[r + c];
                android.widget.TextView cell = Ui.text(act, ic, 26, 0xFF000000, false);
                cell.setGravity(android.view.Gravity.CENTER);
                cell.setPadding(0, Ui.dp(act, 10), 0, Ui.dp(act, 10));
                cell.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                cell.setOnClickListener(v -> {
                    setIcon(act, category, ic);
                    if (dlgHolder[0] != null) dlgHolder[0].dismiss();
                });
                row.addView(cell);
            }
            grid.addView(row);
        }
        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle(Lang.f("给「%1$s」选个图标", category))
                .setView(rootBox)
                .setNeutralButton(Lang.t("自定义…"), (d, w) -> {
                    EditText et = new EditText(act);
                    et.setHint(Lang.t("输入任意 emoji 或 1~2 个字"));
                    new AlertDialog.Builder(act)
                            .setTitle(Lang.t("自定义图标"))
                            .setView(et)
                            .setNegativeButton(Lang.t("取消"), null)
                            .setPositiveButton(Lang.t("确定"), (d2, w2) -> {
                                String t = et.getText().toString().trim();
                                if (!t.isEmpty()) {
                                    if (t.length() > 4) t = t.substring(0, 4);
                                    setIcon(act, category, t);
                                }
                            })
                            .setOnDismissListener(x -> done.run())
                            .show();
                })
                .setPositiveButton(Lang.t("无图标"), (d, w) -> setIcon(act, category, ICON_NONE))
                .setNegativeButton(Lang.t("用默认 📁"), (d, w) -> setIcon(act, category, null))
                .setOnDismissListener(x -> done.run())
                .show();
        dlgHolder[0] = dlg;
        Ui.styleDialog(act, dlg);
    }

    public interface PickCallback { void onPick(String category); }

    /** 给笔记选择分类：未分类 / 各分类 / 新建。 */
    public static void showPickDialog(Activity act, Note note, PickCallback cb) {
        List<String> list = load(act);
        List<String> options = new ArrayList<>();
        options.add(Lang.t("未分类"));
        for (String nm : list) options.add(label(act, nm));
        List<String> names = new ArrayList<>();
        names.add("");
        names.addAll(list);
        int checked = note.category == null || note.category.isEmpty() ? 0 : names.indexOf(note.category);
        if (checked < 0) checked = 0;
        new AlertDialog.Builder(act)
                .setTitle(Lang.t("选择分类"))
                .setSingleChoiceItems(options.toArray(new String[0]), checked, (d, which) -> {
                    cb.onPick(names.get(which));
                    d.dismiss();
                })
                .setNeutralButton(Lang.t("新建分类"), (d, w) -> promptNew(act, name -> {
                    String nm = name.trim();
                    if (add(act, nm)) showIconPicker(act, nm, () -> cb.onPick(nm));
                    else Toast.makeText(act, Lang.t("分类已存在或名称为空"), Toast.LENGTH_SHORT).show();
                }))
                .setNegativeButton(Lang.t("取消"), null)
                .show();
    }

    /** 管理分类：点条目可改名或删除，另可新建。改动后回调 onChanged。 */
    public static void showManageDialog(Activity act, Runnable onChanged) {
        List<String> list = new ArrayList<>(load(act));
        if (list.isEmpty()) {
            new AlertDialog.Builder(act)
                    .setTitle(Lang.t("管理分类"))
                    .setMessage(Lang.t("还没有任何分类。"))
                    .setNeutralButton(Lang.t("新建分类"), (d, w) -> promptNew(act, name -> {
                        if (add(act, name)) onChanged.run();
                    }))
                    .setNegativeButton(Lang.t("关闭"), null)
                    .show();
            return;
        }
        new AlertDialog.Builder(act)
                .setTitle(Lang.t("管理分类（点条目管理）"))
                .setItems(java.util.stream.IntStream.range(0, list.size())
                        .mapToObj(i -> label(act, list.get(i))).toArray(String[]::new), (d, which) -> {
                    String name = list.get(which);
                    new AlertDialog.Builder(act)
                            .setTitle(name)
                            .setItems(new String[]{Lang.t("编辑（图标和名称）"), Lang.t("删除此分类")}, (d2, w2) -> {
                                if (w2 == 0) {
                                    showEditDialog(act, name, onChanged);
                                } else {
                                    new AlertDialog.Builder(act)
                                            .setTitle(Lang.f("删除分类「%1$s」？", name))
                                            .setMessage(Lang.t("该分类下的笔记会变为未分类，笔记本身不会被删除。"))
                                            .setNegativeButton(Lang.t("取消"), null)
                                            .setPositiveButton(Lang.t("删除"), (d3, w3) -> {
                                                delete(act, name);
                                                onChanged.run();
                                            }).show();
                                }
                            })
                            .setNegativeButton(Lang.t("取消"), null)
                            .show();
                })
                .setNeutralButton(Lang.t("新建分类"), (d, w) -> promptNew(act, name -> {
                    String nm = name.trim();
                    if (add(act, nm)) showIconPicker(act, nm, onChanged);
                    else Toast.makeText(act, Lang.t("分类已存在或名称为空"), Toast.LENGTH_SHORT).show();
                }))
                .setNegativeButton(Lang.t("关闭"), null)
                .show();
    }

    /** 编辑分类：图标和名称在同一个弹窗里改完。图标点左侧大图标更换，改名会自动同步笔记与图标。 */
    private static void showEditDialog(Activity act, String name, Runnable onChanged) {
        Palette pal = Palette.of(act);
        android.widget.LinearLayout box = new android.widget.LinearLayout(act);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(act, 20), Ui.dp(act, 10), Ui.dp(act, 20), Ui.dp(act, 4));
        android.widget.LinearLayout row = new android.widget.LinearLayout(act);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        final android.widget.TextView iconView = Ui.text(act, iconOf(act, name), 30, pal.textPrimary, false);
        iconView.setGravity(android.view.Gravity.CENTER);
        iconView.setBackground(Ui.round(pal.surfaceVariant, Ui.dp(act, 16)));
        iconView.setLayoutParams(new android.widget.LinearLayout.LayoutParams(Ui.dp(act, 58), Ui.dp(act, 58)));
        row.addView(iconView);
        EditText et = new EditText(act);
        et.setText(name);
        et.setSelection(name.length());
        et.setTextSize(16);
        et.setHint(Lang.t("分类名称"));
        android.widget.LinearLayout.LayoutParams etLp = new android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        etLp.setMargins(Ui.dp(act, 14), 0, 0, 0);
        et.setLayoutParams(etLp);
        row.addView(et);
        box.addView(row);
        android.widget.TextView hint = Ui.text(act, Lang.t("点左侧图标可更换图标（预置 / 自定义 / 无图标）"), 12, pal.textSecondary, false);
        hint.setPadding(0, Ui.dp(act, 8), 0, 0);
        box.addView(hint);
        iconView.setOnClickListener(v -> showIconPicker(act, name, () -> {
            iconView.setText(iconOf(act, name));
            onChanged.run();
        }));
        new AlertDialog.Builder(act)
                .setTitle(Lang.t("编辑分类"))
                .setView(box)
                .setNegativeButton(Lang.t("取消"), null)
                .setPositiveButton(Lang.t("保存"), (d, w) -> {
                    String nn = et.getText().toString().trim();
                    if (nn.isEmpty()) {
                        Toast.makeText(act, Lang.t("名称不能为空"), Toast.LENGTH_SHORT).show();
                    } else if (!nn.equals(name)) {
                        if (load(act).contains(nn)) {
                            Toast.makeText(act, Lang.t("已有同名分类，未改名（图标修改已保存）"), Toast.LENGTH_LONG).show();
                        } else {
                            rename(act, name, nn);
                        }
                    }
                    onChanged.run();
                })
                .show();
    }

    private interface NameCallback { void onName(String name); }

    private static void promptNew(Activity act, NameCallback cb) {
        EditText et = new EditText(act);
        et.setHint(Lang.t("分类名称，如：旅行"));
        new AlertDialog.Builder(act)
                .setTitle(Lang.t("新建分类"))
                .setView(et)
                .setNegativeButton(Lang.t("取消"), null)
                .setPositiveButton(Lang.t("创建"), (d, w) -> cb.onName(et.getText().toString()))
                .show();
    }

    private static void promptRename(Activity act, String oldName, NameCallback cb) {
        EditText et = new EditText(act);
        et.setText(oldName);
        et.setSelection(oldName.length());
        new AlertDialog.Builder(act)
                .setTitle(Lang.t("改名"))
                .setView(et)
                .setNegativeButton(Lang.t("取消"), null)
                .setPositiveButton(Lang.t("确定"), (d, w) -> cb.onName(et.getText().toString()))
                .show();
    }
}
