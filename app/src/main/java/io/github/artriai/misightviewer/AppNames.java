package io.github.artriai.misightviewer;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns package names into names people recognise. Built-in names cover common system apps (also
 * for snapshots exported from another phone); installed apps on this phone are looked up lazily.
 */
public final class AppNames {
    private static final Map<String, String> BUILT_IN = new HashMap<>();
    static {
        String[] pairs = {
            "android", "Android 系统", "system", "Android 系统",
            "com.android.systemui", "系统界面", "com.android.settings", "设置",
            "com.miui.home", "系统桌面", "com.android.launcher3", "桌面",
            "com.miui.securitycenter", "手机管家", "com.miui.powerkeeper", "电量与性能",
            "com.android.phone", "电话服务", "com.android.incallui", "通话界面",
            "com.android.mms", "短信", "com.android.contacts", "通讯录",
            "com.android.bluetooth", "蓝牙", "com.android.providers.media.module", "媒体存储",
            "com.miui.gallery", "相册", "com.android.camera", "相机",
            "com.miui.player", "音乐", "com.miui.miwallpaper", "壁纸",
            "com.android.chrome", "Chrome", "com.mi.globalbrowser", "小米浏览器",
            "com.miui.analytics", "系统统计", "com.miui.systemAdSolution", "广告服务（MSA）",
            "com.xiaomi.xmsf", "小米推送服务", "com.xiaomi.market", "应用商店",
            "com.miui.cloudservice", "小米云服务", "com.miui.voiceassist", "小爱同学",
            "com.google.android.gms", "Google 服务", "com.android.vending", "Google Play 商店",
            "com.tencent.mm", "微信", "com.tencent.mobileqq", "QQ",
            "com.eg.android.AlipayGphone", "支付宝", "com.taobao.taobao", "淘宝",
            "com.ss.android.ugc.aweme", "抖音", "com.sina.weibo", "微博",
            "com.xingin.xhs", "小红书", "com.tencent.mtt", "QQ 浏览器",
        };
        for (int i = 0; i + 1 < pairs.length; i += 2) BUILT_IN.put(pairs[i], pairs[i + 1]);
    }

    private final PackageManager pm;
    private final Map<String, String> cache = new HashMap<>();
    private Map<String, String> installed;

    public AppNames(Context context) { pm = context.getPackageManager(); }

    /** True for strings shaped like a package name (a.b, no spaces). */
    public static boolean looksLikePackage(String value) {
        if (value == null || value.length() < 3 || value.length() > 150) return false;
        return value.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+") || BUILT_IN.containsKey(value);
    }

    /** Friendly app name, or "" when none is known. */
    public synchronized String label(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String hit = cache.get(pkg);
        if (hit != null) return hit;
        String name = BUILT_IN.get(pkg);
        if (name == null) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                CharSequence l = pm.getApplicationLabel(info);
                name = l == null ? "" : l.toString();
                if (name.equals(pkg)) name = "";
            } catch (Exception e) { name = ""; }
        }
        cache.put(pkg, name);
        return name;
    }

    /** "微信" when known, otherwise the raw package name. */
    public String display(String pkg) {
        String l = label(pkg);
        return l.isEmpty() ? pkg : l;
    }

    /** Packages (built-in plus installed) whose app name contains the query. Used for searching by app name. */
    public synchronized List<String> packagesMatching(String query) {
        List<String> found = new ArrayList<>();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return found;
        for (Map.Entry<String, String> e : BUILT_IN.entrySet())
            if (e.getValue().toLowerCase(Locale.ROOT).contains(q) && !found.contains(e.getKey())) found.add(e.getKey());
        if (installed == null) {
            installed = new HashMap<>();
            try {
                for (ApplicationInfo info : pm.getInstalledApplications(0)) {
                    CharSequence l = pm.getApplicationLabel(info);
                    if (l != null && !l.toString().equals(info.packageName)) installed.put(info.packageName, l.toString());
                }
            } catch (Exception ignored) { }
        }
        for (Map.Entry<String, String> e : installed.entrySet())
            if (found.size() < 40 && e.getValue().toLowerCase(Locale.ROOT).contains(q) && !found.contains(e.getKey())) found.add(e.getKey());
        return found;
    }
}
