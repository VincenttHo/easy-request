package com.easyrequest.ui;

import com.easyrequest.model.*;
import com.easyrequest.persistence.RequestStore;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.TestDialogManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.InplaceButton;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.ui.tabs.impl.JBTabsImpl;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.xmlb.XmlSerializer;
import com.sun.net.httpserver.HttpServer;
import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public class RequestTabsTest extends LightJavaCodeInsightFixtureTestCase {
    private RequestPanel panel;

    @Override protected void setUp() throws Exception {
        super.setUp();
        RequestStore.get(getProject()).loadState(new ProjectState());
        createPanel();
    }
    private void createPanel() {
        panel = new RequestPanel(getProject());
        Disposer.register(getTestRootDisposable(), panel);
    }
    private ProjectState state() { return RequestStore.get(getProject()).getState(); }
    private JBTabsImpl tabs() { return find(panel, JBTabsImpl.class, c -> "request-tabs".equals(c.getName())); }
    private static RequestData endpoint(String module, String path) {
        RequestData request = new RequestData();
        request.name = "用户详情"; request.url = path;
        request.sourceModule = module; request.sourceFileUrl = "file:///workspace/" + module + "/UserController.java";
        request.query.add(new KeyValue("page", "1"));
        return request;
    }
    private static JTextComponent field(RequestEditor editor, String name) {
        return find(editor, JTextComponent.class, c -> name.equals(c.getName()));
    }

    private static void editField(RequestEditor editor, String name, String value) {
        if (name.equals("request-body")) find(editor, RequestBodyEditor.class, c -> true).setText(value);
        else field(editor, name).setText(value);
    }

    public void testControllerDocumentationRefreshPreservesSavedValuesAndPreviewState() {
        RequestData generated = endpoint("app-a", "/users");
        generated.query.getFirst().description = "页码";
        generated.bodyType = "JSON"; generated.body = "{\"name\":\"example\"}";
        generated.bodyDescriptions.put("/name", "用户名称");
        RequestData favorite = generated.copy(); favorite.id = "documented-favorite";
        favorite.query.getFirst().value = "8"; favorite.query.getFirst().description = "";
        favorite.body = "{\"name\":\"saved\"}"; favorite.bodyDescriptions.clear();
        RequestStore.get(getProject()).saveRequest(favorite);
        RequestEditor editor = panel.openSaved(state().requests.getFirst());
        panel.openEndpoint(generated, "");
        assertSame(editor, panel.selectedEditor());
        assertFalse(editor.isDirty()); assertTrue(state().requestTabs.getFirst().preview);
        assertEquals("8", editor.readFields().query.getFirst().value);
        assertEquals("页码", editor.readFields().query.getFirst().description);
        assertEquals(favorite.body, editor.readFields().body);
        assertEquals("用户名称", editor.readFields().bodyDescriptions.get("/name"));
        JTable table = find(editor, JTable.class, t -> t.getRowCount() > 0 && "page".equals(t.getValueAt(0, 1)));
        int descriptionColumn = table.getColumnCount() - 1;
        assertEquals("// 页码", table.getValueAt(0, descriptionColumn));
        assertFalse(table.editCellAt(0, descriptionColumn));
        table.getModel().setValueAt("changed comment", 0, descriptionColumn);
        assertEquals("// 页码", table.getValueAt(0, descriptionColumn));
        assertFalse(editor.isDirty()); assertTrue(state().requestTabs.getFirst().preview);
        generated.query.getFirst().description = "最新页码说明";
        generated.bodyDescriptions.put("/name", "最新名称说明");
        panel.openEndpoint(generated, "");
        assertEquals("// 最新页码说明", table.getValueAt(0, descriptionColumn));
        assertFalse(editor.isDirty()); assertTrue(state().requestTabs.getFirst().preview);
        table.setValueAt("9", 0, 2);
        assertTrue(editor.isDirty()); assertFalse(state().requestTabs.getFirst().preview);
        generated.bodyDescriptions.put("/name", "再次更新说明");
        panel.openEndpoint(generated, "");
        assertEquals("9", editor.readFields().query.getFirst().value); assertTrue(editor.isDirty());
        assertTrue(editor.save()); assertEquals(1, state().requests.size());
        assertEquals("9", state().requests.getFirst().query.getFirst().value);
        assertEquals("再次更新说明", state().requests.getFirst().bodyDescriptions.get("/name"));
        assertTrue(panel.closeRequest(editor));
        editor = panel.openSaved(state().requests.getFirst());
        assertEquals("再次更新说明", editor.readFields().bodyDescriptions.get("/name"));
        assertFalse(editor.isDirty());
    }

    public void testControllerTabsRetainEditsAndKeepProjectOriginsSeparate() {
        RequestData first = endpoint("app-a", "/users");
        RequestData second = endpoint("app-b", "/users");
        panel.openEndpoint(first, "", true);
        RequestEditor a = panel.selectedEditor();
        field(a, "request-name").setText("/users/edited");
        panel.openEndpoint(second, "", true);
        RequestEditor b = panel.selectedEditor();
        assertNotSame(a, b);
        assertEquals(2, tabs().getTabCount());
        assertEquals("/users/edited", a.readFields().name);
        assertEquals("/users", b.readFields().url);
        assertEquals("app-b", b.readFields().sourceModule);
        panel.openEndpoint(first, "", true);
        assertSame(a, panel.selectedEditor());
        assertEquals(2, tabs().getTabCount());
        assertEquals("/users/edited", a.readFields().name);
        assertEquals("app-a", a.readFields().sourceModule);
        assertTrue(a.isDirty());
        a.readFields().query.getFirst().value = "changed copy";
        assertEquals("1", b.readFields().query.getFirst().value);
        assertEquals("1", first.query.getFirst().value);
    }

    public void testTabsRoundTripKeepActiveDraftBaselineAndSource() {
        RequestData saved = endpoint("app-a", "/saved"); saved.id = "saved-a";
        RequestStore.get(getProject()).saveRequest(saved);
        RequestEditor editor = panel.openSaved(saved, true);
        field(editor, "request-name").setText("/saved/unsaved");
        RequestData other = endpoint("app-b", "/other");
        panel.openEndpoint(other, "");
        panel.select(editor);
        Disposer.dispose(panel);
        ProjectState restored = XmlSerializer.deserialize(XmlSerializer.serialize(state()), ProjectState.class);
        assertEquals(2, restored.requestTabs.size());
        assertEquals(0, restored.activeRequestTab);
        assertEquals(saved.name, restored.requestTabs.get(0).baseline.name);
        assertEquals("/saved/unsaved", restored.requestTabs.get(0).request.name);
        assertEquals("app-b", restored.requestTabs.get(1).request.sourceModule);
        assertFalse(restored.requestTabs.getFirst().preview); assertTrue(restored.requestTabs.getLast().preview);
        RequestStore.get(getProject()).loadState(restored);
        createPanel();
        assertEquals(2, tabs().getTabCount());
        assertEquals("/saved/unsaved", panel.selectedEditor().readFields().name);
        assertTrue(panel.selectedEditor().isDirty());
        assertSame(panel.selectedEditor(), panel.openSaved(saved));
        panel.openEndpoint(other, "");
        assertEquals(2, tabs().getTabCount());
        assertEquals("app-b", panel.selectedEditor().readFields().sourceModule);
        assertFalse(panel.selectedEditor().isDirty());
        field((RequestEditor) tabs().getTabAt(0).getComponent(), "request-name").setText(saved.name);
        assertFalse(((RequestEditor) tabs().getTabAt(0).getComponent()).isDirty());
    }

    public void testControllerOpensLegacyFavoriteValuesAndSavingUpdatesSameRecord() {
        FolderData folder = new FolderData(); folder.name = "已调试接口"; state().folders.add(folder);
        UrlPrefix test = new UrlPrefix("测试", "https://test.example"); state().prefixes.add(test);
        RequestData generated = endpoint("app-a", "/users/{id}"); generated.method = "POST";
        RequestData favorite = generated.copy(); favorite.id = "favorite";
        favorite.name = "已维护的标题"; favorite.folderId = folder.id; favorite.prefixId = test.id;
        favorite.url = "/users/{id}";
        favorite.query = new ArrayList<>(List.of(new KeyValue("page", "20")));
        favorite.pathVariables.add(new KeyValue("id", "321"));
        favorite.headers.add(new KeyValue("Authorization", "Bearer saved-token"));
        favorite.bodyType = "JSON"; favorite.body = "{\"name\":\"saved\"}"; favorite.useGlobalHeaders = false;
        RequestStore.get(getProject()).saveRequest(favorite);
        panel.openEndpoint(generated, "");
        RequestEditor editor = panel.selectedEditor();
        RequestData loaded = editor.readFields();
        assertEquals("favorite", loaded.id); assertEquals("已维护的标题", loaded.name);
        assertEquals(folder.id, loaded.folderId); assertEquals(test.id, loaded.prefixId);
        assertEquals("/users/{id}", loaded.url);
        assertEquals("20", loaded.query.getFirst().value); assertEquals("321", loaded.pathVariables.getFirst().value);
        assertEquals("Bearer saved-token", loaded.headers.getFirst().value);
        assertEquals("JSON", loaded.bodyType); assertEquals(favorite.body, loaded.body); assertFalse(loaded.useGlobalHeaders);
        assertFalse(editor.isDirty());
        assertSame(editor, panel.openSaved(state().requests.getFirst()));
        editField(editor, "request-body", "{\"name\":\"updated\"}");
        assertTrue(editor.save());
        assertEquals(1, state().requests.size()); assertEquals("favorite", state().requests.getFirst().id);
        assertTrue(panel.closeRequest(editor));
        panel.openEndpoint(generated, "");
        assertEquals("{\"name\":\"updated\"}", panel.selectedEditor().readFields().body);
        assertEquals(1, tabs().getTabCount()); assertFalse(panel.selectedEditor().isDirty());
        Disposer.dispose(panel);
        ProjectState restored = XmlSerializer.deserialize(XmlSerializer.serialize(state()), ProjectState.class);
        RequestStore.get(getProject()).loadState(restored); createPanel();
        RequestEditor existing = panel.selectedEditor();
        panel.openEndpoint(generated, "");
        assertSame(existing, panel.selectedEditor()); assertEquals(1, tabs().getTabCount());
        assertEquals("/users/{id}", existing.readFields().sourcePath);
    }

    public void testFavoriteTabAndControllerShareUnsavedEditsAndCloseConfirmation() {
        RequestData generated = endpoint("app-a", "/users");
        RequestData favorite = generated.copy(); favorite.id = "favorite";
        RequestStore.get(getProject()).saveRequest(favorite);
        RequestEditor editor = panel.openSaved(favorite);
        field(editor, "request-name").setText("/custom-url?size=88");
        panel.openEndpoint(generated, "", true);
        assertSame(editor, panel.selectedEditor()); assertEquals(1, tabs().getTabCount());
        assertEquals("/custom-url?size=88", editor.readFields().name);
        var previous = TestDialogManager.setTestDialog(message -> Messages.CANCEL);
        try { assertFalse(panel.closeRequest(editor)); }
        finally { TestDialogManager.setTestDialog(previous); }
        assertTrue(editor.save());
        assertEquals(1, state().requests.size()); assertTrue(panel.closeRequest(editor));
        panel.openEndpoint(generated, "");
        assertEquals("favorite", panel.selectedEditor().readFields().id);
        assertEquals("/custom-url?size=88", panel.selectedEditor().readFields().name);
        RequestData post = generated.copy(); post.method = "POST";
        panel.openEndpoint(post, "");
        assertFalse(panel.selectedEditor().readFields().id.isBlank());
        RequestData otherProject = endpoint("app-b", "/users");
        panel.openEndpoint(otherProject, "");
        assertFalse(panel.selectedEditor().readFields().id.isBlank());
    }


    public void testFavoriteReferencesSavedEndpointWithoutSavingUnsavedEdits() {
        RequestData generated = endpoint("app-a", "/users");
        panel.openEndpoint(generated, "");
        RequestEditor editor = panel.selectedEditor();
        String id = editor.readFields().id;
        assertFalse(id.isBlank()); assertFalse(editor.isDirty()); assertEquals(1, state().requests.size());
        assertFalse(state().requests.getFirst().favorite);
        var star = find(editor, InplaceButton.class, c -> "request-favorite".equals(c.getName()));
        assertSame(com.intellij.icons.AllIcons.Nodes.NotFavoriteOnHover, star.getIcon());
        field(editor, "request-name").setText("我的接口标题");
        star.doClick();
        assertSame(com.intellij.icons.AllIcons.Nodes.Favorite, star.getIcon());
        assertEquals("取消收藏", star.getToolTipText());
        assertTrue(state().requests.getFirst().favorite); assertTrue(editor.isDirty());
        assertEquals(generated.name, state().requests.getFirst().name);
        var favorites = find(panel, com.intellij.ui.treeStructure.Tree.class, c -> "favorites-tree".equals(c.getName()));
        var root = (javax.swing.tree.DefaultMutableTreeNode) favorites.getModel().getRoot();
        assertEquals(1, root.getChildCount());
        clickTree(favorites, new javax.swing.tree.TreePath(((javax.swing.tree.DefaultMutableTreeNode) root.getChildAt(0)).getPath()), 1);
        assertSame(editor, panel.selectedEditor()); assertEquals(1, tabs().getTabCount());
        assertEquals("我的接口标题", editor.readFields().name);
        assertTrue(editor.save()); assertEquals(1, state().requests.size());
        assertEquals("我的接口标题", state().requests.getFirst().name);
        star.doClick();
        assertFalse(state().requests.getFirst().favorite); assertFalse(editor.isDirty());
        assertEquals(0, root.getChildCount()); assertEquals(1, state().requests.size());
        assertEquals(id, state().requests.getFirst().id);
        panel.openEndpoint(generated, ""); assertSame(editor, panel.selectedEditor());
        assertEquals("我的接口标题", editor.readFields().name);
    }


    public void testSavedAddressAndMethodAreFixedWhilePrefixTitleAndParametersRemainEditable() {
        panel.openEndpoint(endpoint("app-a", "/users/{id}"), "");
        RequestEditor editor = panel.selectedEditor();
        assertFalse(field(editor, "request-url").isEditable());
        var method = find(editor, JComboBox.class, c -> "request-method".equals(c.getName()));
        assertFalse(method.isEnabled());
        // The persistence boundary also rejects accidental programmatic route changes.
        field(editor, "request-url").setText("/other"); method.setSelectedItem("DELETE");
        var testPrefix = new UrlPrefix("测试", "https://test.example"); state().prefixes.add(testPrefix);
        editor.settingsChanged();
        var prefixes = find(editor, PrefixComboBox.class, c -> true);
        for (int i = 0; i < prefixes.getItemCount(); i++) if (prefixes.getItemAt(i).id.equals(testPrefix.id)) prefixes.setSelectedIndex(i);
        field(editor, "request-name").setText("保存的标题");
        var table = find(editor, JTable.class, t -> t.getRowCount() > 0 && "page".equals(t.getValueAt(0, 1)));
        table.setValueAt("12", 0, 2);
        assertTrue(editor.save());
        RequestData saved = state().requests.getFirst();
        assertEquals("/users/{id}", saved.url); assertEquals("GET", saved.method);
        assertEquals(testPrefix.id, saved.prefixId); assertEquals("保存的标题", saved.name);
        assertEquals("12", saved.query.getFirst().value);
    }


    public void testManualRequestSavesToInterfaceTreeAndStarOnlyChangesBookmark() {
        RequestEditor editor = panel.newRequest("");
        assertTrue(field(editor, "request-url").isEditable());
        field(editor, "request-url").setText("https://example.com/api");
        field(editor, "request-name").setText("手动请求");
        assertTrue(editor.save()); assertFalse(field(editor, "request-url").isEditable());
        assertEquals(1, state().requests.size());
        RequestData saved = state().requests.getFirst();
        assertFalse(saved.catalogManaged); assertFalse(saved.favorite);
        editor.toggleFavorite(); assertTrue(saved.favorite);
        assertSame(editor, panel.openSaved(saved)); assertEquals(1, tabs().getTabCount());
        editor.toggleFavorite(); assertFalse(saved.favorite);
        assertEquals(1, state().requests.size());
    }


    public void testLegacyVariantsMigrateToHistoryAndKeepFavoriteReferencesAfterRestart() {
        RequestData generated = endpoint("app-a", "/users");
        RequestData first = generated.copy(); first.id = "first"; first.body = "first payload";
        RequestData second = generated.copy(); second.id = "second"; second.body = "second payload";
        ProjectState legacy = new ProjectState(); legacy.requests.add(first); legacy.requests.add(second);
        Disposer.dispose(panel); RequestStore.get(getProject()).loadState(legacy); createPanel();
        assertTrue(first.legacyVariant); assertFalse(first.catalogManaged);
        assertFalse(second.legacyVariant); assertTrue(second.catalogManaged);
        assertTrue(first.favorite); assertTrue(second.favorite);
        panel.openEndpoint(generated, "");
        RequestEditor editor = panel.selectedEditor();
        assertEquals("second", editor.readFields().id); assertEquals("second payload", editor.readFields().body);
        editField(editor, "request-body", "updated second"); assertTrue(editor.save());
        assertEquals(2, state().requests.size()); assertEquals("first payload", first.body);
        editor.toggleFavorite(); assertFalse(RequestStore.get(getProject()).find("second").favorite);
        Disposer.dispose(panel);
        ProjectState restored = XmlSerializer.deserialize(XmlSerializer.serialize(state()), ProjectState.class);
        RequestStore.get(getProject()).loadState(restored); createPanel(); panel.openEndpoint(generated, "");
        assertEquals("second", panel.selectedEditor().readFields().id); assertEquals(1, tabs().getTabCount());
        assertEquals("updated second", panel.selectedEditor().readFields().body);
        assertFalse(RequestStore.get(getProject()).find("second").favorite);
        assertTrue(RequestStore.get(getProject()).find("first").favorite);
    }

    public void testSwaggerDisplayTitlesDoNotDetermineEndpointIdentity() {
        RequestData first = endpoint("app-a", "/users");
        first.sourceMethod = "UserController#byId(long)";
        RequestData second = first.copy();
        second.sourceMethod = "UserController#byName(java.lang.String)";
        panel.openEndpoint(first, "", true);
        RequestEditor a = panel.selectedEditor();
        panel.openEndpoint(second, "", true);
        assertSame(a, panel.selectedEditor());
        assertEquals(1, tabs().getTabCount());
        first.name = "修改后的 Swagger 标题";
        panel.openEndpoint(first, "", true);
        assertSame(a, panel.selectedEditor());
        assertEquals(1, tabs().getTabCount());
        assertEquals(first.sourceMethod, state().requestTabs.get(0).request.sourceMethod);
    }

    public void testDeletingFavoriteFolderAndUnstarringKeepInterfaceDataAndOpenDraft() {
        FolderData parent = new FolderData(); parent.name = "接口分类";
        FolderData child = new FolderData(); child.name = "子分类"; child.parentId = parent.id;
        state().folders.add(parent); state().folders.add(child);
        panel.openEndpoint(endpoint("app-a", "/users"), "");
        RequestEditor editor = panel.selectedEditor(); editor.toggleFavorite();
        String id = editor.readFields().id;
        RequestStore.get(getProject()).moveFavorite(id, child.id);
        Disposer.dispose(panel); createPanel(); editor = panel.selectedEditor();
        field(editor, "request-name").setText("待保存标题");
        var tree = find(panel, com.intellij.ui.treeStructure.Tree.class, c -> "favorites-tree".equals(c.getName()));
        var root = (javax.swing.tree.DefaultMutableTreeNode) tree.getModel().getRoot();
        var parentNode = (javax.swing.tree.DefaultMutableTreeNode) root.getChildAt(0);
        tree.setSelectionPath(new javax.swing.tree.TreePath(parentNode.getPath()));
        var previous = TestDialogManager.setTestDialog(message -> Messages.YES);
        try { find(panel.collectionMenu(), JMenuItem.class, c -> "删除文件夹…".equals(c.getText())).doClick(); }
        finally { TestDialogManager.setTestDialog(previous); }
        assertTrue(state().folders.isEmpty()); assertEquals(1, state().requests.size());
        RequestData saved = RequestStore.get(getProject()).find(id);
        assertTrue(saved.favorite); assertEquals("", saved.folderId);
        assertEquals("用户详情", saved.name); assertEquals("待保存标题", editor.readFields().name);
        assertTrue(editor.isDirty()); assertSame(editor, panel.selectedEditor());
        var requestNode = (javax.swing.tree.DefaultMutableTreeNode) root.getChildAt(0);
        tree.setSelectionPath(new javax.swing.tree.TreePath(requestNode.getPath()));
        find(panel.collectionMenu(), JMenuItem.class, c -> "取消收藏".equals(c.getText())).doClick();
        assertFalse(saved.favorite); assertEquals(1, state().requests.size()); assertEquals(0, root.getChildCount());
        assertTrue(editor.isDirty()); assertEquals("待保存标题", editor.readFields().name);
        assertSame(com.intellij.icons.AllIcons.Nodes.NotFavoriteOnHover,
                find(editor, InplaceButton.class, c -> "request-favorite".equals(c.getName())).getIcon());
    }

    public void testClosingDirtyFavoriteCanCancelDiscardOrSaveWithoutAffectingOthers() {
        RequestEditor other = panel.newRequest("");
        RequestData saved = endpoint("app-a", "/saved"); saved.id = "saved-a";
        RequestStore.get(getProject()).saveRequest(saved);
        RequestEditor editor = panel.openSaved(saved, true);
        field(editor, "request-name").setText("/updated");
        var previous = TestDialogManager.setTestDialog(message -> Messages.CANCEL);
        try {
            assertFalse(panel.closeRequest(editor));
            assertSame(editor, panel.selectedEditor());
            assertEquals(2, tabs().getTabCount());
            TestDialogManager.setTestDialog(message -> Messages.YES);
            assertTrue(panel.closeRequest(editor));
            assertEquals("/updated", state().requests.getFirst().name);
            assertEquals(1, tabs().getTabCount());
            assertSame(other, panel.selectedEditor());
            editor = panel.openSaved(state().requests.getFirst(), true);
            field(editor, "request-name").setText("/discard");
            TestDialogManager.setTestDialog(message -> Messages.NO);
            assertTrue(panel.closeRequest(editor));
            assertEquals("/updated", state().requests.getFirst().name);
            assertSame(other, panel.selectedEditor());
            TestDialogManager.setTestDialog(message -> { throw new AssertionError("Unchanged favorite must close silently"); });
            assertTrue(panel.closeRequest(panel.openSaved(state().requests.getFirst(), true)));
        } finally { TestDialogManager.setTestDialog(previous); }
    }

    public void testFreshAndFullyClosedPanelsStayEmptyAfterRestart() {
        assertEquals(0, tabs().getTabCount()); assertNull(panel.selectedEditor());
        assertTrue(state().requestTabs.isEmpty()); assertNull(state().draft);
        var previous = TestDialogManager.setTestDialog(message -> { throw new AssertionError("Unsaved requests must close silently"); });
        try {
            RequestEditor created = panel.newRequest("");
            field(created, "request-url").setText("/unsaved-new");
            assertTrue(panel.closeRequest(created));
            panel.openEndpoint(endpoint("app-a", "/generated"), "");
            assertTrue(panel.closeRequest(panel.selectedEditor()));
            assertEquals(0, tabs().getTabCount()); assertNull(panel.selectedEditor());
            assertNull(state().draft);
            Disposer.dispose(panel);
            ProjectState restored = XmlSerializer.deserialize(XmlSerializer.serialize(state()), ProjectState.class);
            assertNull(restored.draft); assertTrue(restored.requestTabs.isEmpty());
            RequestStore.get(getProject()).loadState(restored); createPanel();
            assertEquals(0, tabs().getTabCount()); assertNull(panel.selectedEditor());
            int mask = com.intellij.openapi.util.SystemInfo.isMac ? java.awt.event.InputEvent.META_DOWN_MASK : java.awt.event.InputEvent.CTRL_DOWN_MASK;
            var key = new com.intellij.openapi.actionSystem.KeyboardShortcut(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_T, mask), null);
            var action = com.intellij.openapi.actionSystem.ex.ActionUtil.getActions(panel).stream()
                    .filter(a -> java.util.Arrays.asList(a.getShortcutSet().getShortcuts()).contains(key)).findFirst().orElseThrow();
            perform(action);
            assertEquals(1, tabs().getTabCount());
        } finally { TestDialogManager.setTestDialog(previous); }
    }

    public void testPreviewReplacesOnlyPreviewAndDoubleOpenPinsWithoutDuplicating() {
        var previous = TestDialogManager.setTestDialog(message -> { throw new AssertionError("Controller preview must not prompt"); });
        try {
            RequestData a = endpoint("app-a", "/a"), b = endpoint("app-b", "/b"), c = endpoint("app-c", "/c");
            panel.openEndpoint(a, "");
            RequestEditor preview = panel.selectedEditor();
            assertTrue(state().requestTabs.getFirst().preview);
            panel.openEndpoint(b, "");
            assertEquals(1, tabs().getTabCount()); assertNotSame(preview, panel.selectedEditor());
            assertEquals("/b", panel.selectedEditor().readFields().url);
            RequestEditor fixed = panel.selectedEditor();
            panel.openEndpoint(b, "", true);
            assertSame(fixed, panel.selectedEditor()); assertEquals(1, tabs().getTabCount());
            assertFalse(state().requestTabs.getFirst().preview);
            panel.openEndpoint(a, "");
            panel.openEndpoint(c, "");
            assertEquals(2, tabs().getTabCount());
            assertSame(fixed, tabs().getTabAt(0).getComponent());
            assertEquals("/c", panel.selectedEditor().readFields().url);
            assertEquals(1L, state().requestTabs.stream().filter(t -> t.preview).count());
            panel.newRequest("");
            assertEquals(3, tabs().getTabCount()); assertFalse(state().requestTabs.getLast().preview);
        } finally { TestDialogManager.setTestDialog(previous); }
    }

    public void testEditedFavoritePinsAndOnlyExplicitClosePrompts() {
        RequestData saved = endpoint("app-a", "/saved"); saved.id = "favorite";
        RequestStore.get(getProject()).saveRequest(saved);
        RequestEditor favorite = panel.openSaved(saved);
        field(favorite, "request-name").setText("/changed");
        assertFalse(state().requestTabs.getFirst().preview);
        var previous = TestDialogManager.setTestDialog(message -> { throw new AssertionError("Switching away from edited favorite must not close it"); });
        try {
            panel.openEndpoint(endpoint("app-b", "/b"), "");
            assertEquals(2, tabs().getTabCount());
            assertSame(favorite, tabs().getTabAt(0).getComponent());
            assertEquals("/changed", favorite.readFields().name);
            TestDialogManager.setTestDialog(message -> Messages.CANCEL);
            assertFalse(panel.closeRequest(favorite));
            TestDialogManager.setTestDialog(message -> Messages.YES);
            assertTrue(panel.closeRequest(favorite));
            assertEquals("/changed", state().requests.getFirst().name);
            favorite = panel.openSaved(state().requests.getFirst());
            field(favorite, "request-name").setText("/discard");
            TestDialogManager.setTestDialog(message -> Messages.NO);
            assertTrue(panel.closeRequest(favorite));
            assertEquals("/changed", state().requests.getFirst().name);
            favorite = panel.openSaved(state().requests.getFirst());
            field(favorite, "request-name").setText("/orphan");
            state().requests.clear();
            TestDialogManager.setTestDialog(message -> { throw new AssertionError("Deleted favorites must not prompt"); });
            assertTrue(panel.closeRequest(favorite));
        } finally { TestDialogManager.setTestDialog(previous); }
    }

    public void testEditsPinPreviewImmediatelyIncludingUncommittedTableCells() {
        var previous = TestDialogManager.setTestDialog(message -> Messages.NO);
        try {
            RequestData request = endpoint("app-a", "/a");
            for (String name : List.of("request-name", "request-body")) {
                panel.openEndpoint(request, "");
                RequestEditor edited = panel.selectedEditor();
                assertTrue(state().requestTabs.getLast().preview);
                editField(edited, name, "modified");
                assertFalse(state().requestTabs.getLast().preview);
                panel.openEndpoint(endpoint("app-b", "/b"), "");
                assertEquals(2, tabs().getTabCount());
                assertSame(edited, tabs().getTabAt(0).getComponent());
                assertTrue(panel.closeRequest(edited));
                assertTrue(panel.closeRequest(panel.selectedEditor()));
            }
            panel.openEndpoint(request, "");
            RequestEditor edited = panel.selectedEditor();
            JTable table = find(edited, JTable.class, t -> t.getRowCount() > 0 && "page".equals(t.getValueAt(0, 1)));
            assertTrue(table.editCellAt(0, 2));
            table.getCellEditor().stopCellEditing();
            assertTrue("Merely focusing a cell must not pin a preview", state().requestTabs.getFirst().preview);
            assertTrue(table.editCellAt(0, 2));
            ((JTextComponent) table.getEditorComponent()).setText("8");
            assertTrue(table.isEditing());
            assertFalse("Typing must pin before the cell is committed", state().requestTabs.getFirst().preview);
            panel.openEndpoint(endpoint("app-b", "/b"), "");
            assertEquals(2, tabs().getTabCount());
            assertSame(edited, tabs().getTabAt(0).getComponent());
            assertEquals("8", edited.readFields().query.getFirst().value);
        } finally { TestDialogManager.setTestDialog(previous); }
    }

    public void testNewRequestKeepsFolderAndBoundaryTogglePersists() {
        FolderData folder = new FolderData(); state().folders.add(folder);
        RequestEditor editor = panel.newRequest(folder.id);
        assertEquals(folder.id, editor.readFields().folderId);
        assertEquals(state().selectedPrefixId, editor.readFields().prefixId);
        assertFalse(editor.isDirty());
        CollectionToggle toggle = find(panel, CollectionToggle.class, b -> "收起侧栏".equals(b.getToolTipText()));
        toggle.doClick();
        assertFalse(state().collectionsVisible);
        assertEquals("展开侧栏", toggle.getToolTipText());
        assertSame(editor, panel.selectedEditor());
        Disposer.dispose(panel);
        createPanel();
        assertFalse(state().collectionsVisible);
        assertEquals(folder.id, panel.selectedEditor().readFields().folderId);
        find(panel, CollectionToggle.class, b -> "展开侧栏".equals(b.getToolTipText())).doClick();
        assertTrue(state().collectionsVisible);
    }

    public void testPlusAndCloseTabActionsWorkWithZeroTabsAndAfterRestore() {
        JRootPane window = new JRootPane();
        window.setGlassPane(new com.intellij.openapi.wm.impl.IdeGlassPaneImpl(window));
        window.setContentPane(panel);
        panel.addNotify();
        try {
            panel.setSize(1080, 780); layoutTree(panel);
            assertEquals(0, tabs().getTabCount());
            assertTrue(tabs().entryPointToolbar.getComponent().getBounds().width > 0);
            assertTrue(tabs().entryPointToolbar.getComponent().getBounds().height > 0);
            var plus = tabs().entryPointToolbar.getActionGroup().getChildren(null)[0];
            perform(plus);
            assertEquals(1, tabs().getTabCount());
            assertFalse(panel.selectedEditor().isDirty());
            closeButton().doClick();
            assertEquals(0, tabs().getTabCount());
            layoutTree(panel);
            assertTrue(tabs().entryPointToolbar.getComponent().getBounds().height > 0);
            perform(plus);
            assertEquals(1, tabs().getTabCount());
        } finally { panel.removeNotify(); }
        Disposer.dispose(panel); createPanel();
        window.setContentPane(panel); panel.addNotify();
        try {
            // A restored request must still expose a clickable native close button.
            assertTrue(closeButton().getParent().getPreferredSize().width > 0);
            closeButton().doClick();
            assertEquals(0, tabs().getTabCount());
        } finally { panel.removeNotify(); }
    }
    public void testTabContextMenuUsesClickedTabForEveryCloseScope() {
        List<String> labels = List.of("关闭全部标签", "关闭右侧全部标签", "关闭左侧全部标签", "关闭其他标签");
        List<List<String>> survivors = List.of(List.of(), List.of("a", "b"), List.of("b", "c", "d"), List.of("b"));
        var previous = TestDialogManager.setTestDialog(message -> { throw new AssertionError("Clean tabs must not prompt"); });
        try {
            for (int operation = 0; operation < labels.size(); operation++) {
                for (String id : List.of("a", "b", "c", "d")) openHttp("http://localhost/" + id, id);
                RequestEditor anchor = (RequestEditor) tabs().getTabAt(1).getComponent();
                assertNotSame(anchor, panel.selectedEditor());
                JPopupMenu menu = panel.tabMenu(anchor);
                assertEquals(4, menu.getComponentCount());
                for (int i = 0; i < labels.size(); i++) assertEquals(labels.get(i), ((JMenuItem) menu.getComponent(i)).getText());
                ((JMenuItem) menu.getComponent(operation)).doClick();
                assertEquals(survivors.get(operation), state().requestTabs.stream().map(tab -> tab.request.id).toList());
                if (tabs().getTabCount() > 0) {
                    assertFalse(((JMenuItem) panel.tabMenu((RequestEditor) tabs().getTabAt(0).getComponent()).getComponent(2)).isEnabled());
                    assertFalse(((JMenuItem) panel.tabMenu((RequestEditor) tabs().getTabAt(tabs().getTabCount() - 1).getComponent()).getComponent(1)).isEnabled());
                    if (tabs().getTabCount() == 1)
                        assertFalse(((JMenuItem) panel.tabMenu(panel.selectedEditor()).getComponent(3)).isEnabled());
                    ((JMenuItem) panel.tabMenu(panel.selectedEditor()).getComponent(0)).doClick();
                }
                assertEquals(0, tabs().getTabCount()); assertTrue(state().requestTabs.isEmpty());
            }
        } finally { TestDialogManager.setTestDialog(previous); }
    }

    public void testBulkClosePromptsEachDirtyTabAndStopsAtCancel() {
        List<RequestEditor> opened = new ArrayList<>();
        for (String id : List.of("save", "discard", "cancel", "untouched")) {
            RequestEditor editor = openHttp("http://localhost/" + id, id);
            field(editor, "request-name").setText("edited-" + id);
            opened.add(editor);
        }
        List<String> prompts = new ArrayList<>();
        var previous = TestDialogManager.setTestDialog(message -> {
            prompts.add(message);
            return switch (prompts.size()) {
                case 1 -> Messages.YES;
                case 2 -> Messages.NO;
                case 3 -> Messages.CANCEL;
                default -> throw new AssertionError("Closing must stop after cancellation");
            };
        });
        try {
            ((JMenuItem) panel.tabMenu(opened.get(1)).getComponent(0)).doClick();
            assertEquals(3, prompts.size());
            assertTrue(prompts.get(0).contains("edited-save"));
            assertTrue(prompts.get(1).contains("edited-discard"));
            assertTrue(prompts.get(2).contains("edited-cancel"));
            assertEquals("edited-save", RequestStore.get(getProject()).find("save").name);
            assertEquals("discard", RequestStore.get(getProject()).find("discard").name);
            assertEquals(List.of("cancel", "untouched"), state().requestTabs.stream().map(tab -> tab.request.id).toList());
            assertSame(opened.get(2), panel.selectedEditor());
            assertTrue(opened.get(2).isDirty()); assertTrue(opened.get(3).isDirty());
            assertEquals("edited-untouched", opened.get(3).readFields().name);
        } finally { TestDialogManager.setTestDialog(previous); }
    }

    public void testBulkCloseCommitsPendingCellBeforeSavePrompt() {
        RequestData request = endpoint("app-a", "/pending"); request.id = "pending";
        RequestStore.get(getProject()).saveRequest(request);
        RequestEditor editor = panel.openSaved(request, true);
        JTable table = find(editor, JTable.class, t -> t.getRowCount() > 0 && "page".equals(t.getValueAt(0, 1)));
        assertTrue(table.editCellAt(0, 2));
        ((JTextComponent) table.getEditorComponent()).setText("42");
        assertTrue(table.isEditing());
        List<String> prompts = new ArrayList<>();
        var previous = TestDialogManager.setTestDialog(message -> { prompts.add(message); return Messages.YES; });
        try {
            ((JMenuItem) panel.tabMenu(editor).getComponent(0)).doClick();
            assertEquals(1, prompts.size()); assertEquals(0, tabs().getTabCount());
            assertEquals("42", RequestStore.get(getProject()).find("pending").query.getFirst().value);
        } finally { TestDialogManager.setTestDialog(previous); }
    }

    public void testBulkCloseStopsWhenSavingFails() {
        RequestEditor invalid = openHttp("", "invalid");
        field(invalid, "request-name").setText("edited-invalid");
        RequestEditor other = openHttp("http://localhost/other", "other");
        List<String> prompts = new ArrayList<>();
        var previous = TestDialogManager.setTestDialog(message -> { prompts.add(message); return Messages.YES; });
        try {
            ((JMenuItem) panel.tabMenu(other).getComponent(0)).doClick();
            assertEquals(2, prompts.size()); assertTrue(prompts.get(1).contains("请先填写请求地址"));
            assertEquals(2, tabs().getTabCount()); assertSame(invalid, panel.selectedEditor());
            assertEquals("invalid", RequestStore.get(getProject()).find("invalid").name);
            assertTrue(invalid.isDirty());
        } finally { TestDialogManager.setTestDialog(previous); }
    }

    private InplaceButton closeButton() {
        return find(tabs().getTabLabel(tabs().getSelectedInfo()), InplaceButton.class,
                b -> b.isVisible() && b.getToolTipText() != null && b.getToolTipText().startsWith("关闭标签"));
    }
    private static void layoutTree(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) if (child instanceof Container nested) layoutTree(nested);
    }

    public void testFavoritesMousePreviewDoubleClickAndTabDoubleClick() {
        RequestData a = endpoint("app-a", "/a"); a.id = "favorite-a"; a.favorite = true;
        RequestData b = endpoint("app-b", "/b"); b.id = "favorite-b"; b.favorite = true;
        RequestStore.get(getProject()).saveRequest(a); RequestStore.get(getProject()).saveRequest(b);
        Disposer.dispose(panel); createPanel();
        var tree = find(panel, com.intellij.ui.treeStructure.Tree.class, c -> "favorites-tree".equals(c.getName()));
        var root = (javax.swing.tree.DefaultMutableTreeNode) tree.getModel().getRoot();
        var first = new javax.swing.tree.TreePath(((javax.swing.tree.DefaultMutableTreeNode) root.getChildAt(0)).getPath());
        var second = new javax.swing.tree.TreePath(((javax.swing.tree.DefaultMutableTreeNode) root.getChildAt(1)).getPath());
        clickTree(tree, first, 1);
        assertEquals(1, tabs().getTabCount()); assertTrue(state().requestTabs.getFirst().preview);
        clickTree(tree, second, 1);
        assertEquals(1, tabs().getTabCount()); assertEquals("favorite-b", panel.selectedEditor().readFields().id);
        clickTree(tree, second, 2);
        assertEquals(1, tabs().getTabCount()); assertFalse(state().requestTabs.getFirst().preview);
        clickTree(tree, first, 1);
        assertEquals(2, tabs().getTabCount()); assertTrue(state().requestTabs.getLast().preview);
        panel.setSize(1080, 780); layoutTree(panel);
        var label = tabs().getTabLabel(tabs().getSelectedInfo());
        label.dispatchEvent(new java.awt.event.MouseEvent(label, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, 3, 3, 2, false, java.awt.event.MouseEvent.BUTTON1));
        assertFalse(state().requestTabs.getLast().preview);
        panel.openEndpoint(endpoint("app-c", "/c"), "");
        assertEquals(3, tabs().getTabCount());
        assertEquals(1L, state().requestTabs.stream().filter(t -> t.preview).count());
    }
    private static void clickTree(JTree tree, javax.swing.tree.TreePath path, int count) {
        tree.setSize(500, 500); tree.expandPath(path.getParentPath()); tree.setSelectionPath(path);
        Rectangle bounds = tree.getPathBounds(path); assertNotNull(bounds);
        tree.dispatchEvent(new java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, bounds.x + 3, bounds.y + 3, count, false, java.awt.event.MouseEvent.BUTTON1));
    }

    public void testConcurrentResponsesStayInTheirTabsAndClosingCancelsOnlyThatTab() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService workers = Executors.newCachedThreadPool(); server.setExecutor(workers);
        CountDownLatch slowStarted = new CountDownLatch(1), releaseSlow = new CountDownLatch(1);
        CountDownLatch closingStarted = new CountDownLatch(1), releaseClosing = new CountDownLatch(1), closingFinished = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            slowStarted.countDown();
            try {
                releaseSlow.await(10, TimeUnit.SECONDS);
                byte[] body = "response-a".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.createContext("/fast", exchange -> {
            byte[] body = "response-b".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/closing", exchange -> {
            closingStarted.countDown();
            try {
                releaseClosing.await(10, TimeUnit.SECONDS);
                byte[] body = "closed response".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
            } catch (Exception ignored) { }
            finally { exchange.close(); closingFinished.countDown(); }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            RequestEditor a = openHttp(base + "/slow", "a");
            click(a, "发送"); await(() -> slowStarted.getCount() == 0);
            RequestEditor b = openHttp(base + "/fast", "b");
            click(b, "发送"); await(() -> field(b, "response-raw").getText().equals("response-b"));
            assertEquals("", field(a, "response-raw").getText());
            assertRequesting(a);
            releaseSlow.countDown(); await(() -> field(a, "response-raw").getText().equals("response-a"));
            assertReady(a); assertReady(b);
            assertSame(b, panel.selectedEditor());
            panel.select(a); assertEquals("response-a", field(a, "response-raw").getText());
            panel.select(b); assertEquals("response-b", field(b, "response-raw").getText());
            RequestEditor closing = openHttp(base + "/closing", "closing");
            click(closing, "发送"); await(() -> closingStarted.getCount() == 0);
            assertTrue(panel.closeRequest(closing));
            assertReady(closing);
            releaseClosing.countDown(); await(() -> closingFinished.getCount() == 0);
            assertEquals("", field(closing, "response-raw").getText());
            assertEquals("response-a", field(a, "response-raw").getText());
            assertEquals("response-b", field(b, "response-raw").getText());
        } finally {
            releaseSlow.countDown(); releaseClosing.countDown(); server.stop(0); workers.shutdownNow();
        }
    }
    public void testLoadingRecoversAfterCancellationTimeoutAndInvalidUrlThenShowsFoldableJson() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService workers = Executors.newCachedThreadPool(); server.setExecutor(workers);
        CountDownLatch slowStarted = new CountDownLatch(1), releaseSlow = new CountDownLatch(1);
        String json = "{\"ok\":true,\"items\":[1,null]}";
        server.createContext("/slow", exchange -> {
            slowStarted.countDown();
            try {
                releaseSlow.await(10, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(204, -1);
            } catch (Exception ignored) { }
            finally { exchange.close(); }
        });
        server.createContext("/json", exchange -> {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            RequestEditor editor = openHttp(base + "/slow", "loading");
            click(editor, "发送"); assertRequesting(editor);
            await(() -> slowStarted.getCount() == 0);
            click(editor, "取消"); assertReady(editor);
            assertEquals("已取消请求", find(editor, JLabel.class, c -> "response-status".equals(c.getName())).getText());
            state().timeoutSeconds = 1;
            click(editor, "发送"); assertRequesting(editor);
            await(() -> find(editor, JButton.class, c -> "request-send".equals(c.getName())).isEnabled());
            assertReady(editor);
            assertEquals("请求失败", find(editor, JLabel.class, c -> "response-status".equals(c.getName())).getText());
            assertTrue(find(editor, ResponseBodyEditor.class, c -> true).getText().contains("Timeout"));
            RequestEditor invalid = openHttp("ftp://invalid/path", "invalid");
            click(invalid, "发送");
            await(() -> find(invalid, JButton.class, c -> "request-send".equals(c.getName())).isEnabled());
            assertReady(invalid);
            assertEquals("请求失败", find(invalid, JLabel.class, c -> "response-status".equals(c.getName())).getText());
            assertFalse(find(invalid, JButton.class, c -> "全部折叠".equals(c.getText())).isEnabled());
            RequestEditor success = openHttp(base + "/json", "json");
            state().timeoutSeconds = 30;
            click(success, "发送"); await(() -> json.equals(field(success, "response-raw").getText()));
            assertReady(success);
            var response = find(success, ResponseBodyEditor.class, c -> true);
            var folds = response.getEditor(true).getFoldingModel().getAllFoldRegions();
            assertEquals(2, folds.length);
            click(success, "全部折叠");
            for (var fold : folds) assertFalse(fold.isExpanded());
            assertEquals(json, field(success, "response-raw").getText());
            click(success, "全部展开");
            for (var fold : folds) assertTrue(fold.isExpanded());
        } finally {
            releaseSlow.countDown(); server.stop(0); workers.shutdownNow();
            Disposer.dispose(panel); UIUtil.dispatchAllInvocationEvents();
        }
    }
    private static void assertRequesting(RequestEditor editor) {
        JButton button = find(editor, JButton.class, c -> "request-send".equals(c.getName()));
        assertEquals("正在请求", button.getText()); assertFalse(button.isEnabled());
        assertTrue(button.getIcon() instanceof com.intellij.ui.AnimatedIcon);
        assertSame(button.getIcon(), button.getDisabledIcon());
        assertTrue(find(editor, JButton.class, c -> "取消".equals(c.getText())).isEnabled());
    }
    private static void assertReady(RequestEditor editor) {
        JButton button = find(editor, JButton.class, c -> "request-send".equals(c.getName()));
        assertEquals("发送", button.getText()); assertTrue(button.isEnabled());
        assertSame(com.intellij.icons.AllIcons.Actions.Execute, button.getIcon());
        assertFalse(find(editor, JButton.class, c -> "取消".equals(c.getText())).isEnabled());
    }
    private RequestEditor openHttp(String url, String id) {
        RequestData request = new RequestData(); request.url = url; request.id = id; request.name = id;
        RequestStore.get(getProject()).saveRequest(request);
        return panel.openSaved(request, true);
    }
    private static void perform(AnAction action) {
        action.actionPerformed(AnActionEvent.createEvent(action, DataContext.EMPTY_CONTEXT, null, "test", ActionUiKind.NONE, null));
    }
    private static void click(Container parent, String text) { find(parent, JButton.class, b -> text.equals(b.getText())).doClick(); }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents(); Thread.sleep(10);
        }
        assertTrue("Timed out waiting for HTTP/UI completion", condition.getAsBoolean());
    }
    private static <T extends Component> T find(Container parent, Class<T> type, Predicate<T> predicate) {
        List<T> matches = new ArrayList<>(); collect(parent, type, predicate, matches);
        if (matches.isEmpty()) throw new AssertionError("Missing UI component: " + type.getSimpleName());
        return matches.getFirst();
    }
    private static <T extends Component> void collect(Component component, Class<T> type, Predicate<T> predicate, List<T> matches) {
        if (type.isInstance(component) && predicate.test(type.cast(component))) matches.add(type.cast(component));
        if (component instanceof Container container) for (Component child : container.getComponents()) collect(child, type, predicate, matches);
    }
}
