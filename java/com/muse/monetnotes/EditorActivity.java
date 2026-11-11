package com.muse.monetnotes;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.Spannable;
import android.text.style.BackgroundColorSpan;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class EditorActivity extends Activity {
    private static final int REQ_IMAGE = 3001;
    private static final int REQ_DOC = 3002;
    private static final int REQ_FILE = 3003;

    private Palette pal;
    private Note note;
    private boolean isNew;
    private EditText titleBox;
    private EditText contentBox;
    // Markdown（v1.43）
    private HorizontalScrollView mdBarScroll;
    private TextView previewBox;
    private TextView previewBtn;
    private boolean previewOn;
    private final Runnable mdRestyleTask = () -> {
        if (!previewOn && note != null && !note.checklist && contentBox != null) {
            try { Markdown.style(contentBox.getText(), pal); } catch (Exception ignored) { }
        }
    };
    private final TextWatcher mdWatcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
        @Override public void afterTextChanged(Editable e) {
            handler.removeCallbacks(mdRestyleTask);
            handler.postDelayed(mdRestyleTask, 200);
        }
    };
    private LinearLayout checklistBox;
    private LinearLayout attachmentsBox;
    private TextView attachTitle;
    private TextView metaText;
    private TextView modeTextBtn;
    private TextView categoryBtn;
    private TextView progressText;
    private TextView pinBtn;
    private TextView favBtn;
    private TextView undoBtn;
    private ScrollView attachScroll;
    private ScrollView bodyScroll;
    private LinearLayout searchPanel;
    private LinearLayout replaceRow;
    private EditText searchQueryBox;
    private EditText replaceBox;
    private TextView searchCountText;
    private final java.util.List<int[]> searchMatches = new java.util.ArrayList<>();
    private final java.util.List<EditText> checklistEditors = new java.util.ArrayList<>();
    private int currentMatch = -1;
    private boolean searchProgrammatic = false;
    private final android.os.Handler searchHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingSearchRun;
    private String cachedLowerText;
    private String cachedRawText;
    private boolean matchesCapped = false;
    private LinearLayout loadBox;
    private ProgressBar loadBar;
    private TextView loadText;
    private boolean loadingContent = false;
    private int loadedChars = 0;
    private String lastSavedJson = null;
    private String lastUndoKey = null;
    private TextView redoBtn;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // 撤销 / 重做：以笔记 JSON 快照为步进单位
    private final List<String> undoStack = new ArrayList<>();
    private final List<String> redoStack = new ArrayList<>();
    private boolean restoring = false;

    private final Runnable saveTask = () -> {
        collect();
        String cur = note.toJsonString();
        String key = buildUndoKey();
        if (lastUndoKey != null && !key.equals(lastUndoKey) && key.length() < 200000) {
            // 内容真的变了（不只是更新时间）才产生一个撤销步进
            if (lastSavedJson != null) pushState(undoStack, lastSavedJson);
            redoStack.clear();
            updateUndoButtons();
        }
        lastSavedJson = cur;
        lastUndoKey = key;
        NoteStore.saveAsync(this);
        ReminderManager.sync(this, note);
        updateWordCount();
        updateMeta(Lang.t("已自动保存 ✓"));
    };

    /** 撤销步进的比较键：只包含内容字段，不含 updatedAt，避免每次自动保存都产生假步进。 */
    private String buildUndoKey() {
        StringBuilder sb = new StringBuilder();
        sb.append(note.title).append('\u0001').append(note.content).append('\u0001')
          .append(note.checklist).append('\u0001');
        for (Note.CheckItem it : note.items) sb.append(it.done ? '1' : '0').append(it.text).append('\u0002');
        sb.append('\u0001');
        for (String t : note.tags) sb.append(t).append('\u0002');
        sb.append('\u0001').append(note.colorIndex).append('\u0001').append(note.customColor)
          .append('\u0001').append(note.category).append('\u0001').append(note.reminderAt)
          .append('\u0001').append(note.pinned).append('\u0001').append(note.favorite)
          .append('\u0001').append(note.trashed);
        for (Note.Attachment a : note.attachments) sb.append('\u0001').append(a.id);
        return sb.toString();
    }

    private final TextWatcher autoSaveWatcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
        @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
            if (restoring) return;
            cachedRawText = null;
            if (!searchProgrammatic && searchPanel != null && searchPanel.getVisibility() == View.VISIBLE
                    && !searchMatches.isEmpty()) {
                clearHighlights();
                searchMatches.clear();
                currentMatch = -1;
                if (searchCountText != null) searchCountText.setText(Lang.t("已修改"));
            }
            handler.removeCallbacks(saveTask);
            handler.postDelayed(saveTask, 500);
        }
        @Override public void afterTextChanged(Editable s) {}
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pal = Palette.of(this);
        getWindow().setStatusBarColor(pal.background);
        getWindow().setNavigationBarColor(pal.background);
        if (!pal.dark) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        NoteStore.load(this);
        Categories.load(this);
        String id = getIntent().getStringExtra("note_id");
        if (id != null) {
            note = NoteStore.find(id);
            isNew = false;
        }
        if (note == null) {
            note = new Note();
            NoteStore.load(this).add(0, note);
            isNew = true;
        }
        if (isNew) {
            String pc = getIntent().getStringExtra("preset_category");
            if (pc != null && !pc.isEmpty()) note.category = pc;
            String pt = getIntent().getStringExtra("preset_tag");
            if (pt != null && !pt.isEmpty() && !note.tags.contains(pt)) note.tags.add(pt);
        }
        lastSavedJson = note.toJsonString();
        lastUndoKey = buildUndoKey();
        buildUi();
        if (getIntent().getBooleanExtra("open_search", false)) toggleSearchPanel();
    }

    @Override
    public void onBackPressed() {
        finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    @Override
    protected void onPause() {
        super.onPause();
        collect();
        // 新笔记若完全为空，离开时不保留
        if (isNew && note.title.trim().isEmpty() && note.content.trim().isEmpty()
                && note.items.isEmpty() && note.attachments.isEmpty() && note.reminderAt == 0) {
            ReminderManager.cancel(this, note);
            NoteStore.load(this).remove(note);
        }
        NoteStore.save(this);
        ReminderManager.sync(this, note);
    }

    // ---------- 撤销 / 重做 ----------

    private void pushState(List<String> stack, String s) {
        if (s == null || s.isEmpty()) return;
        if (!stack.isEmpty() && stack.get(stack.size() - 1).equals(s)) return;
        stack.add(s);
        while (stack.size() > 50) stack.remove(0);
    }

    /** 超长笔记分块载入：每帧只塞一小段正文，进度条持续前进，不再黑屏假死。 */
    private final Runnable loadChunkTask = () -> loadChunkStep();

    private void loadChunkStep() {
        if (isFinishing() || contentBox == null) return;
        String full = note.content;
        int end = Math.min(full.length(), loadedChars + 16000);
        if (end > loadedChars) contentBox.append(full.substring(loadedChars, end));
        loadedChars = end;
        int pct = full.isEmpty() ? 100 : (int) (loadedChars * 100L / full.length());
        if (loadBar != null) loadBar.setProgress(pct);
        if (loadText != null) loadText.setText(Lang.f("正在打开长笔记… %1$d%%", pct));
        if (loadedChars < full.length()) {
            handler.postDelayed(loadChunkTask, 30);
        } else {
            loadingContent = false;
            restoring = false;
            if (loadBox != null) loadBox.setVisibility(View.GONE);
            contentBox.setEnabled(true);
            updateWordCount();
        }
    }

    /** 离散操作（点按钮改状态）执行前调用：先结算未保存的文字修改，再压入当前状态。 */
    private void snapshotBeforeChange() {
        if (buildUndoKey().length() >= 200000) return; // 超长笔记不做快照，避免复制大字符串造成卡顿
        String pre = note.toJsonString();
        collect();
        String cur = note.toJsonString();
        if (!cur.equals(pre)) pushState(undoStack, pre);
        pushState(undoStack, cur);
        lastSavedJson = cur;
        lastUndoKey = buildUndoKey();
        redoStack.clear();
        updateUndoButtons();
    }

    private void doUndo() {
        if (undoStack.isEmpty()) { toast(Lang.t("没有可撤销的操作")); return; }
        collect();
        pushState(redoStack, note.toJsonString());
        String s = undoStack.remove(undoStack.size() - 1);
        applyState(s);
        toast(Lang.t("已撤销"));
    }

    private void doRedo() {
        if (redoStack.isEmpty()) { toast(Lang.t("没有可重做的操作")); return; }
        collect();
        pushState(undoStack, note.toJsonString());
        String s = redoStack.remove(redoStack.size() - 1);
        applyState(s);
        toast(Lang.t("已重做"));
    }

    private void applyState(String json) {
        Note o = Note.fromJsonString(json);
        if (o == null) return;
        restoring = true;
        note.copyFrom(o);
        titleBox.setText(note.title);
        contentBox.setText(note.content);
        modeTextBtn.setText(note.checklist ? Lang.t("清单模式 ✓") : Lang.t("清单模式"));
        categoryBtn.setText(note.category == null || note.category.isEmpty() ? Lang.t("📁 分类") : "📁 " + note.category);
        pinBtn.setText(note.pinned ? "📌" : "📍");
        pinBtn.setAlpha(note.pinned ? 1f : 0.55f);
        favBtn.setText(note.favorite ? "★" : "☆");
        LinearLayout body = (LinearLayout) contentBox.getParent();
        applyModeVisibility(body);
        renderChecklist();
        renderAttachments();
        updateMeta("");
        updateWordCount();
        restoring = false;
        lastSavedJson = note.toJsonString();
        lastUndoKey = buildUndoKey();
        NoteStore.save(this);
        ReminderManager.sync(this, note);
        updateUndoButtons();
    }

    private void updateUndoButtons() {
        if (undoBtn != null) undoBtn.setAlpha(undoStack.isEmpty() ? 0.3f : 1f);
        if (redoBtn != null) redoBtn.setAlpha(redoStack.isEmpty() ? 0.3f : 1f);
    }

    // ---------- UI ----------

    private void buildUi() {
        Lang.init(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(pal.background);

        // 顶栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 4));
        TextView back = Ui.text(this, "‹", 34, pal.textPrimary, false);
        back.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back);
        TextView heading = Ui.text(this, isNew ? Lang.t("新建笔记") : Lang.t("编辑笔记"), 18, pal.textPrimary, true);
        bar.addView(heading, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        undoBtn = barBtn("↩", v -> doUndo());
        bar.addView(undoBtn);
        redoBtn = barBtn("↪", v -> doRedo());
        bar.addView(redoBtn);
        pinBtn = barBtn(note.pinned ? "📌" : "📍", v -> {
            snapshotBeforeChange();
            note.pinned = !note.pinned;
            pinBtn.setText(note.pinned ? "📌" : "📍");
            pinBtn.setAlpha(note.pinned ? 1f : 0.55f);
            scheduleSave();
            toast(note.pinned ? Lang.t("已置顶") : Lang.t("已取消置顶"));
        });
        pinBtn.setAlpha(note.pinned ? 1f : 0.55f);
        bar.addView(pinBtn);
        favBtn = barBtn(note.favorite ? "★" : "☆", v -> {
            snapshotBeforeChange();
            note.favorite = !note.favorite;
            favBtn.setText(note.favorite ? "★" : "☆");
            scheduleSave();
        });
        bar.addView(favBtn);
        previewBtn = barBtn("👁", v -> togglePreview());
        bar.addView(previewBtn);
        bar.addView(barBtn("🔍", v -> toggleSearchPanel()));
        bar.addView(barBtn("↗", v -> share()));
        root.addView(bar);

        // 工具条（可横向滚动）
        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4));
        modeTextBtn = toolChip(note.checklist ? Lang.t("清单模式 ✓") : Lang.t("清单模式"), v -> toggleMode());
        tools.addView(modeTextBtn);
        categoryBtn = toolChip(note.category == null || note.category.isEmpty() ? Lang.t("📁 分类") : "📁 " + note.category, v -> pickCategory());
        tools.addView(categoryBtn);
        tools.addView(toolChip(Lang.t("🏷 标签"), v -> editTags()));
        tools.addView(toolChip(Lang.t("🎨 颜色"), v -> pickColor()));
        tools.addView(toolChip(Lang.t("🔔 提醒"), v -> pickReminder()));
        tools.addView(toolChip(Lang.t("📤 导出"), v -> { collect(); Exporter.showExport(this, note, pal); }));
        HorizontalScrollView thsv = new HorizontalScrollView(this);
        thsv.setHorizontalScrollBarEnabled(false);
        thsv.addView(tools);
        root.addView(thsv);

        // 附件功能栏：固定在顶部，不随正文上下滚动，长笔记里也能随手插入/打开附件
        LinearLayout attachPanel = new LinearLayout(this);
        attachPanel.setOrientation(LinearLayout.VERTICAL);
        attachPanel.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 4));
        LinearLayout attachHead = new LinearLayout(this);
        attachHead.setOrientation(LinearLayout.HORIZONTAL);
        attachHead.setGravity(Gravity.CENTER_VERTICAL);
        attachTitle = Ui.text(this, Lang.t("附件"), 14, pal.textPrimary, true);
        attachHead.addView(attachTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        attachHead.addView(toolChip(Lang.t("🖼 图片"), v -> launchPicker(REQ_IMAGE)));
        attachHead.addView(toolChip(Lang.t("📄 文档"), v -> launchPicker(REQ_DOC)));
        attachHead.addView(toolChip(Lang.t("📎 文件"), v -> launchPicker(REQ_FILE)));
        attachPanel.addView(attachHead);
        attachmentsBox = new LinearLayout(this);
        attachmentsBox.setOrientation(LinearLayout.VERTICAL);
        attachScroll = new ScrollView(this);
        attachScroll.addView(attachmentsBox);
        attachScroll.setVisibility(View.GONE);
        attachPanel.addView(attachScroll);
        root.addView(attachPanel);

        // 长笔记加载进度条（默认隐藏，超长笔记打开时才出现）
        loadBox = new LinearLayout(this);
        loadBox.setOrientation(LinearLayout.VERTICAL);
        loadBox.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 2));
        loadText = Ui.text(this, Lang.t("正在打开长笔记…"), 13, pal.textSecondary, false);
        loadBox.addView(loadText);
        loadBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        loadBar.setMax(100);
        LinearLayout.LayoutParams lbp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        lbp.setMargins(0, Ui.dp(this, 5), 0, 0);
        loadBox.addView(loadBar, lbp);
        loadBox.setVisibility(View.GONE);
        root.addView(loadBox);

        buildSearchPanel(root);

        ScrollView sv = new ScrollView(this);
        bodyScroll = sv;
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 40));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        titleBox = new EditText(this);
        titleBox.setHint(Lang.t("标题"));
        titleBox.setText(note.title);
        titleBox.setTextSize(24);
        titleBox.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleBox.setTextColor(pal.textPrimary);
        titleBox.setHintTextColor(pal.textSecondary);
        titleBox.setBackgroundColor(Color.TRANSPARENT);
        titleBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        titleBox.setPadding(0, 0, 0, Ui.dp(this, 4));
        titleBox.addTextChangedListener(autoSaveWatcher);
        body.addView(titleBox);

        metaText = Ui.text(this, "", 12, pal.textSecondary, false);
        metaText.setPadding(0, 0, 0, Ui.dp(this, 10));
        body.addView(metaText);

        contentBox = new EditText(this);
        contentBox.setHint(Lang.t("写点什么…"));
        final boolean isLongNote = note.content != null && note.content.length() > 20000;
        contentBox.setText(isLongNote ? "" : note.content);
        contentBox.setTextSize(16);
        contentBox.setTextColor(pal.textPrimary);
        contentBox.setHintTextColor(pal.textSecondary);
        contentBox.setBackgroundColor(Color.TRANSPARENT);
        contentBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        contentBox.setGravity(Gravity.TOP);
        contentBox.setMinLines(8);
        contentBox.setLineSpacing(0, 1.35f);
        contentBox.setPadding(0, 0, 0, 0);
        contentBox.addTextChangedListener(autoSaveWatcher);
        contentBox.addTextChangedListener(mdWatcher);
        body.addView(contentBox);

        previewBox = Ui.text(this, "", 16, pal.textPrimary, false);
        previewBox.setLineSpacing(0, 1.35f);
        previewBox.setVisibility(View.GONE);
        body.addView(previewBox);
        contentBox.post(mdRestyleTask);

        checklistBox = new LinearLayout(this);
        checklistBox.setOrientation(LinearLayout.VERTICAL);
        body.addView(checklistBox);
        progressText = Ui.text(this, "", 13, pal.primary, true);
        progressText.setPadding(0, Ui.dp(this, 8), 0, 0);
        body.addView(progressText);
        TextView addItem = Ui.text(this, Lang.t("＋ 添加条目"), 15, pal.primary, true);
        addItem.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 6));
        addItem.setOnClickListener(v -> {
            snapshotBeforeChange();
            note.items.add(new Note.CheckItem("", false));
            renderChecklist();
            scheduleSave();
        });
        body.addView(addItem);
        body.setTag(addItem);

        // Markdown 格式工具栏（v1.43）：横向滚动，正文模式下显示在底部删除键上方
        LinearLayout mdTools = new LinearLayout(this);
        mdTools.setOrientation(LinearLayout.HORIZONTAL);
        mdTools.setPadding(Ui.dp(this, 16), Ui.dp(this, 2), Ui.dp(this, 16), Ui.dp(this, 2));
        mdTools.addView(toolChip("H1", v -> mdHeading("# ")));
        mdTools.addView(toolChip("H2", v -> mdHeading("## ")));
        mdTools.addView(toolChip("H3", v -> mdHeading("### ")));
        mdTools.addView(toolChip("B", v -> mdWrap("**", "**", Lang.t("粗体"))));
        mdTools.addView(toolChip("I", v -> mdWrap("*", "*", Lang.t("斜体"))));
        mdTools.addView(toolChip("S", v -> mdWrap("~~", "~~", Lang.t("删除线"))));
        mdTools.addView(toolChip("❝", v -> mdQuote()));
        mdTools.addView(toolChip("•", v -> mdBullet()));
        mdTools.addView(toolChip("1.", v -> mdNumber()));
        mdTools.addView(toolChip("☑", v -> mdCheck()));
        mdTools.addView(toolChip("`", v -> mdWrap("`", "`", Lang.t("代码"))));
        mdTools.addView(toolChip("```", v -> mdWrap("```\n", "\n```", Lang.t("代码"))));
        mdTools.addView(toolChip("🔗", v -> mdWrap("[", "](https://)", "")));
        mdTools.addView(toolChip("―", v -> mdInsert("\n\n---\n")));
        mdBarScroll = new HorizontalScrollView(this);
        mdBarScroll.setHorizontalScrollBarEnabled(false);
        mdBarScroll.addView(mdTools);
        root.addView(mdBarScroll);

        // 底部删除
        TextView del = Ui.text(this, note.trashed ? Lang.t("彻底删除") : Lang.t("移到回收站"), 14, 0xFFB3261E, false);
        del.setGravity(Gravity.CENTER);
        del.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 16));
        del.setOnClickListener(v -> {
            if (note.trashed) {
                ReminderManager.cancel(this, note);
                NoteStore.load(this).remove(note);
                NoteStore.save(this);
                finish();
            } else {
                note.trashed = true;
                note.pinned = false;
                NoteStore.save(this);
                ReminderManager.sync(this, note);
                toast(Lang.t("已移到回收站，可在主页撤销"));
                finish();
            }
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
        });
        root.addView(del);

        applyModeVisibility(body);
        renderChecklist();
        renderAttachments();
        updateMeta(isNew ? Lang.t("新笔记") : "");
        updateWordCount();
        updateUndoButtons();
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(pal.background);
        frame.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        FastScroll.attach(this, frame, sv, pal, 8);
        FastScroll.attachBackToTop(this, frame, sv, pal, true, 64, 16);
        setContentView(frame);
        BgHelper.apply(this, frame, pal, root, false);
        if (isLongNote && !note.checklist) {
            // 先让界面和进度条画出来，再分块载入正文，避免长时间黑屏
            loadingContent = true;
            restoring = true;
            loadedChars = 0;
            loadBox.setVisibility(View.VISIBLE);
            contentBox.setEnabled(false);
            handler.postDelayed(loadChunkTask, 60);
        }
    }

    private void applyModeVisibility(LinearLayout body) {
        contentBox.setVisibility(note.checklist ? View.GONE : View.VISIBLE);
        checklistBox.setVisibility(note.checklist ? View.VISIBLE : View.GONE);
        View addBtn = (View) body.getTag();
        if (addBtn != null) addBtn.setVisibility(note.checklist ? View.VISIBLE : View.GONE);
        progressText.setVisibility(note.checklist ? View.VISIBLE : View.GONE);
        if (mdBarScroll != null) mdBarScroll.setVisibility(!note.checklist && !previewOn ? View.VISIBLE : View.GONE);
        if (previewBox != null) {
            if (note.checklist) previewBox.setVisibility(View.GONE);
            else if (previewOn) { previewBox.setVisibility(View.VISIBLE); contentBox.setVisibility(View.GONE); }
        }
    }

    // ---------- Markdown 编辑动作（v1.43） ----------

    private void mdLines(java.util.function.Function<String, String> fn) {
        Editable e = contentBox.getText();
        if (e == null) return;
        int a = Math.min(contentBox.getSelectionStart(), contentBox.getSelectionEnd());
        int b = Math.max(contentBox.getSelectionStart(), contentBox.getSelectionEnd());
        if (a < 0) { a = b = e.length(); }
        int ls = a == 0 ? 0 : e.toString().lastIndexOf('\n', a - 1) + 1;
        int le = b;
        while (le < e.length() && e.charAt(le) != '\n') le++;
        String[] lines = e.subSequence(ls, le).toString().split("\n", -1);
        for (int i = 0; i < lines.length; i++) lines[i] = fn.apply(lines[i]);
        String joined = String.join("\n", lines);
        e.replace(ls, le, joined);
        contentBox.setSelection(ls, ls + joined.length());
        contentBox.requestFocus();
    }

    private static String mdIndentOf(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) i++;
        return line.substring(0, i);
    }

    private static String mdStripListMarker(String body) {
        String r = body.replaceFirst("^[-*+]\\s+(\\[[ xX]\\]\\s+)?", "");
        return r.replaceFirst("^\\d{1,9}[.)]\\s+", "");
    }

    /** 标题按钮：切换光标行（或所选多行）的标题前缀，然后把光标放到第一行标题符号之后，方便直接接着输入。 */
    private void mdHeading(String prefix) {
        Editable e = contentBox.getText();
        if (e == null) return;
        int a = Math.min(contentBox.getSelectionStart(), contentBox.getSelectionEnd());
        int b = Math.max(contentBox.getSelectionStart(), contentBox.getSelectionEnd());
        if (a < 0) { a = b = e.length(); }
        int ls = a == 0 ? 0 : e.toString().lastIndexOf('\n', a - 1) + 1;
        int le = b;
        while (le < e.length() && e.charAt(le) != '\n') le++;
        String[] lines = e.subSequence(ls, le).toString().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String stripped = lines[i].replaceFirst("^#{1,6}\\s+", "");
            lines[i] = lines[i].startsWith(prefix) ? stripped : prefix + stripped;
        }
        e.replace(ls, le, String.join("\n", lines));
        int cur = lines[0].startsWith(prefix) ? ls + prefix.length() : ls;
        contentBox.setSelection(cur);
        contentBox.requestFocus();
    }

    private void mdQuote() {
        mdLines(line -> line.startsWith(">") ? line.replaceFirst("^>\\s?", "") : "> " + line);
    }

    private void mdBullet() {
        mdLines(line -> {
            String ind = mdIndentOf(line), body = line.substring(ind.length());
            boolean isBullet = (body.startsWith("- ") || body.startsWith("* ") || body.startsWith("+ "))
                    && !body.substring(2).startsWith("[");
            return isBullet ? ind + body.substring(2) : ind + "- " + mdStripListMarker(body);
        });
    }

    private void mdNumber() {
        mdLines(line -> {
            String ind = mdIndentOf(line), body = line.substring(ind.length());
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\d{1,9}[.)]\\s+").matcher(body);
            return m.find() ? ind + body.substring(m.end()) : ind + "1. " + mdStripListMarker(body);
        });
    }

    private void mdCheck() {
        mdLines(line -> {
            String ind = mdIndentOf(line), body = line.substring(ind.length());
            if (body.startsWith("- [ ] ") || body.startsWith("- [x] ") || body.startsWith("- [X] "))
                return ind + body.substring(6);
            return ind + "- [ ] " + mdStripListMarker(body);
        });
    }

    private void mdWrap(String before, String after, String placeholder) {
        Editable e = contentBox.getText();
        if (e == null) return;
        int a = Math.min(contentBox.getSelectionStart(), contentBox.getSelectionEnd());
        int b = Math.max(contentBox.getSelectionStart(), contentBox.getSelectionEnd());
        if (a < 0) { a = b = e.length(); }
        if (a == b) {
            e.insert(a, before + placeholder + after);
            contentBox.setSelection(a + before.length(), a + before.length() + placeholder.length());
        } else {
            String sel = e.subSequence(a, b).toString();
            e.replace(a, b, before + sel + after);
            contentBox.setSelection(a + before.length(), a + before.length() + sel.length());
        }
        contentBox.requestFocus();
    }

    private void mdInsert(String text) {
        Editable e = contentBox.getText();
        if (e == null) return;
        int a = Math.max(0, contentBox.getSelectionStart());
        e.insert(a, text);
        contentBox.setSelection(a + text.length());
        contentBox.requestFocus();
    }

    private void togglePreview() {
        if (note.checklist) { toast(Lang.t("清单模式不支持预览")); return; }
        previewOn = !previewOn;
        if (previewOn) {
            previewBox.setText(Markdown.render(contentBox.getText().toString(), pal));
            previewBox.setVisibility(View.VISIBLE);
            contentBox.setVisibility(View.GONE);
            mdBarScroll.setVisibility(View.GONE);
            previewBtn.setText("✏️");
        } else {
            previewBox.setVisibility(View.GONE);
            contentBox.setVisibility(View.VISIBLE);
            mdBarScroll.setVisibility(View.VISIBLE);
            previewBtn.setText("👁");
            Markdown.style(contentBox.getText(), pal);
        }
    }

    private TextView barBtn(String label, View.OnClickListener cb) {
        TextView b = Ui.text(this, label, 18, pal.textPrimary, false);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(this, 9), Ui.dp(this, 5), Ui.dp(this, 9), Ui.dp(this, 5));
        b.setOnClickListener(cb);
        return b;
    }

    private TextView toolChip(String label, View.OnClickListener cb) {
        TextView c = Ui.text(this, label, 13, pal.textPrimary, false);
        c.setBackground(Ui.round(pal.surfaceVariant, Ui.dp(this, 16)));
        c.setPadding(Ui.dp(this, 11), Ui.dp(this, 6), Ui.dp(this, 11), Ui.dp(this, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, Ui.dp(this, 8), 0);
        c.setLayoutParams(lp);
        c.setOnClickListener(cb);
        return c;
    }

    private void toggleMode() {
        snapshotBeforeChange();
        note.checklist = !note.checklist;
        modeTextBtn.setText(note.checklist ? Lang.t("清单模式 ✓") : Lang.t("清单模式"));
        LinearLayout body = (LinearLayout) contentBox.getParent();
        applyModeVisibility(body);
        scheduleSave();
    }

    // ---------- 清单 ----------

    private void renderChecklist() {
        checklistBox.removeAllViews();
        checklistEditors.clear();
        for (int i = 0; i < note.items.size(); i++) {
            final int idx = i;
            Note.CheckItem item = note.items.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox cb = new CheckBox(this);
            // 与设置页单选按钮同一教训：勾选圈颜色显式按 Palette 设置，
            // 避免系统深色 + 应用浅色时与背景融在一起
            cb.setButtonTintList(new ColorStateList(
                    new int[][]{{android.R.attr.state_checked}, {}},
                    new int[]{pal.primary, pal.outline}));
            cb.setChecked(item.done);
            cb.setOnCheckedChangeListener((v, checked) -> {
                note.items.get(idx).done = checked;
                updateProgress();
                scheduleSave();
            });
            row.addView(cb);
            EditText et = new EditText(this);
            et.setText(item.text);
            et.setTextSize(15);
            et.setTextColor(pal.textPrimary);
            et.setHint(Lang.t("条目内容"));
            et.setHintTextColor(pal.textSecondary);
            et.setBackgroundColor(Color.TRANSPARENT);
            et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            et.setSingleLine(true);
            et.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                    if (restoring) return;
                    note.items.get(idx).text = s.toString();
                    scheduleSave();
                }
                @Override public void afterTextChanged(Editable s) {}
            });
            checklistEditors.add(et);
            row.addView(et, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView rm = Ui.text(this, "✕", 14, pal.textSecondary, false);
            rm.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
            rm.setOnClickListener(v -> {
                snapshotBeforeChange();
                note.items.remove(idx);
                renderChecklist();
                scheduleSave();
            });
            row.addView(rm);
            checklistBox.addView(row);
        }
        updateProgress();
    }

    private void updateProgress() {
        if (note.items.isEmpty()) { progressText.setText(""); return; }
        progressText.setText(Lang.f("已完成 %1$d/%2$d", note.doneCount(), note.items.size()));
    }

    // ---------- 分类与标签 ----------

    private void pickCategory() {
        Categories.showPickDialog(this, note, picked -> {
            snapshotBeforeChange();
            note.category = picked;
            categoryBtn.setText(picked.isEmpty() ? Lang.t("📁 分类") : "📁 " + picked);
            scheduleSave();
            updateMeta(Lang.t("分类已更新"));
        });
    }

    private void editTags() {
        TagEditor.show(this, pal, note.tags, tags -> {
            snapshotBeforeChange();
            note.tags.clear();
            note.tags.addAll(tags);
            scheduleSave();
            updateMeta(Lang.t("标签已更新"));
        });
    }

    // ---------- 颜色：预设 + 自定义 ----------

    private void pickColor() {
        String[] options = {Lang.t("预设颜色"), Lang.t("自定义颜色…"), Lang.t("清除颜色标记")};
        new AlertDialog.Builder(this)
                .setTitle(Lang.t("笔记颜色"))
                .setItems(options, (d, which) -> {
                    if (which == 0) showPresetColors();
                    else if (which == 1) showCustomColor();
                    else {
                        snapshotBeforeChange();
                        note.colorIndex = 0;
                        note.customColor = 0;
                        scheduleSave();
                        toast(Lang.t("已清除颜色标记"));
                    }
                }).show();
    }

    private void showPresetColors() {
        String[] labels = {Lang.t("默认（跟随主题）"), Lang.t("红"), Lang.t("橙"), Lang.t("黄"), Lang.t("绿"), Lang.t("青"), Lang.t("蓝")};
        int checked = note.customColor != 0 ? 0 : note.colorIndex;
        new AlertDialog.Builder(this)
                .setTitle(Lang.t("预设颜色"))
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    snapshotBeforeChange();
                    note.colorIndex = which;
                    note.customColor = 0;
                    scheduleSave();
                    d.dismiss();
                    toast(Lang.t("颜色已更新，返回列表查看效果"));
                }).show();
    }

    private void showCustomColor() {
        final float[] hsv = new float[3];
        int init = note.customColor != 0 ? note.customColor : pal.primary;
        Color.colorToHSV(init, hsv);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 22), Ui.dp(this, 12), Ui.dp(this, 22), Ui.dp(this, 4));

        final View preview = new View(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52));
        plp.setMargins(0, 0, 0, Ui.dp(this, 12));
        box.addView(preview, plp);

        final EditText hexBox = new EditText(this);
        hexBox.setHint("#RRGGBB");
        hexBox.setSingleLine(true);
        box.addView(hexBox);

        String[] names = {Lang.t("色相"), Lang.t("饱和度"), Lang.t("明度")};
        int[] maxes = {360, 100, 100};
        final SeekBar[] bars = new SeekBar[3];
        final boolean[] syncing = {false};
        Runnable refresh = () -> {
            int color = Color.HSVToColor(hsv);
            int tint = pal.dark ? Palette.blend(color, Color.BLACK, 0.45f)
                                : Palette.blend(color, Color.WHITE, 0.62f);
            preview.setBackground(Ui.round(tint, Ui.dp(this, 12)));
            if (!syncing[0]) {
                syncing[0] = true;
                hexBox.setText(String.format("#%06X", color & 0xFFFFFF));
                syncing[0] = false;
            }
        };
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView label = Ui.text(this, names[i], 13, pal.textPrimary, false);
            label.setPadding(0, Ui.dp(this, 8), 0, 0);
            box.addView(label);
            SeekBar sb = new SeekBar(this);
            sb.setMax(maxes[i]);
            sb.setProgress(idx == 0 ? (int) hsv[0] : (int) (hsv[idx] * 100));
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    hsv[idx] = idx == 0 ? p : p / 100f;
                    refresh.run();
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
            bars[i] = sb;
            box.addView(sb);
        }
        TextView applyHex = Ui.text(this, Lang.t("用十六进制值设置"), 13, pal.primary, true);
        applyHex.setPadding(0, Ui.dp(this, 8), 0, 0);
        applyHex.setOnClickListener(v -> {
            try {
                int c = Color.parseColor(hexBox.getText().toString().trim());
                Color.colorToHSV(c, hsv);
                bars[0].setProgress((int) hsv[0]);
                bars[1].setProgress((int) (hsv[1] * 100));
                bars[2].setProgress((int) (hsv[2] * 100));
                refresh.run();
            } catch (Exception e) {
                toast(Lang.t("颜色值格式不对，如 #FF8800"));
            }
        });
        box.addView(applyHex);
        refresh.run();

        new AlertDialog.Builder(this)
                .setTitle(Lang.t("自定义颜色"))
                .setView(box)
                .setNegativeButton(Lang.t("取消"), null)
                .setPositiveButton(Lang.t("使用此颜色"), (d, w) -> {
                    snapshotBeforeChange();
                    note.customColor = Color.HSVToColor(hsv);
                    scheduleSave();
                    toast(Lang.t("自定义颜色已应用，返回列表查看效果"));
                }).show();
    }

    // ---------- 提醒 ----------

    private void pickReminder() {
        if (note.reminderAt > 0) {
            new AlertDialog.Builder(this)
                    .setTitle(Lang.t("提醒时间"))
                    .setMessage(Lang.f("当前提醒：%1$s", new SimpleDateFormat(Lang.t("yyyy年M月d日 HH:mm"), Locale.US).format(new Date(note.reminderAt)))
                            + Lang.t("\n\n到这个时间会发送系统通知；点通知可回到应用。"))
                    .setNegativeButton(Lang.t("清除提醒"), (d, w) -> {
                        snapshotBeforeChange();
                        note.reminderAt = 0;
                        scheduleSave();
                        ReminderManager.sync(this, note);
                        updateMeta(Lang.t("提醒已清除"));
                    })
                    .setPositiveButton(Lang.t("重新设置"), (d, w) -> showDateTimePicker())
                    .setNeutralButton(Lang.t("取消"), null)
                    .show();
        } else {
            showDateTimePicker();
        }
    }

    private void showDateTimePicker() {
        ReminderManager.requestNotificationPermission(this);
        Calendar cal = Calendar.getInstance();
        if (note.reminderAt > 0) cal.setTimeInMillis(note.reminderAt);
        new DatePickerDialog(this, (view, y, m, day) -> {
            cal.set(y, m, day);
            new TimePickerDialog(this, (tv, h, min) -> {
                cal.set(Calendar.HOUR_OF_DAY, h);
                cal.set(Calendar.MINUTE, min);
                cal.set(Calendar.SECOND, 0);
                snapshotBeforeChange();
                note.reminderAt = cal.getTimeInMillis();
                scheduleSave();
                ReminderManager.sync(this, note);
                updateMeta(Lang.t("提醒已设置"));
                toast(Lang.t("提醒已设置，到时会通知你"));
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show();
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    // ---------- 附件 ----------

    private void launchPicker(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        if (req == REQ_IMAGE) {
            i.setType("image/*");
        } else if (req == REQ_DOC) {
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                    "application/pdf", "text/plain", "text/csv",
                    "application/msword",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/vnd.ms-excel",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-powerpoint",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation"});
        } else {
            i.setType("*/*");
        }
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            toast(Lang.t("没有可用的文件选择器"));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (Exporter.handleSaveResult(this, requestCode, resultCode, data)) return;
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode != REQ_IMAGE && requestCode != REQ_DOC && requestCode != REQ_FILE) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        snapshotBeforeChange();
        int added = 0;
        for (Uri uri : uris) {
            Note.Attachment a = Attachments.copyIn(this, uri);
            if (a != null) {
                note.attachments.add(a);
                added++;
            }
        }
        if (added > 0) {
            renderAttachments();
            scheduleSave();
            toast(Lang.f("已添加 %1$d 个附件", added));
        } else {
            toast(Lang.t("附件导入失败"));
        }
    }

    private void renderAttachments() {
        attachmentsBox.removeAllViews();
        attachTitle.setText(Lang.t("附件") + (note.attachments.isEmpty() ? "" : " (" + note.attachments.size() + ")"));
        for (int i = 0; i < note.attachments.size(); i++) {
            final int idx = i;
            final Note.Attachment a = note.attachments.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.round(pal.surfaceVariant, Ui.dp(this, 14)));
            row.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, 0, 0, Ui.dp(this, 8));
            row.setLayoutParams(rlp);

            if (a.isImage()) {
                ImageView iv = new ImageView(this);
                Bitmap bm = Attachments.thumbnail(this, a, 160);
                if (bm != null) iv.setImageBitmap(bm);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                row.addView(iv, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
            } else {
                TextView icon = Ui.text(this, Attachments.iconFor(a.mime), 26, pal.textPrimary, false);
                icon.setGravity(Gravity.CENTER);
                row.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
            }
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(Ui.dp(this, 10), 0, 0, 0);
            TextView nm = Ui.text(this, a.name, 14, pal.textPrimary, true);
            nm.setMaxLines(1);
            nm.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            info.addView(nm);
            info.addView(Ui.text(this, Attachments.formatSize(a.size) + Lang.t(" · 点按打开"), 12, pal.textSecondary, false));
            row.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView rm = Ui.text(this, "✕", 15, pal.textSecondary, false);
            rm.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
            rm.setOnClickListener(v -> {
                snapshotBeforeChange();
                note.attachments.remove(idx);
                renderAttachments();
                scheduleSave();
                toast(Lang.t("附件已移除，可点 ↩ 撤销"));
            });
            row.addView(rm);
            row.setOnClickListener(v -> Attachments.open(this, a));
            attachmentsBox.addView(row);
        }
        if (attachScroll != null) {
            int rows = note.attachments.size();
            attachScroll.setVisibility(rows == 0 ? View.GONE : View.VISIBLE);
            if (rows > 0) {
                ViewGroup.LayoutParams lp = attachScroll.getLayoutParams();
                lp.height = Ui.dp(this, Math.min(rows, 3) * 76);
                attachScroll.setLayoutParams(lp);
            }
        }
    }

    // ---------- 笔记内搜索 / 替换 ----------

    private TextView searchSmallBtn(String label, View.OnClickListener cb) {
        TextView b = Ui.text(this, label, 14, pal.textPrimary, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.round(pal.surface, Ui.dp(this, 14)));
        b.setPadding(Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 4), 0, 0, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(cb);
        return b;
    }

    private void buildSearchPanel(LinearLayout root) {
        searchPanel = new LinearLayout(this);
        searchPanel.setOrientation(LinearLayout.VERTICAL);
        searchPanel.setBackgroundColor(pal.surfaceVariant);
        searchPanel.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        searchPanel.setVisibility(View.GONE);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        searchQueryBox = new EditText(this);
        searchQueryBox.setHint(Lang.t("在笔记中搜索…"));
        searchQueryBox.setSingleLine(true);
        searchQueryBox.setTextSize(14);
        searchQueryBox.setTextColor(pal.textPrimary);
        searchQueryBox.setHintTextColor(pal.textSecondary);
        searchQueryBox.setBackground(Ui.round(pal.surface, Ui.dp(this, 14)));
        searchQueryBox.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        searchQueryBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        row1.addView(searchQueryBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        searchCountText = Ui.text(this, "", 12, pal.textSecondary, false);
        searchCountText.setPadding(Ui.dp(this, 8), 0, 0, 0);
        row1.addView(searchCountText);
        row1.addView(searchSmallBtn("↑", v -> jumpMatch(-1)));
        row1.addView(searchSmallBtn("↓", v -> jumpMatch(1)));
        row1.addView(searchSmallBtn(Lang.t("替换"), v -> {
            replaceRow.setVisibility(replaceRow.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            if (replaceRow.getVisibility() == View.VISIBLE) replaceBox.requestFocus();
        }));
        row1.addView(searchSmallBtn("✕", v -> toggleSearchPanel()));
        searchPanel.addView(row1);

        replaceRow = new LinearLayout(this);
        replaceRow.setOrientation(LinearLayout.HORIZONTAL);
        replaceRow.setGravity(Gravity.CENTER_VERTICAL);
        replaceRow.setPadding(0, Ui.dp(this, 8), 0, 0);
        replaceRow.setVisibility(View.GONE);
        replaceBox = new EditText(this);
        replaceBox.setHint(Lang.t("替换为…"));
        replaceBox.setSingleLine(true);
        replaceBox.setTextSize(14);
        replaceBox.setTextColor(pal.textPrimary);
        replaceBox.setHintTextColor(pal.textSecondary);
        replaceBox.setBackground(Ui.round(pal.surface, Ui.dp(this, 14)));
        replaceBox.setPadding(Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4));
        replaceBox.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        replaceRow.addView(replaceBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        replaceRow.addView(searchSmallBtn(Lang.t("替换当前"), v -> replaceCurrent()));
        replaceRow.addView(searchSmallBtn(Lang.t("全部替换"), v -> replaceAll()));
        searchPanel.addView(replaceRow);

        searchQueryBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence t, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence t, int a, int b, int c) {
                // 停顿 300ms 后才真正搜索，只统计处数、不自动跳转，避免边打字边卡
                if (pendingSearchRun != null) searchHandler.removeCallbacks(pendingSearchRun);
                pendingSearchRun = () -> computeMatches();
                searchHandler.postDelayed(pendingSearchRun, 300);
            }
            @Override public void afterTextChanged(Editable e) {}
        });
        root.addView(searchPanel);
    }

    private void toggleSearchPanel() {
        if (searchPanel.getVisibility() == View.VISIBLE) {
            searchPanel.setVisibility(View.GONE);
            clearHighlights();
            searchMatches.clear();
            currentMatch = -1;
        } else {
            searchPanel.setVisibility(View.VISIBLE);
            searchQueryBox.requestFocus();
            if (!searchQueryBox.getText().toString().isEmpty()) computeMatches();
        }
    }

    private void clearHighlights() {
        clearSpans(contentBox);
        for (EditText et : checklistEditors) clearSpans(et);
    }

    private void clearSpans(EditText box) {
        if (box == null) return;
        Editable e = box.getText();
        if (e == null) return;
        BackgroundColorSpan[] spans = e.getSpans(0, e.length(), BackgroundColorSpan.class);
        // 只清搜索自己的纯 BackgroundColorSpan，Markdown 代码底色是其子类 Bg，不能误删
        for (BackgroundColorSpan sp : spans) if (sp.getClass() == BackgroundColorSpan.class) e.removeSpan(sp);
    }

    /** 只统计命中并（处数不多时）预高亮，不跳转。长文本的小写副本按内容缓存，避免每次按键都重算。 */
    private void computeMatches() {
        if (loadingContent) { toast(Lang.t("正文还在加载中，稍等一下")); return; }
        String q = searchQueryBox.getText().toString();
        clearHighlights();
        searchMatches.clear();
        currentMatch = -1;
        matchesCapped = false;
        if (q.isEmpty()) { searchCountText.setText(""); return; }
        String ql = q.toLowerCase(Locale.ROOT);
        if (!note.checklist) {
            String raw = contentBox.getText().toString();
            if (!raw.equals(cachedRawText)) {
                cachedRawText = raw;
                cachedLowerText = raw.toLowerCase(Locale.ROOT);
            }
            int from = 0;
            while (searchMatches.size() < 5000) {
                int idx = cachedLowerText.indexOf(ql, from);
                if (idx < 0) break;
                searchMatches.add(new int[]{-1, idx, idx + q.length()});
                from = idx + Math.max(1, q.length());
            }
            if (searchMatches.size() >= 5000 && cachedLowerText.indexOf(ql, from) >= 0) matchesCapped = true;
        } else {
            outer:
            for (int i = 0; i < note.items.size(); i++) {
                String tl = note.items.get(i).text.toLowerCase(Locale.ROOT);
                int from = 0;
                while (true) {
                    int idx = tl.indexOf(ql, from);
                    if (idx < 0) break;
                    searchMatches.add(new int[]{i, idx, idx + q.length()});
                    from = idx + Math.max(1, q.length());
                    if (searchMatches.size() >= 5000) { matchesCapped = true; break outer; }
                }
            }
        }
        // 处数不多时直接全部高亮方便扫视；很多时等跳转时只高亮当前附近，保证流畅
        if (!searchMatches.isEmpty() && searchMatches.size() <= 200) applyHighlights();
        updateSearchCount();
    }

    private void applyHighlights() {
        int allColor = 0x665BC0FF;
        int curColor = 0xFFFFB300;
        boolean windowed = searchMatches.size() > 200 && currentMatch >= 0;
        int curStart = windowed ? searchMatches.get(currentMatch)[1] : 0;
        int curScope = windowed ? searchMatches.get(currentMatch)[0] : -2;
        for (int i = 0; i < searchMatches.size(); i++) {
            int[] m = searchMatches.get(i);
            if (windowed) {
                if (m[0] < 0) { if (Math.abs(m[1] - curStart) > 4000) continue; }
                else if (Math.abs(m[0] - curScope) > 3) continue;
            }
            EditText target = m[0] < 0 ? contentBox : (m[0] < checklistEditors.size() ? checklistEditors.get(m[0]) : null);
            if (target == null) continue;
            Editable e = target.getText();
            if (e == null || m[2] > e.length()) continue;
            e.setSpan(new BackgroundColorSpan(i == currentMatch ? curColor : allColor),
                    m[1], m[2], Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void updateSearchCount() {
        if (searchMatches.isEmpty()) { searchCountText.setText("0/0"); return; }
        String total = searchMatches.size() + (matchesCapped ? "+" : "");
        if (currentMatch < 0) searchCountText.setText(Lang.f("共 %1$d 处", total));
        else searchCountText.setText((currentMatch + 1) + "/" + total);
    }

    private void jumpMatch(int dir) {
        if (searchMatches.isEmpty()) {
            if (!searchQueryBox.getText().toString().isEmpty()) computeMatches();
            if (searchMatches.isEmpty()) { toast(Lang.t("没有找到")); return; }
            return;
        }
        int next = currentMatch + dir;
        if (next >= searchMatches.size()) next = 0;
        if (next < 0) next = searchMatches.size() - 1;
        jumpTo(next);
    }

    private void jumpTo(int idx) {
        currentMatch = idx;
        clearHighlights();
        applyHighlights();
        updateSearchCount();
        int[] m = searchMatches.get(idx);
        if (m[0] < 0) {
            contentBox.requestFocus();
            contentBox.setSelection(m[1], m[2]);
            scrollEditorTo(contentBox, m[1]);
        } else if (m[0] < checklistEditors.size()) {
            EditText et = checklistEditors.get(m[0]);
            et.requestFocus();
            et.setSelection(m[1], m[2]);
            scrollEditorTo(et, m[1]);
        }
    }

    /** 把编辑区滚到指定控件内偏移处可见。 */
    private void scrollEditorTo(View inner, int charOffset) {
        inner.post(() -> {
            int y = 0;
            View v = inner;
            while (v != null && v.getParent() != bodyScroll) {
                y += v.getTop();
                android.view.ViewParent par = v.getParent();
                v = par instanceof View ? (View) par : null;
            }
            if (inner instanceof EditText) {
                android.text.Layout l = ((EditText) inner).getLayout();
                if (l != null && charOffset <= ((EditText) inner).getText().length()) {
                    y += l.getLineTop(l.getLineForOffset(charOffset));
                }
            }
            bodyScroll.smoothScrollTo(0, Math.max(0, y - Ui.dp(this, 140)));
        });
    }

    private void replaceCurrent() {
        if (searchMatches.isEmpty() || currentMatch < 0) { toast(Lang.t("先搜索并定位到一处")); return; }
        String q = searchQueryBox.getText().toString();
        String rep = replaceBox.getText().toString();
        if (q.isEmpty()) return;
        int[] m = searchMatches.get(currentMatch);
        snapshotBeforeChange();
        searchProgrammatic = true;
        if (m[0] < 0) {
            Editable e = contentBox.getText();
            if (m[2] <= e.length()) e.replace(m[1], m[2], rep);
            note.content = contentBox.getText().toString();
        } else {
            Note.CheckItem item = note.items.get(m[0]);
            String t = item.text;
            if (m[2] <= t.length()) item.text = t.substring(0, m[1]) + rep + t.substring(m[2]);
            renderChecklist();
        }
        searchProgrammatic = false;
        scheduleSave();
        int keep = currentMatch;
        cachedRawText = null;
        computeMatches();
        if (!searchMatches.isEmpty()) jumpTo(Math.min(keep, searchMatches.size() - 1));
        toast(Lang.t("已替换 1 处"));
    }

    private void replaceAll() {
        String q = searchQueryBox.getText().toString();
        String rep = replaceBox.getText().toString();
        if (q.isEmpty() || searchMatches.isEmpty()) { toast(Lang.t("没有可替换的内容")); return; }
        int count = searchMatches.size();
        snapshotBeforeChange();
        searchProgrammatic = true;
        if (!note.checklist) {
            restoring = true;
            contentBox.setText(contentBox.getText().toString().replace(q, rep));
            restoring = false;
            note.content = contentBox.getText().toString();
        } else {
            for (Note.CheckItem item : note.items) {
                if (item.text.contains(q)) item.text = item.text.replace(q, rep);
            }
            renderChecklist();
        }
        searchProgrammatic = false;
        scheduleSave();
        cachedRawText = null;
        computeMatches();
        toast(Lang.f("已全部替换 %1$d 处，可点 ↩ 撤销", count));
    }

    // ---------- 通用 ----------

    private void share() {
        collect();
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, note.title);
        i.putExtra(Intent.EXTRA_TEXT, note.plainText());
        startActivity(Intent.createChooser(i, Lang.t("分享笔记")));
    }

    private void collect() {
        note.title = titleBox.getText().toString();
        if (!loadingContent) note.content = contentBox.getText().toString();
        note.updatedAt = System.currentTimeMillis();
    }

    private void scheduleSave() {
        handler.removeCallbacks(saveTask);
        handler.postDelayed(saveTask, 400);
    }

    private void updateWordCount() {
        int count;
        if (note.checklist) count = note.items.size();
        else {
            String t = contentBox == null ? "" : contentBox.getText().toString();
            int c = 0;
            for (int i = 0; i < t.length(); i++) if (!Character.isWhitespace(t.charAt(i))) c++;
            count = c;
        }
        updateMeta(note.checklist ? Lang.f("共 %1$d 个条目", count) : Lang.f("共 %1$d 字", count));
    }

    private void updateMeta(String extra) {
        if (metaText == null) return;
        String s = Lang.t("创建于 ") + new SimpleDateFormat(Lang.t("yyyy年M月d日 HH:mm"), Locale.US).format(new Date(note.createdAt));
        if (note.category != null && !note.category.isEmpty()) s += "   📁 " + note.category;
        if (!note.tags.isEmpty()) s += "   #" + String.join("  #", note.tags);
        if (note.reminderAt > 0) s += Lang.t("\n🔔 提醒：") + new SimpleDateFormat(Lang.t("M月d日 HH:mm"), Locale.US).format(new Date(note.reminderAt));
        if (extra != null && !extra.isEmpty()) s += "\n" + extra;
        metaText.setText(s);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
