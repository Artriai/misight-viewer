package io.github.artriai.misightviewer;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Hand-written synthetic events only. Never copied from a user's phone. */
public final class DemoDatabase {
    public static File create(Context context) throws IOException {
        File temp = File.createTempFile("demo-", ".sqlite", context.getCacheDir());
        try {
            SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(temp, null);
            try {
                db.execSQL("CREATE TABLE event_info(id INTEGER PRIMARY KEY,event_id INTEGER,first_time INTEGER,param_list TEXT,occured_cnt INTEGER)");
                long now = System.currentTimeMillis() / 1000;
                db.execSQL("INSERT INTO event_info VALUES(?,?,?,?,?)", new Object[]{1,907400027,now,"{\"change\":\"TO_BACK-example.reader,TO_FRONT-example.music\",\"type\":1}",1});
                db.execSQL("INSERT INTO event_info VALUES(?,?,?,?,?)", new Object[]{2,902006601,now-60,"{\"PackageName\":\"example.reader\",\"CaseName\":\"RenderJank\",\"FPSMode\":120,\"SoCTemp\":35}",1});
                db.execSQL("INSERT INTO event_info VALUES(?,?,?,?,?)", new Object[]{3,923525000,now-120,"{\"packageName\":\"example.gallery\",\"operationType\":40,\"path\":[\"/example/demo-image.jpg\"]}",1});
            } finally { db.close(); }
            return SnapshotImporter.importStream(context, new FileInputStream(temp));
        } finally { temp.delete(); }
    }
}
