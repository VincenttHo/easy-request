package com.easyrequest.controller;

import com.easyrequest.compat.JavaCompat;

import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.javadoc.PsiDocTag;
import java.util.*;
import java.util.stream.Collectors;

/** Only resolved, human-readable documentation is used; expressions are never shown as descriptions. */
final class ParameterDocumentation {
    private ParameterDocumentation() {}

    static String field(PsiField field) {
        String value = swagger(field);
        if (!JavaCompat.isBlank(value)) return value;
        PsiDocComment doc = field.getDocComment();
        if (doc != null) {
            value = clean(Arrays.stream(doc.getDescriptionElements()).map(PsiElement::getText).collect(Collectors.joining()));
            if (!JavaCompat.isBlank(value)) return value;
        }
        // A comment immediately after the semicolon belongs to this field, not the next one.
        PsiElement next = field.getNextSibling();
        if (next instanceof PsiWhiteSpace && !next.getText().contains("\n")) next = next.getNextSibling();
        if (next instanceof PsiComment) return clean(((PsiComment) next).getText());
        value = clean(Arrays.stream(field.getChildren()).filter(child -> child instanceof PsiComment && !(child instanceof PsiDocComment))
                .map(PsiElement::getText).collect(Collectors.joining("\n")));
        if (!JavaCompat.isBlank(value)) return value;
        List<String> lines = new ArrayList<>();
        PsiElement previous = field.getPrevSibling();
        while (previous != null) {
            if (previous instanceof PsiWhiteSpace) {
                if (previous.getText().chars().filter(c -> c == '\n').count() > 1) break;
                previous = previous.getPrevSibling();
            }
            if (!(previous instanceof PsiComment)) break;
            PsiElement before = previous.getPrevSibling();
            if (before instanceof PsiWhiteSpace && !before.getText().contains("\n")) before = before.getPrevSibling();
            if (before instanceof PsiField) break; // The preceding field's trailing comment.
            lines.add(0, previous.getText());
            previous = previous.getPrevSibling();
        }
        return clean(String.join("\n", lines));
    }

    static String parameter(PsiMethod method, int index) {
        List<PsiMethod> candidates = new ArrayList<>();
        Deque<PsiMethod> pending = new ArrayDeque<>(); pending.add(method);
        Set<PsiMethod> seen = new HashSet<>();
        while (!pending.isEmpty()) {
            PsiMethod candidate = pending.removeFirst();
            if (!seen.add(candidate)) continue;
            candidates.add(candidate);
            if (index < candidate.getParameterList().getParametersCount()) {
                String value = swagger(candidate.getParameterList().getParameters()[index]);
                if (!JavaCompat.isBlank(value)) return value;
            }
            Collections.addAll(pending, candidate.findSuperMethods());
        }
        for (PsiMethod candidate : candidates) {
            PsiDocComment doc = candidate.getDocComment();
            if (doc == null || index >= candidate.getParameterList().getParametersCount()) continue;
            String name = candidate.getParameterList().getParameters()[index].getName();
            for (PsiDocTag tag : doc.findTagsByName("param")) {
                if (tag.getValueElement() == null || !name.equals(tag.getValueElement().getText())) continue;
                String text = tag.getText().replaceFirst("^@param\\s+" + java.util.regex.Pattern.quote(name) + "\\s*", "");
                String value = clean(text);
                if (!JavaCompat.isBlank(value)) return value;
            }
        }
        return "";
    }

    private static String swagger(PsiModifierListOwner owner) {
        for (String[] attribute : JavaCompat.list(new String[]{"io.swagger.v3.oas.annotations.Parameter", "description"}, new String[]{"io.swagger.v3.oas.annotations.media.Schema", "description"}, new String[]{"io.swagger.annotations.ApiParam", "value"}, new String[]{"io.swagger.annotations.ApiModelProperty", "value"}, new String[]{"io.swagger.annotations.ApiModelProperty", "notes"})) {
            String value = clean(SpringEndpointResolver.annotationTitle(owner, attribute[0], attribute[1]));
            if (!JavaCompat.isBlank(value)) return value;
        }
        return "";
    }

    private static String clean(String text) {
        return JavaCompat.strip(text.replaceAll("(?m)^\\s*(/\\*+|//|\\*)\\s?", "")
                .replace("*/", "").replaceAll("\\{@(?:link|linkplain|code|literal)\\s+([^}]+)}", "$1")
                .replaceAll("<[^>]+>", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&").replace("&nbsp;", " ").replaceAll("\\s+", " "));
    }
}
