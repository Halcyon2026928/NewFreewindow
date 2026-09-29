package io.github.halcyon2026928.freewindow;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.Switch;

/**
 * freewindow 设置界面：
 *  - 隐藏窗口白条（自由小窗底部白色手势横条）
 *  - 隐藏三点菜单栏（顶部三点按钮：视觉/点击/触摸穿透）
 *
 * 配置写入模块自己的 SharedPreferences，由 ConfigProvider 暴露给
 * system_server（hook 侧）跨进程读取；修改后重启系统框架生效。
 */
public class SettingsActivity extends Activity {

    private static final String PREFS_NAME = "freewindow_prefs";
    public static final String KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar";
    public static final String KEY_HIDE_THREE_DOT = "hide_three_dot_menu";

    /** UI 偏好（主题），独立于功能配置 */
    private static final String UI_PREFS = "ui_prefs";
    private static final String KEY_LIGHT_THEME = "light_theme";

    private Switch bottomBarSwitch;
    private Switch threeDotSwitch;
    /** 程序同步开关状态时置位，避免 setChecked 递归触发监听器 */
    private boolean syncing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 主题必须在 setContentView 之前应用
        setTheme(isLightTheme() ? R.style.LightTheme : R.style.AppTheme);
        setContentView(R.layout.activity_settings);

        // 界面主题切换（仅 UI，不影响功能开关）
        Switch themeSwitch = findViewById(R.id.switch_theme);
        themeSwitch.setChecked(isLightTheme());
        themeSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_LIGHT_THEME, isChecked).commit();
                recreate();
            }
        });

        bottomBarSwitch = findViewById(R.id.switch_bottom_bar);
        threeDotSwitch = findViewById(R.id.switch_three_dot);

        syncFromPrefs();

        bottomBarSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (syncing) return;
                writePref(KEY_HIDE_BOTTOM_BAR, isChecked);
            }
        });
        threeDotSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (syncing) return;
                writePref(KEY_HIDE_THREE_DOT, isChecked);
            }
        });
    }

    private void syncFromPrefs() {
        syncing = true;
        bottomBarSwitch.setChecked(readPref(KEY_HIDE_BOTTOM_BAR, true));
        threeDotSwitch.setChecked(readPref(KEY_HIDE_THREE_DOT, true));
        syncing = false;
    }

    private boolean readPref(String key, boolean def) {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(key, def);
    }

    private void writePref(String key, boolean value) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(key, value).commit();
    }

    /** UI 偏好：是否使用浅色主题（默认浅色） */
    private boolean isLightTheme() {
        return getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LIGHT_THEME, true);
    }
}
