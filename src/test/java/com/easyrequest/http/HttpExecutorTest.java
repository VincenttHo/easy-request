package com.easyrequest.http;

import com.easyrequest.model.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class HttpExecutorTest {
    private HttpServer server;
    private ExecutorService serverThreads;
    private HttpExecutor client;
    private String base;
    private volatile String receivedMethod, receivedUri, receivedBody, receivedToken, contentType;
    @Before public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverThreads = Executors.newCachedThreadPool(); server.setExecutor(serverThreads);
        server.createContext("/echo", exchange -> {
            receivedMethod = exchange.getRequestMethod(); receivedUri = exchange.getRequestURI().toString();
            receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            receivedToken = exchange.getRequestHeaders().getFirst("X-Token"); contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            byte[] bytes = "{\"ok\":true,\"text\":\"中文\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(201, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/slow", exchange -> {
            try { Thread.sleep(2500); exchange.sendResponseHeaders(204, -1); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } finally { exchange.close(); }
        });
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().set("Location", "/echo"); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.createContext("/large", exchange -> {
            try { byte[] bytes = new byte[HttpExecutor.MAX_RESPONSE_BYTES + 1]; exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
            catch (java.io.IOException ignored) {} finally { exchange.close(); }
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort(); client = new HttpExecutor();
    }
    @After public void stop() { client.close(); server.stop(0); serverThreads.shutdownNow(); }
    @Test public void sendsJsonQueryAndOverridesGlobalHeaderCaseInsensitively() throws Exception {
        RequestData request = new RequestData(); request.method = "POST"; request.url = "/echo/{id}";
        request.pathVariables.add(new KeyValue("id", "42")); request.query.add(new KeyValue("q", "a&b"));
        request.bodyType = "JSON"; request.body = "{\"name\":\"测试\"}"; request.headers.add(new KeyValue("x-token", "local"));
        request.bodyDescriptions.put("/name", "姓名说明，不应发送");
        request.query.getFirst().description = "查询说明，不应发送";
        request.pathVariables.getFirst().description = "路径说明，不应发送";
        request.headers.getFirst().description = "凭据说明，不应发送";
        var result = client.send(request, base, List.of(new KeyValue("X-Token", "global")), 5).get(10, TimeUnit.SECONDS);
        assertEquals(201, result.status()); assertTrue(result.text().contains("中文"));
        assertEquals("POST", receivedMethod); assertEquals("/echo/42?q=a%26b", receivedUri);
        assertEquals(request.body, receivedBody); assertEquals("local", receivedToken); assertTrue(contentType.startsWith("application/json"));
    }
    @Test public void sendsUrlencodedFormAndCanDisableSharedHeaders() throws Exception {
        RequestData r = new RequestData(); r.url = "/echo"; r.method = "PUT"; r.bodyType = "URLENCODED"; r.useGlobalHeaders = false;
        r.form.add(new KeyValue("a", "x y+z"));
        r.form.getFirst().description = "表单说明，不应发送";
        client.send(r, base, List.of(new KeyValue("X-Token", "hidden")), 5).get();
        assertEquals("a=x%20y%2Bz", receivedBody); assertNull(receivedToken);
    }
    @Test public void multipartContainsTextFileAndMatchingBoundary() throws Exception {
        var file = Files.createTempFile("easy-request-test", ".txt");
        try {
            Files.writeString(file, "upload-content"); RequestData r = new RequestData(); r.method = "POST"; r.url = "/echo"; r.bodyType = "MULTIPART";
            r.form.add(new KeyValue("title", "hello")); KeyValue upload = new KeyValue("upload", file.toString()); upload.file = true; r.form.add(upload);
            KeyValue metadata = new KeyValue("metadata", "{\"id\":1}"); metadata.contentType = "application/json"; r.form.add(metadata);
            metadata.description = "元数据说明，不应发送";
            r.headers.add(new KeyValue("Content-Type", "multipart/form-data"));
            client.send(r, base, List.of(), 5).get();
            assertTrue(receivedBody.contains("upload-content")); assertTrue(receivedBody.contains("name=\"title\"\r\n\r\nhello"));
            assertTrue(receivedBody.contains("name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n{\"id\":1}"));
            assertFalse(receivedBody.contains("元数据说明")); assertFalse(receivedBody.contains("description"));
            String boundary = contentType.split("boundary=")[1]; assertTrue(receivedBody.endsWith("--" + boundary + "--\r\n"));
        } finally { Files.deleteIfExists(file); }
    }
    @Test public void respectsTimeoutAndCancellation() throws Exception {
        RequestData request = new RequestData(); request.url = "/slow";
        var timeout = client.send(request, base, List.of(), 1);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> timeout.get(5, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof java.net.SocketTimeoutException);
        var cancelled = client.send(request, base, List.of(), 5); assertTrue(cancelled.cancel(true)); assertTrue(cancelled.isCancelled());
    }
    @Test public void showsRedirectWithoutFollowingIt() throws Exception {
        RequestData r = new RequestData(); r.url = "/redirect";
        assertEquals(302, client.send(r, base, List.of(), 5).get().status()); assertNull(receivedMethod);
    }
    @Test public void limitsResponseSize() throws Exception {
        RequestData r = new RequestData(); r.url = "/large";
        assertThrows(ExecutionException.class, () -> client.send(r, base, List.of(), 5).get(10, TimeUnit.SECONDS));
    }
    @Test public void supportsPatchRequestsOnTheJava8Client() throws Exception {
        RequestData request = new RequestData(); request.method = "PATCH"; request.url = "/echo";
        request.bodyType = "JSON"; request.body = "{\"enabled\":true}";
        assertEquals(201, client.send(request, base, List.of(), 5).get().status());
        assertEquals("PATCH", receivedMethod); assertEquals(request.body, receivedBody);
    }
    @Test public void closingClientCancelsRequestsAndRejectsNewWork() throws Exception {
        RequestData request = new RequestData(); request.url = "/slow";
        var pending = client.send(request, base, List.of(), 10);
        client.close();
        assertTrue(pending.isCancelled());
        assertThrows(IllegalStateException.class, () -> client.send(request, base, List.of(), 5));
    }
    @Test public void timeoutCoversTheWholeBodyEvenWhenDataKeepsArriving() throws Exception {
        server.createContext("/trickle", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try {
                for (int i = 0; i < 30; i++) {
                    exchange.getResponseBody().write('x'); exchange.getResponseBody().flush(); Thread.sleep(100);
                }
            } catch (java.io.IOException ignored) {
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        RequestData request = new RequestData(); request.url = "/trickle";
        var response = client.send(request, base, List.of(), 1);
        ExecutionException error = assertThrows(ExecutionException.class, () -> response.get(2, TimeUnit.SECONDS));
        assertTrue(error.getCause() instanceof java.net.SocketTimeoutException);
    }
    @Test public void validatesHeadersBeforeSending() {
        assertThrows(IllegalArgumentException.class, () -> HttpExecutor.mergeHeaders(List.of(new KeyValue("X-Test", "x\r\ny")), List.of()));
        assertThrows(IllegalArgumentException.class, () -> HttpExecutor.mergeHeaders(List.of(), List.of(new KeyValue("Host", "example.com"))));
    }
}
