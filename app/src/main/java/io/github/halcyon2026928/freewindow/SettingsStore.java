package io.github.halcyon2026928.freewindow;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * system_server 里的设置落盘。
 *
 * <p>开机那一刻，模块 app 还没起来：XSharedPreferences 读不到（world-readable 已被限制），
 * ConfigProvider 也查不了，所以每次重启开关都会掉回默认值。参考 Free-main 的做法，
 * 让系统进程自己维护一份设置文件——进程一起来先读文件，app 改设置再通过广播推过来。
 * 这样开关与 app 在不在、能不能读它的 prefs 都无关。</p>
 */
final class SettingsStore {

    private static final String TAG = "[灵动坞OS-config]";
    /** 系统数据目录，system_server 可写；app 私有目录在系统进程里拿不到。 */
    private static final String STORE_DIR = "/data/system";
    private static final String STORE_NAME = "freewindow_settings.txt";

    static final String ACTION_PUSH = "io.github.halcyon2026928.freewindow.SETTINGS_PUSHED";

    private static volatile MainHook sHook;
    private static volatile Context sContext;
    private static volatile boolean sInstalled;
    /** 上一次落盘的内容，用来避免每次查询都重写同一个文件。 */
    private static volatile Map<String, String> sPersisted;

    private SettingsStore() {
    }

    /** 装钩子时调一次：先读文件把开关恢复成用户上次的选择，再注册接收 app 的推送。 */
    static void install(MainHook hook, ClassLoader cl) {
        if (sInstalled) return;
        sInstalled = true;
        sHook = hook;
        sContext = systemContext(cl);
        restore();
        registerReceiver();
    }

    /** 开机恢复：系统进程比模块 app 早，必须用上次落盘的值先跑起来。 */
    private static void restore() {
        Map<String, String> map = readFile();
        if (map.isEmpty()) {
            log("no stored config, wait for app push");
            return;
        }
        sPersisted = map;
        apply(map);
        log("restored " + map.size() + " keys from " + storeFile().getAbsolutePath());
    }

    private static void apply(Map<String, String> map) {
        MainHook hook = sHook;
        if (hook == null) return;
        try {
            hook.applyConfigFromStore(map);
        } catch (Throwable t) {
            log("apply failed: " + t.getMessage());
        }
    }

    /**
     * 把 app 推过来的整份配置落盘并当场生效。
     * 内容没变化就不重复写，避免开小窗时的查询把文件一直刷。
     */
    static void onPushFromApp(Bundle extras) {
        if (extras == null) return;
        Map<String, String> map = new LinkedHashMap<>();
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            if (value != null) map.put(key, String.valueOf(value));
        }
        if (map.isEmpty()) return;
        boolean same = map.equals(sPersisted);
        if (!same) {
            persist(map);
            sPersisted = map;
        }
        apply(map);
        log("pushed " + map.size() + " keys, written=" + (!same));
    }

    /** 当前值就地落盘：provider / xprefs 读到有效值时用它做首次自举。 */
    static void saveCurrent() {
        MainHook hook = sHook;
        if (hook == null) return;
        try {
            Map<String, String> map = hook.configToMap();
            if (map == null || map.isEmpty()) return;
            if (map.equals(sPersisted)) return;
            persist(map);
            sPersisted = map;
            log("saved current " + map.size() + " keys");
        } catch (Throwable t) {
            log("saveCurrent failed: " + t.getMessage());
        }
    }

    private static void persist(Map<String, String> map) {
        File target = storeFile();
        File temp = new File(target.getParentFile(), STORE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : map.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Throwable t) {
            log("persist failed: " + t.getMessage());
            return;
        }
        // 原子替换：写到一半被读到会解析出半个配置
        if (!temp.renameTo(target)) {
            try (FileInputStream in = new FileInputStream(temp);
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Throwable t) {
                log("persist copy failed: " + t.getMessage());
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private static Map<String, String> readFile() {
        Map<String, String> map = new LinkedHashMap<>();
        File file = storeFile();
        if (!file.exists()) return map;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                map.put(line.substring(0, eq), line.substring(eq + 1));
            }
        } catch (Throwable t) {
            log("read failed: " + t.getMessage());
        }
        return map;
    }

    private static File storeFile() {
        File dir = new File(STORE_DIR);
        if (!dir.canWrite()) {
            Context ctx = sContext;
            if (ctx != null) {
                File dir2 = ctx.getFilesDir();
                if (dir2 != null) return new File(dir2, STORE_NAME);
            }
        }
        return new File(dir, STORE_NAME);
    }

    private static void registerReceiver() {
        Context ctx = sContext;
        if (ctx == null) {
            log("system context unavailable, push channel disabled");
            return;
        }
        try {
            IntentFilter filter = new IntentFilter(ACTION_PUSH);
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null || !ACTION_PUSH.equals(intent.getAction())) return;
                    int uid;
                    try {
                        // API 30+ 才有发送方 uid；拿不到就当不可信
                        uid = getSentFromUid();
                    } catch (Throwable t) {
                        uid = -1;
                    }
                    if (!trusted(uid)) {
                        log("rejected foreign push from uid=" + uid);
                        return;
                    }
                    onPushFromApp(intent.getExtras());
                }
            }, filter, Context.RECEIVER_EXPORTED);
            log("push channel ready");
        } catch (Throwable t) {
            log("register receiver failed: " + t.getMessage());
        }
    }

    /** 只认模块 app 自己或系统进程：这份配置决定模块行为，不能由第三方写。 */
    private static boolean trusted(int uid) {
        if (uid == Process.SYSTEM_UID) return true;
        if (uid < 0) return false;
        Context ctx = sContext;
        if (ctx == null) return false;
        try {
            return ctx.getPackageManager()
                    .getApplicationInfo(ModulePrefs.PKG, 0).uid == uid;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Context systemContext(ClassLoader cl) {
        try {
            Object at = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", cl),
                    "currentActivityThread");
            Object ctx = at == null ? null : XposedHelpers.callMethod(at, "getSystemContext");
            if (ctx instanceof Context) return (Context) ctx;
        } catch (Throwable t) {
            log("systemContext failed: " + t.getMessage());
        }
        return null;
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + " " + msg);
    }
}
