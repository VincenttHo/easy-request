package com.easyrequest.persistence;

import com.easyrequest.controller.EndpointIdentity;
import com.easyrequest.model.*;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import java.util.List;

public class RequestStoreTest extends LightJavaCodeInsightFixtureTestCase {
    private RequestStore store;

    @Override protected void setUp() throws Exception {
        super.setUp();
        store = RequestStore.get(getProject());
        store.loadState(new ProjectState());
        store.migrateCatalog(getProject());
    }

    private RequestData endpoint(String project, String path) {
        RequestData request = new RequestData(); request.name = "源码标题"; request.url = path;
        request.sourceFileUrl = "file:///workspace/" + project + "/src/main/java/Users.java";
        request.sourceModule = project; request.sourceMethod = "Users#list()";
        request.query.add(new KeyValue("page", "1")); request.query.getFirst().description = "页码";
        request.body = "{\"name\":\"example\"}"; request.bodyType = "JSON";
        request.catalog.groupId = project; request.catalog.groupName = project;
        request.catalog.controllerId = project + "#Users"; request.catalog.controllerTitle = "用户管理";
        EndpointIdentity.capture(getProject(), request);
        return request;
    }

    public void testMergeAddsRoutesPreservesValuesAndSeparatesApplicationsAndMethods() {
        RequestData generated = endpoint("app-a", "/users");
        store.getState().selectedPrefixId = "selected-prefix";
        store.mergeCatalog(getProject(), List.of(generated));
        RequestData saved = store.getState().requests.getFirst(); String id = saved.id;
        assertEquals("selected-prefix", saved.prefixId); assertFalse(saved.favorite);
        RequestData edited = saved.copy(); edited.name = "用户维护的标题"; edited.prefixId = "test-prefix";
        edited.query.getFirst().value = "99"; edited.body = "{\"name\":\"edited\"}";
        edited.headers.add(new KeyValue("X-Token", "saved")); edited.useGlobalHeaders = false;
        store.saveRequest(edited); store.setFavorite(id, true); store.moveFavorite(id, "my-folder");
        generated.name = "更新的 Swagger 标题"; generated.query.getFirst().value = "20";
        generated.query.getFirst().description = "最新页码说明"; generated.catalog.controllerTitle = "最新分组";
        RequestData otherApp = endpoint("app-b", "/users");
        RequestData post = endpoint("app-a", "/users"); post.method = "POST"; post.sourceHttpMethod = "POST";
        store.mergeCatalog(getProject(), List.of(generated, otherApp, post, generated.copy()));
        assertEquals(3, store.getState().requests.size());
        saved = store.find(id);
        assertEquals("用户维护的标题", saved.name); assertEquals("test-prefix", saved.prefixId);
        assertEquals("99", saved.query.getFirst().value); assertEquals("最新页码说明", saved.query.getFirst().description);
        assertEquals("{\"name\":\"edited\"}", saved.body); assertFalse(saved.useGlobalHeaders);
        assertEquals("saved", saved.headers.getFirst().value); assertEquals("最新分组", saved.catalog.controllerTitle);
        assertTrue(saved.favorite); assertEquals("my-folder", saved.folderId); assertFalse(saved.sourceMissing);
    }

    public void testPartialProjectMergeNeverMarksUnscannedProjectsMissing() {
        RequestData a = endpoint("app-a", "/users"), b = endpoint("app-b", "/users");
        store.mergeCatalog(getProject(), List.of(a, b));
        RequestData savedA = store.getState().requests.getFirst(), savedB = store.getState().requests.getLast();
        var batch = new com.easyrequest.controller.ControllerCatalog.ProjectBatch("project-a", "Project A", "/workspace/app-a",
                List.of(), java.util.Set.of(savedA.id));
        store.mergeCatalog(getProject(), List.of(), batch);
        assertTrue(savedA.sourceMissing); assertFalse(savedB.sourceMissing);
        assertEquals("project-a", savedA.catalog.groupId);
        assertEquals("app-b", savedB.catalog.groupId);
        store.mergeCatalog(getProject(), List.of(a), batch);
        assertFalse(savedA.sourceMissing); assertFalse(savedB.sourceMissing);
        assertEquals(2, store.getState().requests.size());
    }

    public void testDeletedAndChangedPathsStaySavedAndReappearingRouteKeepsItsId() {
        RequestData source = endpoint("app-a", "/old");
        store.mergeCatalog(getProject(), List.of(source));
        String id = store.getState().requests.getFirst().id; store.setFavorite(id, true);
        RequestData manual = new RequestData(); manual.url = "https://example.com/manual"; store.saveRequest(manual);
        store.mergeCatalog(getProject(), List.of(endpoint("app-a", "/new")));
        assertEquals(3, store.getState().requests.size());
        assertTrue(store.find(id).sourceMissing); assertTrue(store.find(id).favorite);
        assertFalse(store.find(manual.id).sourceMissing);
        store.mergeCatalog(getProject(), List.of(source, endpoint("app-a", "/new")));
        assertEquals(3, store.getState().requests.size()); assertFalse(store.find(id).sourceMissing);
        assertTrue(store.find(id).favorite);
        var restored = XmlSerializer.deserialize(XmlSerializer.serialize(store.getState()), ProjectState.class);
        store.loadState(restored); store.migrateCatalog(getProject());
        assertEquals(id, store.ensureEndpoint(getProject(), source).id);
        assertEquals(3, store.getState().requests.size());
    }

    public void testSavingStaleDraftCannotChangeRouteBookmarkOrSourceStatus() {
        RequestData saved = store.ensureEndpoint(getProject(), endpoint("app-a", "/users"));
        RequestData stale = saved.copy(); stale.url = "/changed"; stale.method = "DELETE";
        stale.query.getFirst().value = "7"; stale.name = "新的标题";
        store.setFavorite(saved.id, true); store.moveFavorite(saved.id, "moved-folder");
        store.mergeCatalog(getProject(), List.of());
        store.saveRequest(stale); saved = store.find(saved.id);
        assertEquals("/users", saved.url); assertEquals("GET", saved.method);
        assertEquals("7", saved.query.getFirst().value); assertEquals("新的标题", saved.name);
        assertTrue(saved.favorite); assertTrue(saved.sourceMissing); assertEquals("moved-folder", saved.folderId);
    }

    public void testMigrationPreservesCustomAddressesAndDuplicatePayloadsAndRunsOnlyOnce() {
        RequestData old = endpoint("app-a", "/users"); old.id = "old"; old.body = "first configuration";
        RequestData recent = old.copy(); recent.id = "recent"; recent.body = "latest configuration";
        RequestData custom = recent.copy(); custom.id = "custom"; custom.url = "https://proxy.example/other?size=12";
        RequestData manual = new RequestData(); manual.id = "manual"; manual.url = "https://example.com/manual";
        ProjectState legacy = new ProjectState(); legacy.requests.addAll(List.of(old, recent, custom, manual));
        store.loadState(legacy); store.migrateCatalog(getProject());
        assertEquals(4, legacy.requests.size()); assertTrue(legacy.requests.stream().allMatch(request -> request.favorite));
        assertTrue(old.legacyVariant); assertTrue(custom.legacyVariant); assertFalse(recent.legacyVariant);
        assertEquals(custom.url, store.find("custom").url); assertEquals("first configuration", store.find("old").body);
        assertEquals("recent", store.ensureEndpoint(getProject(), endpoint("app-a", "/users")).id);
        store.setFavorite("recent", false);
        var restored = XmlSerializer.deserialize(XmlSerializer.serialize(legacy), ProjectState.class);
        store.loadState(restored); store.migrateCatalog(getProject());
        assertFalse(store.find("recent").favorite); assertTrue(store.find("custom").favorite);
        assertTrue(store.find("custom").legacyVariant); assertFalse(store.find("manual").catalogManaged);
        assertEquals(4, store.getState().requests.size());
    }

    public void testLegacyEditedAddressDraftIsDetachedWithoutOverwritingSavedRequestOrDraftValues() {
        RequestData saved = endpoint("app-a", "/users"); saved.id = "saved";
        RequestTabState tab = new RequestTabState(); tab.request = saved.copy(); tab.baseline = saved.copy();
        tab.request.url = "/custom-url"; tab.request.method = "PUT";
        tab.request.body = "unsaved body"; tab.request.query.getFirst().value = "42";
        ProjectState legacy = new ProjectState(); legacy.requests.add(saved); legacy.requestTabs.add(tab);
        store.loadState(legacy); store.migrateCatalog(getProject());
        assertEquals("/users", store.find("saved").url); assertEquals("GET", store.find("saved").method);
        assertEquals("", tab.request.id); assertTrue(tab.request.legacyVariant); assertFalse(tab.request.catalogManaged);
        assertEquals("/custom-url", tab.request.url); assertEquals("PUT", tab.request.method);
        assertEquals("unsaved body", tab.request.body); assertEquals("42", tab.request.query.getFirst().value);
        assertEquals("/users", tab.baseline.url); assertEquals("", tab.baseline.id);
        store.saveRequest(tab.request);
        assertEquals(2, legacy.requests.size()); assertTrue(store.find(tab.request.id).legacyVariant);
        assertEquals("/custom-url", store.find(tab.request.id).url);
    }
}
