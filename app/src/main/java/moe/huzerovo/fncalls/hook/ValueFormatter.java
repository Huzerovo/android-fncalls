package moe.huzerovo.fncalls.hook;

import moe.huzerovo.fncalls.BuildConfig;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Converts hook arguments / return values into bounded, single-line text.
 * Formatting must never throw and must never recurse without bound.
 */
public final class ValueFormatter {

    private static final int MAX_ARGS_LENGTH = 1024;
    private static final String MODULE_PREFIX = BuildConfig.APPLICATION_ID + ".";
    private static final ThreadLocal<Integer> FORMAT_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<SimpleDateFormat> TIME_FORMAT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss.SSS", Locale.US));

    private ValueFormatter() {
    }

    public static String time() {
        SimpleDateFormat format = TIME_FORMAT.get();
        if (format == null) {
            format = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
            TIME_FORMAT.set(format);
        }
        return format.format(new Date());
    }

    public static String signature(Member member) {
        if (member == null) {
            return "<unknown>";
        }
        String owner = member.getDeclaringClass().getName();
        String name = member instanceof Constructor ? "<init>" : member.getName();
        StringBuilder out = new StringBuilder(owner).append('#').append(name).append('(');
        Class<?>[] params;
        if (member instanceof Method) {
            params = ((Method) member).getParameterTypes();
        } else if (member instanceof Constructor) {
            params = ((Constructor<?>) member).getParameterTypes();
        } else {
            params = new Class<?>[0];
        }
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(typeName(params[i]));
        }
        return out.append(')').toString();
    }

    public static String receiver(Object object) {
        if (object == null) {
            return "null";
        }
        try {
            return object.getClass().getSimpleName() + '@'
                    + Integer.toHexString(System.identityHashCode(object));
        } catch (Throwable t) {
            return "<receiver>";
        }
    }

    public static String arguments(Object[] args) {
        if (args == null || args.length == 0) {
            return "()";
        }
        StringBuilder out = new StringBuilder("(");
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(format(args[i], 200));
            if (out.length() > MAX_ARGS_LENGTH) {
                out.append(", …");
                break;
            }
        }
        return out.append(')').toString();
    }

    public static String format(Object value) {
        return format(value, 512);
    }

    public static String format(Object value, int maxLength) {
        int depth = FORMAT_DEPTH.get() == null ? 0 : FORMAT_DEPTH.get();
        if (depth > 3) {
            return "…";
        }
        FORMAT_DEPTH.set(depth + 1);
        try {
            String text = formatInner(value);
            return truncate(text, maxLength);
        } catch (Throwable t) {
            return "<" + t.getClass().getSimpleName() + ">";
        } finally {
            FORMAT_DEPTH.set(depth);
        }
    }

    public static String stack(int depth) {
        if (depth <= 0) {
            return "";
        }
        List<String> frames = new ArrayList<>(depth);
        try {
            for (StackTraceElement element : new Throwable().getStackTrace()) {
                String className = element.getClassName();
                if (isTracerFrame(className)) {
                    continue;
                }
                frames.add(element.toString());
                if (frames.size() >= depth) {
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return String.join(" <- ", frames);
    }

    public static String indent(int depth) {
        int count = Math.min(depth, 16) * 2;
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            out.append(' ');
        }
        return out.toString();
    }

    public static String typeName(Class<?> type) {
        if (type == null) {
            return "?";
        }
        if (type.isArray()) {
            return typeName(type.getComponentType()) + "[]";
        }
        String simple = type.getSimpleName();
        return simple.isEmpty() ? type.getName() : simple;
    }

    private static String formatInner(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return quoted((String) value);
        }
        if (value instanceof CharSequence) {
            CharSequence sequence = (CharSequence) value;
            String text = sequence.length() > 4096
                    ? sequence.subSequence(0, 4096).toString() + "…"
                    : sequence.toString();
            return quoted(text);
        }
        if (value instanceof Character) {
            return "'" + escape(String.valueOf(value)) + "'";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        if (value instanceof Class) {
            return "Class<" + ((Class<?>) value).getName() + ">";
        }
        if (value instanceof Throwable) {
            Throwable throwable = (Throwable) value;
            String message = throwable.getMessage();
            return throwable.getClass().getName() + (message == null ? "" : ": " + message);
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            return formatArray(value);
        }
        if (value instanceof Map) {
            return formatMap((Map<?, ?>) value);
        }
        if (value instanceof Collection) {
            return formatCollection((Collection<?>) value);
        }
        if (value instanceof Enum) {
            return ((Enum<?>) value).name();
        }
        return safeToString(value);
    }

    private static String formatArray(Object array) {
        int length = Array.getLength(array);
        StringBuilder out = new StringBuilder();
        out.append(typeName(array.getClass().getComponentType()))
                .append('[').append(length).append("]{");
        int shown = Math.min(length, 8);
        for (int i = 0; i < shown; i++) {
            if (i > 0) {
                out.append(", ");
            }
            if (array instanceof byte[]) {
                out.append(String.format(Locale.US, "0x%02x", ((byte[]) array)[i] & 0xff));
            } else {
                out.append(format(Array.get(array, i), 64));
            }
        }
        if (shown < length) {
            out.append(", …");
        }
        return out.append('}').toString();
    }

    private static String formatCollection(Collection<?> collection) {
        StringBuilder out = new StringBuilder();
        out.append(typeName(collection.getClass())).append('[').append(collection.size()).append("]{");
        int shown = 0;
        for (Object item : collection) {
            if (shown > 0) {
                out.append(", ");
            }
            out.append(format(item, 96));
            if (++shown >= 5) {
                break;
            }
        }
        if (collection.size() > shown) {
            out.append(", …");
        }
        return out.append('}').toString();
    }

    private static String formatMap(Map<?, ?> map) {
        StringBuilder out = new StringBuilder();
        out.append(typeName(map.getClass())).append('[').append(map.size()).append("]{");
        int shown = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (shown > 0) {
                out.append(", ");
            }
            out.append(format(entry.getKey(), 64)).append('=')
                    .append(format(entry.getValue(), 96));
            if (++shown >= 5) {
                break;
            }
        }
        if (map.size() > shown) {
            out.append(", …");
        }
        return out.append('}').toString();
    }

    private static String safeToString(Object value) {
        try {
            String text = value.toString();
            if (text == null) {
                return "null";
            }
            return text.length() > 4096 ? text.substring(0, 4096) + "…" : text;
        } catch (Throwable t) {
            return value.getClass().getName() + "@"
                    + Integer.toHexString(System.identityHashCode(value));
        }
    }

    private static String quoted(String value) {
        return '"' + escape(value) + '"';
    }

    private static String escape(String value) {
        int limit = Math.min(value.length(), 4096);
        StringBuilder out = new StringBuilder(Math.min(limit + 8, 512));
        for (int i = 0; i < limit; i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        if (value.length() > limit) {
            out.append('…');
        }
        return out.toString();
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "null";
        }
        if (maxLength <= 0 || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "…";
    }

    private static boolean isTracerFrame(String className) {
        return className.startsWith(MODULE_PREFIX)
                || className.startsWith("de.robv.android.xposed.")
                || className.startsWith("io.github.libxposed.")
                || className.startsWith("org.lsposed.")
                || className.startsWith("java.lang.reflect.")
                || className.startsWith("com.android.internal.os.");
    }
}
