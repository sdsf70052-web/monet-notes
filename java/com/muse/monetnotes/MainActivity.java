package com.muse.monetnotes;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    public static final int FILTER_ALL = 0;
    public static final int FILTER_FAV = 1;
    public static final int FILTER_ARCHIVED = 2;
    public static final int FILTER_TRASH = 3;
    public static final int FILTER_TAG = 4;
    public static final int FILTER_CATEGORY = 5;

    private SharedPreferences sp;
    private Palette pal;
    private LinearLayout chipsBox;
    private LinearLayout contentBox;
    private TextView countText;
    private int builtLangSetting = -1;
    private ScrollView listScroll;
    private FrameLayout bgRoot;
    private HorizontalScrollView chipScroll;
    private View selectedChipView;
    private boolean switchingAnim = false;
    private float dragStartX, dragStartY, dragDx, dragDy;
    private boolean dragTracking, dragClaimed;
    private android.view.VelocityTracker vTracker;
    private TextView trashAction;
    private EditText searchBox;
    private TextView viewToggleBtn;
    private int filter = FILTER_ALL;
    private String filterTag = "";
    private String filterCategory = "";
    private String movingCategory = null;
    private LinearLayout undoBar;
    private TextView undoMsg;
    private Runnable pendingUndo;
    private final Handler undoHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideUndoTask = () -> { if (undoBar != null) undoBar.setVisibility(View.GONE); pendingUndo = null; syncOverlayWindow(); };
    private String query = "";
    private String moveSourceId = null;
    private java.util.List<Note> lastShown = new java.util.ArrayList<>();
    private boolean selecting = false;
    private final java.util.Set<String> selectedIds = new java.util.LinkedHashSet<>();
    private LinearLayout selectionBar;
    private android.app.Dialog overlayDialog;
    private FrameLayout overlayBox;
    private TextView selCountText;
    private TextView selOrderBtn;
    private View fabView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences(Palette.PREFS, MODE_PRIVATE);
        pal = Palette.of(this);
        getWindow().setStatusBarColor(pal.background);
        getWindow().setNavigationBarColor(pal.background);
        if (!pal.dark) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        NoteStore.load(this);
        Categories.load(this);
        Attachments.cleanupOrphans(this);
        ReminderManager.requestNotificationPermission(this);
        ReminderManager.syncAll(this);
        buildUi();
        refresh();
        syncOverlayWindow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Exporter.handleSaveResult(this, requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        int curLang = getSharedPreferences(Palette.PREFS, MODE_PRIVATE).getInt(Lang.KEY_LANG, 0);
        if (builtLangSetting >= 0 && curLang != builtLangSetting) { recreate(); return; }
        if (bgRoot != null) BgHelper.apply(this, bgRoot, pal, null, true);
        pal = Palette.of(this);
        ReminderManager.syncAll(this);
        buildUi();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        BgHelper.stopSceneLoop();
    }

    @Override
    protected void onDestroy() {
        BgHelper.stopSceneLoop();
        if (overlayDialog != null && overlayDialog.isShowing()) overlayDialog.dismiss();
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        handleDrag(ev);
        return super.dispatchTouchEvent(ev);
    }

    /** 拖动跟随手指切换分类（滑动特效时）：列表跟着手指走，松手过阈值切到相邻分类，不足回弹。 */
    private void handleDrag(android.view.MotionEvent ev) {
        if (listScroll == null || contentBox == null) return;
        if (selecting || moveSourceId != null || movingCategory != null || switchingAnim) {
            dragTracking = false;
            dragClaimed = false;
            return;
        }
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN: {
                android.graphics.Rect r = new android.graphics.Rect();
                listScroll.getGlobalVisibleRect(r);
                if (!r.contains((int) ev.getRawX(), (int) ev.getRawY())) { dragTracking = false; return; }
                dragTracking = true;
                dragClaimed = false;
                dragStartX = ev.getRawX();
                dragStartY = ev.getRawY();
                dragDx = dragDy = 0;
                if (vTracker != null) vTracker.recycle();
                vTracker = android.view.VelocityTracker.obtain();
                vTracker.addMovement(ev);
                break;
            }
            case android.view.MotionEvent.ACTION_MOVE: {
                if (!dragTracking) return;
                if (vTracker != null) vTracker.addMovement(ev);
                dragDx = ev.getRawX() - dragStartX;
                dragDy = ev.getRawY() - dragStartY;
                int fx = sp.getInt(Palette.KEY_SWIPE_FX, 0);
                if (fx != 0) return; // 非滑动特效：不跟随，松手时按甩动判定
                if (!dragClaimed && Math.abs(dragDx) > Ui.dp(this, 22)
                        && Math.abs(dragDx) > Math.abs(dragDy) * 1.25f) {
                    dragClaimed = true;
                    contentBox.requestDisallowInterceptTouchEvent(true);
                }
                if (dragClaimed) {
                    java.util.List<Object[]> seq = filterSeq();
                    int cur = seqIndexOf(seq, filter, filterCategory);
                    boolean canPrev = cur > 0, canNext = cur < seq.size() - 1;
                    float tx = dragDx;
                    if ((dragDx < 0 && !canNext) || (dragDx > 0 && !canPrev)) tx = dragDx * 0.3f; // 到头了橡皮筋阻尼
                    contentBox.setTranslationX(tx);
                    float w = Math.max(1, listScroll.getWidth());
                    contentBox.setAlpha(1f - Math.min(0.25f, Math.abs(tx) / w * 0.5f));
                }
                break;
            }
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL: {
                if (!dragTracking) return;
                dragTracking = false;
                float vx = 0;
                if (vTracker != null) {
                    vTracker.addMovement(ev);
                    vTracker.computeCurrentVelocity(1000);
                    vx = vTracker.getXVelocity();
                    vTracker.recycle();
                    vTracker = null;
                }
                boolean up = ev.getActionMasked() == android.view.MotionEvent.ACTION_UP;
                int fx = sp.getInt(Palette.KEY_SWIPE_FX, 0);
                if (dragClaimed) {
                    dragClaimed = false;
                    java.util.List<Object[]> seq = filterSeq();
                    int cur = seqIndexOf(seq, filter, filterCategory);
                    boolean canPrev = cur > 0, canNext = cur < seq.size() - 1;
                    float w = Math.max(1, listScroll.getWidth());
                    if (up && canNext && (dragDx < -w * 0.22f || (vx < -900 && dragDx < -Ui.dp(this, 40)))) {
                        finishDragSwitch(1);
                    } else if (up && canPrev && (dragDx > w * 0.22f || (vx > 900 && dragDx > Ui.dp(this, 40)))) {
                        finishDragSwitch(-1);
                    } else {
                        contentBox.animate().translationX(0).alpha(1f).setDuration(160).start();
                    }
                } else if (up && Math.abs(dragDx) > Ui.dp(this, 64)
                        && Math.abs(dragDx) > Math.abs(dragDy) * 1.5f && Math.abs(vx) > 250) {
                    stepFilter(dragDx < 0 ? 1 : -1);
                }
                break;
            }
            default: break;
        }
    }

    /** 拖动松手后完成切换：当前列表继续滑出，新分类列表从另一侧滑入。 */
    private void finishDragSwitch(int dir) {
        java.util.List<Object[]> seq = filterSeq();
        int cur = seqIndexOf(seq, filter, filterCategory);
        int next = cur + dir;
        if (next < 0 || next >= seq.size()) {
            contentBox.animate().translationX(0).alpha(1f).setDuration(160).start();
            return;
        }
        final int f = (Integer) seq.get(next)[0];
        final String cat = (String) seq.get(next)[1];
        switchingAnim = true;
        float targetX = dir > 0 ? -listScroll.getWidth() * 0.55f : listScroll.getWidth() * 0.55f;
        contentBox.animate().translationX(targetX).alpha(0f).setDuration(150)
                .withEndAction(() -> {
                    filter = f;
                    filterCategory = cat;
                    listScroll.scrollTo(0, 0);
                    refresh();
                    contentBox.setTranslationX(dir > 0 ? Ui.dp(this, 56) : -Ui.dp(this, 56));
                    contentBox.setAlpha(0f);
                    contentBox.animate().translationX(0).alpha(1f).setDuration(200)
                            .withEndAction(() -> switchingAnim = false).start();
                }).start();
    }

    private void buildUi() {
        Lang.init(this);
        builtLangSetting = Lang.setting();
        FrameLayout root = new FrameLayout(this);
        bgRoot = root;
        root.setBackgroundColor(pal.background);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 6));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView appName = Ui.text(this, Lang.t("莫奈备忘录"), 26, pal.textPrimary, true);
        countText = Ui.text(this, "", 13, pal.textSecondary, false);
        titles.addView(appName);
        titles.addView(countText);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(topButton(Lang.t("排序"), v -> showSortDialog()));
        viewToggleBtn = topButton(sp.getBoolean(Palette.KEY_GRID, true) ? "☰" : "▦", v -> {
            boolean grid = !sp.getBoolean(Palette.KEY_GRID, true);
            sp.edit().putBoolean(Palette.KEY_GRID, grid).apply();
            viewToggleBtn.setText(grid ? "☰" : "▦");
            refresh();
        });
        header.addView(viewToggleBtn);
        header.addView(topButton("⚙️", v -> {
            startActivity(new Intent(this, SettingsActivity.class));
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        }));
        col.addView(header);

        // 搜索栏
        searchBox = new EditText(this);
        searchBox.setHint(Lang.t("搜索标题、正文或标签…"));
        searchBox.setHintTextColor(pal.textSecondary);
        searchBox.setTextColor(pal.textPrimary);
        searchBox.setTextSize(15);
        searchBox.setSingleLine(true);
        searchBox.setBackground(Ui.round(pal.surfaceVariant, Ui.dp(this, 24)));
        searchBox.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), 0);
        if (!query.isEmpty()) searchBox.setText(query);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48));
        slp.setMargins(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 4));
        col.addView(searchBox, slp);
        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { query = s.toString().trim(); refresh(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        // 筛选栏：左侧 + 为管理分类入口，固定不随 chips 横向滑动
        LinearLayout filterBar = new LinearLayout(this);
        filterBar.setOrientation(LinearLayout.HORIZONTAL);
        filterBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView manageBtn = Ui.text(this, "＋", 20, pal.primary, true);
        manageBtn.setGravity(Gravity.CENTER);
        manageBtn.setBackground(Ui.round(pal.surfaceVariant, Ui.dp(this, 16)));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
        mlp.setMargins(Ui.dp(this, 12), 0, 0, 0);
        manageBtn.setLayoutParams(mlp);
        manageBtn.setOnClickListener(v -> Categories.showManageDialog(this, () -> {
            if (filter == FILTER_CATEGORY && !Categories.load(this).contains(filterCategory)) filter = FILTER_ALL;
            movingCategory = null;
            refresh();
        }));
        filterBar.addView(manageBtn);
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        chipScroll = hsv;
        hsv.setHorizontalScrollBarEnabled(false);
        chipsBox = new LinearLayout(this);
        chipsBox.setOrientation(LinearLayout.HORIZONTAL);
        chipsBox.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        hsv.addView(chipsBox);
        filterBar.addView(hsv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView allTagsBtn = Ui.text(this, "⋯", 20, pal.primary, true);
        allTagsBtn.setGravity(Gravity.CENTER);
        allTagsBtn.setBackground(Ui.round(pal.surfaceVariant, Ui.dp(this, 16)));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(Ui.dp(this, 34), Ui.dp(this, 34));
        tlp.setMargins(0, 0, Ui.dp(this, 12), 0);
        allTagsBtn.setLayoutParams(tlp);
        allTagsBtn.setOnClickListener(v -> showAllTags());
        filterBar.addView(allTagsBtn);
        col.addView(filterBar);

        trashAction = Ui.text(this, Lang.t("🗑 清空回收站"), 14, 0xFFB3261E, true);
        trashAction.setGravity(Gravity.END);
        trashAction.setPadding(0, 0, Ui.dp(this, 20), Ui.dp(this, 4));
        trashAction.setVisibility(View.GONE);
        trashAction.setOnClickListener(v -> confirmEmptyTrash());
        col.addView(trashAction);

        // 内容
        ScrollView sv = new ScrollView(this);
        listScroll = sv;
        // 左右滑动切换分类的手势在 dispatchTouchEvent 里全局观察（见下方重写），
        // 这样从卡片上起手也不会被卡片的点击目标挡住。
        contentBox = new LinearLayout(this);
        contentBox.setOrientation(LinearLayout.VERTICAL);
        contentBox.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 12), Ui.dp(this, 96));
        sv.addView(contentBox);
        // 滚动时标记场景已变（浮层栏可见期间，PixelCopy 循环会据此取新帧）
        sv.setOnScrollChangeListener((v2, sx, sy, osx, osy) -> BgHelper.markSceneDirty());
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // FAB
        TextView fab = new TextView(this);
        fab.setText("＋");
        fab.setTextSize(26);
        fab.setTextColor(pal.onPrimary);
        fab.setGravity(Gravity.CENTER);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(pal.primary);
        fab.setBackground(circle);
        fab.setElevation(Ui.dp(this, 8));
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(Ui.dp(this, 60), Ui.dp(this, 60), Gravity.BOTTOM | Gravity.END);
        flp.setMargins(0, 0, Ui.dp(this, 20), Ui.dp(this, 24));
        Ui.elasticPress(fab);
        fab.setOnClickListener(v -> openEditor(null));
        fabView = fab;
        root.addView(fab, flp);

        // 浮层栏容器：多选栏/撤销栏不住主窗、改住独立浮窗——这样 PixelCopy 拷主窗
        // 成品帧时不会把栏自己拷进去，栏的玻璃才能透出其正下方的真实界面。
        if (overlayDialog != null) { try { overlayDialog.dismiss(); } catch (Exception ignored) {} overlayDialog = null; }
        overlayBox = new FrameLayout(this);
        overlayBox.setPadding(0, Ui.dp(this, 10), 0, 0); // 顶部留一点余量给栏的浮影

        // 撤销栏（删除类操作后短暂出现）
        undoBar = new LinearLayout(this);
        undoBar.setOrientation(LinearLayout.HORIZONTAL);
        undoBar.setGravity(Gravity.CENTER_VERTICAL);
        undoBar.setBackground(Ui.roundScene(pal.surfaceVariant, Ui.dp(this, 16)));
        undoBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10));
        undoBar.setElevation(Ui.dp(this, 6));
        undoMsg = Ui.text(this, "", 14, pal.textPrimary, false);
        undoBar.addView(undoMsg, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView undoBtn = Ui.text(this, Lang.t("撤销"), 14, pal.primary, true);
        undoBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        undoBtn.setOnClickListener(v -> {
            if (pendingUndo != null) pendingUndo.run();
            undoHandler.removeCallbacks(hideUndoTask);
            undoBar.setVisibility(View.GONE);
            pendingUndo = null;
            syncOverlayWindow();
        });
        undoBar.addView(undoBtn);
        FrameLayout.LayoutParams ulp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        ulp.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 96));
        undoBar.setVisibility(View.GONE);
        undoBar.setLayoutParams(ulp);
        overlayBox.addView(undoBar);

        // 多选操作栏
        selectionBar = new LinearLayout(this);
        selectionBar.setOrientation(LinearLayout.HORIZONTAL);
        selectionBar.setGravity(Gravity.CENTER_VERTICAL);
        selectionBar.setBackground(Ui.roundScene(pal.surface, Ui.dp(this, 20)));
        selectionBar.setElevation(Ui.dp(this, 10));
        selectionBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        selCountText = Ui.text(this, "", 14, pal.textPrimary, true);
        selectionBar.addView(selCountText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selectionBar.addView(selBarBtn(Lang.t("全选"), v -> { for (Note n : lastShown) selectedIds.add(n.id); refresh(); }));
        selectionBar.addView(selBarBtn(Lang.t("反选"), v -> {
            for (Note n : lastShown) {
                if (selectedIds.contains(n.id)) selectedIds.remove(n.id);
                else selectedIds.add(n.id);
            }
            if (selectedIds.isEmpty()) selecting = false;
            refresh();
        }));
        selectionBar.addView(selBarBtn(Lang.t("移动"), v -> batchMove()));
        selectionBar.addView(selBarBtn(Lang.t("复制"), v -> batchCopy()));
        selectionBar.addView(selBarBtn(Lang.t("删除"), v -> batchDelete()));
        selOrderBtn = selBarBtn(Lang.t("顺序"), v -> {
            if (selectedIds.size() == 1) {
                String id = selectedIds.iterator().next();
                exitSelection();
                moveSourceId = id;
                refresh();
                Toast.makeText(this, Lang.t("调整顺序：点另一条笔记，这条就移到它前面"), Toast.LENGTH_LONG).show();
            }
        });
        selectionBar.addView(selOrderBtn);
        selectionBar.addView(selBarBtn("✕", v -> { exitSelection(); refresh(); }));
        FrameLayout.LayoutParams sbp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        sbp.setMargins(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 20));
        selectionBar.setLayoutParams(sbp);
        selectionBar.setVisibility(View.GONE);
        overlayBox.addView(selectionBar);

        // 浮窗本体：全透明、不变暗、不抢焦点、不拦截窗外触摸（列表照常滚动/点按）
        overlayDialog = new android.app.Dialog(this, R.style.OverlayDialogTheme);
        overlayDialog.setContentView(overlayBox);
        overlayDialog.setCancelable(false);
        overlayDialog.setCanceledOnTouchOutside(false);
        android.view.Window ow = overlayDialog.getWindow();
        if (ow != null) {
            ow.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            ow.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            ow.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            android.view.WindowManager.LayoutParams olp = ow.getAttributes();
            olp.gravity = Gravity.BOTTOM;
            olp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            olp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            olp.dimAmount = 0f;
            ow.setAttributes(olp);
        }

        // 侧边快速滚动条：按住右侧滑块拖动即可快速上下滚动
        FastScroll.attach(this, root, sv, pal, 96);
        FastScroll.attachBackToTop(this, root, sv, pal, false, 24, 20);

        setContentView(root);
        BgHelper.apply(this, root, pal, null);
    }

    private TextView topButton(String label, View.OnClickListener cb) {
        TextView b = Ui.text(this, label, 14, pal.textPrimary, false);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.round(pal.surfaceVariant, Ui.dp(this, 18)));
        b.setPadding(Ui.dp(this, 10), Ui.dp(this, 7), Ui.dp(this, 10), Ui.dp(this, 7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(Ui.dp(this, 6), 0, 0, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(cb);
        return b;
    }

    private void refresh() {
        if (chipsBox == null) return;
        BgHelper.markSceneDirty();
        buildChips();
        List<Note> all = NoteStore.load(this);
        List<Note> shown = new ArrayList<>();
        Set<String> tagSet = new LinkedHashSet<>();
        int activeCount = 0;
        for (Note n : all) {
            for (String t : n.tags) tagSet.add(t);
            if (!n.trashed && !n.archived) activeCount++;
            boolean inFilter;
            switch (filter) {
                case FILTER_FAV: inFilter = n.favorite && !n.trashed; break;
                case FILTER_ARCHIVED: inFilter = n.archived && !n.trashed; break;
                case FILTER_TRASH: inFilter = n.trashed; break;
                case FILTER_TAG: inFilter = !n.trashed && n.tags.contains(filterTag); break;
                case FILTER_CATEGORY: inFilter = !n.trashed && filterCategory.equals(n.category); break;
                default: inFilter = !n.trashed && !n.archived; break;
            }
            if (!inFilter) continue;
            if (!query.isEmpty()) {
                StringBuilder haySb = new StringBuilder(n.title + "\n" + n.plainText() + "\n" + String.join(" ", n.tags) + "\n" + (n.category == null ? "" : n.category));
                for (Note.Attachment a : n.attachments) haySb.append("\n").append(a.name);
                String hay = haySb.toString().toLowerCase(Locale.ROOT);
                if (!hay.contains(query.toLowerCase(Locale.ROOT))) continue;
            }
            shown.add(n);
        }
        int sort = sp.getInt(Palette.KEY_SORT, 0);
        Collections.sort(shown, (a, b) -> {
            if (sort == 3) {
                int c = Long.compare(a.order, b.order);
                return c != 0 ? c : Long.compare(b.updatedAt, a.updatedAt);
            }
            if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
            if (sort == 1) return Long.compare(b.createdAt, a.createdAt);
            if (sort == 2) return a.title.compareToIgnoreCase(b.title);
            return Long.compare(b.updatedAt, a.updatedAt);
        });
        lastShown = shown;
        if (selecting) {
            java.util.Set<String> visible = new java.util.HashSet<>();
            for (Note n : shown) visible.add(n.id);
            selectedIds.retainAll(visible);
            if (selectedIds.isEmpty() || filter == FILTER_TRASH) selecting = false;
        }
        updateSelectionUI();

        countText.setText(Lang.f("%1$d 条笔记", activeCount));
        trashAction.setVisibility(filter == FILTER_TRASH && !shown.isEmpty() ? View.VISIBLE : View.GONE);

        contentBox.removeAllViews();
        if (shown.isEmpty()) {
            TextView empty = Ui.text(this, query.isEmpty() ? Lang.t("🗒\n\n这里还空空的\n点右下角 ＋ 写下第一条吧") : Lang.t("🔍\n\n没有找到匹配的笔记"), 16, pal.textSecondary, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(this, 80), 0, 0);
            contentBox.addView(empty);
            return;
        }
        boolean grid = sp.getBoolean(Palette.KEY_GRID, true);
        if (grid) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBaselineAligned(false);
            LinearLayout c1 = column();
            LinearLayout c2 = column();
            row.addView(c1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(c2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            contentBox.addView(row);
            for (int i = 0; i < shown.size(); i++) {
                View card = card(shown.get(i), true);
                Ui.animateIn(card, i);
                (i % 2 == 0 ? c1 : c2).addView(card);
            }
        } else {
            for (int i = 0; i < shown.size(); i++) {
                View card = card(shown.get(i), false);
                Ui.animateIn(card, i);
                contentBox.addView(card);
            }
        }
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
        return c;
    }

    /** 筛选顺序（与 chips 排列一致）：全部 → 收藏 → 各分类 → 归档 → 回收站。 */
    private java.util.List<Object[]> filterSeq() {
        java.util.List<Object[]> seq = new java.util.ArrayList<>();
        seq.add(new Object[]{FILTER_ALL, null});
        seq.add(new Object[]{FILTER_FAV, null});
        for (String c : Categories.load(this)) seq.add(new Object[]{FILTER_CATEGORY, c});
        seq.add(new Object[]{FILTER_ARCHIVED, null});
        seq.add(new Object[]{FILTER_TRASH, null});
        return seq;
    }

    private int seqIndexOf(java.util.List<Object[]> seq, int f, String cat) {
        for (int i = 0; i < seq.size(); i++) {
            int sf = (Integer) seq.get(i)[0];
            String sc = (String) seq.get(i)[1];
            if (sf == f && (f != FILTER_CATEGORY || (cat != null && cat.equals(sc)))) return i;
        }
        return f == FILTER_CATEGORY ? 2 : 0;
    }

    /** dir: +1 下一个（左滑），-1 上一个（右滑）。 */
    private void stepFilter(int dir) {
        if (selecting || moveSourceId != null || movingCategory != null || switchingAnim) return;
        java.util.List<Object[]> seq = filterSeq();
        int cur = seqIndexOf(seq, filter, filterCategory);
        int next = cur + dir;
        if (next < 0 || next >= seq.size()) return;
        applyFilterAnimated((Integer) seq.get(next)[0], (String) seq.get(next)[1], dir);
    }

    /** 点 chip / 总览选择时也走同一套过渡动画，方向按目标在序列中的位置决定。 */
    private void goToFilter(int f, String cat) {
        if (switchingAnim) return;
        java.util.List<Object[]> seq = filterSeq();
        int cur = seqIndexOf(seq, filter, filterCategory);
        int tgt = seqIndexOf(seq, f, cat);
        if (tgt == cur && f == filter) { refresh(); return; }
        applyFilterAnimated(f, cat, tgt > cur ? 1 : -1);
    }

    private void applyFilterAnimated(final int f, final String cat, int dir) {
        final int fx = sp.getInt(Palette.KEY_SWIPE_FX, 0);
        if (fx == 3) { // 无特效：直接切换
            filter = f;
            filterCategory = cat;
            if (listScroll != null) listScroll.scrollTo(0, 0);
            refresh();
            return;
        }
        switchingAnim = true;
        final float outX = fx == 0 ? -dir * Ui.dp(this, 48) : 0f;
        android.view.ViewPropertyAnimator out = contentBox.animate().alpha(0f).setDuration(110);
        if (fx == 0) out.translationX(outX);
        if (fx == 2) out.scaleX(0.94f).scaleY(0.94f);
        out.withEndAction(() -> {
            filter = f;
            filterCategory = cat;
            if (listScroll != null) listScroll.scrollTo(0, 0);
            refresh();
            contentBox.setTranslationX(fx == 0 ? -outX : 0f);
            if (fx == 2) contentBox.setScaleX(1.05f);
            if (fx == 2) contentBox.setScaleY(1.05f);
            contentBox.setAlpha(0f);
            android.view.ViewPropertyAnimator in = contentBox.animate().alpha(1f).setDuration(180);
            if (fx == 0) in.translationX(0);
            if (fx == 2) in.scaleX(1f).scaleY(1f);
            in.withEndAction(() -> {
                contentBox.setScaleX(1f);
                contentBox.setScaleY(1f);
                switchingAnim = false;
            }).start();
        }).start();
    }

    private void buildChips() {
        chipsBox.removeAllViews();
        selectedChipView = null;
        addChip(Lang.t("全部"), filter == FILTER_ALL, v -> goToFilter(FILTER_ALL, null));
        addChip(Lang.t("★ 收藏"), filter == FILTER_FAV, v -> goToFilter(FILTER_FAV, null));
        addChip(Lang.t("归档"), filter == FILTER_ARCHIVED, v -> goToFilter(FILTER_ARCHIVED, null));
        addChip(Lang.t("回收站"), filter == FILTER_TRASH, v -> goToFilter(FILTER_TRASH, null));
        for (String cat : Categories.load(this)) {
            final String c = cat;
            boolean isMoving = c.equals(movingCategory);
            TextView chip = addChip(isMoving ? "↕ " + Categories.label(this, c) : Categories.label(this, c),
                    isMoving || (filter == FILTER_CATEGORY && filterCategory.equals(c) && movingCategory == null),
                    v -> {
                        if (movingCategory != null) {
                            if (movingCategory.equals(c)) {
                                movingCategory = null;
                                refresh();
                            } else {
                                String mv = movingCategory;
                                movingCategory = null;
                                Categories.moveBefore(this, mv, c);
                                toast(Lang.f("分类「%1$s」已移动", mv));
                                refresh();
                            }
                            return;
                        }
                        goToFilter(FILTER_CATEGORY, c);
                    });
            chip.setTag("cat");
            chip.setOnLongClickListener(v -> {
                if (c.equals(movingCategory)) { movingCategory = null; refresh(); return true; }
                movingCategory = c;
                toast(Lang.f("移动分类「%1$s」：点另一个分类即可移到它前面，点它自己取消", c));
                refresh();
                return true;
            });
        }
        // 移动模式下点其它筛选 chip 先退出移动模式
        if (movingCategory != null) {
            // 其余 chip 的点击已在各自回调里处理；这里仅提示，不拦截
        }
        // 让选中 chip 始终可见（滑动切到靠后的分类时自动滚过去，免得高亮看不见）
        if (chipScroll != null && selectedChipView != null) {
            final View chip = selectedChipView;
            chipScroll.post(() -> chipScroll.smoothScrollTo(
                    Math.max(0, chip.getLeft() - Ui.dp(this, 56)), 0));
        }
    }

    private TextView addChip(String label, boolean selected, View.OnClickListener cb) {
        TextView c = Ui.text(this, label, 13, selected ? pal.onPrimary : pal.textPrimary, selected);
        c.setBackground(Ui.round(selected ? pal.primary : pal.surfaceVariant, Ui.dp(this, 16)));
        c.setPadding(Ui.dp(this, 13), Ui.dp(this, 7), Ui.dp(this, 13), Ui.dp(this, 7));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, Ui.dp(this, 8), 0);
        c.setLayoutParams(lp);
        if (selected) selectedChipView = c;
        // 移动模式中点非分类 chip 时先取消移动
        c.setOnClickListener(v -> {
            if (movingCategory != null && !"cat".equals(v.getTag())) movingCategory = null;
            cb.onClick(v);
        });
        chipsBox.addView(c);
        return c;
    }

    private View card(Note n, boolean grid) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        boolean moveSel = (moveSourceId != null && moveSourceId.equals(n.id))
                || (selecting && selectedIds.contains(n.id));
        card.setBackground(moveSel
                ? Ui.roundStroke(pal.cardColor(n), Ui.dp(this, 18), Ui.dp(this, 3), pal.primary)
                : Ui.roundStroke(pal.cardColor(n), Ui.dp(this, 18), Ui.dp(this, 1), pal.outline));
        card.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        card.setElevation(Ui.dp(this, pal.glass ? 6 : 2));
        if (pal.glass && android.os.Build.VERSION.SDK_INT >= 28) {
            card.setOutlineSpotShadowColor(0x73000000);
            card.setOutlineAmbientShadowColor(0x40000000);
        }
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, 0, Ui.dp(this, 10));
        card.setLayoutParams(clp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.TOP);
        if (selecting) {
            boolean sel = selectedIds.contains(n.id);
            TextView badge = Ui.text(this, sel ? "✓" : "", 14, pal.onPrimary, true);
            badge.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable bd = new android.graphics.drawable.GradientDrawable();
            bd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            if (sel) bd.setColor(pal.primary);
            else { bd.setColor(0x00000000); bd.setStroke(Ui.dp(this, 2), pal.outline); }
            badge.setBackground(bd);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26));
            blp.setMargins(0, Ui.dp(this, 1), Ui.dp(this, 10), 0);
            badge.setLayoutParams(blp);
            top.addView(badge);
        }
        TextView title = Ui.text(this, n.title.isEmpty() ? Lang.t("(无标题)") : n.title, 16, pal.textPrimary, true);
        title.setMaxLines(2);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView more = Ui.text(this, "⋮", 22, pal.textSecondary, true);
        more.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams moreLp = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        moreLp.setMargins(Ui.dp(this, 2), -Ui.dp(this, 10), -Ui.dp(this, 8), 0);
        more.setLayoutParams(moreLp);
        more.setOnClickListener(v -> showCardMenu(v, n));
        top.addView(more);
        card.addView(top);

        if (n.checklist) {
            int max = Math.min(n.items.size(), grid ? 4 : 3);
            for (int i = 0; i < max; i++) {
                Note.CheckItem it = n.items.get(i);
                TextView line = Ui.text(this, (it.done ? "☑ " : "☐ ") + it.text, 13, it.done ? pal.textSecondary : pal.textPrimary, false);
                line.setMaxLines(1);
                line.setPadding(0, Ui.dp(this, 3), 0, 0);
                card.addView(line);
            }
            if (!n.items.isEmpty()) {
                TextView prog = Ui.text(this, Lang.f("已完成 %1$d/%2$d", n.doneCount(), n.items.size()), 12, pal.primary, true);
                prog.setPadding(0, Ui.dp(this, 6), 0, 0);
                card.addView(prog);
            }
        } else if (!n.content.isEmpty()) {
            String preview = Markdown.strip(n.content);
            preview = preview.length() > 300 ? preview.substring(0, 300) + "…" : preview;
            TextView body = Ui.text(this, preview, 13, pal.textSecondary, false);
            body.setMaxLines(grid ? 7 : 3);
            body.setPadding(0, Ui.dp(this, 5), 0, 0);
            body.setLineSpacing(0, 1.25f);
            card.addView(body);
        }

        Note.Attachment firstImage = null;
        for (Note.Attachment a : n.attachments) if (a.isImage()) { firstImage = a; break; }
        if (firstImage != null) {
            Bitmap bm = Attachments.thumbnail(this, firstImage, 400);
            if (bm != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(bm);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, grid ? 96 : 130));
                ilp.setMargins(0, Ui.dp(this, 8), 0, Ui.dp(this, 2));
                iv.setLayoutParams(ilp);
                card.addView(iv);
            }
        }
        if (n.category != null && !n.category.isEmpty()) {
            TextView cg = Ui.text(this, "📁 " + n.category, 11, pal.primary, true);
            cg.setPadding(0, Ui.dp(this, 8), 0, 0);
            card.addView(cg);
        }
        if (!n.tags.isEmpty()) {
            TextView tg = Ui.text(this, "#" + String.join("  #", n.tags), 11, pal.primary, false);
            tg.setPadding(0, Ui.dp(this, 8), 0, 0);
            tg.setMaxLines(1);
            card.addView(tg);
        }

        String meta = new SimpleDateFormat(Lang.t("M月d日 HH:mm"), Locale.US).format(new Date(n.updatedAt));
        if (n.reminderAt > 0) meta += "   🔔 " + new SimpleDateFormat(Lang.t("M月d日 HH:mm"), Locale.US).format(new Date(n.reminderAt));
        if (n.pinned) meta = "📌 " + meta;
        if (n.favorite) meta += "   ★";
        if (!n.attachments.isEmpty()) meta += "   📎 " + n.attachments.size();
        TextView metaTv = Ui.text(this, meta, 11, pal.textSecondary, false);
        metaTv.setPadding(0, Ui.dp(this, 8), 0, 0);
        card.addView(metaTv);

        Ui.elasticPress(card);
        card.setOnClickListener(v -> {
            if (filter == FILTER_TRASH) { showCardMenu(v, n); return; }
            if (selecting) { toggleSelect(n); return; }
            if (moveSourceId != null) { onMoveTap(n); return; }
            openEditor(n.id);
        });
        card.setOnLongClickListener(v -> {
            if (filter == FILTER_TRASH) return false;
            if (!selecting) enterSelection(n);
            return true;
        });
        return card;
    }

    private TextView selBarBtn(String label, View.OnClickListener cb) {
        TextView b = Ui.text(this, label, 13, pal.primary, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(Ui.dp(this, 9), Ui.dp(this, 8), Ui.dp(this, 9), Ui.dp(this, 8));
        b.setOnClickListener(cb);
        return b;
    }

    private void updateSelectionUI() {
        if (selectionBar == null) return;
        boolean barWasGone = selectionBar.getVisibility() != View.VISIBLE;
        selectionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (selecting && barWasGone) {
            // 多选栏入场弹簧（对标 Salt 迷你播放条的弹性交互）
            selectionBar.setScaleX(0.94f);
            selectionBar.setScaleY(0.88f);
            selectionBar.setAlpha(0.5f);
            selectionBar.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(400)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2.0f)).start();
        }
        if (fabView != null) fabView.setVisibility(selecting ? View.GONE : View.VISIBLE);
        if (selecting) {
            selCountText.setText(Lang.f("已选 %1$d 条", selectedIds.size()));
            selOrderBtn.setVisibility(selectedIds.size() == 1 ? View.VISIBLE : View.GONE);
        }
        syncOverlayWindow();
    }

    /** 任一浮层栏可见就显示浮窗并启动场景拷贝循环；都隐藏则关窗停循环。 */
    private void syncOverlayWindow() {
        if (overlayDialog == null) return;
        boolean need = (selectionBar != null && selectionBar.getVisibility() == View.VISIBLE)
                || (undoBar != null && undoBar.getVisibility() == View.VISIBLE);
        if (need) {
            if (!overlayDialog.isShowing() && !isFinishing()) {
                try {
                    overlayDialog.show();
                    android.view.Window w = overlayDialog.getWindow();
                    if (w != null) w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                } catch (Exception ignored) {}
            }
            BgHelper.startSceneLoop(this);
        } else {
            if (overlayDialog.isShowing()) {
                try { overlayDialog.dismiss(); } catch (Exception ignored) {}
            }
            BgHelper.stopSceneLoop();
        }
    }

    private void enterSelection(Note n) {
        selecting = true;
        moveSourceId = null;
        movingCategory = null;
        selectedIds.add(n.id);
        refresh();
        Toast.makeText(this, Lang.t("多选模式：点卡片勾选，底部可批量移动 / 复制 / 删除"), Toast.LENGTH_SHORT).show();
    }

    private void exitSelection() {
        selecting = false;
        selectedIds.clear();
        updateSelectionUI();
    }

    private void toggleSelect(Note n) {
        if (selectedIds.contains(n.id)) selectedIds.remove(n.id);
        else selectedIds.add(n.id);
        if (selectedIds.isEmpty()) selecting = false;
        refresh();
    }

    private java.util.List<Note> selectedNotes() {
        java.util.List<Note> out = new java.util.ArrayList<>();
        for (Note n : NoteStore.load(this)) if (selectedIds.contains(n.id)) out.add(n);
        return out;
    }

    private void batchMove() {
        java.util.List<Note> targets = selectedNotes();
        if (targets.isEmpty()) return;
        java.util.List<String> cats = Categories.load(this);
        String[] labels = new String[cats.size() + 1];
        labels[0] = Lang.t("未分类");
        for (int i = 0; i < cats.size(); i++) labels[i + 1] = Categories.label(this, cats.get(i));
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(Lang.f("把 %1$d 条笔记移动到", targets.size()))
                .setItems(labels, (d, which) -> {
                    String target = which == 0 ? "" : cats.get(which - 1);
                    for (Note n : targets) n.category = target;
                    NoteStore.save(this);
                    exitSelection();
                    refresh();
                    Toast.makeText(this, Lang.f("已移动 %1$d 条笔记到「%2$s」", targets.size(), target.isEmpty() ? Lang.t("未分类") : target), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(Lang.t("取消"), null)
                .show();
        Ui.styleDialog(this, dlg);
    }

    private void batchCopy() {
        java.util.List<Note> targets = selectedNotes();
        if (targets.isEmpty()) return;
        for (Note n : targets) NoteStore.duplicate(this, n, null, true);
        exitSelection();
        refresh();
        Toast.makeText(this, Lang.f("已复制 %1$d 条", targets.size()), Toast.LENGTH_SHORT).show();
    }

    private void batchDelete() {
        java.util.List<Note> targets = selectedNotes();
        if (targets.isEmpty()) return;
        UndoEntry snap = capture(targets);
        for (Note n : targets) {
            n.trashed = true;
            n.pinned = false;
            ReminderManager.sync(this, n);
        }
        NoteStore.save(this);
        int count = targets.size();
        exitSelection();
        refresh();
        showUndo(Lang.f("已将 %1$d 条笔记移到回收站", count), snap);
    }

    /** 标签栏右端「⋯」：总览全部分类（带条数），点任意一项即按它筛选。 */
    private void showAllTags() {
        java.util.Map<String, Integer> catCounts = new java.util.HashMap<>();
        for (Note n : NoteStore.load(this)) {
            if (n.trashed) continue;
            if (n.category != null && !n.category.isEmpty())
                catCounts.put(n.category, catCounts.getOrDefault(n.category, 0) + 1);
        }
        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(this, 20), Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 10));

        TextView catHead = Ui.text(this, Lang.t("分类"), 13, pal.primary, true);
        catHead.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        box.addView(catHead);
        java.util.List<String> cats = Categories.load(this);
        if (cats.isEmpty()) {
            TextView none = Ui.text(this, Lang.t("暂无分类"), 14, pal.textSecondary, false);
            none.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 6));
            box.addView(none);
        }
        for (String cname : cats) {
            final String cn = cname;
            TextView row = Ui.text(this, Categories.label(this, cn) + " · " + catCounts.getOrDefault(cn, 0) + Lang.t(" 条"),
                    15, pal.textPrimary, false);
            row.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            row.setOnClickListener(v -> {
                movingCategory = null;
                if (holder[0] != null) holder[0].dismiss();
                goToFilter(FILTER_CATEGORY, cn);
            });
            box.addView(row);
        }

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(box);
        holder[0] = new AlertDialog.Builder(this)
                .setTitle(Lang.t("全部分类"))
                .setView(scroll)
                .setNegativeButton(Lang.t("关闭"), null)
                .show();
        Ui.styleDialog(this, holder[0]);
    }

    /** 长按移动模式下点中目标笔记：把源笔记移到目标之前，并切换为手动排序。 */
    private void onMoveTap(Note target) {
        if (target.id.equals(moveSourceId)) { moveSourceId = null; refresh(); return; }
        Note src = null;
        for (Note n : lastShown) if (n.id.equals(moveSourceId)) src = n;
        if (src == null) { moveSourceId = null; refresh(); return; }
        java.util.List<Note> L = new java.util.ArrayList<>(lastShown);
        L.remove(src);
        int idx = L.indexOf(target);
        L.add(Math.max(0, idx), src);
        for (int i = 0; i < L.size(); i++) L.get(i).order = (long) (i + 1) * 1024;
        boolean switched = sp.getInt(Palette.KEY_SORT, 0) != 3;
        if (switched) sp.edit().putInt(Palette.KEY_SORT, 3).apply();
        moveSourceId = null;
        NoteStore.save(this);
        refresh();
        Toast.makeText(this, switched ? Lang.t("已移动，并切换为手动排序") : Lang.t("已移动"), Toast.LENGTH_SHORT).show();
    }

    /** 选择目标分类：copy=false 移动本条，copy=true 复制一份过去。 */
    private void pickCategory(Note n, boolean copy) {
        java.util.List<String> cats = Categories.load(this);
        String[] labels = new String[cats.size() + 1];
        labels[0] = Lang.t("未分类");
        for (int i = 0; i < cats.size(); i++) labels[i + 1] = Categories.label(this, cats.get(i));
        int checked = 0;
        for (int i = 0; i < cats.size(); i++) if (cats.get(i).equals(n.category)) checked = i + 1;
        AlertDialog catDlg = new AlertDialog.Builder(this)
                .setTitle(copy ? Lang.t("复制到分类") : Lang.t("移动到分类"))
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    String target = which == 0 ? "" : cats.get(which - 1);
                    d.dismiss();
                    if (copy) {
                        NoteStore.duplicate(this, n, target, false);
                        refresh();
                        Toast.makeText(this, Lang.f("已复制到「%1$s」", target.isEmpty() ? Lang.t("未分类") : target), Toast.LENGTH_SHORT).show();
                    } else {
                        n.category = target;
                        touch(n);
                        Toast.makeText(this, Lang.f("已移动到「%1$s」", target.isEmpty() ? Lang.t("未分类") : target), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(Lang.t("取消"), null)
                .show();
        Ui.styleDialog(this, catDlg);
    }

    private void editTags(Note n) {
        TagEditor.show(this, pal, n.tags, tags -> {
            n.tags.clear();
            n.tags.addAll(tags);
            touch(n);
        });
    }

    private void showCardMenu(View anchor, Note n) {
        PopupMenu menu = new PopupMenu(this, anchor);
        if (n.trashed) {
            menu.getMenu().add(0, 10, 0, Lang.t("恢复"));
            menu.getMenu().add(0, 11, 0, Lang.t("彻底删除"));
        } else {
            menu.getMenu().add(0, 1, 0, n.pinned ? Lang.t("取消置顶") : Lang.t("置顶"));
            menu.getMenu().add(0, 2, 0, n.favorite ? Lang.t("取消收藏") : Lang.t("收藏"));
            menu.getMenu().add(0, 3, 0, n.archived ? Lang.t("取消归档") : Lang.t("归档"));
            menu.getMenu().add(0, 7, 0, Lang.t("笔记内搜索"));
            menu.getMenu().add(0, 12, 0, Lang.t("移动到分类…"));
            menu.getMenu().add(0, 13, 0, Lang.t("复制到分类…"));
            menu.getMenu().add(0, 14, 0, Lang.t("复制笔记"));
            menu.getMenu().add(0, 15, 0, Lang.t("编辑标签…"));
            menu.getMenu().add(0, 16, 0, Lang.t("多选"));
            menu.getMenu().add(0, 4, 0, Lang.t("分享"));
            menu.getMenu().add(0, 17, 0, Lang.t("导出…"));
            menu.getMenu().add(0, 5, 0, Lang.t("复制正文"));
            menu.getMenu().add(0, 6, 0, Lang.t("移到回收站"));
        }
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: n.pinned = !n.pinned; break;
                case 2: n.favorite = !n.favorite; break;
                case 3: {
                    UndoEntry snap = capture(java.util.Collections.singletonList(n));
                    n.archived = !n.archived;
                    touch(n);
                    showUndo(n.archived ? Lang.t("已归档") : Lang.t("已取消归档"), snap);
                    return true;
                }
                case 7: {
                    Intent si = new Intent(this, EditorActivity.class);
                    si.putExtra("note_id", n.id);
                    si.putExtra("open_search", true);
                    startActivity(si);
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
                    return true;
                }
                case 12: pickCategory(n, false); return true;
                case 13: pickCategory(n, true); return true;
                case 14: {
                    NoteStore.duplicate(this, n, null, true);
                    refresh();
                    Toast.makeText(this, Lang.t("已复制一份"), Toast.LENGTH_SHORT).show();
                    return true;
                }
                case 15: editTags(n); return true;
                case 16: enterSelection(n); return true;
                case 4: share(n); return true;
                case 17: Exporter.showExport(this, n, pal); return true;
                case 5: copy(n); return true;
                case 6: {
                    UndoEntry snap = capture(java.util.Collections.singletonList(n));
                    n.trashed = true; n.pinned = false;
                    touch(n);
                    showUndo(Lang.t("已移到回收站"), snap);
                    return true;
                }
                case 10: {
                    UndoEntry snap = capture(java.util.Collections.singletonList(n));
                    n.trashed = false;
                    touch(n);
                    showUndo(Lang.t("已恢复到全部笔记"), snap);
                    return true;
                }
                case 11: confirmDeleteForever(n); return true;
                default: return false;
            }
            touch(n);
            return true;
        });
        menu.show();
    }

    private void touch(Note n) {
        NoteStore.save(this);
        ReminderManager.sync(this, n);
        refresh();
    }

    private void confirmDeleteForever(Note n) {
        new AlertDialog.Builder(this)
                .setTitle(Lang.t("彻底删除？"))
                .setMessage(Lang.t("删除后无法恢复。"))
                .setNegativeButton(Lang.t("取消"), null)
                .setPositiveButton(Lang.t("删除"), (d, w) -> {
                    UndoEntry snap = capture(java.util.Collections.singletonList(n));
                    ReminderManager.cancel(this, n);
                    NoteStore.load(this).remove(n);
                    NoteStore.save(this);
                    refresh();
                    showUndo(Lang.t("已彻底删除 1 条笔记"), snap);
                }).show();
    }

    private void confirmEmptyTrash() {
        new AlertDialog.Builder(this)
                .setTitle(Lang.t("清空回收站？"))
                .setMessage(Lang.t("回收站里的笔记将被彻底删除。删除后可以点「撤销」恢复，撤销栏消失后将无法恢复。"))
                .setNegativeButton(Lang.t("取消"), null)
                .setPositiveButton(Lang.t("清空"), (d, w) -> {
                    List<Note> all = NoteStore.load(this);
                    List<Note> trashedNotes = new ArrayList<>();
                    List<Note> keep = new ArrayList<>();
                    for (Note n : all) {
                        if (n.trashed) { ReminderManager.cancel(this, n); trashedNotes.add(n); }
                        else keep.add(n);
                    }
                    UndoEntry snap = capture(trashedNotes);
                    int count = trashedNotes.size();
                    all.clear();
                    all.addAll(keep);
                    NoteStore.save(this);
                    refresh();
                    if (count > 0) showUndo(Lang.f("已清空回收站（%1$d 条）", count), snap);
                }).show();
    }

    // ---------- 撤销 ----------
    private static class UndoEntry {
        final List<String> jsons = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();
    }

    private UndoEntry capture(List<Note> targets) {
        List<Note> all = NoteStore.load(this);
        UndoEntry e = new UndoEntry();
        for (Note t : targets) {
            e.jsons.add(t.toJsonString());
            e.indices.add(all.indexOf(t));
        }
        return e;
    }

    private void showUndo(String msg, UndoEntry snap) {
        if (undoBar == null) return;
        undoMsg.setText(msg);
        pendingUndo = () -> {
            List<Note> all = NoteStore.load(this);
            for (int i = 0; i < snap.jsons.size(); i++) {
                Note o = Note.fromJsonString(snap.jsons.get(i));
                if (o == null) continue;
                Note existing = NoteStore.find(o.id);
                if (existing != null) existing.copyFrom(o);
                else all.add(Math.min(Math.max(snap.indices.get(i), 0), all.size()), o);
                ReminderManager.sync(this, o);
            }
            NoteStore.save(this);
            refresh();
            toast(Lang.t("已撤销"));
        };
        undoBar.setVisibility(View.VISIBLE);
        undoHandler.removeCallbacks(hideUndoTask);
        undoHandler.postDelayed(hideUndoTask, 6000);
        syncOverlayWindow();
    }

    private void share(Note n) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, n.title);
        i.putExtra(Intent.EXTRA_TEXT, n.plainText());
        startActivity(Intent.createChooser(i, Lang.t("分享笔记")));
    }

    private void copy(Note n) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("note", n.plainText()));
        toast(Lang.t("已复制"));
    }

    private void openEditor(String id) {
        Intent i = new Intent(this, EditorActivity.class);
        if (id != null) {
            i.putExtra("note_id", id);
        } else {
            // 在某个分类/标签筛选下新建笔记时，自动归入该分类/带上该标签
            if (filter == FILTER_CATEGORY && !filterCategory.isEmpty()) i.putExtra("preset_category", filterCategory);
            if (filter == FILTER_TAG && !filterTag.isEmpty()) i.putExtra("preset_tag", filterTag);
        }
        startActivity(i);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private void showSortDialog() {
        String[] labels = {Lang.t("按更新时间"), Lang.t("按创建时间"), Lang.t("按标题"), Lang.t("手动排序（长按多选后点「顺序」调整）")};
        AlertDialog sortDlg = new AlertDialog.Builder(this)
                .setTitle(Lang.t("排序方式"))
                .setSingleChoiceItems(labels, sp.getInt(Palette.KEY_SORT, 0), (d, which) -> {
                    sp.edit().putInt(Palette.KEY_SORT, which).apply();
                    d.dismiss();
                    refresh();
                }).show();
        Ui.styleDialog(this, sortDlg);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
