package com.easyrequest.controller;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SpringConfigTest {
    @Test public void readsYamlProfilesAndPlaceholderDefault() {
        var sources = List.of(new SpringConfig.Source("application.yml", """
                server:
                  servlet:
                    context-path: /base
                spring:
                  profiles:
                    active: dev
                ---
                spring.config.activate.on-profile: dev
                server.servlet.context-path: ${EASY_REQUEST_TEST_CONTEXT:/development}
                ---
                spring.config.activate.on-profile: prod
                server.servlet.context-path: /production
                """));
        assertEquals("/development", SpringConfig.read(sources, "").contextPath());
        assertEquals("/production", SpringConfig.read(sources, "prod").contextPath());
    }
    @Test public void propertiesOverrideYamlAndProfileOverridesBase() {
        var sources = List.of(new SpringConfig.Source("application.yml", "server.servlet.context-path: /yaml"),
                new SpringConfig.Source("application.properties", "server.servlet.context-path=/properties\nspring.profiles.active=dev"),
                new SpringConfig.Source("application-dev.yml", "server.servlet.context-path: /dev"),
                new SpringConfig.Source("application-prod.properties", "server.servlet.context-path=/prod"));
        assertEquals("/dev", SpringConfig.read(sources, "").contextPath());
        assertEquals("/prod", SpringConfig.read(sources, "prod").contextPath());
        assertEquals("/properties", SpringConfig.read(sources, "test").contextPath());
    }
    @Test public void resolvesConfigReferencesAndKeepsUnknownPlaceholders() {
        var config = SpringConfig.read(List.of(new SpringConfig.Source("application.properties", "api.prefix=/v1\nserver.servlet.context-path=${api.prefix}/service")), "");
        assertEquals("/v1/service", config.contextPath());
        assertEquals("${not.defined}", config.resolve("${not.defined}"));
    }
    @Test public void reportsInvalidYamlWithoutBreakingOtherConfig() {
        var config = SpringConfig.read(List.of(new SpringConfig.Source("application.yml", "server: [invalid"),
                new SpringConfig.Source("application.properties", "server.servlet.context-path=/valid")), "");
        assertEquals("/valid", config.contextPath()); assertEquals(1, config.warnings().size());
    }
    @Test public void lastExplicitProfileWins() {
        var config = SpringConfig.read(List.of(new SpringConfig.Source("application-a.yml", "server.servlet.context-path: /a"),
                new SpringConfig.Source("application-b.yml", "server.servlet.context-path: /b")), "b,a");
        assertEquals("/a", config.contextPath());
    }
    @Test public void inactiveProfileFileCannotLeakItsActivatedDocument() {
        var sources = List.of(new SpringConfig.Source("application.yml", "server.servlet.context-path: /base"),
                new SpringConfig.Source("application-dev.yml", "spring.config.activate.on-profile: test\nserver.servlet.context-path: /dev-test"));
        assertEquals("/base", SpringConfig.read(sources, "test").contextPath());
        assertEquals("/dev-test", SpringConfig.read(sources, "dev,test").contextPath());
    }
}
