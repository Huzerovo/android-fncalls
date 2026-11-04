package moe.huzerovo.fncalls.log;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * File layout used by both the provider (writer) and the UI (reader):
 *
 * <pre>files/traces/&lt;package&gt;.log</pre>
 *
 * One rotation backup (&lt;package&gt;.log.1) is kept.
 */
public final class TraceFiles {

    private static final String TAG = "FnCalls";
    private static final Object LOCK = new Object();
    private static final String DIR = "traces";
    private static final String SUFFIX = ".log";

    private TraceFiles() {
    }

    public static File dir(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "无法创建日志目录: " + dir);
        }
        return dir;
    }

    public static File fileFor(Context context, String packageName) {
        return new File(dir(context), sanitize(packageName) + SUFFIX);
    }

    public static List<File> listLogs(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null) {
            return Collections.emptyList();
        }
        List<File> out = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && file.getName().contains(SUFFIX)
                    && (file.getName().endsWith(SUFFIX) || file.getName().endsWith(SUFFIX + ".1"))) {
                out.add(file);
            }
        }
        out.sort(Comparator.comparingLong(File::lastModified).reversed());
        return out;
    }

    public static boolean append(Context context, String packageName, List<String> lines, int maxMb) {
        if (lines == null || lines.isEmpty()) {
            return true;
        }
        synchronized (LOCK) {
            File file = fileFor(context, packageName);
            try {
                long maxBytes = Math.max(1, maxMb) * 1024L * 1024L;
                if (file.exists() && file.length() > maxBytes) {
                    rotate(file);
                }
                try (FileOutputStream out = new FileOutputStream(file, true)) {
                    for (String line : lines) {
                        out.write(trimLine(line).getBytes(StandardCharsets.UTF_8));
                        out.write('\n');
                    }
                }
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "写入日志失败: " + file, t);
                return false;
            }
        }
    }

    public static void clear(Context context, String packageName) {
        delete(fileFor(context, packageName));
    }

    public static void delete(File file) {
        if (file == null) {
            return;
        }
        synchronized (LOCK) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            if (file.getName().endsWith(SUFFIX)) {
                //noinspection ResultOfMethodCallIgnored
                new File(file.getPath() + ".1").delete();
            }
        }
    }

    public static List<String> readTail(File file, int maxLines) {
        if (file == null || !file.isFile() || maxLines <= 0) {
            return Collections.emptyList();
        }
        List<String> reversed = new ArrayList<>();
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            long position = raf.length();
            ByteArrayOutputStream current = new ByteArrayOutputStream();
            final int chunkSize = 64 * 1024;
            byte[] buffer = new byte[chunkSize];

            while (position > 0 && reversed.size() < maxLines) {
                int size = (int) Math.min(chunkSize, position);
                position -= size;
                raf.seek(position);
                raf.readFully(buffer, 0, size);
                for (int i = size - 1; i >= 0 && reversed.size() < maxLines; i--) {
                    byte b = buffer[i];
                    if (b == '\n') {
                        addReversed(reversed, current);
                    } else {
                        current.write(b);
                    }
                }
            }
            if (current.size() > 0 && reversed.size() < maxLines) {
                addReversed(reversed, current);
            }
        } catch (IOException e) {
            Log.w(TAG, "读取日志失败: " + file, e);
            return Collections.emptyList();
        }
        Collections.reverse(reversed);
        return reversed;
    }

    public static boolean isBackup(File file) {
        return file != null && file.getName().endsWith(SUFFIX + ".1");
    }

    public static String appName(File file) {
        if (file == null) {
            return "";
        }
        String name = file.getName();
        if (name.endsWith(SUFFIX + ".1")) {
            return name.substring(0, name.length() - (SUFFIX + ".1").length());
        }
        if (name.endsWith(SUFFIX)) {
            return name.substring(0, name.length() - SUFFIX.length());
        }
        return name;
    }

    public static String displayName(File file) {
        String name = file.getName();
        if (name.endsWith(SUFFIX + ".1")) {
            return appName(file) + "（备份）";
        }
        if (name.endsWith(SUFFIX)) {
            return appName(file);
        }
        return name;
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    public static String sanitize(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return "unknown";
        }
        StringBuilder out = new StringBuilder(packageName.length());
        for (int i = 0; i < packageName.length() && out.length() < 120; i++) {
            char c = packageName.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
                out.append(c);
            } else {
                out.append('_');
            }
        }
        return out.length() == 0 ? "unknown" : out.toString();
    }

    private static void addReversed(List<String> reversed, ByteArrayOutputStream current) {
        byte[] bytes = current.toByteArray();
        for (int left = 0, right = bytes.length - 1; left < right; left++, right--) {
            byte tmp = bytes[left];
            bytes[left] = bytes[right];
            bytes[right] = tmp;
        }
        current.reset();
        String line = new String(bytes, StandardCharsets.UTF_8);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        if (!line.isEmpty()) {
            reversed.add(line);
        }
    }

    private static void rotate(File file) throws IOException {
        File backup = new File(file.getPath() + ".1");
        //noinspection ResultOfMethodCallIgnored
        backup.delete();
        if (!file.renameTo(backup)) {
            throw new IOException("无法轮转日志: " + file);
        }
    }

    private static String trimLine(String line) {
        if (line == null) {
            return "";
        }
        String value = line.replace('\n', ' ').replace('\r', ' ');
        return value.length() > 4096 ? value.substring(0, 4096) + "…" : value;
    }
}
