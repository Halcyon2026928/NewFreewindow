package io.github.halcyon2026928.freewindow;

import android.content.Context;
import android.database.Cursor;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;


/**
 * 开机首次解锁后自动打开系统「无线调试」。
 *
 * <p>关键结论：只写 {@code Settings.Global.adb_wifi_enabled=1} **不会**真正打开无线调试。
 * 在 AOSP 里那只是「UI 状态值」，真正启动是 system_server 的
 * {@code AdbDebuggingManager.setAdbEnabled(true, AdbTransportType.WIFI)} →
 * {@code AdbDebuggingHandler.MSG_ADBDWIFI_ENABLE}，它内部会：
 * <ol>
 *   <li>取当前 Wi-Fi 的 BSSID/SSID，取不到就把 adb_wifi_enabled 写回 0；</li>
 *   <li>{@code verifyWifiNetwork()} 校验该网络是否在可信列表，不可信就写回 0 并弹确认；</li>
 *   <li>设置 {@code persist.adb.wifi.guid} 之类的 config property，起 adbd 的 TLS 监听线程。</li>
 * </ol>
 * 所以旧版只写 setting 的结果是：日志里能看到 wrote=1，但端口从未起、值随后又被系统改回 0。
 *
 * <p>本版做法：
 * <ol>
 *   <li>hook {@code AdbDebuggingManager} 构造函数，留下实例；</li>
 *   <li>hook 私有校验 {@code verifyWifiNetwork} 使其直接通过（自家设备，免得每次开要人工点“允许”）；</li>
 *   <li>解锁 + Wi-Fi 已连接后，调 {@code setAdbEnabled(true, WIFI)} 走官方启用路径，
 *       同时写 setting 让开发者选项里的开关显示为开；</li>
 *   <li>3 秒后回读 setting，把结果写进 LSPosed 日志（写入被回滚、端口未起都能一眼看出）。</li>
 * </ol>
 */
public final class WirelessAdbAuto {

    /** 设置页开关的键，和 SettingsActivity / ConfigProvider 保持一致。 */
    public static final String KEY = "auto_wireless_adb";

    private static final String TAG = "灵动坞OS-wirelessadb";
    private static final String GLOBAL_KEY = "adb_wifi_enabled";
    /** AdbTransportType.aidl: USB = 0, WIFI = 1 */
    private static final byte TRANSPORT_WIFI = 1;

    private static final int RETRY_LIMIT = 80;      // 80 × 3s ≈ 4 分钟，足够撑到解锁 + Wi-Fi 连上
    private static final long RETRY_MS = 3000L;
    private static final long VERIFY_MS = 3000L;
    /** 被系统抹回 0 时最多补开几次（开机后 Wi-Fi 状态广播会关一次，补回来就稳定了）。 */
    private static final int REPAIR_LIMIT = 5;

    /**
     * 保活：锁屏 / 深度睡眠一段时间后系统会自己把无线调试关掉（写 adb_wifi_enabled=0 或
     * setAdbEnabled(false, WIFI)）。这里每隔 KEEP_ALIVE_MS 复查一次，被关了就补开，
     * 只有「用户在设置里手动关」才不再自动恢复。
     */
    private static final long KEEP_ALIVE_MS = 30000L;
    /** 手动关闭标记：用户在设置里关掉无线调试时置位，保活暂停，用户重新打开时清除。 */
    private static volatile boolean sUserDisabled = false;
    private static volatile boolean sKeepAliveRunning = false;
    private static volatile Context sKeepCtx;
    private static volatile WifiManager.WifiLock sWifiLock;

    /** AdbDebuggingManager 实例（system_server 里只有一份）。 */
    private static volatile Object sManager;
    private static volatile boolean sVerifyHookLogged = false;
    private static volatile boolean sFinished = false;

    private WirelessAdbAuto() {
    }

    public static void install(ClassLoader cl) {
        hookManager(cl);
        hookSettingWrites(cl);
        hookDebuggingMessages(cl);
        hookUserToggle(cl);
        try {
            Class<?> ams = XposedHelpers.findClass(
                    "com.android.server.am.ActivityManagerService", cl);
            XposedBridge.hookAllMethods(ams, "systemReady", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        start(param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " start error: " + t.getMessage());
                    }
                }
            });
            XposedBridge.log(TAG + " installed (adb wifi enable via AdbDebuggingManager)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " install failed: " + t.getMessage());
        }
    }

    /** 拿 AdbDebuggingManager 实例，并让「Wi-Fi 是否可信」的校验直接通过。 */
    private static void hookManager(ClassLoader cl) {
        try {
            Class<?> manager = XposedHelpers.findClass(
                    "com.android.server.adb.AdbDebuggingManager", cl);
            XposedBridge.hookAllConstructors(manager, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sManager = param.thisObject;
                    XposedBridge.log(TAG + " AdbDebuggingManager captured");
                }
            });
            // 私有的网络可信校验：返回 true = 视为已信任网络，跳过“是否允许在此网络调试”的确认。
            Class<?> handler = XposedHelpers.findClass(
                    "com.android.server.adb.AdbDebuggingManager$AdbDebuggingHandler", cl);
            XposedBridge.hookAllMethods(handler, "verifyWifiNetwork", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(true);
                    if (!sVerifyHookLogged) {
                        sVerifyHookLogged = true;
                        XposedBridge.log(TAG + " verifyWifiNetwork forced true (network trusted)");
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " manager hook failed: " + t.getMessage());
        }
    }

    /** 谁在写 adb_wifi_enabled：把写入方（调用栈）记下来；被抹回 0 时立刻补开。 */
    private static void hookSettingWrites(ClassLoader cl) {
        try {
            Class<?> global = XposedHelpers.findClass("android.provider.Settings$Global", cl);
            XposedBridge.hookAllMethods(global, "putInt", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 3) return;
                        if (!GLOBAL_KEY.equals(param.args[1])) return;
                        Object raw = param.args[2];
                        int value = raw instanceof Integer ? (Integer) raw : 0;
                        XposedBridge.log(TAG + " write " + GLOBAL_KEY + "=" + raw
                                + " screen=" + screenState() + " by " + caller());
                        // 系统自己关掉（锁屏计时、省电策略、Wi-Fi 抖动回滚）→ 当场补开。
                        // 用户手动关的那条走 setAdbEnabled(false)，会先置 sUserDisabled，这里就不动了。
                        if (value == 0 && !sUserDisabled && isEnabledOrUnknown()) {
                            Context ctx = sKeepCtx;
                            if (ctx != null && isUserUnlocked(ctx) && hasWifiConnection(ctx)) {
                                XposedBridge.log(TAG + " system closed wireless adb -> re-enable now");
                                Settings.Global.putInt(ctx.getContentResolver(), GLOBAL_KEY, 1);
                                sendEnable();
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " setting write hook failed: " + t.getMessage());
        }
    }

    /**
     * 区分「用户手动关」和「系统自动关」：
     * 用户开关在设置 App 里，关的时候走 AdbManager → system_server 的
     * AdbDebuggingManager.setAdbEnabled(false, WIFI)，此时 Binder.getCallingUid()
     * 是设置 App 的 uid。系统自己关是 system_server 内部调用（uid=1000）。
     */
    private static void hookUserToggle(ClassLoader cl) {
        try {
            Class<?> manager = XposedHelpers.findClass(
                    "com.android.server.adb.AdbDebuggingManager", cl);
            XposedBridge.hookAllMethods(manager, "setAdbEnabled", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 1) return;
                        Object a0 = param.args[0];
                        if (!(a0 instanceof Boolean)) return;
                        if ((Boolean) a0) {
                            if (sUserDisabled) {
                                sUserDisabled = false;
                                XposedBridge.log(TAG + " wireless adb re-enabled -> keep-alive resumes");
                            }
                            return;
                        }
                        int uid = Binder.getCallingUid();
                        String pkg = packageForUid(uid);
                        XposedBridge.log(TAG + " setAdbEnabled(false) uid=" + uid + " pkg=" + pkg);
                        if (pkg != null && pkg.contains("settings")) {
                            sUserDisabled = true;
                            XposedBridge.log(TAG + " user disabled wireless adb manually -> keep-alive paused");
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.log(TAG + " user toggle hook installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " user toggle hook failed: " + t.getMessage());
        }
    }

    /** 记录写入那一刻屏幕亮着还是息屏，用来区分「锁屏/息屏触发关闭」。 */
    private static String screenState() {
        try {
            Context ctx = sKeepCtx;
            if (ctx == null) return "?";
            Object pm = ctx.getSystemService(Context.POWER_SERVICE);
            Object on = XposedHelpers.callMethod(pm, "isScreenOn");
            return on instanceof Boolean && (Boolean) on ? "on" : "off";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String packageForUid(int uid) {
        try {
            Context ctx = sKeepCtx;
            if (ctx == null) return null;
            Object pm = ctx.getPackageManager();
            String[] names = (String[]) XposedHelpers.callMethod(pm, "getPackagesForUid", uid);
            if (names != null && names.length > 0) return names[0];
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 记录 AdbDebuggingHandler 收到的消息号：11=ENABLE 12=DISABLE 24=TLS起 25=TLS断 等。 */
    private static void hookDebuggingMessages(ClassLoader cl) {
        try {
            Class<?> handler = XposedHelpers.findClass(
                    "com.android.server.adb.AdbDebuggingManager$AdbDebuggingHandler", cl);
            XposedBridge.hookAllMethods(handler, "handleMessage", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object msg = param.args[0];
                        int what = XposedHelpers.getIntField(msg, "what");
                        // 只看与无线调试相关的，避免刷屏
                        if (what >= 11 && what <= 12 || what >= 21 && what <= 29) {
                            XposedBridge.log(TAG + " adb handler msg=" + what);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " handler message hook failed: " + t.getMessage());
        }
    }

    private static String caller() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : st) {
            String c = e.getClassName();
            // 跳过 Xposed 框架和调度层，也跳过本模块自己的栈帧 ——
            // 否则打印出来永远是 WirelessAdbAuto$4#afterHookedMethod，看不出真正是谁写的值。
            if (c.startsWith("de.robv.") || c.startsWith("android.os.Handler")
                    || c.startsWith("android.os.Looper") || c.startsWith("java.lang.Thread")
                    || c.startsWith("io.github.halcyon2026928.freewindow")) {
                continue;
            }
            if (sb.length() > 0) sb.append(" <- ");
            sb.append(c).append('#').append(e.getMethodName())
                    .append(':').append(e.getLineNumber());
            if (++n >= 6) break;
        }
        return sb.length() == 0 ? "(unknown)" : sb.toString();
    }

    private static void start(Object ams) {
        if (sFinished) return;
        Context ctx;
        try {
            ctx = (Context) XposedHelpers.getObjectField(ams, "mContext");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " no system context: " + t.getMessage());
            return;
        }
        if (ctx == null) return;
        startKeepAlive(ctx, resolveHandler(ams));
        attempt(ctx, resolveHandler(ams), 0);
    }

    /** 保活轮询：每 30 秒复查一次，被系统关掉就补开；用户手动关过就停下不动。 */
    private static void startKeepAlive(final Context ctx, final Handler handler) {
        sKeepCtx = ctx;
        acquireWifiLock(ctx);
        if (sKeepAliveRunning) return;
        sKeepAliveRunning = true;
        XposedBridge.log(TAG + " keep-alive started, interval=" + KEEP_ALIVE_MS + "ms");
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                keepAliveTick();
                handler.postDelayed(this, KEEP_ALIVE_MS);
            }
        }, KEEP_ALIVE_MS);
    }

    private static void keepAliveTick() {
        Context ctx = sKeepCtx;
        if (ctx == null) return;
        try {
            if (!isEnabledOrUnknown()) return;
            if (sUserDisabled) return;
            int v = Settings.Global.getInt(ctx.getContentResolver(), GLOBAL_KEY, 0);
            if (v == 1) return;
            if (!isUserUnlocked(ctx)) return;
            if (!hasWifiConnection(ctx)) {
                XposedBridge.log(TAG + " keep-alive: wifi not connected, skip");
                return;
            }
            XposedBridge.log(TAG + " keep-alive: adb_wifi_enabled=0 -> re-enable");
            Settings.Global.putInt(ctx.getContentResolver(), GLOBAL_KEY, 1);
            sendEnable();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " keep-alive error: " + t.getMessage());
        }
    }

    /**
     * 持一个 WifiLock，避免锁屏后 Wi-Fi 为省电进入休眠、把无线调试一起带走。
     * WIFI_MODE_FULL：保持连接不断，但不追求满速，耗电比 HIGH_PERF 低。
     */
    private static void acquireWifiLock(Context ctx) {
        if (sWifiLock != null) return;
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return;
            WifiManager.WifiLock lock = wm.createWifiLock(1 /* WIFI_MODE_FULL */, "freewindow:adb-wifi");
            lock.setReferenceCounted(false);
            lock.acquire();
            sWifiLock = lock;
            XposedBridge.log(TAG + " wifi lock acquired (wifi stays awake for adb)");
        } catch (Throwable t) {
            sWifiLock = null;
            XposedBridge.log(TAG + " wifi lock failed: " + t.getMessage());
        }
    }

    private static void attempt(final Context ctx, final Handler handler, final int n) {
        if (sFinished) return;
        Boolean enabled = readEnabled(ctx);
        if (enabled == null) {
            // 读不到（模块 app 还没被拉到、prefs 也还不可读）→ 继续等，别一锤定音放弃
            if (n >= RETRY_LIMIT) {
                XposedBridge.log(TAG + " give up: config still unreadable");
                sFinished = true;
                return;
            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    attempt(ctx, handler, n + 1);
                }
            }, RETRY_MS);
            return;
        }
        if (!enabled) {
            XposedBridge.log(TAG + " auto wireless adb is off, skip");
            sFinished = true;
            return;
        }
        if (!isUserUnlocked(ctx) || !hasWifiConnection(ctx)) {
            if (n >= RETRY_LIMIT) {
                XposedBridge.log(TAG + " give up waiting (unlocked=" + isUserUnlocked(ctx)
                        + ", wifi=" + hasWifiConnection(ctx) + ")");
                sFinished = true;
                return;
            }
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    attempt(ctx, handler, n + 1);
                }
            }, RETRY_MS);
            return;
        }

        write(ctx, handler, n);
    }

    private static void write(final Context ctx, final Handler handler, int n) {
        try {
            Settings.Global.putInt(ctx.getContentResolver(), GLOBAL_KEY, 1);
            XposedBridge.log(TAG + " wrote " + GLOBAL_KEY + "=1 (attempt " + n + ")");
            sendEnable();
            scheduleVerify(ctx, handler, 0);

            sFinished = true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " write failed: " + t.getMessage());
        }
    }

    private static void sendEnable() {
        Object manager = sManager;
        if (manager == null) {
            XposedBridge.log(TAG + " no AdbDebuggingManager yet, setting only (port will not open)");
            return;
        }
        try {
            XposedHelpers.callMethod(manager, "setAdbEnabled", true, TRANSPORT_WIFI);
            XposedBridge.log(TAG + " setAdbEnabled(true, WIFI) sent");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " setAdbEnabled failed: " + t.getMessage());
        }
    }

    /**
     * 回读校验：开机后系统会因为 Wi-Fi 网络状态广播、adbd TLS 断开等原因把 adb_wifi_enabled
     * 抹回 0。这里发现被抹掉就补开一次（最多 REPAIR_LIMIT 次），把“刚开就被关”的窗口期扛过去。
     */
    private static void scheduleVerify(final Context ctx, final Handler handler, final int repair) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    int v = Settings.Global.getInt(ctx.getContentResolver(), GLOBAL_KEY, 0);
                    if (v == 1) {
                        XposedBridge.log(TAG + " verify: " + GLOBAL_KEY + "=1, wireless adb on (repair="
                                + repair + ")");
                        return;
                    }
                    XposedBridge.log(TAG + " verify: " + GLOBAL_KEY + "=0, rolled back (repair=" + repair + ")");
                    if (repair >= REPAIR_LIMIT) {
                        XposedBridge.log(TAG + " give up repairing, wireless adb stays off");
                        return;
                    }
                    Settings.Global.putInt(ctx.getContentResolver(), GLOBAL_KEY, 1);
                    sendEnable();
                    scheduleVerify(ctx, handler, repair + 1);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " verify failed: " + t.getMessage());
                }
            }
        }, VERIFY_MS);
    }

    /**
     * 读「自动打开无线调试」开关。返回 null 表示**读不到**（既没拿到 provider 结果，
     * prefs 也不可读），调用方应重试而不是当成关闭。
     *
     * <p>两条通道：
     * <ol>
     *   <li>ConfigProvider（模块 app 里的 ContentProvider，system_server 直接 query，
     *       会顺带把模块进程拉起来；迁移到 modern API 102 后这条是主力通道）；</li>
     *   <li>模块 prefs XML（模块 app 已把它设成 world-readable 时可用，作为兜底）。</li>
     * </ol>
     */
    private static Boolean readEnabled(Context ctx) {
        if (ctx != null) {
            Cursor c = null;
            try {
                c = ctx.getContentResolver().query(ConfigProvider.CONFIG_URI, null, null, null, null);
                if (c != null) {
                    while (c.moveToNext()) {
                        if (KEY.equals(c.getString(0))) {
                            return c.getInt(1) == 1;
                        }
                    }
                    // 查询成功但没有这个键：说明 provider 已就绪、键缺失 → 当作关闭
                    return Boolean.FALSE;
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " read provider failed: " + t.getMessage());
            } finally {
                if (c != null) {
                    try {
                        c.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        try {
            XSharedPreferences prefs = new XSharedPreferences(ModulePrefs.PKG, ModulePrefs.NAME);
            prefs.reload();
            if (!prefs.getFile().canRead()) {
                XposedBridge.log(TAG + " prefs unreadable: " + prefs.getFile());
                return null;
            }
            return prefs.getBoolean(KEY, false);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " read prefs failed: " + t.getMessage());
            return null;
        }
    }

    /** 开关为「开」或暂时读不到时都算 true：读不到时宁可尝试补开，也不要误判成用户关掉了。 */
    private static boolean isEnabledOrUnknown() {
        Boolean enabled = readEnabled(sKeepCtx);
        return enabled == null || enabled;
    }

    private static boolean isUserUnlocked(Context ctx) {
        try {
            Object um = ctx.getSystemService(Context.USER_SERVICE);
            Object unlocked = XposedHelpers.callMethod(um, "isUserUnlocked");
            return unlocked instanceof Boolean && (Boolean) unlocked;
        } catch (Throwable t) {
            // 取不到就按已解锁处理，成败交给写入本身
            return true;
        }
    }

    /** 无线调试必须是“连着 Wi-Fi 且有有效网络”才允许开，否则系统会立刻把开关写回 0。 */
    private static boolean hasWifiConnection(Context ctx) {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm == null) return false;
            WifiInfo info = wm.getConnectionInfo();
            if (info == null) return false;
            if (info.getNetworkId() == -1) return false;
            String bssid = info.getBSSID();
            return bssid != null && !bssid.isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private static Handler resolveHandler(Object ams) {
        String[] fields = {"mHandler", "mH"};
        for (String f : fields) {
            try {
                Object o = XposedHelpers.getObjectField(ams, f);
                if (o instanceof Handler) return (Handler) o;
            } catch (Throwable ignored) {
            }
        }
        return new Handler(Looper.getMainLooper());
    }
}
