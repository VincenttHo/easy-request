package com.easyrequest.ui;

import com.easyrequest.model.*;
import com.easyrequest.persistence.RequestStore;
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.util.Disposer;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.ui.InplaceButton;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.tabs.impl.JBTabsImpl;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.xmlb.XmlSerializer;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.function.BooleanSupplier;

public class ControllerBrowserTest extends LightJavaCodeInsightFixtureTestCase {
    private RequestPanel panel;
    private PsiFile source;
    @Override protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("package org.springframework.web.bind.annotation; public @interface GetMapping { String[] value() default {}; }");
        myFixture.addClass("package org.springframework.web.bind.annotation; public @interface RequestParam { String defaultValue() default \"\"; }");
        myFixture.addClass("package io.swagger.annotations; public @interface ApiOperation { String value() default \"\"; }");
        source = myFixture.addFileToProject("Users.java", """
                import org.springframework.web.bind.annotation.*;
                class Users {
                    @io.swagger.annotations.ApiOperation("用户列表")
                    @GetMapping("/before") public void list(@RequestParam(defaultValue="20") int size) {}
                }
                """);
        ProjectState state = new ProjectState();
        RequestStore.get(getProject()).loadState(state);
        createPanel();
        await(() -> status().equals("1 个 Controller · 1 个接口"));
    }
    private void createPanel() {
        panel = new RequestPanel(getProject());
        Disposer.register(getTestRootDisposable(), panel);
    }
    private ProjectState state() { return RequestStore.get(getProject()).getState(); }
    private Tree tree() { return find(panel, Tree.class, "controller-tree"); }
    private JBTabsImpl tabs() { return find(panel, JBTabsImpl.class, "request-tabs"); }
    private String status() { return find(panel, JBLabel.class, "controller-status").getText(); }
    private JTabbedPane sidebar() { return find(panel, JTabbedPane.class, "sidebar-views"); }
    private void refresh() { find(panel, InplaceButton.class, "refresh-controllers").doClick(); }
    private List<DefaultMutableTreeNode> entries() {
        List<DefaultMutableTreeNode> result = new ArrayList<>();
        Enumeration<TreeNode> nodes = ((DefaultMutableTreeNode) tree().getModel().getRoot()).preorderEnumeration();
        while (nodes.hasMoreElements()) {
            var node = (DefaultMutableTreeNode) nodes.nextElement();
            if (node.getUserObject() instanceof RequestData) result.add(node);
        }
        return result;
    }
    private void open(DefaultMutableTreeNode node) {
        tree().setSelectionPath(new TreePath(node.getPath()));
        tree().getActionMap().get("open-endpoint").actionPerformed(new ActionEvent(tree(), ActionEvent.ACTION_PERFORMED, "open"));
    }


    public void testInitialScanPersistsEveryEndpointAndRefreshPreservesSavedAndUnsavedValues() {
        assertEquals(1, entries().size()); assertEquals(1, state().requests.size());
        RequestData initialized = state().requests.getFirst();
        assertFalse(initialized.id.isBlank()); assertFalse(initialized.favorite);
        assertEquals("20", initialized.query.getFirst().value);
        String oldId = initialized.id;
        open(entries().getFirst());
        RequestEditor editor = panel.selectedEditor();
        assertFalse(editor.isDirty());
        find(editor, JTextComponent.class, "request-name").setText("已保存标题");
        assertTrue(editor.save()); editor.toggleFavorite();
        find(editor, JTextComponent.class, "request-name").setText("尚未保存的标题");
        var documents = PsiDocumentManager.getInstance(getProject());
        var document = documents.getDocument(source); assertNotNull(document);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            document.setText(document.getText().replace("/before", "/after")); documents.commitDocument(document);
        });
        myFixture.addFileToProject("Added.java", "class Added { @org.springframework.web.bind.annotation.GetMapping(\"/added\") void added() {} }");
        sidebar().setSelectedIndex(1); sidebar().setSelectedIndex(0);
        NonBlockingReadActionImpl.waitForAsyncTaskCompletion(); UIUtil.dispatchAllInvocationEvents();
        assertEquals(1, entries().size()); assertEquals("/before", ((RequestData) entries().getFirst().getUserObject()).url);
        var search = find(panel, JTextComponent.class, "controller-search");
        search.setText("/added"); assertTrue(entries().isEmpty()); search.setText("");
        refresh(); await(() -> entries().size() == 3);
        assertEquals(List.of("/added", "/after", "/before"), entries().stream().map(n -> ((RequestData) n.getUserObject()).url).sorted().toList());
        assertSame(editor, panel.selectedEditor()); assertEquals("尚未保存的标题", editor.readFields().name);
        assertTrue(editor.isDirty()); assertTrue(editor.readFields().sourceMissing);
        RequestData retained = RequestStore.get(getProject()).find(oldId);
        assertTrue(retained.sourceMissing); assertTrue(retained.favorite);
        assertEquals("已保存标题", retained.name); assertEquals("20", retained.query.getFirst().value);
        assertTrue(status().contains("1 个源码中已不存在"));
        refresh(); await(() -> !status().contains("扫描") && entries().size() == 3);
        assertEquals(3, state().requests.size());
        search.setText("尚未保存的标题"); assertTrue(entries().isEmpty());
        search.setText("已保存标题"); assertEquals(1, entries().size());
        search.setText("ADDED"); assertEquals(1, entries().size());
        open(entries().getFirst()); assertEquals(2, tabs().getTabCount());
        RequestEditor added = panel.selectedEditor(); assertEquals("/added", added.readFields().url);
        open(entries().getFirst()); assertSame(added, panel.selectedEditor()); assertEquals(2, tabs().getTabCount());
        sidebar().setSelectedIndex(1);
        Disposer.dispose(panel);
        ProjectState persisted = XmlSerializer.deserialize(XmlSerializer.serialize(state()), ProjectState.class);
        RequestStore.get(getProject()).loadState(persisted); createPanel();
        assertEquals(1, sidebar().getSelectedIndex()); assertEquals(2, tabs().getTabCount());
        assertEquals(3, state().requests.size());
        assertEquals("已保存标题", RequestStore.get(getProject()).find(oldId).name);
    }

    public void testRemovedSourceRemainsOpenableAndRestoringSourceReusesSameRecord() {
        String sourceText = source.getText();
        String id = state().requests.getFirst().id;
        open(entries().getFirst()); panel.selectedEditor().toggleFavorite();
        WriteCommandAction.runWriteCommandAction(getProject(), source::delete);
        NonBlockingReadActionImpl.waitForAsyncTaskCompletion(); UIUtil.dispatchAllInvocationEvents();
        assertEquals(1, entries().size());
        refresh(); await(() -> state().requests.getFirst().sourceMissing);
        assertEquals(1, entries().size()); assertTrue(state().requests.getFirst().favorite);
        assertTrue(status().contains("源码中已不存在"));
        open(entries().getFirst()); assertEquals(id, panel.selectedEditor().readFields().id);
        assertTrue(panel.selectedEditor().readFields().sourceMissing);
        source = myFixture.addFileToProject("Users.java", sourceText);
        refresh(); await(() -> !state().requests.getFirst().sourceMissing);
        assertEquals(1, state().requests.size()); assertEquals(id, state().requests.getFirst().id);
        assertTrue(state().requests.getFirst().favorite);
    }

    public void testRefreshingExistingRouteDoesNotResetTitlePrefixOrParameters() {
        open(entries().getFirst()); RequestEditor editor = panel.selectedEditor();
        find(editor, JTextComponent.class, "request-name").setText("我的用户列表");
        var request = editor.readFields();
        request.query.getFirst().value = "99";
        request.body = "saved body"; request.prefixId = "custom";
        RequestStore.get(getProject()).saveRequest(request);
        String id = request.id;
        var manager = PsiDocumentManager.getInstance(getProject()); var document = manager.getDocument(source);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            document.setText(document.getText().replace("用户列表", "新的 Swagger 标题").replace("20", "50"));
            manager.commitDocument(document);
        });
        refresh(); await(() -> !status().contains("扫描"));
        RequestData saved = RequestStore.get(getProject()).find(id);
        assertEquals(1, state().requests.size()); assertEquals("我的用户列表", saved.name);
        assertEquals("99", saved.query.getFirst().value); assertEquals("saved body", saved.body);
        assertEquals("custom", saved.prefixId);
    }

    public void testMouseSingleClickReplacesPreviewAndRapidDoubleClickPinsResolvedEndpoint() {
        myFixture.addFileToProject("Added.java", "class Added { @org.springframework.web.bind.annotation.GetMapping(\"/added\") void added() {} }");
        refresh(); await(() -> entries().size() == 2);
        var user = entries().stream().filter(n -> ((RequestData) n.getUserObject()).url.equals("/before")).findFirst().orElseThrow();
        var added = entries().stream().filter(n -> ((RequestData) n.getUserObject()).url.equals("/added")).findFirst().orElseThrow();
        click(user, 1); await(() -> panel.selectedEditor() != null);
        assertTrue(state().requestTabs.getFirst().preview); assertEquals(1, tabs().getTabCount());
        click(added, 1);
        await(() -> panel.selectedEditor().readFields().url.equals("/added"));
        assertEquals(1, tabs().getTabCount()); assertTrue(state().requestTabs.getFirst().preview);
        // Both events arrive before background resolution finishes, as in a real double-click.
        click(user, 1); click(user, 2);
        await(() -> panel.selectedEditor().readFields().url.equals("/before") && !state().requestTabs.getFirst().preview);
        assertEquals(1, tabs().getTabCount());
        click(added, 1);
        await(() -> tabs().getTabCount() == 2);
        assertFalse(state().requestTabs.getFirst().preview); assertTrue(state().requestTabs.getLast().preview);
    }
    public void testExpandCollapseAndNavigateToSelectedControllerOrMethod() {
        var endpoint = entries().getFirst();
        var controller = (DefaultMutableTreeNode) endpoint.getParent();
        var group = (DefaultMutableTreeNode) controller.getParent();
        var navigate = find(panel, InplaceButton.class, "navigate-controller-source");
        tree().setSelectionPath(new TreePath(group.getPath())); assertFalse(navigate.isEnabled());
        find(panel, InplaceButton.class, "expand-all-controllers").doClick();
        assertTrue(tree().isExpanded(new TreePath(controller.getPath())));
        find(panel, InplaceButton.class, "collapse-all-controllers").doClick();
        assertFalse(tree().isExpanded(new TreePath(group.getPath())));
        refresh(); await(() -> !status().contains("扫描"));
        endpoint = entries().getFirst(); controller = (DefaultMutableTreeNode) endpoint.getParent();
        group = (DefaultMutableTreeNode) controller.getParent();
        assertFalse(tree().isExpanded(new TreePath(group.getPath())));
        find(panel, InplaceButton.class, "expand-all-controllers").doClick();
        tree().setSelectionPath(new TreePath(controller.getPath())); assertTrue(navigate.isEnabled());
        navigate.doClick();
        var files = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject());
        var type = ((com.intellij.psi.PsiJavaFile) source).getClasses()[0];
        await(() -> files.getSelectedTextEditor() != null && files.getSelectedTextEditor().getCaretModel().getOffset() == type.getTextOffset());
        tree().setSelectionPath(new TreePath(endpoint.getPath())); navigate.doClick();
        await(() -> files.getSelectedTextEditor().getCaretModel().getOffset() == type.getMethods()[0].getTextOffset());
        assertEquals(0, tabs().getTabCount());
    }

    public void testProjectsBecomeVisibleIndividuallyAndRefreshCanRestartBetweenBatches() {
        Disposer.dispose(panel);
        WriteCommandAction.runWriteCommandAction(getProject(), source::delete);
        RequestStore.get(getProject()).loadState(new ProjectState());
        for (String name : List.of("a-project", "b-project")) {
            myFixture.addFileToProject(name + "/pom.xml", "<project/>");
            myFixture.addFileToProject(name + "/src/main/java/Api.java", "class Api { @org.springframework.web.bind.annotation.GetMapping(\"/" + name + "\") void get() {} }");
        }
        List<List<String>> observed = new ArrayList<>();
        ControllerBrowser[] browser = new ControllerBrowser[1];
        boolean[] restarted = {false};
        browser[0] = new ControllerBrowser(getProject(), (request, pinned) -> fail("Scanning must not open a request"), () -> {
            browser[0].refreshSavedRequests();
            Tree current = find(browser[0], Tree.class, "controller-tree");
            List<String> visible = new ArrayList<>();
            var nodes = ((DefaultMutableTreeNode) current.getModel().getRoot()).preorderEnumeration();
            while (nodes.hasMoreElements()) {
                Object value = ((DefaultMutableTreeNode) nodes.nextElement()).getUserObject();
                if (value instanceof RequestData request) visible.add(request.url);
            }
            observed.add(visible);
            if (visible.size() == 1 && !restarted[0]) {
                restarted[0] = true;
                find(browser[0], InplaceButton.class, "refresh-controllers").doClick();
            }
        });
        Disposer.register(getTestRootDisposable(), browser[0]);
        PlatformTestUtil.waitWithEventsDispatching("Project batches did not complete", () ->
                find(browser[0], JBLabel.class, "controller-status").getText().equals("2 个 Controller · 2 个接口"), 10);
        assertTrue(restarted[0]);
        assertTrue(observed.contains(List.of("/a-project")));
        assertEquals(2, observed.getLast().size());
        assertEquals(2, state().requests.size());
        assertTrue(state().requests.stream().noneMatch(r -> r.sourceMissing));
        // An old completion must not publish any additional updates after the restarted scan ends.
        int completions = observed.size();
        NonBlockingReadActionImpl.waitForAsyncTaskCompletion(); UIUtil.dispatchAllInvocationEvents();
        assertEquals(completions, observed.size());
    }

    private void click(DefaultMutableTreeNode node, int count) {
        var path = new TreePath(node.getPath());
        tree().setSize(500, 500); tree().expandPath(path.getParentPath()); tree().setSelectionPath(path);
        Rectangle bounds = tree().getPathBounds(path); assertNotNull(bounds);
        tree().dispatchEvent(new java.awt.event.MouseEvent(tree(), java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, bounds.x + 3, bounds.y + 3, count, false, java.awt.event.MouseEvent.BUTTON1));
    }

    private void await(BooleanSupplier condition) {
        PlatformTestUtil.waitWithEventsDispatching(() -> "Controller browser: " + status(), condition, 10);
    }
    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        T result = findOrNull(root, type, name);
        if (result == null) throw new AssertionError("Missing component: " + name);
        return result;
    }
    private static <T extends Component> T findOrNull(Component component, Class<T> type, String name) {
        if (type.isInstance(component) && name.equals(component.getName())) return type.cast(component);
        if (component instanceof Container parent) for (var child : parent.getComponents()) {
            T found = findOrNull(child, type, name); if (found != null) return found;
        }
        return null;
    }
}
