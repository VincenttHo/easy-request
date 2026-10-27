package com.easyrequest.ui;

import com.easyrequest.compat.JavaCompat;

import com.easyrequest.http.HttpExecutor;
import com.easyrequest.controller.ProjectConfigResolver;
import com.easyrequest.model.*;
import com.easyrequest.persistence.RequestStore;
import com.google.gson.*;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.AnimatedIcon;
import com.intellij.ui.InplaceButton;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.*;
import java.util.function.Consumer;
import com.intellij.util.ui.JBUI;
import com.intellij.util.concurrency.AppExecutorUtil;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.nio.file.Files;
import java.util.List;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

final class RequestEditor extends JPanel implements Disposable {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private final Project project;
    private final RequestStore store;
    private final ProjectState state;
    private final HttpExecutor http = new HttpExecutor();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong prefixGeneration = new AtomicLong();
    private volatile CompletableFuture<?> inFlight;
    private boolean disposed, loading;
    private RequestData current;
    private RequestData baseline;
    private final Consumer<RequestEditor> onChange;
    private final Consumer<RequestEditor> onEdit;
    private final Runnable onSave;
    private final Runnable settings;
    private final javax.swing.Timer changeTimer;
    private HttpExecutor.Result lastResponse;
    private final JBTextField name = new JBTextField();
    private final JBTextField url = new JBTextField();
    private final JComboBox<String> method = new JComboBox<>(new String[]{"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"});
    private final PrefixComboBox prefix = new PrefixComboBox();
    private final JComboBox<String> bodyType = new JComboBox<>(new String[]{"NONE", "JSON", "TEXT", "XML", "URLENCODED", "MULTIPART"});
    private final JBCheckBox globalHeaders = new JBCheckBox("自动添加公共 Headers", true);
    private final RequestBodyEditor body;
    private final ResponseBodyEditor responsePretty;
    private final JTextArea responseRaw = textArea(false);
    private final JTextArea responseHeaders = textArea(false);
    private final KeyValueEditor query, path, headers, form;
    private final JButton send = UiActions.button("发送", AllIcons.Actions.Execute);
    private final JButton cancel = UiActions.button("取消");
    private final InplaceButton favorite = UiActions.icon("收藏接口", AllIcons.Nodes.NotFavoriteOnHover, this::toggleFavorite);
    private final JButton expandResponse = UiActions.button("全部展开", AllIcons.Actions.Expandall);
    private final JButton collapseResponse = UiActions.button("全部折叠", AllIcons.Actions.Collapseall);
    private final Icon requestingIcon = new AnimatedIcon.Default();
    private final JBLabel status = new JBLabel("准备就绪");
    private final JBLabel hint = new JBLabel("输入完整 URL，或选择前缀后输入相对地址");
    private final JBLabel title = new JBLabel("标题");
    private final CardLayout bodyCards = new CardLayout();
    private final JPanel bodyContent = new JPanel(bodyCards);
    RequestEditor(Project project, RequestTabState tab, Consumer<RequestEditor> onChange, Consumer<RequestEditor> onEdit,
                  Runnable onSave, Runnable settings) {
        super(new BorderLayout());
        this.project = project;
        store = RequestStore.get(project);
        state = store.getState();
        this.onChange = onChange;
        this.onEdit = onEdit;
        this.onSave = onSave;
        this.settings = settings;
        body = new RequestBodyEditor(project, this, this::changed);
        responsePretty = new ResponseBodyEditor(project, this);
        send.setName("request-send");
        favorite.setName("request-favorite"); method.setName("request-method");
        name.setName("request-name"); url.setName("request-url"); body.setName("request-body");
        responseRaw.setName("response-raw"); status.setName("response-status");
        changeTimer = new javax.swing.Timer(180, e -> publishChange());
        changeTimer.setRepeats(false);
        query = new KeyValueEditor(project, false, "参数", "值", true, this::changed);
        path = new KeyValueEditor(project, false, "路径变量", "值", true, this::changed);
        headers = new KeyValueEditor(project, false, "Header", "值", true, this::changed);
        form = new KeyValueEditor(project, true, "字段", "值 / 文件路径", true, this::changed);
        setBorder(JBUI.Borders.empty(10, 8, 8, 8));
        add(buildEditor(), BorderLayout.CENTER);
        DocumentListener listener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { changed(); }
            @Override public void removeUpdate(DocumentEvent e) { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { changed(); }
        };
        name.getDocument().addDocumentListener(listener); url.getDocument().addDocumentListener(listener);
        method.addActionListener(e -> changed()); globalHeaders.addActionListener(e -> changed());
        prefix.addActionListener(e -> {
            if (!loading) {
                state.selectedPrefixId = selectedPrefixId(); changed();
                if (UrlPrefix.AUTO_LOCAL_ID.equals(selectedPrefixId())) refreshAutoLocalPrefix();
            }
        });
        bodyType.addActionListener(e -> { showBodyCard(); changed(); });
        send.addActionListener(e -> sendRequest()); cancel.addActionListener(e -> cancelRequest());
        expandResponse.addActionListener(e -> responsePretty.setAllExpanded(true));
        collapseResponse.addActionListener(e -> responsePretty.setAllExpanded(false));
        url.addActionListener(e -> sendRequest()); cancel.setEnabled(false);
        load(tab.request);
        baseline = tab.baseline == null ? null : tab.baseline.copy();
        updateTitle();
    }

    private JPanel buildEditor() {
        JPanel request = new JPanel(new BorderLayout(0, 8));
        request.setMinimumSize(new Dimension(320, 180));
        JPanel top = new JPanel(new BorderLayout(0, 6));
        JPanel nameRow = new JPanel(new BorderLayout(8, 0));
        nameRow.add(title, BorderLayout.WEST); nameRow.add(name, BorderLayout.CENTER);
        JPanel titleActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        titleActions.add(UiActions.icon("保存到接口树", com.intellij.openapi.util.IconLoader.getIcon("/icons/save.svg", RequestEditor.class), this::save));
        titleActions.add(favorite);
        titleActions.add(UiActions.icon("项目设置", AllIcons.General.GearPlain, settings));
        nameRow.add(titleActions, BorderLayout.EAST);
        name.getEmptyText().setText("请求名称"); top.add(nameRow, BorderLayout.NORTH);
        JPanel requestLine = new JPanel(new BorderLayout(6, 0));
        requestLine.add(method, BorderLayout.WEST);
        JPanel address = new JPanel(new BorderLayout(0, 0));
        address.add(prefix, BorderLayout.WEST); address.add(url, BorderLayout.CENTER);
        requestLine.add(address, BorderLayout.CENTER);
        url.getEmptyText().setText("/api/users 或 https://example.com/api/users");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0)); buttons.add(send); buttons.add(cancel); requestLine.add(buttons, BorderLayout.EAST);
        top.add(requestLine, BorderLayout.CENTER);
        hint.setForeground(JBColor.GRAY); hint.setToolTipText("完整 URL 会忽略所选前缀；路径变量在 Path 页签中填写");
        top.add(hint, BorderLayout.SOUTH); request.add(top, BorderLayout.NORTH);
        JTabbedPane tabs = new JTabbedPane(); tabs.addTab("Params", query); tabs.addTab("Path", path);
        JPanel headerTab = new JPanel(new BorderLayout(0, 6)); headerTab.add(globalHeaders, BorderLayout.NORTH); headerTab.add(headers, BorderLayout.CENTER);
        tabs.addTab("Headers", headerTab);
        JPanel bodyTab = new JPanel(new BorderLayout(0, 6));
        JPanel bodyToolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0)); bodyToolbar.add(bodyType);
        bodyToolbar.add(button("格式化 JSON", e -> {
            try { body.setText(JSON.toJson(JsonParser.parseString(body.getText()))); }
            catch (Exception error) { showError("JSON 格式无效：" + error.getMessage()); }
        })); bodyTab.add(bodyToolbar, BorderLayout.NORTH);
        JPanel empty = new JPanel(new BorderLayout()); empty.add(new JBLabel("此请求不发送 Body", SwingConstants.CENTER), BorderLayout.CENTER);
        bodyContent.add(empty, "NONE"); bodyContent.add(body, "RAW"); bodyContent.add(form, "FORM");
        bodyTab.add(bodyContent, BorderLayout.CENTER); tabs.addTab("Body", bodyTab); request.add(tabs, BorderLayout.CENTER);
        request.setBorder(JBUI.Borders.emptyBottom(8));
        JPanel response = buildResponse();
        response.setBorder(JBUI.Borders.emptyTop(8));
        OnePixelSplitter split = new OnePixelSplitter(true, 0.56f);
        split.setHonorComponentsMinimumSize(true);
        split.setFirstComponent(request);
        split.setSecondComponent(response);
        JPanel panel = new JPanel(new BorderLayout()); panel.add(split, BorderLayout.CENTER); return panel;
    }

    private JPanel buildResponse() {
        JPanel response = new JPanel(new BorderLayout(0, 6)); response.setMinimumSize(new Dimension(250, 130));
        JPanel toolbar = new JPanel(new BorderLayout()); toolbar.add(status, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        buttons.add(button("复制", e -> CopyPasteManager.getInstance().setContents(new StringSelection(responseRaw.getText()))));
        buttons.add(button("保存响应…", e -> saveResponse())); toolbar.add(buttons, BorderLayout.EAST);
        response.add(toolbar, BorderLayout.NORTH);
        JPanel pretty = new JPanel(new BorderLayout());
        JPanel foldingActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        foldingActions.add(expandResponse); foldingActions.add(collapseResponse);
        pretty.add(foldingActions, BorderLayout.NORTH); pretty.add(responsePretty, BorderLayout.CENTER);
        JTabbedPane tabs = new JTabbedPane(); tabs.addTab("响应", pretty); tabs.addTab("Raw", new JBScrollPane(responseRaw)); tabs.addTab("Headers", new JBScrollPane(responseHeaders));
        response.add(tabs, BorderLayout.CENTER); return response;
    }

    private void changed() {
        if (loading || current == null || disposed) return;
        // Pin immediately: another click may arrive before the debounced persistence callback.
        onEdit.accept(this);
        changeTimer.restart();
    }
    private void publishChange() {
        if (disposed) return;
        updateTitle();
        onChange.accept(this);
    }
    private void updateTitle() {
        title.setText(isDirty() ? "标题 · 未保存" : "标题");
        boolean starred = current != null && store.find(current.id) != null && store.find(current.id).favorite;
        String label = starred ? "取消收藏" : "收藏接口";
        favorite.setIcon(starred ? AllIcons.Nodes.Favorite : AllIcons.Nodes.NotFavoriteOnHover);
        favorite.setToolTipText(label); favorite.getAccessibleContext().setAccessibleName(label);
    }
    void flushEditors() { query.stopEditing(); path.stopEditing(); headers.stopEditing(); form.stopEditing(); }
    RequestData readFields() {
        RequestData request = current == null ? new RequestData() : current.copy();
        request.name = name.getText().trim();
        if (JavaCompat.isBlank(request.id)) { request.url = url.getText().trim(); request.method = Objects.toString(method.getSelectedItem(), "GET"); }
        request.prefixId = selectedPrefixId(); request.query = query.values(); request.pathVariables = path.values(); request.headers = headers.values();
        request.bodyType = Objects.toString(bodyType.getSelectedItem(), "NONE"); request.body = body.getText(); request.form = form.values(); request.useGlobalHeaders = globalHeaders.isSelected();
        return request;
    }
    RequestTabState snapshot() {
        RequestTabState tab = new RequestTabState();
        tab.request = readFields();
        tab.baseline = baseline == null ? null : baseline.copy();
        return tab;
    }
    void refreshStoredRecord() {
        RequestData saved = store.find(current.id);
        if (saved == null) return;
        RequestStore.copyRecordState(saved, current);
        if (baseline != null) RequestStore.copyRecordState(saved, baseline);
        RequestDocumentation.refresh(saved, current);
        if (baseline != null) RequestDocumentation.refresh(saved, baseline);
        query.refreshDocumentation(saved.query, false); path.refreshDocumentation(saved.pathVariables, false);
        headers.refreshDocumentation(saved.headers, true); form.refreshDocumentation(saved.form, false);
        body.setDescriptions(saved.bodyDescriptions);
        updateAddressState();
        publishChange();
    }
    boolean isDirty() { return baseline == null || !JSON.toJson(readFields()).equals(JSON.toJson(baseline)); }
    void activated() { refreshStoredRecord(); refreshAutoLocalPrefix(); }
    void settingsChanged() {
        flushEditors(); refreshPrefixes(selectedPrefixId()); refreshAutoLocalPrefix(); publishChange();
    }
    void showEndpointNote(String note) {
        String message = JavaCompat.isBlank(note) ? "已从 Controller 生成请求，请检查示例参数后发送" : note;
        hint.setText(message); hint.setToolTipText(message); url.requestFocusInWindow();
    }
    void toggleFavorite() {
        if (store.find(current.id) == null && !save()) return;
        RequestData saved = store.find(current.id);
        store.setFavorite(saved.id, !saved.favorite);
        refreshStoredRecord(); onSave.run();
    }
    private void updateAddressState() {
        boolean locked = !JavaCompat.isBlank(current.id);
        if (locked) {
            boolean previous = loading; loading = true;
            if (!url.getText().equals(current.url)) url.setText(current.url);
            if (!Objects.equals(method.getSelectedItem(), current.method)) method.setSelectedItem(current.method);
            loading = previous;
        }
        url.setEditable(!locked); method.setEnabled(!locked);
        url.setToolTipText(locked ? "接口地址固定；通过左侧前缀切换环境，路径变量在 Path 中填写" : "填写请求地址，保存后固定");
        hint.setText(current.sourceMissing ? "源码中已不存在 · 已保留参数和收藏，仍可发送请求"
                : locked ? "已保存到接口树 · 可修改标题、前缀和参数；星标用于收藏" : "填写请求地址后保存到接口树；完整 URL 自动忽略前缀");
        hint.setToolTipText(hint.getText());
    }
    private String selectedPrefixId() { return prefix.getSelectedItem() instanceof UrlPrefix ? ((UrlPrefix) prefix.getSelectedItem()).id : ""; }
    private void refreshPrefixes(String selectedId) {
        boolean previous = loading; loading = true;
        prefix.removeAllItems(); UrlPrefix none = new UrlPrefix(); none.id = ""; prefix.addItem(none);
        UrlPrefix chosen = none;
        for (UrlPrefix stored : state.prefixes) {
            if (!stored.enabled) continue;
            UrlPrefix value = stored.isAutoLocal() ? UrlPrefix.autoLocal() : stored;
            prefix.addItem(value); if (value.id.equals(selectedId)) chosen = value;
        }
        prefix.setSelectedItem(chosen); loading = previous;
    }
    private void refreshAutoLocalPrefix() {
        if (current == null || disposed) return;
        RequestData request = current.copy();
        long sequence = prefixGeneration.incrementAndGet();
        ReadAction.nonBlocking(() -> ProjectConfigResolver.forRequest(project, request))
                .inSmartMode(project).expireWith(this).expireWith(project).expireWhen(() -> disposed || prefixGeneration.get() != sequence)
                .finishOnUiThread(ModalityState.defaultModalityState(), resolution -> {
                    if (disposed || prefixGeneration.get() != sequence) return;
                    try { updateAutoLocalPreview(resolution.localBaseUrl(), resolution.source(), String.join("；", resolution.config().warnings())); }
                    catch (IllegalArgumentException error) { updateAutoLocalPreview("需选择配置 / 检查端口", "", error.getMessage()); }
                }).submit(AppExecutorUtil.getAppExecutorService());
    }
    private void updateAutoLocalPreview(String base, String source, String tooltip) {
        for (int i = 0; i < prefix.getItemCount(); i++) {
            UrlPrefix item = prefix.getItemAt(i);
            if (!item.isAutoLocal()) continue;
            item.name = JavaCompat.isBlank(source) ? "本地（自动）" : "本地（自动 · " + source + "）";
            item.url = base;
        }
        if (UrlPrefix.AUTO_LOCAL_ID.equals(selectedPrefixId())) prefix.setToolTipText(tooltip);
        prefix.revalidate(); prefix.repaint();
    }
    private void showBodyCard() {
        String type = Objects.toString(bodyType.getSelectedItem(), "NONE");
        body.setBodyType(type);
        bodyCards.show(bodyContent, "NONE".equals(type) ? "NONE" : ("URLENCODED".equals(type) || "MULTIPART".equals(type)) ? "FORM" : "RAW");
    }
    private void load(RequestData request) {
        cancelRequest(); loading = true;
        current = request.copy(); name.setText(request.name); url.setText(request.url); method.setSelectedItem(request.method);
        refreshPrefixes(request.prefixId); query.setValues(request.query); path.setValues(request.pathVariables); headers.setValues(request.headers);
        globalHeaders.setSelected(request.useGlobalHeaders); bodyType.setSelectedItem(request.bodyType); body.setText(request.body); form.setValues(request.form);
        body.setDescriptions(request.bodyDescriptions);
        showBodyCard(); title.setText("标题");
        updateAddressState();
        loading = false;
        responseRaw.setText(""); showResponseBody("", false); responseHeaders.setText(""); lastResponse = null; status.setText("准备就绪");
        refreshAutoLocalPrefix();
    }
    boolean confirmClose() {
        flushEditors();
        if (JavaCompat.isBlank(current.id) || state.requests.stream().noneMatch(request -> request.id.equals(current.id)) || !isDirty()) return true;
        String label = JavaCompat.isBlank(name.getText()) ? "未命名请求" : name.getText();
        int choice = Messages.showYesNoCancelDialog(project, "“" + label + "”有未保存的修改，是否保存后关闭？",
                "关闭请求标签", "保存", "不保存", "取消", Messages.getQuestionIcon());
        return choice == Messages.NO || (choice == Messages.YES && save());
    }
    boolean save() {
        flushEditors();
        RequestData request = readFields();
        if (JavaCompat.isBlank(request.url)) { showError("请先填写请求地址"); return false; }
        if (JavaCompat.isBlank(request.name)) request.name = request.method + " " + request.url;
        store.saveRequest(request);
        current = store.find(request.id).copy();
        loading = true; name.setText(request.name); loading = false;
        updateAddressState();
        baseline = readFields();
        publishChange(); onSave.run();
        return true;
    }

    private void sendRequest() {
        flushEditors();
        if (!send.isEnabled()) return;
        RequestData request = readFields(); publishChange();
        if (request.bodyType.equals("JSON") && !JavaCompat.isBlank(request.body)) {
            try { JsonParser.parseString(request.body); } catch (Exception e) { showError("JSON 格式无效：" + e.getMessage()); return; }
        }
        String base = prefix.getSelectedItem() instanceof UrlPrefix ? ((UrlPrefix) prefix.getSelectedItem()).url : "";
        boolean autoLocal = UrlPrefix.AUTO_LOCAL_ID.equals(request.prefixId) && !request.url.matches("(?i)^https?://.*");
        List<KeyValue> shared = RequestData.copyRows(state.globalHeaders); int timeout = state.timeoutSeconds;
        long sequence = generation.incrementAndGet(); setRequesting(true);
        status.setForeground(JBColor.foreground()); status.setText("请求中…");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                if (generation.get() != sequence) return;
                String resolvedBase = base;
                if (autoLocal) {
                    ProjectConfigResolver.Resolution resolution = DumbService.getInstance(project)
                            .runReadActionInSmartMode(() -> ProjectConfigResolver.forRequest(project, request));
                    resolvedBase = resolution.localBaseUrl();
                    String preview = resolvedBase;
                    ApplicationManager.getApplication().invokeLater(() -> {
                        if (!disposed && generation.get() == sequence) updateAutoLocalPreview(preview, resolution.source(), String.join("；", resolution.config().warnings()));
                    });
                }
                if (generation.get() != sequence) return;
                CompletableFuture<HttpExecutor.Result> future = http.send(request, resolvedBase, shared, timeout);
                synchronized (generation) {
                    if (generation.get() != sequence) { future.cancel(true); return; }
                    inFlight = future;
                }
                future.thenApply(RequestEditor::responseView).whenComplete((view, error) ->
                    ApplicationManager.getApplication().invokeLater(() -> {
                    if (disposed || project.isDisposed() || generation.get() != sequence) return;
                    inFlight = null; setRequesting(false);
                    if (error != null) { showRequestError(error); return; }
                    displayResponse(view);
                    }));
            } catch (Exception error) { ApplicationManager.getApplication().invokeLater(() -> {
                if (disposed || project.isDisposed() || generation.get() != sequence) return;
                setRequesting(false); showRequestError(error);
            }); }
        });
    }
    private void cancelRequest() {
        synchronized (generation) {
            generation.incrementAndGet(); CompletableFuture<?> future = inFlight; inFlight = null;
            if (future != null) future.cancel(true);
        }
        if (!send.isEnabled()) status.setText("已取消请求");
        setRequesting(false);
    }
    private void setRequesting(boolean requesting) {
        send.setText(requesting ? "正在请求" : "发送");
        send.setIcon(requesting ? requestingIcon : AllIcons.Actions.Execute);
        // Keep the animated icon live even while the button is disabled against duplicate sends.
        send.setDisabledIcon(requesting ? requestingIcon : null);
        send.setEnabled(!requesting); cancel.setEnabled(requesting);
    }
    private void showRequestError(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        status.setForeground(JBColor.RED); status.setText("请求失败");
        showResponseBody(error.getClass().getSimpleName() + ": " + Objects.toString(error.getMessage(), "请检查地址、网络和证书"), false);
        responseRaw.setText(""); responseHeaders.setText(""); lastResponse = null;
    }
    private static final class ResponseView {
        private final HttpExecutor.Result result;
        private final String raw;
        private final String pretty;
        private final boolean json;
        private final String headers;
        private ResponseView(HttpExecutor.Result result, String raw, String pretty, boolean json, String headers) {
            this.result = result;
            this.raw = raw;
            this.pretty = pretty;
            this.json = json;
            this.headers = headers;
        }
        public HttpExecutor.Result result() { return result; }
        public String raw() { return raw; }
        public String pretty() { return pretty; }
        public boolean json() { return json; }
        public String headers() { return headers; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ResponseView)) return false;
            ResponseView that = (ResponseView) other;
            return java.util.Objects.equals(result, that.result) && java.util.Objects.equals(raw, that.raw) && java.util.Objects.equals(pretty, that.pretty) && java.util.Objects.equals(json, that.json) && java.util.Objects.equals(headers, that.headers);
        }
        @Override public int hashCode() { return java.util.Objects.hash(result, raw, pretty, json, headers); }
        @Override public String toString() { return "ResponseView[" + "result=" + result + ", " + "raw=" + raw + ", " + "pretty=" + pretty + ", " + "json=" + json + ", " + "headers=" + headers + "]"; }
    }
    private static ResponseView responseView(HttpExecutor.Result result) {
        String raw = result.text(); boolean truncated = raw.length() > 200_000;
        if (truncated) raw = raw.substring(0, 200_000) + "\n\n[预览已截断，可通过“保存响应”导出完整内容]";
        String pretty = raw; boolean json = false;
        if (!truncated && (JavaCompat.strip(raw).startsWith("{") || JavaCompat.strip(raw).startsWith("["))) {
            try {
                pretty = JSON.toJson(JsonParser.parseString(raw));
                if (pretty.length() > 200_000) pretty = pretty.substring(0, 200_000) + "\n\n[预览已截断，可通过“保存响应”导出完整内容]";
                else json = true;
            } catch (Exception ignored) {}
        }
        StringBuilder lines = new StringBuilder(); result.headers().forEach((key, values) -> values.forEach(value -> lines.append(key).append(": ").append(value).append('\n')));
        return new ResponseView(result, raw, pretty, json, lines.toString());
    }
    private void displayResponse(ResponseView view) {
        HttpExecutor.Result result = view.result;
        lastResponse = result;
        status.setText("HTTP " + result.status() + "  ·  " + result.millis() + " ms  ·  " + result.body().length + " bytes");
        status.setForeground(result.status() >= 400 ? JBColor.RED : JBColor.foreground());
        status.setToolTipText(result.uri().toString()); responseRaw.setText(view.raw);
        showResponseBody(view.pretty, view.json); responseRaw.setCaretPosition(0);
        responseHeaders.setText(view.headers); responseHeaders.setCaretPosition(0);
    }
    private void showResponseBody(String text, boolean json) {
        responsePretty.showResponse(text, json);
        expandResponse.setEnabled(json); collapseResponse.setEnabled(json);
    }
    private void saveResponse() {
        if (lastResponse == null) return;
        com.intellij.openapi.vfs.VirtualFileWrapper file = FileChooserFactory.getInstance().createSaveFileDialog(new FileSaverDescriptor("保存响应", "保存响应原始字节"), project).save((com.intellij.openapi.vfs.VirtualFile) null, "response.txt");
        if (file == null) return;
        byte[] bytes = lastResponse.body().clone();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try { Files.write(file.getFile().toPath(), bytes); }
            catch (Exception error) { ApplicationManager.getApplication().invokeLater(() -> { if (!disposed) showError(error.getMessage()); }); }
        });
    }
    private void showError(String message) { Messages.showErrorDialog(project, message, "Easy Request"); }
    private static JButton button(String label, ActionListener action) { JButton button = UiActions.button(label); button.addActionListener(action); return button; }
    private static JTextArea textArea(boolean editable) {
        JTextArea area = new JTextArea(); area.setEditable(editable); area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        area.setTabSize(2); area.setMargin(JBUI.insets(8)); return area;
    }
    @Override public void dispose() {
        if (disposed) return;
        changeTimer.stop(); disposed = true; prefixGeneration.incrementAndGet(); cancelRequest(); http.close();
    }
}
