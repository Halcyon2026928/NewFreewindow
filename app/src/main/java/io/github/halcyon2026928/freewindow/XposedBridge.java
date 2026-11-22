package io.github.halcyon2026928.freewindow;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;

/**
 * 现代 API 下的 hook 注册与日志入口，方法签名与 legacy 的
 * {@code de.robv.android.xposed.XposedBridge} 保持一致，
 * 这样业务代码里的 {@code XposedBridge.log(...)} / {@code hookAllMethods(...)} 不用改写法。
 */
public final class XposedBridge {

    static volatile XposedInterface sApi;
    static volatile ClassLoader sDefaultClassLoader;

    private XposedBridge() {
    }

    static void attach(XposedInterface api, ClassLoader cl) {
        sApi = api;
        if (cl != null) {
            sDefaultClassLoader = cl;
        }
    }

    /** 取证日志文件：system_server 侧写 /data/system/freewindow_journal.log，adb 可直接 cat。 */
    private static java.io.FileOutputStream sJournal;
    private static final java.text.SimpleDateFormat JOURNAL_FMT =
            new java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS");
    private static final long JOURNAL_MAX = 6L * 1024 * 1024;

    /**
     * 现代 API 的 api.log 只写进 LSPosed 自己的日志（logcat 抓不到），排查实时画面这种
     * 时序问题很不方便。这里把带「灵动坞OS」标记的日志再同步落一份到 /data/system，
     * adb shell 有读权限，可以随抓随看。
     */
    private static synchronized void journal(String msg) {
        if (msg == null || !msg.contains("灵动坞OS")) return;
        try {
            java.io.File f = new java.io.File("/data/system/freewindow_journal.log");
            if (sJournal == null || f.length() > JOURNAL_MAX) {
                if (sJournal != null) {
                    try { sJournal.close(); } catch (Throwable ignored) { }
                    sJournal = null;
                }
                if (f.length() > JOURNAL_MAX) {
                    java.io.File old = new java.io.File("/data/system/freewindow_journal.old.log");
                    if (old.exists()) old.delete();
                    f.renameTo(old);
                }
                sJournal = new java.io.FileOutputStream(f, true);
                try {
                    android.system.Os.chmod(f.getAbsolutePath(), 0644);
                } catch (Throwable ignored) {
                }
            }
            sJournal.write((JOURNAL_FMT.format(new java.util.Date())
                    + " " + msg + "\n").getBytes());
            sJournal.flush();
        } catch (Throwable t) {
            try { if (sJournal != null) sJournal.close(); } catch (Throwable ignored) { }
            sJournal = null;
        }
    }

    public static void log(String msg) {
        journal(msg);
        XposedInterface api = sApi;
        if (api != null) {
            try {
                api.log(Log.INFO, "[freewindow-hook]", msg);
                return;
            } catch (Throwable ignored) {
            }
        }
        Log.i("freewindow-hook", msg);
    }

    public static void log(Throwable t) {
        log(Log.getStackTraceString(t));
    }

    public static void log(String msg, Throwable t) {
        log(msg + '\n' + Log.getStackTraceString(t));
    }

    /** 对 class 里所有同名方法挂回调，返回真正挂上的方法集合（与 legacy 返回值形状一致）。 */
    public static java.util.Set<Method> hookAllMethods(Class<?> clazz, String methodName,
                                                      XC_MethodHook callback) {
        java.util.Set<Method> hooked = new java.util.HashSet<>();
        if (clazz == null || methodName == null || callback == null) {
            return hooked;
        }
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable t) {
                continue;
            }
            for (Method m : methods) {
                if (m.getName().equals(methodName) && hookMethod(m, callback)) {
                    hooked.add(m);
                }
            }
        }
        return hooked;
    }

    public static boolean hookMethod(Method method, XC_MethodHook callback) {
        XposedInterface api = sApi;
        if (api == null) {
            log("hook skipped (api not attached): " + method);
            return false;
        }
        try {
            api.hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(callback::dispatch);
            return true;
        } catch (Throwable t) {
            log("hook failed: " + method + " -> " + t);
            return false;
        }
    }

    public static void hookAllConstructors(Class<?> clazz, XC_MethodHook callback) {
        XposedInterface api = sApi;
        if (api == null || clazz == null || callback == null) {
            if (api == null) {
                log("hookAllConstructors skipped (api not attached): " + clazz);
            }
            return;
        }
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            try {
                api.hook(ctor)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept(callback::dispatch);
            } catch (Throwable t) {
                log("hook ctor failed: " + ctor + " -> " + t);
            }
        }
    }
}
