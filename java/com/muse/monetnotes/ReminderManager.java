package com.muse.monetnotes;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** 安排 / 取消笔记提醒，并处理通知权限与精确闹钟的降级。 */
public class ReminderManager {
    public static final String CHANNEL_ID = "note_reminders";
    public static final String EXTRA_NOTE_ID = "note_id";
    public static final String EXTRA_TITLE = "note_title";
    public static final String EXTRA_BODY = "note_body";
    private static final int REQ_NOTIFICATION_PERMISSION = 2001;

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "笔记提醒", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("笔记到达提醒时间时发送通知");
        ch.enableVibration(true);
        nm.createNotificationChannel(ch);
    }

    /** 通知权限是否已具备（API 33+ 需要运行时授权，低版本默认具备）。 */
    public static boolean hasNotificationPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < 33) return true;
        return ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 在 Activity 里请求通知权限；已授权或低版本返回 true。 */
    public static boolean requestNotificationPermission(Activity act) {
        if (hasNotificationPermission(act)) return true;
        if (Build.VERSION.SDK_INT >= 33) {
            act.requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATION_PERMISSION);
        }
        return false;
    }

    private static PendingIntent pendingIntent(Context ctx, Note n, String title, String body) {
        Intent i = new Intent(ctx, ReminderReceiver.class);
        i.putExtra(EXTRA_NOTE_ID, n.id);
        i.putExtra(EXTRA_TITLE, title);
        i.putExtra(EXTRA_BODY, body);
        int req = n.id.hashCode() & 0x7fffffff;
        return PendingIntent.getBroadcast(ctx, req, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 按笔记当前状态安排或取消提醒：未来时间且未在回收站才安排。 */
    public static void sync(Context ctx, Note n) {
        if (n == null) return;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long now = System.currentTimeMillis();
        if (n.trashed || n.reminderAt <= now) {
            cancel(ctx, n);
            return;
        }
        String title = n.title == null || n.title.trim().isEmpty() ? "备忘录提醒" : n.title.trim();
        String body = n.checklist
                ? ("清单 · 已完成 " + n.doneCount() + "/" + n.items.size())
                : (n.content == null ? "" : n.content.trim());
        if (body.length() > 120) body = body.substring(0, 120) + "…";
        PendingIntent pi = pendingIntent(ctx, n, title, body);
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                // 没有精确闹钟权限时降级为非精确闹钟，避免崩溃；提醒仍会送达（可能有少量延迟）
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.reminderAt, pi);
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.reminderAt, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, n.reminderAt, pi);
            }
        } catch (SecurityException e) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.reminderAt, pi);
            } catch (Exception ignored) {
            }
        }
    }

    public static void cancel(Context ctx, Note n) {
        if (n == null) return;
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        // filterEquals 不比较 extras，用同一 note_id 构造即可匹配到已安排的 PendingIntent
        Intent i = new Intent(ctx, ReminderReceiver.class);
        i.putExtra(EXTRA_NOTE_ID, n.id);
        int req = n.id.hashCode() & 0x7fffffff;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, req, i,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (pi != null) {
            am.cancel(pi);
            pi.cancel();
        }
    }

    /** 重新安排全部笔记的提醒（开机、回到主界面时调用，修复重启后丢失的闹钟）。 */
    public static void syncAll(Context ctx) {
        ensureChannel(ctx);
        for (Note n : NoteStore.load(ctx)) sync(ctx, n);
    }
}
