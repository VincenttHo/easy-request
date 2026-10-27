package com.easyrequest.controller;

import com.easyrequest.compat.JavaCompat;

import com.easyrequest.model.RequestData;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Catalog records use application boundaries and routes, independent of Java method names. */
public final class EndpointIdentity {
    public static final class Key {
        private final String project;
        private final String method;
        private final String path;
        public Key(String project, String method, String path) {
            this.project = project;
            this.method = method;
            this.path = path;
        }
        public String project() { return project; }
        public String method() { return method; }
        public String path() { return path; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Key)) return false;
            Key that = (Key) other;
            return java.util.Objects.equals(project, that.project) && java.util.Objects.equals(method, that.method) && java.util.Objects.equals(path, that.path);
        }
        @Override public int hashCode() { return java.util.Objects.hash(project, method, path); }
        @Override public String toString() { return "Key[" + "project=" + project + ", " + "method=" + method + ", " + "path=" + path + "]"; }
    }
    private EndpointIdentity() {}

    public static Optional<Key> of(Project project, RequestData request) {
        String owner = projectUrl(project, request);
        String path = path(JavaCompat.isBlank(request.sourcePath) ? request.url : request.sourcePath);
        String method = JavaCompat.strip((JavaCompat.isBlank(request.sourceHttpMethod) ? request.method : request.sourceHttpMethod)).toUpperCase(Locale.ROOT);
        return JavaCompat.isBlank(owner) || JavaCompat.isBlank(path) || JavaCompat.isBlank(method) ? Optional.empty() : Optional.of(new Key(owner, method, path));
    }

    public static void capture(Project project, RequestData request) {
        of(project, request).ifPresent(key -> {
            request.sourceProjectUrl = key.project(); request.sourceHttpMethod = key.method(); request.sourcePath = key.path();
        });
    }

    public static boolean same(Project project, RequestData a, RequestData b) {
        java.util.Optional<com.easyrequest.controller.EndpointIdentity.Key> key = of(project, a);
        return key.isPresent() && key.equals(of(project, b));
    }

    public static List<RequestData> matches(Project project, List<RequestData> records, RequestData endpoint) {
        java.util.Optional<com.easyrequest.controller.EndpointIdentity.Key> key = of(project, endpoint);
        if ((!key.isPresent())) return JavaCompat.list();
        return records.stream().filter(saved -> !JavaCompat.isBlank(saved.id) && key.equals(of(project, saved))).collect(java.util.stream.Collectors.toList());
    }

    public static void copySource(RequestData from, RequestData to) {
        to.sourceModule = from.sourceModule; to.sourceFileUrl = from.sourceFileUrl; to.sourceMethod = from.sourceMethod;
        to.sourceProjectUrl = from.sourceProjectUrl; to.sourceHttpMethod = from.sourceHttpMethod; to.sourcePath = from.sourcePath;
    }

    private static String projectUrl(Project project, RequestData request) {
        if (!JavaCompat.isBlank(request.sourceProjectUrl)) return request.sourceProjectUrl;
        if (JavaCompat.isBlank(request.sourceFileUrl) && JavaCompat.isBlank(request.sourceModule)) return "";
        VirtualFile file = JavaCompat.isBlank(request.sourceFileUrl) ? null : VirtualFileManager.getInstance().findFileByUrl(request.sourceFileUrl);
        Module module = file == null ? null : ModuleUtilCore.findModuleForFile(file, project);
        if (file != null) {
            VirtualFile root = ProjectRoots.logicalRoot(file, module);
            if (root != null) return root.getUrl();
        }
        // A moved/deleted legacy source can still identify its own application by its src directory.
        if (!JavaCompat.isBlank(request.sourceFileUrl)) {
            int src = request.sourceFileUrl.lastIndexOf("/src/");
            if (src >= 0) return request.sourceFileUrl.substring(0, src);
            // Without a root, match only this exact source instead of guessing across applications.
            return "source:" + request.sourceFileUrl;
        }
        module = ModuleManager.getInstance(project).findModuleByName(request.sourceModule);
        if (module == null) return "";
        VirtualFile[] roots = ModuleRootManager.getInstance(module).getContentRoots();
        // A module alone cannot identify nested applications in an unimported multi-project workspace.
        if (roots.length != 1 || (roots[0].findChild("pom.xml") == null && roots[0].findChild("build.gradle") == null
                && roots[0].findChild("build.gradle.kts") == null)) return "";
        return roots[0].getUrl();
    }

    public static String path(String value) {
        String path = JavaCompat.strip(value);
        if (JavaCompat.isBlank(path)) return "";
        if (path.matches("(?is)^https?://.*")) {
            int authority = path.indexOf("://") + 3;
            int end = authority;
            while (end < path.length() && "/?#".indexOf(path.charAt(end)) < 0) end++;
            path = end == path.length() || path.charAt(end) != '/' ? "/" : path.substring(end);
        }
        int braces = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '{') braces++;
            else if (c == '}') braces = Math.max(0, braces - 1);
            else if (braces == 0 && (c == '?' || c == '#')) { path = path.substring(0, i); break; }
        }
        return path.startsWith("/") ? path : "/" + path;
    }
}
