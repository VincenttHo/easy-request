package com.easyrequest.ui;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.InplaceButton;
import com.intellij.ui.tabs.impl.JBTabsImpl;
import com.intellij.util.ui.JBUI;
import com.easyrequest.compat.IdeCompat;
import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Insets;
import java.lang.reflect.Field;

/** Native tabs, with a separate new-request button on platforms predating entry-point actions. */
final class CompatibleTabs extends com.intellij.ui.tabs.impl.JBEditorTabs {
    private static final Field ENTRY_TOOLBAR = entryToolbarField();
    private Runnable newRequest;
    private InplaceButton legacyNew;

    CompatibleTabs(Project project, Disposable parent, Runnable newRequest) {
        super(project, com.intellij.openapi.actionSystem.ActionManager.getInstance(),
                com.intellij.openapi.wm.IdeFocusManager.getInstance(project), parent);
        this.newRequest = newRequest;
        if (ENTRY_TOOLBAR == null) {
            legacyNew = UiActions.icon("新建接口", AllIcons.General.Add, newRequest);
            legacyNew.setName("new-request");
            setInnerInsets(JBUI.insetsRight(28));
            add(legacyNew);
        }
    }

    // This override is intentionally also compilable against 2019, where the hook does not exist.
    protected DefaultActionGroup getEntryPointActionGroup() {
        DumbAwareAction action = DumbAwareAction.create("新建接口", event -> {
            if (newRequest != null) newRequest.run();
        });
        action.getTemplatePresentation().setIcon(AllIcons.General.Add);
        return new DefaultActionGroup(action);
    }

    @Override public void doLayout() {
        super.doLayout();
        if (legacyNew != null) {
            legacyNew.setBounds(Math.max(0, getWidth() - JBUI.scale(28)), 0, JBUI.scale(26), JBUI.scale(26));
        } else if (getTabCount() == 0 && ENTRY_TOOLBAR != null) {
            try {
                ActionToolbar entry = (ActionToolbar) ENTRY_TOOLBAR.get(this);
                if (entry == null) return;
                JComponent toolbar = entry.getComponent();
                Dimension size = toolbar.getPreferredSize();
                Insets insets = getLayoutInsets();
                toolbar.setBounds(insets.left, insets.top, Math.max(size.width, JBUI.scale(26)), Math.max(size.height, JBUI.scale(26)));
            } catch (IllegalAccessException error) { throw IdeCompat.failure(error); }
        }
    }
    private static Field entryToolbarField() {
        try { return JBTabsImpl.class.getField("entryPointToolbar"); }
        catch (NoSuchFieldException oldPlatform) { return null; }
    }
}
