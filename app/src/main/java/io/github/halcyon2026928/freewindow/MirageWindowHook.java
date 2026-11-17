package io.github.halcyon2026928.freewindow;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 游戏贴边：接到 ColorOS 官方的后台串流（mirage 幻影屏）上继续跑。
 *
 * 建屏与拉起都交给官方一条链，一比一照 LuckyTool：
 * 组件
 *   OplusMirageOptions.makeBackgroundStreamModeOptions().toBundle()
 *   → OplusMirageDisplayManagerService.startMirageWindowMode(intent, bundle)
 * 里面先 createDisplayDevice，再把 intent 放进 mPendingIntents；屏就绪后
 * onMirageDisplayAdded 取到 pending intent，调 startActivityToMirageDisplay 把应用拉进屏。
 * 我们自己只做 attachDisplay 记账和 300ms 后的 resumeTask，不搬任务、不自己 startActivity。
 *
 * 分流：只有引擎认得出是游戏的包才挂机，其它应用照旧走 mini 保活。
 */
final class MirageWindowHook {

    private static final String TAG = "[灵动坞OS-hangup]";

    private static final String FLEXIBLE_DCS_CLASS = "com.android.server.wm.OplusFlexibleDCSManager";
    private static final String MIRAGE_WMS_CLASS = "com.android.server.wm.OplusMirageWindowManagerService";
    private static final String MIRAGE_STATE_MONITOR_CLASS = "com.android.server.wm.OplusMirageWindowStateMonitor";
    private static final String FLEXIBLE_CONTROLLER_CLASS = "com.android.server.wm.FlexibleTaskController";
    private static final String FLOAT_CONTROLLER_CLASS = "com.android.server.wm.FloatHandleController";
    private static final String MIRAGE_DISPLAY_CLASS =
            "com.android.server.display.OplusMirageDisplayManagerService";
    private static final String MIRAGE_KEEP_ALIVE_POLICY_CLASS =
            "com.android.server.wm.OplusMirageKeepAlivePolicy";
    private static final String MIRAGE_OPTIONS_CLASS = "com.oplus.miragewindow.OplusMirageOptions";
    /** ColorOS 17 退后台/贴边走标准 ATMS 路径，不再走 OplusFlexibleDCSManager.startMinimize。 */

    /** 建屏 Bundle 里的 castMode 键；4 = 后台串流（挂机用），3 = 应用分享，5 = 车机。 */
    private static final String KEY_MIRAGE_CAST_MODE = "mirage:castmode";
    private static final int CAST_MODE_BACKGROUND_STREAM = 4;
    /** 原厂启动标记：无转场（缺了会在切应用时闪一下）与来自幻影屏启动。 */

    private static final String TASK_CLASS = "com.android.server.wm.Task";
    private static final String TASKINFO_UID = "TASKINFO_UID";

    /** 任务最小化后系统会立刻删悬浮球，这段时间压住删除，等幻影屏起来。 */
    private static final long KEEP_FLOAT_MS = 2800L;
    /** 同一任务在该窗口内的重复最小化回调直接忽略。 */
    private static final long MINIMIZE_DEDUPE_MS = 3000L;
    /** 贴边后立刻建屏：拉起交给系统内部的 exitZoomWindowToBackIfNeeded，不会再被转场收回。 */
    private static final long MIRAGE_START_DELAY_MS = 0L;
    /** 建屏后立刻补一次顶层恢复，缩短声音中断的空档。 */
    private static final long MIRAGE_RESUME_DELAY_MS = 0L;
    /** 恢复后等宿主收尾，确认它没释放才由我们兜底释放虚拟屏。 */
    private static final long RESTORE_RELEASE_DELAY_MS = 1200L;
    /** 挂起任务消失后的巡检间隔，只在有挂起任务时才会排队。 */
    private static final long REAP_INTERVAL_MS = 5000L;
    /** 模块加载后先等系统就绪，再扫一遍无人使用的后台串流屏。 */
    private static final long ORPHAN_SWEEP_DELAY_MS = 3000L;
    /** 系统为进屏主动退出小窗时，这段窗口内放行 moveTaskToBack（转场约几百毫秒，留足余量）。 */
    private static final long ALLOW_MOVE_BACK_MS = 2000L;

    private static volatile long sAllowMoveBackUntil = 0L;
    /** delayedRunUntilExitFlexibleMode 里被推迟的 startActivity runnable，按任务暂存。 */
    private static final java.util.Map<Integer, Runnable> sDeferredStart = new java.util.concurrent.ConcurrentHashMap<>();
    /** 已提前执行过的 runnable 的 identity 值，5 秒兜底再跑时拦掉。 */
    private static final java.util.Set<Integer> sRanStarts = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 任务上的保活标记：各机型只实现其中几个，逐个尝试。 */
    private static final String[] LIVELY_FLAGS = {
            "setLively", "setMaintainTaskState", "setSceenOffPlay",
            "setSkipMoveTaskToBack", "setIgnoreHiddenFlag",
            "setInsensibleFlag", "dontPauseAfterQActivityIfNeed"
    };

    /** 挂起中的任务集合：每个任务各自一块幻影屏，互不覆盖（LuckyTool 那套单槽位会让第二个把第一个顶掉）。 */
    private static final Set<Integer> sHangUpTasks = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, Integer> sDisplays = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> sMinimizeAt = new ConcurrentHashMap<>();
    private static final Set<Integer> sReleasedDisplays = ConcurrentHashMap.newKeySet();
    /** 任务消失巡检是否已排队。 */
    private static volatile boolean sReapScheduled = false;
    /** 悬浮球保留窗口按任务各记一份，一个任务贴边不会影响另一个的窗口。 */
    private static final Map<Integer, Long> sKeepFloatUntil = new ConcurrentHashMap<>();

    private static Handler sMain;
    private static ClassLoader sClassLoader;

    private MirageWindowHook() {
    }

    static void hook(ClassLoader classLoader) {
        sClassLoader = classLoader;
        sMain = new Handler(Looper.getMainLooper());
        hookMinimize();
        hookMoveTaskToBack();
        hookFlexibleExit();
        hookFlexibleExitDone();
        hookKeepFloatHandle();
        hookReleaseOnRestore();
        hookSelfHeal();
        hookRestoreFromPrimary();
        scheduleOrphanSweep();
    }

    /**
     * 上一次运行留下的后台串流屏（比如 system_server 重启前正在挂机的那块）本进程没有记录，
     * 巡检管不到它们，会一直留在系统里。模块加载完扫一遍，只收 castMode=4 且没有记录的。
     */
    private static void scheduleOrphanSweep() {
        Handler handler = sMain;
        if (handler == null) return;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                sweepOrphanDisplays();
            }
        }, ORPHAN_SWEEP_DELAY_MS);
    }

    private static void sweepOrphanDisplays() {
        try {
            Object service = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(MIRAGE_DISPLAY_CLASS, sClassLoader), "getInstance");
            if (service == null) return;
            Object thread = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", sClassLoader),
                    "currentActivityThread");
            Object context = XposedHelpers.callMethod(thread, "getSystemContext");
            Object displayManager = XposedHelpers.callMethod(context, "getSystemService", "display");
            Object displays = XposedHelpers.callMethod(displayManager, "getDisplays");
            if (!(displays instanceof Object[])) return;
            int swept = 0;
            for (Object display : (Object[]) displays) {
                Object idValue = XposedHelpers.callMethod(display, "getDisplayId");
                if (!(idValue instanceof Integer)) continue;
                int displayId = ((Integer) idValue).intValue();
                Object castMode = XposedHelpers.callMethod(service, "getMirageDisplayCastMode", displayId);
                if (!(castMode instanceof Integer)
                        || ((Integer) castMode).intValue() != CAST_MODE_BACKGROUND_STREAM) {
                    continue;
                }
                if (displayOfAnyTask(displayId)) continue;
                XposedHelpers.callMethod(service, "releaseVirtualDisplayDevice", displayId);
                forgetDisplay(displayId);
                swept++;
            }
            if (swept > 0) {
                XposedBridge.log(TAG + " swept " + swept + " orphan mirage display(s)");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " sweepOrphanDisplays failed: " + t);
        }
    }

    /** 这块屏上是否还有我们记录的挂起任务，有就不能收。 */
    private static boolean displayOfAnyTask(int displayId) {
        for (Map.Entry<Integer, Integer> entry : sDisplays.entrySet()) {
            if (entry.getValue().intValue() == displayId && taskAlive(entry.getKey())) return true;
        }
        return false;
    }

    /** 坞窗还原时由 MainHook 调进来：清这个任务的挂机态，并释放它那块屏。 */
    static void clearHangUp(int taskId) {
        if (taskId < 0) return;
        boolean wasHangUp = sHangUpTasks.remove(taskId);
        sKeepFloatUntil.remove(taskId);
        Integer displayId = sDisplays.remove(taskId);
        int id = displayId == null ? 0 : displayId.intValue();
        XposedBridge.log(TAG + " hangUp cleared taskId=" + taskId + " displayId=" + id
                + " wasHangUp=" + wasHangUp + " left=" + sHangUpTasks.size());
        releaseAfterRestore(id);
    }

    static boolean isHangUp(int taskId) {
        return taskId >= 0 && sHangUpTasks.contains(taskId);
    }

    // ==================== 挂起入口 ====================

    private static void hookMinimize() {
        try {
            Class<?> dcs = XposedHelpers.findClass(FLEXIBLE_DCS_CLASS, sClassLoader);
            XposedBridge.hookAllMethods(dcs, "startMinimize", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        // 原生最小化成功后再把它接到后台串流上（LuckyTool 的入口）
                        onTaskMinimized(param.args);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " handle minimize failed: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + " hooked startMinimize");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookMinimize failed: " + t);
        }
    }

    private static void onTaskMinimized(Object[] args) {
        Object task = findTask(args);
        if (task == null) {
            XposedBridge.log(TAG + " startMinimize without Task, skip");
            return;
        }
        int taskId = taskIdOf(task);
        if (taskId < 0) return;
        if (displayOf(taskId) > 0) {
            XposedBridge.log(TAG + " task " + taskId + " already hanging up");
            return;
        }
        if (!shouldHandleMinimize(taskId)) return;

        Intent baseIntent = baseIntentOf(task);
        if (baseIntent == null) {
            XposedBridge.log(TAG + " base intent null, skip taskId=" + taskId);
            return;
        }
        // 分流：引擎认不出是游戏的，维持原来的 mini 保活
        if (!FloatKeepAliveHook.isGamePackage(baseIntent)) {
            return;
        }
        int userId = userIdOf(task, args);
        if (userId >= 0) baseIntent.putExtra(TASKINFO_UID, userId);

        sHangUpTasks.add(taskId);
        armKeepFloat(taskId);
        boolean lively = keepTaskLively(task);
        XposedBridge.log(TAG + " hangUp taskId=" + taskId + " userId=" + userId
                + " pkg=" + pkgOf(baseIntent) + " lively=" + lively);

        final int id = taskId;
        final Object target = task;
        final Intent intent = baseIntent;
        Handler handler = sMain;
        if (handler == null) return;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startMirage(id, target, intent);
            }
        }, MIRAGE_START_DELAY_MS);
    }

    /**
     * 后台挂起：建屏并把启动意图登记给系统，由系统在 onMirageDisplayAdded 里把任务搬进屏。
     *
     * 一比一照 LuckyTool 的 startMirage：只调 startMirageWindowMode(intent, options)，不自己搬任务、
     * 不拦官方的 startActivityToMirageDisplay。自己搬在这台机器上要么撞 Unknown displayId，
     * 要么和官方拉起撞车，留下一个藏主屏的挂起任务 + 一个空屏，清任务要清两次。
     */
    private static void startMirage(final int taskId, final Object task, final Intent baseIntent) {
        int displayId = 0;
        try {
            displayId = createMirageDisplay(baseIntent);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " createMirageDisplay failed: " + t);
        }
        if (displayId <= 0) {
            XposedBridge.log(TAG + " startMirageWindowMode returned " + displayId + ", skip taskId=" + taskId);
            return;
        }
        attachDisplay(taskId, displayId);
        XposedBridge.log(TAG + " startMirage taskId=" + taskId + " displayId=" + displayId);

        if (task == null) return;
        Handler handler = sMain;
        if (handler == null) return;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                resumeTask(taskId, task);
            }
        }, MIRAGE_RESUME_DELAY_MS);
    }

    /**
     * 建一块后台串流虚拟屏（castMode=4），并把启动意图一起登记给系统。
     *
     * 必须走官方 startMirageWindowMode(intent, options)：它内部 createDisplayDevice 之后会把
     * 这个 intent 放进 mPendingIntents，onMirageDisplayAdded 才拿得到 pending 去拉起。
     * 只调 createVirtualDisplayDevice 会跳过 pending 登记，系统那一步拿不到 intent，
     * 退去 moveTargetTaskToMirageDisplay，本机 policy 不允许 → notifyCastFailed → destroyDisplay，
     * 屏被销毁后任务被搬回主屏（日志里的 stacks moved back），后面再启动就是 Permission Denial。
     */
    private static int createMirageDisplay(Intent baseIntent) {
        Class<?> optionsClass = XposedHelpers.findClass(MIRAGE_OPTIONS_CLASS, sClassLoader);
        Object options = XposedHelpers.callStaticMethod(optionsClass, "makeBackgroundStreamModeOptions");
        Bundle bundle = (Bundle) XposedHelpers.callMethod(options, "toBundle");
        if (bundle == null) return 0;
        if (!bundle.containsKey(KEY_MIRAGE_CAST_MODE)) {
            bundle.putInt(KEY_MIRAGE_CAST_MODE, CAST_MODE_BACKGROUND_STREAM);
        }
        Object manager = XposedHelpers.callStaticMethod(
                XposedHelpers.findClass(MIRAGE_DISPLAY_CLASS, sClassLoader), "getInstance");
        if (manager == null) return 0;
        Object result = XposedHelpers.callMethod(manager, "startMirageWindowMode",
                baseIntent == null ? null : new Intent(baseIntent), bundle);
        return result instanceof Integer ? ((Integer) result).intValue() : 0;
    }

    /** 搬到虚拟屏的任务仍是暂停态，补一次顶层活动恢复（LuckyTool 的 resumeTask）。 */
    private static void resumeTask(int taskId, Object task) {
        if (task == null) return;
        try {
            Object resumed = XposedHelpers.callMethod(task, "resumeTopActivityUncheckedLocked");
            XposedBridge.log(TAG + " resume taskId=" + taskId + " result=" + resumed);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " resume taskId=" + taskId + " failed: " + t);
        }
    }

    // ==================== 挂起期间的拦阻 ====================

    /**
     * 系统为把任务接到幻影屏，会先主动把任务退出小窗（ExitFlexibleTaskToBackStrategy），
     * 转场结束后 onFlexibleTaskTransitionEnd 立刻 startActivity；转场收不了尾就只能等 5 秒兜底。
     * 这次退后台必须放行，否则就是我们自己把进屏时间拖成 5 秒。
     *
     * 同时把系统推迟的 startActivity runnable 暂存起来，一拿到就立刻替系统执行（此时屏已 ready、
     * 游戏还没被暂停，声音不断）。5 秒兜底里再跑的那次用 identity 去重拦掉。
     */
    private static void hookFlexibleExit() {
        try {
            Class<?> monitor = XposedHelpers.findClass(MIRAGE_STATE_MONITOR_CLASS, sClassLoader);
            XposedBridge.hookAllMethods(monitor, "delayedRunUntilExitFlexibleMode", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sAllowMoveBackUntil = SystemClock.uptimeMillis() + ALLOW_MOVE_BACK_MS;
                    Object task = param.args.length > 0 ? param.args[0] : null;
                    Object runnable = param.args.length > 1 ? param.args[1] : null;
                    int taskId = taskIdOf(task);
                    if (taskId >= 0 && runnable instanceof Runnable) {
                        sDeferredStart.put(taskId, (Runnable) runnable);
                        XposedBridge.log(TAG + " defer mirage start taskId=" + taskId);
                    }
                    XposedBridge.log(TAG + " allow moveTaskToBack for mirage entry");
                }
            });
            // 5 秒兜底跑同一个 runnable 时，已由我们提前执行过的那次拦掉，避免重复拉起。
            XposedBridge.hookAllMethods(
                    XposedHelpers.findClass(MIRAGE_STATE_MONITOR_CLASS + "$MirageMonitorRunnable", sClassLoader),
                    "run", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Object inner = XposedHelpers.getObjectField(param.thisObject, "mRunnable");
                            if (inner != null && sRanStarts.contains(System.identityHashCode(inner))) {
                                XposedBridge.log(TAG + " skip duplicated mirage start");
                                param.setResult(null);
                            }
                        }
                    });
            XposedBridge.log(TAG + " hooked flexible exit");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookFlexibleExit failed: " + t);
        }
    }

    /**
     * 执行被推迟的幻影屏拉起 runnable：每个 runnable 只执行一次（identity 去重），
     * 执行统一 post 到主线程、脱离 WM 锁。
     */
    private static void fireDeferredStart(int taskId, final Runnable runnable, long delayMs) {
        if (!sRanStarts.add(System.identityHashCode(runnable))) {
            XposedBridge.log(TAG + " mirage start already fired, skip taskId=" + taskId);
            return;
        }
        XposedBridge.log(TAG + " fire deferred mirage start taskId=" + taskId + " delay=" + delayMs);
        Handler handler = sMain;
        if (handler == null) return;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    runnable.run();
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " deferred mirage start failed: " + t);
                }
            }
        }, delayMs);
    }

    /** 任务真正退出小窗（exitFlexibleTask 返回）后立刻拉起，作为「一拿到就执行」失败时的兜底时机。 */
    private static void hookFlexibleExitDone() {
        try {
            Class<?> controller = XposedHelpers.findClass(FLEXIBLE_CONTROLLER_CLASS, sClassLoader);
            XposedBridge.hookAllMethods(controller, "exitFlexibleTask", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object task = findTask(param.args);
                        int taskId = taskIdOf(task);
                        final Runnable runnable = sDeferredStart.get(taskId);
                        if (runnable == null) return;
                        fireDeferredStart(taskId, runnable, 0L);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " exit flexible done failed: " + t);
                    }
                }
            });
            XposedBridge.log(TAG + " hooked exitFlexibleTask");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookFlexibleExitDone failed: " + t);
        }
    }

    private static void hookMoveTaskToBack() {
        try {
            Class<?> mws = XposedHelpers.findClass(MIRAGE_WMS_CLASS, sClassLoader);
            XposedBridge.hookAllMethods(mws, "moveTaskToBack", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object task = param.args.length > 0 ? param.args[0] : null;
                    int taskId = taskIdOf(task);
                    if (!isHangUp(taskId)) return;
                    if (SystemClock.uptimeMillis() <= sAllowMoveBackUntil) {
                        XposedBridge.log(TAG + " pass moveTaskToBack taskId=" + taskId + " (mirage entry)");
                        return;
                    }
                    XposedBridge.log(TAG + " block moveTaskToBack taskId=" + taskId);
                    param.setResult(null);
                }
            });
            XposedBridge.log(TAG + " hooked moveTaskToBack");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookMoveTaskToBack failed: " + t);
        }
    }

    private static void hookKeepFloatHandle() {
        try {
            Class<?> controller = XposedHelpers.findClass(FLOAT_CONTROLLER_CLASS, sClassLoader);
            XposedBridge.hookAllMethods(controller, "removeFloatHandle", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!shouldKeepFloat(param.args)) return;
                    XposedBridge.log(TAG + " keep float handle");
                    param.setResult(null);
                }
            });
            XposedBridge.hookAllMethods(controller, "isNeedRemoveFloat", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!shouldKeepFloat(param.args)) return;
                    param.setResult(null);
                }
            });
            XposedBridge.log(TAG + " hooked keep float handle");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookKeepFloatHandle failed: " + t);
        }
    }

    // ==================== 恢复 ====================

    private static void hookReleaseOnRestore() {
        try {
            Class<?> controller = XposedHelpers.findClass(FLOAT_CONTROLLER_CLASS, sClassLoader);
            XposedBridge.hookAllMethods(controller, "startActivityByFloatInfo", new XC_MethodHook() {
                private int displayId;

                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    int taskId = taskIdFromArgs(param.args);
                    displayId = clearHangUpValue(taskId);
                    if (taskId >= 0) {
                        XposedBridge.log(TAG + " restore from float info taskId=" + taskId
                                + " displayId=" + displayId);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    releaseAfterRestore(displayId);
                }
            });
            XposedBridge.hookAllMethods(controller, "removeFloatHandleInner", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!shouldKeepFloat(param.args)) return;
                    XposedBridge.log(TAG + " keep float handle inner");
                    param.setResult(null);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int displayId = takeDisplay(taskIdFromArgs(param.args));
                    if (displayId > 0) {
                        XposedBridge.log(TAG + " float handle inner removed, displayId=" + displayId);
                        releaseAfterRestore(displayId);
                    }
                }
            });
            XposedBridge.log(TAG + " hooked release on restore");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookReleaseOnRestore failed: " + t);
        }
    }

    /**
     * 自愈：宿主自己释放了虚拟屏、或者把任务搬回主屏之后，挂起态已经结束，
     * 不清掉残留状态的话，同一个任务再次贴边会被判成「已在挂起中」永远跳过。
     */
    private static void hookSelfHeal() {
        try {
            Class<?> display = XposedHelpers.findClass(MIRAGE_DISPLAY_CLASS, sClassLoader);
            XposedBridge.hookAllMethods(display, "releaseVirtualDisplayDevice", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int displayId = param.args.length > 0 && param.args[0] instanceof Integer
                            ? ((Integer) param.args[0]).intValue() : -1;
                    if (displayId > 0) {
                        sReleasedDisplays.add(displayId);
                        onDisplayReleased(displayId);
                    }
                }
            });
            Class<?> mws = XposedHelpers.findClass(MIRAGE_WMS_CLASS, sClassLoader);
            XposedBridge.hookAllMethods(mws, "moveStacksToDefaultDisplay", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    onStacksMovedBack(param.args);
                }
            });
            XposedBridge.log(TAG + " hooked self heal");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookSelfHeal failed: " + t);
        }
    }

    /** 主屏入口把挂起中的任务拉回来时，挂起态结束。 */
    private static void hookRestoreFromPrimary() {
        try {
            Class<?> policy = XposedHelpers.findClass(MIRAGE_KEEP_ALIVE_POLICY_CLASS, sClassLoader);
            XposedBridge.hookAllMethods(policy, "onStartFromPrimaryScreenRecents", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int taskId = taskIdFromArgs(param.args);
                    if (taskId < 0 || !isHangUp(taskId)) return;
                    XposedBridge.log(TAG + " restore from primary taskId=" + taskId);
                    clearHangUp(taskId);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hookRestoreFromPrimary failed: " + t);
        }
    }

    /** 恢复后释放虚拟屏：先等宿主收尾，确认它没释放我们才兜底，避免重复释放。 */
    private static void releaseAfterRestore(final int displayId) {
        if (displayId <= 0) return;
        Handler handler = sMain;
        if (handler == null) return;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (sReleasedDisplays.contains(displayId)) {
                    XposedBridge.log(TAG + " display" + displayId + " released by host, skip");
                    forgetDisplay(displayId);
                    return;
                }
                try {
                    Class<?> displayClass = XposedHelpers.findClass(MIRAGE_DISPLAY_CLASS, sClassLoader);
                    Object manager = XposedHelpers.callStaticMethod(displayClass, "getInstance");
                    if (manager != null) {
                        XposedHelpers.callMethod(manager, "releaseVirtualDisplayDevice", displayId);
                    }
                    XposedBridge.log(TAG + " release display" + displayId);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " release display" + displayId + " failed: " + t);
                }
                forgetDisplay(displayId);
            }
        }, RESTORE_RELEASE_DELAY_MS);
    }

    /** 屏处理完之后把它从记账里去掉，避免集合无限增长、也避免 displayId 被复用时误判。 */
    private static void forgetDisplay(int displayId) {
        sReleasedDisplays.remove(displayId);
    }

    // ==================== 保活标记 ====================

    /**
     * getWrapper -> getExtImpl -> 逐个试保活标记。
     * 同名标记在不同机型上的参数形态不同（boolean 开关 / int 标记位 / 直接传 Task），
     * 三种形态各试一次，命中的才真正调用。
     */
    private static boolean keepTaskLively(Object task) {
        if (task == null) return false;
        Object ext;
        try {
            Object wrapper = task;
            try {
                wrapper = XposedHelpers.callMethod(task, "getWrapper");
            } catch (Throwable ignored) {
            }
            ext = XposedHelpers.callMethod(wrapper, "getExtImpl");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " get task ext failed: " + t);
            return false;
        }
        List<String> applied = new ArrayList<>();
        for (String name : LIVELY_FLAGS) {
            if (invokeLivelyFlag(ext, name, task, true)) applied.add(name);
        }
        if (!applied.isEmpty()) {
            XposedBridge.log(TAG + " lively " + joinNames(applied));
        }
        return !applied.isEmpty();
    }

    private static boolean invokeLivelyFlag(Object ext, String name, Object task, boolean enabled) {
        Class<?> type = ext.getClass();
        while (type != null) {
            for (Method method : type.getDeclaredMethods()) {
                if (!name.equals(method.getName()) || method.getParameterCount() != 1) continue;
                Class<?> param = method.getParameterTypes()[0];
                try {
                    method.setAccessible(true);
                    if (param == boolean.class) {
                        method.invoke(ext, enabled);
                        return true;
                    }
                    if (param == int.class) {
                        method.invoke(ext, enabled ? 1 : 0);
                        return true;
                    }
                    if (task != null && param.isInstance(task)) {
                        method.invoke(ext, task);
                        return true;
                    }
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static String joinNames(List<String> names) {
        StringBuilder builder = new StringBuilder();
        for (String name : names) {
            if (builder.length() > 0) builder.append('/');
            builder.append(name);
        }
        return builder.toString();
    }

    // ==================== 状态 ====================

    private static boolean shouldHandleMinimize(int taskId) {
        long now = SystemClock.uptimeMillis();
        Long last = sMinimizeAt.get(taskId);
        if (last != null && now - last < MINIMIZE_DEDUPE_MS) return false;
        sMinimizeAt.put(taskId, now);
        return true;
    }

    private static void armKeepFloat(int taskId) {
        if (taskId < 0) return;
        sKeepFloatUntil.put(taskId, SystemClock.uptimeMillis() + KEEP_FLOAT_MS);
    }

    private static void attachDisplay(int taskId, int displayId) {
        if (taskId < 0 || displayId <= 0) return;
        sDisplays.put(taskId, displayId);
        scheduleReap();
    }

    /**
     * 巡检：挂着挂起态的任务如果已经从窗口管理器里消失（用户把游戏划掉 / 进程被清理），
     * 它那块幻影屏就没用了，必须收掉——否则每次退出再贴边都会新建一块屏，越堆越多。
     * 只在存在挂起任务时才排下一次，平时不占任何定时器。
     */
    private static void scheduleReap() {
        Handler handler = sMain;
        if (handler == null || sReapScheduled) return;
        sReapScheduled = true;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                sReapScheduled = false;
                reapDeadTasks();
            }
        }, REAP_INTERVAL_MS);
    }

    private static void reapDeadTasks() {
        if (sDisplays.isEmpty()) return;
        List<Integer> dead = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : sDisplays.entrySet()) {
            if (!taskAlive(entry.getKey())) dead.add(entry.getKey());
        }
        for (Integer taskId : dead) {
            Integer displayId = sDisplays.remove(taskId);
            sHangUpTasks.remove(taskId);
            sKeepFloatUntil.remove(taskId);
            XposedBridge.log(TAG + " reap dead taskId=" + taskId + " displayId=" + displayId);
            if (displayId != null) releaseAfterRestore(displayId.intValue());
        }
        if (!sDisplays.isEmpty()) scheduleReap();
    }

    /** 这个任务是否还活在窗口管理器里；查不到就当活着，避免误收正在用的屏。 */
    private static boolean taskAlive(int taskId) {
        try {
            Object service = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(MIRAGE_WMS_CLASS, sClassLoader), "getInstance");
            Object atms = XposedHelpers.getObjectField(service, "mAtms");
            Object root = XposedHelpers.getObjectField(atms, "mRootWindowContainer");
            return XposedHelpers.callMethod(root, "getRootTask", taskId) != null;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static int displayOf(int taskId) {
        Integer value = sDisplays.get(taskId);
        return value == null ? 0 : value.intValue();
    }

    private static int takeDisplay(int taskId) {
        if (taskId < 0) return 0;
        Integer value = sDisplays.remove(taskId);
        return value == null ? 0 : value.intValue();
    }

    /** 清挂起标记并返回要释放的 displayId（0 = 无需释放）。 */
    private static int clearHangUpValue(int taskId) {
        if (taskId < 0) return 0;
        sHangUpTasks.remove(taskId);
        sKeepFloatUntil.remove(taskId);
        return takeDisplay(taskId);
    }

    private static void onDisplayReleased(int displayId) {
        List<Integer> tasks = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : sDisplays.entrySet()) {
            if (entry.getValue().intValue() == displayId) tasks.add(entry.getKey());
        }
        for (Integer task : tasks) {
            sDisplays.remove(task);
            sHangUpTasks.remove(task);
            sKeepFloatUntil.remove(task);
        }
        forgetDisplay(displayId);
        if (!tasks.isEmpty()) {
            XposedBridge.log(TAG + " display" + displayId + " released, cleared " + tasks.size() + " task(s)");
        }
    }

    /**
     * 系统把某一块幻影屏上的任务搬回主屏后，只清掉这次真正被搬走的那些任务。
     *
     * 恢复其中一个挂机任务时，系统会销毁那块屏并调用本方法，参数就是这块屏上的任务 id 集合。
     * 之前在这里无差别清空全部状态，会把其它仍在挂起的任务一起抹掉 —— 它们随即失去保活、
     * 进程被杀，悬浮球和挂机一起消失（日志里 `stacks moved back, cleared 2 task(s)` 那次
     * 就是这样把还在 10000 上跑的洛克王国一起丢掉的）。
     */
    private static void onStacksMovedBack(Object[] args) {
        List<Integer> moved = taskIdsOfCollection(args);
        if (moved.isEmpty()) {
            int count = sDisplays.size();
            sDisplays.clear();
            sHangUpTasks.clear();
            sKeepFloatUntil.clear();
            if (count > 0) {
                XposedBridge.log(TAG + " stacks moved back, cleared " + count + " task(s), no task list");
            }
            return;
        }
        int cleared = 0;
        for (Integer taskId : moved) {
            if (sDisplays.remove(taskId) != null || sHangUpTasks.remove(taskId)) {
                sKeepFloatUntil.remove(taskId);
                cleared++;
            }
        }
        if (cleared > 0) {
            XposedBridge.log(TAG + " stacks moved back, cleared " + cleared + " task(s) " + moved
                    + ", still hanging " + sDisplays.size());
        }
    }

    /** 从参数里取任务 id 集合：moveStacksToDefaultDisplay 收的是 HashSet&lt;Integer&gt;。 */
    private static List<Integer> taskIdsOfCollection(Object[] args) {
        List<Integer> ids = new ArrayList<>();
        if (args == null) return ids;
        for (Object arg : args) {
            if (arg instanceof Collection) {
                for (Object item : (Collection<?>) arg) {
                    if (item instanceof Integer) ids.add((Integer) item);
                }
            } else if (arg instanceof int[]) {
                for (int value : (int[]) arg) ids.add(value);
            }
        }
        return ids;
    }

    /** 悬浮球保留窗口内命中挂起任务时，压住这次删除。 */
    private static boolean shouldKeepFloat(Object[] args) {
        if (args == null) return false;
        long now = SystemClock.uptimeMillis();
        int argTaskId = -1;
        for (Object arg : args) {
            int id = taskIdOf(arg);
            if (id >= 0) {
                argTaskId = id;
                break;
            }
            if (arg instanceof Integer) {
                argTaskId = ((Integer) arg).intValue();
                break;
            }
        }
        boolean keep;
        if (argTaskId >= 0) {
            // 挂起期间浮标必须常驻：本机宿主在贴边 5 秒后还会再删一次，
            // 只靠 LuckyTool 的 2.8 秒窗口压不住，结果就是后台还在、浮标没了
            // （日志：keep float check taskId=20938 keep=false hangUp=true）。
            Long until = sKeepFloatUntil.get(argTaskId);
            keep = isHangUp(argTaskId) || (until != null && now <= until);
        } else {
            // 参数里取不到 taskId 时，只要还有任务在保留窗口内就压住，避免防盗窗被误删
            keep = false;
            for (Long until : sKeepFloatUntil.values()) {
                if (now <= until) {
                    keep = true;
                    break;
                }
            }
        }
        // 诊断：浮标被删时到底匹配上了没有，参数是什么形态
        XposedBridge.log(TAG + " keep float check taskId=" + argTaskId + " keep=" + keep
                + " hangUp=" + (argTaskId >= 0 && isHangUp(argTaskId))
                + " args=" + describeArgs(args));
        return keep;
    }

    private static String describeArgs(Object[] args) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            Object arg = args[i];
            sb.append(arg == null ? "null" : arg.getClass().getSimpleName());
        }
        return sb.append(']').toString();
    }

    // ==================== 取值 ====================

    private static Object findTask(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg != null && TASK_CLASS.equals(arg.getClass().getName())) return arg;
        }
        return null;
    }

    private static int taskIdFromArgs(Object[] args) {
        if (args == null) return -1;
        for (Object arg : args) {
            int taskId = taskIdOf(arg);
            if (taskId >= 0) return taskId;
            if (arg != null && arg.getClass().getName().contains("FloatHandleInfo")) {
                try {
                    return XposedHelpers.getIntField(arg, "taskId");
                } catch (Throwable ignored) {
                }
            }
        }
        return -1;
    }

    private static int taskIdOf(Object obj) {
        if (obj == null) return -1;
        String name = obj.getClass().getName();
        if (!TASK_CLASS.equals(name) && !name.contains("FloatHandleInfo")
                && !name.contains("ZoomTaskInfo")) {
            return -1;
        }
        for (String field : new String[]{"mTaskId", "taskId"}) {
            Object value = XposedHelpers.getObjectField(obj, field);
            if (value instanceof Integer) return ((Integer) value).intValue();
        }
        return -1;
    }

    private static int userIdOf(Object task, Object[] args) {
        if (args != null) {
            for (Object arg : args) {
                if (arg == null || TASK_CLASS.equals(arg.getClass().getName())) continue;
                for (String field : new String[]{"userId", "mUserId", "uid"}) {
                    Object value = XposedHelpers.getObjectField(arg, field);
                    if (value instanceof Integer && ((Integer) value).intValue() >= 0) {
                        return ((Integer) value).intValue();
                    }
                }
            }
        }
        if (task != null) {
            Object value = XposedHelpers.getObjectField(task, "mUserId");
            if (value instanceof Integer) return ((Integer) value).intValue();
        }
        return 0;
    }

    private static Intent baseIntentOf(Object task) {
        try {
            Object taskInfo = XposedHelpers.callMethod(task, "getTaskInfo");
            Object value = XposedHelpers.getObjectField(taskInfo, "baseIntent");
            if (value instanceof Intent) return new Intent((Intent) value);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " base intent failed: " + t);
        }
        return null;
    }

    private static String pkgOf(Intent intent) {
        if (intent == null) return "null";
        ComponentName component = intent.getComponent();
        return component == null ? String.valueOf(intent.getPackage()) : component.flattenToShortString();
    }
}
