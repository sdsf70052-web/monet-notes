package com.muse.monetnotes;

import android.content.Context;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 轻量多语言：以简体中文原文为 key 查表。
 * 语言选项存 KEY_LANG：0 跟随系统 / 1 简体中文 / 2 繁體中文 / 3 English。
 * t() 查不到译文时回退原文（简体），保证永不缺字。
 */
public class Lang {
    public static final String KEY_LANG = "lang";

    public static final int EFF_ZH = 1, EFF_TW = 2, EFF_EN = 3;
    private static int eff = EFF_ZH;
    private static int setting = 0;

    public static void init(Context ctx) {
        setting = ctx.getSharedPreferences(Palette.PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_LANG, 0);
        eff = resolve(setting);
    }

    public static int setting() { return setting; }

    public static int resolve(int s) {
        if (s == 1) return EFF_ZH;
        if (s == 2) return EFF_TW;
        if (s == 3) return EFF_EN;
        Locale loc = Locale.getDefault();
        String lang = loc.getLanguage();
        if ("zh".equals(lang)) {
            String c = loc.getCountry();
            return ("TW".equals(c) || "HK".equals(c) || "MO".equals(c)) ? EFF_TW : EFF_ZH;
        }
        return EFF_EN;
    }

    public static boolean isEn() { return eff == EFF_EN; }

    public static String t(String zh) {
        if (eff == EFF_ZH || zh == null) return zh;
        String[] v = DICT.get(zh);
        if (v == null) return zh;
        return eff == EFF_TW ? v[0] : v[1];
    }

    /** 带占位符的文案：先翻译格式串再填参数。 */
    public static String f(String zhFormat, Object... args) {
        return String.format(Locale.US, t(zhFormat), args);
    }

    private static final Map<String, String[]> DICT = new HashMap<>();

    private static void d(String zh, String tw, String en) { DICT.put(zh, new String[]{tw, en}); }

    static {
        // ---- 通用 ----
        d("取消", "取消", "Cancel");
        d("确定", "確定", "OK");
        d("保存", "儲存", "Save");
        d("删除", "刪除", "Delete");
        d("关闭", "關閉", "Close");
        d("创建", "建立", "Create");
        d("添加", "新增", "Add");
        d("撤销", "復原", "Undo");
        d("全部", "全部", "All");
        d("无", "無", "None");
        d("分类", "分類", "Category");
        d("未分类", "未分類", "Uncategorized");
        d("M月d日 HH:mm", "M月d日 HH:mm", "MMM d, HH:mm");
        d("yyyy年M月d日 HH:mm", "yyyy年M月d日 HH:mm", "MMM d, yyyy HH:mm");

        // ---- 主页 ----
        d("莫奈备忘录", "莫奈備忘錄", "Monet Notes");
        d("搜索标题、正文或标签…", "搜尋標題、正文或標籤…", "Search title, content or tags…");
        d("＋ 新建", "＋ 新建", "＋ New");
        d("收藏", "收藏", "Favorites");
        d("★ 收藏", "★ 收藏", "★ Favorite");
        d("归档", "歸檔", "Archive");
        d("回收站", "回收桶", "Trash");
        d("全部分类", "全部分類", "All Categories");
        d("暂无分类", "暫無分類", "No categories");
        d("🗒\n\n这里还空空的\n点右下角 ＋ 写下第一条吧", "🗒\n\n這裡還空空的\n點右下角 ＋ 寫下第一條吧", "🗒\n\nNothing here yet\nTap ＋ at the bottom right to write your first note");
        d("🔍\n\n没有找到匹配的笔记", "🔍\n\n沒有找到符合的筆記", "🔍\n\nNo matching notes");
        d("(无标题)", "(無標題)", "(Untitled)");
        d("排序方式", "排序方式", "Sort by");
        d("排序", "排序", "Sort");
        d("按更新时间", "依更新時間", "By updated time");
        d("按创建时间", "依建立時間", "By created time");
        d("按标题", "依標題", "By title");
        d("手动排序（长按多选后点「顺序」调整）", "手動排序（長按多選後點「順序」調整）", "Manual (multi-select, then tap Order)");
        d("手动排序", "手動排序", "Manual");
        d("置顶", "置頂", "Pin");
        d("取消置顶", "取消置頂", "Unpin");
        d("取消收藏", "取消收藏", "Remove from favorites");
        d("取消归档", "取消歸檔", "Unarchive");
        d("恢复", "還原", "Restore");
        d("彻底删除", "永久刪除", "Delete forever");
        d("彻底删除？", "永久刪除？", "Delete forever?");
        d("删除后无法恢复。", "刪除後無法還原。", "This cannot be undone.");
        d("移到回收站", "移到回收桶", "Move to trash");
        d("分享", "分享", "Share");
        d("分享笔记", "分享筆記", "Share note");
        d("复制正文", "複製正文", "Copy text");
        d("复制笔记", "複製筆記", "Duplicate note");
        d("复制到分类…", "複製到分類…", "Copy to category…");
        d("移动到分类…", "移動到分類…", "Move to category…");
        d("移动到分类", "移動到分類", "Move to Category");
        d("复制到分类", "複製到分類", "Copy to Category");
        d("选择分类", "選擇分類", "Select category");
        d("编辑标签…", "編輯標籤…", "Edit tags…");
        d("笔记内搜索", "筆記內搜尋", "Find in note");
        d("多选", "多選", "Multi-select");
        d("全选", "全選", "Select all");
        d("反选", "反選", "Invert");
        d("移动", "移動", "Move");
        d("复制", "複製", "Copy");
        d("顺序", "順序", "Order");
        d("已选 %1$d 条", "已選 %1$d 條", "%1$d selected");
        d("多选模式：点卡片勾选，底部可批量移动 / 复制 / 删除", "多選模式：點卡片勾選，底部可批次移動 / 複製 / 刪除", "Multi-select: tap cards to select; move / copy / delete in bulk below");
        d("调整顺序：点另一条笔记，这条就移到它前面", "調整順序：點另一條筆記，這條就移到它前面", "Reorder: tap another note to move this one before it");
        d("已移到回收站", "已移到回收桶", "Moved to trash");
        d("已彻底删除 1 条笔记", "已永久刪除 1 條筆記", "Deleted permanently");
        d("已归档", "已歸檔", "Archived");
        d("已取消归档", "已取消歸檔", "Unarchived");
        d("已恢复到全部笔记", "已還原到全部筆記", "Restored to All Notes");
        d("已撤销", "已復原", "Undone");
        d("已复制一份", "已複製一份", "Duplicated");
        d("已移动，并切换为手动排序", "已移動，並切換為手動排序", "Moved; switched to manual order");
        d("已将 %1$d 条笔记移到回收站", "已將 %1$d 條筆記移到回收桶", "%1$d notes moved to trash");
        d("已移动 %1$d 条笔记到「%2$s」", "已移動 %1$d 條筆記到「%2$s」", "Moved %1$d notes to “%2$s”");
        d("已复制 %1$d 条笔记到「%2$s」", "已複製 %1$d 條筆記到「%2$s」", "Copied %1$d notes to “%2$s”");
        d("已清空回收站（%1$d 条）", "已清空回收桶（%1$d 條）", "Trash emptied (%1$d)");
        d("清空回收站？", "清空回收桶？", "Empty trash?");
        d("回收站里的笔记将被彻底删除。删除后可以点「撤销」恢复，撤销栏消失后将无法恢复。", "回收桶裡的筆記將被永久刪除。刪除後可以點「復原」恢復，復原欄消失後將無法還原。", "Notes in trash will be permanently deleted. You can tap Undo to restore them until the undo bar disappears.");
        d("清空", "清空", "Empty");
        d("🗑 清空回收站", "🗑 清空回收桶", "🗑 Empty Trash");
        d("移动分类「%1$s」：点另一个分类即可移到它前面，点它自己取消", "移動分類「%1$s」：點另一個分類即可移到它前面，點它自己取消", "Moving category “%1$s”: tap another category to place it before, tap itself to cancel");

        d("%1$d 条笔记", "%1$d 條筆記", "%1$d notes");
        d("分类「%1$s」已移动", "分類「%1$s」已移動", "Category “%1$s” moved");
        d("把 %1$d 条笔记移动到", "把 %1$d 條筆記移動到", "Move %1$d notes to");
        d("已复制 %1$d 条", "已複製 %1$d 條", "Duplicated %1$d notes");
        d("已移动", "已移動", "Moved");
        d("已复制到「%1$s」", "已複製到「%1$s」", "Copied to “%1$s”");
        d("已移动到「%1$s」", "已移動到「%1$s」", "Moved to “%1$s”");
        d(" 条", " 條", " notes");

        d("已复制", "已複製", "Copied");
        d("改名", "改名", "Rename");

        // ---- 编辑页 ----
        d("标题", "標題", "Title");
        d("写点什么…", "寫點什麼…", "Write something…");
        d("新建笔记", "新建筆記", "New Note");
        d("编辑笔记", "編輯筆記", "Edit Note");
        d("新笔记", "新筆記", "New note");
        d("条目内容", "條目內容", "Item text");
        d("＋ 添加条目", "＋ 新增條目", "＋ Add item");
        d("清单模式", "清單模式", "Checklist");
        d("🎨 颜色", "🎨 顏色", "🎨 Color");
        d("🏷 标签", "🏷 標籤", "🏷 Tags");
        d("📁 分类", "📁 分類", "📁 Category");
        d("🔔 提醒", "🔔 提醒", "🔔 Reminder");
        d("附件", "附件", "Attachment");
        d("🖼 图片", "🖼 圖片", "🖼 Image");
        d("📄 文档", "📄 文件", "📄 Document");
        d("📎 文件", "📎 檔案", "📎 File");
        d("提醒时间", "提醒時間", "Reminder time");
        d("清除提醒", "清除提醒", "Clear reminder");
        d("重新设置", "重新設定", "Reschedule");
        d("当前提醒：%1$s", "目前提醒：%1$s", "Current: %1$s");
        d("\n\n到这个时间会发送系统通知；点通知可回到应用。", "\n\n到這個時間會發送系統通知；點通知可回到應用程式。", "\n\nA system notification will be sent at this time; tap it to return to the app.");
        d("提醒已设置", "提醒已設定", "Reminder set");
        d("提醒已设置，到时会通知你", "提醒已設定，到時會通知你", "Reminder set — we'll notify you");
        d("提醒已清除", "提醒已清除", "Reminder cleared");
        d("笔记颜色", "筆記顏色", "Note color");
        d("预设颜色", "預設顏色", "Preset colors");
        d("自定义颜色…", "自訂顏色…", "Custom color…");
        d("自定义颜色", "自訂顏色", "Custom color");
        d("默认（跟随主题）", "預設（跟隨主題）", "Default (follow theme)");
        d("使用此颜色", "使用此顏色", "Use this color");
        d("色相", "色相", "Hue");
        d("饱和度", "飽和度", "Saturation");
        d("明度", "明度", "Brightness");
        d("用十六进制值设置", "用十六進位值設定", "Set by hex value");
        d("颜色值格式不对，如 #FF8800", "顏色值格式不對，如 #FF8800", "Invalid color value, e.g. #FF8800");
        d("颜色已更新，返回列表查看效果", "顏色已更新，返回列表查看效果", "Color updated — check the list to see it");
        d("自定义颜色已应用，返回列表查看效果", "自訂顏色已套用，返回列表查看效果", "Custom color applied — check the list to see it");
        d("已清除颜色标记", "已清除顏色標記", "Color mark cleared");
        d("分类已更新", "分類已更新", "Category updated");
        d("标签已更新", "標籤已更新", "Tags updated");
        d("已自动保存 ✓", "已自動儲存 ✓", "Auto-saved ✓");
        d("已修改", "已修改", "Edited");
        d("创建于 ", "建立於 ", "Created ");
        d("正在打开长笔记…", "正在開啟長筆記…", "Loading long note…");
        d("正文还在加载中，稍等一下", "正文還在載入中，稍等一下", "Content is still loading…");
        d("已移到回收站，可在主页撤销", "已移到回收桶，可在主頁復原", "Moved to trash — undo on the home page");
        d("附件已移除，可点 ↩ 撤销", "附件已移除，可點 ↩ 復原", "Attachment removed — tap ↩ to undo");
        d("附件导入失败", "附件匯入失敗", "Failed to import attachment");
        d("没有可用的文件选择器", "沒有可用的檔案選擇器", "No file picker available");
        d("没有可撤销的操作", "沒有可復原的操作", "Nothing to undo");
        d("没有可重做的操作", "沒有可重做的操作", "Nothing to redo");
        d("已重做", "已重做", "Redone");
        d("已置顶", "已置頂", "Pinned");
        d("已取消置顶", "已取消置頂", "Unpinned");
        d("在笔记中搜索…", "在筆記中搜尋…", "Find in note…");
        d("替换为…", "取代為…", "Replace with…");
        d("替换", "取代", "Replace");
        d("替换当前", "取代目前", "Replace one");
        d("全部替换", "全部取代", "Replace all");
        d("没有找到", "沒有找到", "Not found");
        d("先搜索并定位到一处", "先搜尋並定位到一處", "Find a match first");
        d("已替换 1 处", "已取代 1 處", "Replaced 1");
        d("已全部替换 %1$d 处", "已全部取代 %1$d 處", "Replaced all %1$d");
        d("没有可替换的内容", "沒有可取代的內容", "Nothing to replace");
        d("共 %1$d 处", "共 %1$d 處", "%1$d matches");
        d("%1$d 处，可点 ↩ 撤销", "%1$d 處，可點 ↩ 復原", "%1$d replaced — tap ↩ to undo");
        d("共 %1$d 字 · %2$d 个条目", "共 %1$d 字 · %2$d 個條目", "%1$d chars · %2$d items");
        d("共 %1$d 字 · %2$d 个附件", "共 %1$d 字 · %2$d 個附件", "%1$d chars · %2$d attachments");
        d("共 %1$d 字", "共 %1$d 字", "%1$d chars");
        d("红", "紅", "Red");
        d("橙", "橙", "Orange");
        d("黄", "黃", "Yellow");
        d("绿", "綠", "Green");
        d("青", "青", "Cyan");
        d("蓝", "藍", "Blue");
        d("紫", "紫", "Purple");
        d("粉", "粉", "Pink");

        d("清单模式 ✓", "清單模式 ✓", "Checklist ✓");
        d("清除颜色标记", "清除顏色標記", "Clear color mark");
        d("正在打开长笔记… %1$d%%", "正在開啟長筆記… %1$d%%", "Loading long note… %1$d%%");
        d("已添加 %1$d 个附件", "已新增 %1$d 個附件", "Added %1$d attachments");
        d("已全部替换 %1$d 处，可点 ↩ 撤销", "已全部取代 %1$d 處，可點 ↩ 復原", "Replaced %1$d — tap ↩ to undo");
        d("已完成 %1$d/%2$d", "已完成 %1$d/%2$d", "Done %1$d/%2$d");
        d("共 %1$d 个条目", "共 %1$d 個條目", "%1$d items");
        d("\n🔔 提醒：", "\n🔔 提醒：", "\n🔔 Reminder: ");
        d(" · 点按打开", " · 點按開啟", " · tap to open");

        d("滑动切换特效", "滑動切換特效", "Swipe transition");
        d("滑动", "滑動", "Slide");
        d("淡入淡出", "淡入淡出", "Fade");
        d("缩放", "縮放", "Zoom");
        d("无特效", "無特效", "None");

        // ---- 设置页 ----
        d("设置", "設定", "Settings");
        d("外观", "外觀", "Appearance");
        d("语言", "語言", "Language");
        d("外观模式", "外觀模式", "Theme mode");
        d("跟随系统", "跟隨系統", "System");
        d("浅色", "淺色", "Light");
        d("深色", "深色", "Dark");
        d("莫奈动态取色（跟随壁纸）", "莫奈動態取色（跟隨桌布）", "Monet dynamic color (wallpaper)");
        d("种子色", "種子色", "Seed color");
        d("背景与质感", "背景與質感", "Background & Texture");
        d("全屏背景：已设置", "全螢幕背景：已設定", "Full-screen background: set");
        d("全屏背景：未设置", "全螢幕背景：未設定", "Full-screen background: not set");
        d("选择图片", "選擇圖片", "Pick image");
        d("清除", "清除", "Clear");
        d("高斯模糊（背景图）", "高斯模糊（背景圖）", "Gaussian blur (background)");
        d("液态玻璃", "液態玻璃", "Liquid glass");
        d("背景生效区域", "背景生效區域", "Background applies to");
        d("仅主页", "僅主頁", "Home only");
        d("仅编辑页", "僅編輯頁", "Editor only");
        d("动态背景", "動態背景", "Animated background");
        d("极光", "極光", "Aurora");
        d("气泡", "氣泡", "Bubbles");
        d("星空", "星空", "Stars");
        d("波浪", "波浪", "Waves");
        d("流体", "流體", "Fluid");
        d("效果强度", "效果強度", "Effect intensity");
        d("自定义", "自定義", "Custom");
        d("采样分辨率", "採樣分辨率", "Capture resolution");
        d("拷贝帧率上限", "拷貝幀率上限", "Capture FPS cap");
        d("动态背景帧率", "動態背景幀率", "Animated background FPS");
        d("极致", "極致", "Ultra");
        d("均衡", "均衡", "Balanced");
        d("流畅", "流暢", "Smooth");
        d("应用图标（桌面）", "應用程式圖示（桌面）", "App icon (launcher)");
        d("通用", "通用", "General");
        d("网格视图（关闭为列表）", "格狀檢視（關閉為列表）", "Grid view (off = list)");
        d("默认排序", "預設排序", "Default sort");
        d("数据", "資料", "Data");
        d("云备份", "雲備份", "Cloud backup");
        d("WebDAV 服务器（未配置）", "WebDAV 伺服器（未設定）", "WebDAV server (not set)");
        d("WebDAV 备份", "WebDAV 備份", "WebDAV backup");
        d("服务器地址", "伺服器位址", "Server address");
        d("账号", "帳號", "Username");
        d("密码", "密碼", "Password");
        d("填写你的 WebDAV 文件夹地址，备份会存到该地址下。", "填寫你的 WebDAV 資料夾位址，備份會存到該位址下。", "Enter your WebDAV folder address; backups are stored there.");
        d("测试连接", "測試連線", "Test connection");
        d("正在测试…", "正在測試…", "Testing…");
        d("连接成功", "連線成功", "Connection successful");
        d("连接失败：%1$s", "連線失敗：%1$s", "Connection failed: %1$s");
        d("请先填写服务器地址", "請先填寫伺服器位址", "Enter the server address first");
        d("请先设置 WebDAV", "請先設定 WebDAV", "Set up WebDAV first");
        d("立即备份", "立即備份", "Back up now");
        d("自动备份（每天）", "自動備份（每天）", "Auto backup (daily)");
        d("正在备份…", "正在備份…", "Backing up…");
        d("备份成功", "備份成功", "Backup complete");
        d("备份失败：%1$s", "備份失敗：%1$s", "Backup failed: %1$s");
        d("正在恢复…", "正在恢復…", "Restoring…");
        d("恢复失败：%1$s", "還原失敗：%1$s", "Restore failed: %1$s");
        d("从云端恢复", "從雲端還原", "Restore from cloud");
        d("云端还没有备份", "雲端還沒有備份", "No backup in the cloud yet");
        d("云盘文件夹（未选择）", "雲端資料夾（未選擇）", "Cloud folder (not selected)");
        d("云盘文件夹（已选择）", "雲端資料夾（已選擇）", "Cloud folder (selected)");
        d("云盘文件夹备份", "雲端資料夾備份", "Cloud folder backup");
        d("云盘文件夹已选择", "雲端資料夾已選擇", "Cloud folder selected");
        d("请先选择云盘文件夹", "請先選擇雲端資料夾", "Choose a cloud folder first");
        d("云盘文件夹里还没有备份", "雲端資料夾裡還沒有備份", "No backup in this folder yet");
        d("选择", "選擇", "Choose");
        d("上次备份：%1$s", "上次備份：%1$s", "Last backup: %1$s");
        d("从未备份", "從未備份", "Never backed up");
        d("备份里有 %1$d 条笔记（含附件文件）。\n\n合并：只添加现在没有的笔记；\n覆盖：清空现有笔记后整体恢复。", "備份裡有 %1$d 條筆記（含附件檔案）。\n\n合併：只加入目前沒有的筆記；\n覆蓋：清空現有筆記後整體還原。", "The backup contains %1$d notes (attachments included).\n\nMerge: only add notes you don't have yet.\nOverwrite: erase everything and restore the backup.");
        d("备份里有 %1$d 条笔记。\n\n合并：只添加现在没有的笔记；\n覆盖：清空现有笔记后整体恢复。\n\n注意：这份备份只包含笔记数据，附件文件不在其中；同台设备上原有附件仍可打开。", "備份裡有 %1$d 條筆記。\n\n合併：只加入目前沒有的筆記；\n覆蓋：清空現有筆記後整體還原。\n\n注意：這份備份只包含筆記資料，附件檔案不在其中；同一台裝置上原有附件仍可開啟。", "The backup contains %1$d notes.\n\nMerge: only add notes you don't have yet.\nOverwrite: erase everything and restore the backup.\n\nNote: this backup holds note data only, without attachment files; existing attachments on this device still open.");
        d("导出全部笔记（JSON 备份）", "匯出全部筆記（JSON 備份）", "Export all notes (JSON backup)");
        d("导出", "匯出", "Export");
        d("导入备份（从 JSON 恢复）", "匯入備份（從 JSON 還原）", "Import backup (restore from JSON)");
        d("导入", "匯入", "Import");
        d("导入备份", "匯入備份", "Import backup");
        d("合并导入", "合併匯入", "Merge");
        d("覆盖导入", "覆蓋匯入", "Overwrite");
        d("清空回收站（%1$d 条）", "清空回收桶（%1$d 條）", "Empty trash (%1$d)");
        d("关于", "關於", "About");
        d("GitHub 仓库", "GitHub 倉庫", "GitHub repository");
        d("粗体", "粗體", "bold text");
        d("斜体", "斜體", "italic text");
        d("代码", "程式碼", "code");
        d("清单模式不支持预览", "清單模式不支援預覽", "Preview is not available in checklist mode");
        d("导出笔记", "匯出筆記", "Export note");
        d("导出…", "匯出…", "Export…");
        d("纯文本", "純文本", "Plain text");
        d("HTML 网页", "HTML 網頁", "HTML page");
        d("PDF 文档", "PDF 文件", "PDF document");
        d("长图", "長圖", "Long image");
        d("保存到…", "儲存到…", "Save to…");
        d("已保存", "已儲存", "Saved");
        d("无标题笔记", "無標題筆記", "Untitled note");
        d("正在生成导出文件…", "正在產生匯出檔…", "Generating export file…");
        d("内存不足，长图生成失败", "記憶體不足，長圖產生失敗", "Out of memory — long image failed");
        d("导出长图", "匯出長圖", "Export long image");
        d("这篇笔记内容较长，一张图放不下。\n\n可导出为 %1$d 张连续图片（保持原字号，文件名带序号），或缩成一张长图（字号会自动缩小）。",
          "這篇筆記內容較長，一張圖放不下。\n\n可匯出為 %1$d 張連續圖片（保持原字號，檔名帶序號），或縮成一張長圖（字號會自動縮小）。",
          "This note is too long for a single image.\n\nExport it as %1$d consecutive images (original text size, numbered file names), or shrink it into one long image (text size reduced automatically).");
        d("导出为 %1$d 张图片", "匯出為 %1$d 張圖片", "Export as %1$d images");
        d("一张长图（缩字号）", "一張長圖（縮字號）", "One long image (smaller text)");
        d("保存到文件夹…", "儲存到資料夾…", "Save to folder…");
        d("已保存 %1$d 张图片", "已儲存 %1$d 張圖片", "Saved %1$d images");
        d("分享整组图片", "分享整組圖片", "Share all images");
        d("打包成压缩包", "打包成壓縮檔", "Pack into a ZIP file");
        d("附件：", "附件：", "Attachments:");
        d("无法打开浏览器", "無法打開瀏覽器", "Cannot open browser");
        d("回收站已清空", "回收桶已清空", "Trash emptied");
        d("回收站已经是空的", "回收桶已經是空的", "Trash is already empty");
        d("备份已导出", "備份已匯出", "Backup exported");
        d("导出失败：%1$s", "匯出失敗：%1$s", "Export failed: %1$s");
        d("备份文件无效，导入取消", "備份檔案無效，匯入取消", "Invalid backup file — import cancelled");
        d("备份里没有笔记", "備份裡沒有筆記", "No notes in this backup");
        d("文件过大", "檔案過大", "File too large");
        d("无法读取文件", "無法讀取檔案", "Cannot read file");
        d("备份里有 %1$d 条笔记。\n\n合并：只添加现在没有的笔记；\n覆盖：清空现有笔记后整体恢复。\n\n注意：备份只包含笔记数据，附件文件不在其中；同台设备上原有附件仍可打开。",
          "備份裡有 %1$d 條筆記。\n\n合併：只新增現在沒有的筆記；\n覆蓋：清空現有筆記後整體還原。\n\n注意：備份只包含筆記資料，附件檔案不在其中；同台裝置上原有附件仍可開啟。",
          "This backup contains %1$d notes.\n\nMerge: only add notes you don't have;\nOverwrite: clear current notes and restore everything.\n\nNote: backups contain note data only, not attachment files; on the same device, existing attachments still open.");
        d("已导入 %1$d 条（跳过 %2$d 条已存在的）", "已匯入 %1$d 條（跳過 %2$d 條已存在的）", "Imported %1$d (skipped %2$d already existing)");
        d("已恢复 %1$d 条笔记", "已還原 %1$d 條筆記", "Restored %1$d notes");
        d("将彻底删除 %1$d 条笔记及其附件，无法恢复。", "將永久刪除 %1$d 條筆記及其附件，無法還原。", "This will permanently delete %1$d notes and their attachments.");
        d("背景图已设置", "背景圖已設定", "Background set");
        d("背景图已清除", "背景圖已清除", "Background cleared");
        d("这张图片读不了，换一张试试", "這張圖片讀不了，換一張試試", "Can't read this image — try another");
        d("桌面图标已换成「%1$s」，回桌面看看（个别桌面要等一会儿才刷新）", "桌面圖示已換成「%1$s」，回桌面看看（個別桌面要等一會兒才重新整理）", "App icon changed to “%1$s” — check your home screen (some launchers refresh slowly)");
        d("莫奈备忘录 Monet Notes\n版本 %1$s · %2$d 条笔记 · %3$d 个分类 · %4$d 个标签\n原生 Java 实现，无第三方依赖",
          "莫奈備忘錄 Monet Notes\n版本 %1$s · %2$d 條筆記 · %3$d 個分類 · %4$d 個標籤\n原生 Java 實作，無第三方依賴",
          "Monet Notes\nVersion %1$s · %2$d notes · %3$d categories · %4$d tags\nNative Java, no third-party dependencies");

        // ---- 分类 ----
        d("管理分类", "管理分類", "Manage Categories");
        d("管理分类（点条目管理）", "管理分類（點條目管理）", "Manage categories (tap an item)");
        d("新建分类", "新建分類", "New Category");
        d("分类名称", "分類名稱", "Category name");
        d("分类名称，如：旅行", "分類名稱，如：旅行", "Name, e.g. Travel");
        d("编辑分类", "編輯分類", "Edit Category");
        d("编辑（图标和名称）", "編輯（圖示和名稱）", "Edit (icon & name)");
        d("给「%1$s」选个图标", "給「%1$s」選個圖示", "Choose an icon for “%1$s”");
        d("点左侧图标可更换图标（预置 / 自定义 / 无图标）", "點左側圖示可更換圖示（預置 / 自訂 / 無圖示）", "Tap the icon on the left to change it (preset / custom / none)");
        d("名称不能为空", "名稱不能為空", "Name can't be empty");
        d("分类已存在或名称为空", "分類已存在或名稱為空", "Category already exists or name is empty");
        d("已有同名分类，未改名（图标修改已保存）", "已有同名分類，未改名（圖示修改已儲存）", "Name already taken — rename skipped (icon saved)");
        d("删除分类「%1$s」？", "刪除分類「%1$s」？", "Delete category “%1$s”?");
        d("该分类下的笔记会变为未分类，笔记本身不会被删除。", "該分類下的筆記會變為未分類，筆記本身不會被刪除。", "Notes in it become uncategorized; the notes themselves won't be deleted.");
        d("删除此分类", "刪除此分類", "Delete this category");
        d("无图标", "無圖示", "No icon");
        d("自定义图标", "自訂圖示", "Custom icon");
        d("自定义…", "自訂…", "Custom…");
        d("输入任意 emoji 或 1~2 个字", "輸入任意 emoji 或 1~2 個字", "Any emoji or 1–2 characters");
        d("用默认 📁", "用預設 📁", "Use default 📁");
        d("还没有任何分类。", "還沒有任何分類。", "No categories yet.");
        d("工作", "工作", "Work");
        d("生活", "生活", "Life");
        d("学习", "學習", "Study");
        d("灵感", "靈感", "Ideas");

        // ---- 标签 ----
        d("编辑标签", "編輯標籤", "Edit Tags");
        d("输入新标签", "輸入新標籤", "New tag");
        d("标签已存在", "標籤已存在", "Tag already exists");
        d("还没有标签，用下面的输入框添加", "還沒有標籤，用下面的輸入框新增", "No tags yet — add one below");
        d("其它笔记的标签，点一下添加：", "其它筆記的標籤，點一下新增：", "Tags from other notes — tap to add:");
    }
}
