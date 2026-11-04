package moe.huzerovo.fncalls.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One line of user configuration: {@code ClassName#method1,method2}.
 */
public final class HookSpec {

    public final String raw;
    public final String className;
    public final List<String> methodPatterns;

    private HookSpec(String raw, String className, List<String> methodPatterns) {
        this.raw = raw;
        this.className = className;
        this.methodPatterns = Collections.unmodifiableList(methodPatterns);
    }

    public static HookSpec parse(String line) {
        if (line == null) {
            return null;
        }
        String value = line.trim();
        if (value.isEmpty() || value.startsWith("#") || value.startsWith("//")) {
            return null;
        }
        int hash = value.lastIndexOf('#');
        if (hash <= 0 || hash == value.length() - 1) {
            return null;
        }
        String className = value.substring(0, hash).trim();
        if (className.isEmpty()) {
            return null;
        }
        List<String> patterns = new ArrayList<>();
        for (String part : value.substring(hash + 1).split(",")) {
            String pattern = part.trim();
            if (!pattern.isEmpty()) {
                patterns.add(pattern);
            }
        }
        if (patterns.isEmpty()) {
            return null;
        }
        return new HookSpec(value, className, patterns);
    }

    public boolean wantsConstructors() {
        for (String pattern : methodPatterns) {
            if ("<init>".equals(pattern) || "*".equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    public boolean matchesClass(String candidate) {
        return candidate != null && globMatches(className, candidate);
    }

    public boolean matches(Member member) {
        String name;
        if (member instanceof Constructor) {
            name = "<init>";
        } else if (member instanceof Method) {
            name = member.getName();
        } else {
            return false;
        }
        for (String pattern : methodPatterns) {
            if (globMatches(pattern, name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Iterative wildcard matching, so no regex has to be compiled for every
     * method in a class.
     */
    public static boolean globMatches(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        int p = 0;
        int v = 0;
        int star = -1;
        int starValue = 0;
        while (v < value.length()) {
            if (p < pattern.length()
                    && (pattern.charAt(p) == '?' || pattern.charAt(p) == value.charAt(v))) {
                p++;
                v++;
            } else if (p < pattern.length() && pattern.charAt(p) == '*') {
                star = p++;
                starValue = v;
            } else if (star >= 0) {
                p = star + 1;
                v = ++starValue;
            } else {
                return false;
            }
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }

    @Override
    public String toString() {
        return raw;
    }
}
