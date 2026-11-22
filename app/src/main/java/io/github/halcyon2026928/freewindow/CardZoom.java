package io.github.halcyon2026928.freewindow;

import android.graphics.Rect;
import android.view.View;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 坞窗卡片的「真实小窗对齐」：把任务展开成 ColorOS 自由小窗，几何摆到卡片矩形上，
 * 卡片区留透明洞透出真窗口。
 *
 * <p>阶段二起每张卡片各有一个实例（同一个任务不会同时占两张卡），
 * 静态方法只做入口与批量转发：</p>
 * <ul>
 *   <li>{@link #setActiveCard} + {@link #enterCard} 让某张卡开始接管对应任务；</li>
 *   <li>{@link #syncCard} 坞窗滚动/移动时把所有卡的几何跟上；</li>
 *   <li>{@link #leaveCard} 坞窗收起时把所有卡退回浮标。</li>
 * </ul>
 */
class CardZoom {

    private static final String TAG = "[灵动坞OS-cardzoom]";

    private static final int WINDOWING_MODE_ZOOM = 100;
    private static final int STATE_DEFAULT = 1;
    private static final int STATE_MINI = 2;
    private static final int LAUNCH_FROM = 0;
    private static final String CALL_PKG = "freewindow";

    /** 展开后按几个时间点补钉一次（系统 relayout 会把几何摆回默认）。 */
    private static final int[] REASSERT_DELAYS = {420, 900, 1500};
    /** 展开后持续校验：系统每次 relayout / 焦点变化都可能把窗口摆回默认尺寸。 */
    private static final int WATCHDOG_INTERVAL_MS = 300;
    private static final int WATCHDOG_MAX_TICKS = 40;

    private static final String FLEXIBLE_WM_CLASS =
            "com.android.server.wm.FlexibleWindowManagerService";
    private static final String FLOAT_HANDLE_CONTROLLER_CLASS =
            "com.android.server.wm.FloatHandleController";

    private static ClassLoader sClassLoader;

    /** taskId → 卡片实例，顺序即建卡顺序（日志/遍历用）。 */
    private static final Map<Integer, CardZoom> sCards = new LinkedHashMap<>();
    /** 最近一次 setActiveCard 的那张，供「setActiveCard + enterCard」这种两步式入口使用。 */
    private static CardZoom sLast;

    // ==================== 实例状态 ====================

    private final int taskId;
    private final View anchor;
    private final Object floatInfo;

    private boolean entered;
    private boolean following;
    private boolean leaving;
    private boolean watchdogRunning;
    private Rect lastRect;

    // 进卡片前浮标信息里的几何快照：退出卡片（恢复自由小窗）时必须还回去，
    // 否则官方恢复入口会拿我们写进去的卡片几何去开窗，窗口就停在卡片位置和缩放上。
    private boolean origCaptured;
    private Rect origLaunchBounds;
    private float origScale;
    private float origRatio;

    private CardZoom(int taskId, View anchor, Object floatInfo) {
        this.taskId = taskId;
        this.anchor = anchor;
        this.floatInfo = floatInfo;
    }

    // ==================== 静态入口 ====================

    static void setClassLoader(ClassLoader cl) {
        sClassLoader = cl;
    }

    static boolean isActive() {
        return sLast != null || !sCards.isEmpty();
    }

    static int activeTaskId() {
        return sLast == null ? -1 : sLast.taskId;
    }

    /** 卡片里那块留空区（洞）的 View；多卡场景下用 {@link #anchors()} 拿全部。 */
    static View anchor() {
        return sLast == null ? null : sLast.anchor;
    }

    /** 所有还在接管中的卡片锚点，用来给面板背景挖多个洞。 */
    static List<View> anchors() {
        List<View> list = new ArrayList<>();
        for (CardZoom card : sCards.values()) {
            if (card.anchor != null) list.add(card.anchor);
        }
        return list;
    }

    static int size() {
        return sCards.size();
    }

    /** 给某张卡指定任务与洞位；同一 taskId 重复调用只更新锚点。 */
    static CardZoom setActiveCard(int taskId, View anchor, Object floatInfo) {
        if (taskId <= 0 || anchor == null) return null;
        CardZoom card = sCards.get(taskId);
        if (card != null && card.anchor == anchor) {
            sLast = card;
            return card;
        }
        if (card != null) {
            // 同一个任务换了张卡（坞窗重建/顺序变化）：先把旧卡的收尾做掉，别让两个实例抢同一个任务。
            card.leave("rebind");
        }
        card = new CardZoom(taskId, anchor, floatInfo);
        sCards.put(taskId, card);
        sLast = card;
        XposedBridge.log(TAG + " setActiveCard taskId=" + taskId + " cards=" + sCards.size()
                + " anchor=" + anchor.getWidth() + "x" + anchor.getHeight());
        return card;
    }

    /** 最近一张卡开始接管（坞窗滑入结束后调用）。 */
    static void enterCard(String reason) {
        if (sLast == null) {
            XposedBridge.log(TAG + " enterCard skipped: no card attached");
            return;
        }
        sLast.enter(reason);
    }

    static void enterCard(int taskId, String reason) {
        CardZoom card = sCards.get(taskId);
        if (card == null) {
            XposedBridge.log(TAG + " enterCard skipped: card not attached taskId=" + taskId);
            return;
        }
        card.enter(reason);
    }

    /** 位置/尺寸跟随：全部卡片一起跟上（被坞窗滚动移出视野的卡在 MainHook 里会跳过挖洞）。 */
    static void syncCard(String reason) {
        for (CardZoom card : new ArrayList<>(sCards.values())) {
            card.sync(reason);
        }
    }

    /**
     * 强制对齐：无视「卡片矩形没变就早退」的判断，重新钉一遍几何。
     *
     * <p>滑动坞窗时系统会把小窗 bounds 回滚（真机上 resize 后 300ms 内被改回去过），
     * 这时卡片矩形其实没变，{@link #sync} 会直接 return，画面就停在错的位置不动、
     * 看起来像「不跟着滑」。滚动停下后调一次这个，把窗口按当前卡片矩形重新钉住。</p>
     */
    static void syncCardForce(String reason) {
        for (CardZoom card : new ArrayList<>(sCards.values())) {
            card.syncForce(reason);
        }
    }

    private void syncForce(String reason) {
        if (!entered || !following || taskId <= 0) return;
        lastRect = null;
        sync(reason);
    }

    static void stopFollowing() {
        for (CardZoom card : sCards.values()) {
            card.following = false;
            card.watchdogRunning = false;
        }
    }

    /** 坞窗收起：所有卡都把小窗退回浮标。 */
    static void leaveCard(String reason) {
        for (CardZoom card : new ArrayList<>(sCards.values())) {
            card.leave(reason);
        }
    }

    static void leaveCardExceptForSystemBack(String reason, int taskId) {
        for (CardZoom card : new ArrayList<>(sCards.values())) {
            if (card.taskId != taskId) card.leave(reason);
        }
    }

    /** Drop dock ownership of the task without exiting its freeform state; Back owns it now. */
    static boolean handoffToSystemBack(int taskId) {
        CardZoom card = sCards.remove(taskId);
        if (card == null) return false;
        card.following = false;
        card.watchdogRunning = false;
        card.restoreFloatInfoGeometry();
        card.entered = false;
        card.leaving = true;
        if (sLast == card) {
            sLast = null;
            for (CardZoom remaining : sCards.values()) sLast = remaining;
        }
        XposedBridge.log(TAG + " handed task to native Back taskId=" + taskId
                + " remainingCards=" + sCards.size());
        return true;
    }

    static void clearActiveCard() {
        sCards.clear();
        sLast = null;
        XposedBridge.log(TAG + " clearActiveCard");
    }

    // ==================== 展开 ====================

    /**
     * 把任务展开成自由小窗并摆到卡片矩形上。
     * 路径一：官方「拖拽结束进入小窗」入口，会处理 windowingMode / 迷你态退出 / caption；
     * 路径二：直接写 flexible position info + resize，作为入口不可用时的兜底。
     */
    private boolean enter(String reason) {
        // 任务重新被正常展开：清掉上一轮「用户清除」的标记（比如进程没退干净、
        // 系统把同一个 taskId 又拿出来用），避免这次收起被误判成清除跳过。
        MirageWindowHook.clearUserRemoved(taskId);
        Rect rect = anchorRect();
        if (rect == null || rect.width() <= 0 || rect.height() <= 0) {
            XposedBridge.log(TAG + " enterCard skipped: bad anchor rect=" + rect + " taskId=" + taskId);
            return false;
        }
        Object task = findTask(taskId);
        if (task == null) {
            XposedBridge.log(TAG + " enterCard skipped: task not found taskId=" + taskId);
            return false;
        }
        logTask("enter-before", task);

        boolean viaOfficial = false;
        Object fwms = flexibleService();
        if (fwms != null) {
            try {
                // 先在「任务还没露出来」的时候把几何算准：自由小窗有最小宽度限制，
                // 直接按卡片宽 resize 会被系统拉宽，于是第一帧是「默认大窗」再缩回卡片，
                // 看起来就像一段弹窗动画。这里探一次尺寸，把 scale 和 layout 一次定好。
                Rect layout = new Rect(rect);
                float scale = 1.0f;
                try {
                    Object[] geo = probeGeometry(task, rect);
                    layout = (Rect) geo[0];
                    scale = (Float) geo[1];
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " probeGeometry failed taskId=" + taskId + ": " + t);
                }
                // 先把浮标信息里的原始几何存下来：点击卡片恢复自由小窗时要用它还原。
                captureFloatInfo();
                // 关键：官方入口对浮标任务会转调 FloatHandleController.startActivityByFloatInfo，
                // 它打开窗口用的不是我们传的 rect，而是那份 FloatHandleInfo.flexibleTaskInfo 里
                // 记着的 launchBounds / scale。把这份 info 改成卡片几何，打开落点才会是卡片本身。
                patchFloatInfo(layout, scale);
                // 展开小窗时系统会把悬浮球条目删掉；压住这次删除，条目就一直在，
                // 收起坞窗时不用再走「重建条目 + 快照」那套重活（收起卡顿就卡在那里）。
                MirageWindowHook.keepFloatEntry(taskId, 4000);
                synchronized (globalLock(task)) {
                    XposedHelpers.callMethod(fwms, "finishSplitDragAnimationAndStartFlexible",
                            task, new Rect(layout), scale, LAUNCH_FROM, CALL_PKG);
                }
                viaOfficial = true;
                XposedBridge.log(TAG + " enterCard via finishSplitDragAnimationAndStartFlexible"
                        + " taskId=" + taskId + " rect=" + rect + " layout=" + layout
                        + " scale=" + scale + " cards=" + sCards.size());
            } catch (Throwable t) {
                XposedBridge.log(TAG + " enterCard official path failed taskId=" + taskId + ": " + t);
            }
        }

        // 官方入口内部会转调 FloatHandleController.startActivityByFloatInfo，它会顺手起一个
        // 「从浮标图标放到 launch bounds」的打开动画（FlexibleFloatHandleAnimationSpec, animType=8）。
        // 坞窗场景不需要这段入场动画，直接把动画跳到终点。
        skipOpenAnimation();

        if (!viaOfficial) {
            fallbackApply(task, fwms, rect);
        }

        // 官方入口内部异步落定后还会按记住的 bounds 再 relayout 一次，所以这里不在原地改几何，
        // 等落定后再补。
        entered = true;
        following = true;
        leaving = false;
        lastRect = null;
        logTask("enter-after", findTask(taskId));
        scheduleReassert(reason);
        startWatchdog();
        return true;
    }

    /** 位移小于这个像素数就先不动 WMS，等累计够了一次性改（快速滑动时能砍掉大半 resize）。 */
    private static final int SYNC_MIN_DELTA_PX = 8;

    /** 位置/尺寸跟随：卡片滚动、坞窗移动、方向变化时调用。 */
    private void sync(String reason) {
        if (!entered || !following || taskId <= 0) return;
        Rect rect = anchorRect();
        if (rect == null || rect.width() <= 0 || rect.height() <= 0) return;
        if (lastRect != null && lastRect.equals(rect)) return;
        if (lastRect != null && rect.width() == lastRect.width() && rect.height() == lastRect.height()) {
            int ndx = Math.abs(rect.left - lastRect.left);
            int ndy = Math.abs(rect.top - lastRect.top);
            if (ndx < SYNC_MIN_DELTA_PX && ndy < SYNC_MIN_DELTA_PX) {
                // 不动 lastRect：位移会累计，累计够了再一次性改，画面不会漂。
                return;
            }
        }
        int dx = lastRect == null ? 0 : rect.left - lastRect.left;
        int dy = lastRect == null ? 0 : rect.top - lastRect.top;
        try {
            enforceGeometry(reason);
            lastRect = new Rect(rect);
            XposedBridge.log(TAG + " syncCard(" + reason + ") taskId=" + taskId
                    + " rect=" + rect + " d=" + dx + "," + dy);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " syncCard failed taskId=" + taskId + ": " + t);
        }
    }

    // ==================== 收起 ====================

    /**
     * 坞窗收起：把小窗退出掉，任务压到后台。
     *
     * 直接 setVisibleRequested(false) / setTaskState(2) 是没用的：真机日志里
     * leave-after 仍然是 visible=true，系统根本不理会这两个标记（窗口照旧挂在屏幕上）。
     * 真正会让窗口消失的是系统自己的「退出小窗」流程 exitFlexibleTask。
     *
     * 注意 flexibleCustomTransition 必须传 16，不能传 0：
     *  - 0 = 退小窗到后台（setToBack + moveTaskToBack），窗口是没了，但悬浮球也一起没了；
     *  - 16 = 退小窗到浮标（ExitFlexibleTaskToFloatStrategy 最后一步就是这个），
     *    setTaskState(4) + reparent 到父层 + surface.hide()，窗口藏起来、浮标条目保留。
     * 这里 exitFreeFormStack 传 true：我们隐藏了系统 caption，否则会被
     * "task non freeform" 这个判断直接 return 掉。
     */
    private void leave(String reason) {
        if (taskId <= 0) return;
        if (leaving) {
            XposedBridge.log(TAG + " leaveCard skipped (already leaving) taskId=" + taskId);
            return;
        }
        leaving = true;
        following = false;
        watchdogRunning = false;
        if (MirageWindowHook.isUserRemoved(taskId)) {
            // 用户在坞窗里点了「清除」/「取消运行」：条目已由官方删除、进程正在被杀，
            // 这里不保留、不补回、不再退出小窗，否则会把刚清掉的应用又塞回浮标列表。
            XposedBridge.log(TAG + " leaveCard(" + reason + ") skipped, user-removed taskId=" + taskId);
            entered = false;
            return;
        }
        Object task = findTask(taskId);
        if (task == null) {
            entered = false;
            return;
        }
        Rect lastVisible = null;
        try {
            lastVisible = visibleBoundsOf(task);
        } catch (Throwable ignored) {
        }
        XposedBridge.log(TAG + " leaveCard(" + reason + ") taskId=" + taskId
                + " lastVisible=" + lastVisible);
        // 退出卡片就说明不再需要「打开即落卡片」：把浮标信息里的几何还回进卡片之前的快照。
        // 之后无论走「藏到浮标」（坞窗收起）还是「点卡片恢复自由小窗」（官方入口重启窗口），
        // 用的都是官方原来的 bounds / scale，而不是卡片那一套。
        restoreFloatInfoGeometry();
        // 收起瞬间系统还会再删一次条目：压住它，条目原地保留，免掉重建+快照的卡顿。
        MirageWindowHook.keepFloatEntry(taskId, 5000);
        Object controller = controllerOf(flexibleService());
        // 条目若真的被删过（上面没压住），这里补回去，再让小窗退回浮标：
        // exitFlexibleTask(…,16,…) 只有在「任务在浮标列表里」时才是「藏到浮标」，
        // 否则会走 resetFlexibleTaskToFullScreen。
        restoreFloatHandle(task);
        boolean exited = false;
        if (controller != null) {
            try {
                // 使用系统官方四参数入口；它会启用原生退出转场（withTransition=true），
                // 与从自由小窗收回悬浮球的系统关闭动画保持一致。
                synchronized (globalLock(task)) {
                    XposedHelpers.callMethod(controller, "exitFlexibleTask",
                            task, true, 16, 0);
                }
                exited = true;
                XposedBridge.log(TAG + " leaveCard exitFlexibleTask ok taskId=" + taskId
                        + " lastVisible=" + lastVisible);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " leaveCard exitFlexibleTask failed taskId=" + taskId + ": " + t);
            }
        }
        if (!exited) {
            // 兜底：至少让它不可见
            try {
                if (controller != null) {
                    Object info = XposedHelpers.callMethod(controller, "getFlexibleInfoManager", task);
                    XposedHelpers.callMethod(info, "setTaskState", STATE_MINI);
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " leaveCard setTaskState failed taskId=" + taskId + ": " + t);
            }
            setVisibleRequested(task, false);
        }
        entered = false;
        logTask("leave-after", task);
        // 再拍一次，确认窗口真的走了（还在就补一刀 visibleRequested=false）
        final View a = anchor;
        if (a != null) {
            a.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Object t2 = findTask(taskId);
                    if (t2 == null) return;
                    logTask("leave-check", t2);
                    try {
                        boolean visible = Boolean.TRUE.equals(XposedHelpers.callMethod(t2, "isVisible"));
                        if (visible) setVisibleRequested(t2, false);
                    } catch (Throwable ignored) {
                    }
                }
            }, 400);
        }
    }

    // ==================== 校验 / 补钉 ====================

    /**
     * 卡片对应的「任务真实尺寸 + 缩放」，口径完全照系统的自由小窗。
     *
     * 系统给自由小窗定 scale 的地方是 FlexibleTaskController.getFlexibleTaskTargetScale：
     * 手机（screenType==1）且窗口是竖的（高>宽）时，
     *     scale = 目标可见宽 / getPolicyWidthReferValue(display)
     * 而 getPolicyWidthReferValue = min(logicalWidth, logicalHeight) = 整屏宽（px）。
     * 也就是说：系统的窗口 bounds 永远是「整屏宽」这个参考宽度，应用按整屏宽排版，
     * 再由 scale 把画面等比缩到可见矩形（visible = layout × scale，左上角固定，
     * 见 FlexibleTaskController.scaleBoundsWithFixLeftTop）。小窗被拖小/拖大时应用不会
     * 重新按窄宽度排版，只是整体缩放——这就是小窗里内容比例正常的来源。
     *
     * 之前我们直接把 bounds 压到卡片宽（154dp）+scale=1，等于逼应用按超窄宽度重排，
     * 文字/底栏相对窗口就粗一大圈。现在照系统来：layout 宽 = 整屏宽，scale = 卡片宽/整屏宽。
     */
    private static final int POLICY_REFER_FALLBACK_PX = 1080;

    private Object[] pinGeometry(Object task, Rect visible) {
        int screenW = POLICY_REFER_FALLBACK_PX;
        if (anchor != null) {
            android.util.DisplayMetrics dm = anchor.getContext().getResources().getDisplayMetrics();
            int w = Math.min(dm.widthPixels, dm.heightPixels);
            if (w > 0) screenW = w;
        }
        float scale = visible.width() > 0 ? visible.width() / (float) screenW : 1.0f;
        if (scale <= 0.0f) scale = 1.0f;
        int layoutH = Math.round(visible.height() / scale);
        // 几何口径与系统一致：layout 左上角就是可见矩形左上角，layout 宽度取参考宽度，
        // 高度按 1/scale 放大，保证 visible = layout × scale 正好落在卡片上。
        Rect layout = new Rect(visible.left, visible.top, visible.left + screenW, visible.top + layoutH);
        synchronized (globalLock(task)) {
            Object wrapper = XposedHelpers.callMethod(task, "getWrapper");
            Object ext = XposedHelpers.callMethod(wrapper, "getExtImpl");
            // 先写 scale 再 resize：系统按 layout×scale 判可见矩形，scale 落后一拍会被当越界。
            XposedHelpers.callMethod(ext, "setScale", scale);
            // resizeMode 用 2（PRESERVE_WINDOW_SIZE），和系统自己所有 resize 调用一致。
            XposedHelpers.callMethod(task, "resize", new Rect(layout), 2, false);
            Rect now = (Rect) XposedHelpers.callMethod(task, "getBounds");
            if (now != null && now.width() > 0 && now.width() != layout.width()) {
                // 被系统改过宽度（夹到别的大小）时只记录，不改 scale：
                // 这里有意义的只有「可见宽 / 参考宽」这一个比例，跟着被改的宽度走会让内容比例再次跑偏。
                XposedBridge.log(TAG + " pinGeometry width changed taskId=" + taskId
                        + " want=" + layout.width() + " got=" + now.width());
            }
        }
        return new Object[]{layout, scale};
    }

    /**
     * 在任务还没显示出来的时候先钉一次几何：算出 layout / scale，交给官方入口
     * 直接把落定位置定成卡片，第一帧就不会出现「默认大窗再缩回卡片」。
     */
    private Object[] probeGeometry(Object task, Rect visible) {
        Object[] geo = pinGeometry(task, visible);
        XposedBridge.log(TAG + " probeGeometry taskId=" + taskId + " visible=" + visible
                + " layout=" + geo[0] + " scale=" + geo[1]
                + " readback=" + XposedHelpers.callMethod(task, "getBounds"));
        return geo;
    }

    /**
     * 把卡片几何写进那份 FloatHandleInfo.flexibleTaskInfo。
     * 官方入口对浮标任务会转调 startActivityByFloatInfo，它打开窗口时用的就是这份
     * info 里记着的 launchBounds / scale（默认 770 宽），而不是我们传给入口的 rect；
     * 不改这里，前面所有 resize / 传参都会被它覆盖回默认窗口。
     */
    private void patchFloatInfo(Rect layout, float scale) {
        if (floatInfo == null) return;
        try {
            Object fti = XposedHelpers.getObjectField(floatInfo, "flexibleTaskInfo");
            if (fti == null) return;
            // 先把可能残存的 originLaunchBounds 清掉：startActivityByFloatInfo 若发现它
            // 非空，会无条件用 origin 覆盖 launchBounds，等于又把几何改回旧值。
            try {
                XposedHelpers.callMethod(fti, "setOriginLaunchBounds", (Object) null);
            } catch (Throwable ignored) {
            }
            XposedHelpers.callMethod(fti, "setLaunchBounds", new Rect(layout));
            XposedHelpers.callMethod(fti, "setScale", scale);
            XposedBridge.log(TAG + " patchFloatInfo taskId=" + taskId
                    + " layout=" + layout + " scale=" + scale);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " patchFloatInfo failed taskId=" + taskId + ": " + t);
        }
    }

    /**
     * 进卡片前，把浮标信息里的原始几何存一份快照。
     *
     * <p>patchFloatInfo 会把 launchBounds / scale / ratio 改成卡片几何，好让「打开即落卡片」；
     * 但点击卡片恢复自由小窗时走的是官方入口（startFlexibleFreeformFromHandleView →
     * startActivityByFloatInfo），它用的还是这份 info —— 不还原就会把窗口开在卡片的位置和缩放上。</p>
     */
    private void captureFloatInfo() {
        // 已经持有快照就不再覆盖：一张卡连续 enterCard 时，fti 里此刻可能已经是
        // 我们写进去的卡片几何，再存一次就把「原始值」污染成卡片值了。
        if (origCaptured) return;
        if (floatInfo == null) return;
        try {
            Object fti = XposedHelpers.getObjectField(floatInfo, "flexibleTaskInfo");
            if (fti == null) return;
            origLaunchBounds = (Rect) XposedHelpers.callMethod(fti, "getLaunchBounds");
            origScale = origLaunchBounds == null
                    ? 0f : ((Number) XposedHelpers.callMethod(fti, "getScale")).floatValue();
            origRatio = ((Number) XposedHelpers.callMethod(fti, "getRatio")).floatValue();
            origCaptured = true;
            XposedBridge.log(TAG + " captureFloatInfo taskId=" + taskId
                    + " launchBounds=" + origLaunchBounds + " scale=" + origScale
                    + " ratio=" + origRatio);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " captureFloatInfo failed taskId=" + taskId + ": " + t);
        }
    }

    /**
     * 退出卡片、恢复自由小窗：把浮标信息里的几何还回进卡片之前的快照。
     *
     * <p>必须在「官方恢复入口被调用之前」执行——官方入口读的就是这份 info。
     * 快照为 null（进卡片时本来就没有 launchBounds，比如从迷你态贴边过来的）时同样写回 null，
     * 让官方入口按 calcDefaultLaunchBounds 自己算默认窗口，这才是真正的官方状态。</p>
     */
    private void restoreFloatInfoGeometry() {
        if (!origCaptured) return;
        origCaptured = false;
        if (floatInfo == null) return;
        try {
            Object fti = XposedHelpers.getObjectField(floatInfo, "flexibleTaskInfo");
            if (fti == null) return;
            XposedHelpers.callMethod(fti, "setLaunchBounds",
                    origLaunchBounds == null ? null : new Rect(origLaunchBounds));
            if (origLaunchBounds != null) {
                XposedHelpers.callMethod(fti, "setScale", origScale);
            }
            if (origRatio > 0f) {
                XposedHelpers.callMethod(fti, "setRatio", origRatio);
            }
            XposedBridge.log(TAG + " restoreFloatInfoGeometry taskId=" + taskId
                    + " launchBounds=" + origLaunchBounds + " scale=" + origScale
                    + " ratio=" + origRatio);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " restoreFloatInfoGeometry failed taskId=" + taskId + ": " + t);
        }
    }

    /**
     * 跳过「打开小窗」的入场动画：动画 spec 是在 startActivityByFloatInfo 里建的，
     * 我们调完官方入口后它已经在 FlexibleFloatHandleAnimController 的 map 里，
     * 用系统自己的 checkAndSkipToEndLastAnim 直接跳到终点。
     * spec 建得比我们晚一拍时，靠后面几次重试补上。
     */
    private void skipOpenAnimation() {
        final int[] delays = {0, 60, 150, 300};
        final int id = taskId;
        final View a = anchor;
        for (final int delay : delays) {
            Runnable runnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        ClassLoader cl = sClassLoader;
                        if (cl == null) return;
                        Class<?> clazz = XposedHelpers.findClass(
                                "com.android.server.wm.FlexibleFloatHandleAnimController", cl);
                        Object controller = XposedHelpers.callStaticMethod(clazz, "getInstance");
                        Object spec = XposedHelpers.callMethod(controller, "getFloatHandleAnimationSpec", id);
                        if (spec == null) return;
                        XposedHelpers.callMethod(controller, "checkAndSkipToEndLastAnim", id);
                        int type = (Integer) XposedHelpers.callMethod(spec, "getAnimationType");
                        XposedBridge.log(TAG + " skipOpenAnimation@" + delay + " taskId=" + id
                                + " animType=" + type);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " skipOpenAnimation@" + delay + " failed taskId=" + id + ": " + t);
                    }
                }
            };
            if (delay == 0 || a == null) {
                runnable.run();
            } else {
                a.postDelayed(runnable, delay);
            }
        }
    }

    /** 把悬浮球条目补回列表（任务展开成小窗时系统已经摘掉了它）。 */
    private void restoreFloatHandle(Object task) {
        try {
            Object fhc = floatController();
            if (fhc == null) return;
            boolean inList = Boolean.TRUE.equals(
                    XposedHelpers.callMethod(fhc, "isInFloatingList", taskId));
            if (inList) {
                XposedBridge.log(TAG + " restoreFloatHandle: already in list taskId=" + taskId);
                return;
            }
            if (floatInfo != null) {
                XposedHelpers.callMethod(fhc, "addFloatHandle", floatInfo, 1);
                XposedBridge.log(TAG + " restoreFloatHandle: re-added saved info taskId=" + taskId);
                return;
            }
            if (task == null) return;
            // 没有留存的 info 就按系统自己的口径重建一份
            Object controller = controllerOf(flexibleService());
            if (controller == null) return;
            Object flexInfo = XposedHelpers.callMethod(
                    XposedHelpers.callMethod(controller, "getFlexibleInfoManager", task),
                    "getTaskInfo");
            Object fresh = XposedHelpers.callMethod(fhc, "createFloatHandleInfo",
                    flexInfo, 0, 0, task);
            XposedHelpers.callMethod(fhc, "addFloatHandle", fresh, 1);
            XposedBridge.log(TAG + " restoreFloatHandle: created fresh info taskId=" + taskId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " restoreFloatHandle failed taskId=" + taskId + ": " + t);
        }
    }

    private static Object floatController() {
        ClassLoader cl = sClassLoader;
        if (cl == null) return null;
        try {
            return XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(FLOAT_HANDLE_CONTROLLER_CLASS, cl), "getInstance");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " floatController failed: " + t);
            return null;
        }
    }

    private void startWatchdog() {
        if (watchdogRunning) return;
        watchdogRunning = true;
        tickWatchdog(0);
    }

    private void tickWatchdog(final int tick) {
        final View a = anchor;
        if (a == null || !entered || !following || tick >= WATCHDOG_MAX_TICKS) {
            watchdogRunning = false;
            return;
        }
        a.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!entered || !following || taskId <= 0) {
                    watchdogRunning = false;
                    return;
                }
                try {
                    enforceGeometry("watchdog@" + tick);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " watchdog failed taskId=" + taskId + ": " + t);
                }
                tickWatchdog(tick + 1);
            }
        }, WATCHDOG_INTERVAL_MS);
    }

    private void scheduleReassert(final String reason) {
        for (final int delay : REASSERT_DELAYS) {
            final View a = anchor;
            if (a == null) return;
            a.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!entered || !following || taskId <= 0) return;
                    Object task = findTask(taskId);
                    if (task == null || anchorRect() == null) {
                        XposedBridge.log(TAG + " reassert@" + delay + " skipped taskId=" + taskId
                                + " task=" + task);
                        return;
                    }
                    logTask("reassert@" + delay + "-before", task);
                    enforceGeometry(reason + "@" + delay);
                    logTask("reassert@" + delay + "-after", findTask(taskId));
                }
            }, delay);
        }
    }

    /** 读回真实几何，跟卡片矩形不一致就重钉一遍；一致就什么都不做。 */
    private boolean enforceGeometry(String reason) {
        if (taskId <= 0) return false;
        Object task = findTask(taskId);
        Rect want = anchorRect();
        if (task == null || want == null || want.width() <= 0 || want.height() <= 0) return false;
        Rect nowVisible;
        int mode;
        try {
            nowVisible = visibleBoundsOf(task);
            mode = (Integer) XposedHelpers.callMethod(task, "getWindowingMode");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " enforce read failed taskId=" + taskId + ": " + t);
            return false;
        }
        if (mode == WINDOWING_MODE_ZOOM && want.equals(nowVisible)) return false;
        XposedBridge.log(TAG + " enforce(" + reason + ") taskId=" + taskId + " now=" + nowVisible
                + " mode=" + mode + " want=" + want);
        Object fwms = flexibleService();
        ensureZoomMode(task, fwms);
        applyGeometry(task, fwms, want);
        setCaptionVisible(controllerOf(fwms), task, false);
        return true;
    }

    /** 按 scale 把任务的实际尺寸换算成屏幕上看到的矩形（visible = layout × scale，左上固定）。 */
    private static Rect visibleBoundsOf(Object task) {
        Rect layout = (Rect) XposedHelpers.callMethod(task, "getBounds");
        float scale = 1.0f;
        try {
            Object wrapper = XposedHelpers.callMethod(task, "getWrapper");
            Object ext = XposedHelpers.callMethod(wrapper, "getExtImpl");
            Object value = XposedHelpers.callMethod(ext, "getScale");
            if (value instanceof Float) scale = (Float) value;
            else if (value instanceof Number) scale = ((Number) value).floatValue();
        } catch (Throwable ignored) {
        }
        if (layout == null) layout = new Rect();
        if (scale <= 0f) scale = 1.0f;
        int l = layout.left;
        int t = layout.top;
        int w = Math.round(layout.width() * scale);
        int h = Math.round(layout.height() * scale);
        return new Rect(l, t, l + w, t + h);
    }

    /** 小窗必须是 windowingMode=100；否则应用按全屏 relayout，内容会溢出卡片。 */
    private void ensureZoomMode(Object task, Object fwms) {
        try {
            int mode = (Integer) XposedHelpers.callMethod(task, "getWindowingMode");
            if (mode == WINDOWING_MODE_ZOOM) return;
            XposedBridge.log(TAG + " forceZoomMode from mode=" + mode + " taskId=" + taskId);
            synchronized (globalLock(task)) {
                XposedHelpers.callMethod(task, "setWindowingMode", WINDOWING_MODE_ZOOM);
            }
            int now = (Integer) XposedHelpers.callMethod(task, "getWindowingMode");
            XposedBridge.log(TAG + " forceZoomMode result mode=" + now + " taskId=" + taskId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " forceZoomMode failed taskId=" + taskId + ": " + t);
        }
    }

    // ==================== 内部 ====================

    /**
     * 把窗口几何钉到可见矩形上。
     *
     * ColorOS 的 freeform 任务有两套矩形：layout bounds（任务真正 resize 的尺寸）和
     * visible bounds（屏幕上看到的尺寸），关系是 visible = layout × scale，左上角固定
     * （FlexibleTaskController.scaleBoundsWithFixLeftTop）。
     *
     * 几何统一交给 pinGeometry：layout 宽取系统的参考宽度（整屏宽），scale 把画面压到卡片，
     * 和小窗被拖小时的表现完全一致。
     */
    private void applyGeometry(Object task, Object fwms, Rect visible) {
        try {
            // 和 probeGeometry 走同一套几何，保证第一帧与后续补钉的口径一致。
            Object[] geo = pinGeometry(task, visible);
            Rect layout = (Rect) geo[0];
            float scale = (Float) geo[1];
            Object controller = controllerOf(fwms);
            if (controller != null) {
                Object info = XposedHelpers.callMethod(controller, "getFlexibleInfoManager", task);
                XposedHelpers.callMethod(info, "setTaskState", STATE_DEFAULT);
                XposedHelpers.callMethod(info, "setFlexiblePositionInfo", new Rect(visible), scale);
                XposedHelpers.callMethod(controller, "updateTaskExtraInfo",
                        task, STATE_DEFAULT, new Rect(visible), new Rect(layout), scale);
            }
            XposedBridge.log(TAG + " applyGeometry taskId=" + taskId + " visible=" + visible
                    + " layout=" + layout + " scale=" + scale
                    + " readback=" + XposedHelpers.callMethod(task, "getBounds"));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " applyGeometry failed taskId=" + taskId + ": " + t);
        }
    }

    /** 系统 caption（图标/名称/三点那条）对坞窗卡片是多余的，隐藏掉让内容区等于卡片矩形。 */
    private void setCaptionVisible(Object controller, Object task, boolean visible) {
        if (controller == null) return;
        try {
            XposedHelpers.callMethod(controller, "updateFlexibleTaskCaptionVisibleIfNeed", task, visible);
            XposedBridge.log(TAG + " captionVisible=" + visible + " taskId=" + taskId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " captionVisible failed taskId=" + taskId + ": " + t);
        }
    }

    private void fallbackApply(Object task, Object fwms, Rect rect) {
        XposedBridge.log(TAG + " fallbackApply taskId=" + taskId + " rect=" + rect);
        try {
            synchronized (globalLock(task)) {
                XposedHelpers.callMethod(task, "setWindowingMode", WINDOWING_MODE_ZOOM);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " fallback setWindowingMode failed taskId=" + taskId + ": " + t);
        }
        applyGeometry(task, fwms, rect);
        setVisibleRequested(task, true);
        try {
            XposedHelpers.callMethod(task, "resumeTopActivityUncheckedLocked");
        } catch (Throwable t) {
            try {
                XposedHelpers.callMethod(task, "resumeTopActivityUncheckedLocked", null, null);
            } catch (Throwable ignored) {
            }
        }
    }

    private static Object flexibleService() {
        ClassLoader cl = sClassLoader;
        if (cl == null) return null;
        try {
            Class<?> clazz = XposedHelpers.findClass(FLEXIBLE_WM_CLASS, cl);
            return XposedHelpers.callStaticMethod(clazz, "getInstance", new Object[]{null});
        } catch (Throwable t) {
            XposedBridge.log(TAG + " flexibleService failed: " + t);
            return null;
        }
    }

    private static Object controllerOf(Object fwms) {
        if (fwms == null) return null;
        try {
            return XposedHelpers.getObjectField(fwms, "mFlexibleTaskController");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " controllerOf failed: " + t);
            return null;
        }
    }

    private static Object findTask(int taskId) {
        ClassLoader cl = sClassLoader;
        if (cl == null) return null;
        try {
            Object fhc = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(FLOAT_HANDLE_CONTROLLER_CLASS, cl), "getInstance");
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

    private static Object globalLock(Object task) {
        try {
            Object dc = XposedHelpers.callMethod(task, "getDisplayContent");
            Object atms = XposedHelpers.callMethod(dc, "getAtmService");
            return XposedHelpers.getObjectField(atms, "mGlobalLock");
        } catch (Throwable t) {
            return new Object();
        }
    }

    private static void setVisibleRequested(Object task, boolean visible) {
        try {
            synchronized (globalLock(task)) {
                XposedHelpers.callMethod(task, "setVisibleRequested", visible);
            }
            XposedBridge.log(TAG + " setVisibleRequested(" + visible + ")");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " setVisibleRequested(" + visible + ") failed: " + t);
        }
    }

    private Rect anchorRect() {
        View a = anchor;
        if (a == null || a.getWidth() <= 0 || a.getHeight() <= 0) return null;
        int[] loc = new int[2];
        try {
            a.getLocationOnScreen(loc);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " anchorRect failed taskId=" + taskId + ": " + t);
            return null;
        }
        return new Rect(loc[0], loc[1], loc[0] + a.getWidth(), loc[1] + a.getHeight());
    }

    private void logTask(String stage, Object task) {
        if (task == null) {
            XposedBridge.log(TAG + " " + stage + " task=null taskId=" + taskId);
            return;
        }
        String state;
        try {
            Rect bounds = (Rect) XposedHelpers.callMethod(task, "getBounds");
            int mode = (Integer) XposedHelpers.callMethod(task, "getWindowingMode");
            boolean visible = Boolean.TRUE.equals(XposedHelpers.callMethod(task, "isVisible"));
            Object vreq = null;
            try {
                vreq = XposedHelpers.callMethod(task, "isVisibleRequested");
            } catch (Throwable ignored) {
            }
            state = "taskId=" + taskId + " mode=" + mode + " bounds=" + bounds
                    + " visible=" + visible + " visibleRequested=" + vreq;
        } catch (Throwable t) {
            state = "taskId=" + taskId + " state unreadable: " + t;
        }
        XposedBridge.log(TAG + " " + stage + " " + state);
    }
}
