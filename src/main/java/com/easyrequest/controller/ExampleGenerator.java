package com.easyrequest.controller;

import com.easyrequest.compat.JavaCompat;

import com.google.gson.*;
import com.easyrequest.model.RequestDocumentation;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtil;
import java.util.*;

/** Bounded traversal: recursive DTOs and large object graphs cannot freeze the editor. */
public final class ExampleGenerator {
    private ExampleGenerator() {}
    public static final class DocumentedExample {
        private final JsonElement value;
        private final Map<String, String> descriptions;
        public DocumentedExample(JsonElement value, Map<String, String> descriptions) {
            this.value = value;
            this.descriptions = descriptions;
        }
        public JsonElement value() { return value; }
        public Map<String, String> descriptions() { return descriptions; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof DocumentedExample)) return false;
            DocumentedExample that = (DocumentedExample) other;
            return java.util.Objects.equals(value, that.value) && java.util.Objects.equals(descriptions, that.descriptions);
        }
        @Override public int hashCode() { return java.util.Objects.hash(value, descriptions); }
        @Override public String toString() { return "DocumentedExample[" + "value=" + value + ", " + "descriptions=" + descriptions + "]"; }
    }
    public static JsonElement example(PsiType type) { return documented(type).value(); }
    public static DocumentedExample documented(PsiType type) {
        Map<String, String> descriptions = new LinkedHashMap<>();
        return new DocumentedExample(example(type, 0, new HashSet<>(), "", descriptions), descriptions);
    }

    private static JsonElement example(PsiType type, int depth, Set<String> visiting, String path, Map<String, String> descriptions) {
        if (type == null || depth > 4) return JsonNull.INSTANCE;
        String name = type.getCanonicalText();
        String raw = name.replaceAll("<.*", "");
        if (JavaCompat.set("boolean", "java.lang.Boolean").contains(raw)) return new JsonPrimitive(true);
        if (JavaCompat.set("byte", "short", "int", "long", "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long", "java.math.BigInteger").contains(raw)) return new JsonPrimitive(1);
        if (JavaCompat.set("float", "double", "java.lang.Float", "java.lang.Double", "java.math.BigDecimal").contains(raw)) return new JsonPrimitive(1.0);
        if (raw.equals("java.time.LocalDate")) return new JsonPrimitive("2026-01-01");
        if (raw.equals("java.time.LocalDateTime")) return new JsonPrimitive("2026-01-01T12:00:00");
        if (raw.equals("java.time.LocalTime")) return new JsonPrimitive("12:00:00");
        if (raw.equals("java.util.Date") || raw.equals("java.time.Instant") || raw.equals("java.time.OffsetDateTime")) return new JsonPrimitive("2026-01-01T12:00:00Z");
        if (raw.equals("java.util.UUID")) return new JsonPrimitive("00000000-0000-0000-0000-000000000001");
        if (JavaCompat.set("java.lang.String", "java.lang.Character", "char", "java.lang.Object").contains(raw)) return new JsonPrimitive("string");
        if (type instanceof PsiArrayType) {
            JsonArray result = new JsonArray(); result.add(example(((PsiArrayType) type).getComponentType(), depth + 1, visiting, path + "/*", descriptions)); return result;
        }
        if (!(type instanceof PsiClassType)) return new JsonPrimitive("string");
        PsiType[] arguments = ((PsiClassType) type).getParameters();
        if (JavaCompat.set("java.util.List", "java.util.Set", "java.util.Collection", "java.lang.Iterable", "java.util.ArrayList").contains(raw)) {
            JsonArray result = new JsonArray(); result.add(arguments.length > 0 ? example(arguments[0], depth + 1, visiting, path + "/*", descriptions) : new JsonPrimitive("string")); return result;
        }
        if (raw.equals("java.util.Optional")) return arguments.length > 0 ? example(arguments[0], depth + 1, visiting, path, descriptions) : JsonNull.INSTANCE;
        if (raw.equals("java.util.Map") || raw.equals("java.util.HashMap")) {
            JsonObject result = new JsonObject(); result.add("key", arguments.length > 1 ? example(arguments[1], depth + 1, visiting, path + "/*", descriptions) : new JsonPrimitive("value")); return result;
        }
        PsiClassType.ClassResolveResult resolved = ((PsiClassType) type).resolveGenerics();
        PsiClass cls = resolved.getElement();
        if (cls == null) return new JsonObject();
        if (cls.isEnum()) {
            for (PsiField field : cls.getFields()) if (field instanceof PsiEnumConstant) return new JsonPrimitive(field.getName());
        }
        if (!visiting.add(name)) return JsonNull.INSTANCE;
        JsonObject result = new JsonObject();
        try {
            int count = 0;
            for (PsiField field : cls.getAllFields()) {
                if (count >= 40) break;
                if (field.hasModifierProperty(PsiModifier.STATIC) || field.hasModifierProperty(PsiModifier.TRANSIENT)) continue;
                PsiAnnotation ignore = field.getAnnotation("com.fasterxml.jackson.annotation.JsonIgnore");
                if (ignore != null && !"false".equals(SpringEndpointResolver.stringAttribute(ignore, "value"))) continue;
                String fieldName = field.getName();
                PsiAnnotation property = field.getAnnotation("com.fasterxml.jackson.annotation.JsonProperty");
                if (property != null) {
                    String access = SpringEndpointResolver.stringAttribute(property, "access");
                    if (access.endsWith("READ_ONLY")) continue;
                    String alias = SpringEndpointResolver.stringAttribute(property, "value");
                    if (!JavaCompat.isBlank(alias)) fieldName = alias;
                }
                if (result.has(fieldName)) continue;
                PsiType substituted = resolved.getSubstitutor().substitute(field.getType());
                String fieldPath = RequestDocumentation.childPath(path, fieldName);
                String description = ParameterDocumentation.field(field);
                if (!JavaCompat.isBlank(description)) descriptions.put(fieldPath, description);
                JsonElement value = example(substituted, depth + 1, visiting, fieldPath, descriptions);
                PsiAnnotation schema = field.getAnnotation("io.swagger.v3.oas.annotations.media.Schema");
                String supplied = schema == null ? "" : SpringEndpointResolver.stringAttribute(schema, "example");
                if (JavaCompat.isBlank(supplied)) {
                    PsiAnnotation swagger = field.getAnnotation("io.swagger.annotations.ApiModelProperty");
                    if (swagger != null) supplied = SpringEndpointResolver.stringAttribute(swagger, "example");
                }
                if (!JavaCompat.isBlank(supplied)) {
                    try { value = value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? new JsonPrimitive(supplied) : JsonParser.parseString(supplied); }
                    catch (Exception ignored) { value = new JsonPrimitive(supplied); }
                }
                result.add(fieldName, value); count++;
            }
        } finally { visiting.remove(name); }
        return result;
    }

    public static String scalar(PsiType type) {
        JsonElement value = example(type);
        if (value.isJsonArray() && !value.getAsJsonArray().isEmpty()) value = value.getAsJsonArray().get(0);
        return value.isJsonPrimitive() ? value.getAsString() : "";
    }

    public static boolean simple(PsiType type) {
        if (type instanceof PsiPrimitiveType || type instanceof PsiArrayType) return true;
        String name = type.getCanonicalText();
        PsiClass cls = PsiUtil.resolveClassInClassTypeOnly(type);
        return name.startsWith("java.lang.") || name.startsWith("java.time.") || name.startsWith("java.math.")
                || name.equals("java.util.Date") || name.equals("java.util.UUID") || (cls != null && cls.isEnum());
    }
}
