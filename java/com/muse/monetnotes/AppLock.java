package com.muse.monetnotes;

import android.app.Activity;
import android.hardware.biometrics.BiometricPrompt;
import android.content.Context;
import android.content.Intent;
import android.hardware.biometrics.BiometricManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.Executor;

/**
 * 应用指纹锁：冷启动必锁；退到后台超过 60 秒回前台时重新锁。
 * 用框架版 BiometricPrompt / BiometricManager（API 28+），无第三方依赖；
 * 低版本设备在设置里会被拦下、无法开启。
 */
public class AppLock {
    public static final int BIO_OK = 0;
    public static final int BIO_NONE_ENROLLED = 1;
    public static final int BIO_NO_HARDWARE = 2;
    public static final int BIO_UNSUPPORTED = 3;

    private static final long RELOCK_MS = 60_000;
    private static boolean unlocked = false;
    private static long backgroundedAt = 0;
    static boolean lockShowing = false;

    public static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(Palette.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Palette.KEY_APP_LOCK, false);
    }

    public static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences(Palette.PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(Palette.KEY_APP_LOCK, on).apply();
    }

    public static void markUnlocked() {
        unlocked = true;
    }

    static void onBackground() {
        backgroundedAt = System.currentTimeMillis();
    }

    static void onForeground(Activity a) {
        if (a instanceof LockActivity || lockShowing) return;
        if (!enabled(a)) return;
        if (unlocked && backgroundedAt > 0
                && System.currentTimeMillis() - backgroundedAt > RELOCK_MS) {
            unlocked = false;
        }
        if (!unlocked) a.startActivity(new Intent(a, LockActivity.class));
    }

    public static int canUse(Context ctx) {
        try {
            return canUseInner(ctx);
        } catch (Exception e) {
            // 个别 ROM 上查生物识别会抛异常（如缺权限时的 SecurityException），一律按不可用处理，绝不闪退
            return BIO_UNSUPPORTED;
        }
    }

    private static int canUseInner(Context ctx) {
        if (Build.VERSION.SDK_INT >= 29) {
            BiometricManager bm = ctx.getSystemService(BiometricManager.class);
            if (bm == null) return BIO_UNSUPPORTED;
            int r = bm.canAuthenticate();
            if (r == BiometricManager.BIOMETRIC_SUCCESS) return BIO_OK;
            if (r == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) return BIO_NONE_ENROLLED;
            if (r == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) return BIO_NO_HARDWARE;
            return BIO_UNSUPPORTED;
        }
        if (Build.VERSION.SDK_INT == 28) {
            android.hardware.fingerprint.FingerprintManager fm =
                    (android.hardware.fingerprint.FingerprintManager)
                            ctx.getSystemService(Context.FINGERPRINT_SERVICE);
            if (fm == null || !fm.isHardwareDetected()) return BIO_NO_HARDWARE;
            if (!fm.hasEnrolledFingerprints()) return BIO_NONE_ENROLLED;
            return BIO_OK;
        }
        return BIO_UNSUPPORTED;
    }

    public interface Callback {
        void onSuccess();

        void onError(int errorCode);
    }

    /** 拉起系统生物识别框。调用方须先确认 canUse() == BIO_OK（内含 API 级别判断）。 */
    public static void prompt(Activity act, String title, String negative, final Callback cb) {
        try {
            Handler h = new Handler(Looper.getMainLooper());
            Executor ex = h::post;
            BiometricPrompt bp = new BiometricPrompt.Builder(act)
                    .setTitle(title)
                    .setNegativeButton(negative, ex,
                            // 13 = 负按钮错误码（框架常量在 API 30 改名后无公开名，直接用数值）
                            (d, which) -> cb.onError(13))
                    .build();
            bp.authenticate(new CancellationSignal(), ex, new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                    cb.onSuccess();
                }

                @Override
                public void onAuthenticationError(int errorCode, CharSequence errString) {
                    cb.onError(errorCode);
                }
            });
        } catch (Exception e) {
            // 拉起失败（如 ROM 限制）走错误回调，绝不让异常冒泡成闪退；-1 表示本地异常
            cb.onError(-1);
        }
    }
}
