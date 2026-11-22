package io.github.halcyon2026928.freewindow;

import android.content.ComponentName;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * Live dock preview layers. The Task SurfaceControl is reparented directly beneath the
 * card SurfaceView while the dock is open. Before screen-off it is returned to its WMS
 * parent; after unlock the current Task surface is looked up and reparented again.
 *
 * SurfaceView 用不透明格式，靠它自带的 mBackgroundControl（框架建的 setColorLayer 子层）
 * 上色 + show 来保证父层有可见内容，SurfaceFlinger 才会合成 reparent 进来的任务子层
 * （对齐 5200 官方 LivelyFloatHandleView 的做法，不再锁 canvas 清透明 buffer）。
 * The screen-off/on lifecycle is driven by ACTION_SCREEN_OFF,
 * ACTION_SCREEN_ON, and ACTION_USER_PRESENT because the RMX3820 controller lacks the
 * corresponding GT8 Pro FloatHandleController callbacks.
 */
public final class DockLive {
    private static final String TAG = "[灵动坞OS-live]";

    /**
     * 下一次给 FlexibleTaskInfo 建 ActivityOptions 时，要求系统不要放过渡动画。
     * 官方 getExpectedTransition 里认 "androidx.flexible.activityNoAnimation" 这个 extra，
     * 命中后把 transition 改成 NONE —— 也就是不会再有「恢复自由小窗飞出来」那段。
     * 只在 expandForLive 调用官方入口那一拍置位，用完即清。
     */
    private static boolean sNoAnimOnce;

    /**
     * 展开成可见小窗后，WMS 的打开过渡会把任务层临时挂到 leash 上。
     * 等过渡落定再 reparent，否则图层会被系统抢回去 → 卡片空白。
     */
    private static final long EXPAND_SETTLE_MS = 650L;
    private static final int ATTACH_MAX_ATTEMPT = 5;
    private static final long ATTACH_RETRY_MS = 400L;
    /**
     * 冷启动启动页（SplashActivity）守门：这种页面自己带 SurfaceView，
     * 挂进来会在静态快照和实时画面之间多闪一层。
     * 判定方式就看任务顶层 Activity 的类名是不是 splash —— 冷启动才走这个页面，
     * 热启动直接进主 Activity，不会被判到。
     * 250ms 查一次，最多 12 次（3 秒）；到点无条件放行，避免开屏广告把卡片一直冻住。
     */
    private static final long SPLASH_GUARD_MS = 250L;
    private static final int SPLASH_GUARD_MAX = 12;
    /**
     * 官方浮标打开动画（FlexibleFloatHandleAnimationSpec type=8）从 callAnimationStart 到
     * callAnimationEnd 之间，任务层还挂在动画 leash 上、几何/alpha 都停在起始帧（真机
     * dumpsys SurfaceFlinger：Layer[142] Task=… alpha=0、geomCrop=[0 0 0 0]、bounds 还是
     * 迷你条 1280x217）。这段时间 reparent 进去，任务层就停在起始帧不再前进 —— 卡片
     * 空白；等下一次系统事务（比如按音量键）才被推到终态。所以挂载必须等 callAnimationEnd；
     * 等不到时最多兜底这么久，避免永远不挂。
     */
    private static final long ANIMATION_GUARD_MS = 1500L;
    private static final int ANIMATION_WAIT_ATTEMPTS = 12;
    private static final String FLEXIBLE_WM_CLASS = "com.android.server.wm.FlexibleWindowManagerService";
    private static final String FLOAT_ANIM_CONTROLLER_CLASS =
            "com.android.server.wm.FlexibleFloatHandleAnimController";
    /** FlexibleTaskController.exitFlexibleTask 的「藏到浮标」state。 */
    private static final int STATE_MINI = 2;
    private static final int EXIT_REASON_TO_FLOAT = 16;

    private static final List<Slot> sSlots = new ArrayList<>();
    /** taskId -> 点击卡片画面时的恢复回调（由 MainHook 注册） */
    private static final java.util.Map<Integer, Runnable> sRestoreHandlers =
            new java.util.HashMap<>();
    private static ClassLoader sClassLoader;
    private static boolean sScreenReceiverRegistered;
    /** Prevent WMS transitions from pulling an attached live Task out of its card SurfaceView. */
    private static volatile boolean sSurfaceParentLockEnabled;

    private static final class Slot {
        final SurfaceView surfaceView;
        final ImageView still;
        final int taskId;
        final String pkg;
        /** 建卡时的 FloatHandleInfo，attach 时从 flexibleTaskInfo.getLaunchBounds() 取缩放基准。 */
        final Object floatHandleInfo;

        volatile boolean attached;
        volatile boolean attaching;
        volatile SurfaceControl taskSurface;
        volatile boolean parentLockLogged;
        SurfaceControl animationTaskSurface;
        SurfaceControl animationLeash;
        /** 官方打开动画起止（见 ANIMATION_GUARD_MS）：0 表示这次没走官方动画。 */
        volatile long animationStartedAt;
        volatile boolean animationFinished;
        boolean handedToSystemBack;
        /** prepareRestore 已把任务层还回 WMS、交给官方恢复动画：卡片 SurfaceView 销毁时不许再 hide。 */
        boolean handedToRestore;
        boolean alphaHoldLogged;
        boolean wasVisible = true;
        int recoveryGeneration;
        /** 是否已经为本卡走过一次官方「展开成可见小窗」；只走一次，避免反复触发过渡。 */
        boolean expanded;
        /** 展开前 FlexibleTaskInfo 的原始几何，坞窗收起/恢复前要还回去。 */
        Rect origLaunch;
        float origScale;
        float origRatio;
        /** 展开前任务本体（TaskExtImpl）的 scale；官方算落点时用它乘 launchBounds，不还原会弹大窗。 */
        float origTaskScale;
        boolean geomCaptured;

        /** 卡片预览区圆角（px）。官方画在 SurfaceView 自身（任务层 corner=0，靠父层裁剪）。 */
        final float cornerPx;

        // ===== 照官方 LivelyFloatHandleView 的 FloatHandleRecyclerLivelyViewHolder / MySurfaceView =====
        /** 官方 677：每个 holder 一个常驻事务对象，surfaceChanged 里设完一次 apply。 */
        final SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
        /** 官方 surfaceChanged 给的 surface 实际尺寸；缩放基准用它，不是 View 的宽高。 */
        volatile int surfaceWidth;
        volatile int surfaceHeight;
        /** 官方 MySurfaceView 的滚动裁剪状态（820-858）。 */
        final Rect surfaceRect = new Rect();
        final Rect containRect = new Rect();
        final Rect resultRect = new Rect();
        final Rect lastCropRect = new Rect();
        boolean cropInited;
        Boolean lastGone;

        // ===== 画面区热区窗口（自己开一个系统窗口，让系统签发输入通道） =====
        /** 盖在画面区上的透明可点 View；null 表示没挂。 */
        View touchPad;
        WindowManager.LayoutParams touchPadLp;
        WindowManager padWm;
        /** 上次贴上去的屏幕矩形，用来跳过没必要的 updateViewLayout。 */
        final Rect padRect = new Rect();

        // ===== 冷启动启动页守门 =====
        /** 已经为 splash 让路了几次（每次 250ms）。 */
        int splashGuardTicks;
        /** 守门已经放弃（超时硬挂过），本卡不再判 splash。 */
        boolean splashGuardDone;

        Slot(SurfaceView surfaceView, ImageView still, int taskId, String pkg,
             float cornerPx, Object floatHandleInfo) {
            this.surfaceView = surfaceView;
            this.still = still;
            this.taskId = taskId;
            this.pkg = pkg;
            this.cornerPx = cornerPx;
            this.floatHandleInfo = floatHandleInfo;
        }
    }

    private DockLive() {
    }

    static void setClassLoader(ClassLoader cl) {
        sClassLoader = cl;
    }

    /** 卡片画面区域的点击恢复：由 MainHook 在构建卡片时注册。 */
    static void setRestoreHandler(int taskId, Runnable handler) {
        synchronized (sRestoreHandlers) {
            sRestoreHandlers.put(taskId, handler);
        }
    }

    static void clearRestoreHandler(int taskId) {
        synchronized (sRestoreHandlers) {
            sRestoreHandlers.remove(taskId);
        }
    }

    private static void fireRestore(int taskId) {
        Runnable r;
        synchronized (sRestoreHandlers) {
            r = sRestoreHandlers.get(taskId);
        }
        if (r != null) {
            try {
                r.run();
            } catch (Throwable t) {
                XposedBridge.log(TAG + " restore handler failed: " + t);
            }
        }
    }

    /** 这个 taskId 是否正在坞窗卡片里（供官方浮标动画的 put 钩子判断要不要立刻收尾）。 */
    static boolean isLiveTask(int taskId) {
        synchronized (sSlots) {
            for (Slot slot : sSlots) {
                if (slot.taskId == taskId) return true;
            }
        }
        return false;
    }

    /** live 任务当前卡片圆角（px，四舍五入）；不在坞窗时返回 -1。供 extImpl 圆角 getter 覆盖用。 */
    static int cornerPxOf(int taskId) {
        synchronized (sSlots) {
            for (Slot slot : sSlots) {
                if (slot.taskId == taskId) return Math.round(slot.cornerPx);
            }
        }
        return -1;
    }

    /** Register both temporary WMS surfaces used by the FloatOpen animation. */
    static void registerAnimationSurfaces(int taskId, SurfaceControl taskSurface,
                                          SurfaceControl leash) {
        if (taskSurface == null && leash == null) return;
        synchronized (sSlots) {
            for (Slot slot : sSlots) {
                if (slot.taskId == taskId) {
                    slot.animationTaskSurface = taskSurface;
                    slot.animationLeash = leash;
                    slot.animationStartedAt = android.os.SystemClock.uptimeMillis();
                    slot.animationFinished = false;
                    slot.alphaHoldLogged = false;
                    XposedBridge.log(TAG + " registered FloatOpen surfaces taskId=" + taskId
                            + " task=" + taskSurface + " leash=" + leash);
                    return;
                }
            }
        }
    }

    /** Keep the temporary WMS Task surface hidden until the same surface is parented into its card. */
    static boolean shouldHoldAnimationTaskSurface(SurfaceControl surface) {
        Slot target = null;
        synchronized (sSlots) {
            for (Slot slot : sSlots) {
                if (slot.animationTaskSurface == surface || slot.animationLeash == surface) {
                    target = slot;
                    break;
                }
            }
        }
        if (target == null || target.attached || target.attaching) return false;
        if (!target.alphaHoldLogged) {
            target.alphaHoldLogged = true;
            XposedBridge.log(TAG + " holding transient Task surface at alpha=0 taskId="
                    + target.taskId + " surface=" + surface);
        }
        return true;
    }

    /** 供 FlexibleWindowUtils.setFlexibleTaskOptions 的钩子判断：这一拍要不要免动画。 */
    static void onFloatAnimationEnd(int taskId) {
        final Slot target;
        synchronized (sSlots) {
            Slot found = null;
            for (Slot slot : sSlots) {
                if (slot.taskId == taskId) {
                    found = slot;
                    break;
                }
            }
            target = found;
        }
        if (target == null) return;
        target.animationFinished = true;
        XposedBridge.log(TAG + " float open animation ended taskId=" + taskId);
        final ClassLoader cl = sClassLoader;
        if (cl != null && !target.attached && !target.attaching) {
            target.surfaceView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    attachAttempt(target, cl, 0);
                }
            }, 80L);
        }
    }

    /** 这个层是否是已经挂在坞窗卡片里的任务层（alpha 兜底用）。 */
    static boolean isAttachedLiveTaskSurface(SurfaceControl surface) {
        if (surface == null) return false;
        synchronized (sSlots) {
            for (Slot slot : sSlots) {
                if (slot.attached && sameSurface(surface, slot.taskSurface)) return true;
            }
        }
        return false;
    }

    /**
     * 当前是否有卡片处于「实时画面挂载」状态。
     * 实时挂载时卡片里的任务持有输入焦点，音量键会被派发给它，应用吞掉后既不调音量
     * 也不弹音量条（真机：坞窗关着按 3 次 1→4，实时画面时按 3 次一直是 1）。
     * 所以这一时期音量键要在 policy 阶段自己接过来，不再往下派发。
     */
    static boolean hasAttachedLiveSlot() {
        synchronized (sSlots) {
            for (Slot slot : sSlots) {
                if (slot.attached) return true;
            }
        }
        return false;
    }

    /**
     * 这个包名是不是正挂在卡片里的预览应用。
     * 卡片只是预览画面、不接受任何操作，它的窗口不该抢全局焦点（见
     * MainHook.enforcePreviewUnfocusable 的注释）。
     */
    static boolean isPreviewPkg(String pkg) {
        if (pkg == null) return false;
        synchronized (sSlots) {
            for (Slot slot : sSlots) {
                if (slot.attached && pkg.equals(slot.pkg)) return true;
            }
        }
        return false;
    }

    /** 官方打开动画（type=8）还没收尾时不许挂载，见 ANIMATION_GUARD_MS。 */
    private static boolean waitingForOpenAnimation(Slot slot) {
        long startedAt = slot.animationStartedAt;
        if (startedAt <= 0L || slot.animationFinished) return false;
        return android.os.SystemClock.uptimeMillis() - startedAt < ANIMATION_GUARD_MS;
    }

    /** 供 FlexibleWindowUtils.setFlexibleTaskOptions 的钩子判断：这一拍要不要免动画。 */
    static boolean consumeNoAnimOnce() {
        boolean v = sNoAnimOnce;
        sNoAnimOnce = false;
        return v;
    }

    /** 坞窗卡片是否显示实时画面（设置页「实时画面」开关）。关闭时卡片只显示静态快照。 */
    private static volatile boolean sLivePreviewEnabled = true;

    /** 设置页改了「实时画面」就调这里；对下次打开坞窗建出来的卡片生效。 */
    static void setLivePreviewEnabled(boolean enabled) {
        if (sLivePreviewEnabled == enabled) return;
        sLivePreviewEnabled = enabled;
        XposedBridge.log(TAG + " live preview enabled=" + enabled);
    }

    static boolean isLivePreviewEnabled() {
        return sLivePreviewEnabled;
    }

    /** 卡片预览区：静态快照兜底 + TextureView 承接任务实时图层。 */
    static View createSlot(Context ctx, Object floatHandleInfo,
                           int taskId, String pkg, ClassLoader cl) {
        ensureScreenReceiver(ctx);
        XposedBridge.log(TAG + " createSlot taskId=" + taskId + " pkg=" + pkg
                + " livePreview=" + sLivePreviewEnabled);
        FrameLayout slot = new FrameLayout(ctx);
        // 静态快照 still 是一整块方图铺满卡片，它的四个角只能靠 View 层裁剪修（之前露直角）。
        // 实时画面那一路不受这里影响：SurfaceView 是独立 surface 图层，不吃父 View 的 canvas clip，
        // 它自己的圆角由 prepareSurfaceViewChip 里的 setCornerRadius 负责。
        final float slotCornerPx = ctx.getResources().getDisplayMetrics().density
                * MainHook.CARD_ITEM_CORNER_DP;
        slot.setClipToOutline(true);
        slot.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), slotCornerPx);
            }
        });
        ImageView still = LivelyHook.createPreview(ctx, floatHandleInfo, cl);
        if (taskId > 0) {
            // Keep the static snapshot on the same restore path as the live SurfaceView.
            // It can receive the tap before SurfaceView input is active during first attach.
            still.setClickable(true);
            still.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    XposedBridge.log(TAG + " snapshot click taskId=" + taskId);
                    fireRestore(taskId);
                }
            });
        }
        slot.addView(still, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (taskId <= 0) {
            return slot;
        }
        // 「实时画面」关掉时，这一路到此为止：卡片就一张静态快照。
        // 好处是卡片里没有任务层、也没有热区窗口，点击落在这张图上（走 fireRestore），
        // 上下滑动直接交给坞窗自己的 ScrollView，滚动是原生的。
        if (!sLivePreviewEnabled) {
            XposedBridge.log(TAG + " createSlot static-only taskId=" + taskId);
            return slot;
        }

        // 回到 SurfaceView：TextureView 自己也要渲染内容，任务层挂上去之后画面是静止的
        // （实测不动），所以实时画面只能走 SurfaceView 的独立图层。
        // 副作用是它会压住坞窗里的三点菜单 —— 菜单改成弹到坞窗面板外侧（见 MainHook.showAppMenu），
        // 只要不和画面区域重叠就互不影响。
        final SurfaceView surfaceView = new SurfaceView(ctx);
        // 让 MainHook.refreshPreviewCrop 能在卡片子树里递归找到它（滚动时裁到滚动区底边）。
        surfaceView.setTag("freewindow.snapshotPreview");
        try {
            surfaceView.setZOrderOnTop(true);
            // 不设 TRANSLUCENT：官方（5200 LivelyFloatHandleView）用的是不透明格式，
            // 靠 SurfaceView 自带的 mBackgroundControl（setColorLayer 子层）提供内容，
            // 父层就不会被判 "nothing to draw"，挂在下面的任务层才合成得出来。
        } catch (Throwable t) {
            XposedBridge.log(TAG + " setZOrderOnTop failed: " + t);
        }
        // 官方的 MySurfaceView 还有两件事（LivelyFloatHandleView 679-680 / 860-877）：
        //   1) getWrapper().getExtImpl().setNeedSkipAlpha(true)
        //   2) 圆角设在 SurfaceView 自身并打开 SurfaceView.mRtDrivenClipping
        //      —— 后者让 ViewRoot 按 SurfaceView 的 outline 裁剪，任务层作为子层跟着被裁圆角，
        //      这才是"画面装在 SurfaceView 里"的关键。少了它任务层只是挂在下面的一张直角矩形。
        // 三层（SurfaceView、Background、任务层）统一圆角：值取官方 mItemRadius（35dp，
        // 资源 0x0c050160），不是面板挖洞那套 20dp —— 官方卡片画面本来就比面板洞更圆。
        final float cornerPx = surfaceView.getResources().getDisplayMetrics().density
                * MainHook.CARD_ITEM_CORNER_DP;
        prepareSurfaceViewChip(surfaceView, cornerPx);

        slot.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        // 画面区域点击：任务层挂了 dropInput（输入不投递给应用），点击应当落到这个
        // SurfaceView 上。它的触摸属于坞窗窗口自己的 input channel，点击直接触发恢复。
        surfaceView.setClickable(true);
        surfaceView.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;

            @Override
            public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        downX = event.getX();
                        downY = event.getY();
                        XposedBridge.log(TAG + " preview touch down taskId=" + taskId
                                + " x=" + downX + " y=" + downY);
                        return true;
                    case android.view.MotionEvent.ACTION_UP:
                        float dx = Math.abs(event.getX() - downX);
                        float dy = Math.abs(event.getY() - downY);
                        XposedBridge.log(TAG + " preview touch up taskId=" + taskId
                                + " dx=" + dx + " dy=" + dy);
                        if (dx < 40f && dy < 40f) {
                            fireRestore(taskId);
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });

        final Slot state = new Slot(surfaceView, still, taskId, pkg, cornerPx, floatHandleInfo);
        synchronized (sSlots) {
            sSlots.add(state);
        }
        sSurfaceParentLockEnabled = true;

        final ClassLoader finalCl = cl != null ? cl : sClassLoader;
        // 卡片贴到坞窗上之后才有真实屏幕矩形：延迟 300ms 开热区（静态快照态也开，
        // 画面还没动起来时就能点）；卡片离开窗口立刻撤，避免任务还回 WMS 后这块还被挡着。
        surfaceView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (surfaceView.getWindowToken() == null) return;
                showTouchPad(state);
            }
        }, 300L);
        surfaceView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                hideTouchPad(state);
            }
        });
        // 展开要等 SurfaceView 量好尺寸（卡片在屏幕上的矩形），所以放到第一次 attach 尝试里，
        // 见 attachAttempt → expandForLive。
        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                XposedBridge.log(TAG + " surfaceCreated taskId=" + state.taskId);
                // 官方是在 onAttachedToWindow 里设置圆角和 rtDrivenClipping；此时 SurfaceView
                // 的图层已经起来，再设一次才真正落到 SurfaceControl 上。
                prepareSurfaceViewChip(surfaceView, state.cornerPx, bgColorOf(surfaceView.getContext()));
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                XposedBridge.log(TAG + " surfaceChanged taskId=" + state.taskId
                        + " size=" + width + "x" + height);
                // 官方 LivelyFloatHandleView 1037-1069 就是在 surfaceChanged 里：
                //   记下回调给的宽高 → setResizeBackgroundColor(mBgColor) → 重新 reparent + 重算 scale。
                // 这里照搬同一口径（缩放基准换成回调宽高，见 applyLiveTransaction）。
                state.surfaceWidth = width;
                state.surfaceHeight = height;
                resetCrop(state);
                prepareSurfaceViewChip(surfaceView, state.cornerPx, bgColorOf(surfaceView.getContext()));
                if (state.attached) {
                    Object task = findTask(state.taskId, finalCl);
                    // 已挂上：只按新尺寸重算并重新 reparent（官方 surfaceChanged 就是这样），
                    // 不再走 resumeTask 那一套（避免每次 surface 变化都把顶层 activity 再 resume）。
                    if (task != null && isVisible(task)) {
                        applyLiveTransaction(state, task);
                        return;
                    }
                }
                scheduleAttach(state, finalCl);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                XposedBridge.log(TAG + " surfaceDestroyed taskId=" + state.taskId);
                if (!state.handedToSystemBack && !state.handedToRestore) {
                    detach(state, finalCl, true, false);
                } else if (state.handedToRestore) {
                    XposedBridge.log(TAG + " surfaceDestroyed skipped hide, task already handed to restore"
                            + " taskId=" + state.taskId);
                }
            }
        });
        XposedBridge.log(TAG + " createSlot done taskId=" + taskId + " slotChildren="
                + ((ViewGroup) slot).getChildCount() + " slots=" + sSlots.size());
        return slot;
    }

    private static synchronized void ensureScreenReceiver(Context context) {
        if (sScreenReceiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            context.getApplicationContext().registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    XposedBridge.log(TAG + " screen event=" + intent.getAction());
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        onScreenTurningOff();
                        return;
                    }
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override public void run() {
                            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                                android.app.KeyguardManager km = (android.app.KeyguardManager)
                                        context.getSystemService(Context.KEYGUARD_SERVICE);
                                if (km != null && km.isKeyguardLocked()) {
                                    XposedBridge.log(TAG + " screen-on deferred: keyguard still locked");
                                    return;
                                }
                            }
                            onScreenInteractive();
                        }
                    }, 900L);
                }
            }, filter);
            sScreenReceiverRegistered = true;
            XposedBridge.log(TAG + " screen receiver registered");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " screen receiver registration failed: " + t);
        }
    }

    /**
     * 照官方 MySurfaceView 的 onAttachedToWindow 准备卡片里的那个 SurfaceView：
     *   - setNeedSkipAlpha(true)：Oplus 扩展，避免子层被 alpha 跳过
     *   - setCornerRadius(radius)：圆角画在 SurfaceView 自身
     *   - SurfaceView.mRtDrivenClipping = true：让 ViewRoot 按 outline 裁剪，
     *     挂在它下面的任务层才会被裁成同样的圆角（否则只是张直角矩形贴在下面）
     */
    private static void prepareSurfaceViewChip(SurfaceView surfaceView, float cornerPx) {
        prepareSurfaceViewChip(surfaceView, cornerPx, bgColorOf(surfaceView.getContext()));
    }

    private static void prepareSurfaceViewChip(SurfaceView surfaceView, float cornerPx, int bgColor) {
        try {
            Object wrapper = XposedHelpers.callMethod(surfaceView, "getWrapper");
            Object ext = XposedHelpers.callMethod(wrapper, "getExtImpl");
            XposedHelpers.callMethod(ext, "setNeedSkipAlpha", true);
            XposedBridge.log(TAG + " setNeedSkipAlpha ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " setNeedSkipAlpha unavailable: " + t.getMessage());
        }
        try {
            XposedHelpers.callMethod(surfaceView, "setCornerRadius", cornerPx);
            XposedBridge.log(TAG + " surfaceView.setCornerRadius ok radius=" + cornerPx);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " surfaceView.setCornerRadius unavailable: " + t.getMessage());
        }
        try {
            java.lang.reflect.Field rtDrivenClipping =
                    SurfaceView.class.getDeclaredField("mRtDrivenClipping");
            try {
                java.lang.reflect.Field accessFlags = java.lang.reflect.Field.class.getDeclaredField("accessFlags");
                accessFlags.setAccessible(true);
                int modifiers = accessFlags.getInt(rtDrivenClipping);
                accessFlags.setInt(rtDrivenClipping, modifiers & ~0x10);
            } catch (Throwable ignored) {
            }
            rtDrivenClipping.setAccessible(true);
            rtDrivenClipping.set(surfaceView, Boolean.TRUE);
            XposedBridge.log(TAG + " mRtDrivenClipping=true ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " mRtDrivenClipping unavailable: " + t.getMessage());
        }
        // 官方 MySurfaceView.onAttachedToWindow 860-877 的第三步：setResizeBackgroundColor(mBgColor)。
        // framework 只把它记到 mBackgroundColor，真正上色在 updateBackgroundColor / 我们复用事务里的 setColor。
        try {
            XposedHelpers.callMethod(surfaceView, "setResizeBackgroundColor", bgColor);
            XposedBridge.log(TAG + " setResizeBackgroundColor ok color=#" + Integer.toHexString(bgColor));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " setResizeBackgroundColor unavailable: " + t.getMessage());
        }
        // 卡片底下的 "Background for ..." 纯色子层：mDisableBackgroundLayer=false 时 framework
        // 会按 needDrawBackground 自己 show 它，我们再把 alpha 拉满（见 applyBackgroundLayer）。
        // 用户实测：放行这一层后卡片效果更好（画面未就绪时不是空白，卡片有底色兜着），
        // 所以保留放行，不再常驻隐藏。
        try {
            java.lang.reflect.Field disableBg =
                    SurfaceView.class.getDeclaredField("mDisableBackgroundLayer");
            disableBg.setAccessible(true);
            disableBg.set(surfaceView, Boolean.FALSE);
            XposedBridge.log(TAG + " mDisableBackgroundLayer=false (bg layer allowed)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " mDisableBackgroundLayer unavailable: " + t.getMessage());
        }
        // 画面还没挂上时，SurfaceView 自己的 BLAST 层是个空层（没 buffer），它压在
        // 静态快照上面 → 那一瞬间看着就是"静态 → 透空 → 画面"。把它先 hide 掉：
        // 空层不参与合成，静态图一路顶到画面挂上为止；挂载成功见 applyLiveTransaction 再 show。
        hideBlastLayer(surfaceView, true);
    }

    /**
     * 临时隐藏/放出 SurfaceView 自己的 BLAST 层（mBlastSurfaceControl）。
     * 只改可见性，不动层的结构 —— 框架照旧往里送 buffer，恢复一下就回来。
     */
    private static void hideBlastLayer(SurfaceView surfaceView, boolean hidden) {
        try {
            java.lang.reflect.Field f = SurfaceView.class.getDeclaredField("mBlastSurfaceControl");
            f.setAccessible(true);
            Object obj = f.get(surfaceView);
            if (!(obj instanceof SurfaceControl)) return;
            SurfaceControl blast = (SurfaceControl) obj;
            if (!blast.isValid()) return;
            SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
            // hide/show 是 @hide 方法，编译期看不到，走反射（txCall）。
            if (hidden) txCall(tx, "hide", blast);
            else txCall(tx, "show", blast);
            tx.apply();
            XposedBridge.log(TAG + " blast layer hidden=" + hidden);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " blast layer toggle failed hidden=" + hidden + ": " + t.getMessage());
        }
    }

    /**
     * 卡片的 Background 底色。官方取资源 201719941（mBgColor，深浅色各一份）；
     * 这里跟坞窗面板同色：深色 #1E1E1E、浅色 #E5E5E5，由官方 1059-1065 的
     * setAlpha(0.98/0.95) + setColor + show 三层事务刷到 SurfaceControl 上。
     */
    static int bgColorOf(Context ctx) {
        boolean night;
        try {
            int mode = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            night = mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            night = false;
        }
        return night ? 0xFF1E1E1E : 0xFFE5E5E5;
    }

    private static boolean isNight(Context ctx) {
        try {
            int mode = ctx.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return mode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    static void releaseAll() {
        sSurfaceParentLockEnabled = false;
        final List<Slot> slots;
        synchronized (sSlots) {
            slots = new ArrayList<>(sSlots);
            sSlots.clear();
        }
        for (Slot slot : slots) {
            hideTouchPad(slot);
            if (slot.handedToSystemBack) {
                XposedBridge.log(TAG + " releaseAll skipped handed-off Back taskId=" + slot.taskId);
                continue;
            }
            detach(slot, sClassLoader, true, false);
            collapseToHandle(slot, sClassLoader);
        }
    }

    /** Hand this live Task surface to WindowManager's native Back transition. */
    static boolean handoffTaskToSystemBack(int taskId) {
        synchronized (sSlots) {
            for (Slot slot : sSlots) {
                if (slot.taskId == taskId && slot.attached) {
                    releaseSurfaceParentLock();
                    // Put the Task surface back under its WMS parent and make it visible before
                    // the dock's SurfaceView is removed. Do not call collapseToHandle: native
                    // Back owns the following window transition.
                    // 用户实测：返回手势一触发，还层后的任务立刻在屏幕上露出一整块大窗
                    // （坞窗右半边被任务本体盖住）。所以还层时把任务层一起藏掉（hideLayer=true），
                    // 让 Back 转场自己去决定什么时候 show；坞窗收起这段人眼看不到任务本体。
                    if (!detach(slot, sClassLoader, true, true)) {
                        XposedBridge.log(TAG + " Back handoff failed to detach Task surface taskId="
                                + taskId);
                        return false;
                    }
                    slot.handedToSystemBack = true;
                    slot.recoveryGeneration++;
                    XposedBridge.log(TAG + " Back transition owns visible WMS Task surface taskId="
                            + taskId);
                    return true;
                }
            }
        }
        XposedBridge.log(TAG + " Back handoff skipped: no attached slot taskId=" + taskId);
        return false;
    }

    /** Return task surfaces to WindowManager before keyguard/screen-off transitions touch them. */
    static void onScreenTurningOff() {
        final List<Slot> slots;
        synchronized (sSlots) { slots = new ArrayList<>(sSlots); }
        XposedBridge.log(TAG + " official screenTurnedOff slots=" + slots.size());
        MainHook.setDockTouchable(false);
        for (Slot slot : slots) {
            slot.recoveryGeneration++;
            if (slot.attached) detach(slot, sClassLoader, true, false);
        }
    }

    /** Re-fetch and reparent the current Task surface repeatedly as unlock finishes. */
    static void onScreenInteractive() {
        MainHook.setDockTouchable(true);
        final List<Slot> slots;
        synchronized (sSlots) { slots = new ArrayList<>(sSlots); }
        XposedBridge.log(TAG + " official screenTurnedOn slots=" + slots.size());
        for (Slot slot : slots) {
            final int generation = ++slot.recoveryGeneration;
            scheduleUnlockAttach(slot, generation, 0, 0L);
        }
    }

    private static void scheduleUnlockAttach(final Slot slot, final int generation,
                                             final int attempt, long delayMs) {
        final long[] delays = {0L, 250L, 500L, 900L, 1500L, 2500L};
        if (attempt >= delays.length) {
            XposedBridge.log(TAG + " unlock reparent exhausted taskId=" + slot.taskId);
            return;
        }
        slot.surfaceView.postDelayed(new Runnable() {
            @Override public void run() {
                synchronized (sSlots) {
                    if (!sSlots.contains(slot) || slot.recoveryGeneration != generation) return;
                }
                boolean hostAttached = slot.surfaceView.isAttachedToWindow();
                boolean hostSurfaceValid;
                try {
                    hostSurfaceValid = slot.surfaceView.getHolder().getSurface().isValid();
                } catch (Throwable t) {
                    hostSurfaceValid = false;
                }
                XposedBridge.log(TAG + " unlock reparent attempt=" + (attempt + 1)
                        + " taskId=" + slot.taskId + " hostAttached=" + hostAttached
                        + " hostSurfaceValid=" + hostSurfaceValid + " attached=" + slot.attached);
                if (hostAttached && hostSurfaceValid) {
                    if (slot.attached) reapply(slot, sClassLoader);
                    else attachAttempt(slot, sClassLoader, 0);
                    if (slot.attached) {
                        XposedBridge.log(TAG + " unlock reparent success taskId=" + slot.taskId
                                + " attempt=" + (attempt + 1));
                        slot.recoveryGeneration++;
                        return;
                    }
                }
                scheduleUnlockAttach(slot, generation, attempt + 1,
                        attempt + 1 < delays.length ? delays[attempt + 1] : 0L);
            }
        }, delayMs);
    }

    /**
     * 点击卡片恢复前先把图层还给系统：此时任务是可见的且在卡片里，
     * 必须先 reparent 回主屏层级，官方的恢复入口才能接管。
     */
    static void prepareRestore(int taskId) {
        Slot target = null;
        synchronized (sSlots) {
            for (Slot slot : sSlots) {
                if (slot.taskId == taskId) {
                    target = slot;
                    break;
                }
            }
        }
        if (target == null) {
            XposedBridge.log(TAG + " prepareRestore: no slot for taskId=" + taskId);
            return;
        }
        // Restore can be tapped before the live preview finishes attaching. In either case,
        // release the parent lock before returning the Task surface to WMS; otherwise the
        // reparent hook redirects this transaction back into the SurfaceView being removed.
        releaseSurfaceParentLock();
        target.recoveryGeneration++;
        target.handedToRestore = true;
        // 点卡片恢复走官方入口，它读的是浮标 info 里的几何；先把卡片几何还回去
        restoreFloatGeometry(target, sClassLoader);
        // 照第三版（无闪大窗版本）的口径：还层时把任务层藏掉（hideLayer=true），
        // 官方动画接管时才会把它 show 出来。层隐藏期间屏幕上没有任何任务帧，
        // 也就不存在「还层到动画接管之间一闪而过的大窗」。
        detach(target, sClassLoader, true, true);
        synchronized (sSlots) {
            sSlots.remove(target);
        }
    }

    /** 单个卡片退出实时画面（用于"取消运行"）：还原图层并回到隐藏态。 */
    static void releaseTask(int taskId) {
        Slot target = null;
        synchronized (sSlots) {
            for (Slot slot : sSlots) {
                if (slot.taskId == taskId) {
                    target = slot;
                    break;
                }
            }
            if (target != null) {
                sSlots.remove(target);
            }
        }
        if (target != null) {
            detach(target, sClassLoader, true, false);
            collapseToHandle(target, sClassLoader);
        }
    }

    /** 展开小窗后隔一段时间再挂图层：WMS 的打开过渡（leash 重挂）要先走完。 */
    private static void scheduleAttach(final Slot slot, final ClassLoader cl) {
        slot.surfaceView.postDelayed(new Runnable() {
            @Override
            public void run() {
                attachAttempt(slot, cl, 0);
            }
        }, EXPAND_SETTLE_MS);
    }

    /**
     * 任务顶层 Activity 是冷启动启动页（类名含 splash）就返回类名，否则返回 null。
     * 只认 splash：冷启动一定过这个页面，热启动直接进主 Activity，不会误判。
     */
    private static String splashTopClassName(Object task) {
        try {
            Object rec = null;
            try {
                rec = XposedHelpers.callMethod(task, "getTopMostActivity");
            } catch (Throwable ignored) {
            }
            if (rec == null) {
                try {
                    rec = XposedHelpers.callMethod(task, "getTopActivity");
                } catch (Throwable ignored) {
                }
            }
            if (rec == null) return null;
            String cls = null;
            try {
                Object comp = XposedHelpers.callMethod(rec, "getActivityComponent");
                if (comp instanceof ComponentName) cls = ((ComponentName) comp).getClassName();
            } catch (Throwable ignored) {
            }
            if (cls == null) {
                try {
                    Object comp = XposedHelpers.getObjectField(rec, "mActivityComponent");
                    if (comp instanceof ComponentName) cls = ((ComponentName) comp).getClassName();
                } catch (Throwable ignored) {
                }
            }
            if (cls == null) {
                try {
                    cls = (String) XposedHelpers.getObjectField(rec, "shortComponentName");
                } catch (Throwable ignored) {
                }
            }
            if (cls == null) return null;
            return cls.toLowerCase(java.util.Locale.US).contains("splash") ? cls : null;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " splash check failed taskId=" + task + ": " + t);
            return null;
        }
    }

    /**
     * 挂图层前先确认任务真的可见：收成浮球后 WMS 会把 activity 设成 clientVisible=false，
     * 此时 reparent 一个不可见的图层，卡片只会是黑的（3820 实测 wasVisible=false）。
     * 不可见就先走官方展开入口让它变可见，再等下一次重试。
     */
    private static void attachAttempt(Slot slot, ClassLoader cl, int attempt) {
        if (slot.attached) return;
        synchronized (sSlots) {
            if (!sSlots.contains(slot)) return;
        }
        try {
            if (!slot.surfaceView.isAttachedToWindow()) {
                XposedBridge.log(TAG + " attach attempt skipped: host detached taskId=" + slot.taskId);
                return;
            }
            Object task = findTask(slot.taskId, cl);
            if (task == null) {
                XposedBridge.log(TAG + " attach: task not found taskId=" + slot.taskId);
                return;
            }
            // 冷启动启动页守门：splash 页面自己的 SurfaceView 层挂进来会多闪一层，
            // 这期间先不挂，卡片继续显示静态快照；等它离开 splash 再挂。
            if (!slot.splashGuardDone) {
                String topCls = splashTopClassName(task);
                if (topCls != null) {
                    if (slot.splashGuardTicks < SPLASH_GUARD_MAX) {
                        slot.splashGuardTicks++;
                        XposedBridge.log(TAG + " splash guard hold taskId=" + slot.taskId
                                + " tick=" + slot.splashGuardTicks + " top=" + topCls);
                        final int sameAttempt = attempt;
                        slot.surfaceView.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                attachAttempt(slot, cl, sameAttempt);
                            }
                        }, SPLASH_GUARD_MS);
                        return;
                    }
                    // 到点还在 splash（多半是开屏广告）：放弃守门，硬挂，卡片不许一直冻着。
                    slot.splashGuardDone = true;
                    XposedBridge.log(TAG + " splash guard timeout, attach anyway taskId="
                            + slot.taskId + " top=" + topCls);
                } else if (slot.splashGuardTicks > 0) {
                    XposedBridge.log(TAG + " splash guard release taskId=" + slot.taskId
                            + " tick=" + slot.splashGuardTicks);
                    slot.splashGuardTicks = 0;
                }
            }
            if (!isVisible(task)) {
                if (!slot.expanded) expandForLive(slot, cl);
                if (attempt + 1 < ATTACH_MAX_ATTEMPT) {
                    final int next = attempt + 1;
                    XposedBridge.log(TAG + " attach waiting visible attempt=" + next
                            + " taskId=" + slot.taskId);
                    slot.surfaceView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            attachAttempt(slot, cl, next);
                        }
                    }, ATTACH_RETRY_MS);
                } else {
                    XposedBridge.log(TAG + " attach gave up: task still invisible taskId="
                            + slot.taskId);
                }
                return;
            }
            if (waitingForOpenAnimation(slot) && attempt < ANIMATION_WAIT_ATTEMPTS) {
                final int next = attempt + 1;
                XposedBridge.log(TAG + " attach waiting open animation attempt=" + next
                        + " taskId=" + slot.taskId);
                slot.surfaceView.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        attachAttempt(slot, cl, next);
                    }
                }, 120L);
                return;
            }
            reparent(slot, task);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " attach attempt failed taskId=" + slot.taskId + ": " + t);
        }
    }

    /**
     * 把 Task 的实时图层直接挂到卡片 SurfaceView。
     *
     * <p>官方展开会起一个 WMS 打开过渡，过渡期间任务层被挂在 transition-leash 上；
     * 我们挂早了，过渡结束（finish）时系统会把任务层 reparent 回 taskParent，
     * 把我们的挂载覆盖掉（3820 dumpsys SurfaceFlinger 实测：attach 成功后
     * Task=484 又回到 parentId=11）。所以挂完之后还要补几次，等过渡收尾。</p>
     */
    private static void reparent(Slot slot, Object task) {
        slot.attaching = true;
        boolean applied;
        try {
            applied = applyLiveTransaction(slot, task);
        } finally {
            slot.attaching = false;
        }
        if (applied) {
            try {
                setVisibleRequested(task, true);
                slot.attached = true;
                resumeTask(task);
                // 实时层已经顶上来了，静态快照就没必要占着位置（也不该盖在三点菜单上）
                final ImageView stillView = slot.still;
                slot.surfaceView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (stillView != null) stillView.setVisibility(View.GONE);
                    }
                });
                XposedBridge.log(TAG + " live attached taskId=" + slot.taskId
                        + " pkg=" + slot.pkg + " mode=reparent");
                MainHook.enforcePreviewUnfocusable(slot.pkg);
                scheduleReassert(slot);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " attach post failed taskId=" + slot.taskId + ": " + t);
            }
        }
    }

    /** 过渡收尾会把图层抢回去：挂上之后再补几次（幂等，重设同一套 reparent/scale）。 */
    private static void scheduleReassert(final Slot slot) {
        final long[] delays = {300L, 800L, 1500L, 2500L, 4000L, 6000L};
        for (final long delay : delays) {
            slot.surfaceView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    reassertLive(slot);
                }
            }, delay);
        }
    }

    private static void reassertLive(Slot slot) {
        if (!slot.attached) return;
        synchronized (sSlots) {
            if (!sSlots.contains(slot)) return;
        }
        try {
            Object task = findTask(slot.taskId, sClassLoader);
            if (task == null) return;
            if (!isVisible(task)) {
                XposedBridge.log(TAG + " reassert skipped: task invisible taskId=" + slot.taskId);
                return;
            }
            boolean ok = applyLiveTransaction(slot, task);
            XposedBridge.log(TAG + " reassert live taskId=" + slot.taskId + " ok=" + ok);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " reassert failed taskId=" + slot.taskId + ": " + t);
        }
    }

    /** 计算几何并把任务层 reparent/缩放一次。成功返回 true。 */
    private static boolean applyLiveTransaction(Slot slot, Object task) {
        try {
            Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
            // 官方缩放基准是 surfaceChanged 回调给的 surface 尺寸（LivelyFloatHandleView 1039-1041）。
            // 但坞窗是 system_server 直连的独立窗口：回调尺寸是 surface buffer 尺寸，实测会比卡片
            // View 尺寸略小（上滑收坞窗→再打开、任务从迷你态展开时偏差最明显），按它算 scale
            // 画面会缩一圈、四周露出卡片底色（真机复现的"厚毛边"）。所以以卡片 View 尺寸为主，
            // 回调尺寸只作兜底；日志里两个都打出来便于核对。
            int width = slot.surfaceView.getWidth() > 0 ? slot.surfaceView.getWidth() : slot.surfaceWidth;
            int height = slot.surfaceView.getHeight() > 0 ? slot.surfaceView.getHeight() : slot.surfaceHeight;
            if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0
                    || width <= 0 || height <= 0) {
                XposedBridge.log(TAG + " attach: bad geometry taskId=" + slot.taskId
                        + " bounds=" + bounds + " surface=" + width + "x" + height);
                return false;
            }

            Object taskScObj = XposedHelpers.callMethod(task, "getSurfaceControl");
            Object surfaceScObj = surfaceControlOf(slot.surfaceView);
            if (!(taskScObj instanceof SurfaceControl) || !(surfaceScObj instanceof SurfaceControl)) {
                XposedBridge.log(TAG + " attach: surface control unavailable taskId=" + slot.taskId);
                return false;
            }
            SurfaceControl taskSc = (SurfaceControl) taskScObj;
            SurfaceControl surfaceSc = (SurfaceControl) surfaceScObj;
            if (!taskSc.isValid() || !surfaceSc.isValid()) {
                XposedBridge.log(TAG + " attach: invalid surface control taskId=" + slot.taskId);
                return false;
            }

            // 官方 LivelyFloatHandleView 1046-1058：缩放基准取 flexibleTaskInfo.getLaunchBounds()
            // （任务窗口的完整布局尺寸），不是 task.getBounds()。float handle 下 getBounds 可能是
            // 缩放后的迷你尺寸，用它算 wScale/hScale 会把画面放歪。
            Rect launch = null;
            try {
                Object fti = slot.floatHandleInfo == null ? null
                        : XposedHelpers.getObjectField(slot.floatHandleInfo, "flexibleTaskInfo");
                if (fti != null) launch = (Rect) XposedHelpers.callMethod(fti, "getLaunchBounds");
            } catch (Throwable t) {
                XposedBridge.log(TAG + " launchBounds unavailable taskId=" + slot.taskId + ": " + t);
            }
            Rect base = (launch != null && launch.width() > 0 && launch.height() > 0) ? launch : bounds;
            float wScale = width / (float) base.width();
            float hScale = height / (float) base.height();

            slot.wasVisible = isVisible(task);
            // 官方 677/1058：每个 holder 复用一个 SurfaceControl.Transaction，surfaceChanged 里
            // 设完一次 apply。事务复用后每次都要把全部属性重设一遍（下面逐项对齐官方）。
            SurfaceControl.Transaction tx = slot.transaction;
            tx.reparent(taskSc, surfaceSc);
            txCall(tx, "setPosition", taskSc, 0f, 0f);
            // 官方 setCornerRadius(taskSurfaceControl, 0.0f)：任务层自己不画圆角。
            // 圆角由 SurfaceView 自身（setCornerRadius + mRtDrivenClipping）把整棵子树裁出来。
            txCall(tx, "setCornerRadius", taskSc, 0f);
            // ColorOS 迷你窗态会给 Task 层挂 108.55 宽、0x66FFFFFF 的半透明白描边
            // （真机层树：borderSettings=BorderSettings{strokeWidth: 108.552200, color: 1728053247}），
            // reparent 进卡片后就是卡片四周那圈厚毛边。这里清成零值 = 自由小窗样式。
            Object zeroBorder = zeroBorderSettings();
            if (zeroBorder != null) {
                txCall(tx, "setBorderSettings", taskSc, zeroBorder);
            }
            // framework SurfaceView 796-798 在 isShowSurfaceCornerRadius() 为真时会写
            // setCornerRadius(mSurfaceControl, mCornerRadius)；坞窗是 system_server 直连的
            // SurfaceView，这里显式补同一次，保证父层圆角真的落到 SurfaceControl 上，
            // 任务层（子层）才会被父层圆角裁成圆角。
            txCall(tx, "setCornerRadius", surfaceSc, slot.cornerPx);
            txCall(tx, "setShadowRadius", taskSc, 0f);
            txCall(tx, "setScale", taskSc, wScale, hScale);
            // 展开时被压到 0 的 alpha 抬回来，画面在卡片里出现
            txCall(tx, "setAlpha", taskSc, 1f);
            // 关键：detach 时 hideLayer=true 会把任务层 hide()（真机上再次打开时层状态是
            // "invisible reason=hidden by parent or layer flag"）。只抬 alpha 不够，
            // layer flag 仍是隐藏 → 卡片空白。挂进卡片必须显式 show。
            txCall(tx, "show", taskSc);
            // 官方把 dropInput 设在 SurfaceView 这一层（父层），整棵子树丢弃输入；
            // 不是设在任务层。设错层位触摸仍可能漏到应用里。
            txCall(tx, "setDropInputMode", surfaceSc, 1);
            applyBackgroundLayer(slot, tx);
            tx.apply();
            slot.taskSurface = taskSc;
            XposedBridge.log(TAG + " live tx taskId=" + slot.taskId
                    + " task=" + bounds.width() + "x" + bounds.height()
                    + " launch=" + (base == launch ? "yes" : "fallback")
                    + " base=" + base.width() + "x" + base.height()
                    + " card=" + width + "x" + height
                    + " surfaceCb=" + slot.surfaceWidth + "x" + slot.surfaceHeight
                    + " wScale=" + wScale + " hScale=" + hScale
                    + " wasVisible=" + slot.wasVisible
                    + " parent=" + surfaceSc);
            // 图层事务落下去之后保证实时态的热区一定在（已挂的话只贴一次位置）。
            showTouchPad(slot);
            // 画面已经真的挂上了，把之前压住的 BLAST 层放出来。
            hideBlastLayer(slot.surfaceView, false);
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " live tx failed taskId=" + slot.taskId + ": " + t);
            return false;
        }
    }

    /**
     * 坞窗卡片的 Background 子层（framework 的 "Background for ..." 纯色层）。
     *
     * <p>官方 1059-1065 会给它 setAlpha(0.98/0.95) + setColor(mBgColor) + show 当卡片底色，
     * 这里照同一口径：alpha 拉满 + show，颜色由 setResizeBackgroundColor(bgColor) 提供
     * （浅色 #E5E5E5 / 深色 #1E1E1E，和坞窗面板同色）。
     * 用户实测放行这层后卡片效果更好，所以保留。</p>
     */
    private static void applyBackgroundLayer(Slot slot, SurfaceControl.Transaction tx) {
        try {
            java.lang.reflect.Field bgF = SurfaceView.class.getDeclaredField("mBackgroundControl");
            bgF.setAccessible(true);
            Object bgObj = bgF.get(slot.surfaceView);
            if (!(bgObj instanceof SurfaceControl)) return;
            SurfaceControl bg = (SurfaceControl) bgObj;
            if (!bg.isValid()) return;
            // 这层是 SurfaceView 的纯色子层，不会自动吃父层圆角：必须和卡片同一个基础参数
            // （slot.cornerPx，即官方 mItemRadius 口径），否则放行后它的四个角是直角，
            // 会在画面四角露出来。BLAST 子层拿不到句柄，但它跟随父层几何裁剪，不用单独设。
            txCall(tx, "setCornerRadius", bg, slot.cornerPx);
            txCall(tx, "setAlpha", bg, 1f);
            txCall(tx, "show", bg);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " background layer unavailable: " + t.getMessage());
        }
    }

    // ==================== 官方 MySurfaceView 的滚动裁剪（820-858） ====================

    /** 这个 SurfaceView 是否是坞窗实时卡片（MainHook 的裁剪遍历靠它分流）。 */
    static boolean isLiveSurfaceView(SurfaceView view) {
        return findSlotForView(view) != null;
    }

    private static Slot findSlotForView(SurfaceView view) {
        synchronized (sSlots) {
            for (Slot slot : sSlots) {
                if (slot.surfaceView == view) return slot;
            }
        }
        return null;
    }

    /**
     * 这个 SurfaceControl 是否是坞窗卡片里那个 Task 层。
     *
     * <p>真机层树证据（RMX3820 / 坞窗打开时）：
     * {@code Layer[126] Task=1076#126 borderSettings=BorderSettings{strokeWidth: 108.552200,
     * color: 1728053247} cornerRadius=<161.039,161.044>} —— ColorOS 给"迷你窗态"的 Task 层
     * 挂了悬浮窗描边（108.55 缩到屏幕上约 47px 的白边）和迷你窗圆角，reparent 进卡片后
     * 就是卡片四周那圈"厚毛边"。所以这里要把卡片里的 Task 层按自由小窗样式清掉。</p>
     */
    static boolean isLiveTaskSurface(SurfaceControl surface) {
        if (surface == null) return false;
        synchronized (sSlots) {
            for (Slot slot : sSlots) {
                if (sameSurface(surface, slot.taskSurface)
                        || sameSurface(surface, slot.animationTaskSurface)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 零值 BorderSettings（android.gui.BorderSettings 默认 strokeWidth=0f / color=0）。 */
    static Object zeroBorderSettings() {
        try {
            Class<?> cls = Class.forName("android.gui.BorderSettings");
            return cls.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " zeroBorderSettings failed: " + t);
            return null;
        }
    }

    /** 官方 reset()（802-806）：每次 surfaceChanged 重新量容器。 */
    private static void resetCrop(Slot slot) {
        slot.cropInited = false;
        slot.lastGone = null;
        slot.lastCropRect.setEmpty();
        slot.resultRect.setEmpty();
    }

    /**
     * framework SurfaceView.onSetSurfacePositionAndScale 的 after hook 入口，
     * 等价于官方 MySurfaceView 覆写（814-818）：父层位置写完，这里追加 crop / alpha。
     */
    static void onSurfacePositionUpdate(SurfaceView view, SurfaceControl.Transaction tx,
                                        SurfaceControl surface, int positionLeft, int positionTop) {
        if (tx == null || surface == null || !surface.isValid()) return;
        Slot slot = findSlotForView(view);
        if (slot == null) return;
        adjustSurfaceControl(slot, tx, surface, positionLeft, positionTop);
    }

    /** MainHook 在滚动/布局变化时按窗口坐标重算一次（补 hook 没触发的那一拍）。 */
    static void refreshCrop(SurfaceView view) {
        Slot slot = findSlotForView(view);
        if (slot == null) return;
        // 容器矩形（滚动区）只在这里算：UI 线程读 View 位置，RT 线程的 hook 直接用缓存。
        if (!MainHook.dockPreviewClipRect(slot.containRect)) return;
        if (!slot.attached) return;
        Object scObj = surfaceControlOf(view);
        if (!(scObj instanceof SurfaceControl)) return;
        SurfaceControl surface = (SurfaceControl) scObj;
        if (!surface.isValid()) return;
        int[] loc = new int[2];
        view.getLocationInWindow(loc);
        SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        adjustSurfaceControl(slot, tx, surface, loc[0], loc[1]);
        tx.apply();
    }

    /**
     * 官方 MySurfaceView.adjustSurfaceControl（820-858），逐行照搬：
     * 卡片 surface 顶部/底部滚出容器可见区就 setCrop，完全滚出就 alpha=0；
     * 只有 top/bottom 变了才重设 crop（避免每帧写事务）。
     */
    private static void adjustSurfaceControl(Slot slot, SurfaceControl.Transaction tx,
                                             SurfaceControl surface, int positionLeft, int positionTop) {
        Rect contain = slot.containRect;
        if (contain.isEmpty()) return;
        SurfaceView v = slot.surfaceView;
        int w = v.getWidth();
        int h = v.getHeight();
        if (w <= 0 || h <= 0) return;
        slot.surfaceRect.set(0, 0, w, h);
        slot.surfaceRect.offsetTo(positionLeft, positionTop);
        boolean setGone = false;
        if (slot.surfaceRect.top < contain.top) {
            int top = contain.top - slot.surfaceRect.top;
            int bottom = slot.surfaceRect.height();
            if (top >= slot.surfaceRect.height()) {
                setGone = true;
            }
            slot.resultRect.set(0, Math.min(top, slot.surfaceRect.height() - 1), w, bottom);
        } else if (slot.surfaceRect.bottom > contain.bottom) {
            int bottom2 = slot.surfaceRect.height() - (slot.surfaceRect.bottom - contain.bottom);
            if (bottom2 <= 0) {
                setGone = true;
            }
            slot.resultRect.set(0, 0, w, Math.max(bottom2, 1));
        } else {
            slot.resultRect.set(0, 0, w, h);
        }
        if (slot.resultRect.top != slot.lastCropRect.top
                || slot.resultRect.bottom != slot.lastCropRect.bottom) {
            txCall(tx, "setCrop", surface, new Rect(slot.resultRect));
            slot.lastCropRect.set(slot.resultRect);
        }
        txCall(tx, "setAlpha", surface, setGone ? 0.0f : 1.0f);
        if (slot.lastGone == null || slot.lastGone.booleanValue() != setGone) {
            slot.lastGone = setGone;
            XposedBridge.log(TAG + " crop taskId=" + slot.taskId + " container=" + contain
                    + " surface=" + slot.surfaceRect + " crop=" + slot.resultRect
                    + " gone=" + setGone);
        }
    }

    /** Called by the system-server Transaction.reparent hook for every proposed reparent. */
    static SurfaceControl lockedParentForReparent(SurfaceControl child,
                                                  SurfaceControl requestedParent) {
        if (!sSurfaceParentLockEnabled || child == null) return null;
        synchronized (sSlots) {
            for (Slot slot : sSlots) {
                SurfaceControl taskSurface = slot.taskSurface;
                if (!slot.attached || slot.attaching || taskSurface == null
                        || (!sameSurface(child, taskSurface)
                        && !sameSurface(child, slot.animationTaskSurface))) {
                    continue;
                }
                Object parentObject = surfaceControlOf(slot.surfaceView);
                if (!(parentObject instanceof SurfaceControl)) return null;
                SurfaceControl cardParent = (SurfaceControl) parentObject;
                if (!cardParent.isValid() || sameSurface(requestedParent, cardParent)) return null;
                if (!slot.parentLockLogged) {
                    slot.parentLockLogged = true;
                    XposedBridge.log(TAG + " locked Task reparent in card taskId=" + slot.taskId
                            + " requestedParent=" + requestedParent + " cardParent=" + cardParent);
                }
                return cardParent;
            }
        }
        return null;
    }

    private static boolean sameSurface(SurfaceControl first, SurfaceControl second) {
        if (first == second) return true;
        if (first == null || second == null) return false;
        try {
            return first.equals(second);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Release the SurfaceView parent lock immediately before a dock-close task transition. */
    static void releaseSurfaceParentLock() {
        sSurfaceParentLockEnabled = false;
        XposedBridge.log(TAG + " SurfaceView Task reparent lock released");
    }

    // ==================== 可见性：官方展开 / 收回浮标 ====================

    /**
     * 用官方「拖拽结束进入小窗」入口把任务展开成可见自由窗（只做展开，不做几何跟随）。
     *
     * 收成浮球后 activity 是 clientVisible=false，直接 reparent 只会得到黑卡；
     * 稳定版（CardZoom.enter）能出画面就是因为它先调了这个入口把任务变可见。
     * 这里沿用原始 launchBounds / scale，不写卡片几何——画面由 reparent + setScale 铺满。
     */
    private static void expandForLive(Slot slot, ClassLoader cl) {
        if (slot.expanded) return;
        slot.expanded = true;
        try {
            Object task = findTask(slot.taskId, cl);
            if (task == null) {
                XposedBridge.log(TAG + " expand skipped: task not found taskId=" + slot.taskId);
                return;
            }
            if (isVisible(task)) {
                XposedBridge.log(TAG + " expand skipped: already visible taskId=" + slot.taskId);
                return;
            }
            Rect launch = launchBoundsOf(slot, task);
            Rect card = cardScreenRect(slot);
            if (card.width() <= 0 || card.height() <= 0) {
                XposedBridge.log(TAG + " expand skipped: card not measured taskId=" + slot.taskId);
                slot.expanded = false;
                return;
            }
            // 官方入口 startActivityByFloatInfo 打开窗口时读的是 FlexibleTaskInfo 里的
            // launchBounds / scale（默认 770 宽），不是我们传进去的 rect。不写这里，
            // 窗口会按旧几何先弹出一个大窗再被我们缩回卡片 —— 那就是多出来的那层动画。
            Rect layout = new Rect(card.left, card.top,
                    card.left + launch.width(),
                    card.top + Math.round(card.height() * (launch.width() / (float) card.width())));
            float scale = card.width() / (float) launch.width();
            captureFloatGeometry(slot, task);
            patchFloatGeometry(slot, layout, scale);
            // 官方在 moveTaskToFront 里用「任务本体 scale × launchBounds」算可见落点，
            // 只改 FlexibleTaskInfo 的 scale 没用 —— 任务 scale 还是旧的（0.8022）时
            // 窗口会以 995x1768 的大窗弹出来（3820 实测，就是那「闪两下」的第二帧）。
            setTaskScale(task, scale);
            XposedBridge.log(TAG + " task scale set taskId=" + slot.taskId + " scale=" + scale);
            // 展开时系统会把浮球条目删掉；压住它，坞窗收起时任务才能原路退回浮标。
            MirageWindowHook.keepFloatEntry(slot.taskId, 10000);
            Object fwms = flexibleService(cl);
            if (fwms == null) {
                XposedBridge.log(TAG + " expand skipped: flexible service unavailable taskId="
                        + slot.taskId);
                return;
            }
            synchronized (globalLock(task)) {
                // 官方入口里一打开窗口就同步上屏，等它返回再压 alpha 已经闪出去
                // 一两帧（用户实测「首次开坞窗闪两下」）。所以压 alpha 要抢在它前面。
                hideDuringTransition(slot, task);
                // 这一拍告诉系统「这次起窗别放过渡动画」，由 MainHook 里
                // FlexibleWindowUtils.setFlexibleTaskOptions 的钩子写进 options extra。
                sNoAnimOnce = true;
                try {
                    XposedHelpers.callMethod(fwms, "finishSplitDragAnimationAndStartFlexible",
                            task, new Rect(layout), scale, 0, "freewindow");
                } finally {
                    sNoAnimOnce = false;
                }
            }
            XposedBridge.log(TAG + " expandForLive taskId=" + slot.taskId
                    + " card=" + card + " layout=" + layout + " scale=" + scale);
            hideDuringTransition(slot, task);
            keepHiddenUntilAttached(slot, cl);
            killFloatSnapshot(slot, cl);
            skipOpenAnimation(slot, cl);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " expandForLive failed taskId=" + slot.taskId + ": " + t);
        }
    }

    /**
     * 官方展开会带一段「从浮标飞到 launchBounds」的入场动画（首次打开才播）。
     * 动画照跑，但把任务层 alpha 压到 0，人眼看不到；挂进卡片时再由
     * {@link #applyLiveTransaction} 抬回 1。视觉上就是「静止快照 → 实时画面」。
     */
    private static void hideDuringTransition(Slot slot, Object task) {
        try {
            Object scObj = XposedHelpers.callMethod(task, "getSurfaceControl");
            if (!(scObj instanceof SurfaceControl)) return;
            SurfaceControl sc = (SurfaceControl) scObj;
            if (!sc.isValid()) return;
            SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
            txCall(tx, "setAlpha", sc, 0f);
            tx.apply();
            XposedBridge.log(TAG + " hide during transition taskId=" + slot.taskId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " hideDuringTransition failed taskId=" + slot.taskId + ": " + t);
        }
    }

    /**
     * 首次开坞窗会闪两帧（窗口先按旧几何出一帧、挂进卡片时又出一帧）。
     *
     * <p>打开过程中 WMS 会把任务层 alpha 抬回 1，所以只压一次不够；
     * 这里在挂进卡片之前按几拍反复压 0，挂上后由 {@link #applyLiveTransaction}
     * 抬回 1，视觉上就是「完全不出现 → 直接出实时画面」。</p>
     */
    private static void keepHiddenUntilAttached(final Slot slot, final ClassLoader cl) {
        final long[] delays = {0L, 16L, 32L, 48L, 80L, 120L, 180L, 260L, 350L, 450L, 560L, 680L};
        for (long delay : delays) {
            slot.surfaceView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (slot.attached) return;
                    synchronized (sSlots) {
                        if (!sSlots.contains(slot)) return;
                    }
                    try {
                        Object task = findTask(slot.taskId, cl);
                        if (task == null) return;
                        Object scObj = XposedHelpers.callMethod(task, "getSurfaceControl");
                        if (!(scObj instanceof SurfaceControl)) return;
                        SurfaceControl sc = (SurfaceControl) scObj;
                        if (!sc.isValid()) return;
                        SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
                        txCall(tx, "setAlpha", sc, 0f);
                        tx.apply();
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " keepHidden failed taskId=" + slot.taskId + ": " + t);
                    }
                }
            }, delay);
        }
    }

    /**
     * 官方浮标动画真正画在屏幕上的是它自己建的**快照层**：
     * FlexibleFloatHandleAnimationSpec 里 mSnapshotSurface 是独立 SurfaceControl，
     * 层级 2147483643 —— 压在所有东西上面（所以 alpha 压任务层压不掉它）。
     * 官方给了 removeSnapshotSurface 的公共接口，这里开始展开后连续删几次，
     * 动画就只剩看不见的 leash 在跑，人眼看不到那段入场。
     */
    private static void killFloatSnapshot(final Slot slot, final ClassLoader cl) {
        final long[] delays = {0L, 80L, 160L, 300L, 500L};
        for (final long delay : delays) {
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    try {
                        Class<?> clazz = XposedHelpers.findClass(
                                "com.android.server.wm.FlexibleFloatHandleAnimController", cl);
                        Object controller = XposedHelpers.callStaticMethod(clazz, "getInstance");
                        try {
                            XposedHelpers.callMethod(controller, "removeSnapshotSurface", slot.taskId);
                        } catch (Throwable ignored) {
                            Object spec = XposedHelpers.callMethod(controller,
                                    "getFloatHandleAnimationSpec", slot.taskId);
                            if (spec != null) {
                                XposedHelpers.callMethod(spec, "removeSnapshotSurface");
                            }
                        }
                        XposedBridge.log(TAG + " killFloatSnapshot@" + delay
                                + " taskId=" + slot.taskId);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " killFloatSnapshot@" + delay + " failed taskId="
                                + slot.taskId + ": " + t);
                    }
                }
            };
            if (delay == 0L) {
                r.run();
            } else {
                slot.surfaceView.postDelayed(r, delay);
            }
        }
    }

    /** 卡片在屏幕上的矩形（px）。展开前拿它算「窗口打开就落在卡片里」的几何。 */
    private static Rect cardScreenRect(Slot slot) {
        int[] loc = new int[2];
        try {
            slot.surfaceView.getLocationOnScreen(loc);
        } catch (Throwable t) {
            return new Rect();
        }
        return new Rect(loc[0], loc[1],
                loc[0] + slot.surfaceView.getWidth(), loc[1] + slot.surfaceView.getHeight());
    }

    // ==================== 画面区热区窗口 ====================
    //
    // 任务层带着应用自己的输入通道，压在坞窗之上，手指直接进应用（这就是"卡片里能操作应用"）。
    // 在坞窗 View 树里加挡板没用：事件压根不进坞窗（实测连 DOWN 都收不到）。
    // 所以给每张卡的画面区单独开一个透明系统窗口（type=2002，坞窗同进程同权限），
    // 输入通道由系统自己签发 —— 点到这层的 DOWN/UP 一定进得来，用来做"点击恢复任务"。
    // 它只做点击转发，不参与滚动。
    //
    // 生命周期：和坞窗同存亡 —— 坞窗在，热区就在；坞窗关（卡片销毁）才撤。
    // 位置跟随不做定时轮询（原来 400ms 一次太耗），改成事件驱动：卡片布局变化、
    // 坞窗滚动、窗口重新布局时才贴一次；位置没变直接跳过。

    private static void showTouchPad(final Slot slot) {
        if (slot == null || slot.taskId <= 0) return;
        // 静态画面模式：没有实时层要挡，热区也不该出现，否则会把坞窗的上下滑动吃掉。
        if (!sLivePreviewEnabled) return;
        try {
            if (slot.touchPad != null) {
                // 已经挂着：只把位置贴一次，不重复加窗口。
                syncTouchPad(slot);
                return;
            }
            Rect card = cardScreenRect(slot);
            if (card.width() <= 0 || card.height() <= 0
                    || !isPadRectOnScreen(card, slot.surfaceView)) return;
            Context ctx = slot.surfaceView.getContext();
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return;

            final int taskId = slot.taskId;
            View pad = new View(ctx);
            pad.setBackgroundColor(Color.TRANSPARENT);
            pad.setClickable(true);
            pad.setOnTouchListener(new View.OnTouchListener() {
                private float downX;
                private float downY;

                @Override
                public boolean onTouch(View v, android.view.MotionEvent event) {
                    switch (event.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            downX = event.getX();
                            downY = event.getY();
                            return true;
                        case android.view.MotionEvent.ACTION_UP:
                            if (Math.abs(event.getX() - downX) < 40f
                                    && Math.abs(event.getY() - downY) < 40f) {
                                XposedBridge.log(TAG + " pad click taskId=" + taskId);
                                fireRestore(taskId);
                            }
                            return true;
                        default:
                            return true;
                    }
                }
            });

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(2002);
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            lp.format = PixelFormat.TRANSLUCENT;
            lp.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
            lp.x = card.left;
            lp.y = card.top;
            lp.width = card.width();
            lp.height = card.height();
            lp.setTitle("FreeWindowTouchPad");
            try {
                java.lang.reflect.Field pf =
                        WindowManager.LayoutParams.class.getDeclaredField("privateFlags");
                pf.setAccessible(true);
                pf.setInt(lp, 16);
            } catch (Throwable ignored) {
            }
            wm.addView(pad, lp);
            slot.touchPad = pad;
            slot.touchPadLp = lp;
            slot.padWm = wm;
            slot.padRect.set(card);
            XposedBridge.log(TAG + " touch pad added taskId=" + taskId + " rect=" + card);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " touch pad add failed taskId=" + slot.taskId + ": " + t);
        }
    }

    /** 把热区窗口重新贴到画面 View 的屏幕矩形上；画面滚出屏幕就撤掉。 */
    private static void syncTouchPad(Slot slot) {
        try {
            if (slot.touchPad == null || slot.touchPadLp == null || slot.padWm == null) return;
            Rect card = cardScreenRect(slot);
            if (card.width() <= 0 || card.height() <= 0
                    || !isPadRectOnScreen(card, slot.surfaceView)) {
                hideTouchPad(slot);
                return;
            }
            if (slot.padRect.equals(card)) return;
            WindowManager.LayoutParams lp = slot.touchPadLp;
            lp.x = card.left;
            lp.y = card.top;
            lp.width = card.width();
            lp.height = card.height();
            slot.padWm.updateViewLayout(slot.touchPad, lp);
            slot.padRect.set(card);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " touch pad sync failed taskId=" + slot.taskId + ": " + t);
        }
    }

    /** 矩形是否还有一部分在屏幕内（完全滚出去就没必要留着挡手）。 */
    private static boolean isPadRectOnScreen(Rect r, View anchor) {
        if (r == null || r.width() <= 0 || r.height() <= 0) return false;
        try {
            android.util.DisplayMetrics dm = anchor.getResources().getDisplayMetrics();
            if (dm == null || dm.widthPixels <= 0 || dm.heightPixels <= 0) return true;
            return r.right > 0 && r.bottom > 0 && r.left < dm.widthPixels && r.top < dm.heightPixels;
        } catch (Throwable t) {
            return true;
        }
    }

    private static void hideTouchPad(Slot slot) {
        if (slot == null) return;
        View pad = slot.touchPad;
        WindowManager wm = slot.padWm;
        slot.touchPad = null;
        slot.touchPadLp = null;
        slot.padWm = null;
        slot.padRect.setEmpty();
        if (pad == null || wm == null) return;
        try {
            wm.removeViewImmediate(pad);
            XposedBridge.log(TAG + " touch pad removed taskId=" + slot.taskId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " touch pad remove failed taskId=" + slot.taskId + ": " + t);
        }
    }

    private static void captureFloatGeometry(Slot slot, Object task) {
        if (slot.geomCaptured || slot.floatHandleInfo == null) return;
        try {
            Object fti = XposedHelpers.getObjectField(slot.floatHandleInfo, "flexibleTaskInfo");
            if (fti == null) return;
            Rect r = (Rect) XposedHelpers.callMethod(fti, "getLaunchBounds");
            slot.origLaunch = r == null ? null : new Rect(r);
            slot.origScale = ((Number) XposedHelpers.callMethod(fti, "getScale")).floatValue();
            slot.origRatio = ((Number) XposedHelpers.callMethod(fti, "getRatio")).floatValue();
            slot.origTaskScale = taskScaleOf(task, slot.origScale);
            slot.geomCaptured = true;
            XposedBridge.log(TAG + " captureFloatGeometry taskId=" + slot.taskId
                    + " launch=" + slot.origLaunch + " scale=" + slot.origScale
                    + " taskScale=" + slot.origTaskScale + " ratio=" + slot.origRatio);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " captureFloatGeometry failed taskId=" + slot.taskId + ": " + t);
        }
    }

    /** 任务本体（TaskExtImpl）当前的 scale；读不到就退回传进来的默认值。 */
    private static float taskScaleOf(Object task, float fallback) {
        try {
            Object extImpl = XposedHelpers.callMethod(
                    XposedHelpers.callMethod(task, "getWrapper"), "getExtImpl");
            return ((Number) XposedHelpers.callMethod(extImpl, "getScale")).floatValue();
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 把任务本体 scale 也改成卡片比例：官方算可见落点时用任务 scale 乘 launchBounds。 */
    private static void setTaskScale(Object task, float scale) {
        try {
            Object extImpl = XposedHelpers.callMethod(
                    XposedHelpers.callMethod(task, "getWrapper"), "getExtImpl");
            XposedHelpers.callMethod(extImpl, "setScale", scale);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " setTaskScale failed: " + t);
        }
    }

    private static void patchFloatGeometry(Slot slot, Rect layout, float scale) {
        if (slot.floatHandleInfo == null) return;
        try {
            Object fti = XposedHelpers.getObjectField(slot.floatHandleInfo, "flexibleTaskInfo");
            if (fti == null) return;
            // originLaunchBounds 非空时 startActivityByFloatInfo 会无条件用它覆盖 launchBounds
            try {
                XposedHelpers.callMethod(fti, "setOriginLaunchBounds", (Object) null);
            } catch (Throwable ignored) {
            }
            XposedHelpers.callMethod(fti, "setLaunchBounds", new Rect(layout));
            XposedHelpers.callMethod(fti, "setScale", scale);
            XposedBridge.log(TAG + " patchFloatGeometry taskId=" + slot.taskId
                    + " layout=" + layout + " scale=" + scale);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " patchFloatGeometry failed taskId=" + slot.taskId + ": " + t);
        }
    }

    /** 坞窗收起 / 点卡片恢复前把浮标信息里的几何还原成展开之前的样子。 */
    private static void restoreFloatGeometry(Slot slot, ClassLoader cl) {
        if (!slot.geomCaptured || slot.floatHandleInfo == null) return;
        slot.geomCaptured = false;
        slot.expanded = false;
        try {
            Object fti = XposedHelpers.getObjectField(slot.floatHandleInfo, "flexibleTaskInfo");
            if (fti == null) return;
            XposedHelpers.callMethod(fti, "setLaunchBounds",
                    slot.origLaunch == null ? null : new Rect(slot.origLaunch));
            if (slot.origLaunch != null) {
                XposedHelpers.callMethod(fti, "setScale", slot.origScale);
            }
            if (slot.origRatio > 0f) {
                XposedHelpers.callMethod(fti, "setRatio", slot.origRatio);
            }
            Object task = findTask(slot.taskId, cl);
            if (task != null) {
                setTaskScale(task, slot.origTaskScale);
            }
            XposedBridge.log(TAG + " restoreFloatGeometry taskId=" + slot.taskId
                    + " launch=" + slot.origLaunch + " scale=" + slot.origScale
                    + " taskScale=" + slot.origTaskScale);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " restoreFloatGeometry failed taskId=" + slot.taskId + ": " + t);
        }
    }

    private static Rect launchBoundsOf(Slot slot, Object task) {
        try {
            Object fti = slot.floatHandleInfo == null ? null
                    : XposedHelpers.getObjectField(slot.floatHandleInfo, "flexibleTaskInfo");
            if (fti != null) {
                Rect r = (Rect) XposedHelpers.callMethod(fti, "getLaunchBounds");
                if (r != null && r.width() > 0 && r.height() > 0) return new Rect(r);
            }
        } catch (Throwable ignored) {
        }
        try {
            Rect b = (Rect) XposedHelpers.callMethod(task, "getBounds");
            if (b != null && b.width() > 0) return new Rect(b);
        } catch (Throwable ignored) {
        }
        return new Rect(0, 0, 1080, 1920);
    }

    /** 把「打开小窗」的入场动画直接跳到终点（spec 在官方入口里建，晚一拍时靠重试补上）。 */
    private static void skipOpenAnimation(final Slot slot, final ClassLoader cl) {
        final long[] delays = {0L, 60L, 150L, 300L};
        for (final long delay : delays) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        Class<?> clazz = XposedHelpers.findClass(FLOAT_ANIM_CONTROLLER_CLASS, cl);
                        Object controller = XposedHelpers.callStaticMethod(clazz, "getInstance");
                        Object spec = XposedHelpers.callMethod(controller,
                                "getFloatHandleAnimationSpec", slot.taskId);
                        if (spec == null) return;
                        XposedHelpers.callMethod(controller, "checkAndSkipToEndLastAnim", slot.taskId);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " skipOpenAnimation failed taskId=" + slot.taskId
                                + ": " + t);
                    }
                }
            };
            if (delay == 0L) {
                runnable.run();
            } else {
                slot.surfaceView.postDelayed(runnable, delay);
            }
        }
    }

    /**
     * 坞窗收起：把已展开的窗口原路收回浮标（state=16 + reason=16）。
     * 不收回的话，松开 reparent 后任务会作为普通小窗留在桌面上。
     */
    private static void collapseToHandle(Slot slot, ClassLoader cl) {
        if (MirageWindowHook.isUserRemoved(slot.taskId)) {
            XposedBridge.log(TAG + " collapse skipped, user-removed taskId=" + slot.taskId);
            return;
        }
        // 先还回原始几何：浮标收起来的迷你尺寸是按这份 info 算的
        restoreFloatGeometry(slot, cl);
        try {
            Object task = findTask(slot.taskId, cl);
            if (task == null) return;
            if (isVisible(task)) {
                MirageWindowHook.keepFloatEntry(slot.taskId, 5000);
                ensureFloatEntry(slot, task, cl);
                Object fwms = flexibleService(cl);
                Object controller = fwms == null ? null
                        : XposedHelpers.getObjectField(fwms, "mFlexibleTaskController");
                if (controller == null) {
                    XposedBridge.log(TAG + " collapse skipped: controller null taskId=" + slot.taskId);
                    return;
                }
                synchronized (globalLock(task)) {
                    XposedHelpers.callMethod(controller, "exitFlexibleTask",
                            task, true, EXIT_REASON_TO_FLOAT, 0);
                }
                XposedBridge.log(TAG + " collapseToHandle ok taskId=" + slot.taskId);
            }
            // 兜底：无论走出哪条路，都别再保持「可见请求」
            setVisibleRequested(task, false);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " collapseToHandle failed taskId=" + slot.taskId + ": " + t);
            Object task2 = findTask(slot.taskId, cl);
            if (task2 != null) setVisibleRequested(task2, false);
        }
    }

    /** 展开时系统可能已经把浮球条目摘掉，这里按留存 info 补回去。 */
    private static void ensureFloatEntry(Slot slot, Object task, ClassLoader cl) {
        try {
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl),
                    "getInstance");
            if (fhc == null) return;
            boolean inList = Boolean.TRUE.equals(
                    XposedHelpers.callMethod(fhc, "isInFloatingList", slot.taskId));
            if (inList) return;
            if (slot.floatHandleInfo != null) {
                XposedHelpers.callMethod(fhc, "addFloatHandle", slot.floatHandleInfo, 1);
                XposedBridge.log(TAG + " float entry re-added taskId=" + slot.taskId);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " ensureFloatEntry failed taskId=" + slot.taskId + ": " + t);
        }
    }

    private static Object flexibleService(ClassLoader cl) {
        if (cl == null) cl = sClassLoader;
        if (cl == null) return null;
        try {
            Class<?> clazz = XposedHelpers.findClass(FLEXIBLE_WM_CLASS, cl);
            return XposedHelpers.callStaticMethod(clazz, "getInstance", new Object[]{null});
        } catch (Throwable t) {
            XposedBridge.log(TAG + " flexibleService failed: " + t);
            return null;
        }
    }

    private static Object globalLock(Object task) {
        try {
            Object dc = XposedHelpers.callMethod(task, "getDisplayContent");
            Object atms = XposedHelpers.callMethod(dc, "getAtmService");
            return XposedHelpers.getObjectField(atms, "mGlobalLock");
        } catch (Throwable t) {
            return new Object();
        }
    }

    /** 把 Task 图层还给 WMS 的父层，并恢复任务可见请求。 */
    private static boolean detach(Slot slot, ClassLoader cl, boolean hideLayer, boolean requestedVisible) {
        // 先把热区撤掉：任务层马上要还回 WMS，这块不该继续挡在屏幕上。
        hideTouchPad(slot);
        boolean wasAttached = slot.attached;
        try {
            Object task = findTask(slot.taskId, cl != null ? cl : sClassLoader);
            if (task == null) {
                XposedBridge.log(TAG + " detach: task not found taskId=" + slot.taskId);
                return false;
            }
            Object taskScObj = XposedHelpers.callMethod(task, "getSurfaceControl");
            Object parentScObj = XposedHelpers.callMethod(task, "getParentSurfaceControl");
            Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
            if (!(taskScObj instanceof SurfaceControl) || !(parentScObj instanceof SurfaceControl)) {
                XposedBridge.log(TAG + " detach: surface control unavailable taskId=" + slot.taskId);
                return false;
            }
            SurfaceControl taskSc = (SurfaceControl) taskScObj;
            SurfaceControl parentSc = (SurfaceControl) parentScObj;
            setVisibleRequested(task, requestedVisible);
            // 照第三版（无闪大窗版本）的还层口径：reparent → 摆到 bounds 左上 → 单位矩阵 →
            // 按 hideLayer 决定隐藏。层一旦藏掉，官方动画接管前屏幕上看不到任何任务帧，
            // 恢复时就不存在一闪而过的大窗（setScale/球位置等尝试均已确认无效并撤掉）。
            // 位置必须传 float：传 int 会装箱成 Integer，反射按 (SurfaceControl, float, float)
            // 找不到方法，setPosition 被静默跳过（日志 NoSuchMethodError），层停在 0,0。
            SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
            tx.reparent(taskSc, parentSc);
            txCall(tx, "setDropInputMode", taskSc, 0);
            txCall(tx, "setPosition", taskSc,
                    (float) (bounds != null ? bounds.left : 0),
                    (float) (bounds != null ? bounds.top : 0));
            txCall(tx, "setMatrix", taskSc, 1f, 0f, 0f, 1f);
            // 展开时为了藏入场动画压过 alpha=0，还给系统时要抬回来
            txCall(tx, "setAlpha", taskSc, 1f);
            if (hideLayer) txCall(tx, "hide", taskSc);
            else txCall(tx, "show", taskSc);
            tx.apply();
            slot.attached = false;
            XposedBridge.log(TAG + " live detached taskId=" + slot.taskId
                    + " wasAttached=" + wasAttached + " hideLayer=" + hideLayer
                    + " requestedVisible=" + requestedVisible);
            return true;
        } catch (Throwable t) {
            slot.attached = wasAttached;
            XposedBridge.log(TAG + " detach failed taskId=" + slot.taskId + ": " + t);
            return false;
        }
    }

    private static void txCall(SurfaceControl.Transaction tx, String name, Object... args) {
        try {
            XposedHelpers.callMethod(tx, name, args);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " transaction." + name + " failed: " + t);
        }
    }

    /** 卡片尺寸变化时重算缩放与位置。 */
    private static void reapply(Slot slot, ClassLoader cl) {
        if (!slot.attached) return;
        slot.attached = false;
        attachAttempt(slot, cl, 0);
    }

    private static Object surfaceControlOf(View view) {
        try {
            return XposedHelpers.callMethod(view, "getSurfaceControl");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " getSurfaceControl failed: " + t);
            return null;
        }
    }


    private static boolean isVisible(Object task) {
        try {
            return (Boolean) XposedHelpers.callMethod(task, "isVisible");
        } catch (Throwable t) {
            return true;
        }
    }

    /** 可见性必须改在 WMS 全局锁内。 */
    private static void setVisibleRequested(Object task, boolean visible) {
        Object atms = atmService(task);
        Object lock = null;
        if (atms != null) {
            try {
                lock = XposedHelpers.getObjectField(atms, "mGlobalLock");
            } catch (Throwable ignored) {
            }
        }
        Runnable action = new Runnable() {
            @Override
            public void run() {
                try {
                    XposedHelpers.callMethod(task, "setVisibleRequested", visible);
                    XposedBridge.log(TAG + " setVisibleRequested(" + visible + ") ok");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " setVisibleRequested(" + visible + ") failed: " + t);
                }
            }
        };
        if (lock != null) {
            synchronized (lock) {
                action.run();
            }
        } else {
            action.run();
        }
    }

    /** 让任务顶层 activity 真正 resume 一次，普通 View 才会重新绘制（贴边保活态下仅视频层在动）。 */
    private static void resumeTask(Object task) {
        if (task == null) return;
        Object atms = atmService(task);
        Object lock = null;
        if (atms != null) {
            try {
                lock = XposedHelpers.getObjectField(atms, "mGlobalLock");
            } catch (Throwable ignored) {
            }
        }
        Runnable action = new Runnable() {
            @Override
            public void run() {
                try {
                    // ColorOS Task 上有 no-arg 重载；失败再试带参形态（prev, options）。
                    try {
                        XposedHelpers.callMethod(task, "resumeTopActivityUncheckedLocked");
                    } catch (Throwable noArg) {
                        XposedHelpers.callMethod(task, "resumeTopActivityUncheckedLocked", null, null);
                    }
                    XposedBridge.log(TAG + " resumeTopActivityUncheckedLocked ok");
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " resumeTask failed: " + t);
                }
            }
        };
        if (lock != null) {
            synchronized (lock) {
                action.run();
            }
        } else {
            action.run();
        }
    }

    private static Object atmService(Object task) {
        try {
            Object dc = XposedHelpers.callMethod(task, "getDisplayContent");
            Object atms = XposedHelpers.callMethod(dc, "getAtmService");
            return atms;
        } catch (Throwable ignored) {
        }
        try {
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", sClassLoader),
                    "getInstance");
            return XposedHelpers.getObjectField(fhc, "mAtms");
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object findTask(int taskId, ClassLoader cl) {
        if (cl == null) cl = sClassLoader;
        if (cl == null) return null;
        try {
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.android.server.wm.FloatHandleController", cl), "getInstance");
            Object atms = XposedHelpers.getObjectField(fhc, "mAtms");
            Object rwc = XposedHelpers.getObjectField(atms, "mRootWindowContainer");
            try {
                return XposedHelpers.callMethod(rwc, "anyTaskForId", taskId);
            } catch (Throwable ignored) {
                return XposedHelpers.callMethod(rwc, "anyTaskForId", taskId, 0);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " findTask failed taskId=" + taskId + ": " + t);
            return null;
        }
    }
}

