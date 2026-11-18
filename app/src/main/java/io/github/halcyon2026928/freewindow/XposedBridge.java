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

    public static void log(String msg) {
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
