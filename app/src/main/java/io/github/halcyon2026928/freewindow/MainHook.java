package io.github.halcyon2026928.freewindow;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.lang.reflect.Field;

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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("android".equals(lpparam.packageName)) {
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
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook FlexibleTaskCaptionView failed: " + t.getMessage());
        }
    }

    /**
     * hook FlexibleCaptionView.updateTouchableRegion(Region)：
     * 每次标题栏把触摸区域应用到 Surface 前，把三点按钮触摸区域挖空，
     * 使该位置触摸穿透到自由小窗中的应用。
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
                                // 直接用开关状态（配置读取失败时使用默认值 true）
                                if (!hideThreeDot) return; // 开关关闭：不做触摸区域挖空

                                Region region = (Region) param.args[0];
                                if (region == null) return;

                                Object regionMgr = XposedHelpers.getObjectField(param.thisObject, "mFlexibleCaptionViewRegionMgr");
                                if (regionMgr == null) return;

                                Rect btn = (Rect) XposedHelpers.getObjectField(regionMgr, "mHandleButtonRegion");
                                if (btn == null || btn.isEmpty()) return;

                                region.op(btn.left, btn.top, btn.right, btn.bottom, Region.Op.DIFFERENCE);
                                XposedBridge.log(TAG + " carved three-dot touch region for touch-through: "
                                        + btn.toShortString());
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
}
