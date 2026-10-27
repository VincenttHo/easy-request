package com.easyrequest.model;

import com.easyrequest.compat.JavaCompat;

import java.util.UUID;

public final class UrlPrefix {
    public static final String AUTO_LOCAL_ID = "easy-request:auto-local";
    public String id = UUID.randomUUID().toString();
    public String name = "";
    public String url = "";
    public boolean enabled = true;
    public UrlPrefix() {}
    public UrlPrefix(String name, String url) { this.name = name; this.url = url; }
    public boolean isAutoLocal() { return AUTO_LOCAL_ID.equals(id); }
    public static UrlPrefix autoLocal() {
        UrlPrefix prefix = new UrlPrefix("本地（自动）", "读取应用配置…");
        prefix.id = AUTO_LOCAL_ID;
        return prefix;
    }
    @Override public String toString() { return JavaCompat.isBlank(name) ? "无前缀（完整 URL）" : name + "  ·  " + url; }
}
