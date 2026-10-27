package com.easyrequest.http;

import com.easyrequest.compat.JavaCompat;
import com.easyrequest.model.KeyValue;
import com.easyrequest.model.RequestData;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.AbstractHttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import java.io.*;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A Java 8 HTTP client with cancellation, total request deadlines and bounded responses. */
public final class HttpExecutor implements AutoCloseable {
    public static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    private final CloseableHttpClient client = HttpClients.custom().disableRedirectHandling()
            .disableCookieManagement().disableAutomaticRetries().disableContentCompression().build();
    private final ExecutorService workers = Executors.newCachedThreadPool(task -> daemon(task, "EasyRequest HTTP"));
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "EasyRequest timeout"));
    private final Set<CompletableFuture<Result>> active = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
    }

    public static final class Result {
        private final int status;
        private final Map<String, List<String>> headers;
        private final byte[] body;
        private final long millis;
        private final URI uri;
        public Result(int status, Map<String, List<String>> headers, byte[] body, long millis, URI uri) {
            this.status = status; this.headers = headers; this.body = body; this.millis = millis; this.uri = uri;
        }
        public int status() { return status; }
        public Map<String, List<String>> headers() { return headers; }
        public byte[] body() { return body; }
        public long millis() { return millis; }
        public URI uri() { return uri; }
        public String text() {
            Charset charset = StandardCharsets.UTF_8;
            String type = headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("content-type"))
                    .flatMap(e -> e.getValue().stream()).findFirst().orElse("");
            Matcher matcher = Pattern.compile("(?i)charset=[\"']?([^;\\s\"']+)").matcher(type);
            if (matcher.find()) try { charset = Charset.forName(matcher.group(1)); } catch (Exception ignored) {}
            return new String(body, charset);
        }
    }

    public CompletableFuture<Result> send(RequestData request, String prefix, List<KeyValue> globals, int timeout) throws IOException {
        if (closed) throw new IllegalStateException("HTTP 客户端已关闭");
        URI uri = UrlBuilder.build(prefix, request.url, request.pathVariables, request.query);
        Map<String, String> headers = mergeHeaders(request.useGlobalHeaders ? globals : Collections.emptyList(), request.headers);
        HttpEntity body = null;
        String contentType = null;
        switch (request.bodyType) {
            case "JSON": body = text(request.body); contentType = "application/json; charset=UTF-8"; break;
            case "TEXT": body = text(request.body); contentType = "text/plain; charset=UTF-8"; break;
            case "XML": body = text(request.body); contentType = "application/xml; charset=UTF-8"; break;
            case "URLENCODED":
                StringJoiner values = new StringJoiner("&");
                request.form.stream().filter(v -> v.enabled && !JavaCompat.isBlank(v.key))
                        .forEach(v -> values.add(UrlBuilder.encode(v.key) + "=" + UrlBuilder.encode(v.value)));
                body = text(values.toString()); contentType = "application/x-www-form-urlencoded; charset=UTF-8"; break;
            case "MULTIPART":
                String boundary = "EasyRequest" + UUID.randomUUID().toString().replace("-", "");
                body = multipart(request.form, boundary);
                headers.put("Content-Type", "multipart/form-data; boundary=" + boundary); break;
            default: break;
        }
        if (contentType != null) headers.putIfAbsent("Content-Type", contentType);
        HttpUriRequestBase exchange = new HttpUriRequestBase(request.method, uri);
        exchange.setEntity(body);
        exchange.setConfig(RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(Math.max(1, timeout)))
                .setConnectTimeout(Timeout.ofSeconds(Math.min(15, Math.max(1, timeout))))
                .setResponseTimeout(Timeout.ofSeconds(Math.max(1, timeout))).build());
        headers.forEach(exchange::setHeader);
        long started = System.nanoTime();
        CompletableFuture<Result> result = new CompletableFuture<>();
        active.add(result);
        try {
            ScheduledFuture<?> deadline = deadlines.schedule(() -> {
                if (result.completeExceptionally(new SocketTimeoutException("请求超时"))) exchange.cancel();
            }, Math.max(1, timeout), TimeUnit.SECONDS);
            result.whenComplete((value, error) -> {
                deadline.cancel(false); active.remove(result);
                if (error != null) exchange.cancel();
            });
            Future<?> work = workers.submit(() -> {
                if (result.isDone()) return;
                try {
                    Result response = client.execute(exchange, received -> {
                        Map<String, List<String>> responseHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                        for (Header header : received.getHeaders()) responseHeaders.computeIfAbsent(header.getName(), key -> new ArrayList<>()).add(header.getValue());
                        byte[] bytes = new byte[0];
                        if (received.getEntity() != null) {
                            try (InputStream input = received.getEntity().getContent()) { bytes = readResponse(input, exchange); }
                        }
                        return new Result(received.getCode(), responseHeaders, bytes,
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), uri);
                    });
                    result.complete(response);
                } catch (Exception error) { result.completeExceptionally(error); }
            });
            result.whenComplete((value, error) -> { if (error != null) work.cancel(true); });
        } catch (RejectedExecutionException error) {
            exchange.cancel(); active.remove(result); result.completeExceptionally(error);
        }
        return result;
    }

    private static HttpEntity text(String value) { return new ByteArrayEntity(value.getBytes(StandardCharsets.UTF_8), null); }

    private static byte[] readResponse(InputStream input, HttpUriRequestBase exchange) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if ((long) output.size() + count > MAX_RESPONSE_BYTES) {
                exchange.cancel();
                throw new IOException("响应超过 10 MB，已停止接收");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    public static Map<String, String> mergeHeaders(List<KeyValue> globals, List<KeyValue> local) {
        Map<String, String> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (List<KeyValue> rows : Arrays.asList(globals, local)) {
            for (KeyValue row : rows) {
                if (!row.enabled || JavaCompat.isBlank(row.key)) continue;
                String key = row.key.trim();
                if (!key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || row.value.contains("\r") || row.value.contains("\n"))
                    throw new IllegalArgumentException("Header 格式无效：" + key);
                if (Arrays.asList("host", "content-length", "connection", "expect", "upgrade").contains(key.toLowerCase(Locale.ROOT)))
                    throw new IllegalArgumentException(key + " 由 HTTP 客户端自动生成，请移除该 Header");
                result.put(key, row.value);
            }
        }
        return result;
    }

    @FunctionalInterface private interface Part { void write(OutputStream output) throws IOException; }
    private static void addText(List<Part> parts, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); parts.add(output -> output.write(bytes));
    }
    private static HttpEntity multipart(List<KeyValue> values, String boundary) throws IOException {
        List<Part> parts = new ArrayList<>();
        for (KeyValue pair : values) {
            if (!pair.enabled || JavaCompat.isBlank(pair.key)) continue;
            String disposition = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + safeName(pair.key) + "\"";
            if (pair.contentType.contains("\r") || pair.contentType.contains("\n")) throw new IllegalArgumentException("表单 Content-Type 格式无效：" + pair.key);
            if (pair.file) {
                Path file = Paths.get(pair.value);
                if (!Files.isRegularFile(file)) throw new IllegalArgumentException("上传文件不存在：" + pair.value);
                String mime = JavaCompat.isBlank(pair.contentType) ? Files.probeContentType(file) : pair.contentType;
                addText(parts, disposition + "; filename=\"" + safeName(file.getFileName().toString())
                        + "\"\r\nContent-Type: " + (mime == null ? "application/octet-stream" : mime) + "\r\n\r\n");
                parts.add(output -> Files.copy(file, output));
            } else {
                String type = JavaCompat.isBlank(pair.contentType) ? "" : "\r\nContent-Type: " + pair.contentType;
                addText(parts, disposition + type + "\r\n\r\n" + pair.value);
            }
            addText(parts, "\r\n");
        }
        addText(parts, "--" + boundary + "--\r\n");
        return new AbstractHttpEntity((String) null, null, true) {
            @Override public boolean isRepeatable() { return true; }
            @Override public long getContentLength() { return -1; }
            @Override public InputStream getContent() { throw new UnsupportedOperationException("Streaming upload"); }
            @Override public void writeTo(OutputStream output) throws IOException { for (Part part : parts) part.write(output); }
            @Override public boolean isStreaming() { return false; }
            @Override public void close() {}
        };
    }

    private static String safeName(String value) { return value.replace("\r", "").replace("\n", "").replace("\\", "_").replace("\"", "%22"); }
    @Override public void close() {
        closed = true;
        for (CompletableFuture<Result> request : active) request.cancel(true);
        deadlines.shutdownNow(); workers.shutdownNow();
        try { client.close(); } catch (IOException ignored) {}
    }
}
