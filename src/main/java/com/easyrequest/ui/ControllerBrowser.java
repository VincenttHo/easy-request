package com.easyrequest.ui;

import com.easyrequest.compat.JavaCompat;

import com.easyrequest.controller.ControllerCatalog;
import com.easyrequest.controller.ControllerNavigation;
import com.easyrequest.model.RequestData;
import com.easyrequest.persistence.RequestStore;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.InplaceButton;
import com.intellij.ui.SearchTextField;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import org.jetbrains.concurrency.CancellablePromise;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.concurrent.Callable;

/** The interface tree reads persisted records; successful scans merge additions and source metadata. */
final class ControllerBrowser extends JPanel implements Disposable {
    private static final Logger LOG = Logger.getInstance(ControllerBrowser.class);
    private final Project project;
    private final RequestStore store;
    private final BiConsumer<RequestData, Boolean> open;
    private final Runnable changed;
    private final DefaultMutableTreeNode root = new DefaultMutableTreeNode("接口");
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final Tree tree = new Tree(model) {
        @Override public String getToolTipText(MouseEvent event) {
            TreePath path = getPathForLocation(event.getX(), event.getY());
            if (path == null) return null;
            Object item = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            if (item instanceof RequestData) return ((RequestData) item).method + " " + ((RequestData) item).url + (((RequestData) item).sourceMissing ? " · 源码中已不存在" : "") + " · " + ((RequestData) item).sourceMethod;
            if (item instanceof ControllerGroup) return ((ControllerGroup) item).className() + " · " + ((ControllerGroup) item).filePath();
            return item instanceof Group ? ((Group) item).path() : null;
        }
    };
    private final SearchTextField search = new SearchTextField();
    private final JBLabel status = new JBLabel();
    private final InplaceButton refresh;
    private final InplaceButton navigate;
    private CancellablePromise<?> scanTask, navigationTask;
    private final List<String> failedProjects = new ArrayList<>();
    private Boolean expandAll;

    private long scanGeneration;
    private boolean disposed, loading;
    private static final class Group {
        private final String id;
        private final String name;
        private final String path;
        private Group(String id, String name, String path) {
            this.id = id;
            this.name = name;
            this.path = path;
        }
        public String id() { return id; }
        public String name() { return name; }
        public String path() { return path; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Group)) return false;
            Group that = (Group) other;
            return java.util.Objects.equals(id, that.id) && java.util.Objects.equals(name, that.name) && java.util.Objects.equals(path, that.path);
        }
        @Override public int hashCode() { return java.util.Objects.hash(id, name, path); }
        @Override public String toString() { return "Group[" + "id=" + id + ", " + "name=" + name + ", " + "path=" + path + "]"; }
    }
    private static final class ControllerGroup {
        private final String id;
        private final String title;
        private final String className;
        private final String filePath;
        private final String fileUrl;
        private ControllerGroup(String id, String title, String className, String filePath, String fileUrl) {
            this.id = id;
            this.title = title;
            this.className = className;
            this.filePath = filePath;
            this.fileUrl = fileUrl;
        }
        public String id() { return id; }
        public String title() { return title; }
        public String className() { return className; }
        public String filePath() { return filePath; }
        public String fileUrl() { return fileUrl; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ControllerGroup)) return false;
            ControllerGroup that = (ControllerGroup) other;
            return java.util.Objects.equals(id, that.id) && java.util.Objects.equals(title, that.title) && java.util.Objects.equals(className, that.className) && java.util.Objects.equals(filePath, that.filePath) && java.util.Objects.equals(fileUrl, that.fileUrl);
        }
        @Override public int hashCode() { return java.util.Objects.hash(id, title, className, filePath, fileUrl); }
        @Override public String toString() { return "ControllerGroup[" + "id=" + id + ", " + "title=" + title + ", " + "className=" + className + ", " + "filePath=" + filePath + ", " + "fileUrl=" + fileUrl + "]"; }
    }
    private static final class ScanResult<T> {
        private final T value;
        private final String error;
        private ScanResult(T value, String error) {
            this.value = value;
            this.error = error;
        }
        public T value() { return value; }
        public String error() { return error; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ScanResult)) return false;
            ScanResult<?> that = (ScanResult<?>) other;
            return java.util.Objects.equals(value, that.value) && java.util.Objects.equals(error, that.error);
        }
        @Override public int hashCode() { return java.util.Objects.hash(value, error); }
        @Override public String toString() { return "ScanResult[" + "value=" + value + ", " + "error=" + error + "]"; }
    }

    ControllerBrowser(Project project, BiConsumer<RequestData, Boolean> open, Runnable changed) {
        super(new BorderLayout(0, JBUI.scale(6)));
        this.project = project; this.open = open; this.changed = changed; store = RequestStore.get(project);
        setBorder(JBUI.Borders.empty(8, 6, 6, 6));
        setMinimumSize(JBUI.size(160, 100));
        setName("controller-browser");
        search.getTextEditor().setName("controller-search");
        search.getTextEditor().getEmptyText().setText("搜索名称或路径");
        search.getTextEditor().getAccessibleContext().setAccessibleName("搜索接口");
        search.addDocumentListener(new DocumentAdapter() {
            @Override protected void textChanged(DocumentEvent e) { expandAll = null; rebuildTree(false); }
        });
        refresh = UiActions.icon("增量刷新接口（保留已保存的标题和参数）", AllIcons.Actions.Refresh, this::refresh);
        refresh.setName("refresh-controllers");
        navigate = UiActions.icon("定位到 Controller 源码（F4）", AllIcons.General.Locate, this::navigateToSource);
        navigate.setName("navigate-controller-source"); navigate.setEnabled(false);
        com.intellij.ui.InplaceButton expand = UiActions.icon("全部展开", AllIcons.Actions.Expandall, () -> expandTree(true));
        expand.setName("expand-all-controllers");
        com.intellij.ui.InplaceButton collapse = UiActions.icon("全部折叠", AllIcons.Actions.Collapseall, () -> expandTree(false));
        collapse.setName("collapse-all-controllers");
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        actions.add(navigate); actions.add(expand); actions.add(collapse); actions.add(refresh);
        JPanel toolbar = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        toolbar.add(search, BorderLayout.CENTER); toolbar.add(actions, BorderLayout.SOUTH);
        add(toolbar, BorderLayout.NORTH);
        tree.setName("controller-tree"); tree.setRootVisible(false); tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setCellRenderer(new ColoredTreeCellRenderer() {
            @Override public void customizeCellRenderer(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean focus) {
                Object item = ((DefaultMutableTreeNode) value).getUserObject();
                if (item instanceof Group) { setIcon(AllIcons.Nodes.Module); append(((Group) item).name()); }
                else if (item instanceof ControllerGroup) {
                    setIcon(AllIcons.Nodes.Class); append(((ControllerGroup) item).title());
                    String simpleName = ((ControllerGroup) item).className().substring(((ControllerGroup) item).className().lastIndexOf('.') + 1);
                    if (!((ControllerGroup) item).title().equals(simpleName)) append("  " + simpleName, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    append("  (" + ((DefaultMutableTreeNode) value).getChildCount() + ")", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                } else if (item instanceof RequestData) {
                    setIcon(((RequestData) item).favorite ? AllIcons.Nodes.Favorite : AllIcons.Nodes.Method);
                    append(((RequestData) item).method + "  ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                    append(((RequestData) item).name); append("  " + ((RequestData) item).url, SimpleTextAttributes.GRAYED_ATTRIBUTES);
                    if (((RequestData) item).sourceMissing) append("  · 源码中已不存在", SimpleTextAttributes.GRAYED_ATTRIBUTES);
                }
            }
        });
        tree.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    TreePath path = tree.getPathForLocation(e.getX(), e.getY());
                    if (path != null) openPath(path, e.getClickCount() >= 2);
                }
            }
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-endpoint");
        tree.getActionMap().put("open-endpoint", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { openPath(tree.getSelectionPath(), true); }
        });
        tree.addTreeSelectionListener(e -> {
            Object selected = selectedItem();
            navigate.setEnabled(selected instanceof ControllerGroup || selected instanceof RequestData && !JavaCompat.isBlank(((RequestData) selected).sourceFileUrl));
        });
        tree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), "navigate-source");
        tree.getActionMap().put("navigate-source", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { navigateToSource(); }
        });
        ToolTipManager.sharedInstance().registerComponent(tree);
        add(new JBScrollPane(tree), BorderLayout.CENTER);
        status.setName("controller-status"); status.setBorder(JBUI.Borders.empty(2));
        add(status, BorderLayout.SOUTH);
        rebuildTree(true);
        refresh();
    }

    private void refresh() {
        if (disposed || project.isDisposed()) return;
        long generation = ++scanGeneration;
        if (scanTask != null) scanTask.cancel();
        loading = true; failedProjects.clear(); tree.setPaintBusy(true);
        showStatus(DumbService.isDumb(project) ? "等待 IDEA 索引完成…" : "正在扫描项目目录…");
        List<RequestData> saved = store.getState().requests.stream().map(RequestData::copy).collect(java.util.stream.Collectors.toList());
        submitScan(generation, () -> ControllerCatalog.batches(project, saved), result -> {
            if (!JavaCompat.isBlank(result.error())) { finishScan(); showStatus(result.error()); return; }
            scanProject(generation, result.value(), 0);
        });
    }

    private <T> void submitScan(long generation, Callable<T> task, Consumer<ScanResult<T>> completed) {
        scanTask = ReadAction.nonBlocking(() -> {
            try { return new ScanResult<>(task.call(), ""); }
            catch (ProcessCanceledException e) { throw e; }
            catch (Exception e) { LOG.warn("Controller scan failed", e); return new ScanResult<T>(null, "加载失败，请点击刷新重试"); }
        }).withDocumentsCommitted(project).inSmartMode(project).expireWith(this).expireWith(project)
                .finishOnUiThread(ModalityState.any(), result -> {
                    if (!disposed && !project.isDisposed() && generation == scanGeneration) completed.accept(result);
                }).submit(AppExecutorUtil.getAppExecutorService());
    }

    private void scanProject(long generation, List<ControllerCatalog.ProjectBatch> batches, int index) {
        if (disposed || project.isDisposed() || generation != scanGeneration) return;
        if (index == batches.size()) { finishScan(); return; }
        com.easyrequest.controller.ControllerCatalog.ProjectBatch batch = batches.get(index);
        showStatus("正在扫描 " + batch.name() + "（" + (index + 1) + "/" + batches.size() + "） · 已完成 " + index + " 个项目");
        submitScan(generation, () -> ControllerCatalog.requests(project, batch), result -> {
            if (JavaCompat.isBlank(result.error())) {
                store.mergeCatalog(project, result.value(), batch);
                changed.run();
            } else failedProjects.add(batch.name());
            scanProject(generation, batches, index + 1);
        });
    }

    private void finishScan() {
        loading = false; tree.setPaintBusy(false);
        rebuildTree(false);
    }

    private Object selectedItem() {
        TreePath path = tree.getSelectionPath();
        return path == null ? null : ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
    }

    private void expandTree(boolean expand) {
        expandAll = expand;
        if (expand) for (int row = 0; row < tree.getRowCount(); row++) tree.expandRow(row);
        else for (int row = tree.getRowCount() - 1; row >= 0; row--) tree.collapseRow(row);
    }

    private void navigateToSource() {
        Object selected = selectedItem();
        String url, className, signature;
        if (selected instanceof RequestData) {
            url = ((RequestData) selected).sourceFileUrl; className = ((RequestData) selected).catalog.className; signature = ((RequestData) selected).sourceMethod;
        } else if (selected instanceof ControllerGroup) {
            url = ((ControllerGroup) selected).fileUrl();
            className = ((ControllerGroup) selected).className(); signature = "";
        } else return;
        if (navigationTask != null) navigationTask.cancel();
        navigationTask = ReadAction.nonBlocking(() -> ControllerNavigation.target(project, url, className, signature))
                .withDocumentsCommitted(project).inSmartMode(project).expireWith(this).expireWith(project)
                .finishOnUiThread(ModalityState.any(), target -> {
                    if (disposed || project.isDisposed()) return;
                    if (target == null) showStatus("源码文件已不存在，请刷新接口目录");
                    else target.navigate(true);
                }).submit(AppExecutorUtil.getAppExecutorService());
    }

    private void openPath(TreePath path, boolean pinned) {
        if (disposed || path == null || !(((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject() instanceof RequestData)) return;
        RequestData saved = store.find(((RequestData) ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()).id);
        if (saved != null) open.accept(saved, pinned);
    }

    void refreshSavedRequests() { if (!disposed) rebuildTree(false); }

    private void rebuildTree(boolean initial) {
        Set<String> expanded = new HashSet<>();
        Enumeration<TreePath> paths = tree.getExpandedDescendants(new TreePath(root.getPath()));
        if (paths != null) while (paths.hasMoreElements()) expanded.add(id(paths.nextElement()));
        String selected = id(tree.getSelectionPath());
        Set<String> previousGroups = new HashSet<>();
        for (int i = 0; i < root.getChildCount(); i++) previousGroups.add(id(new TreePath(((DefaultMutableTreeNode) root.getChildAt(i)).getPath())));
        root.removeAllChildren();
        String query = JavaCompat.strip(search.getText()).toLowerCase(Locale.ROOT);
        Map<String, DefaultMutableTreeNode> groups = new LinkedHashMap<>();
        Map<String, DefaultMutableTreeNode> controllers = new LinkedHashMap<>();
        List<RequestData> requests = new ArrayList<>(store.getState().requests);
        requests.sort(Comparator.comparing((RequestData request) -> group(request).name(), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(request -> request.catalog.controllerTitle, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(request -> request.name, String.CASE_INSENSITIVE_ORDER).thenComparing(request -> request.url));
        for (RequestData request : requests) {
            Group descriptor = group(request);
            com.easyrequest.model.CatalogLocation location = request.catalog;
            if (!contains(descriptor.name() + " " + location.controllerTitle + " " + location.className + " "
                    + request.name + " " + request.sourceMethod + " " + request.method + " " + request.url
                    + (request.sourceMissing ? " 源码中已不存在" : ""), query)) continue;
            javax.swing.tree.DefaultMutableTreeNode group = groups.computeIfAbsent(descriptor.id(), key -> {
                javax.swing.tree.DefaultMutableTreeNode node = new DefaultMutableTreeNode(descriptor);
                root.add(node); return node;
            });
            DefaultMutableTreeNode parent = group;
            if (request.catalogManaged) {
                String controllerId = JavaCompat.isBlank(location.controllerId) ? request.sourceFileUrl : location.controllerId;
                parent = controllers.computeIfAbsent(descriptor.id() + "\n" + controllerId, key -> {
                    String title = JavaCompat.isBlank(location.controllerTitle) ? sourceName(request.sourceFileUrl) : location.controllerTitle;
                    javax.swing.tree.DefaultMutableTreeNode node = new DefaultMutableTreeNode(new ControllerGroup(key, title, location.className, location.filePath, request.sourceFileUrl));
                    group.add(node); return node;
                });
            }
            parent.add(new DefaultMutableTreeNode(request, false));
        }
        model.reload();
        Enumeration<TreeNode> nodes = root.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            javax.swing.tree.DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
            TreePath path = new TreePath(node.getPath());
            if (Boolean.TRUE.equals(expandAll) || expanded.contains(id(path))
                    || expandAll == null && (!JavaCompat.isBlank(query) || node.getUserObject() instanceof Group
                    && (initial || !previousGroups.contains(((Group) node.getUserObject()).id())))) tree.expandPath(path);
            if (selected.equals(id(path))) tree.setSelectionPath(path);
        }
        tree.getEmptyText().setText(loading ? "正在加载接口…" : requests.isEmpty() ? "未找到接口，点击刷新或新建请求" : "没有匹配的接口");
        if (!loading) showCounts();
    }
    private static Group group(RequestData request) {
        if (request.legacyVariant) return new Group("legacy", "历史请求（旧版另存为）", "保留旧版本的独立配置");
        if (!request.catalogManaged) return new Group("manual", "手动接口", "手动保存的请求");
        com.easyrequest.model.CatalogLocation location = request.catalog;
        if (!JavaCompat.isBlank(location.groupId)) return new Group(location.groupId, location.groupName, location.groupPath);
        return new Group(request.sourceProjectUrl, JavaCompat.isBlank(request.sourceModule) ? "已保存接口" : request.sourceModule, request.sourceProjectUrl);
    }
    private static String sourceName(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        return JavaCompat.isBlank(name) ? "Controller" : name.replaceFirst("\\.java$", "");
    }
    private static boolean contains(String value, String query) { return value.toLowerCase(Locale.ROOT).contains(query); }
    private static String id(TreePath path) {
        if (path == null) return "";
        Object item = ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
        if (item instanceof Group) return ((Group) item).id();
        if (item instanceof ControllerGroup) return ((ControllerGroup) item).id();
        return item instanceof RequestData ? ((RequestData) item).id : "";
    }
    private void showCounts() {
        int controllers = 0, endpoints = 0, missing = 0;
        Enumeration<TreeNode> nodes = root.preorderEnumeration();
        while (nodes.hasMoreElements()) {
            Object item = ((DefaultMutableTreeNode) nodes.nextElement()).getUserObject();
            if (item instanceof ControllerGroup) controllers++;
            if (item instanceof RequestData) { endpoints++; if (((RequestData) item).sourceMissing) missing++; }
        }
        showStatus(controllers + " 个 Controller · " + endpoints + " 个接口" + (missing > 0 ? " · " + missing + " 个源码中已不存在" : "")
                + (failedProjects.isEmpty() ? "" : " · 加载失败：" + String.join("、", failedProjects) + "，请刷新重试"));
    }
    private void showStatus(String text) { status.setText(text); status.setToolTipText(text); }

    @Override public void dispose() {
        disposed = true;
        if (scanTask != null) scanTask.cancel();
        if (navigationTask != null) navigationTask.cancel();
        tree.setPaintBusy(false);
        ToolTipManager.sharedInstance().unregisterComponent(tree);
    }
}
