package io.github.artriai.misightviewer;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class DatabaseTest {
    private Context context() { return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private File fixture(boolean millis, boolean legacy) throws Exception {
        File file = File.createTempFile("fixture-", ".sqlite", context().getCacheDir());
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        try {
            db.execSQL("CREATE TABLE event_info(id INTEGER PRIMARY KEY,event_id INTEGER,first_time INTEGER,param_list TEXT" + (legacy ? "" : ",occured_cnt INTEGER") + ")");
            db.beginTransaction();
            try {
                for (int i=1;i<=205;i++) {
                    long time = 1793980800L + (i <= 3 ? -1 : 1);
                    if (millis) time *= 1000;
                    String raw = i == 3 ? "not valid json" : new JSONObject().put("PackageName", i % 2 == 0 ? "example.reader" : "example.music").put("literal", "a_%'b").toString();
                    if (legacy) db.execSQL("INSERT INTO event_info VALUES(?,?,?,?)", new Object[]{i,i % 2 == 0 ? 907400027 : 902006601,time,raw});
                    else db.execSQL("INSERT INTO event_info VALUES(?,?,?,?,?)", new Object[]{i,i % 2 == 0 ? 907400027 : 902006601,time,raw,1});
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        } finally { db.close(); }
        return file;
    }
    private byte[] digest(File f) throws Exception {
        MessageDigest d = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) { byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)d.update(b,0,n); }
        return d.digest();
    }
    @Test public void paginationIsStableAndReadOnly() throws Exception {
        File f=fixture(false,false);byte[] before=digest(f);
        try (MisightDatabase db=new MisightDatabase(f)) {
            assertEquals(205,db.stats().total);assertEquals(2,db.stats().types.size());
            MisightDatabase.Page one=db.query(new MisightDatabase.Filter(),0),two=db.query(new MisightDatabase.Filter(),1),last=db.query(new MisightDatabase.Filter(),2);
            assertEquals(100,one.events.size());assertEquals(100,two.events.size());assertEquals(5,last.events.size());
            Set<Long> seen=new HashSet<>();for(MisightDatabase.Page p:Arrays.asList(one,two,last))for(MisightDatabase.Event e:p.events)assertTrue(seen.add(e.id));
            assertEquals(205,seen.size());assertEquals(205,one.events.get(0).id);
        }
        assertArrayEquals(before,digest(f));f.delete();
    }
    @Test public void searchTreatsSqlCharactersLiterallyAndCombinesType() throws Exception {
        File f=fixture(false,false);
        try(MisightDatabase db=new MisightDatabase(f)) {
            MisightDatabase.Filter filter=new MisightDatabase.Filter();filter.search="a_%'b";
            assertEquals(204,db.query(filter,0).total);
            filter.search="example.reader";filter.eventId=907400027L;
            assertEquals(102,db.query(filter,0).total);
            filter.search="' OR 1=1 --";assertEquals(0,db.query(filter,0).total);
            filter.eventId=null;filter.search="902006601";assertEquals(103,db.query(filter,0).total);
        }finally{f.delete();}
    }
    @Test public void dateFilteringHandlesSecondsAndMilliseconds() throws Exception {
        for(boolean millis:new boolean[]{false,true}) {
            File f=fixture(millis,false);
            try(MisightDatabase db=new MisightDatabase(f)) {
                assertEquals(millis,db.milliseconds);
                MisightDatabase.Filter filter=new MisightDatabase.Filter();filter.fromMillis=1793980800000L;filter.untilMillis=1794067200000L;
                assertEquals(202,db.query(filter,0).total);
                filter.fromMillis=null;filter.untilMillis=1793980800000L;assertEquals(3,db.query(filter,0).total);
            }finally{f.delete();}
        }
    }
    @Test public void exportIncludesEveryMatchingPageAndPreservesRawData() throws Exception {
        File f=fixture(false,false);
        try(MisightDatabase db=new MisightDatabase(f)) {
            StringWriter out=new StringWriter();assertEquals(205,db.export(new MisightDatabase.Filter(),out));
            JSONObject root=new JSONObject(out.toString());assertEquals(205,root.getJSONArray("records").length());
            assertEquals(205,root.getInt("count"));assertEquals("not valid json",root.getJSONArray("records").getJSONObject(202).getString("param_list"));
        }finally{f.delete();}
    }
    @Test public void legacySchemaAndMalformedPayloadRemainReadable() throws Exception {
        File f=fixture(false,true);
        try(MisightDatabase db=new MisightDatabase(f)) {
            assertEquals(1,db.query(new MisightDatabase.Filter(),0).events.get(0).occurrences);
            assertEquals("broken {",EventCatalog.pretty("broken {"));
            assertEquals("",EventCatalog.packageHint("broken {"));
        }finally{f.delete();}
    }
    @Test public void importRejectsWrongSchemaAndKeepsOriginalBytes() throws Exception {
        File f=fixture(false,false);byte[] before=digest(f);
        File imported=SnapshotImporter.importStream(context(),new FileInputStream(f));
        try {assertArrayEquals(before,digest(imported));assertArrayEquals(before,digest(f));}
        finally{SnapshotImporter.remove(imported);f.delete();}
        try {SnapshotImporter.importStream(context(),new ByteArrayInputStream("not a database".getBytes()));fail("invalid file accepted");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("SQLite"));}
        File wrong=File.createTempFile("wrong-", ".sqlite",context().getCacheDir());SQLiteDatabase x=SQLiteDatabase.openOrCreateDatabase(wrong,null);x.execSQL("CREATE TABLE other(id INTEGER)");x.close();
        try {SnapshotImporter.importStream(context(),new FileInputStream(wrong));fail("wrong schema accepted");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("event_info"));}finally{wrong.delete();}
    }
    @Test public void demoContainsOnlySyntheticData() throws Exception {
        File f=DemoDatabase.create(context());
        try(MisightDatabase db=new MisightDatabase(f)) {
            assertEquals(3,db.stats().total);MisightDatabase.Filter filter=new MisightDatabase.Filter();filter.search="example.";
            assertEquals(3,db.query(filter,0).total);
        }finally{SnapshotImporter.remove(f);}
    }
    @Test public void optionalLocalSnapshotRemainsPrivateAndQueryable() throws Exception {
        String path=InstrumentationRegistry.getArguments().getString("localSnapshot");
        if(path==null)return; // A developer may supply a device-private snapshot; never part of CI.
        File f=new File(path);byte[] before=digest(f);
        try(MisightDatabase db=new MisightDatabase(f)) {
            long expected=Long.parseLong(InstrumentationRegistry.getArguments().getString("expectedRows","0"));
            assertTrue(db.stats().total>0);if(expected>0)assertEquals(expected,db.stats().total);
            MisightDatabase.Filter filter=new MisightDatabase.Filter();filter.eventId=907400027L;
            MisightDatabase.Page p=db.query(filter,0);assertTrue(p.total>0);
            for(MisightDatabase.Event event:p.events)assertEquals(907400027L,event.eventId);
        }
        assertArrayEquals(before,digest(f));
    }
}
