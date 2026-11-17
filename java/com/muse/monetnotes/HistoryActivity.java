package com.muse.monetnotes;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 历史版本页：列出 NoteHistory 的版本链（新→旧），点开可预览全文并恢复。
 * 恢复前会把当前状态强制存档，恢复本身也会成为新版本——来回切换不会丢任何一版。
 * 加密笔记：未解锁时先过密码门；加密版本只存密文，同盐版本可借会话密钥预览，
 * 旧密码的版本恢复后需用旧密码打开（确认框会提醒）。
 */
public class HistoryActivity extends Activity {
    private Palette pal;
    private Note note;
    private LinearLayout col;
    private boolean inDetail = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Lang.init(this);
        pal = Palette.of(this);
        getWindow().setStatusBarColor(pal.background);
        getWindow().setNavigationBarColor(pal.background);
        if (!pal.dark) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        NoteStore.load(this);
        String id = getIntent().getStringExtra("note_id");
        note = id != null ? NoteStore.find(id) : null;
        if (note == null) {
            finish();
            return;
        }
        if (note.encrypted && !NoteCrypto.hasKey(note.id)) {
            showGate();
            return;
        }
        buildUi();
    }

    // ---------- 加密笔记的密码门 ----------

    private void showGate() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(pal.background);
        root.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0);
        TextView icon = Ui.text(this, "🔒", 56, pal.textPrimary, false);
        icon.setGravity(Gravity.CENTER);
        root.addView(icon);
        TextView msg = Ui.text(this, Lang.t("这篇笔记已加密"), 17, pal.textPrimary, true);
        msg.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(this, 16);
        root.addView(msg, mlp);
        TextView btn = Ui.text(this, Lang.t("输入密码解锁"), 15, pal.primary, true);
        btn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 26);
        btn.setLayoutParams(blp);
        btn.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), Ui.dp(this, 10));
        btn.setOnClickListener(v -> showUnlockDialog());
        root.addView(btn);
        setContentView(root);
    }

    private void showUnlockDialog() {
        final EditText pw = new EditText(this);
        pw.setHint(Lang.t("密码"));
        pw.setSingleLine(true);
        pw.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pw.setTextColor(pal.textPrimary);
        pw.setHintTextColor(pal.textSecondary);
        pw.setTextSize(15);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 8), Ui.dp(this, 22), 0);
        box.addView(pw);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(Lang.t("输入密码解锁"))
                .setView(box)
                .setPositiveButton(Lang.t("解锁"), (d, w) -> {
                    if (NoteCrypto.unlock(note, pw.getText().toString())) {
                        buildUi();
                    } else {
                        Toast.makeText(this, Lang.t("密码错误，请重试"), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(Lang.t("取消"), null)
                .show();
        Ui.styleDialog(this, dlg);
    }

    // ---------- 列表与详情 ----------

    private void buildUi() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(pal.background);
        LinearLayout root = new LinearLayout(this);
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
        bar.addView(Ui.text(this, Lang.t("历史版本"), 22, pal.textPrimary, true));
        root.addView(bar);

        ScrollView sv = new ScrollView(this);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 40));
        sv.addView(col);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(frame);
        BgHelper.apply(this, frame, pal, root, true);
        showList();
    }

    private String fmtTime(long t) {
        return new SimpleDateFormat(Lang.t("yyyy年M月d日 HH:mm"), Locale.US).format(new Date(t));
    }

    /** 把某个版本解析成临时笔记用于预览；加密版本尝试用会话密钥解开，解不开返回 null。 */
    private Note previewNote(NoteHistory.Version v) {
        JSONObject p = v.enc ? NoteCrypto.decryptEntry(note.id, v.data) : v.payload;
        if (p == null) return null;
        Note tmp = new Note();
        tmp.applyPayload(p);
        return tmp;
    }

    private static String firstLine(String s) {
        if (s == null) return "";
        String t = s.trim();
        int nl = t.indexOf('\n');
        return (nl >= 0 ? t.substring(0, nl) : t).trim();
    }

    private void showList() {
        inDetail = false;
        col.removeAllViews();
        List<NoteHistory.Version> vs = NoteHistory.load(this, note.id);
        if (vs.isEmpty()) {
            TextView empty = Ui.text(this, Lang.t("还没有历史版本"), 15, pal.textSecondary, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(this, 60), 0, 0);
            col.addView(empty);
            TextView hint = Ui.text(this, Lang.t("修改后会自动保存历史版本"), 13, pal.textSecondary, false);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(0, Ui.dp(this, 8), 0, 0);
            col.addView(hint);
            return;
        }
        for (NoteHistory.Version v : vs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
            String time = fmtTime(v.t);
            if (NoteHistory.isCurrent(note, v)) time += " · " + Lang.t("当前");
            row.addView(Ui.text(this, time, 12, pal.primary, true));
            Note tmp = previewNote(v);
            if (tmp == null) {
                TextView tt = Ui.text(this, "🔒 " + Lang.t("加密版本"), 15, pal.textPrimary, true);
                tt.setPadding(0, Ui.dp(this, 4), 0, 0);
                row.addView(tt);
            } else {
                String title = !tmp.title.isEmpty() ? tmp.title : firstLine(tmp.plainText());
                if (title.isEmpty()) title = Lang.t("无标题");
                TextView tt = Ui.text(this, title, 15, pal.textPrimary, true);
                tt.setMaxLines(1);
                tt.setPadding(0, Ui.dp(this, 4), 0, 0);
                row.addView(tt);
                String sub;
                if (tmp.checklist) {
                    sub = "☑ " + tmp.doneCount() + "/" + tmp.items.size();
                } else if (!tmp.title.isEmpty()) {
                    sub = firstLine(Markdown.strip(tmp.content));
                } else {
                    String pt = tmp.plainText();
                    int nl = pt.indexOf('\n');
                    sub = nl >= 0 ? firstLine(pt.substring(nl + 1)) : "";
                }
                if (!sub.isEmpty()) {
                    TextView st = Ui.text(this, sub, 13, pal.textSecondary, false);
                    st.setMaxLines(1);
                    st.setPadding(0, Ui.dp(this, 3), 0, 0);
                    row.addView(st);
                }
            }
            row.setOnClickListener(vw -> showDetail(v));
            col.addView(row);
            View div = new View(this);
            div.setBackgroundColor(Palette.withAlpha(pal.outline, 0x33));
            col.addView(div, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
    }

    private void showDetail(NoteHistory.Version v) {
        inDetail = true;
        col.removeAllViews();
        col.addView(Ui.text(this, fmtTime(v.t), 13, pal.primary, true));
        Note tmp = previewNote(v);
        if (tmp == null) {
            TextView tt = Ui.text(this, "🔒 " + Lang.t("加密版本"), 17, pal.textPrimary, true);
            tt.setPadding(0, Ui.dp(this, 14), 0, 0);
            col.addView(tt);
            TextView noteTv = Ui.text(this,
                    Lang.t("这是用旧密码加密的版本，恢复后打开它需要旧密码。"),
                    14, pal.textSecondary, false);
            noteTv.setPadding(0, Ui.dp(this, 10), 0, 0);
            col.addView(noteTv);
        } else {
            if (!tmp.title.isEmpty()) {
                TextView tt = Ui.text(this, tmp.title, 17, pal.textPrimary, true);
                tt.setPadding(0, Ui.dp(this, 12), 0, 0);
                tt.setTextIsSelectable(true);
                col.addView(tt);
            }
            String body;
            if (tmp.checklist) {
                StringBuilder sb = new StringBuilder();
                for (Note.CheckItem it : tmp.items) {
                    sb.append(it.done ? "☑ " : "☐ ").append(it.text).append("\n");
                }
                body = sb.toString().trim();
            } else {
                body = tmp.content;
            }
            TextView bt = Ui.text(this, body, 15, pal.textPrimary, false);
            bt.setPadding(0, Ui.dp(this, 10), 0, 0);
            bt.setTextIsSelectable(true);
            col.addView(bt);
        }
        TextView restore = Ui.text(this, Lang.t("恢复到此版本"), 16, pal.primary, true);
        restore.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(this, 28);
        restore.setLayoutParams(rlp);
        restore.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        restore.setOnClickListener(vw -> confirmRestore(v));
        col.addView(restore);
    }

    private void confirmRestore(final NoteHistory.Version v) {
        String msg = Lang.t("恢复后，当前内容也会保留在历史版本中。");
        if (v.enc && !v.salt.equals(note.encSalt)) {
            msg += "\n" + Lang.t("这是用旧密码加密的版本，恢复后打开它需要旧密码。");
        }
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(Lang.t("恢复此版本？"))
                .setMessage(msg)
                .setPositiveButton(Lang.t("恢复到此版本"), (d, w) -> doRestore(v))
                .setNegativeButton(Lang.t("取消"), null)
                .show();
        Ui.styleDialog(this, dlg);
    }

    private void doRestore(NoteHistory.Version v) {
        NoteHistory.snapshotCurrent(this, note);
        NoteHistory.expectAppendOnNextSave(note.id);
        if (v.enc) {
            boolean sameSalt = v.salt.equals(note.encSalt);
            note.encrypted = true;
            note.encSalt = v.salt;
            note.encData = v.data;
            if (!(sameSalt && NoteCrypto.tryDecryptInPlace(note))) {
                NoteCrypto.clearKey(note.id);
                note.wipePlaintext();
            }
        } else if (v.payload != null) {
            // 普通版本直接回填载荷；笔记当前若是加密状态则保持加密，保存时自动重新加密
            note.applyPayload(v.payload);
        }
        note.updatedAt = System.currentTimeMillis();
        NoteStore.save(this);
        Toast.makeText(this, Lang.t("已恢复到此版本"), Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (inDetail && col != null) {
            showList();
            return;
        }
        finish();
    }
}
