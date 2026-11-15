package io.github.halcyon2026928.freewindow;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
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

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

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

    /** 设为 true 时会把 FlexibleTaskCaptionView 的完整视图树打印到 Xposed 日志，便于在真机上确认三点按钮的确切 id。 */
    private static final boolean DUMP_CAPTION_TREE = false;

    /** 开关状态。首次自由小窗创建或点击悬浮球时经 ConfigProvider 跨进程读取。
     *  读取失败则沿用上次成功读取的配置（缓存兜底）。 */
    private boolean hideBottomBar = true;
    private boolean hideThreeDot = true;
    private boolean enableDock = true;
    /** 解除数量上限开关；关掉时上限 hook 一个都不装，系统保持官方 2 个。 */
    private boolean unlockFloatLimit = true;
    private boolean configLoaded = false;

    /** 系统框架 ClassLoader。 */
    private static ClassLoader sClassLoader;
    /** 当前点击的悬浮球 View。 */
    private static Object sCurrentFloatHandleView;
    /** 悬浮球所在线程（android.ui）的 Handler，点击那一刻抓取，恢复后用它解除 sRestoring。 */
    private static Handler sHandleHandler;
    /** 坞窗容器（全局唯一）。 */
    private static FrameLayout sDockContainer;
    /** 坞窗外面那层全屏透明遮罩：专门吃掉「点坞窗外」那一下，避免穿透到桌面图标。 */
    private static View sDockMask;
    private static View sDockPanel;
    private static int sDockSide = 1;
    private static boolean sDockClosing = false;
    /** 官方 lively 坞窗滑入滑出：PathInterpolator(0.28, 0.4, 0.2, 1) / 457ms */
    private static final long DOCK_ANIM_MS = 457L;
    private static final PathInterpolator DOCK_INTERPOLATOR = new PathInterpolator(0.28f, 0.4f, 0.2f, 1.0f);
    /** 坞窗窗口在屏幕上的偏移（用于计算菜单位置）。 */
    private static int sDockWindowX = 0;
    private static int sDockWindowY = 0;
    /** 坞窗中的应用列表。 */
    private static final List<Object> sDockApps = new ArrayList<>();
    /** 坞窗是否正在显示。 */
    private static boolean sDockShowing = false;
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
                        }
                    }
                    configLoaded = true;
                    XposedBridge.log(TAG + " config(provider) -> hideBottomBar=" + hideBottomBar
                            + " hideThreeDot=" + hideThreeDot
                            + " enableDock=" + enableDock
                            + " unlockFloatLimit=" + unlockFloatLimit);
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
            prefs.reload();
            hideBottomBar = prefs.getBoolean(KEY_HIDE_BOTTOM_BAR, true);
            hideThreeDot = prefs.getBoolean(KEY_HIDE_THREE_DOT, true);
            enableDock = prefs.getBoolean(KEY_ENABLE_DOCK, true);
            unlockFloatLimit = prefs.getBoolean(KEY_UNLOCK_LIMIT, true);
            configLoaded = true;
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

        // 贴边保活：与 GUI 装饰项彼此独立，先装；失败不影响后续 hook。
        try {
            FloatKeepAliveHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " FloatKeepAliveHook install failed: " + t.getMessage());
        }

        // 开发者选项：开机首次解锁后自动打开系统「无线调试」。
        try {
            WirelessAdbAuto.install(lpparam.classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " WirelessAdbAuto install failed: " + t.getMessage());
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

        // 第六层：游戏助手 HangUpUtil 后台挂机（最小化进悬浮球后音频继续）
        // try {
        //     MirageWindowHook.hook(lpparam.classLoader);
        // } catch (Throwable t) {
        //     XposedBridge.log(TAG + " MirageWindowHook failed: " + t.getMessage());
        // }
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
                                if (!hideThreeDot) return;

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
                // 兜底：不是走 add 的情况（批量 set / 重排）也可能留下多个图标，
                // updateIconStyle 结束时再看一眼适配器，多于一个就只留最新的那个。
                XposedBridge.hookAllMethods(fhvClass, "updateIconStyle", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object adapter = XposedHelpers.getObjectField(
                                    param.thisObject, "mIconListAdapter");
                            if (adapter == null) return;
                            Object listObj = XposedHelpers.getObjectField(
                                    adapter, "mFloatIconViewInfoList");
                            if (!(listObj instanceof List)) return;
                            List icons = (List) listObj;
                            if (icons.size() <= 1) return;
                            Object keep = icons.get(0);
                            ArrayList one = new ArrayList();
                            one.add(keep);
                            XposedHelpers.callMethod(adapter, "setFloatIconViewInfoList", one);
                            XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
                            XposedBridge.log(TAG + " single-icon trim: " + icons.size() + " -> 1");
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " single-icon trim error: " + t.getMessage());
                        }
                    }
                });
                XposedBridge.log(TAG + " FloatIconListAdapter single-icon hooks success");
            } catch (Throwable t) {
                XposedBridge.log(TAG + " FloatIconListAdapter hook skipped: " + t.getMessage());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookFloatHandleClick failed: " + t.getMessage());
        }
    }

    /** 显示坞窗：从悬浮球所在侧滑出，展示所有悬浮球应用卡片。 */
    private void showFloatDock(Object floatHandleView, Object floatHandleInfo, ClassLoader cl) {
        try {
            if (sDockClosing) {
                return;
            }
            if (sDockContainer != null) {
                return;
            }

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
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_OUTSIDE) {
                        // 点在坞窗之外：收起坞窗，菜单随坞窗一起移除
                        removeAppMenu();
                        removeFloatDock();
                        return true;
                    }
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        float x = event.getRawX();
                        float y = event.getRawY();
                        int[] loc = new int[2];
                        finalDock.getLocationOnScreen(loc);
                        boolean insideDock = x >= loc[0] && x <= loc[0] + finalDock.getWidth()
                                && y >= loc[1] && y <= loc[1] + finalDock.getHeight();
                        if (!insideDock) {
                            // 点在坞窗外：收起坞窗，菜单随坞窗一起移除
                            removeFloatDock();
                            return true;
                        }
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
            WindowManager.LayoutParams windowParams = new WindowManager.LayoutParams(2520); // TYPE_SYSTEM_EDGE_PANEL
            windowParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
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
                WindowManager.LayoutParams maskParams = new WindowManager.LayoutParams(2520);
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

            wm.addView(container, windowParams);
            sDockWinLeft = dockLeft;
            sDockWinTop = dockTopScreen;
            sDockContainer = container;
            sDockPanel = dock;
            sDockSide = side;
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
            View child = list.getChildAt(i);
            if (!(child instanceof ViewGroup)) continue;
            ViewGroup card = (ViewGroup) child;
            for (int j = 0; j < card.getChildCount(); j++) {
                View v = card.getChildAt(j);
                if (v instanceof SurfaceView && PREVIEW_TAG.equals(v.getTag())) {
                    LivelyHook.cropSnapshot((SurfaceView) v, visibleBottom);
                }
            }
        }
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
        nameTv.setTextColor(0xFF000000);
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
        menuBtn.setTextColor(0xFF333333);
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

        // 画面区：用 SurfaceView 承载快照，不再用 ImageView。
        // 尺寸规则（横竖屏同一套）：宽 = dockWidth - 44dp（标题栏「图标左缘 → 三点右缘」那段），
        // 高 = 宽 × 快照真实比例，再受坞窗可用高度约束。
        // 坞窗宽度两个方向已经统一，所以这里不需要额外冻结尺寸，转屏算出来就是同一个值。
        SurfaceView preview = LivelyHook.createSnapshotSurface(ctx, floatHandleInfo, cl);
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
        XposedBridge.log(TAG + " addAppCard surface width=" + previewWidth + " height=" + previewHeight
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
                restoreFloatInfo(finalInfo, finalCl);
            }
        };
        card.setOnClickListener(restoreListener);
        topBar.setOnClickListener(restoreListener);

        // 卡片高度定下来之后，把画面按滚动区底边再裁一次
        ScrollView sv = sDockScrollView;
        if (sv != null) {
            sv.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshPreviewCrop();
                }
            }, 60);
        }
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

        // 先收起坞窗、恢复悬浮球；官方恢复成功后悬浮球会由系统重新隐藏。
        removeFloatDock();

        // 等坞窗滑出动画跑完（finishRemoveDock 会把悬浮球显示回来）再走官方恢复：
        // 先收起坞窗、球归位，再由官方入口把任务恢复成贴边前的形态，时序与 3.2 一致。
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
        releaseRestoringGuard(handle);
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
    /** 三点菜单是独立窗口，移除时要靠它来 removeView。 */
    private static WindowManager sAppMenuWm = null;
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

        final int menuWidth = dpToPx(ctx, 130);
        final int itemHeight = dpToPx(ctx, 44);

        // 菜单容器
        LinearLayout menuContainer = new LinearLayout(ctx);
        menuContainer.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable menuBg = new GradientDrawable();
        menuBg.setColor(0xFFFFFFFF);
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
                divider.setBackgroundColor(0x14000000);
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                        menuWidth - dpToPx(ctx, 28), 1);
                dividerParams.leftMargin = dpToPx(ctx, 14);
                menuContainer.addView(divider, dividerParams);
            }
            TextView itemTv = new TextView(ctx);
            itemTv.setText(item);
            itemTv.setTextSize(14);
            itemTv.setTextColor(0xFF222222);
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

        // 定位：贴在三点点按钮正下方居中，左右不超出屏幕。
        // 菜单挂在**独立的 WindowManager 窗口**上，而不是坞窗容器里 —— 卡片画面是
        // SurfaceView 且 setZOrderOnTop(true)，它只有在「同一个窗口内」才压过别的 View；
        // 菜单放进坞窗容器就会被画面盖住。开成独立窗口后，后添加的窗口在同 type 内层级更高，
        // 于是「菜单能遮画面、画面遮不住菜单」。
        int[] anchorLoc = new int[2];
        anchor.getLocationOnScreen(anchorLoc);
        int screenWidth = ctx.getResources().getDisplayMetrics().widthPixels;
        int left = anchorLoc[0] + anchor.getWidth() / 2 - menuWidth / 2;
        left = Math.max(dpToPx(ctx, 8), Math.min(left, screenWidth - menuWidth - dpToPx(ctx, 8)));
        int top = anchorLoc[1] + anchor.getHeight() + dpToPx(ctx, 4);

        WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                menuWidth, ViewGroup.LayoutParams.WRAP_CONTENT,
                2520,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = left;
        lp.y = top;
        try {
            wm.addView(menuContainer, lp);
            sAppMenuView = menuContainer;
            sAppMenuWm = wm;
            XposedBridge.log(TAG + " showAppMenu attached at " + left + "," + top);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " showAppMenu addView failed: " + t);
        }
    }

    /** 移除菜单。 */
    private void removeAppMenu() {        View menu = sAppMenuView;
        sAppMenuView = null;
        WindowManager wm = sAppMenuWm;
        sAppMenuWm = null;
        if (menu == null) return;
        try {
            if (wm != null) wm.removeViewImmediate(menu);
        } catch (Throwable ignored) {
        }
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
        if (uid <= 0) return;

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
        sDockClosing = false;
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
        int width = dock.getWidth();
        if (width <= 0) {
            width = dock.getLayoutParams() != null ? dock.getLayoutParams().width : 0;
        }
        int outX = sDockSide == 1 ? width : -width;
        dock.animate().cancel();
        dock.animate()
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
    }

    private void finishRemoveDock() {
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
}
