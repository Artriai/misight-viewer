package io.github.artriai.misightviewer;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.StringWriter;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ReadabilityTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private EventPresenter presenter() {
        EventCatalog catalog = new EventCatalog(context());
        return new EventPresenter(catalog, new AppNames(context()));
    }
    private MisightDatabase.Event event(long eventId, String raw) {
        MisightDatabase.Event e = new MisightDatabase.Event();
        e.id = 1; e.eventId = eventId; e.time = 1793980800L; e.occurrences = 1; e.raw = raw; return e;
    }
    private String field(EventPresenter.Card card, String key) {
        for (EventPresenter.Field f : card.fields) if (f.key.equals(key)) return f.label + "=" + f.value;
        return null;
    }

    @Test public void foregroundSwitchReadsAsASentence() {
        EventPresenter.Card card = presenter().present(event(907400027L, "{\"change\":\"TO_BACK-example.reader,TO_FRONT-example.music\",\"type\":1}"), true);
        assertEquals("example.reader 退到后台，example.music 进入前台", card.headline);
        assertEquals("应用前后台切换", card.category);
    }
    @Test public void knownAppsFieldsAndDurationsAreTranslated() {
        EventPresenter.Card card = presenter().present(event(902006601L, "{\"PackageName\":\"com.tencent.mm\",\"DurationMs\":1500,\"FPSMode\":120,\"ok\":true}"), true);
        assertEquals("微信", card.app);
        assertEquals("微信", card.headline);
        assertEquals("耗时=1.50 秒", field(card, "DurationMs"));
        assertEquals("帧率模式=120", field(card, "FPSMode"));
        assertEquals("ok=是", field(card, "ok"));
        assertEquals("应用=微信（com.tencent.mm）", field(card, "PackageName"));
    }
    @Test public void unknownFieldsKeepTheirRawName() {
        EventPresenter.Card card = presenter().present(event(1L, "{\"someNewField\":7,\"empty\":\"\",\"nothing\":null}"), true);
        assertEquals("someNewField=7", field(card, "someNewField"));
        assertNull(field(card, "empty")); assertNull(field(card, "nothing"));
        assertEquals("未标注事件", card.headline);
    }
    @Test public void sensitiveValuesAreMaskedOnlyWhenAsked() {
        String raw = "{\"path\":[\"/storage/emulated/0/DCIM/a.jpg\"],\"DeviceMac\":\"AA:BB:CC:DD:EE:FF\",\"count\":3}";
        EventPresenter.Card masked = presenter().present(event(923525000L, raw), true);
        assertFalse(field(masked, "path").contains("DCIM")); assertFalse(field(masked, "DeviceMac").contains("BB:CC"));
        assertEquals("次数=3", field(masked, "count"));
        assertFalse(EventPresenter.rawText(raw, true).contains("DCIM"));
        assertTrue(EventPresenter.rawText(raw, true).contains("count"));
        assertTrue(EventPresenter.rawText(raw, false).contains("DCIM"));
        assertTrue(field(presenter().present(event(923525000L, raw), false), "DeviceMac").contains("BB:CC"));
    }
    @Test public void malformedPayloadStillShowsSomething() {
        EventPresenter.Card card = presenter().present(event(1L, "not valid json"), true);
        assertFalse(card.json); assertEquals("内容=not valid json", field(card, ""));
        assertEquals("broken {", EventPresenter.rawText("broken {", true));
    }
    @Test public void catalogExplanationsDropBoilerplate() {
        EventCatalog catalog = new EventCatalog(context());
        assertEquals("", catalog.plainExplanation(902006601L));
        assertTrue(catalog.plainExplanation(923044101L).contains("密码"));
        assertFalse(catalog.idsMatching("蓝牙").isEmpty());
        assertEquals("时间未知", EventCatalog.time(0, false));
        assertEquals("1.50 秒", EventCatalog.duration(1500)); assertEquals("2 分 5 秒", EventCatalog.duration(125_000));
    }
    @Test public void groupAndSearchExtensionsFilterRows() throws Exception {
        File f = DemoDatabase.create(context());
        try (MisightDatabase db = new MisightDatabase(f)) {
            MisightDatabase.Filter filter = new MisightDatabase.Filter();
            filter.eventIds = Arrays.asList(902006601L, 923525000L);
            assertEquals(2, db.query(filter, 0).total);
            filter = new MisightDatabase.Filter(); filter.search = "no-such-text";
            assertEquals(0, db.query(filter, 0).total);
            filter.searchEventIds = Arrays.asList(907400027L);
            assertEquals(1, db.query(filter, 0).total);
            filter.searchEventIds = null; filter.searchPackages = Arrays.asList("example.gallery");
            assertEquals(1, db.query(filter, 0).total);
            StringWriter out = new StringWriter();
            db.export(new MisightDatabase.Filter(), out, new EventCatalog(context()));
            assertTrue(out.toString().contains("event_title"));
        } finally { SnapshotImporter.remove(f); }
    }
}
