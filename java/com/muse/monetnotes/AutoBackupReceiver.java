package com.muse.monetnotes;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 每日自动 WebDAV 备份：闹钟到时在后台线程上传一次完整备份；开机时重新安排日程。 */
public class AutoBackupReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            CloudBackup.scheduleAuto(ctx);
            return;
        }
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                if (CloudBackup.davConfigured(app)) CloudBackup.davBackup(app);
            } catch (Exception ignored) {
                // 自动备份失败静默处理，下一个周期或手动备份时再试
            } finally {
                pr.finish();
            }
        }).start();
    }
}
