package com.easyrequest.controller;

import com.easyrequest.compat.JavaCompat;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.StringReader;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure configuration parser, shared by the IDE integration and its tests. */
public final class SpringConfig {
    public static final class Source {
        private final String name;
        private final String text;
        private final String id;
        private final String profile;
        public Source(String name, String text, String id, String profile) {
            this.name = name;
            this.text = text;
            this.id = id;
            this.profile = profile;
        }
        public String name() { return name; }
        public String text() { return text; }
        public String id() { return id; }
        public String profile() { return profile; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Source)) return false;
            Source that = (Source) other;
            return java.util.Objects.equals(name, that.name) && java.util.Objects.equals(text, that.text) && java.util.Objects.equals(id, that.id) && java.util.Objects.equals(profile, that.profile);
        }
        @Override public int hashCode() { return java.util.Objects.hash(name, text, id, profile); }
        @Override public String toString() { return "Source[" + "name=" + name + ", " + "text=" + text + ", " + "id=" + id + ", " + "profile=" + profile + "]"; }
        public Source(String name, String text) { this(name, text, name, fileProfile(name)); }
    }
    @FunctionalInterface public interface ImportResolver {
        List<Source> resolve(Source referring, String location, List<String> profiles) throws Exception;
    }
    public static final class Result {
        private final Map<String, String> properties;
        private final List<String> warnings;
        public Result(Map<String, String> properties, List<String> warnings) {
            this.properties = properties;
            this.warnings = warnings;
        }
        public Map<String, String> properties() { return properties; }
        public List<String> warnings() { return warnings; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Result)) return false;
            Result that = (Result) other;
            return java.util.Objects.equals(properties, that.properties) && java.util.Objects.equals(warnings, that.warnings);
        }
        @Override public int hashCode() { return java.util.Objects.hash(properties, warnings); }
        @Override public String toString() { return "Result[" + "properties=" + properties + ", " + "warnings=" + warnings + "]"; }
        public String resolve(String value) { return resolveValue(value, properties); }
        public String contextPath() { return resolve(properties.getOrDefault("server.servlet.context-path", "")); }
    }
    private static final class Document {
        private final Source source;
        private final Map<String, String> values;
        private final String fileProfile;
        private final String activation;
        private Document(Source source, Map<String, String> values, String fileProfile, String activation) {
            this.source = source;
            this.values = values;
            this.fileProfile = fileProfile;
            this.activation = activation;
        }
        public Source source() { return source; }
        public Map<String, String> values() { return values; }
        public String fileProfile() { return fileProfile; }
        public String activation() { return activation; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Document)) return false;
            Document that = (Document) other;
            return java.util.Objects.equals(source, that.source) && java.util.Objects.equals(values, that.values) && java.util.Objects.equals(fileProfile, that.fileProfile) && java.util.Objects.equals(activation, that.activation);
        }
        @Override public int hashCode() { return java.util.Objects.hash(source, values, fileProfile, activation); }
        @Override public String toString() { return "Document[" + "source=" + source + ", " + "values=" + values + ", " + "fileProfile=" + fileProfile + ", " + "activation=" + activation + "]"; }
        boolean base() { return JavaCompat.isBlank(fileProfile) && JavaCompat.isBlank(activation); }
        boolean activeFor(String profile, Set<String> active) {
            if (!JavaCompat.isBlank(fileProfile) && !active.contains(fileProfile)) return false;
            if (!JavaCompat.isBlank(activation) && active.stream().noneMatch(p -> matches(activation, p))) return false;
            return fileProfile.equals(profile) || (JavaCompat.isBlank(fileProfile) && matches(activation, profile));
        }
    }
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^{}]+)}");
    private SpringConfig() {}

    public static Result read(List<Source> sources, String selectedProfile) {
        return read(sources, selectedProfile, (referring, location, profiles) -> JavaCompat.list());
    }

    public static Result read(List<Source> sources, String selectedProfile, ImportResolver imports) {
        List<String> warnings = new ArrayList<>();
        List<Document> documents = new ArrayList<>();
        sources.stream().sorted(Comparator.comparingInt(s -> priority(s.name))).forEach(source -> documents.addAll(parse(source, warnings)));
        Map<String, String> base = new LinkedHashMap<>();
        apply(documents, base, JavaCompat.set(), imports, new LinkedHashSet<>(), warnings, 0);
        String active = JavaCompat.isBlank(selectedProfile) ? resolveValue(base.getOrDefault("spring.profiles.active",
                base.getOrDefault("spring.profiles.default", "default")), base) : selectedProfile;
        Set<String> profiles = new LinkedHashSet<>();
        String includes = resolveValue(base.getOrDefault("spring.profiles.include", ""), base);
        for (String profile : includes.split(",")) if (!JavaCompat.isBlank(profile)) profiles.add(profile.trim());
        for (String profile : active.split(",")) if (!JavaCompat.isBlank(profile)) profiles.add(profile.trim());
        Map<String, String> result = new LinkedHashMap<>();
        apply(documents, result, profiles, imports, new LinkedHashSet<>(), warnings, 0);
        return new Result(result, new ArrayList<>(new LinkedHashSet<>(warnings)));
    }

    private static void apply(List<Document> documents, Map<String, String> target, Set<String> profiles,
                              ImportResolver imports, Set<String> stack, List<String> warnings, int depth) {
        List<Document> ordered = new ArrayList<>(documents.stream().filter(Document::base).collect(java.util.stream.Collectors.toList()));
        for (String profile : profiles) documents.stream().filter(d -> !d.base() && d.activeFor(profile, profiles)).forEach(ordered::add);
        for (Document document : ordered) {
            target.putAll(document.values);
            String locations = document.values.getOrDefault("spring.config.import", "");
            if (JavaCompat.isBlank(locations)) continue;
            if (depth >= 16 || !stack.add(document.source.id)) {
                warnings.add("配置引用循环或层数过多：" + document.source.name); continue;
            }
            try {
                for (String location : resolveValue(locations, target).split(",")) {
                    if (JavaCompat.isBlank(location)) continue;
                    try {
                        List<Document> imported = new ArrayList<>();
                        for (Source source : imports.resolve(document.source, location.trim(), JavaCompat.copyList(profiles))) {
                            if (stack.contains(source.id)) { warnings.add("配置引用循环：" + source.name); continue; }
                            imported.addAll(parse(source, warnings));
                        }
                        apply(imported, target, profiles, imports, stack, warnings, depth + 1);
                    } catch (Exception e) { warnings.add("无法读取引用配置 " + location.trim() + "：" + e.getMessage()); }
                }
            } finally { stack.remove(document.source.id); }
        }
    }

    private static List<Document> parse(Source source, List<String> warnings) {
        List<Document> documents = new ArrayList<>();
            String fileProfile = source.profile;
            try {
                if (source.name.endsWith(".properties")) {
                    Properties properties = new Properties();
                    properties.load(new StringReader(source.text));
                    Map<String, String> flat = new LinkedHashMap<>();
                    properties.forEach((k, v) -> flat.put(k.toString(), v.toString()));
                    documents.add(new Document(source, flat, fileProfile, ""));
                } else {
                    LoaderOptions options = new LoaderOptions();
                    options.setCodePointLimit(1_000_000);
                    options.setMaxAliasesForCollections(30);
                    for (Object value : new Yaml(new SafeConstructor(options)).loadAll(source.text)) {
                        Map<String, String> flat = new LinkedHashMap<>();
                        flatten("", value, flat);
                        String activation = flat.getOrDefault("spring.config.activate.on-profile", flat.getOrDefault("spring.profiles", ""));
                        documents.add(new Document(source, flat, fileProfile, activation));
                    }
                }
            } catch (Exception e) { warnings.add(source.name + " 解析失败：" + e.getMessage()); }
        return documents;
    }

    private static boolean matches(String condition, String profile) {
        return Arrays.stream(condition.split("[,|]")).map(String::trim).anyMatch(profile::equals);
    }

    private static void flatten(String prefix, Object value, Map<String, String> target) {
        if (value instanceof Map<?, ?>) ((Map<?, ?>) value).forEach((key, item) -> flatten(prefix.isEmpty() ? key.toString() : prefix + "." + key, item, target));
        else if (value instanceof List<?>) target.put(prefix, String.join(",", ((List<?>) value).stream().map(String::valueOf).collect(java.util.stream.Collectors.toList())));
        else if (value != null) target.put(prefix, value.toString());
    }

    private static String fileProfile(String name) {
        if (!name.startsWith("application-") && !name.startsWith("bootstrap-")) return "";
        String stem = name.substring(0, name.lastIndexOf('.'));
        int dash = stem.indexOf('-');
        return dash < 0 ? "" : stem.substring(dash + 1);
    }

    private static int priority(String name) {
        return (JavaCompat.isBlank(fileProfile(name)) ? 0 : 10) + (name.startsWith("application") ? 4 : 0)
                + (name.endsWith(".properties") ? 2 : name.endsWith(".yaml") ? 1 : 0);
    }

    public static String resolveValue(String value, Map<String, String> values) {
        String current = value;
        for (int depth = 0; depth < 10; depth++) {
            Matcher matcher = PLACEHOLDER.matcher(current);
            StringBuffer out = new StringBuffer();
            boolean changed = false;
            while (matcher.find()) {
                String[] parts = matcher.group(1).split(":", 2);
                String replacement = values.get(parts[0]);
                if (replacement == null) replacement = System.getenv(parts[0]);
                if (replacement == null && parts.length > 1) replacement = parts[1];
                if (replacement == null) replacement = matcher.group();
                changed |= !replacement.equals(matcher.group());
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(out);
            current = out.toString();
            if (!changed) break;
        }
        return current;
    }
}
