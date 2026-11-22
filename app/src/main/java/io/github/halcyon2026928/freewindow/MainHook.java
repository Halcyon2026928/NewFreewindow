package io.github.halcyon2026928.freewindow;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;


public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "[freewindow-hook]";

    /** 解除后允许同时存在的自由小窗数量（系统默认只有 2）。 */
    private static final int MAX_FLOAT_HANDLE_COUNT = 12;

    /** 配置在模块 app 的 SharedPreferences 中（与 SettingsActivity 一致），经 ConfigProvider 暴露。 */
    private static final String KEY_HIDE_BOTTOM_BAR = "hide_bottom_bar";
    private static final String KEY_HIDE_THREE_DOT = "hide_three_dot_menu";
    private static final String KEY_ENABLE_DOCK = "enable_float_dock";
    /** 解除自由小窗数量上限的开关（关掉就退回系统默认的 2 个）。 */
    private static final String KEY_UNLOCK_LIMIT = "unlock_float_limit";
    /** App 里的主题模式（0=跟随系统，1=浅色，2=深色），坞窗配色跟着它走。 */
    private static final String KEY_THEME_MODE = "theme_mode";
    /** 坞窗卡片是否显示实时画面；关闭时卡片只显示静态快照（点击和上下滚动照常）。 */
    private static final String KEY_LIVE_PREVIEW = "live_preview";

    /** 设为 true 时会把 FlexibleTaskCaptionView 的完整视图树打印到 Xposed 日志，便于在真机上确认三点按钮的确切 id。 */
    private static final boolean DUMP_CAPTION_TREE = false;

    /** 开关状态。首次自由小窗创建或点击悬浮球时经 ConfigProvider 跨进程读取。
     *  读取失败则沿用上次成功读取的配置（缓存兜底）。 */
    private boolean hideBottomBar = true;
    private boolean hideThreeDot = true;
    private boolean enableDock = true;
    /** 解除数量上限开关；关掉时上限 hook 一个都不装，系统保持官方 2 个。 */
    private boolean unlockFloatLimit = true;
    /** App 的主题模式（0=跟随系统，1=浅色，2=深色）。坞窗配色跟它同步。 */
    private int themeMode = 0;
    /** 坞窗卡片实时画面开关；关闭时卡片只显示静态快照，不挂任务层、也不开热区。 */
    private boolean livePreview = true;
    private boolean configLoaded = false;

    /** 系统框架 ClassLoader。 */
    private static ClassLoader sClassLoader;
    /** 当前点击的悬浮球 View。 */
    private static Object sCurrentFloatHandleView;
    /** 悬浮球所在线程（android.ui）的 Handler，点击那一刻抓取，恢复后用它解除 sRestoring。 */
    private static Handler sHandleHandler;
    /** 上一次画到悬浮球上的应用（记忆）：恢复某个应用后列表清空时用它把图标补回去。 */
    private static String sLastHandlePkg;
    private static int sLastHandleUserId;
    /** 坞窗容器（全局唯一）。 */
    private static FrameLayout sDockContainer;
    /** 坞窗外面那层全屏透明遮罩：专门吃掉「点坞窗外」那一下，避免穿透到桌面图标。 */
    private static View sDockMask;
    private static View sDockPanel;
    /** 菜单子窗口的 WindowManager（菜单以子窗口形式出现时非空） */
    private static WindowManager sAppMenuWm;
    private static int sDockSide = 1;
    private static boolean sDockClosing = false;
    /** A system-gesture close keeps Task parents locked in their card until final cleanup. */
    private static volatile boolean sDockCloseDuringSystemGesture = false;
    /** Task whose live card was handed to Android's native Back transition. */
    private static volatile int sDockBackRestoreTaskId = -1;
    /** 探测 DisplayPolicy 匿名回调实现时的编号上限（真正命中一个就停）。 */
    private static final int GESTURE_CLASS_PROBE_LIMIT = 40;
    /** 已经挂过手势回调的类名，防止两条路径摸到同一个类重复挂。 */
    private static final java.util.Set<String> sGestureCallbackClasses =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    /**
     * 官方首开动画里那块纯色底板（FlexibleFloatHandleAnimationSpec.mMaskSurface）。
     * 它在 defaultCallAnimationStart 里被 show + setColor(handleViewContainerBackgroundColor)、
     * setAlpha(1.0f)，就是首帧那块直角灰板。登记成 live 坞窗专属后，事务里对它的
     * show / setAlpha / setVisibility 都会被就地压掉。读多写少，用写时复制列表免锁。
     */
    private static final java.util.concurrent.CopyOnWriteArrayList<
            java.lang.ref.WeakReference<SurfaceControl>> sDockMaskSurfaces =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    /** 快速短路：没有登记项时不做任何额外判断。 */
    private static volatile int sDockMaskSurfaceCount = 0;
    /** 官方 lively 坞窗滑入滑出：PathInterpolator(0.28, 0.4, 0.2, 1) / 457ms */
    private static final long DOCK_ANIM_MS = 457L;
    private static final PathInterpolator DOCK_INTERPOLATOR = new PathInterpolator(0.28f, 0.4f, 0.2f, 1.0f);

    /** 坞窗卡片统一走「SurfaceControl reparent」：任务图层直接挂到卡片 SurfaceView 下面。 */
    /** 卡片内容区的圆角（dp）。 */
    /** 卡片内容区圆角（dp）。三层（SurfaceView / Background / 任务层）与挖洞统一用它。 */
    // 坞窗面板挖洞的圆角：用户定 15dp（统一口径）。
    static final int CARD_CONTENT_CORNER_DP = 15;
    // 卡片画面（SurfaceView + Background + 任务层 + 静态快照）的圆角：
    // 官方是 getDimensionPixelOffset(201654624) = 0x0c050160 dimen/TF18 = 35dp（mItemRadius），
    // 但用户要求全部统一为 15dp，这里跟面板同值。
    static final int CARD_ITEM_CORNER_DP = 15;
    /** 坞窗面板背景（不再挖洞，保留字段只为收尾时置空）。 */
    private static DockHoleDrawable sDockHoleBg;
    /** 真实小窗几何同步的节流状态（滚动时每帧都 resize 会把 WMS 拖死）。 */
    private static long sLastLiveSyncAt = 0L;
    private static boolean sLiveSyncPending = false;
    /** 坞窗窗口在屏幕上的偏移（用于计算菜单位置）。 */
    private static int sDockWindowX = 0;
    private static int sDockWindowY = 0;
    /** 坞窗中的应用列表。 */
    private static final List<Object> sDockApps = new ArrayList<>();
    /** 坞窗是否正在显示。 */
    private static volatile boolean sDockShowing = false;
    /** ActivityRecord state hook uses identity to spot navigation away from the app under the dock. */
    private static volatile Object sLastResumedActivity;
    private static volatile Object sDockOriginActivity;
    /** 坞窗当前是按横屏还是竖屏布局建的：屏幕旋转时用它判断要不要重建。 */
    private static boolean sDockLandscape = false;
    /** 建坞窗时用的 FloatHandleInfo，旋转重建时原样传回去（决定弹出侧）。 */
    private static Object sDockInfo;
    /** 方向轮询是否已在跑（坞窗存活期间每秒一次）。 */
    private static volatile boolean sOrientationWatch = false;
    private static final long ORIENTATION_CHECK_MS = 1000L;
    /** 还没抓到快照时，卡片画面先按 16:9 占位；抓到之后按快照真实比例建框。 */
    private static final float PREVIEW_ASPECT_FALLBACK = 1.7778f;
    /**
     * 固定画面比例：打开后卡片画面不再跟快照真实比例走，任何应用、任何转屏都用同一个比例。
     * 竖屏 16:9、横屏 13:9，与系统自由小窗的两个默认比例（1.7777778f / 1.4444444f）一致。
     * 画面内容仍按等比居中绘制：快照比框长就左右留边（露出坞窗底色），比框短就上下留边，不会变形。
     * 需要回退成「按快照真实比例」把这里改成 false 即可。
     */
    private static final boolean FIXED_PREVIEW_ASPECT = true;
    private static final float PREVIEW_ASPECT_PORTRAIT = 16f / 9f;    // 1.7778
    private static final float PREVIEW_ASPECT_LANDSCAPE = 13f / 9f;   // 1.4444
    /**
     * 横屏应用单独一条：它的画面本身是横的，按 9:16（高:宽 = 0.5625）建一个矮框，
     * 画面正好横向铺满，不会像竖框那样把横屏内容切成一条竖缝。
     */
    private static final float PREVIEW_ASPECT_LANDSCAPE_APP = 9f / 16f;  // 0.5625
    /** 我们自己正在走官方恢复路径：此时放行 startFlexibleFreeformFromHandleView，别再弹坞窗。 */
    private static volatile boolean sRestoring = false;

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
     *  沿用上次成功读取的配置（缓存兜底）。 */
    private void loadConfig(Object captionView) {
        try {
            Context ctx = (Context) XposedHelpers.getObjectField(captionView, "mContext");
            loadConfigFromContext(ctx);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " loadConfig error, keep cached config: " + t.getMessage());
        }
    }

    /** 从 LSPosed XSharedPreferences 读取（重启后仍有效），再用 ConfigProvider 覆盖一次以便当场改开关。 */
    private void loadConfigFromContext(Context ctx) {
        loadFromXSharedPrefs();
        if (ctx == null) return;
        try {
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
                        } else if (KEY_ENABLE_DOCK.equals(key)) {
                            enableDock = value == 1;
                        } else if (KEY_UNLOCK_LIMIT.equals(key)) {
                            unlockFloatLimit = value == 1;
                        } else if (KEY_THEME_MODE.equals(key)) {
                            themeMode = value;
                        } else if (KEY_LIVE_PREVIEW.equals(key)) {
                            livePreview = value == 1;
                        }
                    }
                    configLoaded = true;
                    // 交给 DockLive：卡片建的时候据此决定挂不挂实时层、开不开热区。
                    DockLive.setLivePreviewEnabled(livePreview);
                    XposedBridge.log(TAG + " config(provider) -> hideBottomBar=" + hideBottomBar
                            + " hideThreeDot=" + hideThreeDot
                            + " enableDock=" + enableDock
                            + " unlockFloatLimit=" + unlockFloatLimit
                            + " livePreview=" + livePreview);
                } finally {
                    c.close();
                }
            } else {
                XposedBridge.log(TAG + " loadConfig: provider cursor null, use XSharedPreferences");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " loadConfig provider fail, use XSharedPreferences: " + t.getMessage());
        }
    }

    private void loadFromXSharedPrefs() {
        try {
            XSharedPreferences prefs = new XSharedPreferences(ModulePrefs.PKG, ModulePrefs.NAME);
            prefs.makeWorldReadable();
            // 没解析到任何键（模块 app 被清理、文件权限没放开）时保持上次读到的值，
            // 不能拿默认值覆盖——否则退出模块 app 后台后所有开关都会跳回默认。
            if (!prefs.hasData()) {
                XposedBridge.log(TAG + " config(xprefs empty, keep cached) -> hideBottomBar="
                        + hideBottomBar + " hideThreeDot=" + hideThreeDot
                        + " enableDock=" + enableDock
                        + " unlockFloatLimit=" + unlockFloatLimit
                        + " livePreview=" + livePreview);
                return;
            }
            hideBottomBar = prefs.getBoolean(KEY_HIDE_BOTTOM_BAR, true);
            hideThreeDot = prefs.getBoolean(KEY_HIDE_THREE_DOT, true);
            enableDock = prefs.getBoolean(KEY_ENABLE_DOCK, true);
            unlockFloatLimit = prefs.getBoolean(KEY_UNLOCK_LIMIT, true);
            themeMode = prefs.getInt(KEY_THEME_MODE, 0);
            livePreview = prefs.getBoolean(KEY_LIVE_PREVIEW, true);
            configLoaded = true;
            DockLive.setLivePreviewEnabled(livePreview);
            XposedBridge.log(TAG + " config(xprefs readable=" + prefs.getFile().canRead()
                    + ") -> hideBottomBar=" + hideBottomBar
                    + " hideThreeDot=" + hideThreeDot
                    + " enableDock=" + enableDock
                    + " unlockFloatLimit=" + unlockFloatLimit);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " XSharedPreferences error: " + t.getMessage());
        }
    }

    private void hookSystemServer(XC_LoadPackage.LoadPackageParam lpparam) {
        // 先把开关读进来：数量上限的 hook 装不装由 unlock_float_limit 决定，
        // 所以必须在装机（重启）那一刻就知道开关状态。
        try {
            loadFromXSharedPrefs();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " load config before hook failed: " + t.getMessage());
        }

        hookDockDismissOnActivityChange(lpparam.classLoader);
        hookDockDismissOnBottomGesture(lpparam.classLoader);
        hookDockDismissOnBackNavigation(lpparam.classLoader);

        // 贴边保活：与 GUI 装饰项彼此独立，先装；失败不影响后续 hook。
        try {
            FloatKeepAliveHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FloatKeepAliveHook install failed: " + t.getMessage());
        }

        // 解除「自由小窗 / 迷你浮窗」的数量上限（系统默认 2）。
        // 两个机型限制落点不同：
        //   5200(Android 17 / ColorOS 17)：FloatHandleController.getLimitMaxFloatNum() 读字段 mLimitMaxFloatNum；
        //   3820(Android 16 / realme UI 7)：没有该方法，LIMIT_FLOAT_NUM = 2 被内联进 updateInfoMapForAdd
        //     （size == 2 时直接把第一个挤掉），所以必须接管这个方法。
        // 两条 hook 各自独立 try：一条找不到不能拖垮另一条。
        Class<?> fhcCls = null;
        try {
            fhcCls = XposedHelpers.findClass(
                    "com.android.server.wm.FloatHandleController", lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FloatHandleController not found: " + t.getMessage());
        }

        if (fhcCls != null && !unlockFloatLimit) {
            // 开关关掉：一条上限 hook 都不装，系统完全保持官方「最多 2 个」的行为。
            XposedBridge.log(TAG + " float limit unlock disabled by switch -> keep system default 2");
        }
        if (fhcCls != null && unlockFloatLimit) {
            // 5200 走这条：把上限字段顶到大值。
            boolean getterHooked = false;
            try {
                XposedHelpers.findAndHookMethod(fhcCls, "getLimitMaxFloatNum", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        param.setResult(MAX_FLOAT_HANDLE_COUNT);
                    }
                });
                getterHooked = true;
                XposedBridge.log(TAG + " float limit getter hooked");
            } catch (Throwable t) {
                XposedBridge.log(TAG + " float limit getter missing (ok on A16): " + t.getMessage());
            }
            try {
                XposedBridge.hookAllConstructors(fhcCls, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            XposedHelpers.setIntField(param.thisObject, "mLimitMaxFloatNum", MAX_FLOAT_HANDLE_COUNT);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " float limit field set failed: " + t.getMessage());
                        }
                    }
                });
            } catch (Throwable t) {
                XposedBridge.log(TAG + " float limit ctor hook failed: " + t.getMessage());
            }

            // 只有 getter 不存在（3820 / Android 16）时才接管插入点。
            // 5200 那种有 getter 的机型上限已被顶到 10，原生逻辑不会再挤掉旧条目，
            // 保留原方法还能继续跑它自己的 UI 通知（notifyItemInserted），不必也不该替它决定。
            // 注意：不能用 findAndHookMethod 传字符串参数类型 —— Xposed 的 getParameterClasses
            // 会把 "com.android.server.wm.FloatHandleInfo" 的 '.' 换成 '$'，直接 ClassNotFound。
            // 按方法名枚举方法本身，完全不碰参数类型解析。
            if (!getterHooked) try {
                XC_MethodHook addHook = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object info = param.args[0];
                            Object listObj = XposedHelpers.getObjectField(
                                    param.thisObject, "mFloatHandleInfoList");
                            if (info != null && listObj instanceof java.util.List) {
                                java.util.List list = (java.util.List) listObj;
                                synchronized (list) {
                                    // 按 taskId 去重（FloatHandleInfo 的 equals 不可靠）
                                    int newTaskId = XposedHelpers.getIntField(info, "taskId");
                                    boolean exists = false;
                                    for (Object o : list) {
                                        try {
                                            if (XposedHelpers.getIntField(o, "taskId") == newTaskId) {
                                                exists = true;
                                                break;
                                            }
                                        } catch (Throwable ignored) {
                                            // 字段读不到就跳过这一条
                                        }
                                    }
                                    if (!exists) {
                                        list.add(info);
                                    }
                                    XposedBridge.log(TAG + " float handle add, size=" + list.size()
                                            + " taskId=" + newTaskId + (exists ? " (already present)" : " (limit bypassed)"));
                                }
                            }
                            try {
                                int uid = (Integer) XposedHelpers.callMethod(info, "getUserId");
                                String pkg = (String) XposedHelpers.callMethod(info, "getPackageName");
                                XposedHelpers.callMethod(param.thisObject, "updateFloatLockListApp",
                                        pkg + "#" + uid, null, uid);
                            } catch (Throwable ignored) {
                                // 锁列表记账失败不影响解除上限
                            }
                            param.setResult(null);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " float limit bypass failed: " + t.getMessage());
                        }
                    }
                };
                int hooked = 0;
                for (Method m : fhcCls.getDeclaredMethods()) {
                    if (!"updateInfoMapForAdd".equals(m.getName())) {
                        continue;
                    }
                    XposedBridge.hookMethod(m, addHook);
                    hooked++;
                    XposedBridge.log(TAG + " float limit insert hooked: " + m);
                }
                if (hooked == 0) {
                    XposedBridge.log(TAG + " float limit insert hook: method not found");
                } else {
                    XposedBridge.log(TAG + " float handle limit removed -> " + MAX_FLOAT_HANDLE_COUNT);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " float limit insert hook failed: " + t.getMessage());
            }

            // 解除上限的第三个硬编码点：removeFloatHandleInner 只写了 size==1 / size==2，
            // size>=3 落到 else 打一行 "mFloatHandler removeFloatHandle ERROR" 直接返回，
            // 于是坞窗里的「清除」「取消运行」全都关不掉小窗（日志里 size 一直停在 3）。
            // 做法不去改它的判断，而是把列表临时收成「目标 + 一个陪衬」交给原方法：
            // 原方法按 size==2 分支正常 copy / remove / exitMinimize / 刷 UI，其余条目在
            // after 里补回列表，最终净减一项，且完全走系统自己的移除通路。
            if (!getterHooked) try {
                XposedHelpers.findAndHookMethod(fhcCls, "removeFloatHandleInner",
                        int.class, boolean.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    Object listObj = XposedHelpers.getObjectField(
                                            param.thisObject, "mFloatHandleInfoList");
                                    if (!(listObj instanceof List)) {
                                        return;
                                    }
                                    List list = (List) listObj;
                                    int taskId = (Integer) param.args[0];
                                    synchronized (list) {
                                        if (list.size() < 3) {
                                            return;
                                        }
                                        Object target = null;
                                        Object mate = null;
                                        for (Object o : list) {
                                            int tid = (Integer) XposedHelpers.callMethod(o, "getTaskId");
                                            if (tid == taskId) {
                                                target = o;
                                                break;
                                            }
                                            if (mate == null) {
                                                mate = o;
                                            }
                                        }
                                        if (target == null) {
                                            return;
                                        }
                                        if (mate == null) {
                                            mate = list.get(0) == target ? list.get(1) : list.get(0);
                                        }
                                        List saved = new ArrayList();
                                        for (Object o : list) {
                                            if (o != mate && o != target) {
                                                saved.add(o);
                                            }
                                        }
                                        list.clear();
                                        list.add(mate);
                                        list.add(target);
                                        param.setObjectExtra("fw_saved", saved);
                                        XposedBridge.log(TAG + " removeFloatHandle trimmed list for size>2,"
                                                + " saved=" + saved.size() + " taskId=" + taskId);
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + " removeFloatHandle trim error: " + t.getMessage());
                                }
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Object saved = param.getObjectExtra("fw_saved");
                                if (!(saved instanceof List)) {
                                    return;
                                }
                                try {
                                    Object listObj = XposedHelpers.getObjectField(
                                            param.thisObject, "mFloatHandleInfoList");
                                    if (!(listObj instanceof List)) {
                                        return;
                                    }
                                    List list = (List) listObj;
                                    synchronized (list) {
                                        for (Object o : (List) saved) {
                                            int tid = (Integer) XposedHelpers.callMethod(o, "getTaskId");
                                            boolean present = false;
                                            for (Object q : list) {
                                                if ((Integer) XposedHelpers.callMethod(q, "getTaskId") == tid) {
                                                    present = true;
                                                    break;
                                                }
                                            }
                                            if (!present) {
                                                list.add(o);
                                            }
                                        }
                                        XposedBridge.log(TAG + " removeFloatHandle restored, size=" + list.size());
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + " removeFloatHandle restore error: " + t.getMessage());
                                }
                            }
                        });
                XposedBridge.log(TAG + " removeFloatHandle multi-float hook success");
            } catch (Throwable t) {
                XposedBridge.log(TAG + " removeFloatHandle multi-float hook failed: " + t.getMessage());
            }
        }

        // 坞窗实时画面要用 system_server 的类去拿 Task / SurfaceControl
        try {
            DockLive.setClassLoader(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " DockLive.setClassLoader failed: " + t.getMessage());
        }
        sCl = lpparam.classLoader;
        hookLiveTaskSurfaceParentLock();

        // 单应用静音要用 AudioService 实例：LocalServices 查不到时靠构造期 hook 兜底。
        try {
            Class<?> audioCls = XposedHelpers.findClass(
                    "com.android.server.audio.AudioService", lpparam.classLoader);
            XposedBridge.hookAllConstructors(audioCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sAudioService = param.thisObject;
                    XposedBridge.log(TAG + " mute: captured AudioService instance");
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " mute: AudioService constructor hook failed: " + t.getMessage());
        }

        // 取证：坞窗「实时画面」挂载期间按音量键，事件已经到 system_server 的 AudioService
        // （logcat 里 AS.AudioService 每条键后 2~6ms 就打 uid 1000），但 dumpsys audio 里
        // speaker 音量一直不动；坞窗关掉时同样连按 3 次能从 1 走到 4。
        // 所以要看两条：音量调整方法到底有没有被调用、以及 AudioService.VolumeController
        // 的 suppressAdjustment 是不是把它吞了（吞了就是既不调音量也不弹音量条）。
        try {
            Class<?> audioTraceCls = XposedHelpers.findClass(
                    "com.android.server.audio.AudioService", lpparam.classLoader);
            XC_MethodHook volTrace = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    StringBuilder sb = new StringBuilder(TAG + "-volume call ")
                            .append(param.method.getName()).append("(");
                    for (Object a : param.args) sb.append(a).append(",");
                    sb.append(")");
                    XposedBridge.log(sb.toString());
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    XposedBridge.log(TAG + "-volume done " + param.method.getName()
                            + " ret=" + param.getResult());
                }
            };
            XposedBridge.hookAllMethods(audioTraceCls, "adjustStreamVolume", volTrace);
            XposedBridge.hookAllMethods(audioTraceCls, "setStreamVolume", volTrace);
            XposedBridge.hookAllMethods(audioTraceCls, "adjustSuggestedStreamVolume", volTrace);
            XposedBridge.hookAllMethods(audioTraceCls, "adjustStreamVolumeForUid", volTrace);
            Class<?> volCtrlCls = XposedHelpers.findClass(
                    "com.android.server.audio.AudioService$VolumeController", lpparam.classLoader);
            XposedBridge.hookAllMethods(volCtrlCls, "suppressAdjustment", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    XposedBridge.log(TAG + "-volume suppressAdjustment ret=" + param.getResult()
                            + " stream=" + (param.args.length > 0 ? param.args[0] : "?")
                            + " flags=" + (param.args.length > 1 ? param.args[1] : "?")
                            + " isDefault=" + (param.args.length > 2 ? param.args[2] : "?"));
                }
            });
            XposedBridge.log(TAG + " volume trace hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " volume trace hook failed: " + t.getMessage());
        }

        // 实时画面期间接管音量键。焦点在卡片里的任务（坞窗窗口自己 NOT_FOCUSABLE），
        // 音量键派发下去被应用吞掉，系统既不调音量也不弹条（坞窗关着 3 次 1→4，
        // 实时画面时 3 次一直 1，实测）。这里在 interceptKeyBeforeQueueing 收尾处直接
        // 调 AudioManager 调 MUSIC 音量并带 FLAG_SHOW_UI，再把返回值改成 0，
        // 事件不再派发给卡片应用，焦点问题就不会再吃掉音量键。
        try {
            Class<?> pwmCls = XposedHelpers.findClass(
                    "com.android.server.policy.PhoneWindowManager", lpparam.classLoader);
            XposedBridge.hookAllMethods(pwmCls, "interceptKeyBeforeQueueing", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object a0 = param.args.length > 0 ? param.args[0] : null;
                        if (!(a0 instanceof android.view.KeyEvent)) return;
                        android.view.KeyEvent ev = (android.view.KeyEvent) a0;
                        int kc = ev.getKeyCode();
                        if (kc != android.view.KeyEvent.KEYCODE_VOLUME_UP
                                && kc != android.view.KeyEvent.KEYCODE_VOLUME_DOWN) return;
                        if (!DockLive.hasAttachedLiveSlot()) return;
                        if (ev.getAction() != android.view.KeyEvent.ACTION_DOWN) {
                            param.setResult(0); // UP 也吃掉，卡片应用拿不到成对的键
                            return;
                        }
                        Object ctx = XposedHelpers.getObjectField(param.thisObject, "mContext");
                        if (!(ctx instanceof android.content.Context)) return;
                        android.media.AudioManager am = (android.media.AudioManager)
                                ((android.content.Context) ctx)
                                        .getSystemService(android.content.Context.AUDIO_SERVICE);
                        if (am == null) return;
                        int dir = kc == android.view.KeyEvent.KEYCODE_VOLUME_UP
                                ? android.media.AudioManager.ADJUST_RAISE
                                : android.media.AudioManager.ADJUST_LOWER;
                        am.adjustSuggestedStreamVolume(dir,
                                android.media.AudioManager.STREAM_MUSIC,
                                android.media.AudioManager.FLAG_SHOW_UI);
                        param.setResult(0);
                        XposedBridge.log(TAG + "-volume docklive took key kc=" + kc + " dir=" + dir);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "-volume docklive take failed: " + t.getMessage());
                    }
                }
            });
            XposedBridge.log(TAG + " volume docklive hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " volume docklive hook failed: " + t.getMessage());
        }

        // 打开坞窗整屏变暗，真机实测根源不在我们身上：卡片里的应用自己带了
        // screenBrightness（抖音启动页 0.15~0.19），实时挂载让它重新可见，就成了 WMS 的
        // 「窗口亮度覆盖」窗口：
        //   mWindowManagerBrightnessOverride=0.15411766
        //   mWindowManagerBrightnessOverrideTag=com.ss.android.ugc.aweme/...SplashActivity
        //   DpcExtImpl: 11858.0 -> 3599.0  rate type = window exit->window enter
        // 两道一起压：①入口方法（名字含 brightnessOverride）的 float 参数置 -1；
        // ②每轮 updatePowerState 之后把控制器的 mWindowManagerBrightnessOverride 字段
        // 也压回 -1，防止 Oplus 自己又算回来。
        try {
            Class<?> dpcCls = XposedHelpers.findClass(
                    "com.android.server.display.DisplayPowerController", lpparam.classLoader);
            XposedBridge.hookAllConstructors(dpcCls, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sDisplayPowerController = param.thisObject;
                    XposedBridge.log(TAG + " brightness captured DisplayPowerController");
                }
            });
            XposedBridge.hookAllMethods(dpcCls, "updatePowerState", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (sDisplayPowerController == null) return;
                        if (!DockLive.hasAttachedLiveSlot()) return;
                        java.lang.reflect.Field bf = null;
                        Class<?> k = sDisplayPowerController.getClass();
                        while (k != null && bf == null) {
                            try {
                                bf = k.getDeclaredField("mWindowManagerBrightnessOverride");
                            } catch (Throwable ignored) {
                                k = k.getSuperclass();
                            }
                        }
                        if (bf == null) return;
                        bf.setAccessible(true);
                        bf.setFloat(sDisplayPowerController, -1f);
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + " brightness field hook installed");

            String[] brightnessCls = {
                    "com.android.server.display.DisplayManagerService$LocalService",
                    "com.android.server.display.DisplayManagerService",
                    "com.android.server.display.DisplayPowerController"
            };
            for (String cn : brightnessCls) {
                try {
                    Class<?> c = XposedHelpers.findClass(cn, lpparam.classLoader);
                    for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                        String lower = m.getName().toLowerCase();
                        if (!lower.contains("brightnessoverride")) {
                            continue;
                        }
                        final String mName = m.getName();
                        XposedBridge.hookAllMethods(c, mName, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    if (!DockLive.hasAttachedLiveSlot()) return;
                                    for (int i = 0; i < param.args.length; i++) {
                                        if (param.args[i] instanceof Float) {
                                            XposedBridge.log(TAG + "-brightness override " + mName
                                                    + " orig=" + param.args[i] + " -> -1");
                                            param.args[i] = -1f;
                                            return;
                                        }
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + "-brightness override failed: " + t.getMessage());
                                }
                            }
                        });
                        XposedBridge.log(TAG + " brightness override hook " + cn + "#" + mName);
                    }
                } catch (Throwable ignored) {
                    // 这一层没有同名方法就跳过
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " brightness override hook failed: " + t.getMessage());
        }

        // 预览卡片不抢焦点（续）：客户端每次 addWindow / relayoutWindow 都带一份新的
        // LayoutParams，这里把预览应用的 flags 补上 NOT_FOCUSABLE + NOT_TOUCHABLE 并持续保持，
        // WMS 重算焦点时就不会把焦点选到预览卡片里的应用，手指也不会漏进应用。
        try {
            Class<?> wmsCls = XposedHelpers.findClass(
                    "com.android.server.wm.WindowManagerService", lpparam.classLoader);
            XC_MethodHook unfocusHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        for (Object a : param.args) {
                            if (!(a instanceof WindowManager.LayoutParams)) continue;
                            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) a;
                            if (lp.packageName == null) continue;
                            if (!DockLive.isPreviewPkg(lp.packageName.toString())) continue;
                            int want = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                            if ((lp.flags & want) == want) continue;
                            lp.flags |= want;
                            XposedBridge.log(TAG + "-focus preview lp locked pkg="
                                    + lp.packageName + " title=" + lp.getTitle());
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + "-focus preview lp failed: " + t.getMessage());
                    }
                }
            };
            XposedBridge.hookAllMethods(wmsCls, "addWindow", unfocusHook);
            XposedBridge.hookAllMethods(wmsCls, "relayoutWindow", unfocusHook);
            XposedBridge.log(TAG + "-focus preview lp hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "-focus preview lp hook failed: " + t.getMessage());
        }

        // 静音不该一直挂着：应用进程全部退出后自动把该 uid 的音量恢复。
        try {
            hookMuteAutoRestore(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " mute restore install failed: " + t.getMessage());
        }

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
                                // 配置读取失败（如重启后模块app未启动）时，使用默认值 true

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

            // 第五层：坞窗卡片静态画面（ImageView + TaskSnapshot）
            LivelyHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook FlexibleTaskCaptionView failed: " + t.getMessage());
        }

        // 第六层：游戏贴边接到官方后台串流（mirage 幻影屏），普通应用仍走 mini 保活
        try {
            MirageWindowHook.hook(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " MirageWindowHook failed: " + t.getMessage());
        }

        // 第七层：兜住系统 fillTaskInfo 的 NPE，避免无规则软重启
        hookTaskInfoCrashGuard(lpparam.classLoader);
        // 第八层：ColorOS 的 app-compat 圆角裁剪在 surface 已销毁时会空指针原生崩 system_server
        hookAppCompatRoundedCornersGuard(lpparam.classLoader);
        // 第九层：官方浮标动画 spec 一注册就跳到终点，避免「首次开坞窗」那段飞入
        hookFloatHandleAnimPut(lpparam.classLoader);
        // 第十层：坞窗里展开任务时给 options 打上「免过渡动画」，去掉「恢复自由小窗」飞入
        hookFlexibleTaskOptionsNoAnim(lpparam.classLoader);
        // 第十一层：官方「悬浮球展开成自由窗」的 leash 动画（animType 8）起点压成终点，去掉飞入
        hookFloatOpenAnimSpecFlat(lpparam.classLoader);
        // 过渡结束前 Task surface 会短暂回到 WMS 父层；持续隐藏到卡片接管它。
        hookFloatOpenTaskSurfaceHold(lpparam.classLoader);
        // 第十二层：坞窗 live 任务压住标题栏/角标括号/外阴影/直角，消除首开闪帧
        hookLiveTaskChromeSuppress(lpparam.classLoader);
        // 实时画面恢复后，WMS 的 TO_BACK bundle 补回官方自由小窗转场场景标记。
        hookLiveRestoreCloseAnimation(lpparam.classLoader);
        // 第十三层：官方首开动画那块纯色底板（灰直角板）不再显示
        hookDockMaskSurfaceHidden(lpparam.classLoader);
    }

    /** Ensure ColorOS's native flexible-task handler owns freeform swipe-to-home transitions. */
    private void hookLiveRestoreCloseAnimation(ClassLoader cl) {
        try {
            Class<?> transitionController = XposedHelpers.findClass(
                    "com.android.server.wm.OplusTransitionController", cl);
            XposedBridge.hookAllMethods(transitionController, "onTransactionReady",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length < 3 || param.args[2] == null) return;
                                Object transitionInfo = param.args[2];
                                Object changes = XposedHelpers.callMethod(transitionInfo, "getChanges");
                                if (!(changes instanceof Iterable)) return;
                                Object flexibleTaskInfo = null;
                                if (param.args.length > 1 && param.args[1] instanceof Iterable) {
                                    for (Object target : (Iterable<?>) param.args[1]) {
                                        try {
                                            Object container = XposedHelpers.getObjectField(target, "mContainer");
                                            Object task = container;
                                            if (container != null && !container.getClass().getName()
                                                    .endsWith(".Task")) {
                                                task = XposedHelpers.callMethod(container, "getTask");
                                            }
                                            if (task == null) continue;
                                            Object wrapper = XposedHelpers.callMethod(task, "getWrapper");
                                            Object ext = XposedHelpers.callMethod(wrapper, "getExtImpl");
                                            float scale = ((Number) XposedHelpers.callMethod(ext, "getScale"))
                                                    .floatValue();
                                            if (scale > 0f && scale < 0.999f) {
                                                flexibleTaskInfo = XposedHelpers.callMethod(task, "getTaskInfo");
                                                break;
                                            }
                                        } catch (Throwable ignored) {
                                        }
                                    }
                                }
                                for (Object change : (Iterable<?>) changes) {
                                    try {
                                        int mode = ((Number) XposedHelpers.callMethod(
                                                change, "getMode")).intValue();
                                        if (mode != 4) continue; // TransitionInfo.MODE_TO_BACK
                                        Object taskInfo = XposedHelpers.callMethod(change, "getTaskInfo");
                                        if (taskInfo == null) {
                                            if (flexibleTaskInfo == null) {
                                                XposedBridge.log(TAG + " TO_BACK has no taskInfo or flexible target change=" + change);
                                                continue;
                                            }
                                            // The system emits an ActivityRecord-level change for this
                                            // swipe. FlexibleTaskTransitionHandler drops it without TaskInfo.
                                            XposedHelpers.setObjectField(change, "mTaskInfo", flexibleTaskInfo);
                                            Bundle bundle = (Bundle) XposedHelpers.getObjectField(
                                                    change, "mFlexibleTaskBundle");
                                            if (bundle == null) bundle = new Bundle();
                                            int oldScenario = bundle.getInt("flexible_task_scenario", 0);
                                            if (oldScenario == 0) {
                                                bundle.putInt("flexible_task_scenario", 1);
                                                if (bundle.getFloat("flexible_task_exit_speed", 0f) == 0f) {
                                                    // Missing gesture speed falls back to a 400ms
                                                    // close; use the slow end of the native curve.
                                                    bundle.putFloat("flexible_task_exit_speed", -1f);
                                                }
                                                XposedHelpers.setObjectField(change, "mFlexibleTaskBundle", bundle);
                                                XposedBridge.log(TAG + " flexible-target TO_BACK TaskInfo+scenario "
                                                        + oldScenario + "->1 taskId="
                                                        + XposedHelpers.getIntField(flexibleTaskInfo, "taskId"));
                                            }
                                            continue;
                                        }
                                        int taskId = XposedHelpers.getIntField(taskInfo, "taskId");
                                        Object configuration = XposedHelpers.getObjectField(
                                                taskInfo, "configuration");
                                        Object windowConfiguration = XposedHelpers.callMethod(
                                                configuration, "getWindowConfiguration");
                                        int windowingMode = ((Number) XposedHelpers.callMethod(
                                                windowConfiguration, "getWindowingMode")).intValue();
                                        XposedBridge.log(TAG + " TO_BACK candidate taskId=" + taskId
                                                + " windowingMode=" + windowingMode + " change=" + change);
                                        // Android freeform is windowing mode 5. Leave full-screen
                                        // app-to-home transitions untouched.
                                        if (windowingMode != 5) continue;

                                        Bundle bundle = (Bundle) XposedHelpers.getObjectField(
                                                change, "mFlexibleTaskBundle");
                                        if (bundle == null) bundle = new Bundle();
                                        int oldScenario = bundle.getInt("flexible_task_scenario", 0);
                                        if (oldScenario != 0) continue;
                                        bundle.putInt("flexible_task_scenario", 1);
                                        XposedHelpers.setObjectField(change, "mFlexibleTaskBundle", bundle);
                                        XposedBridge.log(TAG + " freeform TO_BACK animation scenario taskId="
                                                + taskId + " windowingMode=" + windowingMode
                                                + " " + oldScenario + "->1");
                                    } catch (Throwable changeError) {
                                        XposedBridge.log(TAG + " inspect live close change failed: "
                                                + changeError);
                                    }
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " live close animation tag failed: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + " freeform TO_BACK animation hook installed on OplusTransitionController");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " live restore close animation hook failed: " + t);
        }
    }

    /**
     * Navigation gestures are pilfered by SystemUI and may never reach the dock's full-screen
     * touch mask. Use the resumed task's activity type to dismiss only for Home/Recents; ordinary
     * app launches must not dismiss the dock. Keep the latest resumed record fresh for reopen.
     */
    private void hookDockDismissOnActivityChange(ClassLoader cl) {
        try {
            Class<?> activityRecord = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityRecord", cl);
            int hooked = 0;
            for (Method method : activityRecord.getDeclaredMethods()) {
                if (!"setState".equals(method.getName())) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object record = param.thisObject;
                            Object state = XposedHelpers.callMethod(record, "getState");
                            if (state == null || !"RESUMED".equals(state.toString())) return;

                            Object previous = sLastResumedActivity;
                            sLastResumedActivity = record;
                            Object origin = sDockOriginActivity;
                            if (!sDockShowing || sDockClosing || record == origin) return;

                            int activityType = activityTypeOf(record);
                            if (activityType == 2 || activityType == 3) { // HOME / RECENTS
                                sDockCloseDuringSystemGesture = true;
                                XposedBridge.log(TAG + " Home/Recents resumed while dock open; dismissing"
                                        + " type=" + activityType + " origin=" + origin + " resumed=" + record);
                                postRemoveFloatDock();
                            } else {
                                // The foreground app changed without a system navigation gesture.
                                // Use it as the new baseline so the next Home gesture still closes.
                                sDockOriginActivity = record;
                                XposedBridge.log(TAG + " ordinary activity resumed; keep dock open"
                                        + " previous=" + previous + " resumed=" + record
                                        + " type=" + activityType);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " dock activity transition check failed: " + t);
                        }
                    }
                });
                hooked++;
            }
            XposedBridge.log(TAG + " dock activity transition hooks=" + hooked);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " dock activity transition hook failed: " + t);
        }
    }

    /** Keep an attached live Task under its card SurfaceView when WMS tries to move it. */
    private void hookLiveTaskSurfaceParentLock() {
        try {
            XposedBridge.hookAllMethods(SurfaceControl.Transaction.class, "reparent",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length < 2
                                    || !(param.args[0] instanceof SurfaceControl)) return;
                            SurfaceControl child = (SurfaceControl) param.args[0];
                            SurfaceControl requestedParent = param.args[1] instanceof SurfaceControl
                                    ? (SurfaceControl) param.args[1] : null;
                            SurfaceControl cardParent = DockLive.lockedParentForReparent(
                                    child, requestedParent);
                            if (cardParent != null) param.args[1] = cardParent;
                        }
                    });
            XposedBridge.log(TAG + " live Task SurfaceView reparent lock installed");
            // 坞窗卡片里的 Task 层必须按"自由小窗"样式显示：ColorOS 的迷你窗态会给 Task 层
            // 挂 BorderSettings{strokeWidth=108.55, color=0x66FFFFFF}（真机 dumpsys SurfaceFlinger
            // 层树：Layer[126] Task=1076#126），缩到卡片上就是一圈 47px 厚白边。
            // DockLive 每次 apply 会主动清一次；这里再压系统后续（flexible 事务）再设的。
            XposedBridge.hookAllMethods(SurfaceControl.Transaction.class, "setBorderSettings",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length < 2 || !(param.args[0] instanceof SurfaceControl)) return;
                                if (!DockLive.isLiveTaskSurface((SurfaceControl) param.args[0])) return;
                                Object zero = DockLive.zeroBorderSettings();
                                if (zero != null) param.args[1] = zero;
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " clear task border failed: " + t);
                            }
                        }
                    });
            // 同一次迷你窗事务还会把 Task 层圆角设成 161（对应卡片上的迷你窗圆角），
            // 一并压成 0：卡片圆角只由 SurfaceView 那一层负责。
            XposedBridge.hookAllMethods(SurfaceControl.Transaction.class, "setCornerRadius",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length < 2 || !(param.args[0] instanceof SurfaceControl)) return;
                                if (!DockLive.isLiveTaskSurface((SurfaceControl) param.args[0])) return;
                                if (!(param.args[1] instanceof Float)) return;
                                param.args[1] = 0f;
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " clear task corner failed: " + t);
                            }
                        }
                    });
            // 挂进卡片后的任务层不允许再被隐藏：真机层树里空白时是
            // "invisible reason=hidden by parent or layer flag"，就是之前 detach 的
            // hide() 留下的 flag。系统之后任何 setVisibility(false) 都压成 true。
            XposedBridge.hookAllMethods(SurfaceControl.Transaction.class, "setVisibility",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length < 2 || !(param.args[0] instanceof SurfaceControl)) return;
                                if (!DockLive.isAttachedLiveTaskSurface((SurfaceControl) param.args[0])) return;
                                if (!Boolean.TRUE.equals(param.args[1])) {
                                    param.args[1] = Boolean.TRUE;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " force live task visible failed: " + t);
                            }
                        }
                    });
            // 官方 MySurfaceView 覆写 onSetSurfacePositionAndScale（LivelyFloatHandleView 814-818）。
            // 坞窗里的 SurfaceView 是 system_server 自己 new 出来的，在这里挂 after 钩子，
            // 等于把那层覆写搬过来：framework 写完父层位置后，追加 crop / alpha（官方 820-858）。
            XposedBridge.hookAllMethods(SurfaceView.class, "onSetSurfacePositionAndScale",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (param.args.length < 4) return;
                                if (!(param.thisObject instanceof SurfaceView)) return;
                                SurfaceView sv = (SurfaceView) param.thisObject;
                                if (!DockLive.isLiveSurfaceView(sv)) return;
                                if (!(param.args[0] instanceof SurfaceControl.Transaction)
                                        || !(param.args[1] instanceof SurfaceControl)) return;
                                if (!(param.args[2] instanceof Integer)
                                        || !(param.args[3] instanceof Integer)) return;
                                DockLive.onSurfacePositionUpdate(sv,
                                        (SurfaceControl.Transaction) param.args[0],
                                        (SurfaceControl) param.args[1],
                                        (Integer) param.args[2], (Integer) param.args[3]);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " surface position crop failed: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + " SurfaceView position crop hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " live Task SurfaceView reparent lock failed: " + t);
        }
    }

    /**
     * The launcher can already be the origin ActivityRecord when the dock opens, so a Home
     * swipe/back may not produce a new RESUMED state. Observe the system-server bottom- and
     * side-edge gesture callbacks directly, regardless of the current foreground activity.
     */
    private void hookDockDismissOnBottomGesture(ClassLoader cl) {
        // 路径一：SystemGesturesPointerEventListener 构造时会把真正的 Callbacks 实例
        // （DisplayPolicy 里的匿名类）交出来，直接对它的实际类型挂方法，
        // 完全不需要知道那个匿名类叫 $1 还是别的编号。
        try {
            Class<?> listener = XposedHelpers.findClass(
                    "com.android.server.wm.SystemGesturesPointerEventListener", cl);
            XposedBridge.hookAllConstructors(listener, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    for (Object arg : param.args) {
                        if (arg == null) continue;
                        hookGestureCallbacks(arg.getClass(), "listener-ctor");
                    }
                }
            });
            XposedBridge.log(TAG + " dock gesture hook armed on SystemGesturesPointerEventListener");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " gesture listener ctor hook failed: " + t.getMessage());
        }

        // 路径二：DisplayPolicy 可能在模块装钩子之前就已经构造完了（此时路径一等不到回调），
        // 那就逐号探测 DisplayPolicy$1..$N，哪个上面真有这三个回调就挂哪个。
        for (int i = 1; i <= GESTURE_CLASS_PROBE_LIMIT; i++) {
            Class<?> candidate = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.DisplayPolicy$" + i, cl);
            if (candidate == null) continue;
            if (hookGestureCallbacks(candidate, "DisplayPolicy$" + i)) break;
        }
    }

    /**
     * 把坞窗关闭逻辑挂到某个手势回调实现的三个边缘回调上。
     *
     * <p>三条方法都走 hookAllMethods（按方法名枚举，不碰参数类型），找不到就返回空集合、
     * 不会抛异常，所以对「猜错的类」是安全的。命中任何一个才算挂上，并按类名去重——
     * 路径一和路径二可能摸到同一个类，重复挂会让一次上滑连关好几次。</p>
     */
    private boolean hookGestureCallbacks(Class<?> cls, String source) {
        if (cls == null) return false;
        String key = cls.getName();
        if (sGestureCallbackClasses.contains(key)) return true;

        int bottom = 0;
        try {
            bottom = XposedBridge.hookAllMethods(cls, "onSwipeFromBottom", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!sDockShowing || sDockClosing) return;
                    dismissDockForSystemGesture("bottom");
                }
            }).size();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook onSwipeFromBottom failed on " + key + ": " + t.getMessage());
        }

        XC_MethodHook backGesture = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                dismissDockForBackGesture(param.method.getName());
            }
        };
        int left = 0;
        int right = 0;
        try {
            left = XposedBridge.hookAllMethods(cls, "onSwipeFromLeft", backGesture).size();
            right = XposedBridge.hookAllMethods(cls, "onSwipeFromRight", backGesture).size();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook side gestures failed on " + key + ": " + t.getMessage());
        }

        int total = bottom + left + right;
        if (total == 0) return false;
        sGestureCallbackClasses.add(key);
        XposedBridge.log(TAG + " dock gesture hooks installed on " + key + " via " + source
                + " bottom=" + bottom + " left=" + left + " right=" + right);
        return true;
    }

    private void dismissDockForSystemGesture(String edge) {
        if (!sDockShowing || sDockClosing) return;
        sDockCloseDuringSystemGesture = true;
        XposedBridge.log(TAG + " " + edge + " system gesture while dock open; dismissing");
        postRemoveFloatDock();
    }

    private void dismissDockForBackGesture(String edge) {
        if (!sDockShowing || sDockClosing) return;
        // Edge recognition happens before Android starts the Back transition. Release only the
        // parent lock here; let startBackNavigation choose and hand off the actual Task.
        DockLive.releaseSurfaceParentLock();
        XposedBridge.log(TAG + " " + edge + " edge recognized; released parent lock, waiting for system Back");
    }

    /**
     * 用户口径（2026-11-22）：坞窗打开时的返回手势**只关闭坞窗**，不许对坞窗里的应用生效，
     * 应用继续保持原进程运行（坞窗收起时任务收成浮标，贴边保活照旧）。
     *
     * <p>以前这里是放行原始 startBackNavigation、把任务交给系统 Back 转场，结果抬手那一拍
     * 任务层被 show 出来，屏幕上露出坞窗右半边的大窗。现在改成直接吞掉这次 Back：
     * 应用收不到返回事件，也不会产生任何 Back 转场；坞窗照常滑出收起。</p>
     */
    private void hookDockDismissOnBackNavigation(ClassLoader cl) {
        try {
            Class<?> atms = XposedHelpers.findClass(
                    "com.android.server.wm.ActivityTaskManagerService", cl);
            int hooked = XposedBridge.hookAllMethods(atms, "startBackNavigation",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!sDockShowing) return;
                            int taskId = focusedTaskId(param.thisObject);
                            if (!DockLive.isLiveTask(taskId)) {
                                int activeTaskId = CardZoom.activeTaskId();
                                if (DockLive.isLiveTask(activeTaskId)) taskId = activeTaskId;
                            }
                            XposedBridge.log(TAG + " back gesture with dock open; focused taskId="
                                    + taskId + " live=" + DockLive.isLiveTask(taskId));
                            if (taskId <= 0 || !DockLive.isLiveTask(taskId)) return;
                            // 只放掉卡片父层锁，不 handoff 给系统 Back、不设置 sDockBackRestoreTaskId
                            // （那两样都是"让系统接管任务"的路径，会把任务层重新 show 出来）。
                            DockLive.releaseSurfaceParentLock();
                            swallowBackNavigation(param);
                            if (!sDockClosing) dismissDockForSystemGesture("back-gesture");
                            XposedBridge.log(TAG + " back gesture consumed by dock taskId=" + taskId
                                    + "; dock dismissed, app untouched");
                        }
                    }).size();
            XposedBridge.log(TAG + " dock back navigation hooks=" + hooked
                    + (hooked == 0 ? " (startBackNavigation missing on this ROM)" : ""));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " dock back navigation hook failed: " + t.getMessage());
        }
    }

    /**
     * 让这次 startBackNavigation 不执行、也不派发 Back 给应用。
     * 返回值必须按真实签名给（返回 boolean 的 setResult(null) 会在拆箱时 NPE 打死 system_server）。
     */
    private static void swallowBackNavigation(XC_MethodHook.MethodHookParam param) {
        try {
            Class<?> rt = ((java.lang.reflect.Method) param.method).getReturnType();
            if (rt == void.class) param.setResult(null);
            else if (rt == boolean.class) param.setResult(Boolean.FALSE);
            else if (rt == int.class) param.setResult(Integer.valueOf(0));
            else if (rt == long.class) param.setResult(Long.valueOf(0L));
            else param.setResult(null);
            XposedBridge.log(TAG + " startBackNavigation swallowed, returnType=" + rt);
        } catch (Throwable t) {
            param.setResult(null);
            XposedBridge.log(TAG + " swallowBackNavigation failed: " + t);
        }
    }

    /** 当前焦点 root task 的 taskId；取不到返回 -1。 */
    private static int focusedTaskId(Object atms) {
        try {
            Object rwc = XposedHelpers.getObjectField(atms, "mRootWindowContainer");
            if (rwc == null) return -1;
            Object task = XposedHelpers.callMethod(rwc, "getTopDisplayFocusedRootTask");
            if (task == null) task = XposedHelpers.callMethod(rwc, "getTopFocusedRootTask");
            return taskIdOfTask(task);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Returns ACTIVITY_TYPE_HOME / ACTIVITY_TYPE_RECENTS for the resumed record's task. */
    private static int activityTypeOf(Object record) {
        try {
            Object value = XposedHelpers.callMethod(record, "getActivityType");
            if (value instanceof Number) return ((Number) value).intValue();
        } catch (Throwable ignored) {
        }
        try {
            Object task = XposedHelpers.callMethod(record, "getTask");
            Object value = XposedHelpers.callMethod(task, "getActivityType");
            if (value instanceof Number) return ((Number) value).intValue();
        } catch (Throwable ignored) {
        }
        try {
            Object task = XposedHelpers.callMethod(record, "getRootTask");
            Object value = XposedHelpers.callMethod(task, "getActivityType");
            if (value instanceof Number) return ((Number) value).intValue();
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** removeFloatDock animates Views and therefore must run on the dock's UI thread. */
    private void postRemoveFloatDock() {
        final FrameLayout container = sDockContainer;
        if (container == null) {
            removeFloatDock();
            return;
        }
        container.post(new Runnable() {
            @Override public void run() {
                if (sDockContainer == container && sDockShowing && !sDockClosing) {
                    removeFloatDock();
                }
            }
        });
    }

    /**
     * 坞窗展开任务时给 ActivityOptions 打上 "androidx.flexible.activityNoAnimation"。
     *
     * <p>官方 FlexibleWindowTransitionController.getExpectedTransition 认这个 extra，
     * 命中后把这次起窗的 transition 直接改成 NONE —— 也就是不会再看到
     * 「点浮球后小窗恢复成自由窗、飞向卡片」那段过渡。只在 DockLive 调用官方展开入口
     * 的那一拍生效（{@link DockLive#consumeNoAnimOnce()} 用完即清）。</p>
     */
    private void hookFlexibleTaskOptionsNoAnim(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleWindowUtils", cl);
            XposedBridge.hookAllMethods(cls, "setFlexibleTaskOptions", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!DockLive.consumeNoAnimOnce()) return;
                    try {
                        Object options = null;
                        for (Object arg : param.args) {
                            if (arg != null && arg.getClass().getName().startsWith("android.app.ActivityOptions")) {
                                options = arg;
                                break;
                            }
                        }
                        if (options == null) options = param.getResult();
                        if (options == null) return;
                        android.os.Bundle extra = null;
                        try {
                            extra = (android.os.Bundle) XposedHelpers.callMethod(options, "getExtraBundle");
                        } catch (Throwable ignored) {
                        }
                        if (extra == null) {
                            extra = new android.os.Bundle();
                            XposedHelpers.callMethod(options, "setExtraBundle", extra);
                        }
                        extra.putBoolean("androidx.flexible.activityNoAnimation", true);
                        XposedBridge.log(TAG + " flexible options: noAnimation=true");
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " noAnimation option failed: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + " hookFlexibleTaskOptionsNoAnim installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookFlexibleTaskOptionsNoAnim failed: " + t.getMessage());
        }
    }

    /**
     * 「点浮球 → 恢复成自由小窗」那段飞出来，是官方
     * {@code FloatHandleController.startFloatOpenAnimNormal} 里建的 animType=8 的 leash 动画：
     * startBounds 是悬浮球所在的小矩形（常常还在屏幕外），endBounds 是窗口落点。
     *
     * <p>日志实证（3820）：
     * {@code createLeashAnimSpec mAnimationType = 8 startBounds = Rect(1240,338 - 1545,524)
     * endBounds = Rect(624,399 - 1163,1357)} —— 正是从球飞到卡片。</p>
     *
     * <p>处理：只在坞窗自己的 task 上，把 spec 的 mStartBounds 直接改成 mEndBounds，
     * 动画仍然照原流程跑（回调、任务 resize、最终状态一个不少），只是没有位移 ——
     * 看起来就是瞬间落位，没有飞入。其它 animType（最小化成球、退出等）一律不碰。</p>
     */
    private void hookFloatOpenAnimSpecFlat(ClassLoader cl) {
        try {
            Class<?> specCls = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleFloatHandleAnimationSpec", cl);
            // 构造器入参（5200 Builder.build 实证）：(atms, task, animType,
            // startBounds, endBounds, side, fromIconMarginStart, toIconMarginStart,
            // fromIconScale, toIconScale, startAlpha, endAlpha, startBehavior)
            XposedBridge.hookAllConstructors(specCls, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length < 5) return;
                        int animType = ((Number) param.args[2]).intValue();
                        if (animType != 8) return;
                        if (!(param.args[3] instanceof Rect) || !(param.args[4] instanceof Rect)) return;
                        Rect start = (Rect) param.args[3];
                        Rect end = (Rect) param.args[4];
                        int taskId = -1;
                        try {
                            taskId = XposedHelpers.getIntField(param.args[1], "mTaskId");
                        } catch (Throwable ignored) {
                        }
                        boolean live = taskId > 0 && DockLive.isLiveTask(taskId);
                        localLog("spec ctor before type=8 taskId=" + taskId + " live=" + live
                                + " start=" + start + " end=" + end);
                        XposedBridge.log(TAG + " float anim spec ctor type=8 taskId=" + taskId
                                + " live=" + live + " start=" + start + " end=" + end);
                        if (live && !end.isEmpty() && !start.equals(end)) {
                            start.set(end);
                            XposedBridge.log(TAG + " float open anim flattened taskId=" + taskId);
                        }
                    } catch (Throwable t) {
                        localLog("spec ctor before err: " + t);
                        XposedBridge.log(TAG + " float anim ctor hook err: " + t);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object spec = param.thisObject;
                        Object task = XposedHelpers.getObjectField(spec, "mAnimTask");
                        int taskId = taskIdOfTask(task);
                        boolean live = DockLive.isLiveTask(taskId);
                        localLog("spec ctor after taskId=" + taskId + " live=" + live);
                        if (live) {
                            zeroSpecShadow(spec, taskId);
                        }
                    } catch (Throwable t) {
                        localLog("spec ctor after err: " + t);
                        XposedBridge.log(TAG + " float anim ctor after err: " + t);
                    }
                }
            });
            localLog("hookFloatOpenAnimSpecFlat(ctor) installed");
            XposedBridge.log(TAG + " hookFloatOpenAnimSpecFlat(ctor) installed");
        } catch (Throwable t) {
            localLog("hookFloatOpenAnimSpecFlat failed: " + t.getMessage());
            XposedBridge.log(TAG + " hookFloatOpenAnimSpecFlat failed: " + t.getMessage());
        }
        try {
            Class<?> builderCls = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleFloatHandleAnimationSpec$Builder", cl);
            XposedBridge.hookAllMethods(builderCls, "build", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object spec = param.getResult();
                        if (spec == null) {
                            localLog("spec build after: spec=null");
                            return;
                        }
                        int animType = XposedHelpers.getIntField(spec, "mAnimationType");
                        if (animType != 8) {
                            try {
                                animType = XposedHelpers.getIntField(spec, "mOriginalAnimType");
                            } catch (Throwable ignored) {
                            }
                            if (animType != 8) return;
                        }
                        Object task = XposedHelpers.getObjectField(spec, "mAnimTask");
                        if (task == null) {
                            localLog("spec build after: animType=" + animType + " task=null");
                            return;
                        }
                        int taskId = XposedHelpers.getIntField(task, "mTaskId");
                        Rect start = (Rect) XposedHelpers.getObjectField(spec, "mStartBounds");
                        Rect end = (Rect) XposedHelpers.getObjectField(spec, "mEndBounds");
                        boolean live = DockLive.isLiveTask(taskId);
                        localLog("spec build after animType=" + animType + " taskId=" + taskId
                                + " live=" + live + " start=" + start + " end=" + end);
                        XposedBridge.log(TAG + " float anim spec build type=8 taskId=" + taskId
                                + " live=" + live + " start=" + start + " end=" + end);
                        if (live && start != null && end != null
                                && !end.isEmpty() && !start.equals(end)) {
                            start.set(end);
                            XposedBridge.log(TAG + " float open anim flattened taskId=" + taskId
                                    + " bounds=" + end);
                        }
                        if (live) {
                            zeroSpecShadow(spec, taskId);
                        }
                    } catch (Throwable t) {
                        localLog("spec build after err: " + t);
                        XposedBridge.log(TAG + " flatten float open anim failed: " + t.getMessage());
                    }
                }
            });
            XposedBridge.log(TAG + " hookFloatOpenAnimSpecFlat(build) installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookFloatOpenAnimSpecFlat(build) failed: " + t.getMessage());
        }
        // holder 在构造期就由 createLeashAnimSpec 生成（1308 行读 mFrom/mToShadowRadius 写死进
        // holder.startVal）；ctor/build after 再改字段时 holder 已建成、不再重读。必须在这个
        // 方法 before、1308 读字段之前把 live 任务的起/止阴影半径压成 0。
        try {
            Class<?> specCls2 = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleFloatHandleAnimationSpec", cl);
            XposedBridge.hookAllMethods(specCls2, "createLeashAnimSpec", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object spec = param.thisObject;
                        Object task = XposedHelpers.getObjectField(spec, "mAnimTask");
                        if (task == null) return;
                        int taskId = XposedHelpers.getIntField(task, "mTaskId");
                        if (!DockLive.isLiveTask(taskId)) return;
                        zeroSpecShadow(spec, taskId);
                    } catch (Throwable t) {
                        localLog("createLeashAnimSpec before err: " + t);
                        XposedBridge.log(TAG + " createLeashAnimSpec before err: " + t.getMessage());
                    }
                }
            });
            localLog("hookFloatOpenAnimSpecFlat(createLeashAnimSpec) installed");
            XposedBridge.log(TAG + " hookFloatOpenAnimSpecFlat(createLeashAnimSpec) installed");
        } catch (Throwable t) {
            localLog("hook createLeashAnimSpec failed: " + t.getMessage());
            XposedBridge.log(TAG + " hook createLeashAnimSpec failed: " + t.getMessage());
        }

    }

    /**
     * 官方首开动画里那块纯色底板（mMaskSurface）就是首帧那块直角灰板。
     * 它和阴影共用同一份 spec：declare 时颜色取 getHandleViewContainerBackgroundColor()，
     * defaultCallAnimationStart 里 show + setColor + setAlpha(1.0f) 一整块贴上来，
     * 尺寸还跟着 leash 缩放，所以看起来是「和阴影绑在一块」的硬边矩形。
     *
     * <p>做法：把这个 surface 登记成 live 坞窗专属，然后在事务层就地压掉它的 show /
     * alpha / visibility。只影响坞窗 live 任务的那一份 spec，官方其它浮球动画不动。</p>
     */
    private void hookDockMaskSurfaceHidden(ClassLoader cl) {
        Class<?> specCls;
        try {
            specCls = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleFloatHandleAnimationSpec", cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " dock mask: spec class missing " + t.getMessage());
            return;
        }

        // 事务真正 apply 的地方，此刻 mMaskSurface 必然已经建好，登记最稳。
        try {
            int hooked = XposedBridge.hookAllMethods(specCls, "defaultCallAnimationStart",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object spec = param.thisObject;
                                Object task = XposedHelpers.getObjectField(spec, "mAnimTask");
                                int taskId = taskIdOfTask(task);
                                if (taskId <= 0 || !DockLive.isLiveTask(taskId)) return;
                                registerDockMaskSurface(
                                        XposedHelpers.getObjectField(spec, "mMaskSurface"), taskId);
                            } catch (Throwable t) {
                                localLog("mask register in anim start failed: " + t);
                            }
                        }
                    }).size();
            XposedBridge.log(TAG + " dock mask: anim start hooks=" + hooked);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " dock mask: anim start hook failed " + t.getMessage());
        }

        int showHooks = 0;
        int alphaHooks = 0;
        int visibilityHooks = 0;
        try {
            showHooks = XposedBridge.hookAllMethods(SurfaceControl.Transaction.class, "show",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length != 1) return;
                            if (!isDockMaskSurface(param.args[0])) return;
                            XposedBridge.log(TAG + " dock mask surface show skipped");
                            param.setResult(param.thisObject);
                        }
                    }).size();
            alphaHooks = XposedBridge.hookAllMethods(SurfaceControl.Transaction.class, "setAlpha",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length != 2) return;
                            if (!isDockMaskSurface(param.args[0])) return;
                            param.args[1] = Float.valueOf(0f);
                        }
                    }).size();
            visibilityHooks = XposedBridge.hookAllMethods(SurfaceControl.Transaction.class,
                    "setVisibility", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length != 2) return;
                            if (!isDockMaskSurface(param.args[0])) return;
                            param.args[1] = Boolean.FALSE;
                        }
                    }).size();
            XposedBridge.log(TAG + " dock mask: transaction hooks show=" + showHooks
                    + " alpha=" + alphaHooks + " visibility=" + visibilityHooks);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " dock mask: transaction hooks failed " + t.getMessage());
        }
    }

    /**
     * The temporary FloatOpen animation uses mTaskSurface, then returns it to WMS before
     * DockLive reparents it into the card. Hold that exact surface invisible through the gap;
     * the atomic card reparent is the only point where alpha=1 is allowed again.
     */
    private void hookFloatOpenTaskSurfaceHold(ClassLoader cl) {
        try {
            Class<?> specCls = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleFloatHandleAnimationSpec", cl);
            XposedBridge.hookAllMethods(specCls, "defaultCallAnimationStart", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object spec = param.thisObject;
                        if (XposedHelpers.getIntField(spec, "mAnimationType") != 8) return;
                        Object task = XposedHelpers.getObjectField(spec, "mAnimTask");
                        int taskId = taskIdOfTask(task);
                        if (!DockLive.isLiveTask(taskId)) return;
                        Object taskSurface = XposedHelpers.getObjectField(spec, "mTaskSurface");
                        Object leash = XposedHelpers.getObjectField(spec, "mTaskAnimLeash");
                        SurfaceControl taskSc = taskSurface instanceof SurfaceControl
                                ? (SurfaceControl) taskSurface : null;
                        SurfaceControl leashSc = leash instanceof SurfaceControl
                                ? (SurfaceControl) leash : null;
                        DockLive.registerAnimationSurfaces(taskId, taskSc, leashSc);

                        // Hide both during the start transaction itself; subsequent WMS/spring
                        // alpha restorations are held by the setAlpha hook until card attachment.
                        if (param.args.length > 0
                                && param.args[0] instanceof SurfaceControl.Transaction) {
                            SurfaceControl.Transaction tx = (SurfaceControl.Transaction) param.args[0];
                            if (taskSc != null && taskSc.isValid()) {
                                XposedHelpers.callMethod(tx, "setAlpha", taskSc, 0f);
                            }
                            if (leashSc != null && leashSc.isValid()) {
                                XposedHelpers.callMethod(tx, "setAlpha", leashSc, 0f);
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " register FloatOpen Task surface failed: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + " FloatOpen Task surface registration installed");
            // 官方打开动画的收尾点：callAnimationEnd 会 apply 一版"任务层归位"的事务
            // （几何/alpha 到终态）。必须等它之后再 reparent，否则任务层停起始帧、卡片空白。
            XposedBridge.hookAllMethods(specCls, "callAnimationEnd", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object spec = param.thisObject;
                        if (XposedHelpers.getIntField(spec, "mAnimationType") != 8) return;
                        Object task = XposedHelpers.getObjectField(spec, "mAnimTask");
                        int taskId = taskIdOfTask(task);
                        if (!DockLive.isLiveTask(taskId)) return;
                        DockLive.onFloatAnimationEnd(taskId);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " callAnimationEnd hook failed: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FloatOpen Task surface registration failed: " + t.getMessage());
        }
    }

    /**
     * 首开坞窗闪帧四要素收口（3820 反编译链路实证）：
     * <ul>
     *   <li>标题栏（三点/手柄）：{@code FlexibleTaskController.updateFlexibleTaskCaptionVisibleIfNeed}
     *       是所有标题栏显隐调用的单一入口；live 任务收到「显示」时直接跳过，不挂标题栏。</li>
     *   <li>应用外阴影：{@code TaskExtImpl.getShadowRadiusFocused/Unfocused} 供过渡事务与
     *       leash 取阴影半径；live 任务压成 0。</li>
     *   <li>直角：{@code TaskExtImpl.getCornerRadius} 供 leash/动画 spec 取圆角；
     *       live 任务回卡片圆角，避免闪出方形直角。</li>
     *   <li>底部两个白色括号：FTIWM 的四角指示件，{@code startStateSwitchAnimation} 播角标弹簧、
     *       {@code updateLeashVisibility/updateHostVisibility} 挂宿主表面；live 任务不播动画、
     *       显隐强制压成隐藏。</li>
     * </ul>
     * 所有判断统一走 {@code DockLive.isLiveTask(taskId)}，非 live 任务一律走原逻辑。
     */
    private void hookLiveTaskChromeSuppress(ClassLoader cl) {
        // ① 标题栏显隐入口：live 任务不允许被「显示」
        try {
            Class<?> ctrlCls = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.FlexibleTaskController", cl);
            if (ctrlCls != null) {
                XposedBridge.hookAllMethods(ctrlCls, "updateFlexibleTaskCaptionVisibleIfNeed",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                int tid = taskIdOfTask(param.args[0]);
                                if (DockLive.isLiveTask(tid) && Boolean.TRUE.equals(param.args[1])) {
                                    XposedBridge.log(TAG + " live caption suppressed task=" + tid);
                                    param.setResult(null);
                                }
                            }
                        });
                XposedBridge.log(TAG + " hookLiveTaskChromeSuppress(caption) installed");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookLiveTaskChromeSuppress(caption) failed: " + t.getMessage());
        }

        // ②③ 阴影半径压 0、圆角回卡片圆角
        try {
            Class<?> extCls = XposedHelpers.findClassIfExists("com.android.server.wm.TaskExtImpl", cl);
            if (extCls != null) {
                XC_MethodHook shadowZero = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int tid = taskIdOfExtImpl(param.thisObject);
                        if (DockLive.isLiveTask(tid)) {
                            XposedBridge.log(TAG + " live shadow zeroed taskId=" + tid
                                    + " " + param.method.getName() + " old=" + param.getResult());
                            param.setResult(0);
                        }
                    }
                };
                XposedBridge.hookAllMethods(extCls, "getShadowRadiusFocused", shadowZero);
                XposedBridge.hookAllMethods(extCls, "getShadowRadiusUnfocused", shadowZero);
                XposedBridge.hookAllMethods(extCls, "getCornerRadius", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        int tid = taskIdOfExtImpl(param.thisObject);
                        if (DockLive.isLiveTask(tid)) {
                            int cp = DockLive.cornerPxOf(tid);
                            if (cp >= 0) {
                                XposedBridge.log(TAG + " live corner override taskId=" + tid
                                        + " old=" + param.getResult() + " new=" + cp);
                                param.setResult(cp);
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + " hookLiveTaskChromeSuppress(extImpl) installed");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookLiveTaskChromeSuppress(extImpl) failed: " + t.getMessage());
        }

        // WMS/animation callbacks can restore alpha after DockLive's initial hide. Intercept only
        // the registered FloatOpen Task surface and hold it until applyLiveTransaction reparents it.
        try {
            XposedBridge.hookAllMethods(SurfaceControl.Transaction.class, "setAlpha",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args.length < 2 || !(param.args[0] instanceof SurfaceControl)
                                    || !(param.args[1] instanceof Number)) return;
                            if (DockLive.shouldHoldAnimationTaskSurface(
                                    (SurfaceControl) param.args[0])) {
                                param.args[1] = 0f;
                                return;
                            }
                            SurfaceControl alphaSc = (SurfaceControl) param.args[0];
                            if (DockLive.isAttachedLiveTaskSurface(alphaSc)
                                    && ((Number) param.args[1]).floatValue() < 1f) {
                                // 已挂进卡片的任务层必须完全可见：打开过渡收尾阶段 WMS
                                // 还会把 alpha 设回 0（真机层树 Layer[142] alpha=0），
                                // 这里抬回 1，避免"第二次打开空白、按音量键才恢复"。
                                param.args[1] = 1f;
                            }
                        }
                    });
            XposedBridge.log(TAG + " FloatOpen Task surface alpha hold installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FloatOpen Task surface alpha hold failed: " + t.getMessage());
        }

        // ④ 角标括号：不播弹簧、宿主表面强制隐藏
        try {
            Class<?> mgrCls = XposedHelpers.findClassIfExists(
                    "com.android.server.wm.FlexibleTaskIndicatorWindowManager", cl);
            if (mgrCls != null) {
                XposedBridge.hookAllMethods(mgrCls, "startStateSwitchAnimation", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int tid = taskIdOfFtiwm(param.thisObject);
                        if (DockLive.isLiveTask(tid)) {
                            XposedBridge.log(TAG + " live indicator anim suppressed task=" + tid);
                            param.setResult(null);
                        }
                    }
                });
                XC_MethodHook forceHide = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int tid = taskIdOfFtiwm(param.thisObject);
                        if (DockLive.isLiveTask(tid) && Boolean.TRUE.equals(param.args[0])) {
                            XposedBridge.log(TAG + " live indicator surface hidden task=" + tid);
                            param.args[0] = Boolean.FALSE;
                        }
                    }
                };
                XposedBridge.hookAllMethods(mgrCls, "updateLeashVisibility", forceHide);
                XposedBridge.hookAllMethods(mgrCls, "updateHostVisibility", forceHide);
                XposedBridge.log(TAG + " hookLiveTaskChromeSuppress(indicator) installed");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookLiveTaskChromeSuppress(indicator) failed: " + t.getMessage());
        }
    }

    /** 把动画 spec 的起/止阴影半径压成 0：首帧 leash/mask 不再带外阴影。 */
    private static void zeroSpecShadow(Object spec, int taskId) {
        try {
            Object fromObj = XposedHelpers.getObjectField(spec, "mFromShadowRadius");
            Object toObj = XposedHelpers.getObjectField(spec, "mToShadowRadius");
            float from = fromObj instanceof Number ? ((Number) fromObj).floatValue() : 0f;
            float to = toObj instanceof Number ? ((Number) toObj).floatValue() : 0f;
            Object mask = XposedHelpers.getObjectField(spec, "mMaskSurface");
            int maskColor = 0;
            try {
                maskColor = XposedHelpers.getIntField(spec, "mMaskSurfaceColor");
            } catch (Throwable ignored) {
            }
            registerDockMaskSurface(mask, taskId);
            XposedHelpers.findField(spec.getClass(), "mFromShadowRadius").setFloat(spec, 0f);
            XposedHelpers.findField(spec.getClass(), "mToShadowRadius").setFloat(spec, 0f);
            Object afterFrom = XposedHelpers.getObjectField(spec, "mFromShadowRadius");
            Object afterTo = XposedHelpers.getObjectField(spec, "mToShadowRadius");
            String msg = "spec shadow zeroed taskId=" + taskId + " from=" + from + " to=" + to
                    + " afterFrom=" + afterFrom + " afterTo=" + afterTo
                    + " maskValid=" + (mask != null)
                    + " maskColor=0x" + Integer.toHexString(maskColor);
            localLog(msg);
            XposedBridge.log(TAG + " " + msg);
        } catch (Throwable t) {
            localLog("zero spec shadow failed: " + t);
            XposedBridge.log(TAG + " zero spec shadow failed: " + t);
        }
    }

    /** 把 live 坞窗首开动画里的纯色底板登记起来，事务层才会把它压掉。 */
    private static void registerDockMaskSurface(Object mask, int taskId) {
        if (!(mask instanceof SurfaceControl)) return;
        SurfaceControl sc = (SurfaceControl) mask;
        int alive = 0;
        for (java.lang.ref.WeakReference<SurfaceControl> ref : sDockMaskSurfaces) {
            SurfaceControl existing = ref.get();
            if (existing == null) continue;
            alive++;
            if (existing == sc) {
                sDockMaskSurfaceCount = alive;
                return;
            }
        }
        sDockMaskSurfaces.add(new java.lang.ref.WeakReference<>(sc));
        sDockMaskSurfaceCount = alive + 1;
        String msg = "dock mask surface registered taskId=" + taskId + " count=" + sDockMaskSurfaceCount;
        localLog(msg);
        XposedBridge.log(TAG + " " + msg);
    }

    private static boolean isDockMaskSurface(Object candidate) {
        if (sDockMaskSurfaceCount == 0 || !(candidate instanceof SurfaceControl)) return false;
        for (java.lang.ref.WeakReference<SurfaceControl> ref : sDockMaskSurfaces) {
            SurfaceControl existing = ref.get();
            if (existing != null && existing == candidate) return true;
        }
        return false;
    }

    /** 兜底写主缓冲（api.log 可能只进 LSPosed 私有 buffer，主 logcat 看不到）。 */
    private static void localLog(String msg) {        try {
            android.util.Log.i("freewindow-hook", msg);
        } catch (Throwable ignored) {
        }
    }

    /** Task 对象 → mTaskId；取不到返回 -1。 */
    private static int taskIdOfTask(Object task) {
        if (task == null) return -1;
        try {
            Object id = XposedHelpers.getObjectField(task, "mTaskId");
            return id instanceof Integer ? (Integer) id : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** TaskExtImpl → mTask → mTaskId；取不到返回 -1。 */
    private static int taskIdOfExtImpl(Object extImpl) {
        if (extImpl == null) return -1;
        try {
            return taskIdOfTask(XposedHelpers.getObjectField(extImpl, "mTask"));
        } catch (Throwable t) {
            return -1;
        }
    }

    /** FTIWM → mAdapter → mObserverTask → mTaskId；取不到返回 -1。 */
    private static int taskIdOfFtiwm(Object mgr) {
        if (mgr == null) return -1;
        try {
            Object adapter = XposedHelpers.getObjectField(mgr, "mAdapter");
            return taskIdOfTask(XposedHelpers.getObjectField(adapter, "mObserverTask"));
        } catch (Throwable t) {
            return -1;
        }
    }

    private static int sRoundedCornersSkipped = 0;

    /**
     * 官方浮标动画 spec 一放进 controller，就让它直接跳到终点并删掉快照层。
     *
     * <p>首次展开时 spec 是 startActivity 之后异步 put 进来的，我们事后按延时调
     * {@code checkAndSkipToEndLastAnim} 往往扑空（spec 还没注册），动画就完整播一遍 ——
     * 这就是「首次开坞窗有小窗飞入、之后没有」的来源。挂到 put 这个注册点上，
     * spec 一进来立刻收尾，第一次也不会看见动画。</p>
     */
    private void hookFloatHandleAnimPut(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(
                    "com.android.server.wm.FlexibleFloatHandleAnimController", cl);
            XposedBridge.hookAllMethods(cls, "put", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        int taskId = (Integer) param.args[0];
                        if (!DockLive.isLiveTask(taskId)) return;
                        Object spec = param.args[1];
                        if (spec == null) return;
                        XposedHelpers.callMethod(spec, "skipToEndOrCancelAnim");
                        try {
                            XposedHelpers.callMethod(spec, "removeSnapshotSurface");
                        } catch (Throwable ignored) {
                        }
                        XposedBridge.log(TAG + " float anim spec skipped taskId=" + taskId);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " float anim spec skip failed: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + " hookFloatHandleAnimPut installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookFloatHandleAnimPut failed: " + t.getMessage());
        }
    }

    /**
     * 跳过 app-compat（letterbox）窗口的圆角裁剪。
     *
     * <p>3820 上 00:29:15 的 system_server SIGSEGV 就死在它里面：</p>
     * <pre>
     * Task.resize（我们调的）→ continueWindowLayout → performSurfacePlacement
     *   → AppCompatLetterboxPolicy.start → AppCompatRoundedCorners.updateRoundedCornersIfNeeded
     *   → SurfaceControl$Transaction.setCrop → libgui 里空 surface 解引用（fault addr 0xedc）
     * </pre>
     *
     * <p>Java 那一层虽然判了 {@code getSurfaceControl() != null && isValid()}，但窗口 surface
     * 在布局过程中被销毁重建时这个判断挡不住（native 指针已经是坏的），我们又在坞窗里高频
     * 改几何，等于一直往这段代码上撞。整个方法跳过即可：letterbox 黑边不再做圆角裁切，
     * 视觉上几乎看不出来，但彻底断掉这条崩溃路径。</p>
     */
    private void hookAppCompatRoundedCornersGuard(ClassLoader cl) {
        try {
            Class<?> cls = XposedHelpers.findClass(
                    "com.android.server.wm.AppCompatRoundedCorners", cl);
            XposedBridge.hookAllMethods(cls, "updateRoundedCornersIfNeeded", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (sRoundedCornersSkipped < 10) {
                        sRoundedCornersSkipped++;
                        XposedBridge.log(TAG + " 跳过 letterbox 圆角裁剪("
                                + sRoundedCornersSkipped + "/10)");
                    }
                    param.setResult(null);
                }
            });
            XposedBridge.log(TAG + " hooked AppCompatRoundedCorners guard");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " AppCompatRoundedCorners guard failed: " + t);
        }
    }

    /** 已拦下的 fillTaskInfo 崩溃次数（只前 30 次打日志，动画期间不刷屏）。 */
    private static int sTaskInfoCrashGuarded = 0;

    /**
     * 兜底：Task.fillTaskInfo 抛出的异常不再拖死 system_server（表现为无规则软重启）。
     *
     * <p>3820 上 21:53:54 / 22:45:11 / 23:24:07 / 23:45:34 四次软重启是同一个 NPE：
     * {@code Attempt to read from field 'android.net.Uri android.app.TaskInfo.capturedLink'
     * on a null object reference at ActivityTaskSupervisor$TaskInfoHelper.accept}。
     * 两条触发链最后都汇到 {@code Task.fillTaskInfo}：</p>
     * <ul>
     *   <li>WindowAnimator.animate → TaskOrganizerController.dispatchTaskInfoChanged</li>
     *   <li>FlexibleTaskController.notifyZoomWindowShow → OplusFeatureMEMCGame.checkTopActivity
     *       → ActivityManager.getRunningTasks</li>
     * </ul>
     *
     * <p>空掉的 TaskInfo 在系统自己的任务/ActivityRecord 里（小窗任务正在做展开/收起过渡、
     * 或刚被 forceStop 后残留在任务栈里时最容易命中）。异常只要逃出 Handler/Looper 就会杀
     * system_server，所以这里吞掉并当作「这次没填成」（返回 false）；但只吞还不够——填了一半的
     * TaskInfo 流到监听方会在 writeTaskToParcel 上二次 NPE（2026-11-21 00:24:31 的软重启），
     * 所以吞完立刻补 token，拷包前再过滤一遍（见 hookTaskInfoParcelGuards）。</p>
     */
    private void hookTaskInfoCrashGuard(ClassLoader cl) {
        try {
            Class<?> taskClass = XposedHelpers.findClass("com.android.server.wm.Task", cl);
            java.util.Set<Method> hooked = XposedBridge.hookAllMethods(taskClass, "fillTaskInfo",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!param.hasThrowable()) {
                                return;
                            }
                            Throwable t = param.getThrowable();
                            if (sTaskInfoCrashGuarded < 30) {
                                sTaskInfoCrashGuarded++;
                                XposedBridge.log(TAG + " fillTaskInfo 崩溃已拦下("
                                        + sTaskInfoCrashGuarded + "/30): " + t);
                            }
                            param.setThrowable(null);
                            Class<?> rt = ((Method) param.method).getReturnType();
                            param.setResult(rt == boolean.class ? Boolean.FALSE : null);
                            // 关键：吞掉异常后 TaskInfo 是「填了一半」的（token 还没写进去），
                            // 直接流到监听方会在 writeTaskToParcel 里 NPE 再杀一次 system_server
                            // （00:24:31 就是这么重启的）。这里给它补一个本任务的 token。
                            repairTaskInfoToken(param.args[0], param.thisObject);
                        }
                    });
            XposedBridge.log(TAG + " task info crash guard hooked: " + hooked.size()
                    + " fillTaskInfo overload(s)");
            hookTaskInfoParcelGuards(cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " task info crash guard failed: " + t);
        }
    }

    // ==================== TaskInfo 拷包前的兜底 ====================

    /** {@code android.app.TaskInfo#token}（WindowContainerToken，没有它就 writeTaskToParcel 会 NPE）。 */
    private static Field sTaskInfoTokenField;
    private static Field sWindowContainerTokenField;
    private static boolean sWindowContainerTokenFieldSearched = false;
    private static int sTokenRepaired = 0;
    private static int sParcelDropped = 0;

    private static Field taskInfoTokenField() {
        Field f = sTaskInfoTokenField;
        if (f != null) return f;
        try {
            f = android.app.TaskInfo.class.getDeclaredField("token");
            f.setAccessible(true);
            sTaskInfoTokenField = f;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " TaskInfo.token field not found: " + t);
        }
        return sTaskInfoTokenField;
    }

    private static Object taskInfoToken(Object info) {
        Field f = taskInfoTokenField();
        if (f == null || info == null) return null;
        try {
            return f.get(info);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 从 Task（或其父类 WindowContainer / TaskFragment）里找一个现成的
     * {@code WindowContainerToken} 补到 TaskInfo 上。找不到就放弃，交给下面的拷包过滤。
     */
    private static void repairTaskInfoToken(Object info, Object task) {
        if (info == null || task == null) return;
        if (taskInfoToken(info) != null) return;
        Field src = windowContainerTokenField(task.getClass());
        if (src == null) return;
        try {
            Object token = src.get(task);
            Field dst = taskInfoTokenField();
            if (token == null || dst == null) return;
            dst.set(info, token);
            if (sTokenRepaired < 10) {
                sTokenRepaired++;
                XposedBridge.log(TAG + " TaskInfo.token 已补回(" + sTokenRepaired + "/10) via "
                        + src.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " TaskInfo.token 补回失败: " + t);
        }
    }

    private static Field windowContainerTokenField(Class<?> cls) {
        if (sWindowContainerTokenFieldSearched && sWindowContainerTokenField != null) {
            return sWindowContainerTokenField;
        }
        sWindowContainerTokenFieldSearched = true;
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getName().equals("android.window.WindowContainerToken")) {
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {
                    }
                    sWindowContainerTokenField = f;
                    return f;
                }
            }
        }
        return null;
    }

    /**
     * 拷包前把「token 为空」的 TaskInfo 剔掉，避免 {@code TaskInfo.writeTaskToParcel}
     * 在 {@code token.writeToParcel} 上 NPE 直接崩 system_server。
     */
    private void hookTaskInfoParcelGuards(ClassLoader cl) {
        // 1) Oplus 的可见任务变更监听（00:24:31 那次崩溃就在这里）：传 List<RunningTaskInfo>
        try {
            Class<?> proxy = XposedHelpers.findClass(
                    "com.oplus.app.IOplusTaskInfoChangeListener$Stub$Proxy", cl);
            XposedBridge.hookAllMethods(proxy, "onVisibleTasksInfoChange", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.args[0] instanceof java.util.List)) return;
                    java.util.List list = (java.util.List) param.args[0];
                    try {
                        java.util.Iterator<?> it = list.iterator();
                        while (it.hasNext()) {
                            Object item = it.next();
                            if (item != null && taskInfoToken(item) == null) {
                                it.remove();
                                if (sParcelDropped < 10) {
                                    sParcelDropped++;
                                    XposedBridge.log(TAG + " 剔掉 token 为空的 TaskInfo("
                                            + sParcelDropped + "/10)");
                                }
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " 过滤 TaskInfo 列表失败: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + " hooked Oplus visible-tasks parcel guard");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Oplus visible-tasks guard skipped: " + t.getMessage());
        }
        // 2) TaskOrganizer 的单条回调：token 为空就整条不发（少一条快照，不会崩）
        try {
            Class<?> proxy = XposedHelpers.findClass("android.app.ITaskOrganizer$Stub$Proxy", cl);
            XposedBridge.hookAllMethods(proxy, "onTaskInfoChanged", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || param.args[0] == null) return;
                    if (taskInfoToken(param.args[0]) == null) {
                        if (sParcelDropped < 10) {
                            sParcelDropped++;
                            XposedBridge.log(TAG + " 跳过 token 为空的 onTaskInfoChanged("
                                    + sParcelDropped + "/10)");
                        }
                        param.setResult(null);
                    }
                }
            });
            XposedBridge.log(TAG + " hooked TaskOrganizer parcel guard");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " TaskOrganizer guard skipped: " + t.getMessage());
        }
        hookParcelWriteGuard();
    }

    /**
     * 最后一道兜底：所有 TaskInfo 列表在写进 Binder 包之前统一过一遍。
     *
     * <p>Oplus 的「可见任务位置变化」广播（每次我们改小窗几何都会触发一次）会把整份
     * TaskInfo 列表写包发给监听方，其中只要有一条是「填了一半、token 为空」的
     * （校验等过渡期那条 Oplus NPE 的后遗症），{@code TaskInfo.writeTaskToParcel}
     * 就会在 {@code token.writeToParcel} 上 NPE，直接把 android.display 线程带走 ——
     * 表现就是坞窗里快速上下滑动就软重启。</p>
     *
     * <p>上面的监听 Proxy 过滤只覆盖 Oplus 那一条链；这里挂在 {@code Parcel.writeTypedList}
     * 上，任何走 typed list 发出去的 TaskInfo 列表都会被剔掉坏条目（动手前先看首个元素类型，
     * 不是 TaskInfo 的列表直接放行，不影响其它 binder 通信）。</p>
     */
    private void hookParcelWriteGuard() {
        try {
            XposedBridge.hookAllMethods(android.os.Parcel.class, "writeTypedList",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object o = param.args.length > 0 ? param.args[0] : null;
                            if (!(o instanceof List)) return;
                            List<?> list = (List<?>) o;
                            if (list.isEmpty()) return;
                            Object head = list.get(0);
                            if (!(head instanceof android.app.TaskInfo)) return;
                            boolean bad = false;
                            for (Object item : list) {
                                if (item instanceof android.app.TaskInfo && taskInfoToken(item) == null) {
                                    bad = true;
                                    break;
                                }
                            }
                            if (!bad) return;
                            List<Object> cleaned = new ArrayList<>(list.size());
                            for (Object item : list) {
                                if (item instanceof android.app.TaskInfo && taskInfoToken(item) == null) {
                                    continue;
                                }
                                cleaned.add(item);
                            }
                            param.args[0] = cleaned;
                            if (sParcelDropped < 10) {
                                sParcelDropped++;
                                XposedBridge.log(TAG + " 写包前剔掉 token 为空的 TaskInfo("
                                        + sParcelDropped + "/10) before=" + list.size()
                                        + " after=" + cleaned.size());
                            }
                        }
                    });
            XposedBridge.log(TAG + " hooked Parcel.writeTypedList TaskInfo guard");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Parcel write guard failed: " + t);
        }
    }

    /**
     * hook FlexibleCaptionView.updateTouchableRegion(Region)：
     * 每次标题栏把触摸区域应用到 Surface 前，把三点按钮触摸区域挖空，
     * 使该位置触摸穿透到自由小窗中的应用。
     *
     * <p>挖空对象用系统真正的按钮热区 {@code mHandleButtonRegion}，不再用
     * {@code mThreeButtonFrame}：RMX5200（realme UI 7）上这个外框实测铺满整个 caption
     * （[0,0][1440,2560]），减掉它等于把整块触摸区做空，SurfaceControl 会把空 Region
     * 当成「整窗可触摸」，标题栏反而吞掉整个小窗的事件 —— 表现就是迷你浮窗只能拖不能点。
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

                                /*
                                 * 特殊机型（ColorOS 16，如 PLK110_16.0.0.211）：该 ROM 上
                                 * mThreeButtonFrame / mHandleButtonRegion 都铺满整个 caption
                                 * （实测日志 [0,0][1272,2261]），挖任何一块都会让标题栏拿不到
                                 * HANDLE_EVENT、小窗拖不动。该机型不做穿透，保持原厂触摸区。
                                 */
                                if (isColorOs16Rom()) {
                                    XposedBridge.log(TAG + " skip carve on coloros16 rom: " + romDisplayId());
                                    return;
                                }

                                /*
                                 * 迷你浮窗：系统故意把整个迷你矩形并进 caption 触摸区，靠它接手势和
                                 * 「单击恢复小窗」。这里挖任何一块都会让迷你浮窗点不动，只在普通小窗态做穿透。
                                 */
                                if (isMiniZoomState(param.thisObject)) {
                                    XposedBridge.log(TAG + " skip carve in mini state");
                                    return;
                                }

                                Region region = (Region) param.args[0];
                                if (region == null || region.isEmpty()) return;

                                Rect hot = handleButtonRegion(param.thisObject);
                                if (hot == null || hot.isEmpty()) return;

                                if (isRegionTooLarge(param.thisObject, hot)) {
                                    XposedBridge.log(TAG + " skip carve: three-dot hot region too large "
                                            + hot.toShortString());
                                    return;
                                }

                                // 在副本上挖，且挖完必须还剩得下一块像样的触摸区，否则宁可放弃：
                                // 空 Region 在 SurfaceControl 里等价「整窗可触摸」，比不穿透更糟。
                                Region carved = new Region(region);
                                carved.op(hot, Region.Op.DIFFERENCE);
                                if (carved.isEmpty()) {
                                    XposedBridge.log(TAG + " skip carve: region would become empty");
                                    return;
                                }
                                // 别把 mRegion 本体挖掉，只替换这一次调用传出去的参数。
                                param.args[0] = carved;
                                XposedBridge.log(TAG + " carved three-dot touch region for touch-through: "
                                        + hot.toShortString() + " remain=" + carved.getBounds().toShortString());
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

    /** 迷你（含 ready）态：该状态下标题栏要独占整块区域做手势与单点恢复。 */
    private static boolean isMiniZoomState(Object captionView) {
        if (!(captionView instanceof View)) return false;
        try {
            Object v = XposedHelpers.callMethod(captionView, "isMiniZoomState");
            return v instanceof Boolean && (Boolean) v;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 系统真正的三点按钮热区（caption 局部坐标），挖空以它为准。 */
    private static Rect handleButtonRegion(Object captionView) {
        try {
            Object mgr = XposedHelpers.getObjectField(captionView, "mFlexibleCaptionViewRegionMgr");
            if (mgr == null) return null;
            Object r = XposedHelpers.getObjectField(mgr, "mHandleButtonRegion");
            return (r instanceof Rect) ? new Rect((Rect) r) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 设备 rom 版本串：PLK110_16.0.0.211(CN01)、RMX5200_17.0.0.xxx、RMX3820_16.0.3.501。 */
    private static String sRomDisplayId;

    private static String romDisplayId() {
        if (sRomDisplayId != null) return sRomDisplayId;
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object v = sp.getMethod("get", String.class).invoke(null, "ro.build.display.id");
            sRomDisplayId = (v instanceof String) ? (String) v : "";
        } catch (Throwable t) {
            sRomDisplayId = "";
        }
        return sRomDisplayId;
    }

    /**
     * 是否为 ColorOS 16（OPPO / 一加上一代系统）。
     *
     * <p>规则：
     * <ul>
     *   <li>realme 机型（版本串前缀为 RMX，如 {@code RMX3820_16.0.3.501}）一律不算——
     *       realme UI 的版本号里同样带 16，容易和 ColorOS 16 混；</li>
     *   <li>其余机型取 {@code _} 之后的第一段版本号，{@code 16.} 开头即 ColorOS 16；
     *       ColorOS 17 是 {@code 17.} 开头，例如 {@code CPH2791_17.0.0.102}。</li>
     * </ul>
     */
    private static boolean isColorOs16Rom() {
        String id = romDisplayId();
        if (id.isEmpty()) return false;
        int us = id.indexOf('_');
        if (us < 0 || us + 3 > id.length()) return false;
        String model = id.substring(0, us);
        if (model.startsWith("RMX")) return false;
        String ver = id.substring(us + 1);
        return ver.startsWith("16.");
    }

    /**
     * 触摸矩形是否大得不像「三点按钮热区」（面积 ≥ caption 的 40%）。
     * 用来防止某些 ROM 把 mHandleButtonRegion 放大成整块，从而把关闭/全屏按钮的点击一起吞掉。
     */
    private static boolean isRegionTooLarge(Object captionView, Rect rect) {
        if (!(captionView instanceof View) || rect == null) return true;
        View v = (View) captionView;
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) return true;
        long all = (long) w * h;
        long area = (long) rect.width() * rect.height();
        return area * 10 >= all * 4;
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

                                /*
                                 * 迷你浮窗的单击是「恢复成自由小窗」（FlexibleTaskCaptionView
                                 * onSingleTapConfirmed，要求 mActionFlag == 0）。这里吞掉会把
                                 * 迷你浮窗的点击恢复打死，只在普通小窗态拦菜单。
                                 */
                                if (isMiniZoomState(thisObj)) return;

                                Rect region = handleButtonRegion(thisObj);
                                if (region == null || region.isEmpty()) return;

                                MotionEvent ev = (MotionEvent) param.args[0];
                                int x = (int) ev.getX();
                                int y = (int) ev.getY();
                                if (!region.contains(x, y)) return;

                                int actionFlag = (int) XposedHelpers.getIntField(thisObj, "mActionFlag");

                                /*
                                 * ColorOS 16（c16）：三点按钮的热区就是 mHandleButtonRegion
                                 * 这一小块（mThreeButtonFrame 反而铺满整个 caption）。
                                 * 该机型上 mActionFlag 有时来不及在 UP 之前更新为 1，
                                 * 依赖 flag 判断会漏拦，表现就是「偶尔点出菜单」。
                                 * 这里改成纯坐标判定，只要落进这一小块就吞掉。
                                 * 若该 ROM 把这个矩形放大到接近整个 caption（覆盖关闭/全屏按钮），
                                 * 则退回原来的 flag 判定，避免误吞其他按钮。
                                 */
                                if (isColorOs16Rom() && !isRegionTooLarge(thisObj, region)) {
                                    XposedBridge.log(TAG + " blocked three-dot menu tap (c16, by rect) at "
                                            + x + "," + y);
                                    param.setResult(false);
                                    return;
                                }

                                if (actionFlag != 1) return; // HANDLE_EVENT，仅三点/把手按钮区

                                XposedBridge.log(TAG + " blocked three-dot menu tap at " + x + "," + y);
                                param.setResult(false);
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
                                // 我们自己正在走官方恢复：放行，别再弹坞窗。
                                if (sRestoring) {
                                    param.setObjectExtra("fwRestorePass", Boolean.TRUE);
                                    XposedBridge.log(TAG + " restoring in progress, pass through official restore");
                                    return;
                                }
                                Object floatHandleView = param.thisObject;
                                try {
                                    Context ctx = (Context) XposedHelpers.callMethod(floatHandleView, "getContext");
                                    loadConfigFromContext(ctx);
                                } catch (Throwable ignored) {
                                }
                                if (!enableDock) {
                                    param.setObjectExtra("fwRestorePass", Boolean.TRUE);
                                    XposedBridge.log(TAG + " dock disabled, skip intercept");
                                    return;
                                }
                                // 该方法返回 boolean，setResult(null) 会在拆箱时 NPE 导致 system_server 软重启
                                if (sDockShowing) {
                                    param.setResult(false);
                                    return;
                                }
                                Object floatHandleInfo = param.args[0];
                                String reason = (String) param.args[1];
                                XposedBridge.log(TAG + " float handle clicked, reason=" + reason);
                                param.setResult(false);
                                sCurrentFloatHandleView = floatHandleView;
                                if (floatHandleView instanceof View) {
                                    sHandleHandler = ((View) floatHandleView).getHandler();
                                }
                                showFloatDock(floatHandleView, floatHandleInfo, cl);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " hookFloatHandleClick error: " + t.getMessage());
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            // 只有官方恢复真的跑起来了才清标记（被我们拦成坞窗时任务还在浮标上，
                            // 保活标记必须留着）。
                            try {
                                if (!Boolean.TRUE.equals(param.getObjectExtra("fwRestorePass"))) return;
                                Object info = param.args[0];
                                if (info == null) return;
                                int taskId = XposedHelpers.getIntField(info, "taskId");
                                Object task = LivelyHook.taskById(sClassLoader, taskId);
                                if (task == null) {
                                    XposedBridge.log(TAG + " restore clear marks: task not found taskId=" + taskId);
                                    return;
                                }
                                // insensible 残留会让 InsensibleTaskController 把 BACK 键整个吞掉，
                                // 小窗恢复后按返回毫无反应就出在这里。
                                FloatKeepAliveHook.clearTaskLively(task);
                                XposedBridge.log(TAG + " cleared lively marks on restore taskId=" + taskId);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " restore clear marks error: " + t.getMessage());
                            }
                        }
                    });
            XposedBridge.log(TAG + " FloatHandleView click hook success");

            // 解除小窗数量上限后的配套修补：系统 onSingleTapUp 只写了 size==2 / size==1 两条分支，
            // 三个以上既不切双图标模式、也不调 startFlexibleFreeformFromHandleView，
            // 直接把点击吞掉 —— 表现就是「悬浮球点不动、坞窗也不弹」。
            // 这里给 3 个以上补一条通路：仍然走 startFlexibleFreeformFromHandleView，
            // 由上面的 hook 拦成我们自己的坞窗（不真的把小窗拉起来）。
            XposedHelpers.findAndHookMethod(fhvClass, "onSingleTapUp", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object view = param.thisObject;
                                Object listObj = XposedHelpers.callMethod(view, "getFloatHandleInfoList");
                                if (!(listObj instanceof List)) {
                                    return;
                                }
                                List list = (List) listObj;
                                if (list.size() < 3) {
                                    return;
                                }
                                if (sDockContainer != null || sDockShowing) {
                                    // 坞窗已经在显示（或正在显示），这次点击直接吞掉
                                    param.setResult(true);
                                    return;
                                }
                                XposedBridge.log(TAG + " onSingleTapUp multi-float size=" + list.size());
                                XposedHelpers.callMethod(view, "startFlexibleFreeformFromHandleView",
                                        list.get(0), "onSingleTapUp-MultiFloat");
                                param.setResult(true);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " multi-float tap hook error: " + t.getMessage());
                            }
                        }
                    });
            XposedBridge.log(TAG + " FloatHandleView multi-float tap hook success");

            // 悬浮球只显示「最新最小化的那一个」，不做官方那种双图标堆叠。
            // FloatHandleView.updateIconStyle() 里：size==1 只画第一图标；size==2 把 get(1)
            // 画在第一图标、get(0) 画在第二图标（所以「越靠列表尾部越新」）；size>=3 干脆两个都不画。
            // View 的 getFloatHandleInfoList() 返回的是 List.copyOf(...) 拷贝，替换返回值不会污染
            // 控制器的真实列表，所以这里把 View 看到的列表裁成「只剩最后一项」：
            // 布局、点击、动画全部自动退回单图标那一套，坞窗仍然拿全量列表显示所有卡片。
            XposedHelpers.findAndHookMethod(fhvClass, "getFloatHandleInfoList", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object res = param.getResult();
                        if (!(res instanceof List)) {
                            return;
                        }
                        List src = (List) res;
                        if (src.size() < 2) {
                            return;
                        }
                        Object newest = src.get(src.size() - 1);
                        if (newest == null) {
                            return;
                        }
                        List single = new ArrayList();
                        single.add(newest);
                        param.setResult(single);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " single icon hook error: " + t.getMessage());
                    }
                }
            });
            XposedBridge.log(TAG + " FloatHandleView single-icon hook success");

            // 5200(Android 17 / ColorOS 17) 走的是另一套：图标不是一次性排好，而是
            // FloatIconListAdapter.addFloatIconViewInfo 增量插入，只有
            // 「size == getLimitMaxFloatNum()」时才挤掉最后一个。上限被我们顶到 12 之后，
            // 2~11 个最小化应用就会全堆在悬浮球上（5200 看到的堆叠就是这个）。
            // 这里直接接管：每来一个新图标就重置成「只有它一个」。
            // 3820 没有这个类，findClass 失败会被 catch 掉，不影响原逻辑。
            try {
                Class<?> adapterCls = XposedHelpers.findClass(
                        "com.android.server.wm.floathandle.FloatHandleView$FloatIconListAdapter", cl);
                XposedBridge.hookAllMethods(adapterCls, "addFloatIconViewInfo", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object info = param.args[0];
                            if (info == null) {
                                return;
                            }
                            ArrayList one = new ArrayList();
                            one.add(info);
                            XposedHelpers.callMethod(param.thisObject,
                                    "setFloatIconViewInfoList", one);
                            XposedHelpers.callMethod(param.thisObject, "notifyDataSetChanged");
                            param.setResult(true);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " single-icon adapter add error: " + t.getMessage());
                        }
                    }
                });
                // 图标记忆（5200 走适配器）：恢复某个应用时系统把它的图标摘掉，列表被摘空后
                // 悬浮球就只剩一个空球。这里在 updateIconStyle 结束后兜一层：
                // 多于一个就只留最新那个；一个都没有就用控制器里仍挂着的应用重建图标。
                if (XposedHelpers.findFieldIfExists(fhvClass, "mIconListAdapter") != null) {
                    XposedBridge.hookAllMethods(fhvClass, "updateIconStyle", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object view = param.thisObject;
                                Object adapter = XposedHelpers.getObjectField(view, "mIconListAdapter");
                                if (adapter == null) return;
                                Object listObj = XposedHelpers.getObjectField(
                                        adapter, "mFloatIconViewInfoList");
                                if (!(listObj instanceof List)) return;
                                List icons = (List) listObj;
                                if (icons.size() > 1) {
                                    ArrayList one = new ArrayList();
                                    one.add(icons.get(0));
                                    XposedHelpers.callMethod(adapter, "setFloatIconViewInfoList", one);
                                    XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
                                    XposedBridge.log(TAG + " single-icon trim: " + icons.size() + " -> 1");
                                    return;
                                }
                                if (icons.isEmpty()) {
                                    refillFloatIcon(view, cl);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " adapter icon memory error: " + t.getMessage());
                            }
                        }
                    });
                }
                XposedBridge.log(TAG + " FloatIconListAdapter single-icon hooks success");
            } catch (Throwable t) {
                XposedBridge.log(TAG + " FloatIconListAdapter hook skipped: " + t.getMessage());
            }

            // 图标记忆（3820 走这里）：updateIconStyle 只会画 size==1 的 get(0) 和 size==2 的
            // get(1)/get(0)，列表一空两个图标都隐藏，出现「悬浮球在、图标没了」。
            // 这里在重绘结束后兜一层：有应用就画最新的那个，没有就画上一次记住的那个。
            if (XposedHelpers.findFieldIfExists(fhvClass, "mIconViewFir") != null) {
                try {
                    XposedBridge.hookAllMethods(fhvClass, "updateIconStyle", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object view = param.thisObject;
                                Object iconObj = XposedHelpers.getObjectField(view, "mIconViewFir");
                                if (!(iconObj instanceof ImageView)) return;
                                ImageView fir = (ImageView) iconObj;
                                if (fir.getVisibility() == View.VISIBLE
                                        && fir.getDrawable() != null) {
                                    rememberHandleApp(view, cl);
                                    return;
                                }
                                paintHandleIcon(view, fir, cl);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " icon memory error: " + t.getMessage());
                            }
                        }
                    });
                    XposedBridge.log(TAG + " FloatHandleView icon-memory hook success");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " icon-memory hook skipped: " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookFloatHandleClick failed: " + t.getMessage());
        }
    }

    /** 记住当前画在悬浮球上的应用，供列表清空后的补画使用。 */
    private static void rememberHandleApp(Object view, ClassLoader cl) {
        try {
            Object listObj = XposedHelpers.callMethod(view, "getFloatHandleInfoList");
            if (!(listObj instanceof List) || ((List) listObj).isEmpty()) return;
            List list = (List) listObj;
            Object info = list.get(list.size() - 1);
            if (info == null) return;
            sLastHandlePkg = (String) XposedHelpers.callMethod(info, "getPackageName");
            sLastHandleUserId = (int) XposedHelpers.callMethod(info, "getUserId");
        } catch (Throwable ignored) {
        }
    }

    /** 从控制器真实列表里挑一个应用记进记忆（excludeTaskId 是刚被恢复/移除的那个）。 */
    private static void rememberOtherHandleApp(ClassLoader cl, int excludeTaskId) {
        try {
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl),
                    "getInstance");
            Object raw = XposedHelpers.callMethod(fhc, "getFloatHandleInfoList");
            if (!(raw instanceof List) || ((List) raw).isEmpty()) return;
            List list = (List) raw;
            for (int i = list.size() - 1; i >= 0; i--) {
                Object info = list.get(i);
                if (info == null) continue;
                int id = -1;
                try {
                    id = XposedHelpers.getIntField(info, "taskId");
                } catch (Throwable ignored) {
                }
                if (id == excludeTaskId) continue;
                sLastHandlePkg = (String) XposedHelpers.callMethod(info, "getPackageName");
                sLastHandleUserId = (int) XposedHelpers.callMethod(info, "getUserId");
                XposedBridge.log(TAG + " icon memory pick pkg=" + sLastHandlePkg + " exclude=" + excludeTaskId);
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 3820：主图标空了就补画，优先用控制器里最新挂着的应用，没有就用记忆的那个。 */
    private static void paintHandleIcon(Object view, ImageView fir, ClassLoader cl) {
        try {
            Context ctx = (Context) XposedHelpers.callMethod(view, "getContext");
            String pkg = null;
            int userId = 0;
            Object infosObj = XposedHelpers.callMethod(view, "getFloatHandleInfoList");
            if (infosObj instanceof List && !((List) infosObj).isEmpty()) {
                List infos = (List) infosObj;
                Object info = infos.get(infos.size() - 1);
                if (info != null) {
                    pkg = (String) XposedHelpers.callMethod(info, "getPackageName");
                    userId = (int) XposedHelpers.callMethod(info, "getUserId");
                }
            }
            if (pkg == null) {
                pkg = sLastHandlePkg;
                userId = sLastHandleUserId;
            }
            if (pkg == null) return;
            sLastHandlePkg = pkg;
            sLastHandleUserId = userId;
            Bitmap icon = (Bitmap) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FlexibleWindowUtils", cl),
                    "getAdjustAppBitmap", ctx, pkg, userId);
            if (icon == null) return;
            fir.setImageBitmap(icon);
            fir.setVisibility(View.VISIBLE);
            XposedBridge.log(TAG + " icon memory repaint pkg=" + pkg);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " icon memory error: " + t.getMessage());
        }
    }

    /**
     * 5200 适配器路径的图标补画：图标列表被摘空时，用控制器里仍挂着的应用重建一个图标。
     * 控制器列表为空（真没有应用了）就保留上一次记忆的包名，仍然把图标画上。
     */
    private static void refillFloatIcon(Object view, ClassLoader cl) {
        try {
            Object adapter = XposedHelpers.getObjectField(view, "mIconListAdapter");
            if (adapter == null) return;
            Object listObj = XposedHelpers.getObjectField(adapter, "mFloatIconViewInfoList");
            if (listObj instanceof List && !((List) listObj).isEmpty()) return;
            Context ctx = (Context) XposedHelpers.callMethod(view, "getContext");
            String pkg = null;
            int userId = 0;
            int handleId = -1;
            Object infosObj = XposedHelpers.callMethod(view, "getFloatHandleInfoList");
            if (infosObj instanceof List && !((List) infosObj).isEmpty()) {
                List infos = (List) infosObj;
                Object info = infos.get(infos.size() - 1);
                if (info != null) {
                    pkg = (String) XposedHelpers.callMethod(info, "getPackageName");
                    userId = (int) XposedHelpers.callMethod(info, "getUserId");
                    try {
                        handleId = XposedHelpers.getIntField(info, "taskId");
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (pkg == null) {
                pkg = sLastHandlePkg;
                userId = sLastHandleUserId;
            }
            if (pkg == null) return;
            sLastHandlePkg = pkg;
            sLastHandleUserId = userId;
            Bitmap bmp = (Bitmap) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FlexibleWindowUtils", cl),
                    "getAdjustAppBitmap", ctx, pkg, userId);
            if (bmp == null) return;
            Object iconInfo = XposedHelpers.newInstance(
                    XposedHelpers.findClass(
                            "com.android.server.wm.floathandle.FloatHandleView$FloatIconViewInfo", cl),
                    bmp, pkg, handleId);
            ArrayList one = new ArrayList();
            one.add(iconInfo);
            XposedHelpers.callMethod(adapter, "setFloatIconViewInfoList", one);
            XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
            XposedBridge.log(TAG + " adapter icon memory repaint pkg=" + pkg);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " adapter icon memory error: " + t.getMessage());
        }
    }

    /** 显示坞窗：从悬浮球所在侧滑出，展示所有悬浮球应用卡片。 */
    private void showFloatDock(Object floatHandleView, Object floatHandleInfo, ClassLoader cl) {
        try {
            if (sDockClosing) {
                XposedBridge.log(TAG + " showFloatDock skipped: closing");
                return;
            }
            if (sDockContainer != null) {
                XposedBridge.log(TAG + " showFloatDock skipped: container exists showing="
                        + sDockShowing + " attached=" + sDockContainer.isAttachedToWindow()
                        + " children=" + sDockContainer.getChildCount());
                return;
            }
            XposedBridge.log(TAG + " showFloatDock begin showing=" + sDockShowing
                    + " apps=" + sDockApps.size());

            // 获取 Context
            Context ctx = (Context) XposedHelpers.callMethod(floatHandleView, "getContext");
            if (ctx == null) {
                XposedBridge.log(TAG + " showFloatDock: context is null");
                return;
            }

            // 获取屏幕尺寸。
            // 用 Display.getRealMetrics 而不是 ctx.getResources().getDisplayMetrics()：
            // 旋转后 groups 的 Resources 配置更新通常晚一两帧，重建坞窗时读到旧值就会
            // 出现“已经转成竖屏、坞窗却还按横屏那套宽度/比例画”。
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics realMetrics = realDisplayMetrics(ctx);
            int screenWidth = realMetrics.widthPixels;
            int screenHeight = realMetrics.heightPixels;

            // 判断悬浮球在左边还是右边
            int side = 1; // 默认右边
            try {
                side = XposedHelpers.getIntField(floatHandleInfo, "side");
            } catch (Throwable ignored) {
            }
            XposedBridge.log(TAG + " showFloatDock side=" + side);

            hideAllFloatHandles(cl, 0f);

            // 全屏透明容器。方向变化由 showFloatDock 末尾启动的 1s 轮询 watcher 检测
            // （WindowManager 添加的视图不一定收到 onConfigurationChanged 分发，而且窗口
            // 参数是按旋转前的像素给的，容器自身尺寸不一定跟着变，轮询最稳）。
            final FrameLayout container = new FrameLayout(ctx);
            container.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // 坞窗主体
            LinearLayout dock = new LinearLayout(ctx);
            dock.setOrientation(LinearLayout.VERTICAL);
            final boolean night = isNightMode(ctx);
            // 深色模式下换成半透明深灰，浅色仍是原来的 #e5e5e5
            int dockColor = night ? 0xE51E1E1E : 0xE5E5E5E5;
            int dockCorner = dpToPx(ctx, CARD_CONTENT_CORNER_DP);
            float[] dockRadii;
            if (side == 1) {
                // 右边弹出，左边圆角
                dockRadii = new float[]{dockCorner, dockCorner, 0, 0, 0, 0, dockCorner, dockCorner};
            } else {
                // 左边弹出，右边圆角
                dockRadii = new float[]{0, 0, dockCorner, dockCorner, dockCorner, dockCorner, 0, 0};
            }
            // 面板背景换成「可挖洞」的版本：真实小窗卡片那一块必须是空的，
            // 否则 0xE5 的面板底会把下面的应用窗口压暗（坞窗 type 2520 在应用窗口之上）。
            DockHoleDrawable dockBg = new DockHoleDrawable(dockColor, dockRadii);
            // 洞的圆角和卡片内容区一致，边缘和面板底色接得上，看不出挖过洞。
            dockBg.setHoleRadius(dpToPx(ctx, CARD_CONTENT_CORNER_DP));
            dock.setBackground(dockBg);
            sDockHoleBg = dockBg;

            // 横屏：坞窗只占 1/4 屏宽，把剩下的 3/4 留给应用。
            // 竖屏：占 1/2 屏宽。画面宽度不再写死像素，直接取卡片内容宽（见 addAppCard），
            // 所以坞窗不需要为画面特意加宽。
            boolean landscape = screenWidth > screenHeight;
            sDockLandscape = landscape;
            // 把「比例钉死 + 画面铺满」同步给绘制层：竖屏 16:9、横屏 13:9，都按 cover 铺满，
            // 宽度永远等于卡片框宽。
            LivelyHook.setPreviewStyle(FIXED_PREVIEW_ASPECT, FIXED_PREVIEW_ASPECT
                    ? (landscape ? PREVIEW_ASPECT_LANDSCAPE : PREVIEW_ASPECT_PORTRAIT) : 0f);
            sDockInfo = floatHandleInfo;
            // 坞窗宽度两个方向共用同一个值（屏幕长边/4）：
            // 横屏的 screenWidth 是长边、竖屏的是短边，若各按「横屏 1/4、竖屏 1/2」算，
            // 会得到 784 与 720 两个宽度，画面宽度随之 608↔544，转屏看着像「长度变短」。
            // 统一之后坞窗和画面在两个方向完全一致。
            int dockWidth = Math.max(screenWidth, screenHeight) / 4;
            // 横屏高度本来就紧，上下各留 10dp；竖屏仍留 50dp
            int dockHeight = screenHeight - dpToPx(ctx, landscape ? 20 : 100);
            FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(dockWidth, dockHeight);
            dockParams.gravity = Gravity.CENTER_VERTICAL | (side == 1 ? Gravity.END : Gravity.START);
            container.addView(dock, dockParams);

            // 滚动区域（应用卡片列表）
            ScrollView scrollView = new ScrollView(ctx);
            scrollView.setBackgroundColor(0x00000000); // 透明
            scrollView.setNestedScrollingEnabled(false);
            // 滚动时不显示侧边滚动条：坞窗本身很窄，那条竖线很扎眼
            scrollView.setVerticalScrollBarEnabled(false);
            scrollView.setHorizontalScrollBarEnabled(false);
            scrollView.setScrollbarFadingEnabled(true);
            scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
            scrollView.setLayoutParams(scrollParams);
            LinearLayout cardList = new LinearLayout(ctx);
            cardList.setOrientation(LinearLayout.VERTICAL);
            cardList.setPadding(dpToPx(ctx, 12), dpToPx(ctx, 12), dpToPx(ctx, 12), dpToPx(ctx, 12));
            scrollView.addView(cardList);
            dock.addView(scrollView);
            sDockScrollView = scrollView;
            sDockCardList = cardList;
            // 滚动时实时把卡片画面裁到可视范围，避免 surface 画到滚动区下面的「清除」按钮上。
            scrollView.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                @Override
                public void onScrollChange(View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) {
                    refreshPreviewCrop();
                }
            });

            // 清除按钮（坞窗容器内的普通 View，随坞窗一起显示/消失）
            TextView clearBtn = new TextView(ctx);
            clearBtn.setText("清除");
            clearBtn.setTextColor(night ? 0xFFEDEDED : 0xFF000000);
            clearBtn.setTextSize(15);
            clearBtn.setGravity(Gravity.CENTER);
            GradientDrawable clearBg = new GradientDrawable();
            clearBg.setColor(night ? 0xFF2E2E2E : 0xFFF3F3F3);
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

            // 添加应用卡片：倒序——最新的最小化应用排在最上面，最早的在底部。
            // 控制器的 mFloatHandleInfoList 是「尾部最新」（FloatHandleView 在 size==2 时
            // 把 get(1) 当主图标就是这个原因），所以从上往下遍历即最新在上。
            for (int i = sDockApps.size() - 1; i >= 0; i--) {
                Object info = sDockApps.get(i);
                try {
                    addAppCard(ctx, cardList, info, cl, dockWidth, dockHeight);
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
                        // 先把实时画面的图层与可见性还原成贴边隐藏态，再走官方清除，
                        // 否则任务还处于"可见"状态，removeFloatHandle 会让小窗闪一下
                        DockLive.releaseAll();
                        clearAllDockApps(finalCl);
                        removeFloatDock();
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " clear all error: " + t.getMessage());
                    }
                }
            });

            // 点击坞窗外收回，点击坞窗内不拦截。
            // 窗口现在只覆盖坞窗矩形，窗口外的按下由系统以 ACTION_OUTSIDE 送到这里。
            final LinearLayout finalDock = dock;
            container.setOnTouchListener(new View.OnTouchListener() {
                private float outsideDownX;
                private float outsideDownY;
                private boolean outsideTapCandidate;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    int action = event.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN) {
                        float x = event.getRawX();
                        float y = event.getRawY();
                        int[] loc = new int[2];
                        finalDock.getLocationOnScreen(loc);
                        boolean insideDock = x >= loc[0] && x <= loc[0] + finalDock.getWidth()
                                && y >= loc[1] && y <= loc[1] + finalDock.getHeight();
                        if (!insideDock) {
                            // The dock window spans the screen. Lock and unlock gestures can
                            // therefore arrive here as outside touches; defer dismissal until
                            // the gesture ends and confirm the device is still interactive.
                            outsideDownX = x;
                            outsideDownY = y;
                            outsideTapCandidate = true;
                            return true;
                        }
                        outsideTapCandidate = false;
                        // 菜单打开时，点在坞窗内但菜单外：只关菜单，不动坞窗
                        if (sAppMenuView != null) {
                            int[] mLoc = new int[2];
                            sAppMenuView.getLocationOnScreen(mLoc);
                            boolean inMenu = x >= mLoc[0] && x <= mLoc[0] + sAppMenuView.getWidth()
                                    && y >= mLoc[1] && y <= mLoc[1] + sAppMenuView.getHeight();
                            if (!inMenu) {
                                XposedBridge.log(TAG + " tap outside menu, close it");
                                removeAppMenu();
                                return true;
                            }
                            return false;
                        }
                    }
                    if (outsideTapCandidate && action == MotionEvent.ACTION_MOVE) {
                        float dx = Math.abs(event.getRawX() - outsideDownX);
                        float dy = Math.abs(event.getRawY() - outsideDownY);
                        if (dx > 36f || dy > 36f) outsideTapCandidate = false;
                        return true;
                    }
                    if (outsideTapCandidate && (action == MotionEvent.ACTION_UP
                            || action == MotionEvent.ACTION_CANCEL)) {
                        boolean dismiss = action == MotionEvent.ACTION_UP
                                && Math.abs(event.getRawX() - outsideDownX) <= 36f
                                && Math.abs(event.getRawY() - outsideDownY) <= 36f;
                        outsideTapCandidate = false;
                        if (dismiss) {
                            final FrameLayout expectedContainer = container;
                            container.postDelayed(new Runnable() {
                                @Override public void run() {
                                    if (sDockContainer != expectedContainer || !sDockShowing) return;
                                    Context context = expectedContainer.getContext();
                                    boolean keyguardLocked = false;
                                    boolean interactive = true;
                                    try {
                                        android.app.KeyguardManager km = (android.app.KeyguardManager)
                                                context.getSystemService(Context.KEYGUARD_SERVICE);
                                        keyguardLocked = km != null && km.isKeyguardLocked();
                                    } catch (Throwable ignored) {
                                    }
                                    try {
                                        android.os.PowerManager pm = (android.os.PowerManager)
                                                context.getSystemService(Context.POWER_SERVICE);
                                        interactive = pm == null || pm.isInteractive();
                                    } catch (Throwable ignored) {
                                    }
                                    XposedBridge.log(TAG + " outside tap confirm keyguard="
                                            + keyguardLocked + " interactive=" + interactive);
                                    if (!keyguardLocked && interactive) removeFloatDock();
                                }
                            }, 900L);
                        }
                        return true;
                    }
                    return false;
                }
            });

            // 窗口参数：窗口尺寸就是坞窗矩形，不再铺满全屏。
            // 卡片画面是 SurfaceView，它的 surface 是窗口外面的独立图层，**不受 ScrollView
            // 和父 View 的裁剪**：卡片列表一旦比坞窗高要滚动，画面就会画到坞窗外面、压住
            // 「清除」按钮（3820 上快照比例 2.23、坞窗只有 2422 高，两张卡片就超了）。
            // 把窗口收缩到坞窗本身，溢出的那一截会被窗口 layer 的 crop 裁掉，画面永远出不了坞窗。
            // 代价：窗口不再含屏幕其它区域，点外面收回改用 FLAG_WATCH_OUTSIDE_TOUCH 的 ACTION_OUTSIDE。
            int dockLeft = side == 1 ? screenWidth - dockWidth : 0;
            int dockTopScreen = (screenHeight - dockHeight) / 2;
            // 窗口类型对齐官方 5200 LivelyFloatHandleView.initWindowParams 的 type=2002。
            // 之前用 Oplus 的 2520（TYPE_SYSTEM_EDGE_PANEL），mBaseLayer=31000，比 SystemUI 的
            // 音量条 VolumeDialog（TYPE_VOLUME_OVERLAY=2032）高得多，坞窗开着时全屏遮罩把音量条
            // 整个压住 —— 真机现象：按音量键音量条不出现、声音也像没变。
            // 层不是音量条不显示的原因：官方 5200 的 LivelyFloatHandleView.initWindowParams
            // 同样是 type=2002 + privateFlags=16（baseLayer 31000），而它开着坞窗时音量条正常；
            // 同时 TYPE_VOLUME_OVERLAY(2032) 在 Oplus 层表里是 layer 63（mBaseLayer 631000），
            // 任何 2002/2038 的浮窗都不可能压住它。所以这里回到官方口径，不再试层。
            // 坞窗开着时屏幕过几分钟会自动变暗（BrightnessEvent policy=DIM，亮度掉到
            // brightnessDim=0.05），因为卡片里的应用不在前台、它的 KEEP_SCREEN_ON 不生效，
            // 坞窗窗口自己又没申请常亮。碰一下屏幕（含关坞窗）触发 user activity 才恢复，
            // 所以看起来像「坞窗记住了一个低亮度」。这里给坞窗窗口申请常亮，坞窗一关自动失效。
            WindowManager.LayoutParams windowParams = new WindowManager.LayoutParams(2002); // TYPE_PHONE
            windowParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            try {
                Field pf = WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
                pf.setAccessible(true);
                pf.setInt(windowParams, 16); // PRIVATE_FLAG_TRUSTED_OVERLAY
            } catch (Throwable ignored) {
            }
            windowParams.format = PixelFormat.TRANSLUCENT;
            windowParams.width = dockWidth;
            windowParams.height = dockHeight;
            windowParams.x = dockLeft;
            windowParams.y = dockTopScreen;
            windowParams.gravity = Gravity.TOP | Gravity.START;
            windowParams.setTitle("FreeWindowDock");

            // 坞窗外的点击必须由我们自己吃掉，否则点桌面图标会顺手把那个应用一起打开。
            // 只靠 FLAG_WATCH_OUTSIDE_TOUCH 不够用：它只是「告诉」我们外面被点了一下，
            // 事件本身照样派发给下层窗口。所以这里单独加一个铺满全屏、完全透明的遮罩窗口
            // （type 2003，层级低于坞窗的 2520），坞窗外的触摸全部落到它身上：
            // 收到 DOWN 就收起坞窗并 return true 把整个触摸序列吃掉，
            // 遮罩本身留到坞窗滑出动画结束（finishRemoveDock）才移除 —— 也就是
            // 「坞窗完全收起之后，其它地方才恢复可点」。
            try {
                View mask = new View(ctx);
                // 与坞窗同一个 type（2520），靠「先添加」压在下层：WMS 对同 type/同 subLayer
                // 的窗口按添加顺序叠放，所以后加的坞窗一定盖在遮罩之上。用 2520 而不是
                // 2003 也顺手避开了系统对 TYPE_SYSTEM_ALERT 的频率限流。
                // 与坞窗同一个 type（2002）同一个 privateFlags，靠「先添加」压在下层：
                // WMS 对同 type/同 subLayer 的窗口按添加顺序叠放，后加的坞窗一定盖在遮罩之上。
                WindowManager.LayoutParams maskParams = new WindowManager.LayoutParams(2002);
                maskParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
                maskParams.format = PixelFormat.TRANSLUCENT;
                maskParams.width = WindowManager.LayoutParams.MATCH_PARENT;
                maskParams.height = WindowManager.LayoutParams.MATCH_PARENT;
                maskParams.gravity = Gravity.TOP | Gravity.START;
                maskParams.setTitle("FreeWindowDockMask");
                try {
                    Field pf2 = WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
                    pf2.setAccessible(true);
                    pf2.setInt(maskParams, 16); // PRIVATE_FLAG_TRUSTED_OVERLAY
                } catch (Throwable ignored) {
                }
                mask.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                            XposedBridge.log(TAG + " tap outside dock (mask), closing");
                            removeAppMenu();
                            removeFloatDock();
                        }
                        return true; // DOWN/UP/MOVE 全部吃掉，绝不再往下派发
                    }
                });
                wm.addView(mask, maskParams);
                sDockMask = mask;
            } catch (Throwable t) {
                XposedBridge.log(TAG + " dock mask add failed: " + t.getMessage());
            }

            XposedBridge.log(TAG + " dock addView container begin ty=" + windowParams.type
                    + " flags=0x" + Integer.toHexString(windowParams.flags)
                    + " cards=" + cardList.getChildCount());
            wm.addView(container, windowParams);
            XposedBridge.log(TAG + " dock addView container ok attached="
                    + container.isAttachedToWindow() + " size=" + container.getWidth()
                    + "x" + container.getHeight());
            sDockWinLeft = dockLeft;
            sDockWinTop = dockTopScreen;
            sDockContainer = container;
            sDockPanel = dock;
            sDockSide = side;
            sDockOriginActivity = sLastResumedActivity;
            sDockCloseDuringSystemGesture = false;
            sDockBackRestoreTaskId = -1;
            sDockShowing = true;
            // 坞窗活着期间每秒比对一次屏幕方向，横↔竖翻转就按新方向重建
            startOrientationWatch();

            // 布局量完之后按滚动区裁一次画面（首帧时 getHeight 还是 0，所以要 post）
            dock.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshPreviewCrop();
                }
            }, 80);

            // 右侧：从右往左滑出；左侧：从左往右滑出（官方 lively 时长/插值）
            dock.setTranslationX(side == 1 ? dockWidth : -dockWidth);
            dock.animate()
                    .translationX(0)
                    .setDuration(DOCK_ANIM_MS)
                    .setInterpolator(DOCK_INTERPOLATOR)
                    .start();

            XposedBridge.log(TAG + " showed float dock, side=" + side + ", apps=" + sDockApps.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " showFloatDock error: " + t.getMessage());
        }
    }

    /**
     * 按坞窗滚动区的底边，把每张卡片的画面裁到可见范围。
     *
     * SurfaceView 的 surface 不在 View 树里，ScrollView 裁不到它：卡片往下滚时画面会一路
     * 画到坞窗底部的「清除」按钮上（3820 上坞窗只有 2422 高，两张卡片加起来就超）。
     * 滚动区和布局变化时调一次，把超出滚动区底边的那一截裁掉。
     */
    private static void refreshPreviewCrop() {
        ScrollView sv = sDockScrollView;
        LinearLayout list = sDockCardList;
        if (sv == null || list == null) return;
        int[] sLoc = new int[2];
        sv.getLocationOnScreen(sLoc);
        int visibleBottom = sLoc[1] + sv.getHeight();
        for (int i = 0; i < list.getChildCount(); i++) {
            cropPreviewTree(list.getChildAt(i), visibleBottom);
        }
    }

    /**
     * 在卡片子树里递归找打了 {@link #PREVIEW_TAG} 的 SurfaceView 并裁剪。
     * SurfaceView 包在 {@code DockLive} 返回的 slot 里，比卡片低一层，所以要递归。
     */
    private static void cropPreviewTree(View root, int visibleBottom) {
        if (root instanceof SurfaceView && PREVIEW_TAG.equals(root.getTag())) {
            SurfaceView surfaceView = (SurfaceView) root;
            if (DockLive.isLiveSurfaceView(surfaceView)) {
                // 实时卡片照官方 MySurfaceView 的 adjustSurfaceControl：crop + alpha，
                // 按滚动区上下边界裁（SurfaceView 层裁掉，任务层作为子层跟着被裁）。
                DockLive.refreshCrop(surfaceView);
            } else {
                LivelyHook.cropSnapshot(surfaceView, visibleBottom);
            }
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                cropPreviewTree(g.getChildAt(i), visibleBottom);
            }
        }
    }

    /**
     * 坞窗滚动区在窗口坐标里的矩形 —— 官方 adjustSurfaceControl 里
     * {@code mRecyclerView.getBoundsOnScreen(...)} 的等价物（官方窗口全屏、x/y=0，
     * 所以屏幕坐标和窗口坐标一致；我们的坞窗是独立窗口，必须用窗口坐标）。
     */
    static boolean dockPreviewClipRect(Rect out) {
        ScrollView sv = sDockScrollView;
        if (sv == null || sv.getWidth() <= 0 || sv.getHeight() <= 0) return false;
        int[] loc = new int[2];
        try {
            sv.getLocationInWindow(loc);
        } catch (Throwable t) {
            return false;
        }
        out.set(loc[0], loc[1], loc[0] + sv.getWidth(), loc[1] + sv.getHeight());
        return true;
    }

    /**
     * 把所有卡片内容区的洞摆到面板背景上（洞用面板局部坐标）。
     *
     * 坞窗窗口在应用窗口之上，卡片区不挖洞真窗口就被底色糊住，所以洞是必须的；
     * 洞按卡片圆角挖（见 DockHoleDrawable.setHoleRadius），视觉上和坞窗底色是一体的。
     * 多卡片时每张在接管中的卡各一个洞；被滚出面板范围的洞会裁掉，避免洞跑到面板外。
     */
    private static void updateDockHoles() {
        DockHoleDrawable bg = sDockHoleBg;
        View panel = sDockPanel;
        if (bg == null || panel == null) return;
        List<View> anchors = CardZoom.anchors();
        if (anchors.isEmpty()) {
            bg.setHoles(null);
            return;
        }
        int[] a = new int[2];
        int[] p = new int[2];
        try {
            panel.getLocationOnScreen(p);
        } catch (Throwable t) {
            return;
        }
        Rect panelLocal = new Rect(0, 0, panel.getWidth(), panel.getHeight());
        List<Rect> holes = new ArrayList<>();
        for (View anchor : anchors) {
            int w = anchor.getWidth();
            int h = anchor.getHeight();
            if (w <= 0 || h <= 0) continue;
            try {
                anchor.getLocationOnScreen(a);
            } catch (Throwable t) {
                continue;
            }
            Rect hole = new Rect(a[0] - p[0], a[1] - p[1], a[0] - p[0] + w, a[1] - p[1] + h);
            if (!hole.intersect(panelLocal)) continue;
            holes.add(hole);
        }
        bg.setHoles(holes);
        if (sHoleLogCount < 8) {
            sHoleLogCount++;
            XposedBridge.log(TAG + " updateDockHoles cards=" + anchors.size()
                    + " holes=" + holes.size() + " " + holes);
        }
    }

    private static int sHoleLogCount = 0;

    /** 滚动停下后补钉一次（强制对齐）的句柄。 */
    private static Runnable sLiveSettleSync = null;
    private static View sLiveSettleAnchor = null;
    /** 上一次真正去改 WMS 几何的时间（比洞的刷新频率低得多）。 */
    private static long sLastLiveResizeAt = 0;

    /**
     * 拖动期间改几何的最小间隔。
     *
     * <p>3820 上 00:29:15 的 system_server 原生崩溃（SIGSEGV，android.ui 线程）就出在这条路上：
     * {@code CardZoom.pinGeometry → Task.resize → continueWindowLayout → performSurfacePlacement
     * → AppCompatLetterboxPolicy.start → AppCompatRoundedCorners.updateRoundedCornersIfNeeded
     * → SurfaceControl$Transaction.setCrop → libgui 空指针}。
     * 也就是每改一次几何都会同步跑一遍布局 + 圆角裁剪；现在那条圆角裁剪已被
     * {@link #hookAppCompatRoundedCornersGuard} 跳过，这里按 80ms 节拍追位置。</p>
     */
    private static final long LIVE_RESIZE_MIN_INTERVAL_MS = 80;

    /** 真实小窗跟随卡片：洞跟着每帧走，WMS 几何按 {@link #LIVE_RESIZE_MIN_INTERVAL_MS} 降频。 */
    private static void syncLiveCard(final String reason) {
        if (!CardZoom.isActive()) return;
        final List<View> anchors = CardZoom.anchors();
        if (anchors.isEmpty()) return;
        updateDockHoles();
        final View first = anchors.get(0);
        long now = android.os.SystemClock.uptimeMillis();
        if (now - sLastLiveResizeAt >= LIVE_RESIZE_MIN_INTERVAL_MS) {
            sLastLiveResizeAt = now;
            CardZoom.syncCard(reason);
        }
        // 滚动停下 200ms 后再强制钉一次：拖动过程中系统可能把小窗 bounds 回滚，
        // 而卡片矩形没变时 sync 会早退（lastRect 相等），画面就停在错位的地方。
        if (sLiveSettleAnchor != null && sLiveSettleSync != null) {
            sLiveSettleAnchor.removeCallbacks(sLiveSettleSync);
        }
        sLiveSettleAnchor = first;
        sLiveSettleSync = new Runnable() {
            @Override
            public void run() {
                sLiveSettleSync = null;
                if (!CardZoom.isActive()) return;
                updateDockHoles();
                sLastLiveResizeAt = android.os.SystemClock.uptimeMillis();
                CardZoom.syncCardForce("settle");
            }
        };
        first.postDelayed(sLiveSettleSync, 200);
    }

    /** 添加一个应用卡片到坞窗。 */
    /** {@code ActivityInfo.isFixedOrientationLandscape/Portrait} 的反射句柄（@hide，缓存一次）。 */
    private static Method sIsFixedLandscape;
    private static Method sIsFixedPortrait;

    private static boolean fixedOrientation(int orientation, boolean landscape) {
        try {
            Method m = landscape ? sIsFixedLandscape : sIsFixedPortrait;
            if (m == null) {
                Class<?> ai = Class.forName("android.content.pm.ActivityInfo");
                m = ai.getMethod(landscape ? "isFixedOrientationLandscape"
                        : "isFixedOrientationPortrait", int.class);
                if (landscape) {
                    sIsFixedLandscape = m;
                } else {
                    sIsFixedPortrait = m;
                }
            }
            Object r = m.invoke(null, orientation);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 判断某张卡片对应的任务是不是「横屏应用」。
     *
     * <p>口径完全照抄系统自己那份 {@code FloatHandleController}（5200 反编译 1300 行）：
     * <pre>
     *   float ratio = ActivityInfo.isFixedOrientationLandscape(info.appOrientation)
     *           ? 0.5625f : 1.7777778f;
     * </pre>
     * 0.5625f 就是 9:16。也就是说官方不看截图、也不看屏幕方向，只看
     * {@code FloatHandleInfo.appOrientation}（= {@code Task.getOrientation()}，
     * 即应用自己声明的 SCREEN_ORIENTATION_*）是不是"固定横屏"。
     *
     * <p>后两级只是兜底：未锁方向的应用（unspecified / fullSensor）再按小窗
     * {@code launchBounds} 的宽高判，最后才用快照宽高。
     */
    private boolean isLandscapeApp(int taskId, Object floatHandleInfo) {
        try {
            int appOrientation = XposedHelpers.getIntField(floatHandleInfo, "appOrientation");
            if (fixedOrientation(appOrientation, true)) {
                return true;
            }
            if (fixedOrientation(appOrientation, false)) {
                return false;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " appOrientation read failed: " + t.getMessage());
        }
        // 未锁方向：按小窗启动边界的宽高（官方 onBindViewHolder 也是拿 launchBounds 的 h/w 起的）
        try {
            Object fti = XposedHelpers.getObjectField(floatHandleInfo, "flexibleTaskInfo");
            if (fti != null) {
                Object lb = XposedHelpers.callMethod(fti, "getLaunchBounds");
                if (lb instanceof Rect) {
                    Rect r = (Rect) lb;
                    if (r.width() > 0 && r.height() > 0) {
                        return r.width() > r.height();
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (taskId >= 0) {
            try {
                Class<?> atmCls = Class.forName("android.app.ActivityTaskManager");
                Object atm = XposedHelpers.callStaticMethod(atmCls, "getService");
                if (atm != null) {
                    Object taskInfo = XposedHelpers.callMethod(atm, "getTaskInfo", taskId);
                    if (taskInfo != null) {
                        Object cfg = XposedHelpers.getObjectField(taskInfo, "configuration");
                        if (cfg != null) {
                            int o = XposedHelpers.getIntField(cfg, "orientation");
                            if (o == 2) return true;    // Configuration.ORIENTATION_LANDSCAPE
                            if (o == 1) return false;   // Configuration.ORIENTATION_PORTRAIT
                        }
                        Object bounds = XposedHelpers.getObjectField(taskInfo, "bounds");
                        if (bounds instanceof Rect) {
                            Rect r = (Rect) bounds;
                            if (r.width() > 0 && r.height() > 0) {
                                return r.width() > r.height();
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " orientation probe task=" + taskId + " failed: " + t.getMessage());
            }
        }
        return LivelyHook.snapshotIsLandscape(floatHandleInfo);
    }

    private void addAppCard(Context ctx, LinearLayout cardList, Object floatHandleInfo,
                            ClassLoader cl, int dockWidth, int dockHeight) {
        // 获取包名和任务信息（FloatHandleInfo 直接有 getPackageName/getUserId 和 taskId 字段）
        String pkgName = "";
        int userId = 0;
        int taskId = -1;
        int appOrientation = -1;
        try {
            pkgName = (String) XposedHelpers.callMethod(floatHandleInfo, "getPackageName");
            userId = (int) XposedHelpers.callMethod(floatHandleInfo, "getUserId");
            taskId = XposedHelpers.getIntField(floatHandleInfo, "taskId");
            appOrientation = XposedHelpers.getIntField(floatHandleInfo, "appOrientation");
        } catch (Throwable ignored) {
        }
        XposedBridge.log(TAG + " addAppCard pkg=" + pkgName + " taskId=" + taskId + " userId=" + userId);

        // 卡片容器（透明背景）
        final boolean night = isNightMode(ctx);
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dpToPx(ctx, 5);
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

        // 应用名称：与图标同高并垂直居中，关掉字体上下留白，避免文字视觉上偏
        TextView nameTv = new TextView(ctx);
        nameTv.setTextSize(14);
        nameTv.setTextColor(night ? 0xFFE6E6E6 : 0xFF000000);
        nameTv.setMaxLines(1);
        nameTv.setGravity(Gravity.CENTER_VERTICAL);
        nameTv.setIncludeFontPadding(false);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, iconSize, 1f);
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

        // 三点菜单按钮：加大触摸区并用标准点击回调（onTouch 容易被卡片点击/滚动吞掉）
        final TextView menuBtn = new TextView(ctx);
        menuBtn.setText("⋮");
        menuBtn.setTextSize(22);
        menuBtn.setTextColor(night ? 0xFFB9B9B9 : 0xFF333333);
        menuBtn.setGravity(Gravity.CENTER);
        menuBtn.setPadding(dpToPx(ctx, 8), 0, dpToPx(ctx, 8), 0);
        menuBtn.setMinWidth(dpToPx(ctx, 36));
        menuBtn.setMinHeight(dpToPx(ctx, 36));
        // 三个点字形在字体里重心偏下，整体上移 1.5dp 与图标/名称中线对齐
        menuBtn.setTranslationY(-1.5f * ctx.getResources().getDisplayMetrics().density);
        menuBtn.setClickable(true);
        topBar.addView(menuBtn);

        final String finalPkgName = pkgName;
        final int finalUserId = userId;
        final int finalTaskId = taskId;
        final Object finalInfo = floatHandleInfo;
        final ClassLoader finalCl = cl;

        menuBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 菜单已打开时再点三点 = 关闭（toggle）
                if (sAppMenuView != null) {
                    XposedBridge.log(TAG + " menuBtn onClick, menu already open -> close");
                    removeAppMenu();
                    return;
                }
                XposedBridge.log(TAG + " menuBtn onClick, open menu");
                showAppMenu(v.getContext(), menuBtn, finalPkgName, finalUserId, finalTaskId, finalInfo, finalCl);
            }
        });

        // 预览区：全部卡片统一用 SurfaceView 承接任务实时图层（把 Task 的 SurfaceControl
        // reparent 到 SurfaceView 下面，对齐 5200 官方 LivelyFloatHandleView）。
        // 不再挖洞、不再把真实 zoom 窗口摆在坞窗后面追位置。
        View preview = DockLive.createSlot(ctx, floatHandleInfo, taskId, pkgName, cl);
        XposedBridge.log(TAG + " addAppCard preview slot taskId=" + taskId
                + " live=" + (taskId > 0));
        // 尺寸规则（横竖屏同一套）：宽 = dockWidth - 44dp（标题栏「图标左缘 → 三点右缘」那段），
        // 高 = 宽 × 快照真实比例，再受坞窗可用高度约束。
        int previewWidth = Math.max(dpToPx(ctx, 120), dockWidth - dpToPx(ctx, 44));
        int maxPreviewHeight = Math.max(dpToPx(ctx, 120), (int) (dockHeight * 0.85f));
        // 固定比例：竖屏 16:9、横屏 13:9（与系统自由小窗两个默认比例一致）；
        // 横屏应用单独走 9:16，用一个矮框把横向画面完整铺开。
        // 关掉 FIXED_PREVIEW_ASPECT 就退回按快照真实比例的老行为。
        boolean appLandscape = isLandscapeApp(taskId, floatHandleInfo);
        float aspect;
        if (FIXED_PREVIEW_ASPECT) {
            if (appLandscape) {
                aspect = PREVIEW_ASPECT_LANDSCAPE_APP;
            } else {
                aspect = sDockLandscape ? PREVIEW_ASPECT_LANDSCAPE : PREVIEW_ASPECT_PORTRAIT;
            }
        } else {
            aspect = LivelyHook.snapshotAspect(floatHandleInfo);
        }
        if (aspect <= 0f) aspect = PREVIEW_ASPECT_FALLBACK;
        int previewHeight = (int) (previewWidth * aspect);
        if (previewHeight > maxPreviewHeight) {
            previewHeight = maxPreviewHeight;
            previewWidth = (int) (previewHeight / aspect);
            XposedBridge.log(TAG + " preview height capped: " + previewWidth + "x" + previewHeight);
        }
        XposedBridge.log(TAG + " addAppCard live slot width=" + previewWidth + " height=" + previewHeight
                + " aspect=" + aspect + " appLandscape=" + appLandscape
                + " appOrientation=" + appOrientation + " dock=" + dockWidth + "x" + dockHeight);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                previewWidth, previewHeight);
        // 水平居中；左右不留 margin，画面边缘就落在图标/三点那两条竖线上。
        previewParams.gravity = Gravity.CENTER_HORIZONTAL;
        previewParams.bottomMargin = dpToPx(ctx, 10);
        // 打个 tag：滚动时要按它把画面裁到坞窗滚动区的可见范围（见 refreshPreviewCrop）。
        preview.setTag(PREVIEW_TAG);
        card.addView(preview, previewParams);

        View.OnClickListener restoreListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                XposedBridge.log(TAG + " card click taskId=" + finalTaskId
                        + " from=" + v.getClass().getSimpleName());
                // 实时画面里任务被挂在卡片层上，先还原回主屏层级，恢复逻辑才能接管
                try {
                    DockLive.prepareRestore(finalTaskId);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " prepareRestore error: " + t.getMessage());
                }
                restoreFloatInfo(finalInfo, finalCl);
            }
        };
        // 卡片（含顶栏图标）点击恢复
        card.setOnClickListener(restoreListener);
        // 画面区域（SurfaceView）点击恢复：任务层挂到 SurfaceView 后自身没有输入通道，
        // 点击由坞窗窗口里的 SurfaceView 接收，这里把它接到同一个恢复流程。
        DockLive.setRestoreHandler(taskId, new Runnable() {
            @Override
            public void run() {
                XposedBridge.log(TAG + " preview click taskId=" + finalTaskId);
                try {
                    DockLive.prepareRestore(finalTaskId);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " preview prepareRestore error: " + t.getMessage());
                }
                restoreFloatInfo(finalInfo, finalCl);
            }
        });
        // 这个应用可能早就跑着（坞窗打开前就在），挂载时不会重新 addWindow：
        // 把已在跑的窗口补上「不抢焦点、不接手指」，否则卡片画面里能直接操作应用。
        enforcePreviewUnfocusable(pkgName);
    }

    /**
     * 点击坞窗卡片：按 ColorOS 官方入口恢复成贴边前的形态。
     *
     * 自由小窗贴边就回到自由小窗，迷你浮窗贴边就回到迷你浮窗——形态由 FloatHandleInfo
     * 里记录的 zoomState/type 决定，官方
     * {@code FloatHandleView.startFlexibleFreeformFromHandleView(info, reason)} 会自己把
     * flexibleTaskInfo 的 bounds/scale/ratio 算好。
     *
     * 旧实现手动调 startActivityByFloatInfo，并强行把 state 改成 2、type 改成 3，
     * 于是所有任务一律恢复成迷你浮窗（自由小窗贴边后回不到小窗），这就是本 bug 的根源。
     * 该修复对齐 3.2 的 restoreFloatInfo 逻辑。
     */
    private void restoreFloatInfo(final Object floatHandleInfo, final ClassLoader cl) {
        if (floatHandleInfo == null || cl == null) return;
        final Object handle = sCurrentFloatHandleView;
        int resolved = -1;
        try {
            resolved = XposedHelpers.getIntField(floatHandleInfo, "taskId");
        } catch (Throwable ignored) {
        }
        final int taskId = resolved;
        XposedBridge.log(TAG + " restoreFloatInfo taskId=" + taskId);
        try {
            if (taskId > 0) MirageWindowHook.clearHangUp(taskId);
        } catch (Throwable ignored) {
        }
        // 恢复的这一刻先把「剩下的那个应用」记进图标记忆：系统把被恢复项的图标摘掉后
        // 如果列表被摘空、没有触发重绘，补画就用它，不会画出刚恢复的那个应用。
        try {
            rememberOtherHandleApp(cl, taskId);
        } catch (Throwable ignored) {
        }

        // 先收起坞窗、恢复悬浮球；官方恢复成功后悬浮球会由系统重新隐藏。
        removeFloatDock();

        sRestoring = true;
        final Runnable restoreAction = new Runnable() {
            @Override
            public void run() {
                doRestoreOfficial(floatHandleInfo, cl, handle, taskId);
            }
        };
        Handler target = null;
        if (handle instanceof View) {
            target = ((View) handle).getHandler();
        }
        if (target == null) target = sHandleHandler;
        if (target != null) {
            target.postDelayed(restoreAction, DOCK_ANIM_MS + 50L);
        } else {
            restoreAction.run();
        }
    }

    /** 官方恢复：走 ColorOS 自己的“点悬浮球恢复”入口，保留小窗/迷你两种形态。 */
    private void doRestoreOfficial(Object floatHandleInfo, ClassLoader cl, Object handle, int taskId) {
        // 坞窗收起时为了不重建条目，压了 5 秒「保留悬浮球」；恢复时必须撤掉，
        // 否则官方恢复里删悬浮球条目那一步会被我们的 shouldKeepFloat 压住，
        // 结果窗口恢复了、球还挂在屏边（用户反馈的“悬浮球没消失”）。
        MirageWindowHook.clearKeepFloatEntry(taskId);
        boolean restored = false;
        if (handle != null) {
            try {
                XposedHelpers.callMethod(handle, "startFlexibleFreeformFromHandleView",
                        floatHandleInfo, "freewindow_restore");
                restored = true;
                XposedBridge.log(TAG + " restore via official handle path taskId=" + taskId);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " official restore failed, fallback: " + t.getMessage());
            }
        }
        if (!restored) {
            try {
                Object fhc = XposedHelpers.callStaticMethod(
                        XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl), "getInstance");
                XposedHelpers.callMethod(fhc, "startActivityByFloatInfo", floatHandleInfo, "lively_panel");
                XposedBridge.log(TAG + " restoreFloatInfo(fallback) done taskId=" + taskId);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " restoreFloatInfo fallback failed: " + t.getMessage());
            }
        }
        scheduleHandleCleanup(taskId, cl);
        releaseRestoringGuard(handle);
    }

    /**
     * 恢复之后确认悬浮球真的没了。
     *
     * <p>官方入口删条目可能是异步的（也可能因为恢复时用的还是坞窗那套状态而根本没删），
     * 这里等一拍再看一眼：列表里还有就按官方同款调用删掉。
     * 不补这一步的表现就是「小窗和悬浮球同时挂在屏幕上」。</p>
     */
    private void scheduleHandleCleanup(final int taskId, final ClassLoader cl) {
        if (taskId <= 0 || cl == null) return;
        Runnable cleanup = new Runnable() {
            @Override
            public void run() {
                try {
                    Object fhc = XposedHelpers.callStaticMethod(
                            XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl),
                            "getInstance");
                    Object inList = XposedHelpers.callMethod(fhc, "isInFloatingList", taskId);
                    if (Boolean.FALSE.equals(inList)) {
                        XposedBridge.log(TAG + " float entry already gone taskId=" + taskId);
                        return;
                    }
                    XposedBridge.log(TAG + " float entry still there, removing taskId=" + taskId);
                    XposedHelpers.callMethod(fhc, "removeFloatHandle", taskId, null, true, 5);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " handle cleanup failed taskId=" + taskId + ": " + t.getMessage());
                }
            }
        };
        Handler handler = sHandleHandler;
        if (handler != null) {
            handler.postDelayed(cleanup, 350);
        } else {
            cleanup.run();
        }
    }

    /**
     * 恢复期间别再拦一次悬浮球点击（否则会又弹一次坞窗）。
     * 1.2 秒后解除；拿不到悬浮球线程的 Handler 时立即解除，保证不会永久失去坞窗。
     */
    private void releaseRestoringGuard(Object handle) {
        Runnable reset = new Runnable() {
            @Override
            public void run() {
                sRestoring = false;
            }
        };
        Handler target = null;
        if (handle instanceof View) {
            target = ((View) handle).getHandler();
        }
        if (target == null) target = sHandleHandler;
        if (target != null) {
            target.postDelayed(reset, 1200L);
        } else {
            reset.run();
        }
    }

    /**
     * 菜单视图：直接贴在坞窗容器里，不再用 PopupWindow。
     *
     * 坞窗是 system_server 里 addView 的 FLAG_NOT_FOCUSABLE 窗口，PopupWindow 会再开一个子窗口，
     * 父窗口不可聚焦时子窗口拿不到 token/焦点，showAsDropDown 静默失败，表现就是「点三点没反应」。
     */
    private static View sAppMenuView = null;
    /** 画面 SurfaceView 的 tag：滚动时靠它找出所有卡片里的画面做裁剪。 */
    private static final String PREVIEW_TAG = "freewindow.snapshotPreview";
    /** 坞窗的滚动容器与卡片列表：滚动时按可视区裁 SurfaceView（surface 不跟着 ScrollView 裁剪）。 */
    private static ScrollView sDockScrollView = null;
    private static LinearLayout sDockCardList = null;
    /** 坞窗窗口的屏幕左上角：SurfaceView 的 layer 坐标是相对窗口的，裁画面时要换算。 */
    private static int sDockWinLeft = 0;
    private static int sDockWinTop = 0;

    /** 显示单个应用的弹出菜单（取消运行、取消静音、关闭）。 */
    private void showAppMenu(Context ctx, View anchor, final String pkgName, final int userId,
                             final int taskId, final Object floatHandleInfo, final ClassLoader cl) {
        XposedBridge.log(TAG + " showAppMenu called, pkg=" + pkgName);
        removeAppMenu();
        if (!(sDockContainer instanceof ViewGroup)) {
            XposedBridge.log(TAG + " showAppMenu: dock container missing");
            return;
        }
        final ViewGroup container = sDockContainer;
        final boolean night = isNightMode(ctx);

        final int menuWidth = dpToPx(ctx, 130);
        final int itemHeight = dpToPx(ctx, 44);

        // 菜单容器
        LinearLayout menuContainer = new LinearLayout(ctx);
        menuContainer.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable menuBg = new GradientDrawable();
        menuBg.setColor(night ? 0xFF2B2B2B : 0xFFFFFFFF);
        menuBg.setCornerRadius(dpToPx(ctx, 12));
        menuContainer.setBackground(menuBg);
        menuContainer.setElevation(dpToPx(ctx, 8));

        // 菜单项（未静音显示「启动静音」，已静音显示「取消静音」）
        boolean muted = sMutedApps.containsKey(muteKey(pkgName, userId));
        String[] items = {"取消运行", muted ? "取消静音" : "启动静音", "关闭"};
        for (int i = 0; i < items.length; i++) {
            final String item = items[i];
            // 分隔线（第一项不加）
            if (i > 0) {
                View divider = new View(ctx);
                divider.setBackgroundColor(night ? 0x1FFFFFFF : 0x14000000);
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                        menuWidth - dpToPx(ctx, 28), 1);
                dividerParams.leftMargin = dpToPx(ctx, 14);
                menuContainer.addView(divider, dividerParams);
            }
            TextView itemTv = new TextView(ctx);
            itemTv.setText(item);
            itemTv.setTextSize(14);
            itemTv.setTextColor(night ? 0xFFE6E6E6 : 0xFF222222);
            itemTv.setGravity(Gravity.CENTER_VERTICAL);
            itemTv.setPadding(dpToPx(ctx, 16), 0, dpToPx(ctx, 16), 0);
            itemTv.setClickable(true);
            LinearLayout.LayoutParams itemParams = new LinearLayout.LayoutParams(menuWidth, itemHeight);
            itemTv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    XposedBridge.log(TAG + " menu item clicked: " + item);
                    removeAppMenu();
                    if ("取消运行".equals(item)) {
                        removeSingleApp(pkgName, userId, taskId, floatHandleInfo, cl);
                        removeFloatDock();
                    } else if ("启动静音".equals(item) || "取消静音".equals(item)) {
                        toggleMute(ctx, pkgName, userId, cl);
                    }
                }
            });
            menuContainer.addView(itemTv, itemParams);
        }

        // 定位：贴在三点点按钮正下方居中，左右不超出屏幕（屏幕坐标，菜单是独立子窗口）
        int[] anchorLoc = new int[2];
        anchor.getLocationOnScreen(anchorLoc);
        int screenWidth = ctx.getResources().getDisplayMetrics().widthPixels;
        int left = anchorLoc[0] + anchor.getWidth() / 2 - menuWidth / 2;
        left = Math.max(dpToPx(ctx, 8), Math.min(left, screenWidth - menuWidth - dpToPx(ctx, 8)));
        int top = anchorLoc[1] + anchor.getHeight() + dpToPx(ctx, 4);

        // 关键：菜单必须以「坞窗窗口的子窗口」出现。
        // 放在坞窗窗口里的话，SurfaceView 是独立图层且 setZOrderOnTop(true)，
        // 会永远压在窗口内所有 View 之上，菜单怎么调都会被画面盖住。
        // 子窗口是独立 window，层在父窗口之上，就能反过来盖住画面。
        boolean added = false;
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams wlp = new WindowManager.LayoutParams(
                    menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, // 子窗口
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            wlp.token = anchor.getWindowToken();
            wlp.gravity = Gravity.TOP | Gravity.START;
            wlp.x = left;
            wlp.y = top;
            wlp.setTitle("FreeWindowDockMenu");
            wm.addView(menuContainer, wlp);
            sAppMenuWm = wm;
            added = true;
            XposedBridge.log(TAG + " showAppMenu as sub-window at " + left + "," + top);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " showAppMenu sub-window failed: " + t);
        }

        if (!added) {
            // 兜底：挂在坞窗容器里（会被实时画面盖住，但不至于没有菜单）
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = left;
            lp.topMargin = top;
            container.addView(menuContainer, lp);
            XposedBridge.log(TAG + " showAppMenu fallback in-container at " + left + "," + top);
        }
        sAppMenuView = menuContainer;
    }

    /** 移除菜单。 */
    private void removeAppMenu() {
        View menu = sAppMenuView;
        sAppMenuView = null;
        if (menu != null) {
            if (sAppMenuWm != null) {
                // 子窗口方式：从 WindowManager 摘掉
                try {
                    sAppMenuWm.removeViewImmediate(menu);
                } catch (Throwable ignored) {
                }
                sAppMenuWm = null;
            } else if (menu.getParent() instanceof ViewGroup) {
                try {
                    ((ViewGroup) menu.getParent()).removeView(menu);
                } catch (Throwable ignored) {
                }
            }
        }
        sAppMenuWm = null;
    }

    // ==================== 单应用静音（参考 FlymeFreeform 的 ColorOsWindowGestures） ====================

    /**
     * 已被坞窗菜单静音的应用，key = pkg + "/" + userId，value = 当时算出来的 uid。
     * 内存态：system_server 重启即清空，不落盘（静音只是临时手段，与 ColorOS 单应用音量同一通道）。
     * 值是 uid 是为了能在进程全部退出后按 uid 精确恢复音量。
     */
    private static final java.util.Map<String, Integer> sMutedApps =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String muteKey(String pkgName, int userId) {
        return pkgName + "/" + userId;
    }

    /** uid -> userId，等同 UserHandle.getUserId（@hide，编译期 android.jar 里没有，只能自己算）。 */
    private static int userIdOf(int uid) {
        return uid / 100000;
    }

    /** 切换单个应用的播放音量（0 = 静音，1 = 恢复），只影响该 uid，不动媒体流整体音量。 */
    private void toggleMute(Context ctx, String pkgName, int userId, ClassLoader cl) {
        String key = muteKey(pkgName, userId);
        boolean muted = sMutedApps.containsKey(key);
        int uid = -1;
        try {
            Object appInfo = XposedHelpers.callMethod(ctx.getPackageManager(),
                    "getApplicationInfoAsUser", pkgName, 0, userId);
            uid = XposedHelpers.getIntField(appInfo, "uid");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " mute: uid lookup failed for " + pkgName + ": " + t.getMessage());
        }
        // 已静音时优先用当时记下的 uid：应用被重装或查询失败也能正常取消，不会留下永久静音
        if (muted) {
            Integer stored = sMutedApps.get(key);
            if (stored != null && stored > 0) uid = stored;
        }
        if (uid <= 0) {
            XposedBridge.log(TAG + " mute: give up, unknown uid for " + pkgName);
            return;
        }

        if (!setVolumeForUid(muted ? 1f : 0f, uid, pkgName, cl)) {
            XposedBridge.log(TAG + " mute: setVolumeForUid unavailable, skip pkg=" + pkgName);
            return;
        }
        if (muted) sMutedApps.remove(key); else sMutedApps.put(key, uid);
        XposedBridge.log(TAG + (muted ? " unmute " : " mute ") + pkgName + " uid=" + uid);
    }

    /**
     * 走 ColorOS 自己的单应用音量通道：
     * AudioService.mPlaybackMonitor.mPamExt.setVolumeForUid(volume, uid, pkg, true)。
     * mPamExt 不存在时退回 monitor 本身，都没有就返回 false。
     */
    private boolean setVolumeForUid(float volume, int uid, String pkgName, ClassLoader cl) {
        try {
            Object audio = resolveAudioService(cl);
            if (audio == null) {
                XposedBridge.log(TAG + " mute: AudioService not found");
                return false;
            }
            Object monitor = readFieldDeep(audio, "mPlaybackMonitor");
            if (monitor == null) {
                XposedBridge.log(TAG + " mute: mPlaybackMonitor missing on "
                        + audio.getClass().getName());
                return false;
            }
            Object ext = readFieldDeep(monitor, "mPamExt");
            if (invokeSetVolumeForUid(ext, volume, uid, pkgName)) return true;
            if (ext != null) {
                XposedBridge.log(TAG + " mute: mPamExt has no setVolumeForUid, try monitor");
            }
            return invokeSetVolumeForUid(monitor, volume, uid, pkgName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " mute: setVolumeForUid error " + t.getMessage());
            return false;
        }
    }

    /**
     * 拿 AudioService 实例。
     *
     * Android 13 起 SystemServer 往 LocalServices 注册的 key 是 AudioServiceInternal
     * （实现类仍是 AudioService），所以只查 AudioService.class 会拿到 null —— 这就是
     * 「静音没效果、日志 mute: AudioService not found」的原因。
     * 这里按 AudioService → AudioServiceInternal → OplusAudioService 的顺序逐个尝试，
     * 另外保留一份构造期 hook 抓到的实例做兜底。
     */
    private static Object sAudioService = null;

    /** 亮度覆盖双保险用：DisplayPowerController 实例，字段级压 -1 时要用。 */
    private static Object sDisplayPowerController = null;

    /** 预览卡片不抢焦点用：system_server 的 ClassLoader 与 WMS 实例缓存。 */
    private static ClassLoader sCl = null;
    private static Object sWms = null;

    /**
     * 卡片只是预览画面、不接受任何操作，所以预览应用的窗口不该抢全局焦点：
     * 给它加 FLAG_NOT_FOCUSABLE。这里处理「已经在跑的窗口」（坞窗打开前任务就已存在，
     * 挂载时不会重新 addWindow，只能直接改 WindowState.mAttrs 再让 WMS 重算焦点）。
     */
    static void enforcePreviewUnfocusable(String pkg) {
        if (pkg == null || sCl == null) return;
        try {
            if (sWms == null) {
                Class<?> atmiCls = XposedHelpers.findClass(
                        "com.android.server.wm.ActivityTaskManagerInternal", sCl);
                Class<?> lsCls = XposedHelpers.findClass("com.android.server.LocalServices", sCl);
                Object atms = XposedHelpers.callStaticMethod(lsCls, "getService", atmiCls);
                sWms = XposedHelpers.getObjectField(atms, "mWindowManager");
                XposedBridge.log(TAG + "-focus resolved WindowManagerService");
            }
            java.util.Map<?, ?> map = (java.util.Map<?, ?>) XposedHelpers.getObjectField(sWms, "mWindowMap");
            int changed = 0;
            for (Object v : map.values()) {
                WindowManager.LayoutParams lp =
                        (WindowManager.LayoutParams) XposedHelpers.getObjectField(v, "mAttrs");
                if (lp == null || lp.packageName == null) continue;
                if (!pkg.equals(lp.packageName.toString())) continue;
                int want = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                if ((lp.flags & want) == want) continue;
                // 已经跑着的窗口：只改 flags 不够，输入窗口句柄也得刷一次，
                // 否则 WMS 侧仍是老的「可触摸」状态，手指继续漏进应用。
                lp.flags |= want;
                try {
                    XposedHelpers.callMethod(v, "updateInputWindow");
                } catch (Throwable ignored) {
                }
                changed++;
            }
            XposedBridge.log(TAG + "-focus preview locked pkg=" + pkg + " windows=" + changed);
            if (changed > 0) {
                try {
                    XposedHelpers.callMethod(sWms, "updateFocusedWindowLocked", 0, true);
                } catch (Throwable ignored) {
                    // 老版本签名不同就跳过，下一次 relayout 会自己重算焦点
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "-focus unfocusable failed: " + t.getMessage());
        }
    }

    private Object resolveAudioService(ClassLoader cl) {
        if (sAudioService != null) return sAudioService;
        String[] keys = {
                "com.android.server.audio.AudioService",
                "com.android.server.audio.AudioServiceInternal",
                "com.android.server.audio.OplusAudioService",
                "com.oplus.server.audio.OplusAudioService",
        };
        try {
            Class<?> localServices = XposedHelpers.findClass("com.android.server.LocalServices", cl);
            for (String key : keys) {
                try {
                    Class<?> c = XposedHelpers.findClass(key, cl);
                    Object svc = XposedHelpers.callStaticMethod(localServices, "getService", c);
                    if (svc != null) {
                        sAudioService = svc;
                        XposedBridge.log(TAG + " mute: AudioService via " + key
                                + " -> " + svc.getClass().getName());
                        return svc;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " mute: LocalServices lookup error " + t.getMessage());
        }
        return null;
    }

    /** 沿父类链找字段（AudioService 的字段可能声明在基类）。 */
    private static Object readFieldDeep(Object obj, String name) {
        Class<?> c = obj == null ? null : obj.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 按名字 + 4 个参数找 setVolumeForUid 并调用。
     * 参数顺序在不同 ROM 上可能是 (float,int,String,boolean) 或 (int,float,String,boolean)，
     * 所以按声明类型逐个填，而不是写死顺序。
     */
    private static boolean invokeSetVolumeForUid(Object target, float volume, int uid, String pkgName) {
        if (target == null) return false;
        Class<?> c = target.getClass();
        while (c != null) {
            java.lang.reflect.Method[] methods = c.getDeclaredMethods();
            for (java.lang.reflect.Method m : methods) {
                if (!"setVolumeForUid".equals(m.getName()) || m.getParameterCount() != 4) continue;
                Class<?>[] ps = m.getParameterTypes();
                Object[] args = new Object[4];
                int intCount = 0;
                boolean ok = true;
                for (int i = 0; i < 4; i++) {
                    Class<?> p = ps[i];
                    if (p == float.class || p == Float.class) {
                        args[i] = volume;
                    } else if (p == int.class || p == Integer.class) {
                        intCount++;
                        args[i] = intCount == 1 ? uid : 0;
                    } else if (p == String.class) {
                        args[i] = pkgName;
                    } else if (p == boolean.class || p == Boolean.class) {
                        args[i] = Boolean.TRUE;
                    } else {
                        ok = false;
                        break;
                    }
                }
                if (!ok) continue;
                try {
                    m.setAccessible(true);
                    m.invoke(target, args);
                    return true;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " mute: invoke " + m.getName() + " failed: " + t.getMessage());
                }
            }
            c = c.getSuperclass();
        }
        return false;
    }

    // ==================== 静音自动恢复（应用完全退出即取消静音） ====================

    private static android.os.Handler sServerHandler = null;
    /** hook 期间顺手记下的 AMS 实例，用它读进程表判断「应用还有没有活着」。 */
    private static Object sAmsInstance = null;

    /**
     * 静音是挂在 uid 上的，应用退到后台、甚至被划掉之后系统不一定帮你清。
     * 所以接管 AMS 的进程清理回调：某个包的进程被清掉后，延迟一点点再看这个 uid
     * 还有没有别的进程（多进程应用要等到全部结束）；一个都不剩就恢复 1 倍音量。
     */
    private void hookMuteAutoRestore(ClassLoader cl) {
        Class<?> amsCls;
        try {
            amsCls = XposedHelpers.findClass("com.android.server.am.ActivityManagerService", cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " mute restore: AMS not found: " + t.getMessage());
            return;
        }
        String[] names = {"cleanUpApplicationRecordLocked", "handleAppDiedLocked"};
        boolean hooked = false;
        for (String name : names) {
            try {
                XposedBridge.hookAllMethods(amsCls, name, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (sAmsInstance == null && param.thisObject != null) {
                            sAmsInstance = param.thisObject;
                        }
                        if (param.args == null || param.args.length == 0) return;
                        scheduleMuteRestore(param.args[0], cl);
                    }
                });
                hooked = true;
                XposedBridge.log(TAG + " mute restore: hooked " + name);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " mute restore: hook " + name + " failed: " + t.getMessage());
            }
        }
        if (!hooked) {
            XposedBridge.log(TAG + " mute restore: no AMS hook installed, keep mute as is");
        }
    }

    /** 进程清理回调里先记下 uid/包名，延迟 1 秒再判断（进程表还没同步完会误判）。 */
    private void scheduleMuteRestore(Object app, final ClassLoader cl) {
        if (app == null || sMutedApps.isEmpty()) return;
        final int uid;
        final String pkgName;
        try {
            Object info = readFieldDeep(app, "info");
            if (info == null) return;
            uid = XposedHelpers.getIntField(info, "uid");
            Object name = XposedHelpers.getObjectField(info, "packageName");
            pkgName = name instanceof String ? (String) name : null;
        } catch (Throwable t) {
            return;
        }
        if (uid <= 0 || pkgName == null) return;
        final String key = muteKey(pkgName, userIdOf(uid));
        if (!sMutedApps.containsKey(key)) return;
        try {
            if (sServerHandler == null) {
                sServerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            }
            sServerHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    restoreMuteIfAppGone(key, pkgName, uid, cl);
                }
            }, 1000L);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " mute restore: post failed " + t.getMessage());
        }
    }

    /** 该 uid 一个进程都不剩时才恢复音量；还有后台进程就继续静音。 */
    private void restoreMuteIfAppGone(String key, String pkgName, int uid, ClassLoader cl) {
        try {
            if (!sMutedApps.containsKey(key)) return;
            if (uidHasProcess(uid)) {
                XposedBridge.log(TAG + " mute restore: " + pkgName + " still has process, keep muted");
                return;
            }
            if (setVolumeForUid(1f, uid, pkgName, cl)) {
                sMutedApps.remove(key);
                XposedBridge.log(TAG + " mute restore: " + pkgName + " uid=" + uid
                        + " fully exited -> volume restored");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " mute restore: error " + t.getMessage());
        }
    }

    /**
     * uid 是否还有活着的进程。
     * 先读 AMS 自己的进程表（system_server 里拿到的是全量），读不到再退到公开 API，
     * 两边都判断不出来时返回 true —— 宁可继续静音，也不要把用户主动开的静音误关掉。
     */
    private boolean uidHasProcess(int uid) {
        try {
            Object ams = sAmsInstance;
            if (ams != null) {
                Object processList = readFieldDeep(ams, "mProcessList");
                if (processList != null) {
                    Object procs = null;
                    try {
                        procs = XposedHelpers.callMethod(processList, "getProcesses");
                    } catch (Throwable ignored) {
                        // 某些 ROM 没有这个方法，下面退到 mPidsSelfLocked
                    }
                    if (procs instanceof java.util.List) {
                        for (Object p : (java.util.List<?>) procs) {
                            try {
                                if (XposedHelpers.getIntField(p, "uid") == uid) return true;
                            } catch (Throwable ignored) {
                                // 单个 ProcessRecord 读不到就跳过
                            }
                        }
                        // 列表读到了且里面没有这个 uid -> 明确已退出
                        return false;
                    }
                    Object pids = readFieldDeep(processList, "mPidsSelfLocked");
                    if (pids instanceof java.util.Map) {
                        for (Object p : ((java.util.Map<?, ?>) pids).values()) {
                            try {
                                if (XposedHelpers.getIntField(p, "uid") == uid) return true;
                            } catch (Throwable ignored) {
                            }
                        }
                        return false;
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " mute restore: process table read failed " + t.getMessage());
        }
        // 兜底：公开 API
        try {
            Object at = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", null),
                    "currentActivityThread");
            Object sysCtx = at == null ? null : XposedHelpers.callMethod(at, "getSystemContext");
            android.app.ActivityManager am = sysCtx instanceof Context
                    ? (android.app.ActivityManager) ((Context) sysCtx)
                    .getSystemService(Context.ACTIVITY_SERVICE)
                    : null;
            if (am != null) {
                java.util.List<android.app.ActivityManager.RunningAppProcessInfo> list =
                        am.getRunningAppProcesses();
                if (list != null) {
                    for (android.app.ActivityManager.RunningAppProcessInfo pi : list) {
                        if (pi.uid == uid) return true;
                    }
                    return false;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " mute restore: running processes fallback failed " + t.getMessage());
        }
        XposedBridge.log(TAG + " mute restore: cannot tell whether uid " + uid + " alive, skip");
        return true;
    }

    /** 清除单个应用：用官方 removeFloatHandle 移除悬浮球 + 杀进程。 */
    private void removeSingleApp(String pkgName, int userId, int taskId, Object floatHandleInfo, ClassLoader cl) {
        try {
            // 挂机中的游戏要先清掉挂机态：否则 MirageWindowHook 的 keep-float 拦截会把这次
            // removeFloatHandle 吃掉（isHangUp 还是 true），悬浮球删不掉、进程要再点一次才退出。
            if (taskId > 0) {
                try {
                    MirageWindowHook.clearHangUp(taskId);
                } catch (Throwable ignored) {
                }
                // 再撤销「保留窗口」并打上用户清除标记：enterCard/leaveCard 的 keepFloatEntry
                // 窗口同样会吃掉这次官方 removeFloatHandle，导致清除按钮删不掉条目；
                // 标记后 shouldKeepFloat 对它放行，CardZoom 收起时也不会把条目补回。
                MirageWindowHook.markUserRemove(taskId);
            }
            // 先把卡片上的实时图层还原回隐藏态，再走官方移除，避免小窗闪一下
            DockLive.releaseTask(taskId);
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

    /** 移除坞窗：右侧从左往右滑入收回，左侧从右往左滑入收回，结束后再显示悬浮球。 */
    /**
     * 坞窗开着时屏幕方向翻转：拆掉按旧方向建好的坞窗，按新方向重建一次。
     *
     * 这里直接 removeViewImmediate，不走滑出动画——旋转过程中两套布局叠在一起会闪。
     * 重建延后 350ms，等系统把新方向的配置和窗口参数发下来，否则会拿到旧的
     * screenWidth/screenHeight，建出来还是旧方向的大小。
     */
    private void onDockOrientationChanged(boolean nowLandscape) {
        if (!sDockShowing || nowLandscape == sDockLandscape) return;
        final Object fhv = sCurrentFloatHandleView;
        final Object info = sDockInfo;
        final ClassLoader cl = sClassLoader;
        if (fhv == null || info == null || cl == null) return;
        XposedBridge.log(TAG + " dock orientation changed, rebuild landscape=" + nowLandscape);

        // 缓存的预览图是旋转前那个方向的画面，不清掉重建出来还是旧比例
        LivelyHook.invalidateSnapshot(info);

        removeAppMenu();
        FrameLayout container = sDockContainer;
        sDockContainer = null;
        sDockPanel = null;
        sDockScrollView = null;
        sDockCardList = null;
        sDockShowing = false;
        sDockOriginActivity = null;
        sDockClosing = false;
        sDockCloseDuringSystemGesture = false;
        if (container != null) {
            try {
                WindowManager wm = (WindowManager) container.getContext()
                        .getSystemService(Context.WINDOW_SERVICE);
                wm.removeViewImmediate(container);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " remove dock on rotate failed: " + t.getMessage());
            }
        }
        // 重建时会重新加遮罩，这里先把旧的撤掉，避免两片全屏遮罩叠在一起。
        removeDockMask();

        // 重建必须回到创建坞窗视图的那个线程（android.ui）：跨线程 addView/removeView 会
        // 抛 CalledFromWrongThreadException 并让 system_server 重启。拿不到就不重建。
        if (sHandleHandler == null) {
            XposedBridge.log(TAG + " rebuild skipped: ui handler missing");
            return;
        }
        sHandleHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    showFloatDock(fhv, info, cl);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " rebuild dock failed: " + t.getMessage());
                }
            }
        }, 350);
    }

    /** 坞窗存活期间的方向轮询：每秒比对一次屏幕宽高，横↔竖翻转就重建坞窗。 */
    private void startOrientationWatch() {
        if (sOrientationWatch) return;
        // 必须在创建坞窗视图的那个线程（android.ui）上轮询：重建过程要 removeView / addView，
        // 换到 main 线程去碰这些 View 会触发 CalledFromWrongThreadException，直接把 system_server 打死。
        if (sHandleHandler == null) {
            XposedBridge.log(TAG + " skip orientation watch: ui handler missing");
            return;
        }
        sOrientationWatch = true;
        sHandleHandler.postDelayed(orientationWatcher, ORIENTATION_CHECK_MS);
    }

    private final Runnable orientationWatcher = new Runnable() {
        @Override
        public void run() {
            if (!sDockShowing || sDockContainer == null) {
                sOrientationWatch = false;
                return;
            }
            try {
                DisplayMetrics dm = realDisplayMetrics(sDockContainer.getContext());
                boolean nowLandscape = dm.widthPixels > dm.heightPixels;
                if (nowLandscape != sDockLandscape) {
                    sOrientationWatch = false;
                    onDockOrientationChanged(nowLandscape);
                    return;
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " orientation watch error: " + t.getMessage());
            }
            if (sHandleHandler == null) {
                sOrientationWatch = false;
                return;
            }
            sHandleHandler.postDelayed(this, ORIENTATION_CHECK_MS);
        }
    };

    private static Handler watchHandler() {
        return sHandleHandler != null ? sHandleHandler : new Handler(Looper.getMainLooper());
    }

    /**
     * 当前屏幕的真实像素尺寸。
     *
     * <p>优先 {@code Display.getRealMetrics()}：它是显示设备本身的尺寸，旋转后立刻正确。
     * {@code Resources.getDisplayMetrics()} 依赖该 Context 的配置更新，system_server 里
     * 拿到的 WindowContext 往往晚一两帧，重建坞窗时读到旧方向的值，就会出现“已经竖屏了
     * 但坞窗还是横屏宽度/比例”。
     */
    private static DisplayMetrics realDisplayMetrics(Context ctx) {
        DisplayMetrics dm = new DisplayMetrics();
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            Object display = XposedHelpers.callMethod(wm, "getDefaultDisplay");
            XposedHelpers.callMethod(display, "getRealMetrics", dm);
            if (dm.widthPixels > 0 && dm.heightPixels > 0) return dm;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " realDisplayMetrics fallback: " + t.getMessage());
        }
        dm.setTo(ctx.getResources().getDisplayMetrics());
        return dm;
    }

    private void removeFloatDock() {
        if (sDockClosing) {
            return;
        }
        removeAppMenu();
        if (sDockContainer == null) {
            finishRemoveDock();
            return;
        }
        sDockClosing = true;
        View dock = sDockPanel;
        if (dock == null && sDockContainer.getChildCount() > 0) {
            dock = sDockContainer.getChildAt(0);
        }
        if (dock == null) {
            finishRemoveDock();
            return;
        }
        final View dockView = dock;
        int width = dockView.getWidth();
        if (width <= 0) {
            width = dockView.getLayoutParams() != null ? dockView.getLayoutParams().width : 0;
        }
        int outX = sDockSide == 1 ? width : -width;
        dockView.animate().cancel();
        // 先把滑出动画启动起来（视觉上立即响应），再做小窗退出。小窗退出里有
        // 反射、globalLock 和条目检查这些杂活，放在动画后面不挡启动帧。
        dockView.animate()
                .translationX(outX)
                .setDuration(DOCK_ANIM_MS)
                .setInterpolator(DOCK_INTERPOLATOR)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        finishRemoveDock();
                    }
                })
                .start();
        if (!sDockCloseDuringSystemGesture) {
            DockLive.releaseSurfaceParentLock();
        }
        try {
            if (sDockBackRestoreTaskId > 0) {
                CardZoom.leaveCardExceptForSystemBack("dock-close", sDockBackRestoreTaskId);
            } else {
                CardZoom.leaveCard("dock-close");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " CardZoom.leaveCard error: " + t.getMessage());
        }
    }

    /** Keep unlock gestures out of the full-screen dock overlay while keyguard is showing. */
    static void setDockTouchable(boolean touchable) {
        FrameLayout container = sDockContainer;
        if (container == null) return;
        try {
            Context ctx = container.getContext();
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) container.getLayoutParams();
            if (touchable) {
                params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            wm.updateViewLayout(container, params);
            XposedBridge.log(TAG + " dock touchable=" + touchable);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " set dock touchability failed: " + t);
        }
    }

    private void finishRemoveDock() {
        DockLive.releaseSurfaceParentLock();
        // 先把任务图层还给系统（还原位置/缩放/可见性），再移除窗口
        try {
            DockLive.releaseAll();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " DockLive.releaseAll error: " + t.getMessage());
        }
        // 真实小窗卡片：坞窗收起时把任务收回迷你态，别把窗口留在屏幕上
        try {
            if (sDockBackRestoreTaskId > 0) {
                CardZoom.leaveCardExceptForSystemBack("dock-close", sDockBackRestoreTaskId);
            } else {
                CardZoom.leaveCard("dock-close");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " CardZoom.leaveCard error: " + t.getMessage());
        }
        CardZoom.clearActiveCard();
        sDockHoleBg = null;
        // 坞窗真正关掉了：把画面快照从「现用」降级为「兜底」，
        // 下次重新打开坞窗时才会重新抓一张；坞窗开着期间转屏不受影响。
        try {
            LivelyHook.releaseSnapshot(sDockInfo);
        } catch (Throwable ignored) {
        }
        if (sDockContainer != null) {
            try {
                Context ctx = sDockContainer.getContext();
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                wm.removeView(sDockContainer);
            } catch (Throwable ignored) {
            }
            sDockContainer = null;
        }
        // 坞窗彻底收起后才撤遮罩：这之前外面那一片仍然不可点，不会误触桌面图标。
        removeDockMask();
        sDockPanel = null;
        sDockScrollView = null;
        sDockCardList = null;
        sDockShowing = false;
        sDockClosing = false;
        sDockBackRestoreTaskId = -1;
        sDockCloseDuringSystemGesture = false;
        sOrientationWatch = false;
        if (sClassLoader != null) {
            hideAllFloatHandles(sClassLoader, 1f);
        }
    }

    /** 撤掉坞窗外面的全屏遮罩。只能在创建它那个线程（android.ui）上调用。 */
    private static void removeDockMask() {
        View mask = sDockMask;
        sDockMask = null;
        if (mask == null) return;
        try {
            Context ctx = mask.getContext();
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            wm.removeViewImmediate(mask);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " remove dock mask failed: " + t.getMessage());
        }
    }

    /** 悬浮球走官方 hide/show 动画；显隐只用 alpha，避免 setVisibility 把圆角打成直角。 */
    private void hideAllFloatHandles(ClassLoader cl, float alpha) {
        boolean hide = alpha == 0f;
        applyHandleAlpha(sCurrentFloatHandleView, hide);
        Object fhc = null;
        try {
            fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl), "getInstance");
            Object floatManager = XposedHelpers.getObjectField(fhc, "mFloatManager");
            if (floatManager != null) {
                if (hide) {
                    XposedHelpers.callMethod(floatManager, "hideFloatHandleView");
                    XposedBridge.log(TAG + " hideAllFloatHandles: hideFloatHandleView");
                } else {
                    applyHandleAlpha(XposedHelpers.callMethod(floatManager, "getView"), false);
                    XposedHelpers.callMethod(floatManager, "showFloatHandleView");
                    XposedBridge.log(TAG + " hideAllFloatHandles: showFloatHandleView");
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hideAllFloatHandles error: " + t.getMessage());
        }
        switchHandleMode(sCurrentFloatHandleView, hide ? 1 : 4);
        if (!hide) {
            final Object controller = fhc;
            restoreHandleCorners(sCurrentFloatHandleView, controller);
            if (sCurrentFloatHandleView instanceof View) {
                ((View) sCurrentFloatHandleView).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        restoreHandleCorners(sCurrentFloatHandleView, controller);
                    }
                }, DOCK_ANIM_MS);
            }
        }
    }

    private void applyHandleAlpha(Object handle, final boolean hide) {
        if (!(handle instanceof View)) return;
        final View v = (View) handle;
        Runnable r = new Runnable() {
            @Override
            public void run() {
                try {
                    XposedHelpers.callMethod(v, "setForceHidden", hide);
                } catch (Throwable ignored) {
                }
                v.setAlpha(hide ? 0f : 1f);
            }
        };
        if (!v.post(r)) {
            r.run();
        }
    }

    private void restoreHandleCorners(Object handle, Object fhc) {
        if (!(handle instanceof View)) return;
        final View v = (View) handle;
        v.post(new Runnable() {
            @Override
            public void run() {
                try {
                    android.os.IBinder token = v.getWindowToken();
                    if (token != null && fhc != null) {
                        XposedHelpers.callMethod(fhc, "resetFloatHandleViewCornersAndShadow", token);
                    }
                } catch (Throwable ignored) {
                }
                restoreOutline(v);
                try {
                    Object container = XposedHelpers.getObjectField(v, "mFloatHandleViewContainer");
                    if (container instanceof View) {
                        restoreOutline((View) container);
                    }
                } catch (Throwable ignored) {
                }
                if (v instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) v;
                    for (int i = 0; i < g.getChildCount(); i++) {
                        restoreOutline(g.getChildAt(i));
                    }
                }
            }
        });
    }

    private void restoreOutline(View v) {
        if (v == null) return;
        v.setClipToOutline(true);
        v.invalidateOutline();
    }

    private void switchHandleMode(Object handle, int toMode) {
        if (handle == null) return;
        try {
            XposedHelpers.callMethod(handle, "setForceHidden", toMode == 1);
        } catch (Throwable ignored) {
        }
        int fromMode = 4;
        try {
            fromMode = XposedHelpers.getIntField(handle, "mCurrentMode");
        } catch (Throwable ignored) {
        }
        Class<?> c = handle.getClass();
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (!"startSwitchModeAnimation".equals(m.getName())) continue;
                Class<?>[] types = m.getParameterTypes();
                if (types.length < 2 || types[0] != int.class || types[1] != int.class) continue;
                try {
                    m.setAccessible(true);
                    if (types.length == 2) {
                        m.invoke(handle, fromMode, toMode);
                    } else {
                        Object[] args = new Object[types.length];
                        args[0] = fromMode;
                        args[1] = toMode;
                        m.invoke(handle, args);
                    }
                    return;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " startSwitchModeAnimation " + t.getMessage());
                }
            }
            c = c.getSuperclass();
        }
    }

    /** dp 转 px。 */
    private int dpToPx(Context ctx, int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics());
    }

    /**
     * 坞窗要不要用深色。
     *
     * <p>跟 App 设置页右上角那个按钮的三态一致：0=跟随系统（读 system 的 uiMode）、
     * 1=固定浅色、2=固定深色。坞窗挂在 system 进程里读不到 App 的 `ui_prefs`，
     * 所以这个值同时写进了模块配置（ConfigProvider 即时查 + XSharedPreferences 重启兜底），
     * 每次打开坞窗重新拉一次，做到「App 里切完、重开坞窗就变」。
     */
    private boolean isNightMode(Context ctx) {
        try {
            if (ctx != null) loadConfigFromContext(ctx);
        } catch (Throwable ignored) {
        }
        if (themeMode == 1) return false;
        if (themeMode == 2) return true;
        try {
            Configuration cfg = ctx.getResources().getConfiguration();
            return (cfg.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }
}

