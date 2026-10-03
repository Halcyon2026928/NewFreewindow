package io.github.halcyon2026928.freewindow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机后把功能开关恢复为全开（主题不改）。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)) {
            SettingsActivity.applyBootDefaults(context);
        }
    }
}
