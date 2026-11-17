package com.muse.monetnotes;

import android.app.Activity;
import android.app.AlertDialog;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 指纹锁屏：不透明全屏盖住应用内容（并禁止最近任务截图），
 * 拉起系统指纹框；成功才放行，取消则把整个任务退到后台。
 * 指纹硬件失效时提供「关闭指纹锁并进入」的出口，避免用户被自己锁在门外。
 */
public class LockActivity extends Activity {
    private Palette pal;
    private boolean promptActive = false;
    private boolean authDone = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Lang.init(this);
        pal = Palette.of(this);
        getWindow().setStatusBarColor(pal.background);
        getWindow().setNavigationBarColor(pal.background);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (!pal.dark) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        AppLock.lockShowing = true;
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(pal.background);
        root.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0);
        TextView icon = Ui.text(this, "🔒", 56, pal.textPrimary, false);
        icon.setGravity(Gravity.CENTER);
        root.addView(icon);
        TextView title = Ui.text(this, Lang.t("莫奈备忘录已锁定"), 18, pal.textPrimary, true);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 16);
        root.addView(title, tlp);
        TextView retry = Ui.text(this, Lang.t("使用指纹解锁"), 15, pal.primary, true);
        retry.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(this, 30);
        retry.setLayoutParams(rlp);
        retry.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        retry.setOnClickListener(v -> showPrompt());
        root.addView(retry);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!authDone && !promptActive) showPrompt();
    }

    private void showPrompt() {
        if (Build.VERSION.SDK_INT < 28 || AppLock.canUse(this) != AppLock.BIO_OK) {
            showEscapeDialog();
            return;
        }
        promptActive = true;
        AppLock.prompt(this, Lang.t("解锁莫奈备忘录"), Lang.t("取消"), new AppLock.Callback() {
            @Override
            public void onSuccess() {
                promptActive = false;
                authDone = true;
                AppLock.markUnlocked();
                finish();
            }

            @Override
            public void onError(int errorCode) {
                promptActive = false;
                // 1 硬件不可用 / 11 无生物信息 / 12 无硬件 / -1 本地拉起异常：
                // 都给「关闭指纹锁并进入」的出口，绝不能把人锁死在门外
                if (errorCode == -1 || errorCode == 1 || errorCode == 11 || errorCode == 12) {
                    showEscapeDialog();
                } else {
                    exitApp();
                }
            }
        });
    }

    private void showEscapeDialog() {
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(Lang.t("指纹不可用"))
                .setMessage(Lang.t("无法使用指纹解锁，可以关闭指纹锁后进入，或退出应用。"))
                .setPositiveButton(Lang.t("关闭指纹锁并进入"), (d, w) -> {
                    AppLock.setEnabled(this, false);
                    AppLock.markUnlocked();
                    authDone = true;
                    finish();
                })
                .setNegativeButton(Lang.t("退出"), (d, w) -> exitApp())
                .setCancelable(false)
                .show();
        Ui.styleDialog(this, dlg);
    }

    private void exitApp() {
        moveTaskToBack(true);
        finish();
    }

    @Override
    public void onBackPressed() {
        exitApp();
    }

    @Override
    protected void onDestroy() {
        AppLock.lockShowing = false;
        super.onDestroy();
    }
}
