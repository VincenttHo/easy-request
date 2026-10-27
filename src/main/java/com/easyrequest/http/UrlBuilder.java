package com.easyrequest.http;

import com.easyrequest.compat.JavaCompat;

import com.easyrequest.model.KeyValue;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UrlBuilder {
    private static final Pattern VARIABLE = Pattern.compile("\\{([\\w.-]+)(?::(?:[^{}]|\\{[^{}]*})+)?}");
    private UrlBuilder() {}

    public static String joinPath(String... parts) {
        StringBuilder path = new StringBuilder();
        for (String part : parts) {
            if (part == null || JavaCompat.isBlank(part) || part.equals("/")) continue;
            String p = part.trim().replaceAll("^/+|/+$", "");
            if (!p.isEmpty()) path.append('/').append(p);
        }
        if (path.length() == 0) return "/";
        for (int i = parts.length - 1; i >= 0; i--) {
            if (parts[i] == null || JavaCompat.isBlank(parts[i])) continue;
            if (parts[i].trim().endsWith("/")) path.append('/');
            break;
        }
        return path.toString();
    }

    public static URI build(String prefix, String url, List<KeyValue> path, List<KeyValue> query) {
        String target = url.trim();
        if (JavaCompat.isBlank(target)) throw new IllegalArgumentException("请输入接口地址");
        Matcher matcher = VARIABLE.matcher(target);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = path.stream().filter(p -> p.enabled && p.key.equals(name))
                    .map(p -> p.value).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("请填写路径变量：" + name));
            if (JavaCompat.isBlank(value)) throw new IllegalArgumentException("请填写路径变量：" + name);
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(encode(value)));
        }
        matcher.appendTail(expanded);
        target = expanded.toString();
        if (!target.matches("(?i)^https?://.*")) {
            if (target.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) throw new IllegalArgumentException("仅支持 HTTP / HTTPS 地址");
            if (prefix == null || JavaCompat.isBlank(prefix)) throw new IllegalArgumentException("请选择接口前缀，或输入完整的 http(s) 地址");
            target = prefix.trim().replaceAll("/+$", "") + "/" + target.replaceAll("^/+", "");
        }
        if (target.contains("{") || target.contains("}")) throw new IllegalArgumentException("接口地址中存在未解析的变量");
        int fragment = target.indexOf('#');
        if (fragment >= 0) target = target.substring(0, fragment);
        StringBuilder result = new StringBuilder(target.replace(" ", "%20"));
        for (KeyValue pair : query) {
            if (!pair.enabled || JavaCompat.isBlank(pair.key)) continue;
            char separator = result.indexOf("?") >= 0 ? '&' : '?';
            if (result.charAt(result.length() - 1) != '?' && result.charAt(result.length() - 1) != '&') result.append(separator);
            result.append(encode(pair.key)).append('=').append(encode(pair.value));
        }
        URI uri;
        try { uri = URI.create(result.toString()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("URL 格式无效：" + e.getMessage()); }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null)
            throw new IllegalArgumentException("仅支持有效的 HTTP / HTTPS 地址");
        if (uri.getUserInfo() != null) throw new IllegalArgumentException("请使用 Authorization Header 设置身份凭据");
        return uri;
    }

    public static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    public static List<String> variables(String path) {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        Matcher m = VARIABLE.matcher(path);
        while (m.find()) if (!names.contains(m.group(1))) names.add(m.group(1));
        return names;
    }
}
