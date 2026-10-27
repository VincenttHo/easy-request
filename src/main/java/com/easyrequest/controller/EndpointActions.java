package com.easyrequest.controller;

import com.easyrequest.compat.JavaCompat;

import com.easyrequest.ui.RequestPanel;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import java.awt.datatransfer.StringSelection;
import java.util.List;

final class EndpointActions {
    private EndpointActions() {}
    static PsiMethod methodAt(AnActionEvent event) {
        Project project = event.getProject();
        if (project == null || DumbService.isDumb(project)) return null;
        PsiElement element = event.getData(CommonDataKeys.PSI_ELEMENT);
        if (element instanceof PsiMethod) return ((PsiMethod) element);
        com.intellij.openapi.editor.Editor editor = event.getData(CommonDataKeys.EDITOR);
        com.intellij.psi.PsiFile file = event.getData(CommonDataKeys.PSI_FILE);
        if (editor == null || file == null) return null;
        element = file.findElementAt(editor.getCaretModel().getOffset());
        return PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
    }
    static void invoke(PsiMethod method, boolean copy) {
        Project project = method.getProject();
        com.intellij.psi.SmartPsiElementPointer<com.intellij.psi.PsiMethod> pointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(method);
        ReadAction.<List<SpringEndpointResolver.Endpoint>>nonBlocking(() -> {
            PsiMethod current = pointer.getElement();
            return current == null ? JavaCompat.list() : SpringEndpointResolver.resolve(current);
        }).inSmartMode(project).expireWith(project).finishOnUiThread(ModalityState.defaultModalityState(), endpoints -> {
            if (endpoints.isEmpty()) { Messages.showInfoMessage(project, "未找到可用的 Spring MVC 路由", "Easy Request"); return; }
            if (endpoints.size() == 1) apply(project, endpoints.get(0), copy);
            else JBPopupFactory.getInstance().createPopupChooserBuilder(endpoints)
                    .setTitle(copy ? "选择要复制的相对地址" : "选择接口")
                    .setItemChosenCallback(endpoint -> apply(project, endpoint, copy)).createPopup().showCenteredInCurrentWindow(project);
        }).submit(AppExecutorUtil.getAppExecutorService());
    }
    private static void apply(Project project, SpringEndpointResolver.Endpoint endpoint, boolean copy) {
        if (copy) { CopyPasteManager.getInstance().setContents(new StringSelection(endpoint.request().url)); return; }
        com.intellij.openapi.wm.ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow("Easy Request");
        if (window == null) return;
        window.activate(() -> {
            for (com.intellij.ui.content.Content content : window.getContentManager().getContents()) if (content.getComponent() instanceof RequestPanel) {
                ((RequestPanel) content.getComponent()).openEndpoint(endpoint.request(), endpoint.note()); break;
            }
        });
    }
}
