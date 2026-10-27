package com.easyrequest.controller;

import com.easyrequest.compat.JavaCompat;

import com.easyrequest.model.RequestData;
import com.easyrequest.persistence.RequestStore;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class ProjectConfigResolver {
    private static final List<String> BASE_NAMES = JavaCompat.list("bootstrap.yml", "bootstrap.yaml", "bootstrap.properties", "application.yml", "application.yaml", "application.properties");
    private static final class ApplicationRoot {
        private final VirtualFile root;
        private final Module module;
        private ApplicationRoot(VirtualFile root, Module module) {
            this.root = root;
            this.module = module;
        }
        public VirtualFile root() { return root; }
        public Module module() { return module; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ApplicationRoot)) return false;
            ApplicationRoot that = (ApplicationRoot) other;
            return java.util.Objects.equals(root, that.root) && java.util.Objects.equals(module, that.module);
        }
        @Override public int hashCode() { return java.util.Objects.hash(root, module); }
        @Override public String toString() { return "ApplicationRoot[" + "root=" + root + ", " + "module=" + module + "]"; }
    }
    private static final class Config {
        private final ApplicationRoot application;
        private final SpringConfig.Result values;
        private final boolean hasFiles;
        private Config(ApplicationRoot application, SpringConfig.Result values, boolean hasFiles) {
            this.application = application;
            this.values = values;
            this.hasFiles = hasFiles;
        }
        public ApplicationRoot application() { return application; }
        public SpringConfig.Result values() { return values; }
        public boolean hasFiles() { return hasFiles; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Config)) return false;
            Config that = (Config) other;
            return java.util.Objects.equals(application, that.application) && java.util.Objects.equals(values, that.values) && java.util.Objects.equals(hasFiles, that.hasFiles);
        }
        @Override public int hashCode() { return java.util.Objects.hash(application, values, hasFiles); }
        @Override public String toString() { return "Config[" + "application=" + application + ", " + "values=" + values + ", " + "hasFiles=" + hasFiles + "]"; }
    }
    public static final class Resolution {
        private final SpringConfig.Result config;
        private final String source;
        private final boolean ambiguous;
        public Resolution(SpringConfig.Result config, String source, boolean ambiguous) {
            this.config = config;
            this.source = source;
            this.ambiguous = ambiguous;
        }
        public SpringConfig.Result config() { return config; }
        public String source() { return source; }
        public boolean ambiguous() { return ambiguous; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Resolution)) return false;
            Resolution that = (Resolution) other;
            return java.util.Objects.equals(config, that.config) && java.util.Objects.equals(source, that.source) && java.util.Objects.equals(ambiguous, that.ambiguous);
        }
        @Override public int hashCode() { return java.util.Objects.hash(config, source, ambiguous); }
        @Override public String toString() { return "Resolution[" + "config=" + config + ", " + "source=" + source + ", " + "ambiguous=" + ambiguous + "]"; }
        public String localBaseUrl() {
            if (ambiguous) throw new IllegalArgumentException("无法唯一确定启动应用，请在项目设置中选择当前接口的配置来源模块");
            Optional<String> failure = config.warnings().stream().filter(w -> w.contains("无法读取引用配置") || w.contains("解析失败") || w.contains("无法读取：")).findFirst();
            if (failure.isPresent()) throw new IllegalArgumentException(failure.get());
            String port = config.resolve(config.properties().getOrDefault("server.port", "8080")).trim();
            int number;
            try { number = Integer.parseInt(port); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("无法解析本地 server.port：" + port + "（配置来源：" + source + "）"); }
            if (number < 1 || number > 65535) throw new IllegalArgumentException("server.port 必须为 1–65535；随机端口 0 请在启动后手动配置前缀");
            boolean ssl = Boolean.parseBoolean(config.resolve(config.properties().getOrDefault("server.ssl.enabled", "false")));
            return (ssl ? "https" : "http") + "://localhost:" + number;
        }
    }
    private ProjectConfigResolver() {}

    public static SpringConfig.Result forMethod(PsiMethod method) {
        VirtualFile file = method.getContainingFile().getVirtualFile();
        Module module = ModuleUtilCore.findModuleForPsiElement(method);
        return resolve(method.getProject(), file, module).config;
    }

    public static Resolution forRequest(Project project, RequestData request) {
        VirtualFile file = JavaCompat.isBlank(request.sourceFileUrl) ? null : VirtualFileManager.getInstance().findFileByUrl(request.sourceFileUrl);
        Module module = file == null ? null : ModuleUtilCore.findModuleForFile(file, project);
        if (module == null && !JavaCompat.isBlank(request.sourceModule)) module = ModuleManager.getInstance(project).findModuleByName(request.sourceModule);
        if (file == null && module == null && (!JavaCompat.isBlank(request.sourceModule) || !JavaCompat.isBlank(request.sourceFileUrl))) {
            return new Resolution(new SpringConfig.Result(JavaCompat.map(), JavaCompat.list("Controller 来源已不存在，请重新从代码打开接口")), "", true);
        }
        return resolve(project, file, module);
    }

    private static Resolution resolve(Project project, VirtualFile file, Module module) {
        com.easyrequest.model.ProjectState settings = RequestStore.get(project).getState();
        VirtualFile origin = ProjectRoots.logicalRoot(file, module);
        VirtualFile group = ProjectRoots.groupRoot(origin);
        Map<String, ApplicationRoot> roots = new LinkedHashMap<>();
        if (origin != null) roots.put(origin.getPath(), new ApplicationRoot(origin, module));
        ModuleManager manager = ModuleManager.getInstance(project);
        for (Module candidate : manager.getModules()) {
            ProgressManager.checkCanceled();
            for (VirtualFile root : ModuleRootManager.getInstance(candidate).getContentRoots()) {
                VirtualFile logical = ProjectRoots.logicalRoot(root, candidate);
                addRoot(roots, logical, candidate, group);
            }
        }
        // Also handles opening a workspace containing several Maven/Gradle projects as a single IDEA module.
        for (String name : BASE_NAMES) {
            for (VirtualFile configFile : FilenameIndex.getVirtualFilesByName(project, name, GlobalSearchScope.projectScope(project))) {
                ProgressManager.checkCanceled();
                String path = configFile.getPath();
                if (path.contains("/src/test/") || path.contains("/target/") || path.contains("/build/") || path.contains("/.idea/")) continue;
                Module candidate = ModuleUtilCore.findModuleForFile(configFile, project);
                addRoot(roots, ProjectRoots.logicalRoot(configFile, candidate), candidate, group);
            }
        }
        Config own = readRoot(new ApplicationRoot(origin, module), settings.springProfile);
        List<Config> available = new ArrayList<>();
        for (ApplicationRoot root : roots.values()) {
            Config config = Objects.equals(root.root, origin) ? own : readRoot(root, settings.springProfile);
            if (config.hasFiles) available.add(config);
        }

        // A manual selection applies only inside the Controller's build project, never to another open project.
        if (!JavaCompat.isBlank(settings.springConfigModule)) {
            List<Config> selected = available.stream().filter(c -> c.application.module != null
                    && c.application.module.getName().equals(settings.springConfigModule)).collect(java.util.stream.Collectors.toList());
            if (selected.size() == 1) return resolution(own, selected.get(0), false);
        }
        boolean hasOwnServer = own.values.properties().containsKey("server.port") || own.values.properties().containsKey("server.servlet.context-path");
        if (origin != null && hasOwnServer) return resolution(own, own, false);
        List<Config> others = available.stream().filter(c -> !Objects.equals(c.application.root, origin)).collect(java.util.stream.Collectors.toList());
        List<Config> dependents = others.stream().filter(c -> c.application.module != module && dependsOn(c.application.module, module)).collect(java.util.stream.Collectors.toList());
        List<Config> candidates = dependents.isEmpty() ? others : dependents;
        if (candidates.size() == 1) return resolution(own, candidates.get(0), false);
        if (candidates.size() > 1) {
            String names = String.join("、", candidates.stream().map(c -> label(c.application)).collect(java.util.stream.Collectors.toList()));
            List<String> warnings = new ArrayList<>(own.values.warnings());
            warnings.add("找到多个启动应用（" + names + "），请在项目设置中选择配置来源模块");
            return new Resolution(new SpringConfig.Result(own.values.properties(), warnings), "", true);
        }
        return resolution(own, own, false);
    }

    private static void addRoot(Map<String, ApplicationRoot> roots, VirtualFile root, Module module, VirtualFile group) {
        if (root == null || (group != null && !group.equals(ProjectRoots.groupRoot(root)))) return;
        roots.putIfAbsent(root.getPath(), new ApplicationRoot(root, module));
    }

    private static String label(ApplicationRoot application) {
        if (application.root != null) return application.root.getName();
        return application.module == null ? "Spring Boot 默认值" : application.module.getName();
    }

    private static Resolution resolution(Config own, Config selected, boolean ambiguous) {
        Map<String, String> properties = new LinkedHashMap<>(own.values.properties());
        properties.putAll(selected.values.properties());
        List<String> notes = new ArrayList<>(own.values.warnings());
        if (selected != own) notes.addAll(selected.values.warnings());
        String source = label(selected.application);
        if (selected.hasFiles) notes.add("配置来源：" + source);
        return new Resolution(new SpringConfig.Result(properties, new ArrayList<>(new LinkedHashSet<>(notes))), source, ambiguous);
    }

    private static boolean dependsOn(Module start, Module target) {
        if (start == null || target == null) return false;
        Set<Module> seen = new HashSet<>();
        Deque<Module> pending = new ArrayDeque<>(); pending.add(start);
        while (!pending.isEmpty()) {
            ProgressManager.checkCanceled();
            Module current = pending.removeFirst();
            if (!seen.add(current) || current.isDisposed()) continue;
            if (current == target) return true;
            Collections.addAll(pending, ModuleRootManager.getInstance(current).getDependencies(false));
        }
        return false;
    }

    private static List<VirtualFile> directories(ApplicationRoot application) {
        Set<VirtualFile> directories = new LinkedHashSet<>();
        if (application.root != null) {
            add(directories, application.root.findFileByRelativePath("src/main/resources"));
            directories.add(application.root);
            add(directories, application.root.findChild("config"));
        }
        if (application.module != null) for (VirtualFile root : ModuleRootManager.getInstance(application.module).getSourceRoots()) {
            if (root.getPath().contains("/src/test/")) continue;
            if (application.root == null || application.root.equals(ProjectRoots.logicalRoot(root, application.module))) directories.add(root);
        }
        return JavaCompat.copyList(directories);
    }

    private static Config readRoot(ApplicationRoot application, String profile) {
        List<VirtualFile> directories = directories(application);
        List<SpringConfig.Source> sources = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Set<String> files = new HashSet<>();
        for (VirtualFile directory : directories) for (VirtualFile file : directory.getChildren()) {
            ProgressManager.checkCanceled();
            if (file.isDirectory() || !file.getName().matches("(?:application|bootstrap)(?:-[^.]+)?\\.(?:properties|ya?ml)") || !files.add(file.getPath())) continue;
            try {
                SpringConfig.Source source = readFile(file, new SpringConfig.Source(file.getName(), "").profile());
                sources.add(source);
            } catch (IOException e) { warnings.add(file.getName() + " 无法读取：" + e.getMessage()); }
        }
        SpringConfig.Result config = SpringConfig.read(sources, profile, (referring, location, profiles) ->
                imports(application, directories, referring, location, profiles));
        warnings.addAll(config.warnings());
        return new Config(application, new SpringConfig.Result(config.properties(), warnings), !sources.isEmpty());
    }

    private static SpringConfig.Source readFile(VirtualFile file, String profile) throws IOException {
        if (file.getLength() > 1_000_000) throw new IOException("配置文件超过 1 MB");
        com.intellij.openapi.editor.Document document = FileDocumentManager.getInstance().getCachedDocument(file);
        String text = document == null ? new String(file.contentsToByteArray(), StandardCharsets.UTF_8) : document.getText();
        return new SpringConfig.Source(file.getName(), text, file.getUrl(), profile);
    }

    private static List<SpringConfig.Source> imports(ApplicationRoot application, List<VirtualFile> directories,
                                                    SpringConfig.Source referring, String location, List<String> profiles) throws IOException {
        boolean optional = location.startsWith("optional:");
        if (optional) location = location.substring("optional:".length());
        List<VirtualFile> searchRoots = new ArrayList<>();
        String path = location;
        if (location.startsWith("classpath:")) {
            path = location.substring("classpath:".length()).replaceAll("^/+", "");
            searchRoots.addAll(directories);
            if (application.module != null) {
                Set<Module> visited = new HashSet<>();
                Deque<Module> pending = new ArrayDeque<>(); pending.add(application.module);
                while (!pending.isEmpty()) {
                    Module current = pending.removeFirst();
                    if (!visited.add(current) || current.isDisposed()) continue;
                    if (current != application.module) for (VirtualFile root : ModuleRootManager.getInstance(current).getContentRoots()) {
                        searchRoots.addAll(directories(new ApplicationRoot(ProjectRoots.logicalRoot(root, current), current)));
                    }
                    Collections.addAll(pending, ModuleRootManager.getInstance(current).getDependencies(false));
                }
            }
        } else if (location.startsWith("file:")) {
            path = location.substring("file:".length());
            if (path.startsWith("/")) {
                VirtualFile root = LocalFileSystem.getInstance().findFileByPath("/");
                if (root != null) searchRoots.add(root);
                path = path.replaceAll("^/+", "");
            } else if (application.root != null) searchRoots.add(application.root);
        } else if (location.contains(":")) {
            if (optional) return JavaCompat.list();
            throw new IOException("仅支持本地 file / classpath 配置引用：" + location);
        } else {
            VirtualFile from = VirtualFileManager.getInstance().findFileByUrl(referring.id());
            if (from != null) searchRoots.add(from.getParent());
        }
        List<String> paths = new ArrayList<>();
        if (path.endsWith("/")) for (String name : BASE_NAMES) paths.add(path + name);
        else paths.add(path);
        List<SpringConfig.Source> result = new ArrayList<>();
        for (String candidate : paths) {
            int dot = candidate.lastIndexOf('.');
            if (dot < 0 || !candidate.matches(".*\\.(?:properties|ya?ml)")) throw new IOException("引用文件须为 properties、yml 或 yaml");
            VirtualFile base = findRelative(searchRoots, candidate);
            if (base != null && !base.isDirectory()) result.add(readFile(base, ""));
            for (String profile : profiles) {
                if (!profile.matches("[\\w.-]+")) continue;
                String variant = candidate.substring(0, dot) + "-" + profile + candidate.substring(dot);
                VirtualFile file = findRelative(searchRoots, variant);
                if (file != null && !file.isDirectory()) result.add(readFile(file, profile));
            }
        }
        if (result.isEmpty() && !optional) throw new IOException("文件不存在：" + location);
        return result;
    }

    private static VirtualFile findRelative(List<VirtualFile> roots, String path) {
        for (VirtualFile root : roots) {
            VirtualFile file = root.findFileByRelativePath(path);
            if (file != null) return file;
        }
        return null;
    }
    private static void add(Set<VirtualFile> files, VirtualFile file) { if (file != null) files.add(file); }
}
