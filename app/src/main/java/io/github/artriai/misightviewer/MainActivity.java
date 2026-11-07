package io.github.artriai.misightviewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends Activity {
    private static final int IMPORT = 10, EXPORT = 11;
    private static final int CHIP_COUNT = 6;

    public static final Set<Long> PRIVACY_IDS = new HashSet<>(Arrays.asList(
        907400027L, // 应用前后台切换（精准监控使用哪些应用）
        915100017L, // 驻留省市与网络场景（省份、地级市、停留时长、电梯/高铁场景）
        915200006L, 915200010L, 915200011L, 915200012L, 915200013L, 915200015L, // 通话记录、呼入呼出、通话时长、挂断原因、通话基站Cell ID
        916101001L, // VPN网络代理状态
        923525000L, // 相册与媒体操作（照片路径、截屏、删除回收站）
        917020102L, 917020105L, 917020301L, 917020701L, 917020703L, // 蓝牙外设名称与物理MAC地址
        919011101L, 919011102L, 919012101L, 919022002L, 919032001L, // 定位诊断、网络定位数据源、卫星定位搜星
        923044101L, // 屏幕解锁方式与鉴权耗时
        920012019L, 920012061L, 920012082L, // 相机调用监控
        906007061L  // 麦克风录音与音频通道监控
    ));
    public static boolean isPrivacy(long id) {
        return PRIVACY_IDS.contains(id) || (id >= 915200000L && id <= 915299999L);
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong generation = new AtomicLong();
    private final MisightDatabase.Filter filter = new MisightDatabase.Filter();
    private final List<MisightDatabase.Event> rows = new ArrayList<>();
    /** What the list actually shows: day headers (String) interleaved with events. */
    private final List<Object> items = new ArrayList<>();
    private final List<Group> groups = new ArrayList<>();
    private volatile MisightDatabase database;
    private MisightDatabase.Stats stats;
    private EventCatalog catalog;
    private AppNames apps;
    private EventPresenter presenter;
    private EventAdapter adapter;
    private TextView overview, highlights, source, status, pageText, empty;
    private EditText search;
    private Button readButton, importButton, typeButton, dateButton, prev, next;
    private HorizontalScrollView chipScroll;
    private LinearLayout chipRow;
    private ProgressBar progress;
    private int page;
    private boolean busy, destroyed, millis, mask = false;
    private Group activeGroup;
    private boolean privacyOnly = false;
    private long privacyTotalCount = 0;
    private int bg, surface, ink, muted, accent, border, alertColor;
    private String dateLabel = "日期";
    private MisightDatabase.Filter exportFilter;
    private final Runnable delayedSearch = () -> reload(0);

    /** Every event id that shares one readable title, shown to people as a single kind of event. */
    private static final class Group {
        final String title; final List<Long> ids = new ArrayList<>(); long count;
        Group(String title) { this.title = title; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        catalog = new EventCatalog(this); apps = new AppNames(this); presenter = new EventPresenter(catalog, apps);
        mask = getPreferences(MODE_PRIVATE).getBoolean("mask", false);
        buildUi();
        String name = getPreferences(MODE_PRIVATE).getString("snapshot", "");
        if (!name.isEmpty() && name.matches("snapshot-[a-zA-Z0-9-]+\\.db")) {
            File saved = new File(new File(getFilesDir(), "snapshots"), name);
            if (saved.isFile()) openExisting(saved);
        }
    }
    private int dp(int n) { return (int)(getResources().getDisplayMetrics().density * n + .5f); }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t;
    }
    private Button button(String title) {
        Button b = new Button(this); b.setText(title); b.setTextSize(13); b.setAllCaps(false);
        b.setTextColor(ink); b.setMinHeight(dp(44)); b.setMinimumWidth(0);
        b.setPadding(dp(10), 0, dp(10), 0); b.setBackground(rounded(surface, 13)); return b;
    }
    private void addGap(LinearLayout parent, int height) {
        parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
    }
    private void horizontalButton(LinearLayout row, View v, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), weight);
        lp.setMargins(0, 0, dp(7), 0); row.addView(v, lp);
    }
    private void buildUi() {
        boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = Color.parseColor(dark ? "#101917" : "#F3F7F4");
        surface = Color.parseColor(dark ? "#1B2824" : "#FFFFFF");
        ink = Color.parseColor(dark ? "#E7F3EC" : "#172D26");
        muted = Color.parseColor(dark ? "#A0B6AA" : "#5E7468");
        accent = Color.parseColor(dark ? "#6CE5C0" : "#087F68");
        border = Color.parseColor(dark ? "#2B3B32" : "#E4ECE6");
        alertColor = Color.parseColor(dark ? "#FF7A7A" : "#D32F2F");
        getWindow().setStatusBarColor(bg); getWindow().setNavigationBarColor(bg);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(bg);
        root.setPadding(dp(16), dp(16), dp(16), dp(8));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(16), insets.getSystemWindowInsetTop()+dp(12), dp(16), insets.getSystemWindowInsetBottom()+dp(8));
            return insets.consumeSystemWindowInsets();
        });
        setContentView(root); root.requestApplyInsets();
        LinearLayout heading = new LinearLayout(this); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("MiSight 查看器", 25, ink); title.setTypeface(null, Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button more = button("更多"); more.setOnClickListener(v -> more()); heading.addView(more);
        root.addView(heading);
        root.addView(text("本地离线 · 只读快照", 12, muted)); addGap(root, 13);
        LinearLayout actions = new LinearLayout(this);
        readButton = button("读取本机 · Root"); readButton.setTextColor(accent);
        importButton = button("导入数据库");
        horizontalButton(actions, readButton, 1); horizontalButton(actions, importButton, 1); root.addView(actions);
        readButton.setOnClickListener(v -> rootSource()); importButton.setOnClickListener(v -> importFile());
        addGap(root, 10);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12)); card.setBackground(rounded(surface, 16));
        overview = text("打开数据库，看看系统记下了什么", 17, ink); overview.setTypeface(null, Typeface.BOLD);
        source = text("可导入已导出的 event.db，也可读取本机主库或备份库。", 12, muted);
        source.setPadding(0, dp(6), 0, 0);
        highlights = text("", 12, accent); highlights.setPadding(0, dp(4), 0, 0); highlights.setVisibility(View.GONE);
        card.addView(overview); card.addView(source); card.addView(highlights); root.addView(card); addGap(root, 10);
        search = new EditText(this); search.setSingleLine(true); search.setTextSize(14); search.setTextColor(ink);
        search.setHintTextColor(muted); search.setHint("搜索应用名、事件名称或字段内容");
        search.setPadding(dp(13), dp(8), dp(13), dp(8)); search.setBackground(rounded(surface, 13));
        root.addView(search, new LinearLayout.LayoutParams(-1, dp(46)));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filter.search = s.toString(); main.removeCallbacks(delayedSearch); main.postDelayed(delayedSearch, 350);
            }
            public void afterTextChanged(Editable s) { }
        });
        addGap(root, 8);
        chipScroll = new HorizontalScrollView(this); chipScroll.setHorizontalScrollBarEnabled(false); chipScroll.setVisibility(View.GONE);
        chipRow = new LinearLayout(this); chipScroll.addView(chipRow); root.addView(chipScroll); addGap(root, 8);
        LinearLayout filters = new LinearLayout(this);
        typeButton = button("全部类型"); dateButton = button("日期"); Button reset = button("重置");
        horizontalButton(filters, typeButton, 1.7f); horizontalButton(filters, dateButton, 1); horizontalButton(filters, reset, .8f);
        typeButton.setOnClickListener(v -> chooseType()); dateButton.setOnClickListener(v -> chooseDate());
        reset.setOnClickListener(v -> {
            privacyOnly = false; filter.eventId = null; filter.eventIds = null; activeGroup = null; filter.fromMillis = null; filter.untilMillis = null; dateLabel = "日期";
            typeButton.setText("全部类型"); dateButton.setText(dateLabel); rebuildChips(); search.setText(""); reload(0);
        }); root.addView(filters);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true); progress.setVisibility(View.INVISIBLE); root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        status = text("尚未读取数据库", 12, muted); status.setPadding(0, dp(6), 0, dp(6)); root.addView(status);
        FrameLayout listArea = new FrameLayout(this);
        ListView list = new ListView(this); list.setDividerHeight(0); list.setCacheColorHint(Color.TRANSPARENT);
        adapter = new EventAdapter(); list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> { Object item = items.get(pos); if (item instanceof MisightDatabase.Event) detail((MisightDatabase.Event) item); });
        listArea.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = text("数据只保存在本机\n点击“更多 → 演示数据”可先体验", 15, muted); empty.setGravity(Gravity.CENTER);
        listArea.addView(empty, new FrameLayout.LayoutParams(-1, -1)); list.setEmptyView(empty);
        root.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout paging = new LinearLayout(this); paging.setGravity(Gravity.CENTER_VERTICAL);
        prev = button("上一页"); next = button("下一页"); pageText = text("—", 13, muted); pageText.setGravity(Gravity.CENTER);
        paging.addView(prev); paging.addView(pageText, new LinearLayout.LayoutParams(0, dp(44), 1)); paging.addView(next);
        prev.setEnabled(false); next.setEnabled(false); prev.setOnClickListener(v -> reload(page-1)); next.setOnClickListener(v -> reload(page+1));
        root.addView(paging);
        TextView note = text("时间：北京时间 · 本地记录不代表已上传", 11, muted); note.setGravity(Gravity.CENTER); root.addView(note);
    }

    private void rootSource() {
        if (busy) return;
        new AlertDialog.Builder(this).setTitle("读取本机快照").setItems(new String[]{"主数据库", "恢复 / 备份数据库"}, (d, which) ->
                loadSnapshot(() -> SnapshotImporter.rootSnapshot(this, which == 1), which == 1 ? "本机备份库" : "本机主库")).setNegativeButton("取消", null).show();
    }
    private void importFile() {
        if (busy) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, IMPORT);
    }
    private interface SnapshotTask { File run() throws Exception; }
    private void openExisting(File file) { loadSnapshot(() -> file, "上次快照"); }
    private void loadSnapshot(SnapshotTask task, String description) {
        busy(true, "正在读取、校验数据库…"); generation.incrementAndGet();
        worker.execute(() -> {
            try {
                File file = task.run(); MisightDatabase opened = new MisightDatabase(file);
                MisightDatabase.Stats loaded;
                try { loaded = opened.stats(); } catch (Exception e) { opened.close(); throw e; }
                File previous = database == null ? null : database.file;
                if (database != null) database.close(); database = opened;
                getPreferences(MODE_PRIVATE).edit().putString("snapshot", file.getName()).apply();
                if (previous != null && !previous.equals(file)) SnapshotImporter.remove(previous);
                ui(() -> {
                    stats = loaded; millis = opened.milliseconds;
                    filter.eventId = null; filter.eventIds = null; activeGroup = null; privacyOnly = false; typeButton.setText("全部类型");
                    buildGroups();
                    overview.setText(String.format(Locale.CHINA, "%,d 条系统记录  ·  %d 类事件", stats.total, groups.size()));
                    source.setText(description + "\n" + EventCatalog.time(stats.first, opened.milliseconds) + " — " + EventCatalog.time(stats.last, opened.milliseconds));

                    long privacyTotal = 0;
                    long appSwitchCount = 0, locCount = 0, callCount = 0, mediaCount = 0, unlockCount = 0, vpnCount = 0;
                    for (MisightDatabase.TypeCount t : stats.types) {
                        if (isPrivacy(t.eventId)) privacyTotal += t.count;
                        if (t.eventId == 907400027L) appSwitchCount += t.count;
                        else if (t.eventId == 915100017L) locCount += t.count;
                        else if (t.eventId >= 915200000L && t.eventId <= 915299999L) callCount += t.count;
                        else if (t.eventId == 923525000L) mediaCount += t.count;
                        else if (t.eventId == 923044101L) unlockCount += t.count;
                        else if (t.eventId == 916101001L) vpnCount += t.count;
                    }
                    privacyTotalCount = privacyTotal;

                    if (privacyTotal > 0) {
                        StringBuilder sb = new StringBuilder();
                        sb.append(String.format(Locale.CHINA, "⚠️ 检出高敏感隐私 %,d 条：", privacyTotal));
                        List<String> parts = new ArrayList<>();
                        if (appSwitchCount > 0) parts.add("📱 应用切换 " + String.format(Locale.CHINA, "%,d", appSwitchCount));
                        if (locCount > 0) parts.add("📍 省市位置 " + String.format(Locale.CHINA, "%,d", locCount));
                        if (callCount > 0) parts.add("📞 通话与基站 " + String.format(Locale.CHINA, "%,d", callCount));
                        if (mediaCount > 0) parts.add("🖼️ 相册 " + String.format(Locale.CHINA, "%,d", mediaCount));
                        if (unlockCount > 0) parts.add("🔓 解锁 " + String.format(Locale.CHINA, "%,d", unlockCount));
                        if (vpnCount > 0) parts.add("🛡️ VPN " + String.format(Locale.CHINA, "%,d", vpnCount));
                        for (int i = 0; i < parts.size(); i++) {
                            if (i > 0) sb.append("  ·  ");
                            sb.append(parts.get(i));
                        }
                        highlights.setText(sb.toString());
                        highlights.setTextColor(alertColor);
                        highlights.setVisibility(View.VISIBLE);
                    } else {
                        StringBuilder top = new StringBuilder();
                        for (int i = 0; i < groups.size() && i < 3; i++) {
                            if (i > 0) top.append("  ·  ");
                            top.append(groups.get(i).title).append(' ').append(String.format(Locale.CHINA, "%,d", groups.get(i).count));
                        }
                        highlights.setText(top.length() == 0 ? "" : "最常见：" + top);
                        highlights.setTextColor(accent);
                        highlights.setVisibility(top.length() == 0 ? View.GONE : View.VISIBLE);
                    }

                    rebuildChips();
                    busy(false, ""); reload(0);
                });
            } catch (Exception e) { ui(() -> { busy(false, database == null ? "尚未读取数据库" : "保留上次快照"); error(e); }); }
        });
    }
    private void buildGroups() {
        groups.clear();
        Map<String, Group> byTitle = new LinkedHashMap<>();
        for (MisightDatabase.TypeCount t : stats.types) {
            String title = catalog.known(t.eventId) ? catalog.title(t.eventId) : "其他事件";
            Group g = byTitle.get(title);
            if (g == null) { g = new Group(title); byTitle.put(title, g); }
            g.ids.add(t.eventId); g.count += t.count;
        }
        groups.addAll(byTitle.values());
        Collections.sort(groups, (a, b) -> Long.compare(b.count, a.count));
    }
    private void rebuildChips() {
        chipRow.removeAllViews();
        if (groups.isEmpty()) { chipScroll.setVisibility(View.GONE); return; }
        chipScroll.setVisibility(View.VISIBLE);
        addChip("全部", activeGroup == null && !privacyOnly, v -> { privacyOnly = false; selectGroup(null); });
        if (privacyTotalCount > 0) {
            addChip("⚠️ 敏感隐私 (" + String.format(Locale.CHINA, "%,d", privacyTotalCount) + ")", privacyOnly, v -> selectPrivacyOnly());
        }
        List<Group> shown = new ArrayList<>(groups.subList(0, Math.min(CHIP_COUNT, groups.size())));
        if (activeGroup != null && !shown.contains(activeGroup)) shown.add(0, activeGroup);
        for (Group g : shown) addChip(g.title, g == activeGroup && !privacyOnly, v -> { privacyOnly = false; selectGroup(g); });
        if (groups.size() > CHIP_COUNT) addChip("更多…", false, v -> chooseType());
    }
    private void addChip(String label, boolean selected, View.OnClickListener click) {
        TextView chip = text(label, 13, selected ? bg : ink);
        chip.setPadding(dp(13), dp(7), dp(13), dp(7)); chip.setBackground(rounded(selected ? accent : surface, 17));
        chip.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMargins(0, 0, dp(6), 0);
        chipRow.addView(chip, lp);
    }
    private void selectPrivacyOnly() {
        if (busy) return;
        privacyOnly = true; activeGroup = null; filter.eventId = null;
        filter.eventIds = new ArrayList<>(PRIVACY_IDS);
        typeButton.setText("⚠️ 敏感隐私");
        rebuildChips(); reload(0);
    }
    private void selectGroup(Group group) {
        if (busy) return;
        privacyOnly = false;
        activeGroup = group; filter.eventId = null; filter.eventIds = group == null ? null : new ArrayList<>(group.ids);
        typeButton.setText(group == null ? "全部类型" : group.title);
        rebuildChips(); reload(0);
    }
    /** Search also matches event names and app names, not just the raw text. Runs on the worker thread. */
    private void expandSearch(MisightDatabase.Filter f) {
        if (f.search == null || f.search.trim().isEmpty()) { f.searchEventIds = null; f.searchPackages = null; return; }
        f.searchEventIds = catalog.idsMatching(f.search); f.searchPackages = apps.packagesMatching(f.search);
    }
    private void rebuildItems() {
        items.clear(); String last = null; long now = System.currentTimeMillis();
        for (MisightDatabase.Event e : rows) {
            String key = EventCatalog.dayKey(e.time, millis);
            if (!key.equals(last)) { items.add(EventCatalog.dayHeader(e.time, millis, now)); last = key; }
            items.add(e);
        }
    }
    private void reload(int requestedPage) {
        if (database == null || busy) return;
        long token = generation.incrementAndGet(); MisightDatabase.Filter selected = filter.copy();
        progress.setVisibility(View.VISIBLE); status.setText("正在筛选…");
        worker.execute(() -> {
            try {
                expandSearch(selected);
                MisightDatabase.Page loaded = database.query(selected, requestedPage);
                ui(() -> {
                    if (token != generation.get()) return;
                    rows.clear(); rows.addAll(loaded.events); page = loaded.index; rebuildItems(); adapter.notifyDataSetChanged();
                    progress.setVisibility(View.INVISIBLE);
                    status.setText(String.format(Locale.CHINA, "匹配 %,d 条 · 每页 %d 条", loaded.total, MisightDatabase.PAGE_SIZE));
                    empty.setText("没有匹配记录\n试试重置筛选条件");
                    long pages = Math.max(1, (loaded.total + MisightDatabase.PAGE_SIZE - 1) / MisightDatabase.PAGE_SIZE);
                    pageText.setText((page+1) + " / " + pages); prev.setEnabled(page > 0); next.setEnabled(page+1 < pages);
                });
            } catch (Exception e) { ui(() -> { if (token == generation.get()) { progress.setVisibility(View.INVISIBLE); error(e); } }); }
        });
    }
    private void chooseType() {
        if (stats == null || busy) return;
        String[] options = new String[groups.size()+2];
        options[0] = "全部事件类型";
        options[1] = "⚠️ 敏感隐私记录 (" + String.format(Locale.CHINA, "%,d", privacyTotalCount) + ")";
        int selected = 0;
        if (privacyOnly) selected = 1;
        for (int i=0;i<groups.size();i++) {
            Group g = groups.get(i);
            options[i+2] = g.title + "  (" + String.format(Locale.CHINA, "%,d", g.count) + ")";
            if (g == activeGroup && !privacyOnly) selected = i+2;
        }
        new AlertDialog.Builder(this).setTitle("事件类型").setSingleChoiceItems(options, selected, (dialog, which) -> {
            dialog.dismiss();
            if (which == 0) selectGroup(null);
            else if (which == 1) selectPrivacyOnly();
            else selectGroup(groups.get(which-2));
        }).setNegativeButton("取消", null).show();
    }
    private static SimpleDateFormat dateFormat() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA); f.setLenient(false);
        f.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai")); return f;
    }
    private void chooseDate() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(8), dp(20), 0);
        EditText from = new EditText(this), until = new EditText(this);
        from.setSingleLine(true); until.setSingleLine(true); from.setHint("开始日期，例如 2026-11-07"); until.setHint("结束日期，例如 2026-11-07");
        if (filter.fromMillis != null) from.setText(dateFormat().format(new Date(filter.fromMillis)));
        if (filter.untilMillis != null) until.setText(dateFormat().format(new Date(filter.untilMillis-1)));
        box.addView(from); box.addView(until); box.addView(text("留空表示不限。包含结束日期全天。", 12, muted));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("按日期筛选").setView(box).setPositiveButton("应用", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String a = from.getText().toString().trim(), b = until.getText().toString().trim();
                if ((!a.isEmpty() && !a.matches("\\d{4}-\\d{2}-\\d{2}")) || (!b.isEmpty() && !b.matches("\\d{4}-\\d{2}-\\d{2}"))) throw new Exception("日期格式为 YYYY-MM-DD");
                Long begin = a.isEmpty() ? null : dateFormat().parse(a).getTime();
                Long end = b.isEmpty() ? null : Math.addExact(dateFormat().parse(b).getTime(), 86_400_000L);
                if (begin != null && end != null && begin >= end) throw new Exception("开始日期不能晚于结束日期");
                filter.fromMillis = begin; filter.untilMillis = end; dateLabel = begin == null && end == null ? "日期" : "日期已筛选";
                dateButton.setText(dateLabel); dialog.dismiss(); reload(0);
            } catch (Exception e) { Toast.makeText(this, "日期无效，请使用 YYYY-MM-DD 并检查先后顺序", Toast.LENGTH_LONG).show(); }
        })); dialog.show();
    }

    private void detail(MisightDatabase.Event event) {
        EventPresenter.Card card = presenter.present(event, mask);
        long now = System.currentTimeMillis();
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(dp(20), dp(10), dp(20), dp(20));
        TextView head = text(card.headline, 19, ink); head.setTypeface(null, Typeface.BOLD); head.setTextIsSelectable(true); box.addView(head);
        String ago = EventCatalog.ago(event.time, millis, now);
        StringBuilder meta = new StringBuilder(card.category).append("\n").append(EventCatalog.time(event.time, millis));
        if (!ago.isEmpty()) meta.append("（").append(ago).append("）");
        if (event.occurrences > 1) meta.append("\n该记录合并了 ").append(event.occurrences).append(" 次发生");
        TextView info = text(meta.toString(), 13, muted); info.setPadding(0, dp(4), 0, dp(8)); box.addView(info);
        String about = catalog.plainExplanation(event.eventId);
        if (!about.isEmpty()) { TextView t = text(about, 13, ink); t.setPadding(0, 0, 0, dp(8)); box.addView(t); }
        box.addView(sectionTitle("详细信息"));
        if (card.fields.isEmpty()) box.addView(text("这条记录没有可读取的字段。", 13, muted));
        for (EventPresenter.Field f : card.fields) box.addView(fieldRow(f));
        TextView note = text("中文名称与换算是阅读辅助，以原始字段为准；本地记录不代表已上传。"
                + (mask ? "\n敏感字段已打码，可在“更多”中关闭。" : ""), 11, muted);
        note.setPadding(0, dp(10), 0, dp(6)); box.addView(note);

        TextView raw = text(EventCatalog.pretty(event.raw), 12, ink); raw.setTypeface(Typeface.MONOSPACE);
        raw.setTextIsSelectable(true); raw.setVisibility(View.GONE); raw.setPadding(0, dp(6), 0, 0);
        Button toggle = button("查看原始字段"); toggle.setOnClickListener(v -> {
            boolean show = raw.getVisibility() != View.VISIBLE;
            raw.setVisibility(show ? View.VISIBLE : View.GONE); toggle.setText(show ? "收起原始字段" : "查看原始字段");
        });
        box.addView(toggle, new LinearLayout.LayoutParams(-1, dp(44))); box.addView(raw);
        box.addView(text("事件 ID " + event.eventId + "  ·  记录 #" + event.id, 11, muted));
        ScrollView scroll = new ScrollView(this); scroll.addView(box);
        new AlertDialog.Builder(this).setTitle("记录详情").setView(scroll).setPositiveButton("关闭", null)
                .setNeutralButton("复制原始字段", (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("MiSight 原始字段", EventCatalog.pretty(event.raw)));
                    Toast.makeText(this, "已复制完整原始字段", Toast.LENGTH_SHORT).show();
                }).show();
    }
    private TextView sectionTitle(String label) {
        TextView t = text(label, 13, accent); t.setTypeface(null, Typeface.BOLD); t.setPadding(0, dp(4), 0, dp(4)); return t;
    }
    private View fieldRow(EventPresenter.Field f) {
        LinearLayout row = new LinearLayout(this); row.setPadding(0, dp(4), 0, dp(4));
        TextView label = text(f.label, 12, muted); label.setTypeface(f.labelled ? Typeface.DEFAULT : Typeface.MONOSPACE);
        TextView value = text(f.value, 14, ink); value.setTextIsSelectable(true);
        row.addView(label, new LinearLayout.LayoutParams(dp(104), -2));
        row.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }
    private void more() {
        new AlertDialog.Builder(this).setItems(new String[]{"导出全部匹配记录 · JSON", "演示数据", "敏感字段打码：" + (mask ? "开" : "关"), "删除本应用的快照", "关于与读取说明"}, (d, which) -> {
            if (which == 2) {
                mask = !mask; getPreferences(MODE_PRIVATE).edit().putBoolean("mask", mask).apply();
                adapter.notifyDataSetChanged();
                Toast.makeText(this, mask ? "已开启敏感字段打码" : "已关闭打码，详情将显示完整内容", Toast.LENGTH_SHORT).show();
                return;
            }
            if (busy) return;
            if (which == 0) {
                if (database == null) { Toast.makeText(this, "请先打开数据库", Toast.LENGTH_SHORT).show(); return; }
                exportFilter = filter.copy();
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_TITLE, "misight-records.json"), EXPORT);
            } else if (which == 1) loadSnapshot(() -> DemoDatabase.create(this), "人工演示数据 · 非真实记录");
            else if (which == 3) clearSnapshot();
            else new AlertDialog.Builder(this).setTitle("MiSight 查看器 0.2.2").setMessage(
                    "支持 MiSight event_info 数据库，中文标签是辅助解释。\n\n列表按日期分组，突出展示被监控感极强的高敏感记录：\n· 📱 应用前后台切换（每分每秒使用应用的切换轨迹）\n· 📍 驻留省市与网络场景（详细地级市、停留时长、电梯/高铁模式）\n· 📞 通话记录与通话基站小区（Cell ID 精准基站塔、通话时长）\n· 🛡️ VPN 代理使用状态\n· 🖼️ 相册图片/视频绝对路径与删除记录\n· 🔓 屏幕解锁方式与鉴权耗时\n· 📷 相机/麦克风调用记录\n\n详情页“复制原始字段”与“查看原始字段”均完整展示未打码原始 JSON。\n\n内置固定签名证书，云端构建固定统一密钥签名。\n\n导入已导出的 SQLite 快照无需 root。读取本机需要授权 root。\n\n应用只读取快照，不修改系统数据库。没有互联网权限，不内置统计 SDK。"
            ).setPositiveButton("知道了", null).show();
        }).show();
    }
    private void clearSnapshot() {
        if (database == null) return;
        new AlertDialog.Builder(this).setTitle("删除本应用快照？").setMessage("将删除本应用保存的副本，系统原数据库不受影响。")
                .setNegativeButton("取消", null).setPositiveButton("删除", (d,w) -> {
                    busy(true, "正在删除快照…"); generation.incrementAndGet();
                    worker.execute(() -> {
                        File file = database.file; database.close(); database = null; SnapshotImporter.remove(file);
                        getPreferences(MODE_PRIVATE).edit().remove("snapshot").apply();
                        ui(() -> {
                            stats = null; rows.clear(); items.clear(); groups.clear(); activeGroup = null; privacyOnly = false; filter.eventIds = null;
                            adapter.notifyDataSetChanged(); rebuildChips(); highlights.setVisibility(View.GONE);
                            overview.setText("尚未打开数据库"); source.setText("导入或读取本机快照开始查看"); busy(false, "快照已删除");
                            prev.setEnabled(false); next.setEnabled(false); pageText.setText("—");
                        });
                    });
                }).show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == IMPORT) loadSnapshot(() -> {
            InputStream in = getContentResolver().openInputStream(uri);
            if (in == null) throw new java.io.IOException("无法打开所选文件");
            return SnapshotImporter.importStream(this, in);
        }, "导入的数据库快照");
        else if (request == EXPORT && database != null) {
            busy(true, "正在导出全部匹配记录…"); generation.incrementAndGet();
            MisightDatabase.Filter selected = exportFilter == null ? filter.copy() : exportFilter;
            worker.execute(() -> {
                try {
                    expandSearch(selected);
                    OutputStream out = getContentResolver().openOutputStream(uri, "wt");
                    if (out == null) throw new java.io.IOException("无法写入所选位置");
                    long count = database.export(selected, new OutputStreamWriter(out, StandardCharsets.UTF_8), catalog);
                    ui(() -> { busy(false, "已导出 " + count + " 条匹配记录"); reload(page); Toast.makeText(this, "已导出 " + count + " 条记录", Toast.LENGTH_LONG).show(); });
                } catch (Exception e) { ui(() -> { busy(false, "导出失败"); reload(page); error(e); }); }
            });
        }
    }
    private void busy(boolean value, String label) {
        busy = value; readButton.setEnabled(!value); importButton.setEnabled(!value);
        typeButton.setEnabled(!value); dateButton.setEnabled(!value); search.setEnabled(!value);
        progress.setVisibility(value ? View.VISIBLE : View.INVISIBLE); status.setText(label);
        if (value) { prev.setEnabled(false); next.setEnabled(false); }
    }
    private void ui(Runnable action) { main.post(() -> { if (!destroyed) action.run(); }); }
    private void error(Exception e) {
        new AlertDialog.Builder(this).setTitle("操作未完成").setMessage(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()).setPositiveButton("知道了", null).show();
    }
    @Override protected void onDestroy() {
        destroyed = true; main.removeCallbacksAndMessages(null);
        worker.execute(() -> { if (database != null) { database.close(); database = null; } });
        worker.shutdown(); super.onDestroy();
    }
    private static final class CardViews { TextView category, time, headline, facts; }
    private final class EventAdapter extends BaseAdapter {
        public int getCount() { return items.size(); }
        public Object getItem(int position) { return items.get(position); }
        public long getItemId(int position) { Object o = items.get(position); return o instanceof MisightDatabase.Event ? ((MisightDatabase.Event) o).id : -position - 1; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return items.get(position) instanceof MisightDatabase.Event ? 1 : 0; }
        @Override public boolean isEnabled(int position) { return items.get(position) instanceof MisightDatabase.Event; }
        public View getView(int position, View recycled, ViewGroup parent) {
            Object item = items.get(position);
            if (!(item instanceof MisightDatabase.Event)) {
                TextView header = recycled instanceof TextView ? (TextView) recycled : text("", 13, muted);
                header.setTypeface(null, Typeface.BOLD); header.setPadding(dp(4), dp(10), dp(4), dp(6)); header.setText((String) item);
                return header;
            }
            FrameLayout wrap; CardViews views;
            if (recycled instanceof FrameLayout) { wrap = (FrameLayout) recycled; views = (CardViews) wrap.getTag(); }
            else {
                wrap = new FrameLayout(MainActivity.this); wrap.setPadding(0, 0, 0, dp(8));
                LinearLayout box = new LinearLayout(MainActivity.this); box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(13), dp(10), dp(13), dp(11)); box.setBackground(rounded(surface, 13));
                views = new CardViews();
                views.category = text("", 11, accent); views.time = text("", 11, muted);
                LinearLayout top = new LinearLayout(MainActivity.this);
                top.addView(views.category, new LinearLayout.LayoutParams(0, -2, 1)); top.addView(views.time);
                views.headline = text("", 15, ink); views.headline.setTypeface(null, Typeface.BOLD); views.headline.setMaxLines(2);
                views.headline.setPadding(0, dp(3), 0, 0);
                views.facts = text("", 12, muted); views.facts.setMaxLines(2); views.facts.setPadding(0, dp(2), 0, 0);
                box.addView(top); box.addView(views.headline); box.addView(views.facts);
                wrap.addView(box, new FrameLayout.LayoutParams(-1, -2)); wrap.setTag(views);
            }
            MisightDatabase.Event e = (MisightDatabase.Event) item;
            EventPresenter.Card card = presenter.present(e, mask);
            boolean sensitive = isPrivacy(e.eventId);
            if (sensitive) {
                views.category.setText("⚠️ " + card.category);
                views.category.setTextColor(alertColor);
            } else {
                views.category.setText(card.category);
                views.category.setTextColor(accent);
            }
            views.time.setText(EventCatalog.clock(e.time, millis) + (e.occurrences > 1 ? "  ·  ×" + e.occurrences : ""));
            views.headline.setText(card.headline);
            String facts = presenter.facts(card, 3);
            views.facts.setText(facts); views.facts.setVisibility(facts.isEmpty() ? View.GONE : View.VISIBLE);
            return wrap;
        }
    }
}
