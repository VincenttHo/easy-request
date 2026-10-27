package com.easyrequest.compat;

import java.util.*;

/** Java 8 equivalents used by every supported IDEA runtime. */
public final class JavaCompat {
    private JavaCompat() {}

    public static boolean isBlank(String value) { return value.codePoints().allMatch(Character::isWhitespace); }
    public static String strip(String value) {
        int start = 0, end = value.length();
        while (start < end && Character.isWhitespace(value.codePointAt(start))) start += Character.charCount(value.codePointAt(start));
        while (end > start && Character.isWhitespace(value.codePointBefore(end))) end -= Character.charCount(value.codePointBefore(end));
        return value.substring(start, end);
    }
    public static String repeat(String value, int count) {
        if (count < 0) throw new IllegalArgumentException("Negative repeat count");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
    @SafeVarargs public static <T> List<T> list(T... values) {
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(values)));
    }
    @SafeVarargs public static <T> Set<T> set(T... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(values)));
    }
    public static <K, V> Map<K, V> map() { return Collections.emptyMap(); }
    public static <K, V> Map<K, V> map(K key, V value) { return Collections.singletonMap(key, value); }
    public static <T> List<T> copyList(Collection<? extends T> values) { return Collections.unmodifiableList(new ArrayList<>(values)); }
    public static <T> Set<T> copySet(Collection<? extends T> values) { return Collections.unmodifiableSet(new LinkedHashSet<>(values)); }
    public static <K, V> Map<K, V> copyMap(Map<? extends K, ? extends V> values) { return Collections.unmodifiableMap(new LinkedHashMap<>(values)); }
    public static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    public static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
