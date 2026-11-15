package io.github.halcyon2026928.freewindow;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * freewindow 设置界面。
 * 功能开关写入 LSPosed 可读 SharedPreferences，系统框架重启后仍保持上次状态。
 * 主题单独存，不影响功能开关。
 */
public class SettingsActivity extends Activity {

    public static final String KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar";
    public static final String KEY_HIDE_THREE_DOT = "hide_three_dot_menu";
    public static final String KEY_ENABLE_DOCK = "enable_float_dock";
    /** 解除自由小窗数量上限（官方 2 个 → 12 个），与 MainHook 的 unlock_float_limit 一致。 */
    public static final String KEY_UNLOCK_LIMIT = "unlock_float_limit";
    /** 贴边保活开关，与 FloatKeepAliveHook 读取的键一致。 */
    public static final String KEY_KEEP_ALIVE = FloatKeepAliveHook.KEY_KEEP_ALIVE;
    /** 开发者选项：开机自动打开系统「无线调试」，与 WirelessAdbAuto 一致。 */
    public static final String KEY_AUTO_WIRELESS_ADB = WirelessAdbAuto.KEY;

    private static final String UI_PREFS = "ui_prefs";
    private static final String KEY_LIGHT_THEME = "light_theme";

    private Switch bottomBarSwitch;
    private Switch threeDotSwitch;
    private Switch dockSwitch;
    private Switch keepAliveSwitch;
    private Switch autoWirelessAdbSwitch;
    private Switch unlockLimitSwitch;
    private boolean syncing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final boolean light = isLightTheme();
        setTheme(light ? R.style.LightTheme : R.style.AppTheme);
        setContentView(R.layout.activity_settings);
        applyImmersiveStatusBar(light);

        View btnTheme = findViewById(R.id.btnTheme);
        ImageView ivThemeIcon = findViewById(R.id.ivThemeIcon);
        ivThemeIcon.setImageResource(light ? R.drawable.ic_theme_sun : R.drawable.ic_theme_moon);
        btnTheme.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
                        .edit().putBoolean(KEY_LIGHT_THEME, !light).commit();
                recreate();
            }
        });

        bottomBarSwitch = findViewById(R.id.switch_bottom_bar);
        threeDotSwitch = findViewById(R.id.switch_three_dot);
        dockSwitch = findViewById(R.id.switch_dock);
        keepAliveSwitch = findViewById(R.id.switch_keep_alive);
        autoWirelessAdbSwitch = findViewById(R.id.switch_auto_wireless_adb);
        unlockLimitSwitch = findViewById(R.id.switch_unlock_limit);

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
        dockSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (syncing) return;
                writePref(KEY_ENABLE_DOCK, isChecked);
            }
        });
        keepAliveSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (syncing) return;
                writePref(KEY_KEEP_ALIVE, isChecked);
            }
        });
        autoWirelessAdbSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (syncing) return;
                writePref(KEY_AUTO_WIRELESS_ADB, isChecked);
            }
        });
        unlockLimitSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (syncing) return;
                writePref(KEY_UNLOCK_LIMIT, isChecked);
            }
        });

        // 捐赠入口：整张卡片可点，点开弹出收款码
        View donateCard = findViewById(R.id.card_donate);
        if (donateCard != null) {
            donateCard.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showDonateDialog();
                }
            });
        }
    }

    /**
     * 捐赠弹窗：一张图同时含微信 / 支付宝收款码，配一段「有捐赠就有动力」的说明。
     * 内容整体交给 ScrollView，图片在小屏上按宽度自适应，保证二维码扫得出来。
     */
    private void showDonateDialog() {
        try {
            float density = getResources().getDisplayMetrics().density;
            int pad = (int) (18 * density);

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(pad, pad, pad, 0);

            TextView msg = new TextView(this);
            msg.setText(R.string.donate_dialog_msg);
            msg.setTextSize(13);
            msg.setLineSpacing(0f, 1.3f);
            box.addView(msg);

            ImageView qr = new ImageView(this);
            qr.setImageResource(R.drawable.donate_qr);
            qr.setAdjustViewBounds(true);
            qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
            // 图按固定宽度居中（不再 MATCH_PARENT）：图缩到 230dp 左右，
            // 内容整体变短，弹窗基本不用滚，也就不需要在右侧露滚动条。
            LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(
                    (int) (230 * density), LinearLayout.LayoutParams.WRAP_CONTENT);
            qrParams.gravity = Gravity.CENTER_HORIZONTAL;
            qrParams.topMargin = pad;
            box.addView(qr, qrParams);

            TextView hint = new TextView(this);
            hint.setText(R.string.donate_qr_desc);
            hint.setTextSize(12);
            hint.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            hintParams.topMargin = pad / 2;
            box.addView(hint, hintParams);

            // 第二张：支付宝扫码领红包（互惠入口，不动代码也能支持一下）
            ImageView reward = new ImageView(this);
            reward.setImageResource(R.drawable.donate_reward);
            reward.setAdjustViewBounds(true);
            reward.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams rewardParams = new LinearLayout.LayoutParams(
                    (int) (190 * density), LinearLayout.LayoutParams.WRAP_CONTENT);
            rewardParams.gravity = Gravity.CENTER_HORIZONTAL;
            rewardParams.topMargin = pad;
            box.addView(reward, rewardParams);

            TextView rewardHint = new TextView(this);
            rewardHint.setText(R.string.donate_reward_desc);
            rewardHint.setTextSize(12);
            rewardHint.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rewardHintParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rewardHintParams.topMargin = pad / 2;
            box.addView(rewardHint, rewardHintParams);

            ScrollView sv = new ScrollView(this);
            // 不要右侧那条滚动提示：内容仍然可滚（小屏兜底），但不显示 scrollbar，
            // clipToPadding=false 让顶部文案不会被内容区裁掉一半。
            sv.setVerticalScrollBarEnabled(false);
            sv.setClipToPadding(false);
            box.setPadding(pad, pad, pad, pad);
            sv.addView(box);

            new AlertDialog.Builder(this)
                    .setTitle(R.string.donate_dialog_title)
                    .setView(sv)
                    .setPositiveButton(R.string.donate_dialog_close, null)
                    .show();
        } catch (Throwable t) {
            android.util.Log.w("freewindow", "showDonateDialog failed: " + t);
        }
    }

    /**
     * 状态栏沉浸：状态栏背景透明、渐变背景一直铺到屏幕顶端，
     * 内容用 padding 往下让出状态栏高度，标题不会被时间/电量压住。
     * 浅色主题要把图标刷成深色，否则白底上看不见。
     */
    private void applyImmersiveStatusBar(boolean light) {
        try {
            Window window = getWindow();
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            // 状态栏 + 导航栏都透明，渐变背景从屏幕顶端一直铺到底部，不留灰边
            window.setStatusBarColor(Color.TRANSPARENT);
            window.setNavigationBarColor(Color.TRANSPARENT);
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (light && Build.VERSION.SDK_INT >= 23) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (light && Build.VERSION.SDK_INT >= 26) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        } catch (Throwable ignored) {
            // 沉浸失败就退回主题里写死的状态栏颜色，不影响使用
        }

        final View root = findViewById(R.id.root);
        if (root == null) return;
        final View scroll = findViewById(R.id.scroll);
        final int base = (int) (20 * getResources().getDisplayMetrics().density);
        // 底部不留内边距：背景一路铺到最底，滚动容器自己也到底。
        // 但最后一张卡片要能完整滚出来，所以把导航栏高度让给 ScrollView 的内容区
        // （clipToPadding=false，滚动条区域不裁内容，视觉上仍然没有空白带）。
        View.OnApplyWindowInsetsListener listener = new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v,
                                                                   android.view.WindowInsets insets) {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                if (top <= 0) top = statusBarHeight();
                v.setPadding(base, base + top, base, 0);
                if (scroll != null) {
                    scroll.setPadding(0, 0, 0, bottom);
                }
                return insets;
            }
        };
        root.setOnApplyWindowInsetsListener(listener);
        root.requestApplyInsets();
        // 保险：insets 偶尔不来（部分 ROM 在首帧之后才下发），这里先按资源值摆一次。
        root.setPadding(base, base + statusBarHeight(), base, 0);
        if (scroll != null) {
            scroll.setPadding(0, 0, 0, navigationBarHeight());
        }
    }

    private int statusBarHeight() {
        return systemDimen("status_bar_height");
    }

    private int navigationBarHeight() {
        return systemDimen("navigation_bar_height");
    }

    private int systemDimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private void syncFromPrefs() {
        syncing = true;
        bottomBarSwitch.setChecked(readPref(KEY_HIDE_BOTTOM_BAR, true));
        threeDotSwitch.setChecked(readPref(KEY_HIDE_THREE_DOT, true));
        dockSwitch.setChecked(readPref(KEY_ENABLE_DOCK, true));
        keepAliveSwitch.setChecked(readPref(KEY_KEEP_ALIVE, true));
        autoWirelessAdbSwitch.setChecked(readPref(KEY_AUTO_WIRELESS_ADB, false));
        unlockLimitSwitch.setChecked(readPref(KEY_UNLOCK_LIMIT, true));
        syncing = false;
    }

    private boolean readPref(String key, boolean def) {
        return ModulePrefs.get(this).getBoolean(key, def);
    }

    private void writePref(String key, boolean value) {
        ModulePrefs.get(this).edit().putBoolean(key, value).commit();
    }

    private boolean isLightTheme() {
        return getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LIGHT_THEME, true);
    }
}
