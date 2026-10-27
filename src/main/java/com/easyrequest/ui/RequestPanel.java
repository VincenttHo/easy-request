package com.easyrequest.ui;

import com.easyrequest.compat.JavaCompat;

import com.easyrequest.model.*;
import com.easyrequest.controller.EndpointIdentity;
import com.easyrequest.persistence.RequestStore;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.CustomShortcutSet;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.JBColor;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.ui.tabs.TabsListener;
import com.intellij.ui.tabs.impl.JBTabsImpl;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.*;

/** Controller/collection sidebar and independent request tabs. */
public final class RequestPanel extends JPanel implements Disposable {
    private final Project project;
    private final RequestStore store;
    private final ProjectState state;
    private final JBTabsImpl tabs;
    private final Map<RequestEditor, TabInfo> editors = new LinkedHashMap<>();
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("收藏夹");
    private final Tree collection = new Tree(new DefaultTreeModel(root));
    private final OnePixelSplitter collectionSplit;
    private final JPanel collectionPanel;
    private final CollectionToggle collectionToggle;
    private RequestEditor previewEditor;
    private ControllerBrowser browser;
    private boolean updatingTabs = true, disposed;

    public RequestPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        store = RequestStore.get(project);
        store.migrateCatalog(project);
        state = store.getState();
        tabs = new CompatibleTabs(project, this, () -> newRequest(""));
        tabs.setName("request-tabs");
        tabs.getPresentation().setSingleRow(true)
                .setTabLabelActionsAutoHide(false).setTabDraggingEnabled(false);
        tabs.addListener(new TabsListener() {
            @Override public void beforeSelectionChanged(TabInfo oldSelection, TabInfo newSelection) {
                if (oldSelection != null && oldSelection.getComponent() instanceof RequestEditor) ((RequestEditor) oldSelection.getComponent()).flushEditors();
            }
            @Override public void selectionChanged(TabInfo oldSelection, TabInfo newSelection) {
                if (updatingTabs || disposed) return;
                if (selectedEditor() != null) {
                    state.selectedPrefixId = selectedEditor().readFields().prefixId;
                    selectedEditor().activated();
                }
                persistTabs();
            }
        });
        tabs.addTabMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger() || disposed) return;
                TabInfo info = tabs.findInfo(e);
                if (info == null || !(info.getComponent() instanceof RequestEditor)) return;
                tabMenu(((RequestEditor) info.getComponent())).show(e.getComponent(), e.getX(), e.getY());
                e.consume();
            }
            @Override public void mouseClicked(MouseEvent e) {
                TabInfo info = tabs.findInfo(e);
                if (SwingUtilities.isMiddleMouseButton(e) && info != null && info.getComponent() instanceof RequestEditor) closeRequest(((RequestEditor) info.getComponent()));
                else if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2
                        && info != null && info.getComponent() instanceof RequestEditor) pin(((RequestEditor) info.getComponent()));
            }
        });
        collectionPanel = buildSidebar();
        collectionToggle = new CollectionToggle(() -> setCollectionVisible(!state.collectionsVisible));
        JPanel boundary = new JPanel();
        boundary.setBorder(JBUI.Borders.empty(0, 2));
        boundary.setLayout(new BoxLayout(boundary, BoxLayout.Y_AXIS));
        boundary.add(Box.createVerticalGlue()); boundary.add(collectionToggle); boundary.add(Box.createVerticalGlue());
        JPanel workspace = new JPanel(new BorderLayout());
        workspace.add(boundary, BorderLayout.WEST); workspace.add(tabs, BorderLayout.CENTER);
        workspace.setMinimumSize(JBUI.size(340, 180));
        float proportion = Float.isFinite(state.collectionsProportion)
                ? JavaCompat.clamp(state.collectionsProportion, 0.1f, 0.8f) : 0.24f;
        collectionSplit = new OnePixelSplitter(false, proportion);
        collectionSplit.setHonorComponentsMinimumSize(true);
        collectionSplit.setSecondComponent(workspace);
        collectionSplit.addPropertyChangeListener("proportion", e -> {
            if (state.collectionsVisible) state.collectionsProportion = collectionSplit.getProportion();
        });
        setCollectionVisible(state.collectionsVisible);
        add(collectionSplit, BorderLayout.CENTER);
        List<RequestTabState> restored = new ArrayList<>(state.requestTabs);
        int active = state.activeRequestTab;
        if (restored.isEmpty() && state.draft != null) restored.add(legacyDraft());
        for (RequestTabState tab : restored) addTab(tab);
        refreshTree();
        if (tabs.getTabCount() > 0) tabs.select(tabs.getTabAt(JavaCompat.clamp(active, 0, tabs.getTabCount() - 1)), false);
        updatingTabs = false;
        installShortcuts();
        persistTabs();
    }

    @Override public void addNotify() {
        super.addNotify();
        // Native tab actions stay hidden until they are updated inside a root pane.
        // Restored tabs are constructed before the tool window is attached.
        tabs.updateTabActions(false);
    }

    private RequestTabState legacyDraft() {
        RequestTabState tab = new RequestTabState();
        tab.request = state.draft == null ? new RequestData() : state.draft.copy();
        if (!JavaCompat.isBlank(tab.request.id)) {
            tab.baseline = state.requests.stream().filter(r -> r.id.equals(tab.request.id))
                    .findFirst().map(RequestData::copy).orElse(null);
        } else {
            tab.baseline = new RequestData();
            tab.baseline.prefixId = tab.request.prefixId;
            tab.baseline.folderId = tab.request.folderId;
        }
        return tab;
    }

    private void installShortcuts() {
        int menuMask = com.intellij.openapi.util.SystemInfo.isMac ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_S, menuMask), "save-request", () -> { if (selectedEditor() != null) selectedEditor().save(); });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_S, menuMask | InputEvent.SHIFT_DOWN_MASK), "toggle-favorite", () -> { if (selectedEditor() != null) selectedEditor().toggleFavorite(); });
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_T, menuMask), "new-request", () -> newRequest(""));
        bind(KeyStroke.getKeyStroke(KeyEvent.VK_W, menuMask), "close-request", () -> closeRequest(selectedEditor()));
    }
    private void bind(KeyStroke key, String id, Runnable action) {
        DumbAwareAction.create(id, e -> {
            if (!disposed) action.run();
        }).registerCustomShortcutSet(new CustomShortcutSet(key), this, this);
    }

    private RequestEditor addTab(RequestTabState state) {
        RequestData saved = store.find(state.request.id);
        if (saved == null && !state.request.legacyVariant && EndpointIdentity.of(project, state.request).isPresent())
            saved = store.ensureEndpoint(project, state.baseline == null ? state.request : state.baseline);
        if (saved != null) {
            RequestStore.copyRecordState(saved, state.request);
            if (state.baseline == null) state.baseline = saved.copy();
            else RequestStore.copyRecordState(saved, state.baseline);
            RequestDocumentation.refresh(saved, state.request);
            RequestDocumentation.refresh(saved, state.baseline);
        }
        RequestEditor editor = new RequestEditor(project, state, this::editorChanged, this::pin, this::refreshTree, this::showSettings);
        TabInfo info = new TabInfo(editor);
        info.setTabLabelActions(new DefaultActionGroup(new DumbAwareAction("关闭标签", null, AllIcons.Actions.Close) {
            // IDEA 2025.1's tab action panel only renders actions updated on the EDT.
            @Override public void update(AnActionEvent event) { event.getPresentation().setEnabled(true); }
            @Override public void actionPerformed(AnActionEvent e) { closeRequest(editor); }
        }), "EasyRequest.Tab");
        editors.put(editor, info);
        if (state.preview && previewEditor == null) previewEditor = editor;
        updateTab(editor);
        tabs.addTab(info);
        return editor;
    }
    RequestEditor newRequest(String folderId) {
        RequestTabState tab = new RequestTabState();
        tab.request.prefixId = state.selectedPrefixId;
        tab.request.folderId = folderId;
        tab.baseline = tab.request.copy();
        RequestEditor editor = addTab(tab);
        select(editor); persistTabs();
        return editor;
    }
    RequestEditor openSaved(RequestData request) {
        return openSaved(request, false);
    }
    RequestEditor openSaved(RequestData request, boolean pinned) {
        RequestData saved = store.find(request.id);
        if (saved == null) { store.saveRequest(request); saved = store.find(request.id); refreshTree(); }
        request = saved;
        for (RequestEditor editor : editors.keySet()) {
            if (!JavaCompat.isBlank(request.id) && request.id.equals(editor.readFields().id)) {
                if (pinned) pin(editor);
                select(editor); return editor;
            }
        }
        if (!closePreview()) return null;
        RequestTabState tab = new RequestTabState();
        tab.request = request.copy(); tab.baseline = request.copy();
        tab.preview = !pinned;
        RequestEditor editor = addTab(tab);
        select(editor); persistTabs();
        return editor;
    }
    public void openEndpoint(RequestData request, String note) {
        openEndpoint(request, note, false);
    }
    public void openEndpoint(RequestData request, String note, boolean pinned) {
        if (disposed) return;
        RequestData saved = store.ensureEndpoint(project, request);
        refreshTree();
        RequestEditor editor = openSaved(saved, pinned);
        if (editor != null && !JavaCompat.isBlank(note)) editor.showEndpointNote(note);
    }
    void select(RequestEditor editor) { tabs.select(editors.get(editor), true); }
    private boolean closePreview() {
        RequestEditor preview = previewEditor;
        if (preview == null) return true;
        // A pending table edit can pin the preview while its cell value is being committed.
        preview.flushEditors();
        return preview != previewEditor || closeRequest(preview);
    }
    void pin(RequestEditor editor) {
        if (editor != previewEditor || editor == null) return;
        previewEditor = null;
        updateTab(editor); persistTabs();
    }
    RequestEditor selectedEditor() {
        return tabs.getSelectedInfo() != null && tabs.getSelectedInfo().getComponent() instanceof RequestEditor ? ((RequestEditor) tabs.getSelectedInfo().getComponent()) : null;
    }
    boolean closeRequest(RequestEditor editor) {
        if (editor == null || !editors.containsKey(editor) || !editor.confirmClose()) return false;
        updatingTabs = true;
        if (previewEditor == editor) previewEditor = null;
        tabs.removeTab(editors.remove(editor));
        Disposer.dispose(editor);
        updatingTabs = false;
        if (selectedEditor() != null) {
            state.selectedPrefixId = selectedEditor().readFields().prefixId;
            selectedEditor().activated();
        }
        persistTabs();
        return true;
    }

    JPopupMenu tabMenu(RequestEditor anchor) {
        JPopupMenu menu = new JPopupMenu();
        List<TabInfo> ordered = JavaCompat.copyList(tabs.getTabs());
        int index = ordered.indexOf(editors.get(anchor));
        if (index < 0 || disposed) return menu;
        addCloseTabsItem(menu, "关闭全部标签", ordered);
        addCloseTabsItem(menu, "关闭右侧全部标签", ordered.subList(index + 1, ordered.size()));
        addCloseTabsItem(menu, "关闭左侧全部标签", ordered.subList(0, index));
        addCloseTabsItem(menu, "关闭其他标签", ordered.stream().filter(info -> info != editors.get(anchor)).collect(java.util.stream.Collectors.toList()));
        return menu;
    }

    private void addCloseTabsItem(JPopupMenu menu, String title, List<TabInfo> targets) {
        // Capture the original order before removals change tab indices and the active selection.
        List<RequestEditor> closing = targets.stream().map(info -> (RequestEditor) info.getComponent()).collect(java.util.stream.Collectors.toList());
        JMenuItem item = menuItem(title, null, () -> {
            for (RequestEditor editor : closing) {
                if (disposed) return;
                if (!editors.containsKey(editor)) continue;
                if (!closeRequest(editor)) {
                    select(editor);
                    return;
                }
            }
        });
        item.setEnabled(!closing.isEmpty());
        menu.add(item);
    }

    private void editorChanged(RequestEditor editor) {
        if (disposed) return;
        updateTab(editor); persistTabs();
    }
    private void updateTab(RequestEditor editor) {
        TabInfo info = editors.get(editor);
        if (info == null) return;
        RequestData request = editor.readFields();
        String label = JavaCompat.isBlank(request.name) ? "未命名请求" : request.name;
        if (label.length() > 28) label = label.substring(0, 27) + "…";
        info.clearText(false).append(request.method + "  " + label + (editor.isDirty() ? " *" : ""),
                editor == previewEditor ? SimpleTextAttributes.REGULAR_ITALIC_ATTRIBUTES : SimpleTextAttributes.REGULAR_ATTRIBUTES);
        info.setTooltipText(request.name + " · " + request.method + " " + request.url
                + (JavaCompat.isBlank(request.sourceModule) ? "" : " · " + request.sourceModule)
                + (editor == previewEditor ? "（临时预览，双击或修改后固定）" : "")
                + (editor.isDirty() ? "（未保存）" : ""));
    }
    private void persistTabs() {
        if (updatingTabs || disposed) return;
        List<RequestTabState> drafts = new ArrayList<>();
        for (TabInfo info : tabs.getTabs()) {
            RequestTabState draft = ((RequestEditor) info.getComponent()).snapshot();
            draft.preview = info.getComponent() == previewEditor;
            drafts.add(draft);
        }
        state.requestTabs = drafts;
        state.activeRequestTab = tabs.getSelectedInfo() == null ? 0 : Math.max(0, tabs.getIndexOf(tabs.getSelectedInfo()));
        state.draft = selectedEditor() == null ? null : selectedEditor().readFields();
    }
    private void showSettings() {
        editors.keySet().forEach(RequestEditor::flushEditors);
        if (new SettingsDialog(project, state).showAndGet()) {
            editors.keySet().forEach(RequestEditor::settingsChanged);
            if (selectedEditor() != null) state.selectedPrefixId = selectedEditor().readFields().prefixId;
            persistTabs();
        }
    }

    private void setCollectionVisible(boolean visible) {
        state.collectionsVisible = visible;
        collectionSplit.setFirstComponent(visible ? collectionPanel : null);
        collectionToggle.setExpanded(visible);
        collectionSplit.revalidate(); collectionSplit.repaint();
    }
    private JPanel buildSidebar() {
        browser = new ControllerBrowser(project, this::openSaved, this::refreshTree);
        Disposer.register(this, browser);
        JBTabbedPane views = new JBTabbedPane();
        views.setName("sidebar-views");
        views.addTab("接口", browser);
        views.addTab("收藏夹", buildCollection());
        views.setSelectedIndex("favorites".equals(state.sidebarView) ? 1 : 0);
        views.addChangeListener(e -> state.sidebarView = views.getSelectedIndex() == 1 ? "favorites" : "controllers");
        JPanel panel = new JPanel(new BorderLayout());
        panel.setMinimumSize(JBUI.size(160, 100));
        panel.add(views, BorderLayout.CENTER);
        return panel;
    }
    private JPanel buildCollection() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setMinimumSize(JBUI.size(130, 100)); panel.setBorder(JBUI.Borders.empty(10, 8, 8, 6));
        collection.setRootVisible(true);
        collection.setName("favorites-tree");
        collection.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
                Object item = ((DefaultMutableTreeNode) value).getUserObject();
                if (item instanceof FolderData) { setText(((FolderData) item).name); setIcon(AllIcons.Nodes.Folder); }
                else if (item instanceof RequestData) {
                    setText(((RequestData) item).method + "  " + ((RequestData) item).name + (((RequestData) item).sourceMissing ? " · 源码中已不存在" : ""));
                    setIcon(AllIcons.Nodes.Favorite); setToolTipText(((RequestData) item).url);
                }
                else setIcon(AllIcons.Nodes.Folder);
                return this;
            }
        });
        collection.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && collection.getPathForLocation(e.getX(), e.getY()) != null)
                    openSelected(e.getClickCount() >= 2);
            }
            @Override public void mousePressed(MouseEvent e) { popup(e); }
            @Override public void mouseReleased(MouseEvent e) { popup(e); }
            private void popup(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                TreePath path = collection.getPathForLocation(e.getX(), e.getY());
                if (path == null) collection.clearSelection(); else collection.setSelectionPath(path);
                collectionMenu().show(collection, e.getX(), e.getY());
            }
        });
        collection.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-request");
        collection.getActionMap().put("open-request", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { openSelected(true); }
        });
        JBScrollPane scroll = new JBScrollPane(collection); scroll.setBorder(JBUI.Borders.empty());
        panel.add(scroll, BorderLayout.CENTER);
        JBLabel hint = new JBLabel("单击预览 · 双击固定 · 右键管理"); hint.setForeground(JBColor.GRAY);
        panel.add(hint, BorderLayout.SOUTH);
        return panel;
    }
    JPopupMenu collectionMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("新建文件夹", AllIcons.Actions.NewFolder, this::addFolder));
        Object item = selectedItem();
        if (item instanceof RequestData) {
            menu.addSeparator(); menu.add(menuItem("打开接口", AllIcons.Actions.OpenNewTab, () -> openSelected(true)));
        }
        if (item instanceof RequestData || item instanceof FolderData) {
            menu.addSeparator();
            menu.add(menuItem(item instanceof RequestData ? "移动到文件夹…" : "重命名…", AllIcons.Actions.Edit, this::renameSelected));
            menu.add(menuItem(item instanceof RequestData ? "取消收藏" : "删除文件夹…", AllIcons.General.Remove, this::deleteSelected));
        }
        return menu;
    }
    private static JMenuItem menuItem(String label, Icon icon, Runnable action) {
        return UiActions.menuItem(label, icon, action);
    }
    private void refreshTree() {
        editors.keySet().forEach(RequestEditor::refreshStoredRecord);
        if (browser != null) browser.refreshSavedRequests();
        Set<String> expanded = new HashSet<>();
        java.util.Enumeration<javax.swing.tree.TreePath> paths = collection.getExpandedDescendants(new TreePath(root.getPath()));
        if (paths != null) while (paths.hasMoreElements()) {
            Object value = ((DefaultMutableTreeNode) paths.nextElement().getLastPathComponent()).getUserObject();
            if (value instanceof FolderData) expanded.add(((FolderData) value).id);
        }
        Object selected = selectedItem();
        String selectedId = selected instanceof RequestData ? ((RequestData) selected).id : selected instanceof FolderData ? ((FolderData) selected).id : "";
        boolean initial = root.getChildCount() == 0;
        root.removeAllChildren(); appendChildren(root, "", new HashSet<>());
        ((DefaultTreeModel) collection.getModel()).reload();
        collection.expandPath(new TreePath(root.getPath()));
        java.util.Enumeration<javax.swing.tree.TreeNode> nodes = root.depthFirstEnumeration();
        while (nodes.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
            Object item = node.getUserObject();
            if (item instanceof FolderData && (initial || expanded.contains(((FolderData) item).id))) collection.expandPath(new TreePath(node.getPath()));
            String id = item instanceof RequestData ? ((RequestData) item).id : item instanceof FolderData ? ((FolderData) item).id : "";
            if (!JavaCompat.isBlank(selectedId) && selectedId.equals(id)) collection.setSelectionPath(new TreePath(node.getPath()));
        }
    }
    private void appendChildren(DefaultMutableTreeNode parent, String folderId, Set<String> visited) {
        if (!visited.add(folderId)) return;
        state.folders.stream().filter(f -> f.parentId.equals(folderId)).sorted(Comparator.comparing(f -> f.name)).forEach(folder -> {
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(folder); parent.add(node); appendChildren(node, folder.id, visited);
        });
        state.requests.stream().filter(r -> r.favorite && r.folderId.equals(folderId)).sorted(Comparator.comparing(r -> r.name)).forEach(r -> parent.add(new DefaultMutableTreeNode(r)));
    }
    private Object selectedItem() { return collection.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode ? ((DefaultMutableTreeNode) collection.getLastSelectedPathComponent()).getUserObject() : null; }
    private String selectedFolderId() {
        Object item = selectedItem();
        return item instanceof FolderData ? ((FolderData) item).id : item instanceof RequestData ? ((RequestData) item).folderId : "";
    }
    private void openSelected(boolean pinned) { if (selectedItem() instanceof RequestData) openSaved(((RequestData) selectedItem()), pinned); }
    private void addFolder() {
        String value = Messages.showInputDialog(project, "文件夹名称（选中文件夹时会创建子文件夹）", "新建文件夹", AllIcons.Nodes.Folder);
        if (value == null || JavaCompat.isBlank(value)) return;
        FolderData folder = new FolderData(); folder.name = value.trim(); folder.parentId = selectedFolderId();
        state.folders.add(folder); refreshTree();
    }
    private void renameSelected() {
        Object item = selectedItem();
        if (item instanceof RequestData) {
            List<FolderChoice> choices = new ArrayList<>(); choices.add(new FolderChoice("", "收藏夹根目录"));
            appendFolderChoices(choices, "", "", new HashSet<>());
            String[] labels = choices.stream().map(FolderChoice::label).toArray(String[]::new);
            String current = choices.stream().filter(choice -> choice.id().equals(((RequestData) item).folderId)).map(FolderChoice::label).findFirst().orElse(labels[0]);
            int selected = Messages.showChooseDialog(project, "选择收藏文件夹", "移动收藏", AllIcons.Nodes.Folder, labels, current);
            if (selected >= 0 && selected < choices.size()) { store.moveFavorite(((RequestData) item).id, choices.get(selected).id()); refreshTree(); }
            return;
        }
        if (!(item instanceof FolderData)) return;
        String value = Messages.showInputDialog(project, "文件夹名称", "重命名文件夹", AllIcons.Nodes.Folder, ((FolderData) item).name, null);
        if (value != null && !JavaCompat.isBlank(value)) { ((FolderData) item).name = value.trim(); refreshTree(); }
    }
    private void deleteSelected() {
        Object item = selectedItem();
        if (!(item instanceof FolderData) && !(item instanceof RequestData)) return;
        if (item instanceof FolderData && Messages.showYesNoDialog(project, "删除此文件夹及子文件夹？其中的收藏将移到收藏夹根目录，接口及参数会保留。", "删除收藏文件夹", Messages.getQuestionIcon()) != Messages.YES) return;
        Set<String> removedRequests = new HashSet<>(), removedFolders = new HashSet<>();
        if (item instanceof RequestData) removedRequests.add(((RequestData) item).id);
        else {
            removedFolders.add(((FolderData) item).id);
            boolean added;
            do { added = false; for (FolderData folder : state.folders) if (removedFolders.contains(folder.parentId)) added |= removedFolders.add(folder.id); } while (added);
            state.requests.stream().filter(r -> removedFolders.contains(r.folderId)).forEach(r -> r.folderId = "");
        }
        removedRequests.forEach(id -> store.setFavorite(id, false));
        state.folders.removeIf(f -> removedFolders.contains(f.id));
        refreshTree(); persistTabs();
    }
    private static final class FolderChoice {
        private final String id;
        private final String label;
        private FolderChoice(String id, String label) {
            this.id = id;
            this.label = label;
        }
        public String id() { return id; }
        public String label() { return label; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof FolderChoice)) return false;
            FolderChoice that = (FolderChoice) other;
            return java.util.Objects.equals(id, that.id) && java.util.Objects.equals(label, that.label);
        }
        @Override public int hashCode() { return java.util.Objects.hash(id, label); }
        @Override public String toString() { return "FolderChoice[" + "id=" + id + ", " + "label=" + label + "]"; }
    }
    private void appendFolderChoices(List<FolderChoice> choices, String parent, String path, Set<String> seen) {
        if (!seen.add(parent)) return;
        for (FolderData folder : state.folders) if (folder.parentId.equals(parent)) {
            String label = JavaCompat.isBlank(path) ? folder.name : path + " / " + folder.name;
            choices.add(new FolderChoice(folder.id, label)); appendFolderChoices(choices, folder.id, label, seen);
        }
    }

    @Override public void dispose() {
        if (disposed) return;
        editors.keySet().forEach(RequestEditor::flushEditors);
        persistTabs(); disposed = true;
        editors.keySet().forEach(Disposer::dispose);
        editors.clear();
    }
}
