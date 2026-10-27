package com.easyrequest.http;

import com.easyrequest.model.KeyValue;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class UrlBuilderTest {
    @Test public void combinesPrefixPathAndEncodedQuery() {
        var uri = UrlBuilder.build("http://localhost:8080/", "/app/users/{id}?page=1", List.of(new KeyValue("id", "张 / 三")), List.of(new KeyValue("q", "a+b &c")));
        assertEquals("http://localhost:8080/app/users/%E5%BC%A0%20%2F%20%E4%B8%89?page=1&q=a%2Bb%20%26c", uri.toString());
    }
    @Test public void absoluteUrlIgnoresPrefixAndDisabledRows() {
        KeyValue ignored = new KeyValue("token", "secret"); ignored.enabled = false;
        assertEquals("https://example.com/a?x=1", UrlBuilder.build("http://wrong", "https://example.com/a?x=1#fragment", List.of(), List.of(ignored)).toString());
    }
    @Test public void supportsSpringRegexPathVariables() {
        String path = "/users/{id:[0-9]{1,8}}/{name}";
        assertEquals(List.of("id", "name"), UrlBuilder.variables(path));
        assertEquals("http://localhost/users/12/a%2Fb", UrlBuilder.build("http://localhost", path, List.of(new KeyValue("id", "12"), new KeyValue("name", "a/b")), List.of()).toString());
    }
    @Test public void failsOnMissingVariableOrUnsupportedScheme() {
        assertThrows(IllegalArgumentException.class, () -> UrlBuilder.build("http://localhost", "/{missing}", List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> UrlBuilder.build("", "file:///etc/hosts", List.of(), List.of()));
    }
    @Test public void normalizesContextAndControllerPaths() {
        assertEquals("/service/users/list", UrlBuilder.joinPath("/service/", "/users/", "/list"));
        assertEquals("/", UrlBuilder.joinPath("", "/"));
        assertEquals("/users/", UrlBuilder.joinPath("/users", "/"));
    }
}
