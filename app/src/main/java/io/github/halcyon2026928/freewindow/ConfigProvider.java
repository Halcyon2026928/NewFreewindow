package io.github.halcyon2026928.freewindow;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * 给 system_server 的即时读取通道。重启后的权威数据在 XSharedPreferences。
 */
public class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.halcyon2026928.freewindow.config";
    public static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        // 每次被 system_server 查询时顺手把 prefs 设成可读，保证 XSharedPreferences 兜底通道有效。
        ModulePrefs.makePrefsReadable(getContext());
        SharedPreferences prefs = ModulePrefs.get(getContext());
        MatrixCursor c = new MatrixCursor(new String[]{"key", "value"});
        c.addRow(new Object[]{SettingsActivity.KEY_HIDE_BOTTOM_BAR,
                prefs.getBoolean(SettingsActivity.KEY_HIDE_BOTTOM_BAR, true) ? 1 : 0});
        c.addRow(new Object[]{SettingsActivity.KEY_HIDE_THREE_DOT,
                prefs.getBoolean(SettingsActivity.KEY_HIDE_THREE_DOT, true) ? 1 : 0});
        c.addRow(new Object[]{SettingsActivity.KEY_ENABLE_DOCK,
                prefs.getBoolean(SettingsActivity.KEY_ENABLE_DOCK, true) ? 1 : 0});
        c.addRow(new Object[]{SettingsActivity.KEY_KEEP_ALIVE,
                prefs.getBoolean(SettingsActivity.KEY_KEEP_ALIVE, true) ? 1 : 0});
        c.addRow(new Object[]{SettingsActivity.KEY_UNLOCK_LIMIT,
                prefs.getBoolean(SettingsActivity.KEY_UNLOCK_LIMIT, true) ? 1 : 0});
        // 坞窗卡片实时画面开关：关闭时卡片只显示静态快照，点击和滚动照常。
        c.addRow(new Object[]{SettingsActivity.KEY_LIVE_PREVIEW,
                prefs.getBoolean(SettingsActivity.KEY_LIVE_PREVIEW, true) ? 1 : 0});
        // 主题模式：0=跟随系统，1=浅色，2=深色。坞窗在 system 进程里按它配色。
        c.addRow(new Object[]{SettingsActivity.KEY_THEME_MODE,
                prefs.getInt(SettingsActivity.KEY_THEME_MODE, SettingsActivity.THEME_FOLLOW_SYSTEM)});
        // 被查询即顺手推一份落盘：system_server 里那份文件是开机恢复的唯一来源，
        // 这样即便用户改完设置就直接关掉 app，下次开机也能拿到最新的值。
        SettingsActivity.pushSettings(getContext());
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.freewindow.config";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
