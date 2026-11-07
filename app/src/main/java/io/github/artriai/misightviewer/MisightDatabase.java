package io.github.artriai.misightviewer;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.JsonWriter;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** All queries run against a private, read-only snapshot. No source database writes. */
public final class MisightDatabase implements Closeable {
    public static final int PAGE_SIZE = 100;
    private final SQLiteDatabase db;
    public final File file;
    public final boolean milliseconds;
    private final boolean hasOccurrences;

    public static final class Filter {
        public String search = "";
        public Long eventId;
        /** Restricts to a group of event ids (e.g. every id that shares one readable title). */
        public List<Long> eventIds;
        /** Extra matches for the search box: events whose title matched, and apps whose name matched. */
        public List<Long> searchEventIds;
        public List<String> searchPackages;
        public Long fromMillis, untilMillis;
        public Filter copy() {
            Filter f = new Filter(); f.search = search; f.eventId = eventId;
            f.eventIds = eventIds == null ? null : new ArrayList<>(eventIds);
            f.searchEventIds = searchEventIds == null ? null : new ArrayList<>(searchEventIds);
            f.searchPackages = searchPackages == null ? null : new ArrayList<>(searchPackages);
            f.fromMillis = fromMillis; f.untilMillis = untilMillis; return f;
        }
    }
    public static final class TypeCount {
        public final long eventId, count;
        TypeCount(long id, long n) { eventId = id; count = n; }
    }
    public static final class Stats {
        public long total, first, last;
        public final List<TypeCount> types = new ArrayList<>();
    }
    public static final class Event {
        public long id, eventId, time, occurrences;
        public String raw;
    }
    public static final class Page {
        public long total;
        public int index;
        public final List<Event> events = new ArrayList<>();
    }

    public MisightDatabase(File file) throws IOException {
        this.file = file;
        SQLiteDatabase opened = null;
        try {
            opened = SQLiteDatabase.openDatabase(file.getPath(), null,
                    SQLiteDatabase.OPEN_READONLY | SQLiteDatabase.NO_LOCALIZED_COLLATORS);
            Set<String> columns = new HashSet<>();
            try (Cursor c = opened.rawQuery("PRAGMA table_info(event_info)", null)) {
                while (c.moveToNext()) columns.add(c.getString(1));
            }
            for (String required : new String[]{"id", "event_id", "first_time", "param_list"}) {
                if (!columns.contains(required)) throw new IOException("不是支持的 MiSight 数据库：缺少 event_info." + required);
            }
            hasOccurrences = columns.contains("occured_cnt");
            try (Cursor c = opened.rawQuery("PRAGMA quick_check(1)", null)) {
                if (!c.moveToFirst() || !"ok".equals(c.getString(0)))
                    throw new IOException("数据库校验失败，请导出完整快照（活跃数据库可能需要 WAL 文件）。");
            }
            try (Cursor c = opened.rawQuery("SELECT MAX(first_time) FROM event_info", null)) {
                c.moveToFirst(); milliseconds = c.getLong(0) > 100_000_000_000L;
            }
            db = opened;
        } catch (Exception e) {
            if (opened != null) opened.close();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("无法读取数据库：" + e.getMessage(), e);
        }
    }

    public Stats stats() {
        Stats s = new Stats();
        try (Cursor c = db.rawQuery("SELECT COUNT(*), MIN(first_time), MAX(first_time) FROM event_info", null)) {
            c.moveToFirst(); s.total = c.getLong(0); s.first = c.getLong(1); s.last = c.getLong(2);
        }
        try (Cursor c = db.rawQuery("SELECT event_id,COUNT(*) FROM event_info GROUP BY event_id ORDER BY COUNT(*) DESC,event_id", null)) {
            while (c.moveToNext()) s.types.add(new TypeCount(c.getLong(0), c.getLong(1)));
        }
        return s;
    }

    private static final class Where {
        final String sql;
        final List<String> args;
        Where(String sql, List<String> args) { this.sql = sql; this.args = args; }
    }
    private Where where(Filter f) {
        StringBuilder sql = new StringBuilder(" WHERE 1=1");
        List<String> args = new ArrayList<>();
        if (f.eventId != null) { sql.append(" AND event_id=?"); args.add(f.eventId.toString()); }
        if (f.fromMillis != null) {
            sql.append(" AND first_time>=?");
            args.add(Long.toString(milliseconds ? f.fromMillis : Math.floorDiv(f.fromMillis, 1000)));
        }
        if (f.untilMillis != null) {
            sql.append(" AND first_time<?");
            args.add(Long.toString(milliseconds ? f.untilMillis : Math.floorDiv(f.untilMillis, 1000)));
        }
        if (f.eventIds != null && !f.eventIds.isEmpty()) sql.append(" AND event_id IN (").append(idList(f.eventIds)).append(")");
        if (f.search != null && !f.search.trim().isEmpty()) {
            sql.append(" AND (instr(lower(COALESCE(param_list,'')),lower(?))>0 OR CAST(event_id AS TEXT)=?");
            args.add(f.search.trim()); args.add(f.search.trim());
            if (f.searchEventIds != null && !f.searchEventIds.isEmpty()) sql.append(" OR event_id IN (").append(idList(f.searchEventIds)).append(")");
            if (f.searchPackages != null) for (String pkg : f.searchPackages) {
                sql.append(" OR instr(lower(COALESCE(param_list,'')),lower(?))>0"); args.add(pkg);
            }
            sql.append(")");
        }
        return new Where(sql.toString(), args);
    }
    /** Event ids are numeric, so they are inlined: avoids SQLite's bound-variable limit for large groups. */
    private static String idList(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) { if (sb.length() > 0) sb.append(','); sb.append(id.longValue()); }
        return sb.toString();
    }
    private String columns() {
        return "SELECT id,event_id,first_time,param_list," + (hasOccurrences ? "COALESCE(occured_cnt,1)" : "1") + " FROM event_info";
    }
    private static Event event(Cursor c) {
        Event e = new Event(); e.id = c.getLong(0); e.eventId = c.getLong(1);
        e.time = c.getLong(2); e.raw = c.isNull(3) ? "" : c.getString(3); e.occurrences = c.getLong(4);
        return e;
    }
    public Page query(Filter filter, int requestedPage) {
        Where w = where(filter);
        Page p = new Page();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM event_info" + w.sql, w.args.toArray(new String[0]))) {
            c.moveToFirst(); p.total = c.getLong(0);
        }
        p.index = Math.max(0, Math.min(requestedPage, (int) Math.max(0, (p.total - 1) / PAGE_SIZE)));
        w.args.add(Integer.toString(PAGE_SIZE)); w.args.add(Long.toString((long) p.index * PAGE_SIZE));
        try (Cursor c = db.rawQuery(columns() + w.sql + " ORDER BY first_time DESC,id DESC LIMIT ? OFFSET ?", w.args.toArray(new String[0]))) {
            while (c.moveToNext()) p.events.add(event(c));
        }
        return p;
    }
    /** Streams every matching row; export is independent of the visible page. */
    public long export(Filter filter, Writer output) throws IOException { return export(filter, output, null); }
    /** Same as above; when a catalog is given each record also carries a readable {@code event_title}. */
    public long export(Filter filter, Writer output, EventCatalog catalog) throws IOException {
        Where w = where(filter); long count = 0;
        try (JsonWriter writer = new JsonWriter(output);
             Cursor c = db.rawQuery(columns() + w.sql + " ORDER BY first_time DESC,id DESC", w.args.toArray(new String[0]))) {
            writer.beginObject(); writer.name("format").value("misight-viewer-1");
            writer.name("time_unit").value(milliseconds ? "milliseconds" : "seconds");
            writer.name("display_timezone").value("Asia/Shanghai");
            writer.name("records").beginArray();
            while (c.moveToNext()) {
                Event e = event(c);
                writer.beginObject(); writer.name("id").value(e.id); writer.name("event_id").value(e.eventId);
                if (catalog != null) writer.name("event_title").value(catalog.title(e.eventId));
                writer.name("first_time").value(e.time); writer.name("time").value(EventCatalog.time(e.time, milliseconds));
                writer.name("occured_cnt").value(e.occurrences); writer.name("param_list").value(e.raw);
                writer.endObject(); count++;
            }
            writer.endArray(); writer.name("count").value(count); writer.endObject();
        }
        return count;
    }
    @Override public void close() { db.close(); }
}
