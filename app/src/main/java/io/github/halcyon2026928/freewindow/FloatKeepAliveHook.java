package io.github.halcyon2026928.freewindow;

import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 「贴边保活」：小窗收成贴边浮标后，不把它的任务停掉。
 *
 * 原厂把窗口收成浮标时会把任务移回后台（Task.moveTaskToBack），可见性遍历随后让
 * ActivityRecord 走 makeInvisible()，状态落到 STOPPED；进程变成 cached，稍后即被冻结
 * 或回收，正在下载/更新的应用也就停了。
 *
 * 这里只做一件事：浮标列表里的任务不参与这次可见性收尾——跳过 makeInvisible()、
 * 拒绝把 visibleRequested 改回 false、拦掉原厂那次显式的 addToStopping，并挡住到
 * STOPPING/STOPPED 的状态迁移。ActivityRecord 因此维持「可见但未置顶」的状态，进程
 * 留在 perceptible 档，不会被冻结。窗口本身仍由原厂隐藏，用户只看到浮标。
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

    private static final Set<String> STOP_STATES = new HashSet<>();

    static {
        STOP_STATES.add("STOPPING");
        STOP_STATES.add("STOPPED");
    }

    /** Display.STATE_OFF */
    private static final int DISPLAY_STATE_OFF = 1;

    /** 配置缓存：判定路径高频调用，最多每 2 秒重读一次开关。 */
    private static final long CONFIG_TTL_MS = 2000L;

    private static volatile boolean sEnabled = true;
    private static volatile long sConfigReadAt = -CONFIG_TTL_MS * 2;

    private static Method sGetInstance;
    private static Method sIsInFloatingList;

    /** 假暂停接管成功后为 true；只有它成立时才跳过暂停超时，避免服务端卡在 PAUSING。 */
    private static volatile boolean sPauseBypassActive = false;

    /** 不同 ROM 的 token 字段名不一致，按候选顺序探测。 */
    private static final String[] TOKEN_FIELDS =
            {"token", "mToken", "mClientToken", "activityToken", "mActivityToken", "mAppToken"};

    private static final ConcurrentHashMap<Integer, Boolean> sLoggedTasks = new ConcurrentHashMap<>();

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

        resolveController(classLoader);

        boolean recordHooked = installRecordHooks();
        boolean pauseHooked = installPauseSkip(classLoader);
        if (recordHooked) {
            XposedBridge.log(TAG + " installed (pauseSkip=" + pauseHooked
                    + ", floatingController=" + (sIsInFloatingList != null ? "ok" : "missing") + ")");
        } else {
            XposedBridge.log(TAG + " no ActivityRecord target found, keep-alive inactive");
        }
    }

    /** ActivityRecord 上三组保护：可见性收尾、状态迁移、停止。 */
    private static boolean installRecordHooks() {
        boolean hooked = false;

        // 1) makeInvisible(): 浮标任务不参与可见性收尾，保持 visibleRequested。
        hooked |= hookAllSafe(sRecordClass, "makeInvisible", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (shouldKeepVisible(param.thisObject)) {
                        logSkip(param.thisObject, "invisible");
                        param.setResult(null);
                    } else if (shouldKeep(param.thisObject)) {
                        logRelease(param.thisObject, "invisible");
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " makeInvisible hook error: " + t.getMessage());
                }
            }
        });

        // 2) setVisibleRequested(boolean): 拒绝把浮标任务改回不可见，按可见继续执行。
        hooked |= hookAllSafe(sRecordClass, "setVisibleRequested", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length != 1) return;
                    Object arg = param.args[0];
                    if (!(arg instanceof Boolean)) return;
                    if (((Boolean) arg) || !shouldKeepVisible(param.thisObject)) {
                        if (!((Boolean) arg) && shouldKeep(param.thisObject)) {
                            logRelease(param.thisObject, "visible_requested");
                        }
                        return;
                    }
                    logSkip(param.thisObject, "visible_requested");
                    param.args[0] = Boolean.TRUE;
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " setVisibleRequested hook error: " + t.getMessage());
                }
            }
        });

        // 3) setState(State, String): 挡住到 STOPPING/STOPPED 的迁移。
        hooked |= hookAllSafe(sRecordClass, "setState", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object stateArg = param.args.length > 0 ? param.args[0] : null;
                    if (!(stateArg instanceof Enum)) return;
                    String state = ((Enum<?>) stateArg).name();
                    if (!STOP_STATES.contains(state)) return;
                    if (!shouldKeep(param.thisObject)) return;
                    if (((Method) param.method).getReturnType() != Void.TYPE) return;
                    logSkip(param.thisObject, "state_" + state);
                    param.setResult(null);
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

    private static boolean shouldKeep(Object record) {
        if (record == null) return false;
        if (!keepAliveEnabled()) return false;
        try {
            if (Boolean.TRUE.equals(readField(record, "finishing"))) return false;
            Object task = readField(record, "task");
            if (task == null) task = readField(record, "mTask");
            if (task == null) return false;
            Object taskIdObj = readField(task, "mTaskId");
            if (!(taskIdObj instanceof Integer)) return false;
            return isFloating((Integer) taskIdObj);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isFloating(int taskId) {
        if (taskId <= 0) return false;
        Method getInstance = sGetInstance;
        Method isInList = sIsInFloatingList;
        if (getInstance == null || isInList == null) return false;
        try {
            Object controller = getInstance.invoke(null);
            if (controller == null) return false;
            Object result = isInList.invoke(controller, taskId);
            return Boolean.TRUE.equals(result);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 可见性两道钩子只在「屏幕亮着且没锁屏」时生效。
     *
     * 锁屏时原厂会把全场窗口刷成不可见，这时如果继续把浮标任务按成「可见」，
     * 窗口管理器会在锁屏过渡里把浮标变回窗口显示在锁屏上。放开可见性只是让窗口
     * 消失，暂停/停止照样拦着，应用仍在跑。
     */
    private static boolean shouldKeepVisible(Object record) {
        return shouldKeep(record) && !isDisplayLockedOrOff(record);
    }

    private static boolean isDisplayLockedOrOff(Object record) {
        Object display = callNoArg(record, "getDisplayContent");
        if (display == null) return false;
        Object policy = callNoArg(display, "getDisplayPolicy");
        if (policy != null) {
            if (Boolean.TRUE.equals(callNoArg(policy, "isKeyguardShowing"))) return true;
            if (Boolean.FALSE.equals(readField(policy, "mAwake"))) return true;
        }
        Object displayInfo = readField(display, "mDisplayInfo");
        Object state = readField(displayInfo, "state");
        return state instanceof Integer && (Integer) state == DISPLAY_STATE_OFF;
    }

    private static boolean keepAliveEnabled() {
        long now = SystemClock.uptimeMillis();
        if (now - sConfigReadAt < CONFIG_TTL_MS) return sEnabled;
        sConfigReadAt = now;
        try {
            XSharedPreferences prefs = new XSharedPreferences(ModulePrefs.PKG, ModulePrefs.NAME);
            prefs.reload();
            sEnabled = prefs.getBoolean(KEY_KEEP_ALIVE, true);
        } catch (Throwable t) {
            // 读不到时保留上次的值（模块进程未启动/文件不可读）。
        }
        return sEnabled;
    }

    private static Object callNoArg(Object instance, String name) {
        if (instance == null) return null;
        Class<?> type = instance.getClass();
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(instance);
            } catch (Throwable ignored) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

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

    private static void logSkip(Object record, String reason) {
        int taskId = taskIdOf(record);
        if (sLoggedTasks.putIfAbsent(taskId, Boolean.TRUE) == null) {
            XposedBridge.log(TAG + " keep task" + taskId + " (" + reason + ")");
        }
    }

    private static void logRelease(Object record, String reason) {
        int taskId = taskIdOf(record);
        XposedBridge.log(TAG + " release task" + taskId + " (" + reason + ")");
    }

    private static int taskIdOf(Object record) {
        Object task = readField(record, "task");
        if (task == null) task = readField(record, "mTask");
        Object taskId = readField(task, "mTaskId");
        return taskId instanceof Integer ? (Integer) taskId : -1;
    }
}
