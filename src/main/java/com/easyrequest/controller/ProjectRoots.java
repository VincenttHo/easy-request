package com.easyrequest.controller;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VfsUtilCore;
import java.util.ArrayList;
import java.util.List;

/** Logical application roots stay separate even when one IDEA module contains several projects. */
final class ProjectRoots {
    private ProjectRoots() {}
    static VirtualFile logicalRoot(VirtualFile file, Module module) {
        VirtualFile inferred = null;
        for (VirtualFile dir = file == null ? null : file.isDirectory() ? file : file.getParent(); dir != null; dir = dir.getParent()) {
            if (dir.findChild("pom.xml") != null || dir.findChild("build.gradle") != null || dir.findChild("build.gradle.kts") != null) return dir;
            if (dir.getName().equals("src") && inferred == null) inferred = dir.getParent();
            if (dir.findChild("settings.gradle") != null || dir.findChild("settings.gradle.kts") != null) return inferred == null ? dir : inferred;
        }
        if (inferred != null) return inferred;
        if (module != null) {
            VirtualFile best = null;
            for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) {
                if (file == null || VfsUtilCore.isAncestor(root, file, false)) {
                    if (best == null || root.getPath().length() > best.getPath().length()) best = root;
                }
            }
            return best;
        }
        return null;
    }

    static VirtualFile groupRoot(VirtualFile root) {
        if (root == null) return null;
        VirtualFile grouped = root;
        for (VirtualFile dir = root; dir != null; dir = dir.getParent()) {
            if (dir.findChild("settings.gradle") != null || dir.findChild("settings.gradle.kts") != null) return dir;
            for (VirtualFile child : mavenModules(dir)) if (VfsUtilCore.isAncestor(child, grouped, false)) { grouped = dir; break; }
        }
        return grouped;
    }

    static List<VirtualFile> mavenModules(VirtualFile root) {
        List<VirtualFile> result = new ArrayList<>();
        VirtualFile pom = root.findChild("pom.xml");
        if (pom == null || pom.getLength() > 1_000_000) return result;
        try {
            org.jdom.Element xml = JDOMUtil.load(new java.io.ByteArrayInputStream(pom.contentsToByteArray()));
            org.jdom.Element modules = xml.getChild("modules", xml.getNamespace());
            if (modules != null) for (org.jdom.Element entry : modules.getChildren("module", xml.getNamespace())) {
                VirtualFile directory = root.findFileByRelativePath(entry.getTextTrim());
                if (directory != null && directory.isDirectory()) result.add(directory);
            }
        } catch (Exception ignored) { /* An incomplete POM cannot establish project ownership. */ }
        return result;
    }
}
