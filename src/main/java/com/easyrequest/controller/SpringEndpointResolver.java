package com.easyrequest.controller;

import com.easyrequest.compat.JavaCompat;

import com.easyrequest.http.UrlBuilder;
import com.easyrequest.model.KeyValue;
import com.easyrequest.model.RequestData;
import com.easyrequest.model.RequestDocumentation;
import com.google.gson.*;
import com.intellij.psi.*;
import java.util.*;

public final class SpringEndpointResolver {
    private static final String WEB = "org.springframework.web.bind.annotation.";
    private static final Map<String, String> MAPPINGS = new HashMap<>();
    static {
        MAPPINGS.put(WEB + "GetMapping", "GET");
        MAPPINGS.put(WEB + "PostMapping", "POST");
        MAPPINGS.put(WEB + "PutMapping", "PUT");
        MAPPINGS.put(WEB + "DeleteMapping", "DELETE");
        MAPPINGS.put(WEB + "PatchMapping", "PATCH");
        MAPPINGS.put(WEB + "RequestMapping", "");
    }
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    public static final class Endpoint {
        private final RequestData request;
        private final String note;
        public Endpoint(RequestData request, String note) {
            this.request = request;
            this.note = note;
        }
        public RequestData request() { return request; }
        public String note() { return note; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Endpoint)) return false;
            Endpoint that = (Endpoint) other;
            return java.util.Objects.equals(request, that.request) && java.util.Objects.equals(note, that.note);
        }
        @Override public int hashCode() { return java.util.Objects.hash(request, note); }
        @Override public String toString() { return request.method + " " + request.url; }
    }
    public static final class Route {
        private final String method;
        private final String classPath;
        private final String methodPath;
        public Route(String method, String classPath, String methodPath) {
            this.method = method;
            this.classPath = classPath;
            this.methodPath = methodPath;
        }
        public String method() { return method; }
        public String classPath() { return classPath; }
        public String methodPath() { return methodPath; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Route)) return false;
            Route that = (Route) other;
            return java.util.Objects.equals(method, that.method) && java.util.Objects.equals(classPath, that.classPath) && java.util.Objects.equals(methodPath, that.methodPath);
        }
        @Override public int hashCode() { return java.util.Objects.hash(method, classPath, methodPath); }
        @Override public String toString() { return "Route[" + "method=" + method + ", " + "classPath=" + classPath + ", " + "methodPath=" + methodPath + "]"; }
        public String path() { return UrlBuilder.joinPath(classPath, methodPath); }
    }
    private static final class Mapping {
        private final List<String> paths;
        private final List<String> methods;
        private final List<String> params;
        private final List<String> headers;
        private final List<String> consumes;
        private Mapping(List<String> paths, List<String> methods, List<String> params, List<String> headers, List<String> consumes) {
            this.paths = paths;
            this.methods = methods;
            this.params = params;
            this.headers = headers;
            this.consumes = consumes;
        }
        public List<String> paths() { return paths; }
        public List<String> methods() { return methods; }
        public List<String> params() { return params; }
        public List<String> headers() { return headers; }
        public List<String> consumes() { return consumes; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Mapping)) return false;
            Mapping that = (Mapping) other;
            return java.util.Objects.equals(paths, that.paths) && java.util.Objects.equals(methods, that.methods) && java.util.Objects.equals(params, that.params) && java.util.Objects.equals(headers, that.headers) && java.util.Objects.equals(consumes, that.consumes);
        }
        @Override public int hashCode() { return java.util.Objects.hash(paths, methods, params, headers, consumes); }
        @Override public String toString() { return "Mapping[" + "paths=" + paths + ", " + "methods=" + methods + ", " + "params=" + params + ", " + "headers=" + headers + ", " + "consumes=" + consumes + "]"; }
    }
    private SpringEndpointResolver() {}

    public static boolean hasMapping(PsiMethod method) { return method != null && methodMapping(method) != null; }

    public static List<Endpoint> resolve(PsiMethod method) {
        return resolve(method, method.getContainingClass(), null);
    }

    /** Resolve inherited methods in the concrete Controller's application, not the API library. */
    public static List<Endpoint> resolve(PsiMethod method, PsiClass controller, Route selectedRoute) {
        Mapping mapping = methodMapping(method);
        if (mapping == null || controller == null) return JavaCompat.list();
        Mapping parent = classMapping(controller, new HashSet<>());
        if (parent == null) parent = new Mapping(JavaCompat.list(""), JavaCompat.list(), JavaCompat.list(), JavaCompat.list(), JavaCompat.list());
        RequestData origin = new RequestData();
        com.intellij.openapi.module.Module sourceModule = com.intellij.openapi.module.ModuleUtilCore.findModuleForPsiElement(controller);
        origin.sourceModule = sourceModule == null ? "" : sourceModule.getName();
        com.intellij.openapi.vfs.VirtualFile sourceFile = controller.getContainingFile().getVirtualFile();
        origin.sourceFileUrl = sourceFile == null ? "" : sourceFile.getUrl();
        origin.sourceMethod = signature(method, controller);
        origin.name = endpointTitle(method);
        origin.catalog = ControllerCatalog.location(controller);
        SpringConfig.Result config = ProjectConfigResolver.forRequest(method.getProject(), origin).config();
        List<Endpoint> result = new ArrayList<>();
        for (Route route : routes(mapping, parent)) {
            if (selectedRoute != null && !selectedRoute.equals(route)) continue;
            RequestData request = origin.copy();
            request.method = route.method;
            request.url = UrlBuilder.joinPath(config.contextPath(), config.resolve(route.classPath), config.resolve(route.methodPath));
            EndpointIdentity.capture(method.getProject(), request);
            List<String> notes = new ArrayList<>(config.warnings());
            if (request.url.contains("${")) notes.add("地址含未解析的配置占位符，请完善项目配置后刷新接口树");
            populateParameters(method, request);
            UrlBuilder.variables(request.url).forEach(name -> {
                if (request.pathVariables.stream().noneMatch(p -> p.key.equals(name))) request.pathVariables.add(new KeyValue(name, "1"));
            });
            applyConditions(parent.params, request.query, notes);
            applyConditions(mapping.params, request.query, notes);
            applyConditions(parent.headers, request.headers, notes);
            applyConditions(mapping.headers, request.headers, notes);
            List<String> consumes = mapping.consumes.isEmpty() ? parent.consumes : mapping.consumes;
            if (!consumes.isEmpty()) {
                String contentType = consumes.get(0);
                if (contentType.contains("multipart/form-data")) request.bodyType = "MULTIPART";
                else if (contentType.contains("application/x-www-form-urlencoded")) {
                    request.bodyType = "URLENCODED";
                    request.form.addAll(request.query); request.query.clear();
                } else if (!contentType.contains("*")) request.headers.add(new KeyValue("Content-Type", contentType));
            }
            if (request.bodyType.equals("MULTIPART")) {
                request.form.addAll(request.query); request.query.clear();
            }
            if (!JavaCompat.isBlank(request.body) && !JavaCompat.set("JSON", "TEXT", "XML").contains(request.bodyType))
                notes.add("该方法同时声明了请求体和表单，请按服务端要求检查 Body");
            result.add(new Endpoint(request, String.join("；", notes)));
        }
        return result;
    }

    public static List<Route> routes(PsiMethod method, PsiClass controller) {
        Mapping mapping = methodMapping(method);
        if (mapping == null) return JavaCompat.list();
        Mapping parent = classMapping(controller, new HashSet<>());
        if (parent == null) parent = new Mapping(JavaCompat.list(""), JavaCompat.list(), JavaCompat.list(), JavaCompat.list(), JavaCompat.list());
        return routes(mapping, parent);
    }

    private static List<Route> routes(Mapping mapping, Mapping parent) {
        List<String> verbs = mapping.methods.isEmpty() ? parent.methods : mapping.methods;
        if (verbs.isEmpty()) verbs = JavaCompat.list("GET");
        Set<Route> result = new LinkedHashSet<>();
        for (String classPath : parent.paths) for (String methodPath : mapping.paths) for (String verb : verbs) {
            result.add(new Route(verb, classPath, methodPath));
            if (result.size() >= 100) return JavaCompat.copyList(result);
        }
        return JavaCompat.copyList(result);
    }

    static String signature(PsiMethod method, PsiClass controller) {
        return Objects.toString(controller.getQualifiedName(), controller.getName()) + "#" + method.getName()
                + "(" + String.join(",", Arrays.stream(method.getParameterList().getParameters())
                .map(parameter -> parameter.getType().getCanonicalText()).collect(java.util.stream.Collectors.toList())) + ")";
    }

    static String endpointTitle(PsiMethod method) {
        Deque<PsiMethod> pending = new ArrayDeque<>();
        Set<PsiMethod> visited = new HashSet<>();
        pending.add(method);
        while (!pending.isEmpty()) {
            PsiMethod candidate = pending.removeFirst();
            if (!visited.add(candidate)) continue;
            String title = annotationTitle(candidate, "io.swagger.v3.oas.annotations.Operation", "summary");
            if (JavaCompat.isBlank(title)) title = annotationTitle(candidate, "io.swagger.annotations.ApiOperation", "value");
            if (!JavaCompat.isBlank(title)) return title;
            Collections.addAll(pending, candidate.findSuperMethods());
        }
        return method.getName();
    }

    static String controllerTitle(PsiClass controller) {
        String title = annotationTitle(controller, "io.swagger.v3.oas.annotations.tags.Tag", "name");
        if (JavaCompat.isBlank(title)) title = annotationTitle(controller, "io.swagger.annotations.Api", "tags");
        if (JavaCompat.isBlank(title)) title = annotationTitle(controller, "io.swagger.annotations.Api", "value");
        return JavaCompat.isBlank(title) ? Objects.toString(controller.getName(), "Controller") : title;
    }

    static String annotationTitle(PsiModifierListOwner owner, String annotationName, String attribute) {
        PsiAnnotation annotation = owner.getAnnotation(annotationName);
        if (annotation == null) return "";
        PsiAnnotationMemberValue attributeValue = annotation.findAttributeValue(attribute);
        if (attributeValue == null) return "";
        PsiAnnotationMemberValue[] values = attributeValue instanceof PsiArrayInitializerMemberValue
                ? ((PsiArrayInitializerMemberValue) attributeValue).getInitializers() : new PsiAnnotationMemberValue[]{attributeValue};
        for (PsiAnnotationMemberValue value : values) {
            Object resolved = evaluate(value, new HashSet<>(), 0);
            if (resolved instanceof String && !JavaCompat.isBlank(((String) resolved))) return JavaCompat.strip(((String) resolved));
        }
        return "";
    }

    private static void applyConditions(List<String> conditions, List<KeyValue> rows, List<String> notes) {
        for (String condition : conditions) {
            if (condition.startsWith("!")) { rows.removeIf(r -> r.key.equals(condition.substring(1))); continue; }
            if (condition.contains("!=")) { notes.add("路由条件：" + condition); continue; }
            String[] pair = condition.split("=", 2);
            String description = rows.stream().filter(row -> row.key.equalsIgnoreCase(pair[0]))
                    .map(row -> row.description).findFirst().orElse("");
            rows.removeIf(row -> row.key.equalsIgnoreCase(pair[0]));
            KeyValue value = new KeyValue(pair[0], pair.length == 2 ? pair[1] : "");
            value.description = description; rows.add(value);
        }
    }

    private static void populateParameters(PsiMethod method, RequestData request) {
        PsiParameter[] parameters = method.getParameterList().getParameters();
        PsiMethod[] supers = method.findSuperMethods();
        for (int index = 0; index < parameters.length; index++) {
            PsiParameter parameter = parameters[index];
            PsiParameter annotated = parameter;
            if (Arrays.stream(parameter.getAnnotations()).noneMatch(a -> Optional.ofNullable(a.getQualifiedName()).orElse("").startsWith(WEB))) {
                for (PsiMethod parent : supers) if (parent.getParameterList().getParametersCount() > index) {
                    PsiParameter inherited = parent.getParameterList().getParameters()[index];
                    if (inherited.getAnnotations().length > 0) { annotated = inherited; break; }
                }
            }
            PsiType type = parameter.getType();
            String description = ParameterDocumentation.parameter(method, index);
            String raw = type.getCanonicalText();
            boolean servletPart = raw.equals("jakarta.servlet.http.Part") || raw.equals("javax.servlet.http.Part");
            if ((!servletPart && (raw.startsWith("jakarta.servlet.") || raw.startsWith("javax.servlet."))) || raw.equals("org.springframework.validation.BindingResult")
                    || raw.equals("org.springframework.validation.Errors") || raw.startsWith("org.springframework.ui.") || raw.equals("java.security.Principal")) continue;
            if (annotated.getAnnotation(WEB + "RequestBody") != null) {
                request.bodyType = ExampleGenerator.simple(type) && raw.equals("java.lang.String") ? "TEXT" : "JSON";
                com.easyrequest.controller.ExampleGenerator.DocumentedExample example = ExampleGenerator.documented(type);
                request.body = request.bodyType.equals("TEXT") ? "string" : JSON.toJson(example.value());
                request.bodyDescriptions.putAll(example.descriptions());
                if (!JavaCompat.isBlank(description)) request.bodyDescriptions.put("", description);
                continue;
            }
            PsiAnnotation path = annotated.getAnnotation(WEB + "PathVariable");
            PsiAnnotation header = annotated.getAnnotation(WEB + "RequestHeader");
            PsiAnnotation query = annotated.getAnnotation(WEB + "RequestParam");
            PsiAnnotation part = annotated.getAnnotation(WEB + "RequestPart");
            if (annotated.getAnnotation(WEB + "CookieValue") != null) {
                PsiAnnotation cookie = annotated.getAnnotation(WEB + "CookieValue");
                String pair = parameterName(cookie, parameter) + "=" + parameterValue(cookie, type);
                Optional<KeyValue> existing = request.headers.stream().filter(h -> h.key.equals("Cookie")).findFirst();
                KeyValue value = existing.orElseGet(() -> { KeyValue row = new KeyValue("Cookie", ""); request.headers.add(row); return row; });
                value.value += (value.value.isEmpty() ? "" : "; ") + pair;
                if (!JavaCompat.isBlank(description)) value.description += (JavaCompat.isBlank(value.description) ? "" : "；") + parameterName(cookie, parameter) + "：" + description;
                continue;
            }
            if (annotated.getAnnotation(WEB + "RequestAttribute") != null || annotated.getAnnotation(WEB + "SessionAttribute") != null) continue;
            PsiAnnotation binding = path != null ? path : header != null ? header : query != null ? query : part;
            String name = parameterName(binding, parameter);
            KeyValue value = new KeyValue(name, parameterValue(binding, type));
            value.description = description;
            boolean file = raw.contains("MultipartFile") || servletPart;
            if (file || part != null) {
                request.bodyType = "MULTIPART"; value.file = file;
                value.value = file ? "" : ExampleGenerator.simple(type) ? ExampleGenerator.scalar(type) : JSON.toJson(ExampleGenerator.example(type));
                if (!file) value.contentType = ExampleGenerator.simple(type) ? "text/plain; charset=UTF-8" : "application/json";
                request.form.add(value);
            } else if (path != null) request.pathVariables.add(value);
            else if (header != null) request.headers.add(value);
            else if (ExampleGenerator.simple(type) || query != null) request.query.add(value);
            else {
                com.easyrequest.controller.ExampleGenerator.DocumentedExample example = ExampleGenerator.documented(type);
                if (example.value().isJsonObject()) flattenQuery("", "", example.value().getAsJsonObject(), example.descriptions(), request.query);
            }
        }
    }

    private static void flattenQuery(String prefix, String path, JsonObject value, Map<String, String> descriptions, List<KeyValue> rows) {
        for (java.util.Map.Entry<java.lang.String,com.google.gson.JsonElement> entry : value.entrySet()) {
            String name = prefix + entry.getKey();
            String fieldPath = RequestDocumentation.childPath(path, entry.getKey());
            JsonElement example = entry.getValue();
            if (example.isJsonObject()) flattenQuery(name + ".", fieldPath, example.getAsJsonObject(), descriptions, rows);
            else if (example.isJsonArray()) {
                for (JsonElement item : example.getAsJsonArray()) addDocumentedRow(rows, name, item.isJsonPrimitive() ? item.getAsString() : item.toString(), descriptions.get(fieldPath));
            } else addDocumentedRow(rows, name, example.isJsonNull() ? "" : example.getAsString(), descriptions.get(fieldPath));
        }
    }

    private static void addDocumentedRow(List<KeyValue> rows, String name, String value, String description) {
        KeyValue row = new KeyValue(name, value); row.description = Objects.toString(description, ""); rows.add(row);
    }

    private static String parameterName(PsiAnnotation annotation, PsiParameter parameter) {
        String name = annotation == null ? "" : stringAttribute(annotation, "name");
        if (JavaCompat.isBlank(name) && annotation != null) name = stringAttribute(annotation, "value");
        return JavaCompat.isBlank(name) ? parameter.getName() : name;
    }
    private static String parameterValue(PsiAnnotation annotation, PsiType type) {
        String value = annotation == null ? "" : stringAttribute(annotation, "defaultValue");
        return JavaCompat.isBlank(value) || value.contains("\ue000") ? ExampleGenerator.scalar(type) : value;
    }

    private static Mapping methodMapping(PsiMethod method) {
        Deque<PsiMethod> pending = new ArrayDeque<>();
        Set<PsiMethod> visited = new HashSet<>(); pending.add(method);
        while (!pending.isEmpty()) {
            PsiMethod candidate = pending.removeFirst();
            if (!visited.add(candidate)) continue;
            Mapping found = mapping(candidate);
            if (found != null) return found;
            Collections.addAll(pending, candidate.findSuperMethods());
        }
        return null;
    }

    static boolean hasControllerAnnotation(PsiClass controller) {
        if (classMapping(controller, new HashSet<>()) != null) return true;
        Deque<PsiAnnotation> annotations = new ArrayDeque<>(Arrays.asList(controller.getAnnotations()));
        Set<String> seen = new HashSet<>();
        while (!annotations.isEmpty()) {
            PsiAnnotation annotation = annotations.removeFirst();
            String name = annotation.getQualifiedName();
            if (name == null || !seen.add(name)) continue;
            if (name.equals(WEB + "RestController") || name.equals("org.springframework.stereotype.Controller")) return true;
            if (name.startsWith("java.") || name.startsWith("org.jetbrains.")) continue;
            PsiJavaCodeReferenceElement reference = annotation.getNameReferenceElement();
            if (reference != null && reference.resolve() instanceof PsiClass)
                Collections.addAll(annotations, ((PsiClass) reference.resolve()).getAnnotations());
        }
        return false;
    }
    private static Mapping classMapping(PsiClass cls, Set<PsiClass> visited) {
        if (cls == null || !visited.add(cls)) return null;
        Mapping own = mapping(cls);
        if (own != null) return own;
        for (PsiClass parent : cls.getSupers()) { Mapping inherited = classMapping(parent, visited); if (inherited != null) return inherited; }
        return null;
    }
    private static Mapping mapping(PsiModifierListOwner owner) {
        for (PsiAnnotation annotation : owner.getAnnotations()) {
            Mapping result = mappingAnnotation(annotation, new HashSet<>());
            if (result != null) return result;
        }
        return null;
    }
    private static Mapping mappingAnnotation(PsiAnnotation annotation, Set<String> visited) {
        String name = annotation.getQualifiedName();
        if (name == null || !visited.add(name)) return null;
        if (MAPPINGS.containsKey(name)) {
            List<String> methods = JavaCompat.isBlank(MAPPINGS.get(name)) ? attributes(annotation, "method").stream()
                    .map(s -> s.substring(s.lastIndexOf('.') + 1)).collect(java.util.stream.Collectors.toList()) : JavaCompat.list(MAPPINGS.get(name));
            return new Mapping(paths(annotation), methods, attributes(annotation, "params"), attributes(annotation, "headers"), attributes(annotation, "consumes"));
        }
        if (name.startsWith("java.") || name.startsWith("org.jetbrains.")) return null;
        PsiJavaCodeReferenceElement reference = annotation.getNameReferenceElement();
        if (reference != null && reference.resolve() instanceof PsiClass) {
            for (PsiAnnotation meta : ((PsiClass) reference.resolve()).getAnnotations()) {
                Mapping inherited = mappingAnnotation(meta, visited);
                if (inherited == null) continue;
                List<String> override = attributes(annotation, "path");
                if (override.isEmpty()) override = attributes(annotation, "value");
                return new Mapping(override.isEmpty() ? inherited.paths : override, inherited.methods,
                        inherited.params, inherited.headers, inherited.consumes);
            }
        }
        return null;
    }
    private static List<String> paths(PsiAnnotation annotation) {
        List<String> values = attributes(annotation, "path");
        if (values.isEmpty()) values = attributes(annotation, "value");
        return values.isEmpty() ? JavaCompat.list("") : values;
    }
    public static String stringAttribute(PsiAnnotation annotation, String name) {
        List<String> values = attributes(annotation, name); return values.isEmpty() ? "" : values.get(0);
    }
    private static List<String> attributes(PsiAnnotation annotation, String name) {
        PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue(name);
        if (value == null) return JavaCompat.list();
        if (value instanceof PsiArrayInitializerMemberValue) return Arrays.stream(((PsiArrayInitializerMemberValue) value).getInitializers()).map(SpringEndpointResolver::constant).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toList());
        String result = constant(value); return result.isEmpty() ? JavaCompat.list() : JavaCompat.list(result);
    }
    private static String constant(PsiAnnotationMemberValue value) {
        Object result = evaluate(value, new HashSet<>(), 0);
        return result == null ? value.getText() : result.toString();
    }
    private static Object evaluate(PsiElement value, Set<PsiElement> visiting, int depth) {
        if (value == null || depth > 20 || !visiting.add(value)) return null;
        try {
            Object result = JavaPsiFacade.getInstance(value.getProject()).getConstantEvaluationHelper().computeConstantExpression(value);
            if (result != null) return result;
            if (value instanceof PsiLiteralExpression) return ((PsiLiteralExpression) value).getValue();
            if (value instanceof PsiParenthesizedExpression) return evaluate(((PsiParenthesizedExpression) value).getExpression(), visiting, depth + 1);
            if (value instanceof PsiReferenceExpression && ((PsiReferenceExpression) value).resolve() instanceof PsiField) {
                if (((PsiField) ((PsiReferenceExpression) value).resolve()) instanceof PsiEnumConstant) return ((PsiField) ((PsiReferenceExpression) value).resolve()).getName();
                if (((PsiField) ((PsiReferenceExpression) value).resolve()).hasModifierProperty(PsiModifier.FINAL)) return evaluate(((PsiField) ((PsiReferenceExpression) value).resolve()).getInitializer(), visiting, depth + 1);
            }
            // String concatenation also works while a project's JDK/library types are not fully resolved.
            if (value instanceof PsiPolyadicExpression && ((PsiPolyadicExpression) value).getOperationTokenType() == JavaTokenType.PLUS) {
                StringBuilder text = new StringBuilder();
                boolean stringSeen = false;
                for (PsiExpression operand : ((PsiPolyadicExpression) value).getOperands()) {
                    Object item = evaluate(operand, visiting, depth + 1);
                    if (item == null) return null;
                    stringSeen |= item instanceof String;
                    if (!stringSeen) return null;
                    text.append(item);
                }
                return text.toString();
            }
            return null;
        } finally { visiting.remove(value); }
    }
}
