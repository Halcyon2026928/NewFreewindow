package io.github.halcyon2026928.freewindow;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[freewindow-hook]";

    /** 配置在模块 app 的 SharedPreferences 中（与 SettingsActivity 一致），经 ConfigProvider 暴露。 */
    private static final String KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar";
    private static final String KEY_HIDE_THREE_DOT = "hide_three_dot_menu";

    /** 设为 true 时会把 FlexibleTaskCaptionView 的完整视图树打印到 Xposed 日志，便于在真机上确认三点按钮的确切 id。 */
    private static final boolean DUMP_CAPTION_TREE = false;

    /** 开关状态。首次自由小窗创建时经 ConfigProvider 跨进程读取（Binder/Provider 机制，
     *  无 SELinux/ROM 写入限制）；修改后重启系统框架，重新打开自由小窗即按新配置生效。 */
    private boolean hideBottomBar = true;
    private boolean hideThreeDot = true;
    private boolean configLoaded = false;

    /** 系统框架 ClassLoader。 */
    private static ClassLoader sClassLoader;
    /** 当前点击的悬浮球 View。 */
    private static Object sCurrentFloatHandleView;
    /** 坞窗容器（全局唯一）。 */
    private static FrameLayout sDockContainer;
    /** 坞窗中的应用列表。 */
    private static final List<Object> sDockApps = new ArrayList<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("android".equals(lpparam.packageName)) {
            sClassLoader = lpparam.classLoader;
            hookSystemServer(lpparam);
        }
    }

    /** 从模块 app 的 ConfigProvider 读取开关状态。
     *  每次创建自由小窗（initCaptionView）时重新读取——开关修改后重新打开自由小窗即生效，无需重启设备；
     *  读取成功则更新开关字段；读取失败（如 app 进程被清理/停止导致 provider 不可达）则
     *  沿用上次成功读取的配置（缓存兜底），避免“清理后台后重开自由小窗”时误回退为全部显示。 */
    private void loadConfig(Object captionView) {
        try {
            Context ctx = (Context) XposedHelpers.getObjectField(captionView, "mContext");
            ContentResolver cr = ctx.getContentResolver();
            Cursor c = cr.query(ConfigProvider.CONFIG_URI, null, null, null, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        String key = c.getString(0);
                        int value = c.getInt(1);
                        if (KEY_HIDE_BOTTOM_BAR.equals(key)) {
                            hideBottomBar = value == 1;
                        } else if (KEY_HIDE_THREE_DOT.equals(key)) {
                            hideThreeDot = value == 1;
                        }
                    }
                    configLoaded = true;
                    XposedBridge.log(TAG + " config -> hideBottomBar=" + hideBottomBar
                            + " hideThreeDot=" + hideThreeDot);
                } finally {
                    c.close();
                }
            } else {
                XposedBridge.log(TAG + " loadConfig: provider cursor null, keep cached config");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " loadConfig error, keep cached config: " + t.getMessage());
        }
        // 读取失败：不重置 configLoaded，沿用上次成功读取的开关状态
    }

    private void hookSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> captionViewClass = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleTaskCaptionView", lpparam.classLoader);

            // 注册 initCaptionView：首次自由小窗创建时读取开关配置，并按开关执行
            XposedHelpers.findAndHookMethod(captionViewClass, "initCaptionView",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object thisObj = param.thisObject;
                                loadConfig(thisObj);
                                // 配置读取失败（如重启后模块app未启动）时，使用默认值 true（都隐藏），
                                // 避免重启后白条和菜单栏默认显示，需要手动重新开关才生效的问题

                                // ---- 隐藏底部手势小横条（开关：hide_bottom_bar） ----
                                if (hideBottomBar) {
                                    View bottomFrame = (View) XposedHelpers.getObjectField(thisObj, "mBottomFrame");
                                    View bottomHandleView = (View) XposedHelpers.getObjectField(thisObj, "mBottomHandleView");
                                    View bottomHandleLongView = (View) XposedHelpers.getObjectField(thisObj, "mBottomHandleLongView");

                                    if (bottomFrame != null) bottomFrame.setVisibility(View.GONE);
                                    if (bottomHandleView != null) bottomHandleView.setVisibility(View.GONE);
                                    if (bottomHandleLongView != null) bottomHandleLongView.setVisibility(View.GONE);
                                    XposedBridge.log(TAG + " hidden freeform bottom handle bar");
                                }

                                // ---- 移除顶部三点菜单按钮（开关：hide_three_dot_menu） ----
                                if (hideThreeDot && thisObj instanceof View) {
                                    View captionRoot = (View) thisObj;
                                    if (DUMP_CAPTION_TREE) {
                                        dumpViewTree(captionRoot, 0);
                                    }
                                    View menuButton = removeThreeDotMenuButton(captionRoot);
                                    if (menuButton != null) {
                                        XposedBridge.log(TAG + " removed freeform three-dot menu button: " + describe(menuButton));
                                    } else {
                                        XposedBridge.log(TAG + " three-dot menu button not found (try DUMP_CAPTION_TREE=true to locate its id)");
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " initCaptionView hook error: " + t.getMessage());
                            }
                        }
                    });
            XposedBridge.log(TAG + " FlexibleTaskCaptionView.initCaptionView hook success");

            // 第二层：拦截三点按钮区域的点击功能（开关：hide_three_dot_menu）
            hookSingleTapUpMenuBlock(captionViewClass);

            // 第三层：让触摸穿透应用（开关：hide_three_dot_menu）
            hookTouchThroughCarve(captionViewClass);

            // 第四层：点击悬浮球弹出坞窗
            hookFloatHandleClick(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook FlexibleTaskCaptionView failed: " + t.getMessage());
        }
    }

    /**
     * hook FlexibleCaptionView.updateTouchableRegion(Region)：
     * 每次标题栏把触摸区域应用到 Surface 前，把三点按钮触摸区域挖空，
     * 使该位置触摸穿透到自由小窗中的应用。
     *
     * 注意：ColorOS16 上 mHandleButtonRegion 不是三点按钮的小区域，
     * 而是整个标题栏拖动区域（如 [243,-52][1029,142]，宽786高194），
     * 直接挖空会导致小窗无法拖动。因此当区域过大时，
     * 只挖空右上角三点按钮附近的小区域（64x64），保留拖动条。
     */
    private void hookTouchThroughCarve(Class<?> captionViewClass) {
        try {
            Class<?> baseClass = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleCaptionView", captionViewClass.getClassLoader());

            XposedHelpers.findAndHookMethod(baseClass, "updateTouchableRegion", Region.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!hideThreeDot) return;

                                Region region = (Region) param.args[0];
                                if (region == null) return;

                                Object regionMgr = XposedHelpers.getObjectField(param.thisObject, "mFlexibleCaptionViewRegionMgr");
                                if (regionMgr == null) return;

                                Rect btn = (Rect) XposedHelpers.getObjectField(regionMgr, "mHandleButtonRegion");
                                if (btn == null || btn.isEmpty()) return;

                                int w = btn.width();
                                int h = btn.height();
                                // ColorOS17: mHandleButtonRegion 是三点按钮小区域（通常 < 100x100）
                                // ColorOS16: mHandleButtonRegion 是整个标题栏拖动区域（宽 > 200）
                                if (w > 200 || h > 120) {
                                    // 只挖空右上角三点按钮附近的小区域（64x64），保留拖动条
                                    int carveSize = 64;
                                    int carveLeft = btn.right - carveSize;
                                    int carveTop = btn.top;
                                    int carveRight = btn.right;
                                    int carveBottom = Math.min(btn.top + carveSize, btn.bottom);
                                    region.op(carveLeft, carveTop, carveRight, carveBottom, Region.Op.DIFFERENCE);
                                    XposedBridge.log(TAG + " carved small three-dot region (ColorOS16 compat): ["
                                            + carveLeft + "," + carveTop + "][" + carveRight + "," + carveBottom + "]"
                                            + " (original mHandleButtonRegion=" + btn.toShortString() + ")");
                                } else {
                                    region.op(btn.left, btn.top, btn.right, btn.bottom, Region.Op.DIFFERENCE);
                                    XposedBridge.log(TAG + " carved three-dot touch region for touch-through: "
                                            + btn.toShortString());
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " updateTouchableRegion carve error: " + t.getMessage());
                            }
                        }
                    });
            XposedBridge.log(TAG + " touch-through carve hook success");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook updateTouchableRegion failed: " + t.getMessage());
        }
    }

    /**
     * 拦截 FlexibleCaptionView.onSingleTapUp(MotionEvent)：
     * 当 actionFlag == HANDLE_EVENT(1) 且触摸点位于三点按钮触摸区域（mHandleButtonRegion）时，
     * 直接返回 false，阻止系统弹出自由小窗菜单。
     */
    private void hookSingleTapUpMenuBlock(Class<?> captionViewClass) {
        try {
            Class<?> baseClass = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleCaptionView", captionViewClass.getClassLoader());

            XposedHelpers.findAndHookMethod(baseClass, "onSingleTapUp", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                // 直接用开关状态（配置读取失败时使用默认值 true）
                                if (!hideThreeDot) return; // 开关关闭：不做点击拦截

                                Object thisObj = param.thisObject;
                                Object regionMgr = XposedHelpers.getObjectField(thisObj, "mFlexibleCaptionViewRegionMgr");
                                if (regionMgr == null) return;

                                int actionFlag = (int) XposedHelpers.getIntField(thisObj, "mActionFlag");
                                if (actionFlag != 1) return; // HANDLE_EVENT，仅三点/把手按钮区

                                Rect region = (Rect) XposedHelpers.getObjectField(regionMgr, "mHandleButtonRegion");
                                if (region == null || region.isEmpty()) return;

                                MotionEvent ev = (MotionEvent) param.args[0];
                                if (region.contains((int) ev.getX(), (int) ev.getY())) {
                                    XposedBridge.log(TAG + " blocked three-dot menu tap at " + (int) ev.getX() + "," + (int) ev.getY());
                                    param.setResult(false);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " onSingleTapUp block error: " + t.getMessage());
                            }
                        }
                    });
            XposedBridge.log(TAG + " FlexibleCaptionView.onSingleTapUp menu block hook success");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook onSingleTapUp failed: " + t.getMessage());
        }
    }

    /**
     * 移除顶部“三点菜单按钮”（自由小窗的“浮窗控制器”）。
     *
     * 真机视图树确认（ColorOS，oplus 资源包）：
     *   flexible_button
     *     ├─ flexible_toolbar_mode   （工具栏：handle/full/mini/closed）
     *     └─ flexible_simple_mode    （简单模式，即截图中的三点控制器）
     *         ├─ control_bg
     *         └─ control_flexible    desc=浮窗控制器 ← 三点按钮本体
     *             ├─ control_left_ellipse / control_center_ellipse / control_right_ellipse（三个点）
     *
     * 主路径：按资源名精确移除 flexible_simple_mode 整个容器（连三点按钮带背景一起清掉，干净无残留）；
     * 若新版布局改名，则退化为移除 control_flexible，再退化为语义/兜底查找。
     */
    private View removeThreeDotMenuButton(View root) {
        if (root == null) return null;

        View simpleMode = findViewByResName(root, "flexible_simple_mode");
        if (simpleMode != null) {
            removeViewCleanly(simpleMode);
            return simpleMode;
        }
        View control = findViewByResName(root, "control_flexible");
        if (control != null) {
            removeViewCleanly(control);
            return control;
        }

        // 兜底：语义（menu/more/overflow/更多/菜单）+ 保守启发式，并排除把手类视图
        View semantic = findMenuButtonBySemantics(root);
        if (semantic != null) {
            removeViewCleanly(semantic);
            return semantic;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (isLikelyMenuButton(child)) {
                    removeViewCleanly(child);
                    return child;
                }
            }
        }
        return null;
    }

    /** 按资源名（如 "oplus:id/flexible_simple_mode"）在视图树中递归查找视图。 */
    private View findViewByResName(View view, String simpleResName) {
        if (view == null) return null;
        try {
            int id = view.getId();
            if (id != View.NO_ID) {
                String resName = view.getResources().getResourceName(id);
                if (resName != null && resName.endsWith("/" + simpleResName)) {
                    return view;
                }
            }
        } catch (Throwable ignored) {
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View hit = findViewByResName(group.getChildAt(i), simpleResName);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private View findMenuButtonBySemantics(View view) {
        if (view == null) return null;

        if (isSemanticMenuButton(view)) return view;

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View hit = findMenuButtonBySemantics(group.getChildAt(i));
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** 通过 id 资源名 / contentDescription 判断是否是“更多/菜单”按钮。 */
    private boolean isSemanticMenuButton(View v) {
        // 先排除把手类视图（handle），避免误伤底部把手/把手图标
        if (isHandleView(v)) return false;

        // id 资源名
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                String resName = v.getResources().getResourceName(id).toLowerCase();
                if (resName.contains("menu") || resName.contains("more")
                        || resName.contains("overflow")) {
                    // 排除通用的 Menu 类资源误报（例如标题为 menu 的容器）：只接受按钮型视图
                    if (isButtonLike(v)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        // contentDescription
        CharSequence cd = v.getContentDescription();
        if (cd != null) {
            String s = cd.toString().toLowerCase();
            if (s.contains("更多") || s.contains("菜单") || s.contains("menu") || s.contains("more")
                    || s.contains("overflow")) {
                if (isButtonLike(v)) return true;
            }
        }
        return false;
    }

    /** 判断视图是否是“把手”类（底部横条/把手），这些不是三点菜单按钮。 */
    private boolean isHandleView(View v) {
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                String resName = v.getResources().getResourceName(id).toLowerCase();
                if (resName.contains("handle")) return true;
            }
        } catch (Throwable ignored) {
        }
        CharSequence cd = v.getContentDescription();
        if (cd != null) {
            String s = cd.toString();
            if (s.contains("把手") || s.contains("handle")) return true;
        }
        return false;
    }

    /** 判断是否像按钮（可点击或为 ImageView/ImageButton 等控件型）。 */
    private boolean isButtonLike(View v) {
        return v.isClickable() || v instanceof ImageView
                || v.getClass().getSimpleName().toLowerCase().contains("imagebutton")
                || v.getClass().getSimpleName().toLowerCase().contains("button");
    }

    /**
     * 兜底启发式：判断一个顶层子视图是否“可能”是三点菜单按钮。
     * 只在语义定位失败时使用，且做了保守过滤，避免误删关闭/最小化按钮。
     */
    private boolean isLikelyMenuButton(View v) {
        if (v == null || !isButtonLike(v)) return false;
        if (isHandleView(v)) return false;
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                String resName = v.getResources().getResourceName(id).toLowerCase();
                // 明确是关闭/最小化/最大化/返回 的按钮，跳过
                if (resName.contains("close") || resName.contains("minimize")
                        || resName.contains("maximize") || resName.contains("back")) {
                    return false;
                }
            }
        } catch (Throwable ignored) {
        }
        // 语义兜底：控件型、可点击、且没有被识别为关闭类 → 在标题栏顶层视为候选。
        // 说明：若真机上此候选不是三点按钮，请用 DUMP_CAPTION_TREE=true 定位真实 id 后收紧匹配。
        return true;
    }

    /** 从父容器中干净移除视图（真正删掉而非隐藏）；若没有父容器则退化为 GONE。 */
    private void removeViewCleanly(View v) {
        if (v == null) return;
        ViewGroup parent = (v.getParent() instanceof ViewGroup) ? (ViewGroup) v.getParent() : null;
        if (parent != null) {
            try {
                parent.removeView(v);
                return;
            } catch (Throwable ignored) {
            }
        }
        v.setVisibility(View.GONE);
    }

    /** 生成便于阅读的视图描述，用于日志。 */
    private String describe(View v) {
        StringBuilder sb = new StringBuilder(v.getClass().getSimpleName());
        sb.append('@').append(Integer.toHexString(v.hashCode()));
        try {
            int id = v.getId();
            if (id != View.NO_ID) {
                sb.append(" id=").append(v.getResources().getResourceName(id));
            }
        } catch (Throwable ignored) {
        }
        CharSequence cd = v.getContentDescription();
        if (cd != null && cd.length() > 0) sb.append(" desc=").append(cd);
        return sb.toString();
    }

    /** 打印标题栏视图树（用于在真机上确认三点按钮 id）。 */
    private void dumpViewTree(View v, int depth) {
        if (v == null) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(describe(v));
        XposedBridge.log(TAG + " tree: " + sb);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) dumpViewTree(g.getChildAt(i), depth + 1);
        }
    }

    // ==================== 悬浮球坞窗 ====================

    /**
     * hook FloatHandleView.startFlexibleFreeformFromHandleView(FloatHandleInfo, String)：
     * 拦截点击悬浮球的默认行为（恢复自由小窗），改为弹出坞窗。
     */
    private void hookFloatHandleClick(final ClassLoader cl) {
        try {
            Class<?> fhvClass = XposedHelpers.findClass("com.android.server.wm.floathandle.FloatHandleView", cl);
            Class<?> fhiClass = XposedHelpers.findClass("com.android.server.wm.floathandle.FloatHandleInfo", cl);
            XposedHelpers.findAndHookMethod(fhvClass, "startFlexibleFreeformFromHandleView",
                    fhiClass, String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object floatHandleView = param.thisObject;
                                Object floatHandleInfo = param.args[0];
                                String reason = (String) param.args[1];
                                XposedBridge.log(TAG + " float handle clicked, reason=" + reason);
                                // 阻止默认恢复自由小窗
                                param.setResult(null);
                                sCurrentFloatHandleView = floatHandleView;
                                showFloatDock(floatHandleView, floatHandleInfo, cl);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " hookFloatHandleClick error: " + t.getMessage());
                            }
                        }
                    });
            XposedBridge.log(TAG + " FloatHandleView click hook success");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookFloatHandleClick failed: " + t.getMessage());
        }
    }

    /** 显示坞窗：从悬浮球所在侧滑出，展示所有悬浮球应用卡片。 */
    private void showFloatDock(Object floatHandleView, Object floatHandleInfo, ClassLoader cl) {
        try {
            if (sDockContainer != null) {
                removeFloatDock();
            }

            // 获取 Context
            Context ctx = (Context) XposedHelpers.callMethod(floatHandleView, "getContext");
            if (ctx == null) {
                XposedBridge.log(TAG + " showFloatDock: context is null");
                return;
            }

            // 获取屏幕尺寸
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            int screenWidth = ctx.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = ctx.getResources().getDisplayMetrics().heightPixels;

            // 判断悬浮球在左边还是右边
            int side = 1; // 默认右边
            try {
                side = XposedHelpers.getIntField(floatHandleInfo, "side");
            } catch (Throwable ignored) {
            }
            XposedBridge.log(TAG + " showFloatDock side=" + side);

            // 隐藏所有悬浮球（用 setAlpha，不能用 setVisibility 否则丢失圆角）
            hideAllFloatHandles(cl, 0f);

            // 全屏透明容器
            final FrameLayout container = new FrameLayout(ctx);
            container.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // 坞窗主体
            LinearLayout dock = new LinearLayout(ctx);
            dock.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable dockBg = new GradientDrawable();
            dockBg.setColor(0xE5E5E5E5); // #e5e5e5 with alpha
            int dockCorner = dpToPx(ctx, 20);
            if (side == 1) {
                // 右边弹出，左边圆角
                dockBg.setCornerRadii(new float[]{dockCorner, dockCorner, 0, 0, 0, 0, dockCorner, dockCorner});
            } else {
                // 左边弹出，右边圆角
                dockBg.setCornerRadii(new float[]{0, 0, dockCorner, dockCorner, dockCorner, dockCorner, 0, 0});
            }
            dock.setBackground(dockBg);

            int dockWidth = screenWidth * 4 / 9;
            int dockHeight = screenHeight - dpToPx(ctx, 100); // 上下各留50dp
            FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(dockWidth, dockHeight);
            dockParams.gravity = Gravity.CENTER_VERTICAL | (side == 1 ? Gravity.END : Gravity.START);
            container.addView(dock, dockParams);

            // 滚动区域（应用卡片列表）
            ScrollView scrollView = new ScrollView(ctx);
            scrollView.setBackgroundColor(0x00000000); // 透明
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            scrollView.setLayoutParams(scrollParams);

            LinearLayout cardList = new LinearLayout(ctx);
            cardList.setOrientation(LinearLayout.VERTICAL);
            cardList.setPadding(dpToPx(ctx, 12), dpToPx(ctx, 12), dpToPx(ctx, 12), dpToPx(ctx, 12));
            scrollView.addView(cardList);
            dock.addView(scrollView);

            // 清除按钮
            TextView clearBtn = new TextView(ctx);
            clearBtn.setText("清除");
            clearBtn.setTextColor(0xFF000000);
            clearBtn.setTextSize(15);
            clearBtn.setGravity(Gravity.CENTER);
            GradientDrawable clearBg = new GradientDrawable();
            clearBg.setColor(0xFFF3F3F3);
            clearBg.setCornerRadius(dpToPx(ctx, 24));
            clearBtn.setBackground(clearBg);
            LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
                    dockWidth - dpToPx(ctx, 60), dpToPx(ctx, 48));
            clearParams.gravity = Gravity.CENTER_HORIZONTAL;
            clearParams.bottomMargin = dpToPx(ctx, 25);
            clearParams.topMargin = dpToPx(ctx, 5);
            dock.addView(clearBtn, clearParams);

            // 获取所有悬浮球应用
            sDockApps.clear();
            try {
                Object fhc = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl), "getInstance");
                Object infoList = XposedHelpers.getObjectField(fhc, "mFloatHandleInfoList");
                if (infoList instanceof List) {
                    for (Object info : (List<?>) infoList) {
                        sDockApps.add(info);
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " get float handle list error: " + t.getMessage());
            }

            XposedBridge.log(TAG + " dock apps count=" + sDockApps.size());

            // 添加应用卡片
            for (Object info : sDockApps) {
                try {
                    addAppCard(ctx, cardList, info, cl);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " add app card error: " + t.getMessage());
                }
            }

            // 清除按钮点击
            final ClassLoader finalCl = cl;
            clearBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        clearAllDockApps(finalCl);
                        removeFloatDock();
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " clear all error: " + t.getMessage());
                    }
                }
            });

            // 点击坞窗外收回，点击坞窗内不拦截
            final LinearLayout finalDock = dock;
            container.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        int[] loc = new int[2];
                        finalDock.getLocationOnScreen(loc);
                        float x = event.getRawX();
                        float y = event.getRawY();
                        boolean insideDock = x >= loc[0] && x <= loc[0] + finalDock.getWidth()
                                && y >= loc[1] && y <= loc[1] + finalDock.getHeight();
                        if (!insideDock) {
                            removeFloatDock();
                            return true;
                        }
                    }
                    return false;
                }
            });

            // 窗口参数
            WindowManager.LayoutParams windowParams = new WindowManager.LayoutParams(2520); // TYPE_SYSTEM_EDGE_PANEL
            windowParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            try {
                Field pf = WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
                pf.setAccessible(true);
                pf.setInt(windowParams, 16); // PRIVATE_FLAG_TRUSTED_OVERLAY
            } catch (Throwable ignored) {
            }
            windowParams.format = PixelFormat.TRANSLUCENT;
            windowParams.width = screenWidth;
            windowParams.height = screenHeight;
            windowParams.gravity = Gravity.TOP | Gravity.START;
            windowParams.setTitle("FreeWindowDock");

            wm.addView(container, windowParams);
            sDockContainer = container;

            // 滑入动画
            dock.setTranslationX(side == 1 ? dockWidth : -dockWidth);
            dock.animate().translationX(0).setDuration(250).start();

            XposedBridge.log(TAG + " showed float dock, side=" + side + ", apps=" + sDockApps.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " showFloatDock error: " + t.getMessage());
        }
    }

    /** 添加一个应用卡片到坞窗。 */
    private void addAppCard(Context ctx, LinearLayout cardList, Object floatHandleInfo, ClassLoader cl) {
        // 获取包名和任务信息（FloatHandleInfo 直接有 getPackageName/getUserId 和 taskId 字段）
        String pkgName = "";
        int userId = 0;
        int taskId = -1;
        try {
            pkgName = (String) XposedHelpers.callMethod(floatHandleInfo, "getPackageName");
            userId = (int) XposedHelpers.callMethod(floatHandleInfo, "getUserId");
            taskId = XposedHelpers.getIntField(floatHandleInfo, "taskId");
        } catch (Throwable ignored) {
        }
        XposedBridge.log(TAG + " addAppCard pkg=" + pkgName + " taskId=" + taskId + " userId=" + userId);

        // 卡片容器（透明背景）
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dpToPx(ctx, 10);
        cardList.addView(card, cardParams);

        // 顶部栏：图标 + 名称 + 三点菜单
        LinearLayout topBar = new LinearLayout(ctx);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dpToPx(ctx, 10), dpToPx(ctx, 8), dpToPx(ctx, 10), dpToPx(ctx, 8));
        card.addView(topBar);

        // 应用图标
        ImageView icon = new ImageView(ctx);
        int iconSize = dpToPx(ctx, 36);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        topBar.addView(icon, iconParams);
        try {
            PackageManager pm = ctx.getPackageManager();
            // getApplicationInfoAsUser 是隐藏API，用反射调用
            ApplicationInfo appInfo = (ApplicationInfo) XposedHelpers.callMethod(pm,
                    "getApplicationInfoAsUser", pkgName, 512, userId);
            icon.setImageDrawable(pm.getApplicationIcon(appInfo));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " get icon error: " + t.getMessage());
        }

        // 应用名称
        TextView nameTv = new TextView(ctx);
        nameTv.setTextSize(14);
        nameTv.setTextColor(0xFF000000);
        nameTv.setMaxLines(1);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.leftMargin = dpToPx(ctx, 10);
        topBar.addView(nameTv, nameParams);
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo appInfo = (ApplicationInfo) XposedHelpers.callMethod(pm,
                    "getApplicationInfoAsUser", pkgName, 512, userId);
            CharSequence label = pm.getApplicationLabel(appInfo);
            nameTv.setText(label != null ? label : pkgName);
        } catch (Throwable t) {
            nameTv.setText(pkgName.isEmpty() ? "未知" : pkgName);
        }

        // 三点菜单按钮
        final TextView menuBtn = new TextView(ctx);
        menuBtn.setText("⋮");
        menuBtn.setTextSize(22);
        menuBtn.setTextColor(0xFF333333);
        menuBtn.setGravity(Gravity.CENTER);
        menuBtn.setPadding(dpToPx(ctx, 12), dpToPx(ctx, 4), dpToPx(ctx, 12), dpToPx(ctx, 4));
        topBar.addView(menuBtn);

        final String finalPkgName = pkgName;
        final int finalUserId = userId;
        final int finalTaskId = taskId;
        final Object finalInfo = floatHandleInfo;
        final ClassLoader finalCl = cl;

        menuBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAppMenu(ctx, menuBtn, finalPkgName, finalUserId, finalTaskId, finalInfo, finalCl);
            }
        });

        // 灰色占位区（预览区）
        View preview = new View(ctx);
        preview.setBackgroundColor(0xFFD0D0D0);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(ctx, 200));
        previewParams.leftMargin = dpToPx(ctx, 10);
        previewParams.rightMargin = dpToPx(ctx, 10);
        previewParams.bottomMargin = dpToPx(ctx, 10);
        card.addView(preview, previewParams);
    }

    /** 显示单个应用的弹出菜单（取消运行、取消静音、关闭）。 */
    private void showAppMenu(Context ctx, View anchor, final String pkgName, final int userId,
                             final int taskId, final Object floatHandleInfo, final ClassLoader cl) {
        final android.widget.PopupMenu popup = new android.widget.PopupMenu(ctx, anchor);
        popup.getMenu().add("取消运行");
        popup.getMenu().add("取消静音");
        popup.getMenu().add("关闭");
        popup.setOnMenuItemClickListener(new android.widget.PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                String title = item.getTitle().toString();
                if ("取消运行".equals(title)) {
                    removeSingleApp(pkgName, userId, taskId, floatHandleInfo, cl);
                    removeFloatDock();
                } else if ("关闭".equals(title)) {
                    // 只关闭菜单
                }
                return true;
            }
        });
        popup.show();
    }

    /** 清除单个应用：用官方 removeFloatHandle 移除悬浮球 + 杀进程。 */
    private void removeSingleApp(String pkgName, int userId, int taskId, Object floatHandleInfo, ClassLoader cl) {
        try {
            // 官方逻辑：removeFloatHandle(int taskId, null, true, 5)
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl), "getInstance");
            XposedHelpers.callMethod(fhc, "removeFloatHandle", taskId, null, true, 5);
            XposedBridge.log(TAG + " official removeFloatHandle taskId=" + taskId);
            // 杀进程
            try {
                Object am = XposedHelpers.callStaticMethod(android.app.ActivityManager.class, "getService");
                XposedHelpers.callMethod(am, "forceStopPackage", pkgName, userId);
                XposedBridge.log(TAG + " force stopped " + pkgName);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " forceStop error: " + t.getMessage());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " removeSingleApp error: " + t.getMessage());
        }
    }

    /** 清除坞窗中所有应用。 */
    private void clearAllDockApps(ClassLoader cl) {
        for (Object info : new ArrayList<>(sDockApps)) {
            try {
                String pkgName = (String) XposedHelpers.callMethod(info, "getPackageName");
                int userId = (int) XposedHelpers.callMethod(info, "getUserId");
                int taskId = XposedHelpers.getIntField(info, "taskId");
                removeSingleApp(pkgName, userId, taskId, info, cl);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " clear app error: " + t.getMessage());
            }
        }
        sDockApps.clear();
    }

    /** 移除坞窗，恢复悬浮球显示。 */
    private void removeFloatDock() {
        if (sDockContainer != null) {
            try {
                Context ctx = sDockContainer.getContext();
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                wm.removeView(sDockContainer);
            } catch (Throwable ignored) {
            }
            sDockContainer = null;
        }
        // 恢复悬浮球显示
        if (sClassLoader != null) {
            hideAllFloatHandles(sClassLoader, 1f);
        }
    }

    /** 隐藏/显示所有悬浮球（alpha=0 时同时 hide，阻止点击；alpha=1 时 show）。 */
    private void hideAllFloatHandles(ClassLoader cl, float alpha) {
        try {
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl), "getInstance");
            Object infoList = XposedHelpers.getObjectField(fhc, "mFloatHandleInfoList");
            if (infoList instanceof List) {
                for (Object info : (List<?>) infoList) {
                    try {
                        Object task = XposedHelpers.getObjectField(info, "task");
                        if (task != null) {
                            Object sc = XposedHelpers.callMethod(task, "getSurfaceControl");
                            if (sc != null) {
                                Object tx = XposedHelpers.newInstance(
                                        XposedHelpers.findClass("android.view.SurfaceControl$Transaction", cl));
                                XposedHelpers.callMethod(tx, "setAlpha", sc, alpha);
                                if (alpha == 0f) {
                                    XposedHelpers.callMethod(tx, "hide", sc);
                                } else {
                                    XposedHelpers.callMethod(tx, "show", sc);
                                }
                                XposedHelpers.callMethod(tx, "apply");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hideAllFloatHandles error: " + t.getMessage());
        }
    }

    /** dp 转 px。 */
    private int dpToPx(Context ctx, int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics());
    }
}
