package com.muse.monetnotes;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 列表式标签编辑器：逐条增减，底部输入添加，附其他笔记用过的标签可点选。 */
public class TagEditor {

    public interface SaveCallback { void onSave(List<String> tags); }

    public static void show(Activity act, Palette pal, List<String> initial, SaveCallback cb) {
        final List<String> working = new ArrayList<>(initial);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), Ui.dp(act, 4));

        ScrollView scroll = new ScrollView(act);
        final LinearLayout listBox = new LinearLayout(act);
        listBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listBox);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 其它笔记用过的标签（点选添加）
        final TextView sugHead = Ui.text(act, Lang.t("其它笔记的标签，点一下添加："), 12, pal.textSecondary, false);
        sugHead.setPadding(0, Ui.dp(act, 12), 0, Ui.dp(act, 4));
        final LinearLayout sugBox = new LinearLayout(act);
        sugBox.setOrientation(LinearLayout.VERTICAL);

        final Runnable[] renderRef = new Runnable[1];
        renderRef[0] = () -> {
            listBox.removeAllViews();
            if (working.isEmpty()) {
                TextView empty = Ui.text(act, Lang.t("还没有标签，用下面的输入框添加"), 13, pal.textSecondary, false);
                empty.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 8));
                listBox.addView(empty);
            }
            for (String t : new ArrayList<>(working)) {
                LinearLayout row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView name = Ui.text(act, "# " + t, 15, pal.textPrimary, false);
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                TextView del = Ui.text(act, "✕", 15, 0xFFB3261E, true);
                del.setGravity(Gravity.CENTER);
                del.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(act, 40), Ui.dp(act, 40)));
                del.setOnClickListener(v -> {
                    working.remove(t);
                    renderRef[0].run();
                });
                row.addView(del);
                listBox.addView(row);
            }
            // 建议标签
            Set<String> all = new LinkedHashSet<>();
            for (Note n : NoteStore.load(act)) all.addAll(n.tags);
            all.removeAll(working);
            sugBox.removeAllViews();
            sugHead.setVisibility(all.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
            LinearLayout chipRow = null;
            int inRow = 0;
            for (String t : all) {
                if (chipRow == null || inRow >= 3) {
                    chipRow = new LinearLayout(act);
                    chipRow.setOrientation(LinearLayout.HORIZONTAL);
                    chipRow.setPadding(0, Ui.dp(act, 3), 0, Ui.dp(act, 3));
                    sugBox.addView(chipRow);
                    inRow = 0;
                }
                TextView chip = Ui.text(act, "# " + t + " ＋", 13, pal.textPrimary, false);
                chip.setBackground(Ui.round(pal.surfaceVariant, Ui.dp(act, 14)));
                chip.setPadding(Ui.dp(act, 10), Ui.dp(act, 5), Ui.dp(act, 10), Ui.dp(act, 5));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.setMargins(0, 0, Ui.dp(act, 8), 0);
                chip.setLayoutParams(clp);
                final String tag = t;
                chip.setOnClickListener(v -> {
                    working.add(tag);
                    renderRef[0].run();
                });
                chipRow.addView(chip);
                inRow++;
            }
        };
        renderRef[0].run();

        // 添加一行：输入框 + 添加按钮
        LinearLayout addRow = new LinearLayout(act);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        addRow.setPadding(0, Ui.dp(act, 12), 0, 0);
        final EditText input = new EditText(act);
        input.setHint(Lang.t("输入新标签"));
        input.setSingleLine(true);
        input.setTextSize(15);
        addRow.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView addBtn = Ui.text(act, Lang.t("添加"), 14, pal.onPrimary, true);
        addBtn.setGravity(Gravity.CENTER);
        addBtn.setBackground(Ui.round(pal.primary, Ui.dp(act, 14)));
        addBtn.setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), Ui.dp(act, 8));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMargins(Ui.dp(act, 10), 0, 0, 0);
        addBtn.setLayoutParams(alp);
        addBtn.setOnClickListener(v -> {
            String t = input.getText().toString().trim();
            if (t.isEmpty()) return;
            if (working.contains(t)) {
                Toast.makeText(act, Lang.t("标签已存在"), Toast.LENGTH_SHORT).show();
                return;
            }
            working.add(t);
            input.setText("");
            renderRef[0].run();
        });
        addRow.addView(addBtn);
        root.addView(addRow);
        root.addView(sugHead);
        root.addView(sugBox);

        AlertDialog dlg = new AlertDialog.Builder(act)
                .setTitle(Lang.t("编辑标签"))
                .setView(root)
                .setNegativeButton(Lang.t("取消"), null)
                .setPositiveButton(Lang.t("保存"), (d, w) -> cb.onSave(new ArrayList<>(working)))
                .show();
        Ui.styleDialog(act, dlg);
    }
}
