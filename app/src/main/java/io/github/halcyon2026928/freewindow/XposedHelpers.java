package io.github.halcyon2026928.freewindow;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * legacy {@code XposedHelpers} 的等价实现：框架在新 API 里不再提供这些反射工具，
 * 这里用纯反射补一套，方法名/语义与 legacy 对齐，业务代码照旧调用即可。
 */
public final class XposedHelpers {

    private XposedHelpers() {
    }

    // ---------------- class ----------------

    public static Class<?> findClass(String className) {
        return findClass(className, XposedBridge.sDefaultClassLoader);
    }

    public static Class<?> findClass(String className, ClassLoader cl) {
        if (className == null) {
            throw new IllegalArgumentException("className == null");
        }
        ClassLoader loader = cl != null ? cl : XposedBridge.sDefaultClassLoader;
        if (loader != null) {
            try {
                return Class.forName(className, false, loader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new NoClassDefFoundError(className);
        }
    }

    public static Class<?> findClassIfExists(String className, ClassLoader cl) {
        try {
            return findClass(className, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------------- field ----------------

    public static Field findFieldIfExists(Class<?> clazz, String fieldName) {
        try {
            return findField(clazz, fieldName);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Field findField(Class<?> clazz, String fieldName) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldError(fieldName + " in " + clazz.getName());
    }

    public static Object getObjectField(Object obj, String fieldName) {
        if (obj == null) {
            throw new IllegalArgumentException("obj == null");
        }
        try {
            return findField(obj.getClass(), fieldName).get(obj);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static int getIntField(Object obj, String fieldName) {
        Object v = getObjectField(obj, fieldName);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        throw new ClassCastException(fieldName + " is not a number: " + v);
    }

    public static void setIntField(Object obj, String fieldName, int value) {
        if (obj == null) {
            throw new IllegalArgumentException("obj == null");
        }
        try {
            findField(obj.getClass(), fieldName).setInt(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        if (obj == null) {
            throw new IllegalArgumentException("obj == null");
        }
        try {
            findField(obj.getClass(), fieldName).set(obj, value);
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    // ---------------- method call ----------------

    public static Object callMethod(Object obj, String methodName, Object... args) {
        if (obj == null) {
            throw new IllegalArgumentException("obj == null");
        }
        Method m = findBestMethod(obj.getClass(), methodName, args, false);
        try {
            return m.invoke(obj, args);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        Method m = findBestMethod(clazz, methodName, args, true);
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalAccessError(e.getMessage());
        }
    }

    public static Object callStaticMethod(ClassLoader cl, String className, String methodName,
                                          Object... args) {
        return callStaticMethod(findClass(className, cl), methodName, args);
    }

    public static Object newInstance(Class<?> clazz, Object... args) {
        Constructor<?> best = null;
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            if (matches(ctor.getParameterTypes(), args)) {
                best = ctor;
                if (exactMatch(ctor.getParameterTypes(), args)) {
                    break;
                }
            }
        }
        if (best == null) {
            throw new NoSuchMethodError("constructor of " + clazz.getName() + " with " + args.length + " args");
        }
        best.setAccessible(true);
        try {
            return best.newInstance(args);
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    // ---------------- hook ----------------

    /**
     * 对应 legacy 的 findAndHookMethod：参数列表最后一个是回调，前面是参数类型（Class 或类名字符串）。
     */
    public static void findAndHookMethod(Class<?> clazz, String methodName,
                                         Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("no callback in last argument");
        }
        XC_MethodHook callback =
                (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        int paramCount = parameterTypesAndCallback.length - 1;
        Class<?>[] paramTypes = new Class<?>[paramCount];
        for (int i = 0; i < paramCount; i++) {
            Object o = parameterTypesAndCallback[i];
            if (o instanceof Class) {
                paramTypes[i] = (Class<?>) o;
            } else if (o instanceof String) {
                paramTypes[i] = findClass((String) o, clazz.getClassLoader());
            } else if (o == null) {
                paramTypes[i] = null;
            } else {
                throw new IllegalArgumentException("unsupported parameter type: " + o);
            }
        }
        Method m = findExactMethod(clazz, methodName, paramTypes);
        XposedBridge.hookMethod(m, callback);
    }

    public static void findAndHookConstructor(Class<?> clazz, Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[parameterTypesAndCallback.length - 1] instanceof XC_MethodHook)) {
            throw new IllegalArgumentException("no callback in last argument");
        }
        XC_MethodHook callback =
                (XC_MethodHook) parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        XposedBridge.hookAllConstructors(clazz, callback);
    }

    // ---------------- internals ----------------

    private static Method findExactMethod(Class<?> clazz, String methodName, Class<?>[] paramTypes) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) {
                    continue;
                }
                Class<?>[] actual = m.getParameterTypes();
                if (actual.length != paramTypes.length) {
                    continue;
                }
                boolean ok = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    if (paramTypes[i] != null && actual[i] != paramTypes[i]) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        throw new NoSuchMethodError(methodName + " in " + clazz.getName());
    }

    private static Method findBestMethod(Class<?> clazz, String methodName, Object[] args, boolean isStatic) {
        int argc = args == null ? 0 : args.length;
        Method best = null;
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) {
                    continue;
                }
                if (isStatic != Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?>[] types = m.getParameterTypes();
                if (types.length != argc) {
                    continue;
                }
                if (matches(types, args)) {
                    boolean exact = exactMatch(types, args);
                    m.setAccessible(true);
                    if (best == null || exact) {
                        best = m;
                    }
                    if (exact) {
                        return m;
                    }
                }
            }
        }
        if (best == null) {
            throw new NoSuchMethodError(methodName + " in " + clazz.getName() + " with " + argc + " args");
        }
        return best;
    }

    private static boolean matches(Class<?>[] types, Object[] args) {
        if (args == null) {
            args = new Object[0];
        }
        if (types.length != args.length) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            if (!isCompatible(types[i], args[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean exactMatch(Class<?>[] types, Object[] args) {
        if (args == null) {
            args = new Object[0];
        }
        if (types.length != args.length) {
            return false;
        }
        for (int i = 0; i < types.length; i++) {
            Object a = args[i];
            if (a == null) {
                if (types[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            if (!boxed(types[i]).equals(a.getClass())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCompatible(Class<?> type, Object arg) {
        if (arg == null) {
            return !type.isPrimitive();
        }
        return boxed(type).isInstance(arg);
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return new RuntimeException(cause);
    }
}
