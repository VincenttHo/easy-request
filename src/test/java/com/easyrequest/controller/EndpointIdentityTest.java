package com.easyrequest.controller;

import com.easyrequest.model.RequestData;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.util.xmlb.XmlSerializer;
import java.util.List;

public class EndpointIdentityTest extends LightJavaCodeInsightFixtureTestCase {
    private RequestData request(String application, String fileName, String method, String url) {
        if (myFixture.findFileInTempDir(application + "/pom.xml") == null)
            myFixture.addFileToProject(application + "/pom.xml", "<project/>");
        var file = myFixture.addFileToProject(application + "/src/main/java/" + fileName + ".java", "class " + fileName + " {}");
        RequestData request = new RequestData();
        request.sourceFileUrl = file.getVirtualFile().getUrl(); request.sourceModule = getModule().getName();
        request.method = method; request.url = url;
        return request;
    }

    public void testApplicationRootAndHttpMethodPreventCrossProjectMatches() {
        RequestData a = request("a", "First", "GET", "/users");
        RequestData same = request("a", "Renamed", "GET", "https://test.example/users?page=9#section");
        RequestData b = request("b", "First", "GET", "/users");
        RequestData post = a.copy(); post.method = "POST";
        assertTrue(EndpointIdentity.same(getProject(), a, same));
        assertFalse(EndpointIdentity.same(getProject(), a, b));
        assertFalse(EndpointIdentity.same(getProject(), a, post));
        a.id = "a"; b.id = "b"; post.id = "post";
        assertEquals(List.of(a), EndpointIdentity.matches(getProject(), List.of(a, b, post), same));
    }

    public void testCapturedIdentitySurvivesUrlEditsAndSerialization() {
        RequestData original = request("app", "Users", "GET", "/service/users/{id}");
        RequestData favorite = original.copy();
        EndpointIdentity.capture(getProject(), favorite);
        assertTrue(favorite.sourceProjectUrl.endsWith("/app"));
        favorite.url = "https://proxy.example/other/123?size=20";
        favorite.method = "POST";
        var restored = XmlSerializer.deserialize(XmlSerializer.serialize(favorite.copy()), RequestData.class);
        assertTrue(EndpointIdentity.same(getProject(), original, restored));
        assertEquals("GET", restored.sourceHttpMethod);
        assertEquals("/service/users/{id}", restored.sourcePath);
    }

    public void testLegacyMetadataAndUnknownOriginsAreHandledConservatively() {
        RequestData endpoint = request("app", "Controller", "GET", "/api/items");
        RequestData legacy = endpoint.copy(); legacy.url = " /api/items?name=example ";
        assertTrue(EndpointIdentity.same(getProject(), endpoint, legacy));
        RequestData manual = new RequestData(); manual.url = "/api/items";
        assertTrue(EndpointIdentity.of(getProject(), manual).isEmpty());
        assertFalse(EndpointIdentity.same(getProject(), endpoint, manual));
        RequestData moduleOnly = manual.copy(); moduleOnly.sourceModule = getModule().getName();
        assertTrue("A workspace module alone must not choose a nested application", EndpointIdentity.of(getProject(), moduleOnly).isEmpty());
    }

    public void testPathNormalizationKeepsSpringPathSemantics() {
        assertEquals("/items/{id:[0-9]{1,3}?}", EndpointIdentity.path("https://test.example/items/{id:[0-9]{1,3}?}?size=10#top"));
        assertEquals("/items", EndpointIdentity.path("items?size=10"));
        assertEquals("/items/", EndpointIdentity.path("/items/"));
        assertEquals("/ITEMS", EndpointIdentity.path("/ITEMS"));
        assertEquals("/a%2Fb", EndpointIdentity.path("/a%2Fb"));
        assertEquals("/", EndpointIdentity.path("https://test.example?size=10"));
        assertEquals("", EndpointIdentity.path(" "));
    }
}
