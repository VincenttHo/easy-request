package com.easyrequest.controller;

import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.psi.*;
import java.util.*;

/** Resolve current PSI instead of storing offsets that become stale after source edits. */
public final class ControllerNavigation {
    private ControllerNavigation() {}

    public static OpenFileDescriptor target(Project project, String fileUrl, String className, String signature) {
        com.intellij.openapi.vfs.VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(fileUrl);
        if (file == null || !file.isValid()) return null;
        if (PsiManager.getInstance(project).findFile(file) instanceof PsiJavaFile) {
            Deque<PsiClass> classes = new ArrayDeque<>(Arrays.asList(((PsiJavaFile) PsiManager.getInstance(project).findFile(file)).getClasses()));
            while (!classes.isEmpty()) {
                PsiClass type = classes.removeFirst();
                Collections.addAll(classes, type.getInnerClasses());
                String name = Objects.toString(type.getQualifiedName(), type.getName());
                if (!Objects.equals(name, className) && !signature.startsWith(name + "#")) continue;
                for (PsiMethod method : type.getAllMethods()) {
                    if (!SpringEndpointResolver.signature(method, type).equals(signature)) continue;
                    PsiElement target = method.getNavigationElement();
                    com.intellij.psi.PsiFile targetFile = target.getContainingFile();
                    if (targetFile != null && targetFile.getVirtualFile() != null)
                        return new OpenFileDescriptor(project, targetFile.getVirtualFile(), target.getTextOffset());
                }
                return new OpenFileDescriptor(project, file, type.getTextOffset());
            }
        }
        return new OpenFileDescriptor(project, file, 0);
    }
}
