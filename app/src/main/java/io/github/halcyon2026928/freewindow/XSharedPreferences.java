package io.github.halcyon2026928.freewindow;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * legacy {@code XSharedPreferences} 的等价实现：新 API 里模块配置改由 remote preferences 提供，
 * 这里直接按 legacy 的路径读模块 app 的 shared_prefs XML。
 * 读不到（权限/未生成）时返回调用方给的默认值，运行期真正的开关同步走 ConfigProvider。
 */
public final class XSharedPreferences {

    private final String packageName;
    private final String prefFileName;
    private final Map<String, Object> values = new HashMap<>();

    /** 上一次打印读取失败日志的时间：失败很频繁时只打一次，避免刷爆 logcat 把关键日志顶掉。 */
    private static long sLastFailLogAt = 0L;

    public XSharedPreferences(String packageName, String prefFileName) {
        this.packageName = packageName;
        this.prefFileName = prefFileName;
        reload();
    }

    public void makeWorldReadable() {
        // 现代 API 下无需改权限，保留空实现以兼容调用方。
    }

    public void reload() {
        values.clear();
        // 三个候选路径多数指向同一个文件，但也可能残留旧文件/旧目录：
        // 取修改时间最新的那份，避免读到过期配置把开关覆盖回默认。
        File newest = null;
        for (File f : candidateFiles()) {
            if (!f.exists() || !f.canRead()) continue;
            if (newest == null || f.lastModified() >= newest.lastModified()) {
                newest = f;
            }
        }
        if (newest != null) {
            parse(newest);
        }
    }

    /** 是否真的解析到了至少一个键。false 表示文件不存在/不可读/为空，此时不要拿默认值覆盖调用方的缓存。 */
    public boolean hasData() {
        return !values.isEmpty();
    }

    public File getFile() {
        File newest = null;
        for (File f : candidateFiles()) {
            if (!f.exists()) continue;
            if (newest == null || f.lastModified() >= newest.lastModified()) {
                newest = f;
            }
        }
        return newest != null ? newest
                : new File("/data/user_de/0/" + packageName + "/shared_prefs/" + prefFileName + ".xml");
    }

    public boolean getBoolean(String key, boolean defValue) {
        Object v = values.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            return Boolean.parseBoolean((String) v);
        }
        return defValue;
    }

    public String getString(String key, String defValue) {
        Object v = values.get(key);
        if (v instanceof String) return (String) v;
        return v != null ? String.valueOf(v) : defValue;
    }

    public int getInt(String key, int defValue) {
        Object v = values.get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt((String) v);
            } catch (Throwable ignored) {
            }
        }
        return defValue;
    }

    private File[] candidateFiles() {
        String[] roots = new String[]{
                "/data/data/" + packageName + "/shared_prefs/",
                "/data/user/0/" + packageName + "/shared_prefs/",
                "/data/user_de/0/" + packageName + "/shared_prefs/"
        };
        File[] out = new File[roots.length];
        for (int i = 0; i < roots.length; i++) {
            out[i] = new File(roots[i] + prefFileName + ".xml");
        }
        return out;
    }

    /**
     * 解析 Android 的 SharedPreferences XML。
     *
     * <p>Android 写出来的 boolean / int / long / float 值放在属性里
     * （{@code <boolean name="k" value="true" />}），只有 string 才是标签文本。
     * 之前只处理文本节点，等于所有布尔开关都解析不出来、每次回落到调用方默认值——
     * 表现就是「设置改完、退出模块 app 后台后开关恢复默认」。
     */
    private boolean parse(File file) {
        FileInputStream in = null;
        try {
            if (!file.canRead()) {
                return false;
            }
            in = new FileInputStream(file);
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, "utf-8");
            String pendingName = null;
            int type = parser.getEventType();
            while (type != XmlPullParser.END_DOCUMENT) {
                if (type == XmlPullParser.START_TAG) {
                    String tag = parser.getName();
                    String name = parser.getAttributeValue(null, "name");
                    String attr = parser.getAttributeValue(null, "value");
                    if (name != null && attr != null) {
                        values.put(name, typedValue(tag, attr));
                        pendingName = null;
                    } else {
                        pendingName = name;
                    }
                } else if (type == XmlPullParser.TEXT) {
                    if (pendingName != null) {
                        String text = parser.getText();
                        if (text != null) {
                            values.put(pendingName, text.trim());
                        }
                        pendingName = null;
                    }
                } else if (type == XmlPullParser.END_TAG) {
                    pendingName = null;
                }
                type = parser.next();
            }
            return !values.isEmpty();
        } catch (Throwable t) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - sLastFailLogAt > 30000L) {
                sLastFailLogAt = now;
                XposedBridge.log("XSharedPreferences read fail: " + file + " -> " + t);
            }
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Object typedValue(String tag, String raw) {
        try {
            if ("boolean".equals(tag)) return Boolean.parseBoolean(raw);
            if ("int".equals(tag)) return Integer.parseInt(raw);
            if ("long".equals(tag)) return Long.parseLong(raw);
            if ("float".equals(tag)) return Float.parseFloat(raw);
        } catch (Throwable ignored) {
        }
        return raw;
    }
}
