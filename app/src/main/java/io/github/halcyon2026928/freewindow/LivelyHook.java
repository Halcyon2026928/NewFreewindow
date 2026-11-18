package io.github.halcyon2026928.freewindow;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

import java.io.FileWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * ColorOS17：不再依赖 addFloatHandle(FloatHandleInfo,int) 精确签名。
 * 拦截系统 takeTaskSnapshot 结果，并从 Task.getSurfaceControl 补抓。
 */
public final class LivelyHook {

    private static final String TAG = "freewindow-lively";
    private static final String DUMP = "/data/system/fw_lively.txt";
    private static final ConcurrentHashMap<Integer, Bitmap> sSnapshots = new ConcurrentHashMap<>();
    /** 固定画面比例时铺满整块画面（cover，裁掉溢出部分），默认 false 走等比完整装入（fit）。 */
    private static volatile boolean sFillMode = false;
    /** 固定比例值（高/宽）；>0 时覆盖快照真实比例，0 表示不覆盖。由坞窗注入。 */
    private static volatile float sAspectOverride = 0f;

    /** 坞窗打开时注入：是否铺满、以及要不要把画面比例钉死。 */
    public static void setPreviewStyle(boolean fill, float aspectOverride) {
        sFillMode = fill;
        sAspectOverride = aspectOverride;
    }
    /** 旋转时被作废的旧快照：重新抓图失败时用它兜底，避免卡片空白。 */
    private static final ConcurrentHashMap<Integer, Bitmap> sFallback = new ConcurrentHashMap<>();
    private static final Handler sMain = new Handler(Looper.getMainLooper());
    private static ClassLoader sClassLoader;

    private LivelyHook() {
    }

    private static void log(String msg) {
        Log.e(TAG, msg);
        XposedBridge.log("[" + TAG + "] " + msg);
        try {
            FileWriter w = new FileWriter(DUMP, true);
            w.write(System.currentTimeMillis() + " " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    public static void install(ClassLoader cl) {
        sClassLoader = cl;
        log("install begin");
        hookByName(cl, "com.android.server.wm.FloatHandleController", "addFloatHandle", true);
        hookByName(cl, "com.android.server.wm.floathandle.FloatHandleViewManager", "addFloatHandleView", true);
        hookSnapshotMethods(cl, "com.android.server.wm.ActivityTaskManagerService");
        hookSnapshotMethods(cl, "com.android.server.wm.TaskSnapshotController");
        hookMethodsContaining(cl, "com.android.server.wm.TaskSnapshotCache", "putSnapshot");
        hookMethodsContaining(cl, "com.android.server.wm.WindowManagerService", "captureTaskSnapshot");
        dumpClass(cl, "com.android.server.wm.TaskSnapshotController");
        dumpClass(cl, "com.android.server.wm.WindowManagerService");
        dumpClass(cl, "com.android.server.wm.FloatHandleController");
        dumpClass(cl, "com.android.server.wm.floathandle.FloatHandleInfo");
        dumpClass(cl, "com.android.server.wm.ActivityTaskManagerService");
        log("install done");
    }

    private static void hookByName(ClassLoader cl, String className, String methodName, boolean afterAdd) {
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            int n = 0;
            XC_MethodHook hook = afterAdd ? afterAddHook() : null;
            for (Method m : c.getDeclaredMethods()) {
                if (!methodName.equals(m.getName())) continue;
                XposedBridge.hookMethod(m, hook);
                n++;
                log("hooked " + className + "." + m.getName() + sig(m));
            }
            log("hookByName " + className + "." + methodName + " count=" + n);
        } catch (Throwable t) {
            log("hookByName fail " + className + "." + methodName + " " + t);
        }
    }

    private static XC_MethodHook afterAddHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object info = findInfo(param.args);
                int taskId = readTaskId(info);
                if (info == null && param.args != null && param.args.length > 0
                        && param.args[0] instanceof Integer) {
                    taskId = (Integer) param.args[0];
                }
                log("afterAdd " + param.method.getName() + " taskId=" + taskId
                        + " info=" + (info == null ? "null" : info.getClass().getName()));
                if (taskId < 0) return;
                Bitmap bmp = readInfoSnapshot(info);
                if (bmp == null) bmp = captureTaskBitmap(info, taskId, sClassLoader);
                if (bmp != null) {
                    // 已有缓存就不覆盖：转屏后系统偶尔会给一张空白快照，覆盖掉就等于画面消失。
                    if (!sSnapshots.containsKey(taskId)) sSnapshots.put(taskId, bmp);
                    log("cached afterAdd taskId=" + taskId + " " + bmp.getWidth() + "x" + bmp.getHeight());
                } else {
                    log("afterAdd capture null taskId=" + taskId);
                }
            }
        };
    }

    private static void hookSnapshotMethods(ClassLoader cl, String className) {
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            int n = 0;
            XC_MethodHook after = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (result == null || param.args == null || param.args.length == 0) return;
                    int taskId = -1;
                    if (param.args[0] instanceof Integer) {
                        taskId = (Integer) param.args[0];
                    }
                    if (taskId < 0) return;
                    Bitmap bmp = bitmapFromTaskSnapshot(result);
                    if (bmp != null) {
                        if (!sSnapshots.containsKey(taskId)) sSnapshots.put(taskId, bmp);
                        log("cached " + param.method.getName() + " taskId=" + taskId
                                + " " + bmp.getWidth() + "x" + bmp.getHeight());
                    }
                }
            };
            for (Method m : c.getDeclaredMethods()) {
                String name = m.getName();
                if (!name.toLowerCase().contains("snapshot")) continue;
                XposedBridge.hookMethod(m, after);
                n++;
                log("hooked snapshot " + className + "." + name + sig(m));
            }
            log("hookSnapshot " + className + " count=" + n);
        } catch (Throwable t) {
            log("hookSnapshot fail " + className + " " + t);
        }
    }

    private static void hookMethodsContaining(ClassLoader cl, String className, String methodName) {
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            int n = 0;
            XC_MethodHook hook = cacheSnapshotHook();
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                XposedBridge.hookMethod(m, hook);
                n++;
                log("hooked " + className + "." + m.getName() + sig(m));
            }
            log("hookMethods " + className + "." + methodName + " count=" + n);
        } catch (Throwable t) {
            log("hookMethods fail " + className + "." + methodName + " " + t);
        }
    }

    private static XC_MethodHook cacheSnapshotHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    log("sys " + param.thisObject.getClass().getSimpleName() + "."
                            + param.method.getName() + " " + describeArgs(param.args)
                            + " result=" + (param.getResult() == null ? "null"
                            : param.getResult().getClass().getSimpleName()));
                    int taskId = extractTaskId(param.args);
                    Object snap = findSnapshotObj(param.args, param.getResult());
                    Bitmap bmp = bitmapFromTaskSnapshot(snap);
                    if (bmp != null && taskId >= 0) {
                        if (!sSnapshots.containsKey(taskId)) sSnapshots.put(taskId, bmp);
                        log("cached sys " + param.method.getName() + " taskId=" + taskId
                                + " " + bmp.getWidth() + "x" + bmp.getHeight());
                    }
                } catch (Throwable t) {
                    log("cacheSnapshotHook " + t);
                }
            }
        };
    }

    private static String describeArgs(Object[] args) {
        if (args == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(",");
            Object a = args[i];
            sb.append(a == null ? "null" : a.getClass().getSimpleName());
        }
        return sb.append("]").toString();
    }

    private static int extractTaskId(Object[] args) {
        if (args == null) return -1;
        for (Object a : args) {
            if (a instanceof Integer) return (Integer) a;
            if (a == null) continue;
            try {
                return XposedHelpers.getIntField(a, "mTaskId");
            } catch (Throwable ignored) {
            }
            try {
                Object v = XposedHelpers.callMethod(a, "getTaskId");
                if (v instanceof Integer) return (Integer) v;
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static Object findSnapshotObj(Object[] args, Object result) {
        if (isSnapshot(result)) return result;
        if (args == null) return null;
        for (Object a : args) {
            if (isSnapshot(a)) return a;
        }
        return null;
    }

    private static boolean isSnapshot(Object o) {
        if (o == null) return false;
        String n = o.getClass().getName();
        return n.contains("TaskSnapshot") || n.contains("ScreenshotHardwareBuffer")
                || n.contains("HardwareBuffer");
    }

    private static void dumpClass(ClassLoader cl, String className) {
        try {
            Class<?> c = XposedHelpers.findClass(className, cl);
            log("dump " + className);
            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase();
                if (n.contains("float") || n.contains("snap") || n.contains("surface")
                        || n.contains("task")) {
                    log("  M " + m.getName() + sig(m));
                }
            }
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                String n = f.getName().toLowerCase();
                if (n.contains("snap") || n.contains("icon") || n.contains("atms")) {
                    log("  F " + f.getName() + " " + f.getType().getSimpleName());
                }
            }
        } catch (Throwable t) {
            log("dump fail " + className + " " + t);
        }
    }

    /**
     * 作废某个任务的缓存快照。
     *
     * <p>快照是按 taskId 缓存的一张图，内容是“拍照那一刻屏幕方向的画面”。屏幕旋转后重建坞窗时
     * 如果不清掉，{@link #createPreview} 会直接复用它，卡片比例就一直停在旋转前那个方向
     * （看着像“坞窗转了、画面没转”）。清掉之后会重新抓一次当前方向的画面。
     */
    public static void invalidateSnapshot(Object floatHandleInfo) {
        int taskId = readTaskId(floatHandleInfo);
        if (taskId < 0) return;
        // 转屏后不再作废旧图：旧图一律保留（sSnapshots 原样 + sFallback 备份）。
        // 这样转屏重建卡片时 createPreview / fill 都能立刻命中缓存，不再重新抓图 ——
        // 重新抓图正是 5200（ColorOS 17 / Android 17）上「画面消失 + 有延迟」的来源。
        Bitmap old = sSnapshots.get(taskId);
        if (old != null && !old.isRecycled()) {
            sFallback.put(taskId, old);
            log("snapshot kept across rotation taskId=" + taskId);
        }
    }

    /**
     * 坞窗真正关闭时把该任务的快照从「现用缓存」移到「兜底」。
     *
     * <p>语义（用户定的）：坞窗关掉、下次重新打开时才去抓新图；坞窗开着期间无论怎么转屏，
     * 画面都保持这一张不动。所以这里只清 sSnapshots，旧图仍留在 sFallback 里，
     * 重新打开坞窗的瞬间先用它铺住（不出现空白），随后抓到新图再替换。
     */
    public static void releaseSnapshot(Object floatHandleInfo) {
        int taskId = readTaskId(floatHandleInfo);
        if (taskId >= 0) {
            Bitmap old = sSnapshots.remove(taskId);
            if (old != null && !old.isRecycled()) sFallback.put(taskId, old);
            log("snapshot released taskId=" + taskId);
        } else {
            sSnapshots.clear();
            log("snapshot released all");
        }
    }

    /**
     * 卡片画面改用 SurfaceView 承载：把快照直接画到 surface 上，不走 ImageView。
     *
     * <p>这条路径是重新设计的一套，和下面 ImageView 那套互不相干：
     * <ol>
     *   <li>画面来源仍是抓出来的快照 Bitmap：先查缓存（sSnapshots / sFallback），
     *       没有才抓一次；抓到就固定住，转屏不重抓、不复算比例。</li>
     *   <li>绘制挂在 SurfaceHolder 回调上：surfaceCreated / surfaceChanged 各画一次，
     *       后台抓到新图后再补画一次。</li>
     *   <li>SurfaceView 的 surface 不参与 View 的 clipToOutline，圆角只能在画布上裁，
     *       所以 surface 设为 {@code setZOrderOnTop(true)} + {@code TRANSLUCENT}，
     *       每次先把整块清成全透明，再用圆角 Path 裁着画图 —— 圆角外透明，透出坞窗
     *       自身的浅灰底，不会出现黑边。</li>
     *   <li>绘制跑在单线程池里：坞窗建在 system_server 的 android.ui 线程上，
     *       不能让 lockCanvas 阻塞它。</li>
     * </ol>
     */
    public static SurfaceView createSnapshotSurface(Context ctx, Object floatHandleInfo, ClassLoader cl) {
        SurfaceView view = new SurfaceView(ctx);
        SurfaceHolder holder = view.getHolder();
        view.setZOrderOnTop(true);
        holder.setFormat(PixelFormat.TRANSLUCENT);
        int taskId = readTaskId(floatHandleInfo);
        SnapshotSurface painter = new SnapshotSurface(ctx, holder, taskId, floatHandleInfo, cl);
        holder.addCallback(painter);
        painter.load();
        log("createSnapshotSurface taskId=" + taskId);
        return view;
    }

    /**
     * 把画面 surface 裁到「坞窗滚动区」的可见范围。
     *
     * SurfaceView 的 surface 是窗口之外的独立图层：坞窗窗口的 crop 能挡住坞窗外的溢出，
     * 但挡不住「坞窗内部、滚动区下面」的那一截 —— 卡片往下滚时画面会一直画到坞窗底部的
     * 「清除」按钮上。滚动区顶边就是坞窗窗口顶边，向上溢出的部分已由窗口 crop 挡掉，
     * 所以这里只需要裁底部：保留从 surface 顶部算起、仍然落在滚动区内的那一截。
     *
     * @param visibleBottomScreen 滚动区底边的屏幕 y
     */
    public static void cropSnapshot(SurfaceView view, int visibleBottomScreen) {
        try {
            Object sc = XposedHelpers.callMethod(view, "getSurfaceControl");
            if (sc == null) return;
            SurfaceControl surface = (SurfaceControl) sc;
            int w = view.getWidth();
            int h = view.getHeight();
            if (w <= 0 || h <= 0) return;
            int[] loc = new int[2];
            view.getLocationOnScreen(loc);
            int visibleH = visibleBottomScreen - loc[1];
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            if (visibleH <= 0) {
                t.setAlpha(surface, 0f);
            } else if (visibleH >= h) {
                t.setAlpha(surface, 1f);
                t.setCrop(surface, null);
            } else {
                t.setAlpha(surface, 1f);
                t.setCrop(surface, new Rect(0, 0, w, visibleH));
            }
            t.apply();
        } catch (Throwable t) {
            log("cropSnapshot error: " + t);
        }
    }

    /** SurfaceView 的画布渲染器：管快照的获取、缓存的复用和 surface 的绘制。 */
    private static final class SnapshotSurface implements SurfaceHolder.Callback {
        private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

        private final float density;
        private final SurfaceHolder holder;
        private final int taskId;
        private final Object info;
        private final ClassLoader loader;
        private volatile Bitmap shot;
        private volatile boolean surfaceReady;

        SnapshotSurface(Context ctx, SurfaceHolder holder, int taskId, Object info, ClassLoader loader) {
            this.density = ctx.getResources().getDisplayMetrics().density;
            this.holder = holder;
            this.taskId = taskId;
            this.info = info;
            this.loader = loader;
        }

        /** 取图：缓存优先，没有就后台抓一次并写回缓存，之后一直用这一张。 */
        void load() {
            Bitmap cached = taskId >= 0 ? sSnapshots.get(taskId) : null;
            if ((cached == null || cached.isRecycled()) && taskId >= 0) cached = sFallback.get(taskId);
            if (cached != null && !cached.isRecycled()) {
                shot = cached;
                if (taskId >= 0) sSnapshots.put(taskId, cached);
                log("snapshot surface reuse taskId=" + taskId);
                draw();
                return;
            }
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    Bitmap b = captureTaskBitmap(info, taskId, loader);
                    if (b == null) {
                        log("snapshot surface capture null taskId=" + taskId);
                        return;
                    }
                    if (taskId >= 0) sSnapshots.put(taskId, b);
                    shot = b;
                    log("snapshot surface captured taskId=" + taskId
                            + " " + b.getWidth() + "x" + b.getHeight());
                    draw();
                }
            });
        }

        @Override
        public void surfaceCreated(SurfaceHolder h) {
            surfaceReady = true;
            draw();
        }

        @Override
        public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
            surfaceReady = true;
            draw();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder h) {
            surfaceReady = false;
            log("snapshot surface destroyed taskId=" + taskId);
        }

        private void draw() {
            if (!surfaceReady) return;
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    paint();
                }
            });
        }

        /** 整块清透明 → 圆角裁剪 → 等比完整装入并居中。 */
        private void paint() {
            Canvas canvas;
            try {
                canvas = holder.lockCanvas();
            } catch (Throwable t) {
                log("snapshot lockCanvas " + t);
                return;
            }
            if (canvas == null) return;
            try {
                int w = canvas.getWidth();
                int h = canvas.getHeight();
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
                Bitmap src = shot;
                if (src == null || src.isRecycled() || w <= 0 || h <= 0) return;
                float scale;
                boolean fill = sFillMode;
                if (fill) {
                    // 固定比例：框已经钉死（竖屏 16:9 / 横屏 13:9），画面按「铺满」缩放，
                    // 快照比框更高就上下各裁一点，比框更宽就左右裁，宽度永远等于卡片框宽。
                    scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
                } else {
                    scale = Math.min(w / (float) src.getWidth(), h / (float) src.getHeight());
                }
                float dw = src.getWidth() * scale;
                float dh = src.getHeight() * scale;
                float dx = (w - dw) * 0.5f;
                float dy = (h - dh) * 0.5f;
                Path clip = new Path();
                float radius = 12f * density;
                RectF frame = fill ? new RectF(0, 0, w, h) : new RectF(dx, dy, dx + dw, dy + dh);
                clip.addRoundRect(frame, radius, radius,
                        Path.Direction.CW);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                canvas.save();
                canvas.clipPath(clip);
                canvas.drawBitmap(src, null, new RectF(dx, dy, dx + dw, dy + dh), paint);
                canvas.restore();
            } catch (Throwable t) {
                log("snapshot paint " + t);
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static ImageView createPreview(Context ctx, Object floatHandleInfo, ClassLoader cl) {
        final ImageView preview = new ImageView(ctx);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        final float radius = dp(ctx, 12);
        GradientDrawable bg = new GradientDrawable();
        // 完全透明：画面没铺满时不再露出灰底，直接透出坞窗本身（#E5E5E5）的浅色。
        // 圆角仍由下面 outline + clipToOutline 保证，不依赖这个背景。
        bg.setColor(0x00000000);
        bg.setCornerRadius(radius);
        preview.setBackground(bg);
        preview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int w = view.getWidth();
                int h = view.getHeight();
                if (w > 0 && h > 0) {
                    outline.setRoundRect(0, 0, w, h, radius);
                }
            }
        });
        preview.setClipToOutline(true);
        preview.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                v.invalidateOutline();
                Object tag = v.getTag();
                if (tag instanceof Bitmap && right - left > 0 && bottom - top > 0) {
                    applyRoundedBitmap(preview, (Bitmap) tag, radius);
                    v.setTag(null);
                }
            }
        });

        final ClassLoader loader = cl != null ? cl : sClassLoader;
        final int taskId = readTaskId(floatHandleInfo);
        log("createPreview taskId=" + taskId + " cache=" + sSnapshots.containsKey(taskId)
                + " info=" + (floatHandleInfo == null ? "null" : floatHandleInfo.getClass().getName()));
        dumpInfo(floatHandleInfo);

        Bitmap ready = taskId >= 0 ? sSnapshots.get(taskId) : null;
        if (ready == null) {
            ready = readInfoSnapshot(floatHandleInfo);
            if (ready != null) {
                ready = softwareCopy(ready);
                if (ready != null && taskId >= 0) sSnapshots.put(taskId, ready);
            }
        }
        if (ready != null && !ready.isRecycled()) {
            applyRoundedBitmap(preview, ready, radius);
            log("preview from cache taskId=" + taskId);
            return preview;
        }

        final Object info = floatHandleInfo;
        Runnable fill = new Runnable() {
            int tries = 0;

            @Override
            public void run() {
                // 缓存里已经有这个任务的画面：直接复用，绝不重新抓图。
                // 转屏重建卡片会再走到这里，而重新抓图在 A17/ColorOS 17 上要么拿到空白、
                // 要么要等 surface 重建完才有内容（表现为「转屏后画面消失、过一会儿才出来」）。
                // 用户口径：转屏不动这张图，一直显示它。
                Bitmap cached = sSnapshots.get(taskId);
                if (cached != null && !cached.isRecycled()) {
                    final Bitmap reuse = cached;
                    preview.post(new Runnable() {
                        @Override
                        public void run() {
                            applyRoundedBitmap(preview, reuse, radius);
                        }
                    });
                    log("fill reuse snapshot taskId=" + taskId);
                    return;
                }
                // 缓存被 releaseSnapshot 清掉了（坞窗关过再打开）：先立刻把上一张旧图铺上去，
                // 保证这个空窗期里卡片不是空白，再往下走正常抓图流程去刷新。
                Bitmap stale = sFallback.get(taskId);
                if (stale != null && !stale.isRecycled()) {
                    final Bitmap show = stale;
                    preview.post(new Runnable() {
                        @Override
                        public void run() {
                            applyRoundedBitmap(preview, show, radius);
                        }
                    });
                    log("fill show stale while refreshing taskId=" + taskId);
                }
                Bitmap captured = captureTaskBitmap(info, taskId, loader);
                if (captured == null) {
                    // 主抓图路径失败（旋转瞬间 surface 正在重建、或某些 ROM 上直接拒绝），
                    // 先退到系统自己给的 FloatHandleInfo.snapShot —— 官方坞窗用的就是它。
                    Bitmap fromInfo = readInfoSnapshot(info);
                    if (fromInfo != null) {
                        captured = softwareCopy(fromInfo);
                        if (captured != null) log("capture fallback to info snapshot taskId=" + taskId);
                    }
                }
                if (captured == null) {
                    log("capture null taskId=" + taskId + " try=" + tries);
                    if (tries++ < 3) {
                        sMain.postDelayed(this, 400);
                        return;
                    }
                    // 还是抓不到：退回旋转前那张图，宁可显示旧方向的画面，也不能让卡片空白。
                    final Bitmap old = sFallback.get(taskId);
                    if (old != null && !old.isRecycled()) {
                        preview.post(new Runnable() {
                            @Override
                            public void run() {
                                applyRoundedBitmap(preview, old, radius);
                                log("capture failed, fallback to previous snapshot taskId=" + taskId);
                            }
                        });
                    }
                    return;
                }
                final Bitmap bmp = captured;
                sSnapshots.put(taskId, bmp);
                // 必须投回预览 View 自己的线程再设图。
                // 坞窗是挂在 system_server 的 android.ui 线程上的（showFloatDock 由悬浮球点击回调触发），
                // 而这里（sMain = main 线程）直接 setImageBitmap 会抛
                // ViewRootImpl$CalledFromWrongThreadException，直接把 system_server 打死重启。
                preview.post(new Runnable() {
                    @Override
                    public void run() {
                        applyRoundedBitmap(preview, bmp, radius);
                        log("preview captured taskId=" + taskId + " "
                                + bmp.getWidth() + "x" + bmp.getHeight());
                    }
                });
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            fill.run();
        } else {
            sMain.post(fill);
        }
        return preview;
    }

    /**
     * 官方比例公式：{@code radio = min(1.7778f, h / w)}。
     *
     * <p>抄自 {@code LivelyFloatHandleView$LivelyFloatHandleRecyclerViewAdapter.onBindViewHolder}：
     * 卡片高度 = 卡片宽度 × radio。也就是说画面按快照自身比例显示，但宽高比最多 16:9；
     * 这套规则里没有任何“横屏 / 竖屏”分支，两个方向走的是同一份代码。
     *
     * @return 快照的 ratio；缓存里还没有快照时返回 -1。
     */
    public static float snapshotAspect(Object floatHandleInfo) {
        int taskId = readTaskId(floatHandleInfo);
        Bitmap cached = taskId >= 0 ? sSnapshots.get(taskId) : null;
        if (cached == null || cached.isRecycled()) {
            Bitmap fromInfo = readInfoSnapshot(floatHandleInfo);
            if (fromInfo != null && !fromInfo.isRecycled()) cached = fromInfo;
        }
        return aspectOf(cached);
    }

    /** 快照本身是不是横的（宽 > 高）。给「横屏应用画面按 9:16 显示」判断方向用。 */
    public static boolean snapshotIsLandscape(Object floatHandleInfo) {
        int taskId = readTaskId(floatHandleInfo);
        Bitmap cached = taskId >= 0 ? sSnapshots.get(taskId) : null;
        if (cached == null || cached.isRecycled()) {
            Bitmap fromInfo = readInfoSnapshot(floatHandleInfo);
            if (fromInfo != null && !fromInfo.isRecycled()) cached = fromInfo;
        }
        if (cached == null || cached.isRecycled()) return false;
        return cached.getWidth() > 0 && cached.getWidth() > cached.getHeight();
    }

    private static float aspectOf(Bitmap src) {        if (sAspectOverride > 0f) return sAspectOverride;   // 画面比例已钉死
        if (src == null || src.isRecycled() || src.getWidth() <= 0) return -1f;
        // 用快照真实比例，不再压到 16:9。
        // 压到 1.7778 时，比 16:9 更长的快照（如 720×1568 = 2.18）会得到一个比画面更宽的框，
        // FIT 之后画面左右留白、看起来整体偏右，和卡片标题行「图标 → 三点」对不齐。
        // 真实比例建框 → 画面正好铺满框，左右边缘就落在那两处参照线上。
        float a = src.getHeight() / (float) src.getWidth();
        return Math.min(2.6f, a);   // 只防极端值，正常竖屏快照 1.7~2.2
    }

    /** 卡片高度的最终裁决者：由坞窗那边注入，用来给官方比例加上“不超过可视区”的上限。 */
    public interface HeightPolicy {
        int heightFor(int width, float aspect);
    }

    private static volatile HeightPolicy sHeightPolicy;

    public static void setHeightPolicy(HeightPolicy policy) {
        sHeightPolicy = policy;
    }

    /** 按官方公式算高度，再交给坞窗注入的策略做上限裁剪。 */
    public static int computeHeight(int width, float aspect) {
        if (width <= 0 || aspect <= 0f) return 0;
        HeightPolicy policy = sHeightPolicy;
        if (policy != null) {
            int h = policy.heightFor(width, aspect);
            if (h > 0) return h;
        }
        return (int) (width * aspect);
    }

    private static void applyRoundedBitmap(ImageView preview, Bitmap src, float radius) {
        if (src == null || src.isRecycled()) return;
        // 官方 Lively 的定高规则：高度 = 宽度 × min(16:9, h/w)，再由坞窗策略限制最大高度。
        // 高度一变就先把图挂到 tag 上等下一次 layout，layout 完成后按新尺寸裁圆角，
        // 否则会拿旧尺寸裁图、画面被拉伸。
        ViewGroup.LayoutParams lp = preview.getLayoutParams();
        if (lp != null && lp.width > 0) {
            int target = computeHeight(lp.width, aspectOf(src));
            if (target > 0 && lp.height != target) {
                lp.height = target;
                preview.setTag(src);
                preview.setLayoutParams(lp);
                return;
            }
        }
        int w = preview.getWidth();
        int h = preview.getHeight();
        if (w <= 0 || h <= 0) {
            preview.setTag(src);
            preview.setImageBitmap(src);
            return;
        }
        log("preview laid out " + w + "x" + h + " src=" + src.getWidth() + "x" + src.getHeight());
        preview.setImageBitmap(roundCrop(src, w, h, radius));
    }

    private static Bitmap roundCrop(Bitmap src, int w, int h, float radius) {
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        // 原先是 Math.max（CENTER_CROP），画面会被裁掉边缘；这里改成 Math.min 完整装下，
        // 剩余空间居中留空，横屏应用、竖屏应用都能看到整幅画面。
        float scale = Math.min(w / (float) src.getWidth(), h / (float) src.getHeight());
        if (sFillMode) {
            scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        }
        float dw = src.getWidth() * scale;
        float dh = src.getHeight() * scale;
        float dx = (w - dw) * 0.5f;
        float dy = (h - dh) * 0.5f;
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        matrix.postTranslate(dx, dy);
        BitmapShader shader = new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        shader.setLocalMatrix(matrix);
        paint.setShader(shader);
        if (sFillMode) {
            canvas.drawRoundRect(0f, 0f, w, h, radius, radius, paint);
        } else {
            canvas.drawRoundRect(dx, dy, dx + dw, dy + dh, radius, radius, paint);
        }
        return out;
    }

    private static void dumpInfo(Object info) {
        if (info == null) return;
        try {
            StringBuilder sb = new StringBuilder("info fields:");
            Class<?> c = info.getClass();
            while (c != null && !c.equals(Object.class)) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v = f.get(info);
                    String vs = v == null ? "null" : v.getClass().getSimpleName();
                    if (v instanceof Bitmap) {
                        Bitmap b = (Bitmap) v;
                        vs = "Bitmap " + b.getWidth() + "x" + b.getHeight() + " recycled=" + b.isRecycled();
                    }
                    sb.append(" ").append(f.getName()).append("=").append(vs);
                }
                c = c.getSuperclass();
            }
            log(sb.toString());
        } catch (Throwable t) {
            log("dumpInfo " + t);
        }
    }

    private static Object findInfo(Object[] args) {
        if (args == null) return null;
        for (Object a : args) {
            if (a != null && a.getClass().getName().contains("FloatHandleInfo")) return a;
        }
        return args.length > 0 ? args[0] : null;
    }

    private static Bitmap captureTaskBitmap(Object info, int taskId, ClassLoader cl) {
        Bitmap fromInfo = readInfoSnapshot(info);
        if (fromInfo != null) {
            log("capture from info.snapShot");
            return softwareCopy(fromInfo);
        }
        Object atms = getAtms(cl);
        Object task = null;
        try {
            Object rwc = XposedHelpers.getObjectField(atms, "mRootWindowContainer");
            task = invokeAny(rwc, "anyTaskForId", taskId);
            log("capture task=" + (task == null ? "null" : task.toString()));
        } catch (Throwable t) {
            log("anyTaskForId " + t);
        }
        Object wms = null;
        Object tsc = null;
        try {
            wms = XposedHelpers.getObjectField(atms, "mWindowManager");
        } catch (Throwable ignored) {
        }
        if (wms != null) {
            tsc = getField(wms, "mTaskSnapshotController");
            if (tsc == null) tsc = getField(wms, "mSnapshotController");
        }
        if (tsc == null) tsc = getField(atms, "mTaskSnapshotController");

        Object snapshot = null;
        Object lock = getField(atms, "mGlobalLock");
        if (lock != null) {
            synchronized (lock) {
                snapshot = invokeSnapshotApi(tsc, task, taskId);
                if (snapshot == null) snapshot = invokeSnapshotApi(wms, task, taskId);
                if (snapshot == null) snapshot = invokeAnyLocked(atms, "takeTaskSnapshot", taskId);
                if (snapshot == null) snapshot = invokeAnyLocked(atms, "getTaskSnapshot", taskId);
            }
        } else {
            snapshot = invokeSnapshotApi(tsc, task, taskId);
            if (snapshot == null) snapshot = invokeSnapshotApi(wms, task, taskId);
            if (snapshot == null) snapshot = invokeAny(atms, "takeTaskSnapshot", taskId);
            if (snapshot == null) snapshot = invokeAny(atms, "getTaskSnapshot", taskId);
        }
        if (snapshot instanceof Bitmap) {
            return softwareCopy((Bitmap) snapshot);
        }
        Bitmap fromSnap = bitmapFromTaskSnapshot(snapshot);
        if (fromSnap != null) {
            log("capture from TaskSnapshot " + fromSnap.getWidth() + "x" + fromSnap.getHeight());
            return fromSnap;
        }
        return captureFromSurface(taskId, cl, atms);
    }

    private static Object getField(Object obj, String name) {
        if (obj == null) return null;
        try {
            return XposedHelpers.getObjectField(obj, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeSnapshotApi(Object target, Object task, int taskId) {
        if (target == null) return null;
        String[] names = {
                "captureTaskSnapshot", "createTaskSnapshot", "snapshot", "recordSnapshot",
                "snapshotTask", "captureSnapshot", "takeSnapshot"
        };
        Class<?> c = target.getClass();
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                boolean match = false;
                for (String n : names) {
                    if (n.equals(m.getName())) {
                        match = true;
                        break;
                    }
                }
                if (!match) continue;
                Object[] args = buildSnapshotArgs(m.getParameterTypes(), task, taskId);
                if (args == null) continue;
                try {
                    m.setAccessible(true);
                    Object r = m.invoke(target, args);
                    log(target.getClass().getSimpleName() + "." + m.getName() + sig(m)
                            + " -> " + (r == null ? "null" : r.getClass().getSimpleName()));
                    if (isSnapshot(r)) return r;
                    if (r instanceof Bitmap) return r;
                } catch (Throwable t) {
                    log(m.getName() + sig(m) + " err " + t.getMessage());
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object[] buildSnapshotArgs(Class<?>[] types, Object task, int taskId) {
        if (types.length == 0) return null;
        Object[] args = new Object[types.length];
        boolean usedTask = false;
        boolean usedId = false;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (!usedTask && task != null && t.isInstance(task)) {
                args[i] = task;
                usedTask = true;
            } else if (!usedTask && task != null && t.getName().equals("com.android.server.wm.Task")) {
                args[i] = task;
                usedTask = true;
            } else if (!usedId && (t == int.class || t == Integer.class)) {
                args[i] = taskId;
                usedId = true;
            } else if (t == boolean.class || t == Boolean.class) {
                args[i] = Boolean.TRUE;
            } else if (t == float.class || t == Float.class) {
                args[i] = 1.0f;
            } else if (t == int.class || t == Integer.class) {
                args[i] = 0;
            } else if (t == Rect.class) {
                args[i] = new Rect();
            } else if (t.isArray() && t.getComponentType() == SurfaceControl.class) {
                args[i] = new SurfaceControl[0];
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    /** 按 taskId 找 Task（RootWindowContainer.anyTaskForId 有多个重载，逐个试）。 */
    static Object taskById(ClassLoader cl, int taskId) {
        Object atms = getAtms(cl);
        if (atms == null) return null;
        try {
            Object rwc = XposedHelpers.getObjectField(atms, "mRootWindowContainer");
            return invokeAny(rwc, "anyTaskForId", taskId);
        } catch (Throwable t) {
            log("taskById " + t);
            return null;
        }
    }

    private static Object getAtms(ClassLoader cl) {
        try {
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl),
                    "getInstance");
            return XposedHelpers.getObjectField(fhc, "mAtms");
        } catch (Throwable t) {
            log("getAtms " + t);
            return null;
        }
    }

    private static Object invokeAny(Object target, String name, int taskId) {
        if (target == null) return null;
        Object lock = null;
        try {
            lock = XposedHelpers.getObjectField(target, "mGlobalLock");
        } catch (Throwable ignored) {
        }
        if (lock != null) {
            synchronized (lock) {
                return invokeAnyLocked(target, name, taskId);
            }
        }
        return invokeAnyLocked(target, name, taskId);
    }

    private static Object invokeAnyLocked(Object target, String name, int taskId) {
        Class<?> c = target.getClass();
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (!name.equals(m.getName())) continue;
                Object[] args = buildArgs(m.getParameterTypes(), taskId);
                if (args == null) continue;
                try {
                    m.setAccessible(true);
                    Object r = m.invoke(target, args);
                    log(name + sig(m) + " -> " + (r == null ? "null" : r.getClass().getSimpleName()));
                    if (r != null) return r;
                } catch (Throwable t) {
                    log(name + sig(m) + " err " + t.getMessage());
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static Object[] buildArgs(Class<?>[] types, int taskId) {
        if (types.length == 0) return null;
        if (types[0] != int.class && types[0] != Integer.class) return null;
        Object[] args = new Object[types.length];
        args[0] = Integer.valueOf(taskId);
        for (int i = 1; i < types.length; i++) {
            if (types[i] == boolean.class || types[i] == Boolean.class) {
                args[i] = Boolean.TRUE;
            } else if (types[i] == int.class || types[i] == Integer.class) {
                args[i] = 0;
            } else {
                args[i] = null;
            }
        }
        return args;
    }

    private static Bitmap bitmapFromTaskSnapshot(Object snapshot) {
        if (snapshot == null || Build.VERSION.SDK_INT < 29) return null;
        try {
            HardwareBuffer buffer = (HardwareBuffer) XposedHelpers.callMethod(snapshot, "getHardwareBuffer");
            if (buffer == null) {
                log("snapshot hardwareBuffer null");
                return null;
            }
            ColorSpace cs = null;
            try {
                cs = (ColorSpace) XposedHelpers.callMethod(snapshot, "getColorSpace");
            } catch (Throwable ignored) {
            }
            Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, cs);
            if (hw == null) return null;
            return softwareCopy(hw);
        } catch (Throwable t) {
            log("wrapHardwareBuffer " + t);
            return null;
        }
    }

    private static Bitmap captureFromSurface(int taskId, ClassLoader cl, Object atms) {
        SurfaceControl sc = null;
        try {
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl),
                    "getInstance");
            Object got = invokeAny(fhc, "getTaskSurfaceControl", taskId);
            if (got instanceof SurfaceControl) sc = (SurfaceControl) got;
        } catch (Throwable t) {
            log("getTaskSurfaceControl " + t);
        }
        if (sc == null || !sc.isValid()) {
            sc = surfaceFromTask(atms, taskId);
        }
        if (sc == null || !sc.isValid()) {
            log("no valid surface taskId=" + taskId);
            return null;
        }
        Object buffer = captureLayers(cl, sc);
        if (buffer == null) {
            log("captureLayers null");
            return null;
        }
        try {
            HardwareBuffer hb = (HardwareBuffer) XposedHelpers.callMethod(buffer, "getHardwareBuffer");
            if (hb == null) return null;
            ColorSpace cs = null;
            try {
                cs = (ColorSpace) XposedHelpers.callMethod(buffer, "getColorSpace");
            } catch (Throwable ignored) {
            }
            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, cs);
            return softwareCopy(hw);
        } catch (Throwable t) {
            log("captureFromSurface wrap " + t);
            return null;
        }
    }

    private static SurfaceControl surfaceFromTask(Object atms, int taskId) {
        if (atms == null) return null;
        try {
            Object rwc = XposedHelpers.getObjectField(atms, "mRootWindowContainer");
            Object task = invokeAny(rwc, "anyTaskForId", taskId);
            if (task == null) {
                log("anyTaskForId null");
                return null;
            }
            Object sc = XposedHelpers.callMethod(task, "getSurfaceControl");
            log("task.getSurfaceControl " + sc);
            return sc instanceof SurfaceControl ? (SurfaceControl) sc : null;
        } catch (Throwable t) {
            log("surfaceFromTask " + t);
            return null;
        }
    }

    /**
     * 抓某一层的画面（LayerCaptureArgs）。
     *
     * <p>宿主类在不同 ROM / 版本上不一样：
     * Android 14–16（realme UI 7 等）在 {@code android.window.ScreenCapture}；
     * Android 17（ColorOS 17）把 LayerCaptureArgs 挪到了
     * {@code android.window.ScreenCaptureInternal}，而 {@code ScreenCapture} 只剩新的
     * {@code ScreenCaptureParams}。旧代码只查前一个宿主，于是在 A17 上永远
     * “LayerCaptureArgs.Builder missing”、抓图返回 null —— 卡片空白/延迟就出在这里。
     * 这里两个宿主都试一遍。
     */
    private static Object captureLayers(ClassLoader cl, SurfaceControl sc) {
        ClassLoader loader = cl != null ? cl : SurfaceControl.class.getClassLoader();
        String[] hosts = {"android.window.ScreenCapture", "android.window.ScreenCaptureInternal"};
        for (String hostName : hosts) {
            Class<?> hostCl;
            try {
                hostCl = Class.forName(hostName, false, loader);
            } catch (Throwable t) {
                log(hostName + " not present");
                continue;
            }
            try {
                Class<?> argsCl = null;
                Class<?> builderCl = null;
                for (Class<?> inner : hostCl.getDeclaredClasses()) {
                    if (!"LayerCaptureArgs".equals(inner.getSimpleName())) continue;
                    argsCl = inner;
                    for (Class<?> b : inner.getDeclaredClasses()) {
                        if ("Builder".equals(b.getSimpleName())) builderCl = b;
                    }
                }
                if (builderCl == null) {
                    log(hostName + ": LayerCaptureArgs.Builder missing");
                    continue;
                }
                Object builder = builderCl.getConstructor(SurfaceControl.class).newInstance(sc);
                try {
                    builderCl.getMethod("setCaptureSecureLayers", boolean.class).invoke(builder, true);
                } catch (Throwable ignored) {
                }
                Object args = builderCl.getMethod("build").invoke(builder);
                Method cap = hostCl.getMethod("captureLayers", argsCl);
                cap.setAccessible(true);
                Object r = cap.invoke(null, args);
                log(hostName + " captureLayers -> "
                        + (r == null ? "null" : r.getClass().getSimpleName()));
                if (r != null) return r;
            } catch (Throwable t) {
                log(hostName + " captureLayers err " + t);
            }
        }
        // 最后再试无参形态的老接口
        try {
            Class<?> screenCapture = Class.forName("android.window.ScreenCapture", false, loader);
            for (Method m : screenCapture.getDeclaredMethods()) {
                if (!"captureLayers".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                m.setAccessible(true);
                try {
                    Object r = null;
                    if (p.length == 1 && SurfaceControl.class.isAssignableFrom(p[0])) {
                        r = m.invoke(null, sc);
                    } else if (p.length == 3 && SurfaceControl.class.isAssignableFrom(p[0])
                            && p[1] == Rect.class && p[2] == float.class) {
                        r = m.invoke(null, sc, new Rect(), 1.0f);
                    }
                    log("legacy captureLayers " + sig(m) + " -> "
                            + (r == null ? "null" : r.getClass().getSimpleName()));
                    if (r != null) return r;
                } catch (Throwable t) {
                    log("legacy captureLayers err " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            log("legacy captureLayers " + t);
        }
        return null;
    }

    /**
     * 该应用当前画面是不是竖着的（高 > 宽）。
     *
     * 坞窗布局用它在横屏下识别「竖屏应用」，给这类卡片多留一点边。读取顺序与 createPreview
     * 一致：先查已抓到的快照，再退回 FloatHandleInfo 自带的截图，都拿不到就按横屏处理。
     */
    public static boolean isPortraitContent(Object floatHandleInfo) {
        try {
            Bitmap b = sSnapshots.get(readTaskId(floatHandleInfo));
            if (b == null || b.isRecycled()) b = readInfoSnapshot(floatHandleInfo);
            return b != null && !b.isRecycled() && b.getHeight() > b.getWidth();
        } catch (Throwable t) {
            return false;
        }
    }

    private static Bitmap readInfoSnapshot(Object info) {
        if (info == null) return null;
        String[] names = {"snapShot", "snapshot", "mSnapShot", "mSnapshot", "icon"};
        for (String n : names) {
            try {
                Object snap = XposedHelpers.getObjectField(info, n);
                if (snap instanceof Bitmap && !((Bitmap) snap).isRecycled()
                        && ((Bitmap) snap).getWidth() > 8 && ((Bitmap) snap).getHeight() > 8) {
                    return (Bitmap) snap;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Bitmap softwareCopy(Bitmap src) {
        if (src == null || src.isRecycled()) return null;
        try {
            Bitmap argb = src;
            if (src.getConfig() == Bitmap.Config.HARDWARE) {
                argb = src.copy(Bitmap.Config.ARGB_8888, false);
                if (argb == null) return null;
            }
            if (argb.getWidth() > 720) {
                int h = (int) (720f * argb.getHeight() / argb.getWidth());
                Bitmap scaled = Bitmap.createScaledBitmap(argb, 720, Math.max(h, 1), true);
                if (scaled != argb && argb != src) argb.recycle();
                return scaled;
            }
            if (argb != src) return argb;
            Bitmap copy = argb.copy(Bitmap.Config.ARGB_8888, false);
            return copy != null ? copy : argb;
        } catch (Throwable t) {
            log("softwareCopy " + t);
            return src;
        }
    }

    private static int readTaskId(Object info) {
        if (info == null) return -1;
        try {
            return XposedHelpers.getIntField(info, "taskId");
        } catch (Throwable ignored) {
        }
        try {
            Object v = XposedHelpers.callMethod(info, "getTaskId");
            if (v instanceof Integer) return (Integer) v;
        } catch (Throwable ignored) {
        }
        return -1;
    }

    private static String sig(Method m) {
        StringBuilder sb = new StringBuilder("(");
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(ps[i].getSimpleName());
        }
        return sb.append(")").toString();
    }

    private static int dp(Context ctx, int dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                ctx.getResources().getDisplayMetrics());
    }
}
