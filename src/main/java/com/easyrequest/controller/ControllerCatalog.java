package com.easyrequest.controller;

import com.easyrequest.compat.JavaCompat;

import com.easyrequest.model.CatalogLocation;
import com.easyrequest.model.RequestData;
import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.psi.*;
import com.intellij.psi.search.FileTypeIndex;
import com.intellij.psi.search.GlobalSearchScope;
import java.util.*;

/** Read-action snapshots used to initialize and incrementally merge the persisted request catalog. */
public final class ControllerCatalog {
    public static final class Entry {
        private final String id;
        private final String title;
        private final String signature;
        private final SpringEndpointResolver.Route route;
        private final SmartPsiElementPointer<PsiClass> controller;
        private final SmartPsiElementPointer<PsiMethod> method;
        public Entry(String id, String title, String signature, SpringEndpointResolver.Route route, SmartPsiElementPointer<PsiClass> controller, SmartPsiElementPointer<PsiMethod> method) {
            this.id = id;
            this.title = title;
            this.signature = signature;
            this.route = route;
            this.controller = controller;
            this.method = method;
        }
        public String id() { return id; }
        public String title() { return title; }
        public String signature() { return signature; }
        public SpringEndpointResolver.Route route() { return route; }
        public SmartPsiElementPointer<PsiClass> controller() { return controller; }
        public SmartPsiElementPointer<PsiMethod> method() { return method; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Entry)) return false;
            Entry that = (Entry) other;
            return java.util.Objects.equals(id, that.id) && java.util.Objects.equals(title, that.title) && java.util.Objects.equals(signature, that.signature) && java.util.Objects.equals(route, that.route) && java.util.Objects.equals(controller, that.controller) && java.util.Objects.equals(method, that.method);
        }
        @Override public int hashCode() { return java.util.Objects.hash(id, title, signature, route, controller, method); }
        @Override public String toString() { return "Entry[" + "id=" + id + ", " + "title=" + title + ", " + "signature=" + signature + ", " + "route=" + route + ", " + "controller=" + controller + ", " + "method=" + method + "]"; }
        public Optional<SpringEndpointResolver.Endpoint> resolve() {
            PsiClass type = controller.getElement();
            PsiMethod current = method.getElement();
            if (type == null || current == null || !signature.equals(SpringEndpointResolver.signature(current, type)))
                return Optional.empty();
            return SpringEndpointResolver.resolve(current, type, route).stream().findFirst();
        }
    }
    public static final class Controller {
        private final String id;
        private final String groupId;
        private final String groupName;
        private final String groupPath;
        private final String title;
        private final String className;
        private final String filePath;
        private final List<Entry> endpoints;
        public Controller(String id, String groupId, String groupName, String groupPath, String title, String className, String filePath, List<Entry> endpoints) {
            this.id = id;
            this.groupId = groupId;
            this.groupName = groupName;
            this.groupPath = groupPath;
            this.title = title;
            this.className = className;
            this.filePath = filePath;
            this.endpoints = endpoints;
        }
        public String id() { return id; }
        public String groupId() { return groupId; }
        public String groupName() { return groupName; }
        public String groupPath() { return groupPath; }
        public String title() { return title; }
        public String className() { return className; }
        public String filePath() { return filePath; }
        public List<Entry> endpoints() { return endpoints; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Controller)) return false;
            Controller that = (Controller) other;
            return java.util.Objects.equals(id, that.id) && java.util.Objects.equals(groupId, that.groupId) && java.util.Objects.equals(groupName, that.groupName) && java.util.Objects.equals(groupPath, that.groupPath) && java.util.Objects.equals(title, that.title) && java.util.Objects.equals(className, that.className) && java.util.Objects.equals(filePath, that.filePath) && java.util.Objects.equals(endpoints, that.endpoints);
        }
        @Override public int hashCode() { return java.util.Objects.hash(id, groupId, groupName, groupPath, title, className, filePath, endpoints); }
        @Override public String toString() { return "Controller[" + "id=" + id + ", " + "groupId=" + groupId + ", " + "groupName=" + groupName + ", " + "groupPath=" + groupPath + ", " + "title=" + title + ", " + "className=" + className + ", " + "filePath=" + filePath + ", " + "endpoints=" + endpoints + "]"; }
    }

    /** Discovery only lists source files; PSI and default parameters are resolved one project at a time. */
    public static final class ProjectBatch {
        private final String id;
        private final String name;
        private final String path;
        private final List<VirtualFile> files;
        private final Set<String> savedIds;
        public ProjectBatch(String id, String name, String path, List<VirtualFile> files, Set<String> savedIds) {
            this.id = id;
            this.name = name;
            this.path = path;
            this.files = files;
            this.savedIds = savedIds;
        }
        public String id() { return id; }
        public String name() { return name; }
        public String path() { return path; }
        public List<VirtualFile> files() { return files; }
        public Set<String> savedIds() { return savedIds; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ProjectBatch)) return false;
            ProjectBatch that = (ProjectBatch) other;
            return java.util.Objects.equals(id, that.id) && java.util.Objects.equals(name, that.name) && java.util.Objects.equals(path, that.path) && java.util.Objects.equals(files, that.files) && java.util.Objects.equals(savedIds, that.savedIds);
        }
        @Override public int hashCode() { return java.util.Objects.hash(id, name, path, files, savedIds); }
        @Override public String toString() { return "ProjectBatch[" + "id=" + id + ", " + "name=" + name + ", " + "path=" + path + ", " + "files=" + files + ", " + "savedIds=" + savedIds + "]"; }
    }
    private static final class Group {
        private final String id;
        private final String name;
        private final String path;
        private Group(String id, String name, String path) {
            this.id = id;
            this.name = name;
            this.path = path;
        }
        public String id() { return id; }
        public String name() { return name; }
        public String path() { return path; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Group)) return false;
            Group that = (Group) other;
            return java.util.Objects.equals(id, that.id) && java.util.Objects.equals(name, that.name) && java.util.Objects.equals(path, that.path);
        }
        @Override public int hashCode() { return java.util.Objects.hash(id, name, path); }
        @Override public String toString() { return "Group[" + "id=" + id + ", " + "name=" + name + ", " + "path=" + path + "]"; }
    }

    private ControllerCatalog() {}

    private static Group group(VirtualFile file, Module module, Map<VirtualFile, Group> cache) {
        VirtualFile logical = ProjectRoots.logicalRoot(file, module);
        if (logical == null) logical = file.isDirectory() ? file : file.getParent();
        return cache.computeIfAbsent(logical, root -> {
            VirtualFile owner = ProjectRoots.groupRoot(root);
            return new Group(owner.getUrl(), owner.getName(), owner.getPath());
        });
    }

    public static List<ProjectBatch> batches(Project project, List<RequestData> saved) {
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        Map<VirtualFile, Group> cache = new HashMap<>();
        Map<Group, List<VirtualFile>> files = new LinkedHashMap<>();
        Map<Group, Set<String>> ids = new HashMap<>();
        for (VirtualFile file : FileTypeIndex.getFiles(JavaFileType.INSTANCE, GlobalSearchScope.projectScope(project))) {
            ProgressManager.checkCanceled();
            if (!index.isInSourceContent(file) || index.isInTestSourceContent(file) || index.isInLibrary(file)) continue;
            Group owner = group(file, index.getModuleForFile(file), cache);
            files.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(file);
        }
        // Include saved projects even when their last Controller (or entire directory) was deleted.
        for (RequestData request : saved) {
            ProgressManager.checkCanceled();
            if (!request.catalogManaged) continue;
            VirtualFile origin = VirtualFileManager.getInstance().findFileByUrl(request.sourceFileUrl);
            if (origin == null) origin = VirtualFileManager.getInstance().findFileByUrl(request.sourceProjectUrl);
            if (origin == null && !JavaCompat.isBlank(request.catalog.groupPath))
                origin = LocalFileSystem.getInstance().findFileByPath(request.catalog.groupPath);
            Group owner = origin == null
                    ? new Group(request.catalog.groupId, request.catalog.groupName, request.catalog.groupPath)
                    : group(origin, index.getModuleForFile(origin), cache);
            files.computeIfAbsent(owner, ignored -> new ArrayList<>());
            ids.computeIfAbsent(owner, ignored -> new HashSet<>()).add(request.id);
        }
        return files.entrySet().stream().map(entry -> new ProjectBatch(entry.getKey().id(), entry.getKey().name(),
                        entry.getKey().path(), JavaCompat.copyList(entry.getValue()), JavaCompat.copySet(ids.getOrDefault(entry.getKey(), JavaCompat.set()))))
                .sorted(Comparator.comparing(ProjectBatch::name, String.CASE_INSENSITIVE_ORDER).thenComparing(ProjectBatch::id)).collect(java.util.stream.Collectors.toList());
    }

    public static CatalogLocation location(PsiClass controller) {
        CatalogLocation location = new CatalogLocation();
        VirtualFile file = controller.getContainingFile().getVirtualFile();
        if (file == null) return location;
        Module module = com.intellij.openapi.module.ModuleUtilCore.findModuleForPsiElement(controller);
        Group owner = group(file, module, new HashMap<>());
        location.groupPath = owner.path();
        location.groupId = owner.id();
        location.groupName = owner.name();
        location.className = Objects.toString(controller.getQualifiedName(), controller.getName());
        location.controllerId = file.getUrl() + "#" + location.className;
        location.controllerTitle = SpringEndpointResolver.controllerTitle(controller);
        location.filePath = file.getPath();
        return location;
    }

    public static List<RequestData> requests(Project project) {
        List<RequestData> result = new ArrayList<>();
        for (ProjectBatch batch : batches(project, JavaCompat.list())) result.addAll(requests(project, batch));
        return JavaCompat.copyList(result);
    }

    public static List<RequestData> requests(Project project, ProjectBatch batch) {
        List<RequestData> result = new ArrayList<>();
        for (Controller controller : scan(project, batch)) {
            for (Entry entry : controller.endpoints()) {
                ProgressManager.checkCanceled();
                // A failed scan must not mark stored endpoints as missing from the source.
                result.add(entry.resolve().orElseThrow(() -> new IllegalStateException("接口扫描期间已变更，请重试")).request());
            }
        }
        return JavaCompat.copyList(result);
    }

    public static List<Controller> scan(Project project) {
        List<Controller> result = new ArrayList<>();
        for (ProjectBatch batch : batches(project, JavaCompat.list())) result.addAll(scan(project, batch));
        return JavaCompat.copyList(result);
    }

    private static List<Controller> scan(Project project, ProjectBatch batch) {
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        PsiManager manager = PsiManager.getInstance(project);
        SmartPointerManager pointers = SmartPointerManager.getInstance(project);
        List<Controller> result = new ArrayList<>();
        for (VirtualFile file : batch.files()) {
            ProgressManager.checkCanceled();
            if (!file.isValid() || !index.isInSourceContent(file) || index.isInTestSourceContent(file) || index.isInLibrary(file)) continue;
            if (!(manager.findFile(file) instanceof PsiJavaFile)) continue;
            Deque<PsiClass> classes = new ArrayDeque<>(Arrays.asList(((PsiJavaFile) manager.findFile(file)).getClasses()));
            while (!classes.isEmpty()) {
                ProgressManager.checkCanceled();
                PsiClass type = classes.removeFirst();
                Collections.addAll(classes, type.getInnerClasses());
                if (type.isAnnotationType() || type.isEnum() || type.getName() == null) continue;
                // Include mapped API interfaces as well as concrete Spring Controllers.
                if (!SpringEndpointResolver.hasControllerAnnotation(type)
                        && Arrays.stream(type.getMethods()).noneMatch(SpringEndpointResolver::hasMapping)) continue;
                String className = Objects.toString(type.getQualifiedName(), type.getName());
                String id = file.getUrl() + "#" + className;
                com.intellij.psi.SmartPsiElementPointer<com.intellij.psi.PsiClass> classPointer = pointers.createSmartPsiElementPointer(type);
                List<Entry> entries = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                List<PsiMethod> methods = new ArrayList<>(Arrays.asList(type.getMethods()));
                Collections.addAll(methods, type.getAllMethods());
                for (PsiMethod method : methods) {
                    ProgressManager.checkCanceled();
                    String signature = SpringEndpointResolver.signature(method, type);
                    if (!seen.add(signature)) continue;
                    List<SpringEndpointResolver.Route> routes = SpringEndpointResolver.routes(method, type);
                    if (routes.isEmpty()) continue;
                    String title = SpringEndpointResolver.endpointTitle(method);
                    com.intellij.psi.SmartPsiElementPointer<com.intellij.psi.PsiMethod> methodPointer = pointers.createSmartPsiElementPointer(method);
                    for (com.easyrequest.controller.SpringEndpointResolver.Route route : routes) entries.add(new Entry(id + "\n" + signature + "\n" + route,
                            title, signature, route, classPointer, methodPointer));
                }
                if (entries.isEmpty()) continue;
                entries.sort(Comparator.comparing(Entry::title, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(e -> e.route().path()).thenComparing(e -> e.route().method()).thenComparing(Entry::id));
                result.add(new Controller(id, batch.id(), batch.name(), batch.path(), SpringEndpointResolver.controllerTitle(type),
                        className, file.getPath(), JavaCompat.copyList(entries)));
            }
        }
        result.sort(Comparator.comparing(Controller::groupName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Controller::groupId).thenComparing(Controller::title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Controller::id));
        return JavaCompat.copyList(result);
    }
}
