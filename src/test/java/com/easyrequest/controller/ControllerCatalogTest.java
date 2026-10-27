package com.easyrequest.controller;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.*;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.List;

public class ControllerCatalogTest extends LightJavaCodeInsightFixtureTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("package org.springframework.web.bind.annotation; public enum RequestMethod {GET, POST}");
        for (String annotation : List.of("RequestMapping", "GetMapping"))
            myFixture.addClass("package org.springframework.web.bind.annotation; public @interface " + annotation
                    + " { String[] value() default {}; String[] path() default {}; RequestMethod[] method() default {}; }");
        myFixture.addClass("package org.springframework.web.bind.annotation; public @interface RestController {}");
        myFixture.addClass("package org.springframework.web.bind.annotation; public @interface RequestParam { String defaultValue() default \"\"; }");
        myFixture.addClass("package io.swagger.annotations; public @interface Api { String[] tags() default {}; String value() default \"\"; }");
        myFixture.addClass("package io.swagger.annotations; public @interface ApiOperation { String value() default \"\"; }");
        myFixture.addClass("package io.swagger.v3.oas.annotations.tags; public @interface Tag { String name() default \"\"; }");
        myFixture.addClass("package io.swagger.v3.oas.annotations; public @interface Operation { String summary() default \"\"; }");
    }
    private PsiJavaFile source(String path, String code) {
        return (PsiJavaFile) myFixture.addFileToProject(path, "import org.springframework.web.bind.annotation.*;\n" + code);
    }
    private ControllerCatalog.Controller controller(String name) {
        return ControllerCatalog.scan(getProject()).stream().filter(c -> c.className().equals(name)).findFirst().orElseThrow();
    }

    public void testSwaggerNamesFallbackAndControllersShareFileWithoutMerging() {
        source("Users.java", """
                @io.swagger.v3.oas.annotations.tags.Tag(name = "用户管理")
                @io.swagger.annotations.Api(tags = "旧名称")
                @RequestMapping("/users") class Users {
                    @io.swagger.v3.oas.annotations.Operation(summary = "用户列表")
                    @GetMapping public void list() {}
                }
                @io.swagger.annotations.Api(tags = {" ", "订单管理"}, value = "旧订单")
                class Orders {
                    @io.swagger.annotations.ApiOperation("订单详情")
                    @GetMapping("/orders") public void detail() {}
                }
                @io.swagger.annotations.Api(value = "旧版标签") class Legacy {
                    @GetMapping("/legacy") public void legacy() {}
                }
                @io.swagger.v3.oas.annotations.tags.Tag(name = " ") class Plain {
                    @GetMapping("/plain") public void plainMethod() {}
                }
                class Utility { public void unused() {} }
                @RestController class EmptyController {}
                """);
        var catalog = ControllerCatalog.scan(getProject());
        assertEquals(4, catalog.size());
        assertEquals("用户管理", controller("Users").title());
        assertEquals("用户列表", controller("Users").endpoints().getFirst().title());
        assertEquals("订单管理", controller("Orders").title());
        assertEquals("订单详情", controller("Orders").endpoints().getFirst().title());
        assertEquals("旧版标签", controller("Legacy").title());
        assertEquals("Plain", controller("Plain").title());
        assertEquals("plainMethod", controller("Plain").endpoints().getFirst().title());
        assertEquals(4, catalog.stream().map(ControllerCatalog.Controller::id).distinct().count());
        assertEquals(1, catalog.stream().map(ControllerCatalog.Controller::groupId).distinct().count());
    }

    public void testEveryRouteAndNestedControllerAreIncludedOnce() {
        source("Routes.java", """
                @RequestMapping({"/v1", "/v2"}) class Routes {
                    @RequestMapping(value={"/a", "/b", "/a"}, method={RequestMethod.GET, RequestMethod.POST})
                    public void routes() {}
                    static class Nested {
                        @GetMapping("/nested") public void nested() {}
                    }
                }
                """);
        var entries = controller("Routes").endpoints();
        assertEquals(8, entries.size());
        assertEquals(8, entries.stream().map(ControllerCatalog.Entry::id).distinct().count());
        assertEquals(2, ControllerCatalog.scan(getProject()).size());
        assertEquals("/nested", controller("Routes.Nested").endpoints().getFirst().route().path());
    }

    public void testInheritedDefaultMethodUsesConcreteControllerRouteAndOrigin() {
        source("Api.java", """
                @RequestMapping("/api") interface Api {
                    @io.swagger.annotations.ApiOperation("继承的接口")
                    @GetMapping("/items") default void list(@RequestParam(defaultValue="20") int size) {}
                }
                """);
        var file = source("Concrete.java", "@RestController @RequestMapping(\"/concrete\") class Concrete implements Api {}");
        myFixture.addFileToProject("application.properties", "server.servlet.context-path=/service\nserver.port=8123");
        var entries = controller("Concrete").endpoints();
        assertEquals(1, entries.size());
        var request = entries.getFirst().resolve().orElseThrow().request();
        assertEquals("继承的接口", request.name);
        assertEquals("/service/concrete/items", request.url);
        assertEquals(file.getVirtualFile().getUrl(), request.sourceFileUrl);
        assertEquals("Concrete#list(int)", request.sourceMethod);
        assertEquals("20", request.query.getFirst().value);
        assertEquals("http://localhost:8123", ProjectConfigResolver.forRequest(getProject(), request).localBaseUrl());
        assertEquals("/api/items", controller("Api").endpoints().getFirst().route().path());
    }

    public void testNavigationResolvesOverloadNestedClassAndInheritedDeclaration() {
        var file = source("Targets.java", """
                class Targets {
                    @GetMapping("/one") void find(int id) {}
                    @GetMapping("/two") void find(java.lang.String id) {}
                    static class Nested { @GetMapping("/nested") void nested() {} }
                }
                """);
        var type = file.getClasses()[0];
        var method = type.findMethodsByName("find", false)[1];
        var target = ControllerNavigation.target(getProject(), file.getVirtualFile().getUrl(), "Targets", "Targets#find(java.lang.String)");
        assertNotNull(target); assertEquals(method.getTextOffset(), target.getOffset());
        var nested = type.getInnerClasses()[0];
        target = ControllerNavigation.target(getProject(), file.getVirtualFile().getUrl(), "Targets.Nested", "");
        assertEquals(nested.getTextOffset(), target.getOffset());
        var api = source("Inherited.java", "interface Inherited { @GetMapping(\"/inherited\") default void inherited() {} }");
        var concrete = source("Concrete.java", "@RestController class Concrete implements Inherited {}");
        target = ControllerNavigation.target(getProject(), concrete.getVirtualFile().getUrl(), "Concrete", "Concrete#inherited()");
        assertEquals(api.getVirtualFile(), target.getFile());
        assertEquals(api.getClasses()[0].getMethods()[0].getTextOffset(), target.getOffset());
        String url = file.getVirtualFile().getUrl();
        WriteCommandAction.runWriteCommandAction(getProject(), file::delete);
        assertNull(ControllerNavigation.target(getProject(), url, "Targets", ""));
    }

    public void testChangedOrDeletedRouteRequiresRefresh() {
        var file = source("Changing.java", "class Changing { @GetMapping(\"/old\") public void fetch() {} }");
        var entry = controller("Changing").endpoints().getFirst();
        var documents = PsiDocumentManager.getInstance(getProject());
        var document = documents.getDocument(file);
        assertNotNull(document);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            document.setText(document.getText().replace("/old", "/new"));
            documents.commitDocument(document);
        });
        assertEquals("/old", entry.route().path());
        assertTrue(entry.resolve().isEmpty());
        var fresh = controller("Changing").endpoints().getFirst();
        assertEquals("/new", fresh.resolve().orElseThrow().request().url);
        WriteCommandAction.runWriteCommandAction(getProject(), file::delete);
        assertTrue(fresh.resolve().isEmpty());
    }
}
