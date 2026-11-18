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
        for (File f : candidateFiles()) {
            if (parse(f)) {
                return;
            }
        }
    }

    public File getFile() {
        File[] files = candidateFiles();
        return files.length > 0 ? files[0] : new File("/data/user_de/0/" + packageName + "/shared_prefs/" + prefFileName + ".xml");
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
        return v instanceof String ? (String) v : defValue;
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
                "/data/user_de/0/" + packageName + "/shared_prefs/",
                "/data/data/" + packageName + "/shared_prefs/",
                "/data/user/0/" + packageName + "/shared_prefs/"
        };
        File[] out = new File[roots.length];
        for (int i = 0; i < roots.length; i++) {
            out[i] = new File(roots[i] + prefFileName + ".xml");
        }
        return out;
    }

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
            for (int type = parser.getEventType(); type != XmlPullParser.END_DOCUMENT;
                 type = parser.next()) {
                if (type == XmlPullParser.START_TAG) {
                    pendingName = parser.getAttributeValue(null, "name");
                } else if (type == XmlPullParser.TEXT && pendingName != null) {
                    String text = parser.getText();
                    if (text != null) {
                        values.put(pendingName, text.trim());
                    }
                    pendingName = null;
                }
            }
            return !values.isEmpty();
        } catch (Throwable t) {
            XposedBridge.log("XSharedPreferences read fail: " + file + " -> " + t);
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
}
