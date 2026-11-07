package io.github.artriai.misightviewer;

import org.json.JSONArray;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private static final Map<String, String> PROVINCES = new HashMap<>();
    private static final Map<String, String> CITIES = new HashMap<>();
    private static final Map<String, String> SCENES = new HashMap<>();
    private static final Map<String, String> OPERATORS = new HashMap<>();

    static {
        String[][] provList = {
            {"Guangdong", "广东省"}, {"Beijing", "北京市"}, {"Shanghai", "上海市"}, {"Tianjin", "天津市"},
            {"Chongqing", "重庆市"}, {"Zhejiang", "浙江省"}, {"Jiangsu", "江苏省"}, {"Shandong", "山东省"},
            {"Sichuan", "四川省"}, {"Hubei", "湖北省"}, {"Hunan", "湖南省"}, {"Henan", "河南省"},
            {"Hebei", "河北省"}, {"Fujian", "福建省"}, {"Anhui", "安徽省"}, {"Shaanxi", "陕西省"},
            {"Liaoning", "辽宁省"}, {"Jilin", "吉林省"}, {"Heilongjiang", "黑龙江省"}, {"Jiangxi", "江西省"},
            {"Guangxi", "广西壮族自治区"}, {"Guizhou", "贵州省"}, {"Yunnan", "云南省"}, {"Shanxi", "山西省"},
            {"InnerMongolia", "内蒙古自治区"}, {"NeiMongol", "内蒙古自治区"}, {"Gansu", "甘肃省"},
            {"Hainan", "海南省"}, {"Ningxia", "宁夏回族自治区"}, {"Qinghai", "青海省"}, {"Tibet", "西藏自治区"},
            {"Xizang", "西藏自治区"}, {"Xinjiang", "新疆维吾尔自治区"}, {"HongKong", "香港特别行政区"},
            {"Macau", "澳门特别行政区"}, {"Taiwan", "台湾省"}
        };
        for (String[] p : provList) PROVINCES.put(p[0].toLowerCase(Locale.ROOT), p[1]);

        String[][] cityList = {
            {"Guangzhou", "广州市"}, {"Shenzhen", "深圳市"}, {"Dongguan", "东莞市"}, {"Foshan", "佛山市"},
            {"Zhuhai", "珠海市"}, {"Zhongshan", "中山市"}, {"Huizhou", "惠州市"}, {"Shantou", "汕头市"},
            {"Jiangmen", "江门市"}, {"Zhanjiang", "湛江市"}, {"Zhaoqing", "肇庆市"}, {"Qingyuan", "清远市"},
            {"Chaozhou", "潮州市"}, {"Jieyang", "揭阳市"}, {"Meizhou", "梅州市"}, {"Shaoguan", "韶关市"},
            {"Heyuan", "河源市"}, {"Shanwei", "汕尾市"}, {"Maoming", "茂名市"}, {"Yangjiang", "阳江市"},
            {"Yunfu", "云浮市"},
            {"Hangzhou", "杭州市"}, {"Ningbo", "宁波市"}, {"Wenzhou", "温州市"}, {"Jiaxing", "嘉兴市"},
            {"Huzhou", "湖州市"}, {"Shaoxing", "绍兴市"}, {"Jinhua", "金华市"}, {"Quzhou", "衢州市"},
            {"Zhoushan", "舟山市"}, {"Taizhou", "台州市"}, {"Lishui", "丽水市"},
            {"Nanjing", "南京市"}, {"Suzhou", "苏州市"}, {"Wuxi", "无锡市"}, {"Changzhou", "常州市"},
            {"Nantong", "南通市"}, {"Xuzhou", "徐州市"}, {"Yancheng", "盐城市"}, {"Yangzhou", "扬州市"},
            {"Zhenjiang", "镇江市"}, {"Huaian", "淮安市"}, {"Huai'an", "淮安市"}, {"Lianyungang", "连云港市"},
            {"Suqian", "宿迁市"},
            {"Chengdu", "成都市"}, {"Mianyang", "绵阳市"}, {"Wuhan", "武汉市"}, {"Changsha", "长沙市"},
            {"Jinan", "济南市"}, {"Qingdao", "青岛市"}, {"Zhengzhou", "郑州市"}, {"Shijiazhuang", "石家庄市"},
            {"Fuzhou", "福州市"}, {"Xiamen", "厦门市"}, {"Quanzhou", "泉州市"}, {"Hefei", "合肥市"},
            {"Xian", "西安市"}, {"Xi'an", "西安市"}, {"Shenyang", "沈阳市"}, {"Dalian", "大连市"},
            {"Harbin", "哈尔滨市"}, {"Changchun", "长春市"}, {"Nanchang", "南昌市"}, {"Nanning", "南宁市"},
            {"Guiyang", "贵阳市"}, {"Kunming", "昆明市"}, {"Taiyuan", "太原市"}, {"Hohhot", "呼和浩特市"},
            {"Lanzhou", "兰州市"}, {"Haikou", "海口市"}, {"Sanya", "三亚市"}, {"Yinchuan", "银川市"},
            {"Xining", "西宁市"}, {"Lhasa", "拉萨市"}, {"Urumqi", "乌鲁木齐市"}
        };
        for (String[] c : cityList) CITIES.put(c[0].toLowerCase(Locale.ROOT), c[1]);

        SCENES.put("elevatormode", "电梯模式");
        SCENES.put("highspeedtrain", "高铁模式");
        SCENES.put("invehicle", "车载模式");
        SCENES.put("subway", "地铁模式");
        SCENES.put("walking", "步行模式");
        SCENES.put("stationary", "静止场景");
        SCENES.put("sa_night_mode", "5G夜间优化");
        SCENES.put("network_recovery_strategy", "网络恢复策略");

        OPERATORS.put("46000", "中国移动");
        OPERATORS.put("46002", "中国移动");
        OPERATORS.put("46007", "中国移动");
        OPERATORS.put("46008", "中国移动");
        OPERATORS.put("46001", "中国联通");
        OPERATORS.put("46006", "中国联通");
        OPERATORS.put("46009", "中国联通");
        OPERATORS.put("46003", "中国电信");
        OPERATORS.put("46005", "中国电信");
        OPERATORS.put("46011", "中国电信");
        OPERATORS.put("46015", "中国广电");
        OPERATORS.put("46020", "中国广电");
    }

    public static String translateProvince(String p) {
        if (p == null || p.trim().isEmpty()) return "";
        String hit = PROVINCES.get(p.trim().toLowerCase(Locale.ROOT));
        return hit == null ? p : hit;
    }

    public static String translateCity(String c) {
        if (c == null || c.trim().isEmpty()) return "";
        String hit = CITIES.get(c.trim().toLowerCase(Locale.ROOT));
        if (hit != null) return hit;
        return c.endsWith("市") ? c : c + "市";
    }

    public static String translateScene(String s) {
        if (s == null || s.trim().isEmpty()) return "";
        String hit = SCENES.get(s.trim().toLowerCase(Locale.ROOT));
        return hit == null ? s : hit;
    }

    public static String translateOperator(String op) {
        if (op == null || op.trim().isEmpty()) return "";
        String hit = OPERATORS.get(op.trim());
        return hit == null ? op : hit + " (" + op + ")";
    }

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

    /** First few readable "label：value" facts for list rows, prioritizing key privacy and diagnostic items. */
    public String facts(Card card, int max) {
        StringBuilder sb = new StringBuilder(); int n = 0;
        String[] priorityKeys = {"c", "p", "call_duration", "CallDirect", "call_cell_id", "ueSpecialScene", "fivegOptimizeScene", "path", "DeviceName", "DeviceMac", "unlockType"};
        for (String pk : priorityKeys) {
            if (n >= max) break;
            for (Field f : card.fields) {
                if (f.key.equalsIgnoreCase(pk)) {
                    if (n++ > 0) sb.append("  ·  ");
                    sb.append(f.label).append("：").append(f.value);
                    break;
                }
            }
        }
        for (int pass = 0; pass < 2 && n < max; pass++) {
            for (Field f : card.fields) {
                if (n >= max) break;
                if (pass == 0 && !f.labelled) continue;
                if (f.value.length() > 28 || f.key.isEmpty() && pass == 0) continue;
                String kind = catalog.fieldKind(f.key);
                if (kind.equals("pkg") || kind.equals("time") || f.key.equalsIgnoreCase("change")) continue;
                boolean alreadyAdded = false;
                for (String pk : priorityKeys) {
                    if (f.key.equalsIgnoreCase(pk)) { alreadyAdded = true; break; }
                }
                if (alreadyAdded) continue;
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
        } else if (id == 915100017L) {
            String p = o.optString("p", ""), c = o.optString("c", "");
            String pZh = translateProvince(p), cZh = translateCity(c);
            StringBuilder sb = new StringBuilder("📍 ");
            if (!cZh.isEmpty() && !pZh.isEmpty() && !pZh.equals(cZh)) {
                sb.append(pZh).append(" ").append(cZh);
            } else if (!cZh.isEmpty()) {
                sb.append(cZh);
            } else if (!pZh.isEmpty()) {
                sb.append(pZh);
            } else {
                sb.append("位置场景");
            }
            String scene = o.optString("ueSpecialScene", "");
            if (!scene.isEmpty()) {
                sb.append(" · ").append(translateScene(scene));
            } else {
                String opt = o.optString("fivegOptimizeScene", "");
                if (!opt.isEmpty()) sb.append(" · ").append(translateScene(opt));
                else {
                    String fg = o.optString("foregroundActivity", "");
                    if (!fg.isEmpty()) {
                        int slash = fg.indexOf('/');
                        String pkg = slash > 0 ? fg.substring(0, slash) : fg;
                        sb.append(" · 前台 ").append(apps.display(pkg));
                    }
                }
            }
            return sb.toString();
        } else if (id == 915200012L) {
            long dur = o.optLong("call_duration", -1);
            String begin = o.optString("call_begin_time", "");
            StringBuilder sb = new StringBuilder("📞 通话记录");
            if (dur >= 0) sb.append(" · 时长 ").append(dur).append(" 秒");
            if (!begin.isEmpty()) {
                int sp = begin.indexOf(' ');
                sb.append(" (").append(sp > 0 ? begin.substring(sp + 1) : begin).append(")");
            }
            return sb.toString();
        } else if (id == 915200011L) {
            String dir = o.optString("CallDirect", "");
            String type = o.optString("CallType", "");
            String title = "MT".equalsIgnoreCase(dir) ? "📥 来电通话（呼入）" : ("MO".equalsIgnoreCase(dir) ? "📤 去电通话（呼出）" : "📞 通话建立");
            return type.isEmpty() ? title : title + " · " + type;
        } else if (id == 915200013L || id == 915200010L) {
            String dir = o.optString("CallDirect", "");
            String msg = o.optString("DiconnectMessage", "");
            String title = "MT".equalsIgnoreCase(dir) ? "📵 呼入通话挂断" : ("MO".equalsIgnoreCase(dir) ? "📵 呼出通话挂断" : "📵 通话结束挂断");
            return msg.isEmpty() ? title : title + " · " + msg;
        } else if (id == 915200015L) {
            String cell = o.optString("call_cell_id", "");
            return cell.isEmpty() ? "🗼 通话基站记录" : "🗼 通话基站 · Cell ID " + cell;
        } else if (id == 923525000L) {
            int op = o.optInt("operationType", 0);
            String prefix = op == 40 ? "🗑️ 相册删除/回收站：" : (op == 30 ? "🖼️ 相册访问照片：" : "🖼️ 相册媒体操作：");
            JSONArray paths = o.optJSONArray("path");
            String p = paths != null && paths.length() > 0 ? paths.optString(0) : o.optString("path", "");
            if (!p.isEmpty()) {
                int slash = p.lastIndexOf('/');
                return prefix + (slash >= 0 ? p.substring(slash + 1) : p);
            }
            return op == 40 ? "🗑️ 相册删除照片" : "🖼️ 相册访问照片";
        } else if (id == 917020105L || id == 917020701L) {
            String dev = o.optString("DeviceName", "");
            String mac = o.optString("DeviceMac", "");
            if (!dev.isEmpty()) return "🎧 蓝牙设备 · " + dev;
            if (!mac.isEmpty()) return "🎧 蓝牙设备 · " + mac;
            return "🎧 蓝牙外设连接";
        } else if (id == 919032001L) {
            String pkg = o.optString("PackageName", "");
            return "🗺️ 定位权限申请 · " + (pkg.isEmpty() ? "未知应用" : apps.display(pkg));
        } else if (id == 919012101L) {
            int sv = o.optInt("SvCount", -1);
            return sv >= 0 ? "🛰️ 卫星搜星定位 · " + sv + " 颗卫星" : "🛰️ 卫星搜星定位";
        } else if (id == 919011101L) {
            return "🛰️ 网络定位请求与解析";
        } else if (id == 923044101L) {
            String target = o.optString("unlockTarget", "");
            String type = o.optString("unlockType", "");
            long dur = o.optLong("durMs", -1);
            StringBuilder sb = new StringBuilder("🔓 屏幕解锁");
            if (!type.isEmpty()) sb.append(" · ").append(type);
            else if (!target.isEmpty()) sb.append(" · ").append(target);
            if (dur > 0) sb.append(" (").append(dur).append("ms)");
            return sb.toString();
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

        if ("c".equalsIgnoreCase(key)) {
            String zh = translateCity(text);
            return zh.equals(text) ? text : zh + "（" + text + "）";
        }
        if ("p".equalsIgnoreCase(key)) {
            String zh = translateProvince(text);
            return zh.equals(text) ? text : zh + "（" + text + "）";
        }
        if ("ueSpecialScene".equalsIgnoreCase(key) || "fivegOptimizeScene".equalsIgnoreCase(key)) {
            String zh = translateScene(text);
            return zh.equals(text) ? text : zh + "（" + text + "）";
        }
        if ("hplmn".equalsIgnoreCase(key) || "vplmn".equalsIgnoreCase(key) || ("Network".equalsIgnoreCase(key) && text.startsWith("460"))) {
            return translateOperator(text);
        }
        if ("CallDirect".equalsIgnoreCase(key)) {
            if ("MT".equalsIgnoreCase(text)) return "呼入（被叫来电）";
            if ("MO".equalsIgnoreCase(text)) return "呼出（主叫去电）";
        }
        if ("operationType".equalsIgnoreCase(key)) {
            if ("30".equals(text)) return "查看 / 读取（30）";
            if ("40".equals(text)) return "移入回收站 / 删除（40）";
        }

        Long number = v instanceof Number ? Long.valueOf(((Number) v).longValue()) : parseLong(text);
        if ("call_duration".equalsIgnoreCase(key) && number != null) {
            return EventCatalog.duration(number * 1000L);
        }

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
