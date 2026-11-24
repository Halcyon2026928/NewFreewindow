package io.github.halcyon2026928.freewindow;

import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * 「贴边保活」：小窗收成贴边浮标后，不把它的任务停掉。
 *
 * 原厂把窗口收成浮标时会把任务移回后台（Task.moveTaskToBack），可见性遍历随后让
 * ActivityRecord 走 makeInvisible()，状态落到 STOPPED；进程变成 cached，稍后即被冻结
 * 或回收，正在下载/更新的应用也就停了。
 *
 * 核心思路：浮标列表里的任务不参与这次可见性收尾——跳过 makeInvisible()、拒绝把
 * visibleRequested 改回 false、拦掉原厂那次显式的 addToStopping，并挡住到
 * STOPPING/STOPPED 的状态迁移。ActivityRecord 因此维持「可见但未置顶」的状态，进程
 * 留在 perceptible 档，不会被冻结。窗口本身仍由原厂隐藏，用户只看到浮标。
 *
 * 「被按成可见」的副作用由下面几道守卫兜住（对应上游 v2.3.6 / v2.3.10 的修复）：
 *   - 横竖屏切换、解锁瞬间：可见性整体放行，并把已经抬起来的窗口重新压回去，
 *     于是锁屏不会自动展开、解锁后仍能从浮标打开；
 *   - 锁屏/息屏期间拦住 resetFlexibleTaskToFullScreen：应用自己起新页面不会把浮标
 *     展成全屏；
 *   - 浮标期间给应用加 doze 白名单（离开浮标撤销），后台网络与休眠不再卡住播放；
 *   - 对保活应用的进程伪装「亮屏 / 没锁屏 / 显示可用」，换集连播不会因为息屏自己停。
 *
 * 移植自 FlymeFreeform 的 FloatKeepAliveHook（libxposed 版），改为经典 XposedBridge 实现。
 */
final class FloatKeepAliveHook {

    private static final String TAG = "[灵动坞OS-keepalive]";

    /** 与 SettingsActivity / ConfigProvider 中的键一致。 */
    static final String KEY_KEEP_ALIVE = "keep_alive_floating";

    private static final String RECORD_CLASS = "com.android.server.wm.ActivityRecord";
    private static final String CONTROLLER_CLASS = "com.android.server.wm.FloatHandleController";
    private static final String TASK_FRAGMENT_CLASS = "com.android.server.wm.TaskFragment";
    private static final String FLEXIBLE_CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController";
    private static final String TASK_CLASS = "com.android.server.wm.Task";
    private static final String WMS_CLASS = "com.android.server.wm.WindowManagerService";
    private static final String POWER_BINDER_CLASS =
            "com.android.server.power.PowerManagerService$BinderService";
    private static final String DISPLAY_BINDER_CLASS =
            "com.android.server.display.DisplayManagerService$BinderService";
    private static final String BROADCAST_CONTROLLER_CLASS = "com.android.server.am.BroadcastController";
    /** ColorOS 音频加固：它会拦掉后台应用的音频焦点申请。 */
    private static final String HARDENING_ENFORCER_CLASS = "com.android.server.audio.HardeningEnforcer";

    /** 贴边最小化入口：游戏在这里接幻影窗口（见 MirageWindowHook），其它应用走 mini 保活。 */
    private static final String DCS_CLASS = "com.android.server.wm.OplusFlexibleDCSManager";

    /** 引擎判定的「认不出来」结果，此时维持原来的 mini 保活。 */
    private static final String ENGINE_NONE = "none";

    /** 3.2 那套任务标记（缺哪个方法就跳过，两台机的 TaskExtImpl 不完全一样）。 */
    private static final String[] LIVELY_FLAGS = {
            "setLively", "setSceenOffPlay", "setIgnoreHiddenFlag",
            "setMaintainTaskState", "setSkipMoveTaskToBack",
            "dontPauseAfterQActivityIfNeed"
    };

    /** Display.STATE_ON / STATE_OFF。 */
    private static final int DISPLAY_STATE_ON = 2;

    /** ApplicationInfo.CATEGORY_GAME：游戏类应用。 */
    private static final int CATEGORY_GAME = 2;

    private static final Set<String> STOP_STATES = new HashSet<>();

    static {
        STOP_STATES.add("STOPPING");
        STOP_STATES.add("STOPPED");
    }

    /** 配置缓存：判定路径高频调用，最多每 2 秒重读一次开关。 */
    private static final long CONFIG_TTL_MS = 2000L;

    /** 旋转/解锁过渡结束后复查窗口是否被重新提交的时间点。 */
    private static final long[] ROTATION_CHECK_DELAYS_MS = {800L, 2000L, 4000L, 8000L};
    private static final long[] UNLOCK_CHECK_DELAYS_MS = {800L, 1600L, 2600L};

    /** 复查 doze 白名单的时间间隔。 */
    private static final long EXEMPT_SWEEP_MS = 60000L;

    private static volatile boolean sEnabled = true;
    private static volatile long sConfigReadAt = -CONFIG_TTL_MS * 2;

    private static Method sGetInstance;
    private static Method sIsInFloatingList;

    /** system_server 侧的 classLoader，装 hook 时存下来，运行期判定引擎要用。 */
    private static ClassLoader sClassLoader;

    /**
     * 浮标内起新页面之后的一小段窗口期：这段时间里不把浮标 task 移出浮标。
     * 见 installFloatEjectGuard —— 原厂正是在这一步把 mini 抬成小窗的。
     */
    private static volatile long sSuppressFloatEjectAt = 0L;

    /**
     * 用户主动从浮标打开应用的窗口期：这段时间内放行「抬成可见」，
     * 否则会把正常点开也拦掉（上游 2.3.6 修过的「解锁后从浮标打不开」）。
     */
    private static volatile long sUserFloatOpenAt = 0L;

    /** 假暂停接管成功后为 true；只有它成立时才跳过暂停超时，避免服务端卡在 PAUSING。 */
    private static volatile boolean sPauseBypassActive = false;

    /** 横竖屏切换期间为 true：可见性一律放行，避免浮标里的窗口被重新显示出来。 */
    private static final AtomicBoolean sRotationGuard = new AtomicBoolean(false);

    /** 解锁过渡期间为 true：同上。 */
    private static final AtomicBoolean sUnlockGuard = new AtomicBoolean(false);

    /** 浮标任务 -> ActivityRecord，旋转/解锁时按它主动把可见性收回去。 */
    private static final ConcurrentHashMap<Integer, WeakReference<Object>> sTracked =
            new ConcurrentHashMap<>();

    /** 包名 -> userId，只记本模块加过的 doze 白名单。 */
    private static final ConcurrentHashMap<String, Integer> sExempted = new ConcurrentHashMap<>();
    private static final AtomicBoolean sSweepScheduled = new AtomicBoolean(false);
    private static final java.util.concurrent.ExecutorService sExemptExec =
            Executors.newSingleThreadExecutor();

    /** 包名 -> 引擎判定结果（ue/unity/cocos/ejoy/none），只算一次。 */
    private static final ConcurrentHashMap<String, String> sEngineCache = new ConcurrentHashMap<>();

    /**
     * 引擎 native so 特征。
     *
     * 取自动手实测：洛克王国=libUE4.so、全民农场=libcocos2dcpp.so、三国志=libejoysdk_*，
     * 而抖音这类非游戏一个引擎 so 都没有。腾讯的 libgcloud/libMSDK 不算判据——普通
     * 腾讯应用也带。
     */
    private static final String[] SO_UNREAL = {"libUE4.so", "libUE5.so"};
    private static final String[] SO_UNITY = {"libunity.so"};
    private static final String[] SO_COCOS = {"libcocos2dcpp.so", "libcocos2djs.so", "libcocos.so"};
    private static final String[] SO_EJOY = {"libejoysdk_bundle_vm.so", "libejoysdk_inside_vm.so"};

    /** so 目录列不出来时的兜底：贴边那一刻真实顶层 Activity 的类名前缀。 */
    private static final String[] GAME_ACTIVITY_PREFIXES = {
            "com.epicgames.ue4.", "com.ejoy2dx.", "com.unity3d.player.", "org.cocos2dx."
    };

    private static Handler sHandler;

    private static final ConcurrentHashMap<String, Boolean> sLoggedTasks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Boolean> sLogOnce = new ConcurrentHashMap<>();

    /** 不同 ROM 的 token 字段名不一致，按候选顺序探测。 */
    private static final String[] TOKEN_FIELDS =
            {"token", "mToken", "mClientToken", "activityToken", "mActivityToken", "mAppToken"};

    private static Class<?> sRecordClass;

    private FloatKeepAliveHook() {
    }

    static void install(ClassLoader classLoader) {
        try {
            sRecordClass = XposedHelpers.findClass(RECORD_CLASS, classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " ActivityRecord unavailable: " + t.getMessage());
            return;
        }
        sHandler = new Handler(Looper.getMainLooper());
        sClassLoader = classLoader;

        resolveController(classLoader);

        boolean recordHooked = installRecordHooks();
        boolean pauseHooked = installPauseSkip(classLoader);
        installRotationGuard(classLoader);
        installKeyguardGuard(classLoader);
        installFullScreenResetGuard(classLoader);
        installAdjustOptionsGuard(classLoader);
        installFloatEjectGuard(classLoader);
        installAudioFocusGuard(classLoader);
        installAwakeGuard(classLoader);
        installScreenOffGuard(classLoader);
        installDisplayStateGuard(classLoader);
        installKeyguardLockGuard(classLoader);
        boolean livelyMarks = installLivelyMarks(classLoader);
        sExemptExec.execute(new Runnable() {
            @Override
            public void run() {
                sweepExemptions();
            }
        });

        XposedBridge.log(TAG + " installed (pauseSkip=" + pauseHooked
                + ", floatingController=" + (sIsInFloatingList != null ? "ok" : "missing")
                + ", livelyMarks=" + livelyMarks + ")");
    }

    // ==================== 贴边时的任务标记 ====================

    /**
     * 贴边时给任务打一整套保活标记（3.2 的做法，对所有应用都打，before + after 各一次）。
     *
     * 真正的「后台串流继续跑」在 MirageWindowHook 里，只对游戏启用；
     * 这里只负责标记，普通应用维持原来的 mini 保活。
     */
    private static boolean installLivelyMarks(ClassLoader classLoader) {
        Class<?> dcs = findClassSafe(classLoader, DCS_CLASS);
        if (dcs == null) return false;
        return hookAllSafe(dcs, "startMinimize", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                markTaskLively(taskArg(param));
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                markTaskLively(taskArg(param));
            }
        });
    }

    /** 任务标记：息屏继续跑、别把隐藏当成掉后台。缺哪个方法就跳过。 */
    private static void markTaskLively(Object task) {
        if (task == null) return;
        Object ext = extOf(task);
        for (String name : LIVELY_FLAGS) {
            callQuietly(ext, name, true);
        }
        // 这个是 int 形参（1 = 不敏感），不能跟着上面按 boolean 一起传。
        callQuietly(ext, "setInsensibleFlag", 1);
    }

    /**
     * 从浮标/坞窗恢复成可见自由小窗时，把 minimize 阶段打上的整套标记清掉。
     *
     * <p>其中 {@code insensibleFlag} 最关键：系统的
     * {@code InsensibleTaskController.ignoreBackKeyEventIfNeeded} 对「不敏感任务 + 无白条 +
     * 在屏内」直接返回 true 把 BACK 键吞掉（原厂只在屏外拉起的 AI 窗口上打这个标记）。
     * 标记残留在恢复后的小窗上，表现就是「按返回没反应、页面不后退、窗口只浮在桌面上」。
     * 官方恢复路径只清了 {@code setSkipMoveTaskToBack}，不会清 insensible，所以必须我们自己清。
     */
    static void clearTaskLively(Object task) {
        if (task == null) return;
        Object ext = extOf(task);
        for (String name : LIVELY_FLAGS) {
            callQuietly(ext, name, false);
        }
        callQuietly(ext, "setInsensibleFlag", 0);
    }

    private static Object extOf(Object task) {
        Object ext = task;
        try {
            Object wrapper = XposedHelpers.callMethod(task, "getWrapper");
            ext = XposedHelpers.callMethod(wrapper, "getExtImpl");
        } catch (Throwable ignored) {
            try {
                ext = XposedHelpers.callMethod(task, "getExtImpl");
            } catch (Throwable alsoIgnored) {
                ext = task;
            }
        }
        return ext;
    }

    private static Object taskArg(XC_MethodHook.MethodHookParam param) {
        Object[] args = param.args;
        if (args == null) return null;
        for (Object arg : args) {
            if (arg != null && "com.android.server.wm.Task".equals(arg.getClass().getName())) {
                return arg;
            }
        }
        return args.length > 1 ? args[1] : null;
    }

    /** 挂机分流用：引擎能认出来才算游戏，普通应用照旧走 mini 保活。 */
    static boolean isGamePackage(Intent intent) {
        if (intent == null) return false;
        ComponentName component = intent.getComponent();
        if (component == null) return false;
        return !ENGINE_NONE.equals(
                engineOfPackage(component.getPackageName(), component.getClassName()));
    }

    /** 引擎判定：先看 so，读不到再看顶层 Activity 前缀；都没有算普通应用。 */
    private static String engineOfPackage(String pkg, String topClass) {
        String cached = sEngineCache.get(pkg);
        if (cached != null) return cached;
        String engine = ENGINE_NONE;
        boolean confident = false;
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.AppGlobals", sClassLoader), "getInitialApplication");
            if (app != null) {
                Object pm = XposedHelpers.callMethod(app, "getPackageManager");
                Object appInfo = XposedHelpers.callMethod(pm, "getApplicationInfo", pkg, 0);
                Object lib = readField(appInfo, "nativeLibraryDir");
                if (lib instanceof String) {
                    engine = engineFromLib((String) lib);
                    confident = true;
                }
            }
        } catch (Throwable ignored) {
        }
        if (ENGINE_NONE.equals(engine)) {
            engine = engineFromClass(topClass);
            confident = true;
        }
        // 只有拿到确定结论才缓存：查询失败一次就把游戏永久记成普通应用是最坏的结果
        if (confident) sEngineCache.put(pkg, engine);
        return engine;
    }

    private static void callQuietly(Object target, String method, Object... args) {
        if (target == null) return;
        try {
            XposedHelpers.callMethod(target, method, args);
        } catch (Throwable ignored) {
        }
    }



    // ==================== ActivityRecord 上的保护 ====================

    /**
     * 浮标任务上的保护：状态迁移、停止、暂停、暂停超时。
     *
     * 可见性两条**只观测、不改写**（3820 实测结论）：
     * 把浮标任务按成「可见」后，系统会把它当可见任务重新提交窗口，应用一起来新页面
     * 就会弹出系统的自由小窗；放行由系统自己收尾反而更接近原厂形态。
     * 进程档位由 setState / addToStopping / pauseSkip 维持，播不中断由音频焦点、亮屏
     * 伪装、息屏广播、Display/Keyguard 伪装和 doze 白名单负责，都不依赖「任务可见」。
     *
     * 弹窗问题改在 `isAllowedToAdjustOptions` 上处理（见 installAdjustOptionsGuard）。
     */
    private static boolean installRecordHooks() {
        boolean hooked = false;

        // 1) makeInvisible(): 只观测，不拦截。系统该收尾就收尾，窗口才不会被抬起来。
        hooked |= hookAllSafe(sRecordClass, "makeInvisible", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (((Method) param.method).getReturnType() != Void.TYPE) return;
                    if (!shouldKeep(param.thisObject)) return;
                    logSkip(param.thisObject, "invisible");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " makeInvisible hook error: " + t.getMessage());
                }
            }
        });

        // 2) setVisibleRequested(boolean[, boolean])：浮标任务不接受「抬成可见」。
        //    先放行再压回会让窗口显示半拍又消失、输入焦点丢掉（3820 实测 ANR），
        //    所以直接在 before 里拒掉；用户点浮标打开时会先经过 moveTaskToFront 打开白名单。
        hooked |= hookAllSafe(sRecordClass, "setVisibleRequested", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length < 1) return;
                    Object arg = param.args[0];
                    if (!(arg instanceof Boolean)) return;
                    boolean visible = ((Boolean) arg).booleanValue();
                    if (!shouldKeep(param.thisObject)) return;
                    if (visible) {
                        if (SystemClock.uptimeMillis() < sUserFloatOpenAt) {
                            logOnce("useropen_" + taskIdOf(param.thisObject),
                                    "task" + taskIdOf(param.thisObject) + " 用户从浮标打开，放行可见");
                            return;
                        }
                        // 用户从桌面/最近任务正常打开这个还在保活里的 task：
                        // 这条路径不经过 FloatHandleController.moveTaskToFront，拿不到
                        // sUserFloatOpenAt 白名单。若继续拒掉可见性，窗口会停在
                        // isFocused=true + isVisible=false —— 输入法 attach 不上，
                        // 表现为「从桌面打开后点输入框弹不出键盘」。
                        if (SystemClock.uptimeMillis() >= sSuppressFloatEjectAt
                                && isTopRootTask(param.thisObject)) {
                            leaveKeepAlive(param.thisObject, "visible");
                            return;
                        }
                        param.args[0] = Boolean.FALSE;
                        logOnce("deny_raised_" + taskIdOf(param.thisObject),
                                "task" + taskIdOf(param.thisObject) + " 拒绝抬成可见（浮标保持）");
                    } else {
                        logSkip(param.thisObject, "visible_requested_false");
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " setVisibleRequested hook error: " + t.getMessage());
                }
            }
        });

        // 2b) setVisibility(boolean)：系统的应用切换流程会直接在这里写可见性，
        //     不经过 setVisibleRequested，浮标任务同样要拒掉。
        hooked |= hookAllSafe(sRecordClass, "setVisibility", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length != 1) return;
                    Object arg = param.args[0];
                    if (!(arg instanceof Boolean) || !((Boolean) arg).booleanValue()) return;
                    if (!shouldKeep(param.thisObject)) return;
                    if (SystemClock.uptimeMillis() < sUserFloatOpenAt) return;
                    // 同 setVisibleRequested：桌面/最近任务把 task 抬到顶层时是用户在正常
                    // 打开它，必须放行并退出保活，否则窗口「有焦点不可见」，输入法失效。
                    if (SystemClock.uptimeMillis() >= sSuppressFloatEjectAt
                            && isTopRootTask(param.thisObject)) {
                        leaveKeepAlive(param.thisObject, "visibility");
                        return;
                    }
                    param.args[0] = Boolean.FALSE;
                    logOnce("deny_visibility_" + taskIdOf(param.thisObject),
                            "task" + taskIdOf(param.thisObject) + " 拒绝提交可见性（浮标保持）");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " setVisibility hook error: " + t.getMessage());
                }
            }
        });

        // 3) setState(State, String): 挡住到 STOPPING/STOPPED 的迁移，并在换集窗口内挡住新页面的 RESUMED。
        hooked |= hookAllSafe(sRecordClass, "setState", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object stateArg = param.args.length > 0 ? param.args[0] : null;
                    if (!(stateArg instanceof Enum)) return;
                    String state = ((Enum<?>) stateArg).name();
                    if (((Method) param.method).getReturnType() != Void.TYPE) return;
                    /*
                     * 用户从桌面 / 最近任务正常打开一个贴过边的应用时，走的是 task 到前台
                     * + resume 这条正常流程，不经过 FloatHandleController.moveTaskToFront，
                     * 也就没有 clearTaskLively 的机会。minimize 阶段打的 insensibleFlag 会
                     * 一路残留，系统的
                     *   FlexibleWindowUtils.isNoSensibleFlexibleWindow(task)
                     * 于是判定为真，WindowStateExtImpl.cannotBeImeTarget 返回 true：
                     * 窗口不能当输入法宿主，点输入框永远弹不出键盘（5200 22:19:10 起的
                     * "cannotBeImeTarget while target is insensible window"）。
                     * 这里按「record 已在 display 顶层 root task 上」判定为用户正常打开，
                     * 立刻清掉整套标记。浮标里的任务不是顶层 root task，不会被误清。
                     */
                    if ("RESUMED".equals(state) && isTopRootTask(param.thisObject)) {
                        Object task = callNoArg(param.thisObject, "getTask");
                        clearTaskLively(task);
                        leaveKeepAlive(param.thisObject, "resumed_top");
                        // 官方口径：应用被外部正常打开后，浮标里的条目没了，坞窗里也不该再留它。
                        // 但坞窗正展开时这里的 RESUMED 是坞窗自己把卡片任务拉上顶层造成的，
                        // 不是用户从桌面打开——此时撤卡会把坞窗清空并自动收起（要点两次悬浮球）。
                        int extTaskId = taskIdOf(param.thisObject);
                        if (!MainHook.isDockShowing()) {
                            try {
                                DockLive.onTaskExternallyOpened(extTaskId);
                                MainHook.removeDockCardForTask(extTaskId);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    if (!shouldKeep(param.thisObject)) return;
                    if (STOP_STATES.contains(state)) {
                        logSkip(param.thisObject, "state_" + state);
                        param.setResult(null);
                        return;
                    }
                    /*
                     * 换集（浮标内应用自己起新页面）会 resume 一个新 Activity。resume 的
                     * 触发链是 setState(RESUMED) -> onActivityStateChanged -> setResumedActivity
                     * -> onTaskFocusChanged -> onFocusChanged(mHasFocus=true)，最后输入焦点落到
                     * 这个窗口上。而窗口可见性又被「换集不弹窗」那道钩子压成 false，最终
                     * isFocused=true + isVisible=false —— 输入派发找不到可见的聚焦窗口，之后
                     * 一调音量就 Input dispatching timed out（无聚焦窗口）ANR。
                     *
                     * 处理：在换集窗口内，让新页面停在 started、别进 RESUMED（客户端照常收到
                     * onResume、继续播），这样 onActivityStateChanged 不触发，焦点不转移，也不弹窗。
                     * 用户点浮标打开时 sUserFloatOpenAt 生效，不会误伤。
                     */
                    if ("RESUMED".equals(state)
                            && SystemClock.uptimeMillis() < sSuppressFloatEjectAt
                            && SystemClock.uptimeMillis() >= sUserFloatOpenAt) {
                        int taskId = taskIdOf(param.thisObject);
                        logOnce("skip_resume_" + taskId,
                                "task" + taskId + " 换集新页面不进入 RESUMED（保持 started，焦点不动）");
                        param.setResult(null);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " setState hook error: " + t.getMessage());
                }
            }
        });

        // 4) addToStopping(...): 签名随 ROM 变化，按名字遍历全部 void 重载。
        hooked |= hookAllSafe(sRecordClass, "addToStopping", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (((Method) param.method).getReturnType() != Void.TYPE) return;
                    if (!shouldKeep(param.thisObject)) return;
                    logSkip(param.thisObject, "add_to_stopping");
                    param.setResult(null);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " addToStopping hook error: " + t.getMessage());
                }
            }
        });

        // 5) schedulePauseTimeout(): 仅在假暂停已接管时跳过，避免阻塞 AMS 的暂停流程。
        hooked |= hookAllSafe(sRecordClass, "schedulePauseTimeout", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length != 0) return;
                    if (!sPauseBypassActive) return;
                    if (!shouldKeep(param.thisObject)) return;
                    if (((Method) param.method).getReturnType() != Void.TYPE) return;
                    param.setResult(null);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " schedulePauseTimeout hook error: " + t.getMessage());
                }
            }
        });

        return hooked;
    }

    /**
     * 暂停请求不发给客户端：服务端照常进入 PAUSING，再由这里补一次 activityPaused
     * 让 AMS 完成暂停并继续恢复下一个应用；客户端收不到 onPause，画面与逻辑继续跑。
     */
    private static boolean installPauseSkip(ClassLoader classLoader) {
        Class<?> fragmentClass;
        try {
            fragmentClass = XposedHelpers.findClass(TASK_FRAGMENT_CLASS, classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " TaskFragment unavailable: " + t.getMessage());
            return false;
        }
        return hookAllSafe(fragmentClass, "schedulePauseActivity", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length == 0 || !sRecordClass.isInstance(param.args[0])) return;
                    Object record = param.args[0];
                    if (!shouldKeep(record)) return;
                    /*
                     * 游戏不能吞掉 pause。
                     *
                     * ejoy 这类引擎在 onPause 里切到自己的「后台音频」分支；只丢给它
                     * topResumedLost + 窗口 NO_WINDOW（pause 被吞）时，它判定状态异常，
                     * 直接把音频引擎停掉。实测三国志战略版冷启动首次收进浮标后全程无声，
                     * 唤出小窗再贴边时走的是 pauseInRecentsAnim（pause 没被吞、应用完整
                     * 收到 onPause），声音立刻恢复。
                     *
                     * 所以对游戏放行这条 pause，让应用按自己的后台逻辑继续出声；
                     * stop 与可见性仍由 setState / addToStopping / setVisibleRequested
                     * 三道钩子拦着，ActivityRecord 掉不到 cached，进程档位不变。
                     */
                    if (isGameRecord(record)) {
                        logSkip(record, "pause_passthrough_game");
                        return;
                    }
                    if (!scheduleFakePause(record)) return;
                    logSkip(record, "pause");
                    markPausing(param.thisObject, record);
                    param.setResult(null);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " schedulePauseActivity hook error: " + t.getMessage());
                }
            }
        });
    }

    // ==================== 旋转 / 解锁：把被抬起来的窗口压回去 ====================

    /**
     * 横竖屏切换：浮标里的窗口不能重新出现。
     *
     * 旋转时原厂会按「这个任务是否可见」重排浮动窗口，而保活为了进程档位一直把
     * ActivityRecord 按成可见，于是旋转一结束窗口就被重新提交出来。这里在配置变化
     * 开始时把浮标任务的可见性主动收回（守卫置位，可见性两道钩子整体放行），
     * 过渡结束后再复查几次：仍是浮标却又是可见的，重新压下去。
     */
    private static void installRotationGuard(ClassLoader classLoader) {
        Class<?> flexibleClass = findClassSafe(classLoader, FLEXIBLE_CONTROLLER_CLASS);
        if (flexibleClass != null) {
            boolean hooked = hookAllSafe(flexibleClass, "onGlobalConfigurationChanged", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length < 3) return;
                        if (!isRotationChange(param.args[1], param.args[2])) return;
                        param.setObjectExtra("fw_rotated", Boolean.TRUE);
                        beginRotation();
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " rotation config hook error: " + t.getMessage());
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!Boolean.TRUE.equals(param.getObjectExtra("fw_rotated"))) return;
                        scheduleRotationEnd();
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " rotation config after error: " + t.getMessage());
                    }
                }
            });
            if (!hooked) XposedBridge.log(TAG + " rotation config hook missing (ok on some ROMs)");
        }

        // 旋转过渡自身也有回调，作为配置变化之外的第二个入口。
        Class<?> floatClass = findClassSafe(classLoader, CONTROLLER_CLASS);
        if (floatClass != null) {
            hookAllSafe(floatClass, "onDisplayRotationTransitionStarting", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length != 0) return;
                        beginRotation();
                    } catch (Throwable ignored) {
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length == 0) scheduleRotationEnd();
                    } catch (Throwable ignored) {
                    }
                }
            });
        }
    }

    private static void beginRotation() {
        if (!sRotationGuard.compareAndSet(false, true)) return;
        rehideTracked("rotation");
        XposedBridge.log(TAG + " rotation guard on");
    }

    private static void scheduleRotationEnd() {
        Handler handler = sHandler;
        if (handler == null) return;
        for (int i = 0; i < ROTATION_CHECK_DELAYS_MS.length; i++) {
            final boolean last = i == ROTATION_CHECK_DELAYS_MS.length - 1;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        rehideTracked("rotation_recheck");
                        if (last) sRotationGuard.set(false);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " rotation recheck error: " + t.getMessage());
                    }
                }
            }, ROTATION_CHECK_DELAYS_MS[i]);
        }
    }

    /**
     * 解锁瞬间同样是一次整屏可见性重排：先把保卫放下、把被抬起来的窗口压回去，
     * 再在几个时间点复查，这样解锁后不会自己变成全屏窗口，浮标/轮盘也还能打开。
     */
    private static void installKeyguardGuard(ClassLoader classLoader) {
        Class<?> flexibleClass = findClassSafe(classLoader, FLEXIBLE_CONTROLLER_CLASS);
        if (flexibleClass == null) return;
        boolean hooked = hookAllSafe(flexibleClass, "notifyKeyguardStateChanged", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length < 2) return;
                    Object showing = param.args[1];
                    if (!(showing instanceof Boolean)) return;
                    boolean keyguardShowing = ((Boolean) showing).booleanValue();
                    XposedBridge.log(TAG + " keyguard state changed showing=" + keyguardShowing
                            + " who=" + param.args[0]);
                    if (keyguardShowing) {
                        // 官方锁屏事件：坞窗开着就按正常关闭路径收掉（和点坞窗外一致），
                        // 这样收起后的状态和平时手动收起完全一样，解锁不会弹窗
                        MainHook.dismissDockForLock();
                    } else {
                        param.setObjectExtra("fw_unlocking", Boolean.TRUE);
                        beginUnlockGuard();
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " keyguard hook error: " + t.getMessage());
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!Boolean.TRUE.equals(param.getObjectExtra("fw_unlocking"))) return;
                    scheduleUnlockGuardEnd();
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " keyguard after error: " + t.getMessage());
                }
            }
        });
        if (!hooked) XposedBridge.log(TAG + " keyguard state hook missing");
    }

    private static void beginUnlockGuard() {
        if (!sUnlockGuard.compareAndSet(false, true)) return;
        rehideTracked("unlock");
        XposedBridge.log(TAG + " unlock guard on");
    }

    private static void scheduleUnlockGuardEnd() {
        Handler handler = sHandler;
        if (handler == null) {
            sUnlockGuard.set(false);
            return;
        }
        for (int i = 0; i < UNLOCK_CHECK_DELAYS_MS.length; i++) {
            final boolean last = i == UNLOCK_CHECK_DELAYS_MS.length - 1;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        rehideTracked("unlock_recheck");
                        if (last) sUnlockGuard.set(false);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " unlock recheck error: " + t.getMessage());
                    }
                }
            }, UNLOCK_CHECK_DELAYS_MS[i]);
        }
    }

    /**
     * 这个 record 是否已经坐在 display 的顶层 root task 上。
     *
     * 从桌面 / 最近任务打开一个还在浮标保活里的应用时，系统会复用原 task 并把它
     * bring 到最前，此时它就是顶层 root task。这条路径不经过
     * FloatHandleController.moveTaskToFront，所以没有 sUserFloatOpenAt 白名单。
     */
    private static boolean isTopRootTask(Object record) {
        try {
            Object task = callNoArg(record, "getTask");
            if (task == null) return false;
            return Boolean.TRUE.equals(callNoArg(task, "isTopRootTaskInDisplayArea"));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 退出保活：用户从外部（桌面 / 最近任务）把任务正常打开时调用。
     *
     * 只需要把记录从 sTracked 摘掉并放行可见性；浮标本身由系统
     * FloatHandleController 在 bring-to-front 时自己摘除，不用模块插手。
     */
    private static void leaveKeepAlive(Object record, String reason) {
        try {
            int taskId = taskIdOf(record);
            if (taskId > 0) sTracked.remove(taskId);
            logOnce("leave_" + taskId + "_" + reason,
                    "task" + taskId + " 用户从外部正常打开，退出保活并放行可见性（" + reason + "）");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " leaveKeepAlive error: " + t.getMessage());
        }
    }

    /** 仍是浮标、却被按成可见的任务，重新压回不可见，让它回到浮标状态。 */
    private static void rehideTracked(String reason) {        for (java.util.Map.Entry<Integer, WeakReference<Object>> entry : sTracked.entrySet()) {
            Object record = entry.getValue() == null ? null : entry.getValue().get();
            if (record == null) continue;
            if (!isFloating(entry.getKey().intValue())) continue;
            try {
                if (Boolean.TRUE.equals(readField(record, "mVisibleRequested"))) {
                    Object result = callNoArg(record, "setVisibleRequested", Boolean.FALSE);
                    if (Boolean.TRUE.equals(result)) {
                        logOnce("rehide_" + entry.getKey() + "_" + reason,
                                "task" + entry.getKey() + " rehide (" + reason + ")");
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " rehide error: " + t.getMessage());
            }
        }
    }

    // ==================== 锁屏/息屏期间不把浮标展开成全屏 ====================

    /**
     * 原厂在 isAllowedToAdjustOptions() 里有一条：锁屏状态下若目标任务是浮窗，直接把任务
     * resetFlexibleTaskToFullScreen()。正常使用没问题，但保活时应用在锁屏期间还在跑，
     * 它自己起一个新 Activity（例如短剧自动连播）就会命中这条，浮标被摘掉、任务变全屏，
     * 解锁后用户看到的是一屏全屏窗口。
     */
    private static void installFullScreenResetGuard(ClassLoader classLoader) {
        Class<?> flexibleClass = findClassSafe(classLoader, FLEXIBLE_CONTROLLER_CLASS);
        if (flexibleClass == null) return;
        hookAllSafe(flexibleClass, "resetFlexibleTaskToFullScreen", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    // 只拦 (Task, int) 这一层：单参版本内部会转调它，拦一次就够
                    if (param.args.length != 2) return;
                    if (!(param.args[0] != null && param.args[1] instanceof Integer)) return;
                    int taskId = taskIdOfObject(param.args[0]);
                    if (!isKeptFloat(taskId)) return;
                    if (!isLockedOrOff(param.thisObject)) return;
                    logOnce("skip_fullscreen_" + taskId, "task" + taskId + " skip fullscreen reset");
                    param.setResult(null);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " fullscreen reset hook error: " + t.getMessage());
                }
            }
        });
    }

    /** 保活负责的浮标任务：还在浮标列表里，或本轮保活记录过的。 */
    private static boolean isKeptFloat(int taskId) {
        if (taskId <= 0) return false;
        if (isFloating(taskId)) return true;
        WeakReference<Object> ref = sTracked.get(taskId);
        return ref != null && ref.get() != null;
    }

    /**
     * 应用在浮标里自己起新页面（例如短剧换集）时，别让原厂把新 Activity 排成浮动小窗。
     *
     * 反编译 FlexibleTaskController 里那条判定：
     *   `isAllowedToAdjustOptions` 只有在「来源与目标同一个 task，且目标可见或来源 visibleRequested
     *   为真」时才 return 4（= 不调整 options，沿用原样）；否则 return 0，进入
     *   `adjustOptionsForFlexibleTask` 的浮动小窗布局分支，新 Activity 就被摆成小窗、浮标随之展开。
     *
     * 保活为了让进程不掉档，浮标任务的 visibleRequested 是 false，所以每次都掉进「弹小窗」那条。
     * 这里按「保活中的浮标 task + 同 task 内启动」把结果按成 4，等价于原厂认为它「还在正常显示」，
     * 于是新 Activity 在原 task 里继续跑，窗口不弹出来；播放不受影响。
     */
    /**
     * 认标记：哪一次启动是「浮标里的应用自己起了新页面」。
     *
     * 这一步**只做识别，不改原厂的判定结果**。之前在这里把 reason 按成 4 是无效的——
     * 实测 reason 9 和 4 都会走到同一个结果：原厂随后把 task 移前台、摘浮标、抬成 zoom，
     * 新页面就是以自由小窗的形态出来（3820 03:02 日志）。窗口形态不是 options 决定的。
     *
     * 唯一有用的产出是一个时间窗：接下来的 moveTaskToFront 属于「自己起页面」，
     * 不该把浮标摘掉（见 installFloatEjectGuard）。
     */
    private static void installAdjustOptionsGuard(ClassLoader classLoader) {
        Class<?> flexibleClass = findClassSafe(classLoader, FLEXIBLE_CONTROLLER_CLASS);
        if (flexibleClass == null) return;
        boolean hooked = hookAllSafe(flexibleClass, "isAllowedToAdjustOptions", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!keepAliveEnabled()) return;
                    if (param.args.length < 4) return;
                    if (((Method) param.method).getReturnType() != Integer.TYPE) return;
                    Object startActivity = param.args[2];
                    Object sourceRecord = param.args[1];
                    if (startActivity == null || sourceRecord == null) return;
                    Object sourceTask = readField(sourceRecord, "task");
                    if (sourceTask == null) sourceTask = readField(sourceRecord, "mTask");
                    int sourceId = taskIdOfObject(sourceTask);
                    if (sourceId <= 0 || !isFloating(sourceId)) return;
                    Object sourcePkg = readField(sourceRecord, "packageName");
                    Object startPkg = readField(startActivity, "packageName");
                    if (!(sourcePkg instanceof String) || !(startPkg instanceof String)) return;
                    if (!sourcePkg.equals(startPkg)) return;
                    sSuppressFloatEjectAt = SystemClock.uptimeMillis() + 2500L;
                    logOnce("floatjump_" + sourceId,
                            "task" + sourceId + " 浮标内起新页面（" + startPkg + "），reason="
                                    + param.getResult() + " 不摘浮标");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " isAllowedToAdjustOptions hook error: " + t.getMessage());
                }
            }
        });
        if (!hooked) XposedBridge.log(TAG + " isAllowedToAdjustOptions not hooked");
    }

    /**
     * 别让「浮标里的应用自己起新页面」把浮标摘掉。
     *
     * 3820 的系统日志（03:02:03.933）：
     *   FloatHandleController: task has move to front, goto removeFloatHandle taskID = 3549
     *   FloatHandleController: removeFloatHandleInner remove info = FloatHandleInfo{taskId = 3549 ...}
     *   OplusZoomWindowObserverManager: OplusZoomWindowInfo { ... shown = true ... }
     * 也就是：新页面一启动，原厂就把这个 task 当作「用户要打开它」处理——移到前台、摘掉浮标，
     * task 从 mini 回到 zoom，于是新页面以官方自由小窗的形态弹出来。
     * 这一步发生在 options 之后，所以之前改 reason / 改可见性都拦不住。
     *
     * 处理：只在「我们刚拦到浮标内同包起新页面」的那 2.5 秒里，把这次 moveTaskToFront 直接吃掉。
     * task 留在浮标里，新页面照常 resume、照常播放。
     * 用户真去点浮标打开应用时不走 isAllowedToAdjustOptions，窗口期不会打开，行为不受影响。
     */
    private static boolean installFloatEjectGuard(ClassLoader classLoader) {
        Class<?> controller = findClassSafe(classLoader, CONTROLLER_CLASS);
        if (controller == null) return false;
        boolean hooked = hookAllSafe(controller, "moveTaskToFront", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length != 2) return;
                    Object task = param.args[1];
                    if (task == null) return;
                    int taskId = taskIdOfObject(task);
                    if (taskId <= 0 || !isFloating(taskId)) return;
                    if (SystemClock.uptimeMillis() < sSuppressFloatEjectAt) {
                        // 应用自己起新页面：这次别移出浮标
                        param.setResult(null);
                        logOnce("eject_" + taskId,
                                "task" + taskId + " 起新页面时不移出浮标（保留浮标，不弹窗）");
                        return;
                    }
                    // 其余情况（用户点浮标打开）记一个白名单窗口，放行后面的可见性
                    sUserFloatOpenAt = SystemClock.uptimeMillis() + 2000L;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " moveTaskToFront hook error: " + t.getMessage());
                }
            }
        });
        if (!hooked) XposedBridge.log(TAG + " FloatHandleController.moveTaskToFront missing");
        return hooked;
    }

    private static boolean isLockedOrOff(Object controller) {
        if (Boolean.TRUE.equals(readField(controller, "mIsKeyGuardShowing"))) return true;
        Object fwms = readField(controller, "mFlexibleWindowManagerService");
        return Boolean.TRUE.equals(callNoArg(fwms, "isScreenOff"));
    }

    // ==================== 保活应用的「前台假象」：让换集连播不中断 ====================

    /**
     * 保活期间放行音频焦点。
     *
     * ColorOS 的音频加固（HardeningEnforcer.blockFocusMethod）不允许后台应用申请音频焦点，
     * 应用贴边成浮标后就是后台，换集/换曲重新申请焦点被拒，播放就自己停了。
     * 浮标保活的语义是「这个应用还在前台服务工作」，所以这里直接判为不拦截。
     */
    private static void installAudioFocusGuard(ClassLoader classLoader) {
        Class<?> enforcerClass = findClassSafe(classLoader, HARDENING_ENFORCER_CLASS);
        if (enforcerClass == null) return;
        hookAllSafe(enforcerClass, "blockFocusMethod", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!keepAliveEnabled()) return;
                    if (param.args.length == 0) return;
                    Object uidArg = param.args[0];
                    if (!(uidArg instanceof Integer)) return;
                    int taskId = keptFloatTaskOfUid(((Integer) uidArg).intValue());
                    if (taskId <= 0) return;
                    if (((Method) param.method).getReturnType() != Boolean.TYPE) return;
                    logOnce("focus_" + taskId, "task" + taskId + " audio focus allowed");
                    param.setResult(Boolean.FALSE);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " audio focus hook error: " + t.getMessage());
                }
            }
        });
    }

    /**
     * 对保活中的浮标应用伪装成「亮屏、没进省电」。
     *
     * 视频类应用在换下一集前普遍会看 PowerManager.isInteractive()，息屏就不自动连播。
     * 这里只对「正在被保活的浮标应用」按调用 uid 返回亮屏，其它进程不受影响。
     */
    private static void installAwakeGuard(ClassLoader classLoader) {
        Class<?> binderClass = findClassSafe(classLoader, POWER_BINDER_CLASS);
        if (binderClass == null) return;
        hookAwakeAnswer(binderClass, "isInteractive", Boolean.TRUE);
        hookAwakeAnswer(binderClass, "isDeviceIdleMode", Boolean.FALSE);
        hookAwakeAnswer(binderClass, "isLightDeviceIdleMode", Boolean.FALSE);
    }

    private static void hookAwakeAnswer(Class<?> binderClass, String name, final Boolean answer) {
        hookAllSafe(binderClass, name, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!keepAliveEnabled()) return;
                    if (param.args.length != 0) return;
                    if (((Method) param.method).getReturnType() != Boolean.TYPE) return;
                    int taskId = keptFloatTaskOfUid(Binder.getCallingUid());
                    if (taskId <= 0) return;
                    logOnce("awake_" + name + "_" + taskId,
                            "task" + taskId + " " + name + " -> " + answer);
                    param.setResult(answer);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " " + name + " hook error: " + t.getMessage());
                }
            }
        });
    }

    /**
     * 不把息屏广播投给正在保活的浮标应用。
     *
     * 视频类应用收到 ACTION_SCREEN_OFF 后会把自己标成「熄屏」，当前这集照播，换下一集时
     * 就不会自动连播。做法只在这一条广播上把保活应用的包名加进 excludedPackages。
     */
    private static void installScreenOffGuard(ClassLoader classLoader) {
        Class<?> controllerClass = findClassSafe(classLoader, BROADCAST_CONTROLLER_CLASS);
        if (controllerClass == null) return;
        boolean hooked = false;
        for (Method method : controllerClass.getDeclaredMethods()) {
            if (!"broadcastIntentLocked".equals(method.getName())) continue;
            if (method.getParameterCount() < 20) continue;
            final int excludedIdx = excludedPackagesIndex(method);
            if (excludedIdx < 0) continue;
            try {
                method.setAccessible(true);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (!keepAliveEnabled()) return;
                            // Intent 的参数位置随 ROM 变化（有的机器前置了 service），按类型找。
                            Intent intent = null;
                            for (Object arg : param.args) {
                                if (arg instanceof Intent) {
                                    intent = (Intent) arg;
                                    break;
                                }
                            }
                            if (intent == null) return;
                            if (!Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) return;
                            ArrayList<String> packages = keptFloatPackages();
                            if (packages.isEmpty()) return;
                            ArrayList<String> merged = new ArrayList<>();
                            Object previous = param.args[excludedIdx];
                            if (previous instanceof String[]) {
                                for (String p : (String[]) previous) {
                                    if (p != null && !merged.contains(p)) merged.add(p);
                                }
                            }
                            for (String p : packages) {
                                if (!merged.contains(p)) merged.add(p);
                            }
                            param.args[excludedIdx] = merged.toArray(new String[0]);
                            logOnce("screenoff_" + merged, "screen off skipped for " + packages);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " screen-off hook error: " + t.getMessage());
                        }
                    }
                });
                hooked = true;
                break;
            } catch (Throwable t) {
                XposedBridge.log(TAG + " screen-off hook failed: " + t.getMessage());
            }
        }
        if (!hooked) XposedBridge.log(TAG + " BroadcastController.broadcastIntentLocked not hooked");
    }

    /** 找 broadcastIntentLocked 里 excludedPackages 那个 String[] 的下标（第三个 String[]）。 */
    private static int excludedPackagesIndex(Method method) {
        int seen = 0;
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == String[].class) {
                seen++;
                if (seen == 3) return i;
            }
        }
        return -1;
    }

    private static ArrayList<String> keptFloatPackages() {
        ArrayList<String> packages = new ArrayList<>(2);
        for (java.util.Map.Entry<Integer, WeakReference<Object>> entry : sTracked.entrySet()) {
            Object record = entry.getValue() == null ? null : entry.getValue().get();
            if (record == null) continue;
            if (!isFloating(entry.getKey().intValue())) continue;
            Object pkg = readField(record, "packageName");
            if (pkg instanceof String) {
                String name = (String) pkg;
                if (!packages.contains(name)) packages.add(name);
            }
        }
        return packages;
    }

    /**
     * 对保活中的浮标应用把 Display.getState() 报成 STATE_ON。
     *
     * 播放器常用 DisplayManager.getDisplayInfo(0).state 判断屏幕是否可用，
     * 熄屏时它是 STATE_OFF，于是不启动下一集。这里对保活应用返回一份 state=ON 的副本。
     */
    private static void installDisplayStateGuard(ClassLoader classLoader) {
        Class<?> binderClass = findClassSafe(classLoader, DISPLAY_BINDER_CLASS);
        if (binderClass == null) return;
        boolean hooked = hookAllSafe(binderClass, "getDisplayInfo", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!keepAliveEnabled()) return;
                    if (param.args.length != 1) return;
                    int taskId = keptFloatTaskOfUid(Binder.getCallingUid());
                    if (taskId <= 0) return;
                    Object info = param.getResult();
                    if (info == null) return;
                    Object state = readField(info, "state");
                    if (!(state instanceof Integer)) return;
                    int stateValue = ((Integer) state).intValue();
                    if (stateValue == DISPLAY_STATE_ON) return;
                    Object copy = copyDisplayInfo(info);
                    if (copy == null) return;
                    setIntField(copy, "state", DISPLAY_STATE_ON);
                    param.setResult(copy);
                    logOnce("display_" + taskId,
                            "task" + taskId + " display state " + stateValue + " -> ON");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " display state hook error: " + t.getMessage());
                }
            }
        });
        if (!hooked) XposedBridge.log(TAG + " getDisplayInfo not hooked");
    }

    /** DisplayInfo 有 (DisplayInfo other) 拷贝构造，用它复制一份，不改系统缓存里的对象。 */
    private static Object copyDisplayInfo(Object info) {
        try {
            Constructor<?> ctor = info.getClass().getDeclaredConstructor(info.getClass());
            ctor.setAccessible(true);
            return ctor.newInstance(info);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 对保活中的浮标应用回答「没有锁屏」。
     *
     * 很多播放器在换集前会走 KeyguardManager.isKeyguardLocked()，锁屏时直接跳过连播。
     * 服务端就是 WindowManagerService.isKeyguardLocked()，只对保活应用返回 false。
     */
    private static void installKeyguardLockGuard(ClassLoader classLoader) {
        Class<?> wmsClass = findClassSafe(classLoader, WMS_CLASS);
        if (wmsClass == null) return;
        boolean hooked = hookAllSafe(wmsClass, "isKeyguardLocked", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (!keepAliveEnabled()) return;
                    if (param.args.length != 0) return;
                    if (!Boolean.TRUE.equals(param.getResult())) return;
                    int taskId = keptFloatTaskOfUid(Binder.getCallingUid());
                    if (taskId <= 0) return;
                    param.setResult(Boolean.FALSE);
                    logOnce("keyguard_" + taskId, "task" + taskId + " keyguard reported unlocked");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " keyguard lock hook error: " + t.getMessage());
                }
            }
        });
        if (!hooked) XposedBridge.log(TAG + " WMS.isKeyguardLocked not hooked");
    }

    // ==================== 浮标期间的后台运行豁免（doze 白名单） ====================

    /**
     * 贴边浮标出现时给该应用加系统省电白名单（等于“允许完全后台运行”），离开浮标后撤销。
     *
     * 锁屏后应用仍在播，但换集要新建连接/解析新域名，会被打盹下的后台网络限制卡住，
     * 表现为“这一集播完就停、解锁立刻继续”。加入白名单（待机桶随之变成 EXEMPTED）后
     * 不再受限。只在保活期间生效，退出浮标即撤销，不留长期副作用。
     */
    private static void exemptFloat(Object record) {
        Object pkgObj = readField(record, "packageName");
        if (!(pkgObj instanceof String)) return;
        final String pkg = (String) pkgObj;
        if (pkg.isEmpty()) return;
        Object userIdObj = readField(record, "mUserId");
        final int userId = userIdObj instanceof Integer ? ((Integer) userIdObj).intValue() : 0;
        if (sExempted.putIfAbsent(pkg, Integer.valueOf(userId)) != null) {
            scheduleExemptSweep();
            return;
        }
        // 贴边这一刻的真实顶层 Activity 与 native so 目录，用来判定「这算不算游戏」
        Object info = readField(record, "info");
        Object appInfo = readField(info, "applicationInfo");
        Object classObj = readField(info, "name");
        final String topClass = classObj instanceof String ? (String) classObj : null;
        Object libObj = readField(appInfo, "nativeLibraryDir");
        final String libDir = libObj instanceof String ? (String) libObj : null;
        Object catObj = readField(appInfo, "category");
        final int category = catObj instanceof Integer ? ((Integer) catObj).intValue() : 0;
        sExemptExec.execute(new Runnable() {
            @Override
            public void run() {
                probeGame(pkg, topClass, libDir, category);
                try {
                    boolean ok = deviceIdleCall("addPowerSaveWhitelistApp", pkg);
                    XposedBridge.log(TAG + (ok ? " exempt " : " exempt failed ") + pkg);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " exempt error " + pkg + ": " + t.getMessage());
                }
            }
        });
        scheduleExemptSweep();
    }

    private static void scheduleExemptSweep() {
        if (!sSweepScheduled.compareAndSet(false, true)) return;
        Handler handler = sHandler;
        if (handler == null) {
            sSweepScheduled.set(false);
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                sSweepScheduled.set(false);
                sExemptExec.execute(new Runnable() {
                    @Override
                    public void run() {
                        sweepExemptions();
                    }
                });
            }
        }, EXEMPT_SWEEP_MS);
    }

    /**
     * 判定「这个包算不算游戏」，只打日志，不影响任何行为。
     *
     * 判据来自设备实测，不用 ColorOS 那套游戏识别（`GameManagerServiceExtImpl.isGamePkg`
     * 是云端包名表，且实测在 5200 上根本没被调用），也不用 `ApplicationInfo.category`
     * ——三家游戏实测都是 0（不是 CATEGORY_GAME）。真正稳定的是引擎 so：
     * 洛克王国 libUE4.so、全民农场 libcocos2dcpp.so、三国志 libejoysdk_*，
     * 而非游戏的抖音一个引擎 so 都没有。
     */
    private static void probeGame(String pkg, String topClass, String libDir, int category) {
        String engine = sEngineCache.get(pkg);
        if (engine == null) {
            engine = engineFromLib(libDir);
            if ("none".equals(engine)) engine = engineFromClass(topClass);
            // so 目录拿不到时先不缓存，下次贴边重新判，避免一次失败永久记成非游戏
            if (libDir != null) sEngineCache.put(pkg, engine);
        }
        XposedBridge.log(TAG + " probe " + pkg
                + " top=" + (topClass == null ? "-" : topClass)
                + " cat=" + category
                + " engine=" + engine
                + " => " + ("none".equals(engine) ? "APP" : "GAME"));
    }

    /** 引擎 so 判定：返回 ue / unity / cocos / ejoy，都没有则 none。 */
    private static String engineFromLib(String libDir) {
        String[] names;
        try {
            names = libDir == null || libDir.isEmpty() ? null : new File(libDir).list();
        } catch (Throwable t) {
            names = null;
        }
        if (names == null) return "none";
        for (String name : names) {
            if (contains(SO_UNREAL, name)) return "ue";
            if (contains(SO_UNITY, name)) return "unity";
            if (contains(SO_COCOS, name)) return "cocos";
            if (contains(SO_EJOY, name)) return "ejoy";
        }
        return "none";
    }

    /** so 目录列不出来时的兜底：引擎 Activity 类名前缀。 */
    private static String engineFromClass(String topClass) {
        if (topClass == null) return "none";
        if (topClass.startsWith("com.epicgames.ue4.")) return "ue";
        if (topClass.startsWith("com.ejoy2dx.")) return "ejoy";
        if (topClass.startsWith("com.unity3d.player.")) return "unity";
        if (topClass.startsWith("org.cocos2dx.")) return "cocos";
        for (String prefix : GAME_ACTIVITY_PREFIXES) {
            if (topClass.startsWith(prefix)) return "engine";
        }
        return "none";
    }

    private static boolean contains(String[] pool, String value) {
        if (value == null) return false;
        for (String item : pool) {
            if (item.equals(value)) return true;
        }
        return false;
    }

    private static void sweepExemptions() {
        // 顺手回收已经消失的 record，避免 taskId 长期堆积。
        for (java.util.Map.Entry<Integer, WeakReference<Object>> entry : sTracked.entrySet()) {
            if (entry.getValue() == null || entry.getValue().get() == null) {
                sTracked.remove(entry.getKey(), entry.getValue());
            }
        }
        HashSet<String> stillFloating = new HashSet<>();
        if (keepAliveEnabled()) {
            for (java.util.Map.Entry<Integer, WeakReference<Object>> entry : sTracked.entrySet()) {
                Object record = entry.getValue() == null ? null : entry.getValue().get();
                if (record == null) continue;
                if (!isFloating(entry.getKey().intValue())) continue;
                Object pkg = readField(record, "packageName");
                if (pkg instanceof String) stillFloating.add((String) pkg);
            }
        }
        for (String pkg : new ArrayList<>(sExempted.keySet())) {
            if (stillFloating.contains(pkg)) continue;
            sExempted.remove(pkg);
            try {
                boolean ok = deviceIdleCall("removePowerSaveWhitelistApp", pkg);
                XposedBridge.log(TAG + (ok ? " exempt off " : " exempt off failed ") + pkg);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " exempt off error " + pkg + ": " + t.getMessage());
            }
        }
        if (!sExempted.isEmpty()) scheduleExemptSweep();
    }

    /** 直接走 IDeviceIdleController（system_server 内可用，权限也够）。 */
    private static boolean deviceIdleCall(String method, String pkg) {
        try {
            Class<?> serviceManager = XposedHelpers.findClass("android.os.ServiceManager", null);
            Object binder = XposedHelpers.callStaticMethod(serviceManager, "getService", "deviceidle");
            if (!(binder instanceof IBinder)) return false;
            Class<?> stub = XposedHelpers.findClass("android.os.IDeviceIdleController$Stub", null);
            Object iface = XposedHelpers.callStaticMethod(stub, "asInterface", binder);
            if (iface == null) return false;
            XposedHelpers.callMethod(iface, method, pkg);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " deviceidle " + method + " failed: " + t.getMessage());
            return false;
        }
    }

    // ==================== 基础工具 ====================

    /** 只 hook 实际存在的方法；某个 ROM 缺签名时跳过而不是整体失败。 */
    private static boolean hookAllSafe(Class<?> type, String methodName, XC_MethodHook callback) {
        try {
            if (XposedBridge.hookAllMethods(type, methodName, callback).size() > 0) {
                return true;
            }
            XposedBridge.log(TAG + " method not found: " + type.getName() + "." + methodName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hook " + methodName + " failed: " + t.getMessage());
        }
        return false;
    }

    private static Class<?> findClassSafe(ClassLoader classLoader, String name) {
        try {
            return XposedHelpers.findClass(name, classLoader);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " class missing: " + name + " (" + t.getMessage() + ")");
            return null;
        }
    }

    private static void markPausing(Object fragment, Object record) {
        if (fragment == null || record == null) return;
        Class<?> type = fragment.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("mPausingActivity");
                field.setAccessible(true);
                field.set(fragment, record);
                return;
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
    }

    /**
     * 补一次 activityPaused（走服务端 handler），让 AMS 认为客户端已暂停，
     * 从而继续恢复下一个应用；客户端实际没收到 onPause。
     */
    private static boolean scheduleFakePause(Object record) {
        if (record == null) return false;
        Object atmService = readField(record, "mAtmService");
        if (atmService == null) return false;
        Object controller = readField(atmService, "mActivityClientController");
        if (controller == null) return false;
        Object handlerObj = readField(atmService, "mH");
        if (!(handlerObj instanceof Handler)) return false;
        IBinder token = findToken(record);
        if (token == null) return false;
        Method pausedMethod;
        try {
            pausedMethod = controller.getClass().getDeclaredMethod("activityPaused", IBinder.class);
            pausedMethod.setAccessible(true);
        } catch (Throwable t) {
            return false;
        }
        final Object target = controller;
        final Method method = pausedMethod;
        final IBinder finalToken = token;
        ((Handler) handlerObj).post(new Runnable() {
            @Override
            public void run() {
                try {
                    method.invoke(target, finalToken);
                    sPauseBypassActive = true;
                    Handler handler = sHandler;
                    if (handler != null) {
                        // 补完 activityPaused 之后服务端已经离开 PAUSING，超时就不该再被跳过，
                        // 这里短时间后复位，避免影响别的任务。
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                sPauseBypassActive = false;
                            }
                        }, 2000L);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " fake pause failed: " + t.getMessage());
                }
            }
        });
        return true;
    }

    /** 不同 ROM 的 token 字段名不同；按候选名探测，取第一个 IBinder。 */
    private static IBinder findToken(Object record) {
        for (String name : TOKEN_FIELDS) {
            Object value = readField(record, name);
            if (value instanceof IBinder) {
                return (IBinder) value;
            }
        }
        return null;
    }

    private static void resolveController(ClassLoader classLoader) {
        try {
            Class<?> type = XposedHelpers.findClass(CONTROLLER_CLASS, classLoader);
            sGetInstance = type.getDeclaredMethod("getInstance");
            sGetInstance.setAccessible(true);
            sIsInFloatingList = type.getDeclaredMethod("isInFloatingList", int.class);
            sIsInFloatingList.setAccessible(true);
        } catch (Throwable t) {
            sGetInstance = null;
            sIsInFloatingList = null;
            XposedBridge.log(TAG + " FloatHandleController unavailable: " + t.getMessage());
        }
    }

    /** 判断记录属于浮标任务；命中时顺手记下 record，供旋转/解锁/白名单使用。 */
    private static boolean shouldKeep(Object record) {
        if (record == null) return false;
        if (!keepAliveEnabled()) return false;
        try {
            if (Boolean.TRUE.equals(readField(record, "finishing"))) return false;
            int taskId = taskIdOf(record);
            // 已经进幻影屏的任务由 MirageWindowHook 自己管，这里让路，
            // 否则两边同时改状态会把任务又拉回主屏。
            if (MirageWindowHook.isHangUp(taskId)) return false;
            if (!isFloating(taskId)) return false;
            WeakReference<Object> ref = sTracked.get(taskId);
            if (ref == null || ref.get() != record) {
                sTracked.put(taskId, new WeakReference<>(record));
            }
            exemptFloat(record);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 目标 Activity 是否归类为游戏。
     *
     * 只认 ApplicationInfo.category：它在清单里声明，跟包名无关，换游戏也不用改代码。
     * 读不到就按非游戏处理（保守，维持原来的吞 pause 行为）。
     */
    private static boolean isGameRecord(Object record) {
        Object info = readField(record, "info");
        if (info == null) return false;
        Object applicationInfo = readField(info, "applicationInfo");
        if (applicationInfo == null) return false;
        Object category = readField(applicationInfo, "category");
        return category instanceof Integer && ((Integer) category).intValue() == CATEGORY_GAME;
    }

    /** 按调用 uid 找保活中的浮标 taskId；找不到返回 -1。 */
    private static int keptFloatTaskOfUid(int uid) {
        if (uid <= 0) return -1;
        for (java.util.Map.Entry<Integer, WeakReference<Object>> entry : sTracked.entrySet()) {
            Object record = entry.getValue() == null ? null : entry.getValue().get();
            if (record == null) continue;
            if (!isFloating(entry.getKey().intValue())) continue;
            Object value = callNoArg(record, "getUid");
            if (value instanceof Integer && ((Integer) value).intValue() == uid) {
                return entry.getKey().intValue();
            }
        }
        return -1;
    }

    private static boolean isRotationChange(Object oldArg, Object newArg) {
        if (oldArg == null || newArg == null) return false;
        Object oldConfig = readField(oldArg, "windowConfiguration");
        Object newConfig = readField(newArg, "windowConfiguration");
        if (oldConfig == null || newConfig == null) return false;
        Object oldRotation = readField(oldConfig, "mRotation");
        Object newRotation = readField(newConfig, "mRotation");
        if (!(oldRotation instanceof Integer) || !(newRotation instanceof Integer)) return false;
        return ((Integer) oldRotation).intValue() != ((Integer) newRotation).intValue();
    }

    private static boolean isFloating(int taskId) {
        if (taskId <= 0) return false;
        Method getInstance = sGetInstance;
        Method isInList = sIsInFloatingList;
        if (getInstance == null || isInList == null) return false;
        try {
            Object controller = getInstance.invoke(null);
            if (controller == null) return false;
            Object result = isInList.invoke(controller, Integer.valueOf(taskId));
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean keepAliveEnabled() {
        long now = SystemClock.uptimeMillis();
        if (now - sConfigReadAt < CONFIG_TTL_MS) return sEnabled;
        sConfigReadAt = now;
        try {
            XSharedPreferences prefs = new XSharedPreferences(ModulePrefs.PKG, ModulePrefs.NAME);
            // 读不到配置时保留上次的值，不要被默认值覆盖。
            if (!prefs.hasData()) return sEnabled;
            sEnabled = prefs.getBoolean(KEY_KEEP_ALIVE, true);
        } catch (Throwable t) {
            // 读不到时保留上次的值（模块进程未启动/文件不可读）。
        }
        return sEnabled;
    }

    private static Object callNoArg(Object instance, String name) {
        Object ignored = INSTANCE_MARKER;
        return callNoArg(instance, name, ignored);
    }

    private static Object callNoArg(Object instance, String name, Object arg) {
        if (instance == null) return null;
        Class<?> type = instance.getClass();
        while (type != null) {
            try {
                if (arg == INSTANCE_MARKER) {
                    Method method = type.getDeclaredMethod(name);
                    method.setAccessible(true);
                    return method.invoke(instance);
                }
                Method method = type.getDeclaredMethod(name, arg.getClass());
                method.setAccessible(true);
                return method.invoke(instance, arg);
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        // 参数类型不是 arg.getClass() 时（比如 boolean 形参），按名字 + 1 个参数兜底找一次
        if (arg != INSTANCE_MARKER) {
            type = instance.getClass();
            while (type != null) {
                for (Method method : type.getDeclaredMethods()) {
                    if (!name.equals(method.getName()) || method.getParameterCount() != 1) continue;
                    try {
                        method.setAccessible(true);
                        return method.invoke(instance, arg);
                    } catch (Throwable ignored) {
                    }
                }
                type = type.getSuperclass();
            }
        }
        return null;
    }

    /** callNoArg 的重载哨兵：区分「无参」与「一个参数」。 */
    private static final Object INSTANCE_MARKER = new Object();

    private static Object readField(Object instance, String name) {
        if (instance == null) return null;
        Class<?> type = instance.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance);
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    private static void setIntField(Object instance, String name, int value) {
        if (instance == null) return;
        Class<?> type = instance.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.setInt(instance, value);
                return;
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
    }

    private static int taskIdOf(Object record) {
        return taskIdOfObject(readField(record, "task") != null
                ? readField(record, "task") : readField(record, "mTask"));
    }

    private static int taskIdOfObject(Object task) {
        Object taskId = readField(task, "mTaskId");
        return taskId instanceof Integer ? ((Integer) taskId).intValue() : -1;
    }

    private static void logSkip(Object record, String reason) {
        int taskId = taskIdOf(record);
        // 按 taskId+reason 去重：同一个任务上 pause / invisible / visible_requested 都要各自留一行，
        // 否则第一条（pause）会把后面几条吞掉，出问题时看不到可见性那两道路过没有。
        if (sLoggedTasks.putIfAbsent(taskId + "/" + reason, Boolean.TRUE) == null) {
            XposedBridge.log(TAG + " keep task" + taskId + " (" + reason + ")");
        }
    }

    private static void logOnce(String key, String message) {
        if (sLogOnce.putIfAbsent(key, Boolean.TRUE) == null) {
            XposedBridge.log(TAG + " " + message);
        }
    }
}
