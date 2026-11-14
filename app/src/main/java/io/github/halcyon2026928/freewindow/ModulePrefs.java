package io.github.halcyon2026928.freewindow;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * LSPosed 官方开关记忆方式（New XSharedPreferences）：
 * 模块进程用 MODE_WORLD_READABLE 写 prefs；LSPosed 把它放到可读目录。
 * 系统框架用 XSharedPreferences(包名, 文件名) 读取，重启后仍能拿到上次状态。
 */
public final class ModulePrefs {

    public static final String PKG = "io.github.halcyon2026928.freewindow";
    public static final String NAME = "freewindow_prefs";

    private ModulePrefs() {
    }

    public static SharedPreferences get(Context ctx) {
        SharedPreferences prefs;
        try {
            prefs = ctx.getSharedPreferences(NAME, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            prefs = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        }
        migrateOldPrivatePrefs(ctx, prefs);
        return prefs;
    }

    /** 把旧版 MODE_PRIVATE 里已保存的开关拷到 LSPosed 可读 prefs，只拷一次。 */
    private static void migrateOldPrivatePrefs(Context ctx, SharedPreferences dest) {
        if (dest.contains(SettingsActivity.KEY_HIDE_BOTTOM_BAR)
                || dest.contains(SettingsActivity.KEY_HIDE_THREE_DOT)
                || dest.contains(SettingsActivity.KEY_ENABLE_DOCK)
                || dest.contains(SettingsActivity.KEY_KEEP_ALIVE)) {
            return;
        }
        SharedPreferences old = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        if (old == dest) return;
        if (!old.contains(SettingsActivity.KEY_HIDE_BOTTOM_BAR)
                && !old.contains(SettingsActivity.KEY_HIDE_THREE_DOT)
                && !old.contains(SettingsActivity.KEY_ENABLE_DOCK)
                && !old.contains(SettingsActivity.KEY_KEEP_ALIVE)) {
            return;
        }
        dest.edit()
                .putBoolean(SettingsActivity.KEY_HIDE_BOTTOM_BAR,
                        old.getBoolean(SettingsActivity.KEY_HIDE_BOTTOM_BAR, true))
                .putBoolean(SettingsActivity.KEY_HIDE_THREE_DOT,
                        old.getBoolean(SettingsActivity.KEY_HIDE_THREE_DOT, true))
                .putBoolean(SettingsActivity.KEY_ENABLE_DOCK,
                        old.getBoolean(SettingsActivity.KEY_ENABLE_DOCK, true))
                .putBoolean(SettingsActivity.KEY_KEEP_ALIVE,
                        old.getBoolean(SettingsActivity.KEY_KEEP_ALIVE, true))
                .commit();
    }
}
