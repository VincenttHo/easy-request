package com.easyrequest.controller;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProvider;
import com.intellij.openapi.editor.markup.GutterIconRenderer;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import javax.swing.Icon;

public final class ControllerLineMarkerProvider implements LineMarkerProvider {
    private static final Icon ICON = IconLoader.getIcon("/icons/request.svg", ControllerLineMarkerProvider.class);
    @Override public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
        if (!(element instanceof PsiIdentifier) || !(element.getParent() instanceof PsiMethod)
                || element != ((PsiMethod) element.getParent()).getNameIdentifier() || DumbService.isDumb(element.getProject())
                || !SpringEndpointResolver.hasMapping(((PsiMethod) element.getParent()))) return null;
        return new LineMarkerInfo<>(element, element.getTextRange(), ICON, e -> "在 Easy Request 中打开接口",
                (event, leaf) -> { if (leaf.getParent() instanceof PsiMethod) EndpointActions.invoke(((PsiMethod) leaf.getParent()), false); },
                GutterIconRenderer.Alignment.LEFT);
    }
}
