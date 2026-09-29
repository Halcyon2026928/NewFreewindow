package io.github.halcyon2026928.freewindow;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * 配置读取通道：hook 侧（system_server）通过 ContentResolver 跨进程读取本 Provider，
 * 返回模块开关配置。绕开 Settings.System 的 ROM 写入限制与 prefs 文件的 SELinux 读取限制：
 * app 写自己的 SharedPreferences（无权限问题），system_server 走标准 Binder/Provider 机制读取。
 */
public class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.halcyon2026928.freewindow.config";
    public static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");

    private static final String PREFS_NAME = "freewindow_prefs";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        SharedPreferences prefs = getContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        MatrixCursor c = new MatrixCursor(new String[]{"key", "value"});
        c.addRow(new Object[]{SettingsActivity.KEY_HIDE_BOTTOM_BAR,
                prefs.getBoolean(SettingsActivity.KEY_HIDE_BOTTOM_BAR, true) ? 1 : 0});
        c.addRow(new Object[]{SettingsActivity.KEY_HIDE_THREE_DOT,
                prefs.getBoolean(SettingsActivity.KEY_HIDE_THREE_DOT, true) ? 1 : 0});
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
