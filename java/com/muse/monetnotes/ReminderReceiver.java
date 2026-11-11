package com.muse.monetnotes;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 提醒到时发送通知；同时用于开机后重新安排全部提醒。 */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            ReminderManager.syncAll(ctx);
            return;
        }
        String noteId = intent.getStringExtra(ReminderManager.EXTRA_NOTE_ID);
        String title = intent.getStringExtra(ReminderManager.EXTRA_TITLE);
        String body = intent.getStringExtra(ReminderManager.EXTRA_BODY);
        if (title == null || title.isEmpty()) title = "备忘录提醒";
        if (body == null) body = "";

        // 触发后清除该笔记的提醒时间，避免重复触发；若用户已改到更晚的时间则保留
        if (noteId != null) {
            Note n = NoteStore.find(noteId);
            if (n == null) {
                // 进程刚启动时缓存为空，从文件加载后再找一次
                NoteStore.load(ctx);
                n = NoteStore.find(noteId);
            }
            if (n != null && n.reminderAt > 0 && n.reminderAt <= System.currentTimeMillis() + 60_000L) {
                n.reminderAt = 0;
                NoteStore.save(ctx);
            }
        }

        if (!ReminderManager.hasNotificationPermission(ctx)) return;
        ReminderManager.ensureChannel(ctx);

        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int req = noteId == null ? 0 : (noteId.hashCode() & 0x7fffffff);
        PendingIntent content = PendingIntent.getActivity(ctx, req, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = new Notification.Builder(ctx, ReminderManager.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(content);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(req, b.build());
    }
}
