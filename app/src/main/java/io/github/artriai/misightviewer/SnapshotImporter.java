package io.github.artriai.misightviewer;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class SnapshotImporter {
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final byte[] HEADER = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
    public static final String MAIN_SOURCE = "/data/miuilog/misight/event.db";
    public static final String RESCUE_SOURCE = "/mnt/rescue/misight/event.db";
    private SnapshotImporter() { }

    private static File newFile(Context context) throws IOException {
        File dir = new File(context.getFilesDir(), "snapshots");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建快照目录");
        return new File(dir, "snapshot-" + UUID.randomUUID() + ".db");
    }
    public static File importStream(Context context, InputStream input) throws IOException {
        File file = newFile(context);
        try {
            try (InputStream in = input; OutputStream out = new FileOutputStream(file)) { copy(in, out, MAX_BYTES); }
            validate(file); return file;
        } catch (Exception e) {
            remove(file);
            if (e instanceof IOException) throw (IOException)e;
            throw new IOException(e.getMessage(), e);
        }
    }
    private static void copy(InputStream in, OutputStream out, long max) throws IOException {
        byte[] buffer = new byte[65536]; long total = 0; int count;
        while ((count = in.read(buffer)) != -1) {
            total += count;
            if (total > max) throw new IOException("文件超过 256 MB 读取上限");
            out.write(buffer, 0, count);
        }
    }
    private static void validate(File file) throws IOException {
        byte[] header = new byte[16];
        try (InputStream in = new FileInputStream(file)) {
            if (in.read(header) != 16 || !Arrays.equals(header, HEADER)) throw new IOException("所选文件不是 SQLite 数据库");
        }
        try (MisightDatabase ignored = new MisightDatabase(file)) { /* Schema + quick_check. */ }
    }
    public static File rootSnapshot(Context context, boolean rescue) throws IOException {
        String path = rescue ? RESCUE_SOURCE : MAIN_SOURCE;
        if (!"0".equals(rootText("id -u").trim())) throw new IOException("未取得 root，请在 KernelSU / Magisk 中授权本应用");
        for (int attempt = 0; attempt < 3; attempt++) {
            File file = newFile(context);
            try {
                String before = fingerprint(path);
                boolean wal = !before.endsWith("\nNO_WAL");
                rootToFile("cat '" + path + "'", file);
                if (wal) rootToFile("cat '" + path + "-wal'", new File(file.getPath() + "-wal"));
                String after = fingerprint(path);
                String[] lines = before.split("\n");
                if (!before.equals(after) || !lines[0].startsWith(sha256(file)) ||
                        (wal && !lines[1].startsWith(sha256(new File(file.getPath() + "-wal"))))) {
                    remove(file); continue;
                }
                validate(file); return file;
            } catch (IOException e) { remove(file); throw e; }
        }
        throw new IOException("系统正在更新数据库，三次均未取得稳定快照，请稍后重试或导入已导出的副本");
    }
    private static String fingerprint(String path) throws IOException {
        String value = rootText("sha256sum '" + path + "' || exit 1; if [ -f '" + path +
                "-wal' ]; then sha256sum '" + path + "-wal'; else echo NO_WAL; fi").trim();
        if (!value.matches("(?s)^[0-9a-f]{64} .*\\n(?:[0-9a-f]{64} .*|NO_WAL)$"))
            throw new IOException("无法校验源数据库，请确认文件存在且 root 可读");
        return value;
    }
    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] data = new byte[65536]; int n;
                while ((n = in.read(data)) != -1) digest.update(data, 0, n);
            }
            StringBuilder result = new StringBuilder();
            for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 255));
            return result.toString();
        } catch (Exception e) { throw new IOException("无法校验快照", e); }
    }
    private static String rootText(String command) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        root(command, output, 16384);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
    private static void rootToFile(String command, File file) throws IOException {
        try (OutputStream output = new FileOutputStream(file)) { root(command, output, MAX_BYTES); }
    }
    private static void root(String command, OutputStream output, long max) throws IOException {
        Process process;
        try { process = new ProcessBuilder("su", "-c", command).start(); }
        catch (IOException e) { throw new IOException("找不到 su 入口；可以改用导入数据库", e); }
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        ScheduledFuture<?> timeout = timer.schedule(() -> { process.destroyForcibly(); }, 60, TimeUnit.SECONDS);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        Thread errorReader = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                byte[] data = new byte[2048]; int n;
                while ((n = in.read(data)) != -1) if (errors.size() < 8192) errors.write(data, 0, n);
            } catch (IOException ignored) { }
        }, "root-stderr");
        errorReader.start();
        try (InputStream in = process.getInputStream()) {
            copy(in, output, max);
            int status = process.waitFor(); errorReader.join(1000);
            if (timeout.isDone()) throw new IOException("Root 读取超时，请检查授权后重试");
            if (status != 0) throw new IOException("Root 读取失败：" + errors.toString("UTF-8").trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IOException("读取已取消", e);
        } finally { timeout.cancel(false); timer.shutdownNow(); process.destroyForcibly(); }
    }
    public static void remove(File file) {
        if (file == null) return;
        for (String suffix : new String[]{"", "-wal", "-shm", "-journal"}) {
            File part = new File(file.getPath() + suffix);
            if (part.exists()) part.delete();
        }
    }
}
