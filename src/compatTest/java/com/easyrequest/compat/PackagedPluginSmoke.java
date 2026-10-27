package com.easyrequest.compat;

import com.easyrequest.controller.SpringConfig;
import com.easyrequest.http.HttpExecutor;
import com.easyrequest.model.RequestData;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs with only the distributed plugin JAR on a real Java 8 runtime. */
public final class PackagedPluginSmoke {
    public static void main(String[] args) throws Exception {
        check("1.8".equals(System.getProperty("java.specification.version")), "Use Java 8 for this check");
        SpringConfig.Source source = new SpringConfig.Source("application.yml",
                "server:\n  port: 8123\n  servlet:\n    context-path: /demo\n");
        SpringConfig.Result config = SpringConfig.read(Collections.singletonList(source), "");
        check(config.warnings().isEmpty(), "YAML failed: " + config.warnings());
        check("8123".equals(config.properties().get("server.port")), "YAML port missing");
        check("/demo".equals(config.contextPath()), "YAML context path missing");
        Object json = Class.forName("com.easyrequest.internal.gson.JsonParser")
                .getMethod("parseString", String.class).invoke(null, "{\"ok\":true}");
        check(json.toString().contains("true"), "Shaded JSON parser failed");
        check(JavaCompat.isBlank("\u2003"), "Unicode whitespace changed");
        check("value".equals(JavaCompat.strip("\u2003value\u2003")), "Unicode strip changed");

        AtomicReference<String> method = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            method.set(exchange.getRequestMethod());
            byte[] body = "{\"text\":\"中文\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try (HttpExecutor client = new HttpExecutor()) {
            RequestData request = new RequestData(); request.url = "/echo"; request.method = "PATCH";
            request.bodyType = "JSON"; request.body = "{\"enabled\":true}";
            HttpExecutor.Result response = client.send(request, "http://127.0.0.1:" + server.getAddress().getPort(),
                    Collections.emptyList(), 5).get(10, TimeUnit.SECONDS);
            check(response.status() == 201, "HTTP response failed");
            check("PATCH".equals(method.get()), "PATCH method changed");
            check(response.text().contains("中文"), "UTF-8 response changed");
        } finally { server.stop(0); }
        System.out.println("Java 8 packaged-plugin smoke passed: YAML, JSON, Unicode and HTTP PATCH.");
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
