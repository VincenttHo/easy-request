package com.easyrequest.controller;

import com.intellij.openapi.actionSystem.*;
import com.intellij.psi.PsiMethod;
import org.jetbrains.annotations.NotNull;

public final class OpenEndpointAction extends AnAction {
    @Override public void update(@NotNull AnActionEvent event) {
        event.getPresentation().setEnabledAndVisible(SpringEndpointResolver.hasMapping(EndpointActions.methodAt(event)));
    }
    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        PsiMethod method = EndpointActions.methodAt(event); if (method != null) EndpointActions.invoke(method, false);
    }
}
