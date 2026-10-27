package com.easyrequest.controller;

import com.easyrequest.model.RequestData;
import com.easyrequest.model.UrlPrefix;
import com.easyrequest.persistence.RequestStore;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.testFramework.fixtures.JavaCodeInsightFixtureTestCase;
import java.util.List;

public class LocalApplicationConfigTest extends JavaCodeInsightFixtureTestCase {
    @Override protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("package org.springframework.web.bind.annotation; public @interface GetMapping { String[] value() default {}; }");
        myFixture.addClass("package org.springframework.web.bind.annotation; public @interface RequestMapping { String[] value() default {}; }");
    }

    private Module module(String name, String path) {
        VirtualFile root = myFixture.addFileToProject(path + "/.test-root", "").getVirtualFile().getParent();
        Module module = WriteAction.compute(() -> ModuleManager.getInstance(getProject()).newModule(root.getPath() + "/" + name + ".iml", "JAVA_MODULE"));
        ModuleRootModificationUtil.updateModel(module, model -> model.addContentEntry(root).addSourceFolder(root, false));
        ModuleRootModificationUtil.addDependency(module, getModule());
        return module;
    }
    private void aggregator(String path, String... modules) {
        StringBuilder xml = new StringBuilder("<project><modules>");
        for (String name : modules) xml.append("<module>").append(name).append("</module>");
        myFixture.addFileToProject(path + "/pom.xml", xml.append("</modules></project>").toString());
        for (String name : modules) myFixture.addFileToProject(path + "/" + name + "/pom.xml", "<project/>");
    }
    private VirtualFile config(String path, String text) {
        return myFixture.addFileToProject(path + "/src/main/resources/application.yml", text).getVirtualFile();
    }
    private PsiMethod controller(String path) {
        var file = myFixture.addFileToProject(path + "/src/main/java/PaperMrApi.java", """
                import org.springframework.web.bind.annotation.*;
                @RequestMapping("/api/paperMr") class PaperMrApi {
                    @GetMapping("/visits") public void listVisits() {}
                }
                """);
        return ((PsiJavaFile) file).getClasses()[0].findMethodsByName("listVisits", false)[0];
    }
    private RequestData request(PsiMethod method) { return SpringEndpointResolver.resolve(method).getFirst().request(); }
    private String local(RequestData request) { return ProjectConfigResolver.forRequest(getProject(), request).localBaseUrl(); }

    public void testStartModuleSuppliesContextAndActiveProfilePort() {
        aggregator("nprs", "api", "start");
        Module api = module("nprs-api", "nprs/api"), start = module("nprs-start", "nprs/start");
        ModuleRootModificationUtil.addDependency(start, api);
        config("nprs/start", "server.servlet.context-path: /nprs.api\nspring.profiles.active: local\nserver.port: 8000");
        myFixture.addFileToProject("nprs/start/src/main/resources/application-local.yml", "server.port: 8186");
        RequestData request = request(controller("nprs/api"));
        assertEquals("/nprs.api/api/paperMr/visits", request.url);
        assertEquals("http://localhost:8186", local(request));
        assertEquals("nprs-api", request.sourceModule);
        assertFalse(request.sourceFileUrl.isBlank());
    }

    public void testSeparateProjectsInOneIdeaModuleNeverSharePorts() {
        myFixture.addFileToProject("project-a/pom.xml", "<project/>");
        myFixture.addFileToProject("project-b/pom.xml", "<project/>");
        config("project-a", "server.port: 8101\nserver.servlet.context-path: /a");
        config("project-b", "server.port: 8202\nserver.servlet.context-path: /b");
        RequestData a = request(controller("project-a")), b = request(controller("project-b"));
        assertEquals("http://localhost:8101", local(a)); assertEquals("http://localhost:8202", local(b));
        assertEquals("/a/api/paperMr/visits", a.url); assertEquals("/b/api/paperMr/visits", b.url);
        assertEquals("http://localhost:8101", local(a.copy()));
    }

    public void testCatalogKeepsIndependentProjectRootsAndPortsSeparate() {
        myFixture.addFileToProject("project-a/pom.xml", "<project/>");
        myFixture.addFileToProject("project-b/pom.xml", "<project/>");
        config("project-a", "server.port: 8101\nserver.servlet.context-path: /a");
        config("project-b", "server.port: 8202\nserver.servlet.context-path: /b");
        controller("project-a"); controller("project-b");
        var catalog = ControllerCatalog.scan(getProject());
        assertEquals(2, catalog.size());
        assertFalse(catalog.getFirst().groupId().equals(catalog.getLast().groupId()));
        assertFalse(catalog.getFirst().id().equals(catalog.getLast().id()));
        for (var type : catalog) {
            var request = type.endpoints().getFirst().resolve().orElseThrow().request();
            boolean first = type.groupPath().endsWith("project-a");
            assertEquals(first ? "http://localhost:8101" : "http://localhost:8202", local(request));
            assertEquals((first ? "/a" : "/b") + "/api/paperMr/visits", request.url);
        }
    }

    public void testCatalogBatchesCombineMavenModulesButKeepProjectsAndRouteIdentitiesSeparate() {
        aggregator("ip-emr", "api", "web");
        module("ip-emr-api", "ip-emr/api"); module("ip-emr-web", "ip-emr/web");
        myFixture.addFileToProject("ip-nis/pom.xml", "<project/>");
        controller("ip-emr/api"); controller("ip-emr/web"); controller("ip-nis");
        var batches = ControllerCatalog.batches(getProject(), List.of());
        var emr = batches.stream().filter(b -> b.name().equals("ip-emr")).findFirst().orElseThrow();
        assertEquals(2, emr.files().size());
        var requests = ControllerCatalog.requests(getProject(), emr);
        assertEquals(2, requests.size());
        assertTrue(requests.stream().allMatch(r -> r.catalog.groupId.equals(emr.id())));
        assertFalse(EndpointIdentity.same(getProject(), requests.getFirst(), requests.getLast()));
        var catalog = ControllerCatalog.scan(getProject());
        assertEquals(3, catalog.size());
        assertEquals(2, catalog.stream().map(ControllerCatalog.Controller::groupId).distinct().count());
        assertEquals(1, batches.stream().filter(b -> b.name().equals("ip-nis")).count());
    }

    public void testGradleSubprojectsShareOneCatalogGroup() {
        myFixture.addFileToProject("gradle-app/settings.gradle.kts", "include(\"api\", \"web\")");
        for (String child : List.of("api", "web")) {
            myFixture.addFileToProject("gradle-app/" + child + "/build.gradle.kts", "plugins { java }");
            controller("gradle-app/" + child);
        }
        var catalog = ControllerCatalog.scan(getProject());
        assertEquals(2, catalog.size());
        assertEquals("gradle-app", catalog.getFirst().groupName());
        assertEquals(catalog.getFirst().groupId(), catalog.getLast().groupId());
    }

    public void testCatalogInheritedMethodUsesConcreteApplicationModule() {
        aggregator("suite", "api", "first", "second");
        Module api = module("shared-api", "suite/api");
        Module first = module("first-app", "suite/first"), second = module("second-app", "suite/second");
        ModuleRootModificationUtil.addDependency(first, api); ModuleRootModificationUtil.addDependency(second, api);
        myFixture.addFileToProject("suite/api/src/main/java/Api.java", """
                package shared;
                public interface Api {
                    @org.springframework.web.bind.annotation.GetMapping("/items") default void items() {}
                }
                """);
        for (String app : List.of("first", "second")) myFixture.addFileToProject("suite/" + app + "/src/main/java/Concrete.java",
                "package " + app + "; @org.springframework.web.bind.annotation.RequestMapping(\"/concrete\") public class Concrete implements shared.Api {}");
        config("suite/first", "server.port: 8101\nserver.servlet.context-path: /first");
        config("suite/second", "server.port: 8202\nserver.servlet.context-path: /second");
        var controllers = ControllerCatalog.scan(getProject()).stream().filter(c -> c.className().endsWith(".Concrete")).toList();
        assertEquals(2, controllers.size());
        for (var controller : controllers) {
            var request = controller.endpoints().getFirst().resolve().orElseThrow().request();
            boolean isFirst = controller.className().equals("first.Concrete");
            assertEquals(isFirst ? "first-app" : "second-app", request.sourceModule);
            assertEquals(isFirst ? "http://localhost:8101" : "http://localhost:8202", local(request));
            assertEquals((isFirst ? "/first" : "/second") + "/concrete/items", request.url);
        }
    }

    public void testCatalogExcludesTestAndExcludedSourceRoots() {
        Module app = module("catalog-app", "catalog-app");
        controller("catalog-app");
        VirtualFile test = myFixture.addFileToProject("catalog-app/test/TestController.java",
                "class TestController { @org.springframework.web.bind.annotation.GetMapping(\"/test\") void test() {} }")
                .getVirtualFile().getParent();
        VirtualFile excluded = myFixture.addFileToProject("catalog-app/excluded/ExcludedController.java",
                "class ExcludedController { @org.springframework.web.bind.annotation.GetMapping(\"/excluded\") void test() {} }")
                .getVirtualFile().getParent();
        ModuleRootModificationUtil.updateModel(app, model -> {
            model.getContentEntries()[0].addSourceFolder(test, true);
            model.getContentEntries()[0].addExcludeFolder(excluded);
        });
        var catalog = ControllerCatalog.scan(getProject());
        assertEquals(1, catalog.size());
        assertEquals("PaperMrApi", catalog.getFirst().className());
    }

    public void testDependencySelectsCorrectStartAmongSeveralApplications() {
        aggregator("suite", "api", "application", "start", "other");
        Module api = module("suite-api", "suite/api"), application = module("suite-application", "suite/application");
        Module start = module("suite-start", "suite/start"); module("suite-other", "suite/other");
        ModuleRootModificationUtil.addDependency(application, api); ModuleRootModificationUtil.addDependency(start, application);
        config("suite/start", "server.port: 8303"); config("suite/other", "server.port: 9999");
        assertEquals("http://localhost:8303", local(request(controller("suite/api"))));
    }

    public void testImportedFilesProfilesPlaceholdersAndUnsavedEdits() {
        myFixture.addFileToProject("app/pom.xml", "<project/>");
        config("app", "spring.profiles.active: local\nspring.config.import: optional:classpath:settings/ports.yml\nserver.port: 7000");
        myFixture.addFileToProject("app/src/main/resources/settings/ports.yml", "spring.config.import: values.properties\nserver.port: ${app.http-port:7100}");
        myFixture.addFileToProject("app/src/main/resources/settings/values.properties", "app.http-port=8404");
        VirtualFile localFile = myFixture.addFileToProject("app/src/main/resources/settings/ports-local.yml", "server.port: 8505").getVirtualFile();
        RequestData request = request(controller("app"));
        assertEquals("http://localhost:8505", local(request));
        var document = FileDocumentManager.getInstance().getDocument(localFile);
        assertNotNull(document);
        WriteAction.run(() -> document.setText("server.port: 8606"));
        assertEquals("http://localhost:8606", local(request));
    }

    public void testPortPlaceholderFromImportedPropertiesAndFileLocation() {
        myFixture.addFileToProject("app/pom.xml", "<project/>");
        config("app", "spring.config.import: file:./config/port.properties\nserver.port: ${local.http-port:8081}");
        myFixture.addFileToProject("app/config/port.properties", "local.http-port=8765");
        assertEquals("http://localhost:8765", local(request(controller("app"))));
    }

    public void testAmbiguousApplicationsRequireSelectionAndDoNotUseOtherProjectOverride() {
        aggregator("suite", "api", "start-a", "start-b");
        Module api = module("shared-api", "suite/api"), a = module("start-a", "suite/start-a"), b = module("start-b", "suite/start-b");
        ModuleRootModificationUtil.addDependency(a, api); ModuleRootModificationUtil.addDependency(b, api);
        config("suite/start-a", "server.port: 8111"); config("suite/start-b", "server.port: 8222");
        RequestData request = request(controller("suite/api"));
        assertTrue(ProjectConfigResolver.forRequest(getProject(), request).ambiguous());
        assertThrows(IllegalArgumentException.class, () -> local(request));
        RequestStore.get(getProject()).getState().springConfigModule = "start-b";
        assertEquals("http://localhost:8222", local(request));
        myFixture.addFileToProject("separate/pom.xml", "<project/>");
        config("separate", "server.port: 8777");
        assertEquals("http://localhost:8777", local(request(controller("separate"))));
    }

    public void testSpringDefaultAndRandomPortHandling() {
        myFixture.addFileToProject("app/pom.xml", "<project/>");
        RequestData request = request(controller("app"));
        assertEquals("http://localhost:8080", local(request));
        config("app", "server.port: 0");
        assertThrows(IllegalArgumentException.class, () -> local(request));
    }

    public void testClasspathImportCanReadDependencyResources() {
        aggregator("suite", "api", "common", "start");
        Module api = module("suite-api", "suite/api"), common = module("suite-common", "suite/common"), start = module("suite-start", "suite/start");
        ModuleRootModificationUtil.addDependency(start, api); ModuleRootModificationUtil.addDependency(start, common);
        config("suite/start", "spring.config.import: classpath:shared-port.yml");
        myFixture.addFileToProject("suite/common/src/main/resources/shared-port.yml", "server.port: 8998");
        assertEquals("http://localhost:8998", local(request(controller("suite/api"))));
    }

    public void testMissingRequiredImportDoesNotSilentlyUseDefaultPort() {
        myFixture.addFileToProject("app/pom.xml", "<project/>");
        config("app", "spring.config.import: classpath:missing.yml");
        assertThrows(IllegalArgumentException.class, () -> local(request(controller("app"))));
    }

    public void testLegacyLocalPrefixMigratesWithoutChangingCustomPrefixes() {
        var state = new com.easyrequest.model.ProjectState(); state.prefixes.clear();
        UrlPrefix old = new UrlPrefix("本地", "http://localhost:8080"), custom = new UrlPrefix("测试", "http://localhost:9999");
        state.prefixes.addAll(List.of(old, custom)); state.selectedPrefixId = old.id;
        RequestData saved = new RequestData(); saved.prefixId = old.id; state.requests.add(saved); state.draft = saved.copy();
        RequestStore.get(getProject()).loadState(state);
        assertEquals(UrlPrefix.AUTO_LOCAL_ID, state.selectedPrefixId); assertEquals(UrlPrefix.AUTO_LOCAL_ID, state.requests.getFirst().prefixId);
        assertEquals(UrlPrefix.AUTO_LOCAL_ID, state.draft.prefixId); assertEquals(2, state.prefixes.size());
        assertTrue(state.prefixes.stream().anyMatch(p -> p.id.equals(custom.id) && p.url.equals("http://localhost:9999")));
    }
}
