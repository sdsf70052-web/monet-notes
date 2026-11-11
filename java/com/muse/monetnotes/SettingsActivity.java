package com.muse.monetnotes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 设置页：外观主题、背景与质感、应用图标、通用、数据与关于。 */
public class SettingsActivity extends Activity {
    private static final int REQ_BG_PICK = 701;
    private static final int REQ_EXPORT = 702;
    private static final int REQ_IMPORT = 703;

    private Palette pal;
    private FrameLayout bgFrame;
    private View bgInner;
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pal = Palette.of(this);
        sp = getSharedPreferences(Palette.PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(pal.background);
        getWindow().setNavigationBarColor(pal.background);
        buildUi();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    // ---------- 基础控件 ----------

    private TextView sectionTitle(String text) {
        TextView t = Ui.text(this, text, 13, pal.primary, true);
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 22), Ui.dp(this, 20), Ui.dp(this, 6));
        return t;
    }

    private LinearLayout cardBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Ui.round(pal.surface, Ui.dp(this, 20)));
        box.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        box.setLayoutParams(lp);
        return box;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        return r;
    }

    private TextView rowLabel(String text) {
        TextView t = Ui.text(this, text, 15, pal.textPrimary, false);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return t;
    }

    private Switch switchFor(String key, boolean def, Runnable after) {
        Switch sw = new Switch(this);
        // 开关的滑块/轨道颜色也显式按 Palette 设置：跟单选按钮同一教训，
        // 用主题默认色时，系统深色 + 应用强制浅色会让控件与背景融在一起
        sw.setThumbTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{pal.primary, Palette.blend(pal.textPrimary, pal.background, 0.45f)}));
        sw.setTrackTintList(new ColorStateList(
                new int[][]{{android.R.attr.state_checked}, {}},
                new int[]{Palette.withAlpha(pal.primary, 0x66), Palette.withAlpha(pal.outline, 0x55)}));
        sw.setChecked(sp.getBoolean(key, def));
        sw.setOnCheckedChangeListener((v, checked) -> {
            sp.edit().putBoolean(key, checked).apply();
            if (after != null) after.run();
        });
        return sw;
    }

    /**
     * 构建单选组：每个按钮用全局唯一 ID，并只在值真正变化时回调。
     * （教训：多组共用 0..n 小 ID 时，recreate 的视图状态恢复会串组触发监听，
     *  保存错误的值并再次 recreate，形成无限重建、屏幕疯狂闪烁。）
     */
    private void buildRadioGroup(RadioGroup g, String[] labels, int checkedIdx,
                                 java.util.function.IntConsumer onPick) {
        final int[] ids = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            // 文字与圆圈颜色必须显式按 Palette 设置：RadioButton 的默认色来自系统主题，
            // 当系统处于深色而应用被设为浅色时，白字落在近白卡片上会完全看不见（v1.41 修复）
            rb.setTextColor(pal.textPrimary);
            rb.setButtonTintList(new ColorStateList(
                    new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{pal.primary, pal.outline}));
            ids[i] = View.generateViewId();
            rb.setId(ids[i]);
            g.addView(rb);
        }
        if (checkedIdx >= 0 && checkedIdx < ids.length) g.check(ids[checkedIdx]);
        g.setOnCheckedChangeListener((grp, id) -> {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == id) { onPick.accept(i); return; }
            }
        });
    }

    private TextView smallBtn(String label, boolean primary, View.OnClickListener cb) {
        TextView b = Ui.text(this, label, 13, primary ? pal.onPrimary : pal.textPrimary, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.round(primary ? pal.primary : pal.surfaceVariant, Ui.dp(this, 14)));
        b.setPadding(Ui.dp(this, 14), Ui.dp(this, 7), Ui.dp(this, 14), Ui.dp(this, 7));
        b.setOnClickListener(cb);
        return b;
    }

    // ---------- 页面 ----------

    @Override protected void onResume() {
        super.onResume();
        if (bgFrame != null && pal != null) BgHelper.apply(this, bgFrame, pal, bgInner, true);
    }

    private void buildUi() {
        Lang.init(this);
        FrameLayout frame = new FrameLayout(this);
        bgFrame = frame;
        frame.setBackgroundColor(pal.background);
        LinearLayout root = new LinearLayout(this);
        bgInner = root;
        root.setOrientation(LinearLayout.VERTICAL);
        frame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 6));
        TextView back = Ui.text(this, "‹", 30, pal.textPrimary, false);
        back.setGravity(Gravity.CENTER);
        back.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back);
        TextView title = Ui.text(this, Lang.t("设置"), 22, pal.textPrimary, true);
        bar.addView(title);
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, 0, 0, Ui.dp(this, 40));
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        buildAppearance(col);
        buildBackground(col);
        buildIcons(col);
        buildGeneral(col);
        buildData(col);
        buildAbout(col);

        setContentView(frame);
        BgHelper.apply(this, frame, pal, root, true);
    }

    private void buildAppearance(LinearLayout col) {
        col.addView(sectionTitle(Lang.t("外观")));
        LinearLayout box = cardBox();
        LinearLayout rLang = row();
        rLang.addView(rowLabel(Lang.t("语言")));
        RadioGroup langGroup = new RadioGroup(this);
        langGroup.setOrientation(RadioGroup.HORIZONTAL);
        buildRadioGroup(langGroup, new String[]{Lang.t("跟随系统"), "简体中文", "繁體中文", "English"},
                sp.getInt(Lang.KEY_LANG, 0), idx -> {
                    if (idx == sp.getInt(Lang.KEY_LANG, 0)) return;
                    sp.edit().putInt(Lang.KEY_LANG, idx).apply();
                    Lang.init(this);
                    recreate();
                });
        rLang.addView(langGroup);
        box.addView(rLang);
        LinearLayout r1 = row();
        r1.addView(rowLabel(Lang.t("外观模式")));
        RadioGroup modes = new RadioGroup(this);
        modes.setOrientation(RadioGroup.HORIZONTAL);
        buildRadioGroup(modes, new String[]{Lang.t("跟随系统"), Lang.t("浅色"), Lang.t("深色")},
                sp.getInt(Palette.KEY_MODE, 0), idx -> {
                    if (idx == sp.getInt(Palette.KEY_MODE, 0)) return;
                    sp.edit().putInt(Palette.KEY_MODE, idx).apply();
                    recreate();
                });
        r1.addView(modes);
        box.addView(r1);

        LinearLayout r2 = row();
        r2.addView(rowLabel(Lang.t("莫奈动态取色（跟随壁纸）")));
        r2.addView(switchFor(Palette.KEY_DYNAMIC, true, this::recreate));
        box.addView(r2);

        LinearLayout r3 = row();
        r3.addView(rowLabel(Lang.t("种子色")));
        RadioGroup seeds = new RadioGroup(this);
        seeds.setOrientation(RadioGroup.HORIZONTAL);
        buildRadioGroup(seeds, new String[]{Lang.t("紫"), Lang.t("蓝"), Lang.t("绿"), Lang.t("橙"), Lang.t("粉")},
                sp.getInt(Palette.KEY_SEED, 0), idx -> {
                    if (idx == sp.getInt(Palette.KEY_SEED, 0)) return;
                    sp.edit().putInt(Palette.KEY_SEED, idx).apply();
                    recreate();
                });
        r3.addView(seeds);
        box.addView(r3);
        col.addView(box);
    }

    private void buildBackground(LinearLayout col) {
        col.addView(sectionTitle(Lang.t("背景与质感")));
        LinearLayout box = cardBox();

        LinearLayout r1 = row();
        r1.addView(rowLabel(BgHelper.hasBg(this) ? Lang.t("全屏背景：已设置") : Lang.t("全屏背景：未设置")));
        r1.addView(smallBtn(Lang.t("选择图片"), true, v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("image/*");
            startActivityForResult(pick, REQ_BG_PICK);
        }));
        TextView clear = smallBtn(Lang.t("清除"), false, v -> {
            BgHelper.clearBg(this);
            recreate();
            Toast.makeText(this, Lang.t("背景图已清除"), Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        clear.setLayoutParams(clp);
        r1.addView(clear);
        box.addView(r1);

        LinearLayout r2 = row();
        r2.addView(rowLabel(Lang.t("背景生效区域")));
        RadioGroup scope = new RadioGroup(this);
        scope.setOrientation(RadioGroup.HORIZONTAL);
        buildRadioGroup(scope, new String[]{Lang.t("全部"), Lang.t("仅主页"), Lang.t("仅编辑页")},
                sp.getInt(Palette.KEY_BG_SCOPE, 0), idx -> {
                    if (idx == sp.getInt(Palette.KEY_BG_SCOPE, 0)) return;
                    sp.edit().putInt(Palette.KEY_BG_SCOPE, idx).apply();
                    recreate();
                });
        r2.addView(scope);
        box.addView(r2);

        LinearLayout r3 = row();
        r3.addView(rowLabel(Lang.t("动态背景")));
        HorizontalScrollView fxScroll = new HorizontalScrollView(this);
        fxScroll.setHorizontalScrollBarEnabled(false);
        RadioGroup fx = new RadioGroup(this);
        fx.setOrientation(RadioGroup.HORIZONTAL);
        buildRadioGroup(fx, new String[]{Lang.t("无"), Lang.t("极光"), Lang.t("气泡"), Lang.t("星空"), Lang.t("波浪"), Lang.t("流体")},
                sp.getInt(Palette.KEY_BG_FX, 0), idx -> {
                    if (idx == sp.getInt(Palette.KEY_BG_FX, 0)) return;
                    sp.edit().putInt(Palette.KEY_BG_FX, idx).apply();
                    recreate();
                });
        fxScroll.addView(fx);
        r3.addView(fxScroll);
        box.addView(r3);

        LinearLayout r4 = row();
        r4.addView(rowLabel(Lang.t("高斯模糊（背景图）")));
        r4.addView(switchFor(Palette.KEY_BLUR, false, this::recreate));
        box.addView(r4);

        LinearLayout r5 = row();
        r5.addView(rowLabel(Lang.t("液态玻璃")));
        r5.addView(switchFor(Palette.KEY_GLASS, false, this::recreate));
        box.addView(r5);

        LinearLayout r6 = row();
        r6.addView(rowLabel(Lang.t("效果强度")));
        // 自定义参数区（先建好供单选回调控制展开/收起；加入布局在 r6 之后）
        LinearLayout fxCustomBox = new LinearLayout(this);
        fxCustomBox.setOrientation(LinearLayout.VERTICAL);
        fxCustomBox.addView(fxNumRow(Lang.t("采样分辨率"), Palette.KEY_FX_C_SCALE, 100, 10, 100, "%"));
        fxCustomBox.addView(fxNumRow(Lang.t("拷贝帧率上限"), Palette.KEY_FX_C_COPY, 0, 0, 60, "fps"));
        fxCustomBox.addView(fxNumRow(Lang.t("动态背景帧率"), Palette.KEY_FX_C_ANIM, 30, 5, 60, "fps"));
        fxCustomBox.setVisibility(sp.getInt(Palette.KEY_FX_LEVEL, 0) == 3 ? View.VISIBLE : View.GONE);
        HorizontalScrollView lvlScroll = new HorizontalScrollView(this);
        lvlScroll.setHorizontalScrollBarEnabled(false);
        RadioGroup fxLvl = new RadioGroup(this);
        fxLvl.setOrientation(RadioGroup.HORIZONTAL);
        buildRadioGroup(fxLvl, new String[]{Lang.t("极致"), Lang.t("均衡"), Lang.t("流畅"), Lang.t("自定义")},
                sp.getInt(Palette.KEY_FX_LEVEL, 0), idx -> {
                    if (idx == sp.getInt(Palette.KEY_FX_LEVEL, 0)) return;
                    sp.edit().putInt(Palette.KEY_FX_LEVEL, idx).apply();
                    fxCustomBox.setVisibility(idx == 3 ? View.VISIBLE : View.GONE);
                    BgHelper.markSceneDirty(); // 正在显示的浮层栏立刻按新档位重拷一帧
                });
        lvlScroll.addView(fxLvl);
        r6.addView(lvlScroll);
        box.addView(r6);
        box.addView(fxCustomBox);
        col.addView(box);
    }

    /** 效果强度「自定义」的数值行：数字输入框 + 单位；失焦/确认时钳制范围并保存、即时生效。 */
    private LinearLayout fxNumRow(String label, final String key, final int def,
                                  final int min, final int max, String unit) {
        LinearLayout r = row();
        r.addView(rowLabel(label));
        final android.widget.EditText et = new android.widget.EditText(this);
        et.setText(String.valueOf(sp.getInt(key, def)));
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setSingleLine(true);
        et.setGravity(android.view.Gravity.CENTER);
        et.setTextColor(pal.textPrimary);
        et.setTextSize(14);
        et.setBackground(Ui.roundStroke(pal.surfaceVariant, Ui.dp(this, 10), Math.max(1, Ui.dp(this, 1)), pal.outline));
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(Ui.dp(this, 76), Ui.dp(this, 40));
        ep.leftMargin = Ui.dp(this, 8);
        et.setLayoutParams(ep);
        final Runnable commit = () -> {
            try {
                int v = Integer.parseInt(et.getText().toString().trim());
                v = Math.max(min, Math.min(max, v));
                et.setText(String.valueOf(v));
                if (v != sp.getInt(key, def)) {
                    sp.edit().putInt(key, v).apply();
                    BgHelper.markSceneDirty();
                }
            } catch (Exception e) {
                et.setText(String.valueOf(sp.getInt(key, def)));
            }
        };
        et.setOnFocusChangeListener((v, has) -> { if (!has) commit.run(); });
        et.setOnEditorActionListener((v, id, ev) -> { commit.run(); return false; });
        r.addView(et);
        TextView u = new TextView(this);
        u.setText(unit);
        u.setTextColor(pal.textSecondary);
        u.setTextSize(13);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        up.leftMargin = Ui.dp(this, 6);
        u.setLayoutParams(up);
        r.addView(u);
        return r;
    }

    private void buildIcons(LinearLayout col) {
        col.addView(sectionTitle(Lang.t("应用图标（桌面）")));
        LinearLayout box = cardBox();
        LinearLayout iconRow = new LinearLayout(this);
        iconRow.setOrientation(LinearLayout.HORIZONTAL);
        iconRow.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        final List<ImageView> views = new ArrayList<>();
        for (int i = 0; i < AppIcon.NAMES.length; i++) {
            final int idx = i;
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(this);
            iv.setImageResource(AppIcon.RES[i]);
            iv.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)));
            cell.addView(iv);
            views.add(iv);
            TextView nm = Ui.text(this, AppIcon.NAMES[i], 10, pal.textSecondary, false);
            nm.setGravity(Gravity.CENTER);
            cell.addView(nm);
            cell.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            cell.setOnClickListener(v -> {
                AppIcon.setIcon(this, idx);
                markIconSelection(views);
                Toast.makeText(this, Lang.f("桌面图标已换成「%1$s」，回桌面看看（个别桌面要等一会儿才刷新）", AppIcon.NAMES[idx]), Toast.LENGTH_LONG).show();
            });
            iconRow.addView(cell);
        }
        box.addView(iconRow);
        col.addView(box);
        markIconSelection(views);
    }

    private void markIconSelection(List<ImageView> views) {
        int cur = AppIcon.current(this);
        for (int i = 0; i < views.size(); i++) {
            views.get(i).setBackground(i == cur
                    ? Ui.roundStroke(0x00000000, Ui.dp(this, 14), Ui.dp(this, 3), pal.primary)
                    : null);
        }
    }

    private void buildGeneral(LinearLayout col) {
        col.addView(sectionTitle(Lang.t("通用")));
        LinearLayout box = cardBox();
        LinearLayout r1 = row();
        r1.addView(rowLabel(Lang.t("默认排序")));
        final String[] sortLabels = {Lang.t("按更新时间"), Lang.t("按创建时间"), Lang.t("按标题"), Lang.t("手动排序")};
        TextView sortVal = Ui.text(this, sortLabels[sp.getInt(Palette.KEY_SORT, 0)] + " ›", 14, pal.textSecondary, false);
        r1.addView(sortVal);
        r1.setOnClickListener(v -> {
            AlertDialog dlg = new AlertDialog.Builder(this)
                    .setTitle(Lang.t("默认排序"))
                    .setSingleChoiceItems(sortLabels, sp.getInt(Palette.KEY_SORT, 0), (d, which) -> {
                        sp.edit().putInt(Palette.KEY_SORT, which).apply();
                        sortVal.setText(sortLabels[which] + " ›");
                        d.dismiss();
                    })
                    .setNegativeButton(Lang.t("取消"), null)
                    .show();
            Ui.styleDialog(this, dlg);
        });
        box.addView(r1);

        LinearLayout r2 = row();
        r2.addView(rowLabel(Lang.t("网格视图（关闭为列表）")));
        r2.addView(switchFor(Palette.KEY_GRID, true, null));
        box.addView(r2);

        LinearLayout r3 = row();
        r3.addView(rowLabel(Lang.t("滑动切换特效")));
        final String[] fxLabels = {Lang.t("滑动"), Lang.t("淡入淡出"), Lang.t("缩放"), Lang.t("无特效")};
        TextView fxVal = Ui.text(this, fxLabels[sp.getInt(Palette.KEY_SWIPE_FX, 0)] + " ›", 14, pal.textSecondary, false);
        r3.addView(fxVal);
        r3.setOnClickListener(v -> {
            AlertDialog dlg = new AlertDialog.Builder(this)
                    .setTitle(Lang.t("滑动切换特效"))
                    .setSingleChoiceItems(fxLabels, sp.getInt(Palette.KEY_SWIPE_FX, 0), (d, which) -> {
                        sp.edit().putInt(Palette.KEY_SWIPE_FX, which).apply();
                        fxVal.setText(fxLabels[which] + " ›");
                        d.dismiss();
                    })
                    .setNegativeButton(Lang.t("取消"), null)
                    .show();
            Ui.styleDialog(this, dlg);
        });
        box.addView(r3);
        col.addView(box);
    }

    private void buildData(LinearLayout col) {
        col.addView(sectionTitle(Lang.t("数据")));
        LinearLayout box = cardBox();
        LinearLayout r1 = row();
        r1.addView(rowLabel(Lang.t("导出全部笔记（JSON 备份）")));
        r1.addView(smallBtn(Lang.t("导出"), true, v -> {
            Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            create.addCategory(Intent.CATEGORY_OPENABLE);
            create.setType("application/json");
            create.putExtra(Intent.EXTRA_TITLE, "monet-notes-backup.json");
            startActivityForResult(create, REQ_EXPORT);
        }));
        box.addView(r1);

        LinearLayout rImp = row();
        rImp.addView(rowLabel(Lang.t("导入备份（从 JSON 恢复）")));
        rImp.addView(smallBtn(Lang.t("导入"), false, v -> {
            Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            open.addCategory(Intent.CATEGORY_OPENABLE);
            open.setType("*/*");
            startActivityForResult(open, REQ_IMPORT);
        }));
        box.addView(rImp);

        int trashCount = 0;
        for (Note n : NoteStore.load(this)) if (n.trashed) trashCount++;
        LinearLayout r2 = row();
        r2.addView(rowLabel(Lang.f("清空回收站（%1$d 条）", trashCount)));
        final int tc = trashCount;
        r2.addView(smallBtn(Lang.t("清空"), false, v -> {
            if (tc == 0) {
                Toast.makeText(this, Lang.t("回收站已经是空的"), Toast.LENGTH_SHORT).show();
                return;
            }
            AlertDialog dlg = new AlertDialog.Builder(this)
                    .setTitle(Lang.t("清空回收站？"))
                    .setMessage(Lang.f("将彻底删除 %1$d 条笔记及其附件，无法恢复。", tc))
                    .setNegativeButton(Lang.t("取消"), null)
                    .setPositiveButton(Lang.t("清空"), (d, w) -> {
                        List<Note> all = NoteStore.load(this);
                        List<Note> keep = new ArrayList<>();
                        for (Note n : all) {
                            if (n.trashed) {
                                for (Note.Attachment a : n.attachments) Attachments.fileOf(this, a).delete();
                                ReminderManager.cancel(this, n);
                            } else keep.add(n);
                        }
                        all.clear();
                        all.addAll(keep);
                        NoteStore.save(this);
                        Toast.makeText(this, Lang.t("回收站已清空"), Toast.LENGTH_SHORT).show();
                        recreate();
                    })
                    .show();
            Ui.styleDialog(this, dlg);
        }));
        box.addView(r2);
        col.addView(box);
    }

    private void buildAbout(LinearLayout col) {
        col.addView(sectionTitle(Lang.t("关于")));
        LinearLayout box = cardBox();
        int notes = 0, tags = 0;
        java.util.Set<String> tagSet = new java.util.HashSet<>();
        for (Note n : NoteStore.load(this)) {
            if (!n.trashed) { notes++; tagSet.addAll(n.tags); }
        }
        tags = tagSet.size();
        TextView info = Ui.text(this,
                Lang.f("莫奈备忘录 Monet Notes\n版本 %1$s · %2$d 条笔记 · %3$d 个分类 · %4$d 个标签\n原生 Java 实现，无第三方依赖",
                        "1.41", notes, Categories.load(this).size(), tags),
                14, pal.textSecondary, false);
        info.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
        info.setLineSpacing(0, 1.4f);
        box.addView(info);
        col.addView(box);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_BG_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            if (BgHelper.importBg(this, data.getData())) {
                Toast.makeText(this, Lang.t("背景图已设置"), Toast.LENGTH_SHORT).show();
                recreate();
            } else {
                Toast.makeText(this, Lang.t("这张图片读不了，换一张试试"), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            handleImport(data.getData());
        } else if (requestCode == REQ_EXPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                JSONArray arr = new JSONArray();
                for (Note n : NoteStore.load(this)) arr.put(n.toJson());
                if (out != null) out.write(arr.toString(2).getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this, Lang.t("备份已导出"), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, Lang.f("导出失败：%1$s", e.getMessage()), Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 读取并解析备份文件，确认后按合并或覆盖方式导入。 */
    private void handleImport(Uri uri) {
        final java.util.List<Note> parsed = new java.util.ArrayList<>();
        try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new Exception(Lang.t("无法读取文件"));
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) {
                baos.write(buf, 0, r);
                if (baos.size() > 64 * 1024 * 1024) throw new Exception(Lang.t("文件过大"));
            }
            JSONArray arr = new JSONArray(new String(baos.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) parsed.add(Note.fromJson(arr.getJSONObject(i)));
        } catch (Exception e) {
            Toast.makeText(this, Lang.t("备份文件无效，导入取消"), Toast.LENGTH_SHORT).show();
            return;
        }
        if (parsed.isEmpty()) {
            Toast.makeText(this, Lang.t("备份里没有笔记"), Toast.LENGTH_SHORT).show();
            return;
        }
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(Lang.t("导入备份"))
                .setMessage(Lang.f("备份里有 %1$d 条笔记。\n\n合并：只添加现在没有的笔记；\n覆盖：清空现有笔记后整体恢复。\n\n注意：备份只包含笔记数据，附件文件不在其中；同台设备上原有附件仍可打开。", parsed.size()))
                .setNegativeButton(Lang.t("取消"), null)
                .setNeutralButton(Lang.t("覆盖导入"), (d, w) -> {
                    for (Note old : NoteStore.load(this)) {
                        for (Note.Attachment a : old.attachments) Attachments.fileOf(this, a).delete();
                        ReminderManager.cancel(this, old);
                    }
                    java.util.List<Note> all = NoteStore.load(this);
                    all.clear();
                    all.addAll(parsed);
                    NoteStore.save(this);
                    ReminderManager.syncAll(this);
                    Toast.makeText(this, Lang.f("已恢复 %1$d 条笔记", parsed.size()), Toast.LENGTH_SHORT).show();
                    recreate();
                })
                .setPositiveButton(Lang.t("合并导入"), (d, w) -> {
                    java.util.Set<String> existing = new java.util.HashSet<>();
                    for (Note n : NoteStore.load(this)) existing.add(n.id);
                    int added = 0;
                    for (Note n : parsed) {
                        if (!existing.contains(n.id)) {
                            NoteStore.load(this).add(n);
                            added++;
                        }
                    }
                    NoteStore.save(this);
                    ReminderManager.syncAll(this);
                    Toast.makeText(this, Lang.f("已导入 %1$d 条（跳过 %2$d 条已存在的）", added, parsed.size() - added), Toast.LENGTH_LONG).show();
                    recreate();
                })
                .show();
        Ui.styleDialog(this, dlg);
    }
}
