package io.github.artriai.misightviewer;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public final class EventCatalog {
    public static final String UNKNOWN_TITLE = "未标注事件";
    private static final String GENERIC_NOTE = "这是根据字段和编号整理的辅助标签";
    private final JSONObject catalog;
    /** lower-cased field name -> {label, kind}; kind may be empty. */
    private final Map<String, String[]> fields = new HashMap<>();

    public EventCatalog(Context context) {
        catalog = load(context, "event_catalog.json");
        JSONObject labels = load(context, "field_labels.json");
        Iterator<String> keys = labels.keys();
        while (keys.hasNext()) {
            String key = keys.next(); Object value = labels.opt(key);
            if (value instanceof JSONArray) {
                JSONArray a = (JSONArray) value;
                fields.put(key.toLowerCase(Locale.ROOT), new String[]{a.optString(0), a.optString(1)});
            } else if (value instanceof String) fields.put(key.toLowerCase(Locale.ROOT), new String[]{(String) value, ""});
        }
    }
    private static JSONObject load(Context context, String asset) {
        try (InputStream in = context.getAssets().open(asset)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) { return new JSONObject(); }
    }

    public String title(long id) {
        JSONObject item = catalog.optJSONObject(Long.toString(id));
        return item == null ? UNKNOWN_TITLE : item.optString("title", UNKNOWN_TITLE);
    }
    public boolean known(long id) { return catalog.optJSONObject(Long.toString(id)) != null; }
    /** Raw catalog text; kept for callers that want the unedited description. */
    public String explanation(long id) {
        JSONObject item = catalog.optJSONObject(Long.toString(id));
        return item == null ? "尚无此事件的说明，请结合原始字段判断。" : item.optString("description");
    }
    /**
     * Reader-friendly explanation: drops the repeated disclaimer and the long list of possible
     * field names, keeps only event-specific notes. Empty when there is nothing specific to say.
     */
    public String plainExplanation(long id) {
        JSONObject item = catalog.optJSONObject(Long.toString(id));
        if (item == null) return "";
        String text = item.optString("description");
        int cut = text.indexOf("\n可能字段：");
        if (cut >= 0) text = text.substring(0, cut);
        text = text.trim();
        return text.startsWith(GENERIC_NOTE) ? "" : text;
    }
    /** Ids of catalogued events whose title contains the query (case-insensitive). */
    public List<Long> idsMatching(String query) {
        List<Long> ids = new ArrayList<>();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return ids;
        Iterator<String> keys = catalog.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject item = catalog.optJSONObject(key);
            if (item == null || !item.optString("title").toLowerCase(Locale.ROOT).contains(q)) continue;
            try { ids.add(Long.parseLong(key)); } catch (NumberFormatException ignored) { }
        }
        return ids;
    }

    /** Chinese label for a raw field name, or null when we have none. Nested keys use their last segment. */
    public String fieldLabel(String key) { String[] f = lookup(key); return f == null ? null : f[0]; }
    /** Value hint for a field: "pkg", "ms", "time", "bool" or "". */
    public String fieldKind(String key) { String[] f = lookup(key); return f == null ? "" : f[1]; }
    private String[] lookup(String key) {
        if (key == null) return null;
        String k = key.replace("[]", "").toLowerCase(Locale.ROOT);
        String[] hit = fields.get(k);
        if (hit == null) {
            int dot = k.lastIndexOf('.');
            if (dot >= 0) hit = fields.get(k.substring(dot + 1));
        }
        return hit;
    }

    private static SimpleDateFormat format(String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.CHINA);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai")); return f;
    }
    public static String time(long value, boolean milliseconds) {
        if (value <= 0) return "时间未知";
        try { return format("yyyy-MM-dd HH:mm:ss").format(new Date(milliseconds ? value : Math.multiplyExact(value, 1000))); }
        catch (Exception e) { return Long.toString(value); }
    }
    public static long toMillis(long value, boolean milliseconds) {
        try { return milliseconds ? value : Math.multiplyExact(value, 1000); } catch (ArithmeticException e) { return 0; }
    }
    /** Clock part only, for list rows that already sit under a day header. */
    public static String clock(long value, boolean milliseconds) {
        if (value <= 0) return "时间未知";
        return format("HH:mm:ss").format(new Date(toMillis(value, milliseconds)));
    }
    private static Calendar day(long millis) {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"), Locale.CHINA);
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
        return c;
    }
    /** Day key used to detect when a new day header is needed. */
    public static String dayKey(long value, boolean milliseconds) {
        if (value <= 0) return "未知时间";
        return format("yyyy-MM-dd").format(new Date(toMillis(value, milliseconds)));
    }
    /** "今天 · 11月7日 周六" style header. */
    public static String dayHeader(long value, boolean milliseconds, long nowMillis) {
        if (value <= 0) return "时间未知";
        long millis = toMillis(value, milliseconds);
        long days = Math.round((day(nowMillis).getTimeInMillis() - day(millis).getTimeInMillis()) / 86_400_000d);
        String date = format("M月d日 E").format(new Date(millis));
        if (days == 0) return "今天 · " + date;
        if (days == 1) return "昨天 · " + date;
        if (days == 2) return "前天 · " + date;
        return format("yyyy年").format(new Date(millis)).equals(format("yyyy年").format(new Date(nowMillis)))
                ? date : format("yyyy年M月d日 E").format(new Date(millis));
    }
    /** "刚刚 / 5 分钟前 / 3 小时前"; empty when older than a day or in the future. */
    public static String ago(long value, boolean milliseconds, long nowMillis) {
        long diff = nowMillis - toMillis(value, milliseconds);
        if (value <= 0 || diff < 0) return "";
        if (diff < 60_000) return "刚刚";
        if (diff < 3_600_000) return (diff / 60_000) + " 分钟前";
        if (diff < 86_400_000) return (diff / 3_600_000) + " 小时前";
        return "";
    }
    public static String duration(long ms) {
        if (ms < 0) return Long.toString(ms);
        if (ms < 1000) return ms + " 毫秒";
        if (ms < 60_000) return String.format(Locale.CHINA, "%.2f 秒", ms / 1000d);
        long s = ms / 1000;
        if (s < 3600) return (s / 60) + " 分 " + (s % 60) + " 秒";
        return (s / 3600) + " 小时 " + ((s % 3600) / 60) + " 分";
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
