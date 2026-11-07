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
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong generation = new AtomicLong();
    private final MisightDatabase.Filter filter = new MisightDatabase.Filter();
    private final List<MisightDatabase.Event> rows = new ArrayList<>();
    private volatile MisightDatabase database;
    private MisightDatabase.Stats stats;
    private EventCatalog catalog;
    private EventAdapter adapter;
    private TextView overview, source, status, pageText, empty;
    private EditText search;
    private Button readButton, importButton, typeButton, dateButton, prev, next;
    private ProgressBar progress;
    private int page;
    private boolean busy, destroyed;
    private int bg, surface, ink, muted, accent, border;
    private String dateLabel = "日期";
    private MisightDatabase.Filter exportFilter;
    private final Runnable delayedSearch = () -> reload(0);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        catalog = new EventCatalog(this);
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
        source.setPadding(0, dp(6), 0, 0); card.addView(overview); card.addView(source); root.addView(card); addGap(root, 10);
        search = new EditText(this); search.setSingleLine(true); search.setTextSize(14); search.setTextColor(ink);
        search.setHintTextColor(muted); search.setHint("搜索包名、字段内容或事件 ID");
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
        LinearLayout filters = new LinearLayout(this);
        typeButton = button("全部类型"); dateButton = button("日期"); Button reset = button("重置");
        horizontalButton(filters, typeButton, 1.7f); horizontalButton(filters, dateButton, 1); horizontalButton(filters, reset, .8f);
        typeButton.setOnClickListener(v -> chooseType()); dateButton.setOnClickListener(v -> chooseDate());
        reset.setOnClickListener(v -> {
            filter.eventId = null; filter.fromMillis = null; filter.untilMillis = null; dateLabel = "日期";
            typeButton.setText("全部类型"); dateButton.setText(dateLabel); search.setText(""); reload(0);
        }); root.addView(filters);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true); progress.setVisibility(View.INVISIBLE); root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        status = text("尚未读取数据库", 12, muted); status.setPadding(0, dp(6), 0, dp(6)); root.addView(status);
        FrameLayout listArea = new FrameLayout(this);
        ListView list = new ListView(this); list.setDividerHeight(0); list.setCacheColorHint(Color.TRANSPARENT);
        adapter = new EventAdapter(); list.setAdapter(adapter); list.setOnItemClickListener((p, v, pos, id) -> detail(rows.get(pos)));
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
                    stats = loaded; filter.eventId = null; typeButton.setText("全部类型");
                    overview.setText(String.format(Locale.CHINA, "%,d 条记录  ·  %d 种事件", stats.total, stats.types.size()));
                    source.setText(description + "\n" + EventCatalog.time(stats.first, opened.milliseconds) + " — " + EventCatalog.time(stats.last, opened.milliseconds));
                    busy(false, ""); reload(0);
                });
            } catch (Exception e) { ui(() -> { busy(false, database == null ? "尚未读取数据库" : "保留上次快照"); error(e); }); }
        });
    }
    private void reload(int requestedPage) {
        if (database == null || busy) return;
        long token = generation.incrementAndGet(); MisightDatabase.Filter selected = filter.copy();
        progress.setVisibility(View.VISIBLE); status.setText("正在筛选…");
        worker.execute(() -> {
            try {
                MisightDatabase.Page loaded = database.query(selected, requestedPage);
                ui(() -> {
                    if (token != generation.get()) return;
                    rows.clear(); rows.addAll(loaded.events); page = loaded.index; adapter.notifyDataSetChanged();
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
        String[] options = new String[stats.types.size()+1]; options[0] = "全部事件类型";
        int selected = 0;
        for (int i=0;i<stats.types.size();i++) {
            MisightDatabase.TypeCount t = stats.types.get(i);
            options[i+1] = catalog.title(t.eventId) + " · " + t.eventId + "  (" + t.count + ")";
            if (Objects.equals(filter.eventId, t.eventId)) selected = i+1;
        }
        new AlertDialog.Builder(this).setTitle("事件类型").setSingleChoiceItems(options, selected, (dialog, which) -> {
            filter.eventId = which == 0 ? null : stats.types.get(which-1).eventId;
            typeButton.setText(which == 0 ? "全部类型" : catalog.title(filter.eventId));
            dialog.dismiss(); reload(0);
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
        String body = catalog.title(event.eventId) + "\n事件 ID：" + event.eventId + "  ·  行 ID：" + event.id +
                "\n时间：" + EventCatalog.time(event.time, database.milliseconds) + "\noccured_cnt：" + event.occurrences +
                "\n\n" + catalog.explanation(event.eventId) + "\n标签是阅读提示，原始字段为准。\n\n" + EventCatalog.pretty(event.raw);
        TextView content = text(body, 13, ink); content.setTypeface(Typeface.MONOSPACE); content.setTextIsSelectable(true); content.setPadding(dp(18), dp(10), dp(18), dp(20));
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("记录详情").setView(scroll).setPositiveButton("关闭", null)
                .setNeutralButton("复制原始字段", (d, w) -> {
                    ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("MiSight 原始字段", event.raw));
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                }).show();
    }
    private void more() {
        new AlertDialog.Builder(this).setItems(new String[]{"导出全部匹配记录 · JSON", "演示数据", "删除本应用的快照", "关于与读取说明"}, (d, which) -> {
            if (busy) return;
            if (which == 0) {
                if (database == null) { Toast.makeText(this, "请先打开数据库", Toast.LENGTH_SHORT).show(); return; }
                exportFilter = filter.copy();
                startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_TITLE, "misight-records.json"), EXPORT);
            } else if (which == 1) loadSnapshot(() -> DemoDatabase.create(this), "人工演示数据 · 非真实记录");
            else if (which == 2) clearSnapshot();
            else new AlertDialog.Builder(this).setTitle("MiSight 查看器 0.1.0").setMessage(
                    "支持 MiSight event_info 数据库，中文标签是辅助解释。\n\n导入已导出的 SQLite 快照无需 root。活跃数据库应导出一致性副本，单独复制主文件可能漏掉 WAL 中的记录。\n\n读取本机需要授权本应用 root：主库 /data/miuilog/misight/event.db；备份库 /mnt/rescue/misight/event.db。\n\n应用只读取快照，不修改系统数据库。没有互联网权限，不内置统计 SDK。导出的是全部匹配行，param_list 保留原始 JSON 字符串。\n\n本地记录不能单独证明存在上传。时间统一显示为北京时间。"
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
                        ui(() -> { stats = null; rows.clear(); adapter.notifyDataSetChanged(); overview.setText("尚未打开数据库"); source.setText("导入或读取本机快照开始查看"); busy(false, "快照已删除"); prev.setEnabled(false); next.setEnabled(false); pageText.setText("—"); });
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
                    OutputStream out = getContentResolver().openOutputStream(uri, "wt");
                    if (out == null) throw new java.io.IOException("无法写入所选位置");
                    long count = database.export(selected, new OutputStreamWriter(out, StandardCharsets.UTF_8));
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
    private final class EventAdapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int position) { return rows.get(position); }
        public long getItemId(int position) { return rows.get(position).id; }
        public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout box; TextView time, title, hint, raw;
            if (recycled == null) {
                box = new LinearLayout(MainActivity.this); box.setOrientation(LinearLayout.VERTICAL);
                box.setPadding(dp(13), dp(10), dp(13), dp(11)); box.setBackground(rounded(surface, 13));
                time = text("", 11, muted); title = text("", 15, ink); title.setTypeface(null, Typeface.BOLD);
                hint = text("", 12, accent); hint.setMaxLines(2); raw = text("", 12, muted); raw.setMaxLines(2);
                box.addView(time); box.addView(title); box.addView(hint); box.addView(raw);
                box.setTag(new TextView[]{time,title,hint,raw});
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2); lp.setMargins(0,0,0,dp(8));
                // ListView ignores child margins; a separator below each card supplies spacing.
                box.setMinimumHeight(dp(114));
            } else box = (LinearLayout)recycled;
            TextView[] views = (TextView[])box.getTag(); MisightDatabase.Event e = rows.get(position);
            views[0].setText(EventCatalog.time(e.time, database.milliseconds) + "  ·  #" + e.id);
            views[1].setText(catalog.title(e.eventId) + "  " + e.eventId);
            String value = EventCatalog.packageHint(e.raw); views[2].setText(value); views[2].setVisibility(value.isEmpty() ? View.GONE : View.VISIBLE);
            views[3].setText(EventCatalog.preview(e.raw)); return box;
        }
    }
}
