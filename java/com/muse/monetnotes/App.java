package com.muse.monetnotes;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

/** 全局生命周期跟踪：供指纹锁判断应用回到前台的时机。 */
public class App extends Application {
    private int startedCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override
            public void onActivityStarted(Activity a) {
                if (startedCount == 0) AppLock.onForeground(a);
                startedCount++;
            }

            @Override
            public void onActivityStopped(Activity a) {
                startedCount--;
                if (startedCount == 0) AppLock.onBackground();
            }

            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityResumed(Activity a) { }
            @Override public void onActivityPaused(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            @Override public void onActivityDestroyed(Activity a) { }
        });
    }
}
