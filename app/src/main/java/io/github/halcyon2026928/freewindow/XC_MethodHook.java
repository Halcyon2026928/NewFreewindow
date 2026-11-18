package io.github.halcyon2026928.freewindow;

import java.lang.reflect.Member;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * 现代 API（libxposed API 102）下的回调载体，形状与 legacy 的
 * {@code de.robv.android.xposed.XC_MethodHook} 完全一致：
 * 子类覆盖 {@link #beforeHookedMethod} / {@link #afterHookedMethod}，
 * 用 {@link MethodHookParam#args} 读改参数、{@link MethodHookParam#setResult} 改返回值。
 *
 * <p>和 legacy 的差别只有一处：legacy 的 {@code setResult} 在 before 阶段表示「跳过原方法」，
 * 这里用一个 returnEarly 标记表达同样的语义；after 阶段的 setResult 依然是覆盖返回值。</p>
 */
public abstract class XC_MethodHook {

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    /** 把一次调用转成 legacy 语义：before → （可能跳过）原方法 → after。 */
    Object dispatch(XposedInterface.Chain chain) throws Throwable {
        MethodHookParam param = new MethodHookParam();
        param.thisObject = chain.getThisObject();
        param.method = chain.getExecutable();
        List<Object> chainArgs = chain.getArgs();
        param.args = chainArgs.toArray(new Object[0]);
        try {
            beforeHookedMethod(param);
        } catch (Throwable t) {
            XposedBridge.log("before hook error: " + param.method + " -> " + t);
        }
        if (param.returnEarly) {
            return param.result;
        }
        try {
            Object ret = chain.proceed(param.args);
            if (!param.afterSetResult) {
                param.result = ret;
            }
        } catch (Throwable t) {
            param.throwable = t;
        }
        param.inAfter = true;
        try {
            afterHookedMethod(param);
        } catch (Throwable t) {
            XposedBridge.log("after hook error: " + param.method + " -> " + t);
        }
        if (param.throwable != null) {
            throw param.throwable;
        }
        return param.result;
    }

    /** 回调入参，对应 legacy 的 {@code XC_MethodHook.MethodHookParam}。 */
    public static class MethodHookParam {
        /** 被 hook 的实例，静态方法为 null。 */
        public Object thisObject;
        /** 本次调用的参数数组，可以原地修改，修改会传给原方法。 */
        public Object[] args;
        /** 被 hook 的方法/构造器。 */
        public Member method;

        Object result;
        Throwable throwable;
        boolean returnEarly;
        boolean afterSetResult;
        boolean inAfter;
        private java.util.HashMap<String, Object> extras;

        public Object getResult() {
            return result;
        }

        /** legacy 的 param extra：同一次调用在 before/after 之间传自定义数据。 */
        public void setObjectExtra(String name, Object value) {
            if (extras == null) {
                extras = new java.util.HashMap<>();
            }
            extras.put(name, value);
        }

        public Object getObjectExtra(String name) {
            return extras == null ? null : extras.get(name);
        }

        /** after 阶段：覆盖返回值；before 阶段：直接返回该值并跳过原方法。 */
        public void setResult(Object value) {
            result = value;
            if (inAfter) {
                afterSetResult = true;
            } else {
                returnEarly = true;
            }
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable t) {
            throwable = t;
        }
    }
}
