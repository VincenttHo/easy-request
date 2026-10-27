package com.easyrequest.controller;

import com.easyrequest.model.*;
import com.easyrequest.persistence.RequestStore;
import com.easyrequest.ui.RequestPanel;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.*;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import java.util.List;

public class ControllerIntegrationTest extends LightJavaCodeInsightFixtureTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("package java.lang; public final class String {}");
        for (String annotation : List.of("RequestMapping", "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping")) {
            myFixture.addClass("package org.springframework.web.bind.annotation; public @interface " + annotation
                    + " { String[] value() default {}; String[] path() default {}; RequestMethod[] method() default {}; String[] params() default {}; String[] headers() default {}; String[] consumes() default {}; }");
        }
        myFixture.addClass("package org.springframework.web.bind.annotation; public enum RequestMethod {GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS}");
        for (String annotation : List.of("PathVariable", "RequestParam", "RequestHeader", "RequestBody", "RequestPart", "CookieValue", "ModelAttribute")) {
            myFixture.addClass("package org.springframework.web.bind.annotation; public @interface " + annotation
                    + " { String value() default \"\"; String name() default \"\"; String defaultValue() default \"\"; boolean required() default true; }");
        }
        myFixture.addClass("package org.springframework.web.multipart; public interface MultipartFile {}");
        myFixture.addClass("package com.fasterxml.jackson.annotation; public @interface JsonProperty { String value(); }");
        myFixture.addClass("package com.fasterxml.jackson.annotation; public @interface JsonIgnore { boolean value() default true; }");
        myFixture.addClass("package io.swagger.annotations; public @interface ApiOperation { String value() default \"\"; String notes() default \"\"; }");
        myFixture.addClass("package io.swagger.v3.oas.annotations; public @interface Operation { String summary() default \"\"; String description() default \"\"; }");
        myFixture.addClass("package io.swagger.v3.oas.annotations.media; public @interface Schema { String description() default \"\"; String example() default \"\"; }");
        myFixture.addClass("package io.swagger.annotations; public @interface ApiModelProperty { String value() default \"\"; String notes() default \"\"; String example() default \"\"; }");
        myFixture.addClass("package io.swagger.v3.oas.annotations; public @interface Parameter { String description() default \"\"; }");
        myFixture.addClass("package io.swagger.annotations; public @interface ApiParam { String value() default \"\"; }");
    }
    private PsiMethod method(String code, String name) {
        myFixture.configureByText("Demo.java", "import org.springframework.web.bind.annotation.*;\n" + code);
        PsiJavaFile file = (PsiJavaFile) myFixture.getFile();
        return file.getClasses()[0].findMethodsByName(name, false)[0];
    }
    public void testContextPathMultipleRoutesAndParameters() {
        myFixture.addFileToProject("application.properties", "server.servlet.context-path=/service");
        PsiMethod method = method("""
                @RequestMapping("/users") class Demo {
                  @GetMapping(path={"/{id}", "/detail/{id}"}, params="version=1")
                  public void get(@PathVariable("id") long userId, @RequestParam(defaultValue="10") int size, @RequestHeader("X-Tenant") String tenant) {}
                }
                """, "get");
        var endpoints = SpringEndpointResolver.resolve(method);
        assertEquals(2, endpoints.size()); var request = endpoints.getFirst().request();
        assertEquals("/service/users/{id}", request.url); assertEquals("GET", request.method);
        assertEquals("id", request.pathVariables.getFirst().key);
        assertTrue(request.query.stream().anyMatch(v -> v.key.equals("size") && v.value.equals("10")));
        assertTrue(request.query.stream().anyMatch(v -> v.key.equals("version") && v.value.equals("1")));
        assertEquals("X-Tenant", request.headers.getFirst().key);
    }
    public void testJsonBodyHandlesRecursionAndJacksonNames() {
        PsiMethod method = method("""
                class Demo {
                  @PostMapping("/save") public void save(@RequestBody Payload payload) {}
                }
                class Payload {
                  @com.fasterxml.jackson.annotation.JsonProperty("display_name") String name;
                  @com.fasterxml.jackson.annotation.JsonIgnore String secret;
                  int count;
                  Payload child;
                  static String CONSTANT;
                }
                """, "save");
        var request = SpringEndpointResolver.resolve(method).getFirst().request();
        assertEquals("JSON", request.bodyType);
        var json = com.google.gson.JsonParser.parseString(request.body).getAsJsonObject();
        assertTrue(json.has("display_name")); assertFalse(json.has("secret")); assertFalse(json.has("CONSTANT"));
        assertEquals(1, json.get("count").getAsInt()); assertTrue(json.get("child").isJsonNull());
    }
    public void testInheritedMappingAndParameterAnnotations() {
        myFixture.addClass("""
                import org.springframework.web.bind.annotation.*;
                @RequestMapping("/base") interface Api {
                  @GetMapping("/{id}") void fetch(@PathVariable("id") int id);
                }
                """);
        PsiMethod method = method("class Demo implements Api { public void fetch(int number) {} }", "fetch");
        assertTrue(SpringEndpointResolver.hasMapping(method));
        var request = SpringEndpointResolver.resolve(method).getFirst().request();
        assertEquals("/base/{id}", request.url); assertEquals("id", request.pathVariables.getFirst().key);
    }
    public void testMultipartAndFormObjectExamples() {
        PsiMethod method = method("""
                class Demo {
                  @PostMapping(value="/upload", consumes="multipart/form-data")
                  public void upload(@RequestParam("file") org.springframework.web.multipart.MultipartFile file, @RequestParam("title") String title) {}
                }
                """, "upload");
        var request = SpringEndpointResolver.resolve(method).getFirst().request();
        assertEquals("MULTIPART", request.bodyType); assertEquals(2, request.form.size());
        assertTrue(request.form.stream().anyMatch(v -> v.file && v.key.equals("file")));
        assertTrue(request.query.isEmpty());
    }
    public void testRequestMappingHttpMethodsAndConstants() {
        PsiMethod method = method("""
                class Demo {
                  static final String PREFIX = "/v1";
                  @RequestMapping(value=PREFIX + "/items", method={RequestMethod.POST, RequestMethod.PUT})
                  public void submit(@RequestParam("name") String name) {}
                }
                """, "submit");
        var endpoints = SpringEndpointResolver.resolve(method);
        assertEquals(List.of("POST", "PUT"), endpoints.stream().map(e -> e.request().method).toList());
        assertEquals("/v1/items", endpoints.getFirst().request().url);
    }
    public void testSwaggerTitleSupportsConstantsAndMultipleRoutes() {
        PsiMethod method = method("""
                class Demo {
                  static final String TITLE = "纸质回收";
                  @io.swagger.annotations.ApiOperation(value = TITLE + "患者列表")
                  @GetMapping({"/visits", "/visits/list"})
                  public void list() {}
                }
                """, "list");
        var endpoints = SpringEndpointResolver.resolve(method);
        assertEquals(2, endpoints.size());
        for (var endpoint : endpoints) {
            assertEquals("纸质回收患者列表", endpoint.request().name);
            assertEquals("Demo#list()", endpoint.request().sourceMethod);
            assertEquals("Demo#list()", endpoint.request().copy().sourceMethod);
        }
    }
    public void testOpenApiSummaryTakesPriorityAndBlankSummaryFallsBackToSwagger() {
        PsiMethod method = method("""
                class Demo {
                  @io.swagger.v3.oas.annotations.Operation(summary = "  用户查询  ", description = "这是一段详细说明")
                  @io.swagger.annotations.ApiOperation("旧版查询标题")
                  @GetMapping("/users") public void list() {}
                }
                """, "list");
        assertEquals("用户查询", SpringEndpointResolver.resolve(method).getFirst().request().name);
        method = method("""
                class Demo {
                  @io.swagger.v3.oas.annotations.Operation(summary = " ")
                  @io.swagger.annotations.ApiOperation("用户列表")
                  @GetMapping("/users") public void list() {}
                }
                """, "list");
        assertEquals("用户列表", SpringEndpointResolver.resolve(method).getFirst().request().name);
    }
    public void testSwaggerTitleIsInheritedAndImplementationTitleOverridesIt() {
        myFixture.addClass("""
                interface Api {
                  @io.swagger.v3.oas.annotations.Operation(summary = "接口上定义的标题")
                  void fetch();
                }
                """);
        myFixture.addClass("abstract class Base implements Api { public abstract void fetch(); }");
        PsiMethod method = method("""
                class Demo extends Base {
                  @GetMapping("/fetch") public void fetch() {}
                }
                """, "fetch");
        assertEquals("接口上定义的标题", SpringEndpointResolver.resolve(method).getFirst().request().name);
        method = method("""
                class Demo extends Base {
                  @io.swagger.annotations.ApiOperation("实现类的标题")
                  @GetMapping("/fetch") public void fetch() {}
                }
                """, "fetch");
        assertEquals("实现类的标题", SpringEndpointResolver.resolve(method).getFirst().request().name);
    }
    public void testMissingBlankOrUnresolvedSwaggerTitleUsesMethodName() {
        for (String annotation : List.of("", "@io.swagger.annotations.ApiOperation(\" \" )",
                "@io.swagger.v3.oas.annotations.Operation(description = \"仅有说明\")",
                "@io.swagger.annotations.ApiOperation(Unknown.TITLE)")) {
            PsiMethod method = method("class Demo { " + annotation + " @GetMapping(\"/items\") public void listItems() {} }", "listItems");
            assertEquals("listItems", SpringEndpointResolver.resolve(method).getFirst().request().name);
        }
    }
    public void testProjectStateRoundTripAndPanelLifecycle() {
        ProjectState original = new ProjectState();
        FolderData folder = new FolderData(); folder.name = "用户接口"; original.folders.add(folder);
        RequestData request = new RequestData(); request.id = "saved"; request.folderId = folder.id; request.headers.add(new KeyValue("Authorization", "Bearer example"));
        original.requests.add(request); original.draft = request.copy(); original.draft.url = "/unsaved";
        var xml = XmlSerializer.serialize(original);
        ProjectState loaded = XmlSerializer.deserialize(xml, ProjectState.class);
        assertEquals(1, loaded.prefixes.size()); assertEquals(folder.id, loaded.requests.getFirst().folderId);
        assertEquals("Bearer example", loaded.requests.getFirst().headers.getFirst().value);
        assertEquals("/unsaved", loaded.draft.url);
        RequestStore.get(getProject()).loadState(loaded);
        RequestPanel panel = new RequestPanel(getProject());
        panel.setSize(1080, 780);
        layoutTree(panel);
        java.awt.image.BufferedImage screenshot = new java.awt.image.BufferedImage(1080, 780, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = screenshot.createGraphics();
        panel.printAll(graphics); graphics.dispose();
        try {
            java.nio.file.Path destination = java.nio.file.Path.of("build/reports/ui/easy-request.png");
            java.nio.file.Files.createDirectories(destination.getParent());
            javax.imageio.ImageIO.write(screenshot, "png", destination.toFile());
        } catch (java.io.IOException e) { throw new AssertionError(e); }
        Disposer.dispose(panel);
        assertEquals("/unsaved", RequestStore.get(getProject()).getState().draft.url);
        assertEquals(1, loaded.requestTabs.size());
        assertEquals("/unsaved", loaded.requestTabs.getFirst().request.url);
        assertEquals(request.url, loaded.requestTabs.getFirst().baseline.url);
    }
    public void testBodyDescriptionsPreferSwaggerAndFollowNestedJacksonNames() {
        PsiMethod method = method("""
                class Demo {
                  @PostMapping("/save") void save(@RequestBody Payload payload) {}
                }
                class Parent {
                  /** 继承的标识 */ int id;
                }
                class Payload extends Parent {
                  static final String LABEL = "用户";
                  /** 字段上的旧名称 */
                  @io.swagger.v3.oas.annotations.media.Schema(description=LABEL + "名称", example="小王")
                  @io.swagger.annotations.ApiModelProperty("Swagger 2 说明")
                  @com.fasterxml.jackson.annotation.JsonProperty("display_name") String name;
                  @io.swagger.v3.oas.annotations.media.Schema(description=" ")
                  @io.swagger.annotations.ApiModelProperty("年龄") int age;
                  /** 备注第一行
                   * 第二行 {@code optional}
                   * @since 1.0
                   */
                  @io.swagger.v3.oas.annotations.media.Schema(description=Unknown.TEXT) String note;
                  /** 子对象 */ Child child;
                  /** 明细列表 */ Child[] children;
                  @com.fasterxml.jackson.annotation.JsonIgnore @io.swagger.annotations.ApiModelProperty("隐藏") String secret;
                  /** 静态字段 */ static String constant;
                }
                class Child {
                  @io.swagger.annotations.ApiModelProperty(notes="商品编号") String code;
                  /** 数量 */ int count;
                }
                """, "save");
        RequestData request = SpringEndpointResolver.resolve(method).getFirst().request();
        var docs = request.bodyDescriptions;
        assertEquals("用户名称", docs.get("/display_name"));
        assertEquals("年龄", docs.get("/age"));
        assertEquals("备注第一行 第二行 optional", docs.get("/note"));
        assertEquals("继承的标识", docs.get("/id"));
        assertEquals("商品编号", docs.get("/child/code"));
        assertEquals("数量", docs.get("/children/*/count"));
        assertEquals("明细列表", docs.get("/children"));
        assertFalse(docs.containsKey("/secret")); assertFalse(docs.containsKey("/constant")); assertFalse(docs.containsKey("/name"));
        var json = com.google.gson.JsonParser.parseString(request.body).getAsJsonObject();
        assertEquals("小王", json.get("display_name").getAsString()); assertFalse(request.body.contains("用户名称"));
        RequestData copy = request.copy(); copy.bodyDescriptions.put("/id", "changed");
        assertEquals("继承的标识", request.bodyDescriptions.get("/id"));
        RequestData restored = XmlSerializer.deserialize(XmlSerializer.serialize(request), RequestData.class);
        assertEquals(docs, restored.bodyDescriptions); assertEquals(request.body, restored.body);
    }

    public void testQueryDtoDescriptionsSupportOrdinaryCommentsWithoutBorrowingPreviousField() {
        PsiMethod method = method("""
                class Demo { @GetMapping("/query") void query(Filter filter) {} }
                class Filter {
                  // 开始时间
                  String start;
                  /* 结束时间 */ String end;
                  int size; // 每页条数
                  String undocumented;
                  // 第一段
                  // 第二段
                  String note;
                  /** 联系方式 */ Contact contact;
                }
                class Contact { /** 城市名称 */ String city; }
                """, "query");
        RequestData request = SpringEndpointResolver.resolve(method).getFirst().request();
        var docs = request.query.stream().collect(java.util.stream.Collectors.toMap(row -> row.key, row -> row.description));
        assertEquals(docs.toString(), "开始时间", docs.get("start")); assertEquals("结束时间", docs.get("end"));
        assertEquals("每页条数", docs.get("size")); assertEquals("", docs.get("undocumented"));
        assertEquals("第一段 第二段", docs.get("note")); assertEquals("城市名称", docs.get("contact.city"));
        RequestData restored = XmlSerializer.deserialize(XmlSerializer.serialize(request), RequestData.class);
        assertEquals("开始时间", restored.query.getFirst().description);
    }

    public void testMethodParameterDescriptionsAndInheritedDocumentation() {
        myFixture.addClass("""
                interface DescribedApi {
                  void get(@io.swagger.v3.oas.annotations.Parameter(description="用户标识") long id,
                           int size, String token);
                }
                """);
        PsiMethod method = method("""
                class Demo implements DescribedApi {
                  /**
                   * @param id 旧标识
                   * @param size 每页条数
                   * @param token 凭据
                   */
                  @GetMapping(value="/{id}", params="size=10")
                  public void get(@PathVariable("id") long userId, @RequestParam int size,
                     @RequestHeader("X-Token") @io.swagger.annotations.ApiParam("访问令牌") String token) {}
                }
                """, "get");
        RequestData request = SpringEndpointResolver.resolve(method).getFirst().request();
        assertEquals("用户标识", request.pathVariables.getFirst().description);
        assertEquals("每页条数", request.query.getFirst().description);
        assertEquals("10", request.query.getFirst().value);
        assertEquals("访问令牌", request.headers.getFirst().description);
        method = method("""
                class Demo {
                  @PostMapping(value="/upload", consumes="multipart/form-data")
                  void upload(@RequestPart @io.swagger.v3.oas.annotations.Parameter(description="附件") org.springframework.web.multipart.MultipartFile file,
                    @RequestParam @io.swagger.annotations.ApiParam("文件标题") String title) {}
                }
                """, "upload");
        request = SpringEndpointResolver.resolve(method).getFirst().request();
        assertEquals("附件", request.form.getFirst().description);
        assertEquals("文件标题", request.form.get(1).description);
    }
    private static void layoutTree(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) if (child instanceof java.awt.Container nested) layoutTree(nested);
    }
}
