package com.muse.monetnotes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 系统分享接收：其他应用通过 ACTION_SEND / ACTION_SEND_MULTIPLE 分享文字、图片或文件时，
 * 弹主题化确认框（预览 + 选分类），可直接保存为新笔记或保存后打开编辑。
 * 附件走 Attachments.copyIn 与编辑页选文件完全相同的导入路径。
 */
public class ShareActivity extends Activity {
    private String sharedTitle = "";
    private String sharedText = "";
    private final List<Uri> sharedUris = new ArrayList<>();
    private String category = "";
    private boolean handled = false; // 已进入保存流程后，确认框关闭不再 finish

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Lang.init(this);
        NoteStore.load(this);
        Categories.load(this);
        parse(getIntent());
        if (sharedText.isEmpty() && sharedTitle.isEmpty() && sharedUris.isEmpty()) {
            Toast.makeText(this, Lang.t("没有可保存的内容"), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        showConfirm();
    }

    private void parse(Intent i) {
        if (i == null) return;
        String subject = i.getStringExtra(Intent.EXTRA_SUBJECT);
        if (subject == null) subject = i.getStringExtra(Intent.EXTRA_TITLE);
        CharSequence text = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (subject != null) sharedTitle = subject.trim().replaceAll("\\s*\n\\s*", " ");
        if (sharedTitle.length() > 100) sharedTitle = sharedTitle.substring(0, 100);
        if (text != null) sharedText = text.toString().trim();
        if (!sharedTitle.isEmpty() && sharedText.equals(sharedTitle)) sharedText = "";
        LinkedHashSet<Uri> set = new LinkedHashSet<>();
        try {
            Uri single = i.getParcelableExtra(Intent.EXTRA_STREAM);
            if (single != null) set.add(single);
        } catch (Exception ignored) {
        }
        try {
            ArrayList<Uri> multi = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (multi != null) for (Uri u : multi) if (u != null) set.add(u);
        } catch (Exception ignored) {
        }
        ClipData cd = i.getClipData();
        if (cd != null) {
            for (int k = 0; k < cd.getItemCount(); k++) {
                Uri u = cd.getItemAt(k).getUri();
                if (u != null) set.add(u);
            }
        }
        sharedUris.addAll(set);
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (ni >= 0) {
                    String n = c.getString(ni);
                    if (n != null && !n.isEmpty()) return n;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String seg = uri.getLastPathSegment();
        return seg != null ? seg : Lang.t("附件");
    }

    private String previewText() {
        StringBuilder sb = new StringBuilder();
        if (!sharedTitle.isEmpty()) sb.append(sharedTitle);
        if (!sharedText.isEmpty()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(sharedText.length() > 140 ? sharedText.substring(0, 140) + "…" : sharedText);
        }
        if (!sharedUris.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            int show = Math.min(sharedUris.size(), 3);
            for (int k = 0; k < show; k++) {
                if (k > 0) sb.append("\n");
                sb.append(Attachments.iconFor(getContentResolver().getType(sharedUris.get(k))))
                        .append(" ").append(displayName(sharedUris.get(k)));
            }
            if (sharedUris.size() > show) sb.append("\n").append(Lang.f("…等 %1$d 个文件", sharedUris.size()));
        }
        return sb.toString();
    }

    private void showConfirm() {
        final List<String> cats = Categories.load(this);
        String[] labels = new String[cats.size() + 1];
        labels[0] = Lang.t("未分类");
        for (int i = 0; i < cats.size(); i++) labels[i + 1] = Categories.label(this, cats.get(i));
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(Lang.t("保存到莫奈备忘录"))
                .setMessage(previewText())
                .setSingleChoiceItems(labels, 0, (d, which) -> category = which == 0 ? "" : cats.get(which - 1))
                .setPositiveButton(Lang.t("保存"), (d, w) -> doSave(false))
                .setNeutralButton(Lang.t("编辑"), (d, w) -> doSave(true))
                .setNegativeButton(Lang.t("取消"), (d, w) -> finish())
                .create();
        dlg.setOnDismissListener(d -> {
            if (!handled && !isFinishing()) finish();
        });
        dlg.show();
        Ui.styleDialog(this, dlg);
    }

    private void doSave(final boolean openEditor) {
        handled = true;
        Palette pal = Palette.of(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 20);
        box.setPadding(pad, Ui.dp(this, 16), pad, Ui.dp(this, 8));
        ProgressBar pb = new ProgressBar(this);
        box.addView(pb);
        TextView tv = Ui.text(this, Lang.t("正在保存…"), 15, pal.textPrimary, false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = Ui.dp(this, 14);
        box.addView(tv, tlp);
        final AlertDialog prog = new AlertDialog.Builder(this).setView(box).create();
        prog.setCancelable(false);
        prog.setCanceledOnTouchOutside(false);
        prog.show();
        Ui.styleDialog(this, prog);
        // 等进度框先渲染出来，再在主线程做导入（与编辑页选附件相同的线程模型）
        prog.getWindow().getDecorView().post(() -> {
            Note note = new Note();
            note.title = sharedTitle;
            note.content = sharedText;
            note.category = category;
            for (Uri u : sharedUris) {
                Note.Attachment a = Attachments.copyIn(this, u);
                if (a != null) note.attachments.add(a);
            }
            prog.dismiss();
            if (note.title.isEmpty() && note.content.isEmpty() && note.attachments.isEmpty()) {
                Toast.makeText(this, Lang.t("没有可保存的内容"), Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            NoteStore.load(this).add(0, note);
            NoteStore.save(this);
            Toast.makeText(this, Lang.t("已保存到莫奈备忘录"), Toast.LENGTH_SHORT).show();
            if (openEditor) {
                Intent ei = new Intent(this, EditorActivity.class);
                ei.putExtra("note_id", note.id);
                startActivity(ei);
            }
            finish();
        });
    }
}
