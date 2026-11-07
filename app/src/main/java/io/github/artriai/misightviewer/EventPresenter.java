package io.github.artriai.misightviewer;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one raw event row into something an ordinary reader can scan: an app name, a one-line
 * headline, and Chinese-labelled fields with readable values. The raw JSON is never altered here;
 * this is presentation only.
 */
public final class EventPresenter {
    public static final class Field {
        public final String key, label, value;
        public final boolean labelled;
        Field(String key, String label, String value, boolean labelled) {
            this.key = key; this.label = label; this.value = value; this.labelled = labelled;
        }
    }
    public static final class Card {
        /** Recognisable app name (or package name); empty when the record names no app. */
        public String app = "";
        public String packageName = "";
        public String headline = "";
        public String category = "";
        public boolean json;
        public final List<Field> fields = new ArrayList<>();
    }

    private static final Pattern CHANGE = Pattern.compile("(TO_FRONT|TO_BACK|OPEN)-([A-Za-z][A-Za-z0-9_.]*)");
    private static final String[] PACKAGE_KEYS = {"PackageName", "packageName", "pkgName", "pkg_name", "foregroundPkg",
            "callerPkgName", "callerPkg", "sourcePackageName", "LaunchFromPackage", "AppName"};
    private static final String[] SENSITIVE_PARTS = {"phone", "imei", "imsi", "iccid", "ssid", "account", "token",
            "password", "address", "latitude", "longitude", "cellid", "cell_id", "path", "uri", "url", "devicename"};
    private static final String[] SENSITIVE_EXACT = {"mac", "ip", "lat", "lng", "lon", "number"};
    private static final int MAX_VALUE = 200;

    private final EventCatalog catalog;
    private final AppNames apps;

    public EventPresenter(EventCatalog catalog, AppNames apps) { this.catalog = catalog; this.apps = apps; }

    public Card present(MisightDatabase.Event event, boolean mask) {
        Card card = new Card();
        card.category = catalog.title(event.eventId);
        JSONObject o = null;
        try { o = new JSONObject(event.raw); card.json = true; } catch (Exception ignored) { }
        if (o == null) {
            if (event.raw != null && !event.raw.isEmpty()) card.fields.add(new Field("", "内容", clip(event.raw), true));
            card.headline = card.category;
            return card;
        }
        flatten(o, "", card.fields, mask);
        card.packageName = primaryPackage(o);
        if (card.packageName.isEmpty()) {
            String name = o.optString("AppName", "");
            if (!name.isEmpty() && !name.equals("null")) card.app = name;
        } else card.app = apps.display(card.packageName);
        card.headline = headline(event.eventId, o, card);
        return card;
    }

    /** First few readable "label：value" facts for list rows. */
    public String facts(Card card, int max) {
        StringBuilder sb = new StringBuilder(); int n = 0;
        for (int pass = 0; pass < 2 && n == 0; pass++) {
            for (Field f : card.fields) {
                if (n >= max) break;
                if (pass == 0 && !f.labelled) continue;
                if (f.value.length() > 28 || f.key.isEmpty() && pass == 0) continue;
                String kind = catalog.fieldKind(f.key);
                if (kind.equals("pkg") || kind.equals("time") || f.key.equalsIgnoreCase("change")) continue;
                if (n++ > 0) sb.append("  ·  ");
                sb.append(f.label).append("：").append(f.value);
            }
        }
        return sb.toString();
    }

    private String headline(long id, JSONObject o, Card card) {
        if (id == 907400027L) {
            List<String> front = new ArrayList<>(), back = new ArrayList<>();
            Matcher m = CHANGE.matcher(o.optString("change", ""));
            while (m.find()) {
                String name = apps.display(m.group(2));
                if (m.group(1).equals("TO_BACK")) back.add(name); else if (m.group(1).equals("TO_FRONT")) front.add(name);
            }
            StringBuilder sb = new StringBuilder();
            if (!back.isEmpty()) sb.append(join(back)).append(" 退到后台");
            if (!front.isEmpty()) sb.append(sb.length() > 0 ? "，" : "").append(join(front)).append(" 进入前台");
            if (sb.length() > 0) return sb.toString();
        } else if (id == 907404002L) {
            String caller = o.optString("callerPkgName", ""), callee = o.optString("calleePkgName", "");
            if (!caller.isEmpty() && !callee.isEmpty()) return apps.display(caller) + " 唤醒 " + apps.display(callee);
        }
        return card.app.isEmpty() ? card.category : card.app;
    }
    private static String join(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size() && i < 3; i++) sb.append(i > 0 ? "、" : "").append(items.get(i));
        if (items.size() > 3) sb.append(" 等 ").append(items.size()).append(" 个应用");
        return sb.toString();
    }

    private String primaryPackage(JSONObject o) {
        for (String key : PACKAGE_KEYS) {
            String v = o.optString(key, "");
            if (AppNames.looksLikePackage(v)) return v;
        }
        Matcher m = CHANGE.matcher(o.optString("change", ""));
        return m.find() ? m.group(2) : "";
    }

    private void flatten(JSONObject o, String prefix, List<Field> out, boolean mask) {
        JSONArray names = o.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String name = names.optString(i); String key = prefix + name;
            Object v = o.opt(name);
            if (v == null || v == JSONObject.NULL) continue;
            if (v instanceof JSONObject) { flatten((JSONObject) v, key + ".", out, mask); continue; }
            String text;
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                if (a.length() == 0) continue;
                text = array(key, a, mask);
            } else {
                text = scalar(key, v, mask);
                if (text.isEmpty()) continue;
            }
            String label = catalog.fieldLabel(key);
            out.add(new Field(key, label == null ? key : label, text, label != null));
        }
    }

    private String array(String key, JSONArray a, boolean mask) {
        boolean primitive = true;
        for (int i = 0; i < a.length(); i++) {
            Object x = a.opt(i);
            if (x instanceof JSONObject || x instanceof JSONArray) { primitive = false; break; }
        }
        if (!primitive) return clip(mask && sensitive(key) ? maskValue(a.toString()) : a.toString());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length() && i < 6; i++) {
            if (i > 0) sb.append("、");
            sb.append(scalar(key, a.opt(i), mask));
        }
        if (a.length() > 6) sb.append(" … 共 ").append(a.length()).append(" 项");
        return clip(sb.toString());
    }

    private String scalar(String key, Object v, boolean mask) {
        if (v == null || v == JSONObject.NULL) return "";
        String kind = catalog.fieldKind(key);
        if (mask && sensitive(key)) return maskValue(plain(v));
        if (v instanceof Boolean) return ((Boolean) v) ? "是" : "否";
        String text = plain(v);
        if (text.isEmpty()) return "";
        Long number = v instanceof Number ? Long.valueOf(((Number) v).longValue()) : parseLong(text);
        switch (kind) {
            case "bool":
                if (text.equals("1") || text.equalsIgnoreCase("true")) return "是";
                if (text.equals("0") || text.equalsIgnoreCase("false")) return "否";
                break;
            case "ms":
                if (number != null) return EventCatalog.duration(number);
                break;
            case "time":
                if (number != null && number > 100_000_000_000L) return EventCatalog.time(number, true);
                if (number != null && number > 100_000_000L) return EventCatalog.time(number, false);
                break;
            case "pkg":
                if (AppNames.looksLikePackage(text)) {
                    String label = apps.label(text);
                    return label.isEmpty() ? text : label + "（" + text + "）";
                }
                break;
            default:
        }
        return clip(text);
    }
    private static Long parseLong(String s) {
        if (s.length() > 18 || !s.matches("-?\\d+")) return null;
        try { return Long.valueOf(s); } catch (NumberFormatException e) { return null; }
    }
    private static String plain(Object v) {
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
            return BigDecimal.valueOf(d).toPlainString();
        }
        return String.valueOf(v);
    }
    private static String clip(String s) {
        return s.length() > MAX_VALUE ? s.substring(0, MAX_VALUE) + "…（共 " + s.length() + " 字符）" : s;
    }

    public static boolean sensitive(String key) {
        if (key == null) return false;
        String k = key.toLowerCase(Locale.ROOT).replace("[]", "");
        int dot = k.lastIndexOf('.');
        if (dot >= 0) k = k.substring(dot + 1);
        if (k.endsWith("mac")) return true;
        for (String s : SENSITIVE_PARTS) if (k.contains(s)) return true;
        for (String s : SENSITIVE_EXACT) if (k.equals(s)) return true;
        return false;
    }
    public static String maskValue(String s) {
        if (s == null) return "";
        return s.length() <= 4 ? "••••" : s.substring(0, 2) + "••••" + s.substring(s.length() - 2);
    }

    /** Pretty-printed raw JSON, with sensitive values hidden when {@code mask} is on. */
    public static String rawText(String raw, boolean mask) {
        if (!mask) return EventCatalog.pretty(raw);
        try {
            JSONObject o = new JSONObject(raw);
            maskJson(o);
            return o.toString(2);
        } catch (Exception e) { return raw == null ? "" : raw; }
    }
    private static void maskJson(JSONObject o) throws Exception {
        JSONArray names = o.names();
        if (names == null) return;
        for (int i = 0; i < names.length(); i++) {
            String key = names.getString(i); Object v = o.opt(key);
            if (v instanceof JSONObject) maskJson((JSONObject) v);
            else if (v instanceof JSONArray && !sensitive(key)) {
                JSONArray a = (JSONArray) v;
                for (int j = 0; j < a.length(); j++) if (a.opt(j) instanceof JSONObject) maskJson(a.getJSONObject(j));
            } else if (v != null && v != JSONObject.NULL && sensitive(key)) o.put(key, maskValue(plain(v)));
        }
    }
}
