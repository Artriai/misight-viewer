package io.github.artriai.misightviewer;

import android.content.Context;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class EventCatalog {
    private final JSONObject catalog;
    public EventCatalog(Context context) {
        JSONObject loaded;
        try (InputStream in = context.getAssets().open("event_catalog.json")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            loaded = new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) { loaded = new JSONObject(); }
        catalog = loaded;
    }
    public String title(long id) {
        JSONObject item = catalog.optJSONObject(Long.toString(id));
        return item == null ? "未标注事件" : item.optString("title", "未标注事件");
    }
    public String explanation(long id) {
        JSONObject item = catalog.optJSONObject(Long.toString(id));
        return item == null ? "尚无此事件的说明，请结合原始字段判断。" : item.optString("description");
    }
    public static String time(long value, boolean milliseconds) {
        if (value <= 0) return "时间未知";
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
            f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            return f.format(new Date(milliseconds ? value : Math.multiplyExact(value, 1000)));
        } catch (Exception e) { return Long.toString(value); }
    }
    public static String pretty(String raw) {
        try { return new JSONObject(raw).toString(2); }
        catch (Exception e) { return raw == null ? "" : raw; }
    }
    public static String packageHint(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            for (String key : new String[]{"PackageName", "packageName", "pkgName", "pkg_name", "AppName", "foregroundPkg", "callerPkgName"}) {
                String value = o.optString(key, "");
                if (!value.isEmpty() && !value.equals("null")) return value;
            }
            String change = o.optString("change", "");
            if (change.contains("TO_") || change.contains("OPEN-")) return change;
            String activity = o.optString("ActivityName", "");
            if (!activity.isEmpty()) return activity;
        } catch (Exception ignored) { }
        return "";
    }
    public static String preview(String raw) {
        if (raw == null) return "";
        String s = raw.replace('\n', ' ');
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }
}
