package com.easyrequest.ui;

import com.easyrequest.compat.JavaCompat;

import com.easyrequest.http.HttpExecutor;
import com.easyrequest.model.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nullable;
import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;

public final class SettingsDialog extends DialogWrapper {
    private static final String AUTO_MODULE = "自动识别 Controller / 启动模块";
    private final ProjectState state;
    private final KeyValueEditor prefixes;
    private final KeyValueEditor headers;
    private final JBTextField profile = new JBTextField();
    private final JComboBox<String> configModule = new JComboBox<>();
    private final JSpinner timeout = new JSpinner(new SpinnerNumberModel(30, 1, 600, 1));

    public SettingsDialog(Project project, ProjectState state) {
        super(project); this.state = state;
        setTitle("Easy Request · 项目设置");
        prefixes = new KeyValueEditor(project, false, "名称", "接口前缀，例如 http://localhost:8080", () -> {});
        headers = new KeyValueEditor(project, false, "Header", "值", () -> {});
        List<KeyValue> values = new ArrayList<>();
        for (UrlPrefix prefix : state.prefixes) {
            if (prefix.isAutoLocal()) continue;
            KeyValue row = new KeyValue(prefix.name, prefix.url); row.id = prefix.id; row.enabled = prefix.enabled; values.add(row);
        }
        prefixes.setValues(values); headers.setValues(state.globalHeaders);
        profile.setText(state.springProfile); timeout.setValue(JavaCompat.clamp(state.timeoutSeconds, 1, 600));
        configModule.addItem(AUTO_MODULE);
        Arrays.stream(ModuleManager.getInstance(project).getModules()).map(com.intellij.openapi.module.Module::getName)
                .sorted().forEach(configModule::addItem);
        if (!JavaCompat.isBlank(state.springConfigModule)) {
            if (ModuleManager.getInstance(project).findModuleByName(state.springConfigModule) == null) configModule.addItem(state.springConfigModule);
            configModule.setSelectedItem(state.springConfigModule);
        }
        init();
    }

    @Override protected JComponent createCenterPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setPreferredSize(new Dimension(680, 500));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("接口前缀", prefixes); tabs.addTab("公共 Headers", headers);
        panel.add(tabs, BorderLayout.CENTER);
        JPanel settings = new JPanel(new GridLayout(0, 2, 8, 8));
        settings.add(new JBLabel("配置来源模块")); settings.add(configModule);
        settings.add(new JBLabel("Spring profile（空白时读取配置文件）")); settings.add(profile);
        settings.add(new JBLabel("请求超时（秒）")); settings.add(timeout);
        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.add(settings, BorderLayout.NORTH);
        footer.add(new JBLabel("<html>“本地（自动）”按当前接口所属应用读取端口；上方只维护自定义前缀。<br>公共 Headers 默认应用到每个请求；接口同名 Header 优先。<br>数据仅保存在当前项目的本机 workspace.xml 中，包含填写的 Header 值。</html>"), BorderLayout.SOUTH);
        footer.setBorder(JBUI.Borders.emptyTop(8)); panel.add(footer, BorderLayout.SOUTH);
        return panel;
    }

    @Override protected @Nullable ValidationInfo doValidate() {
        for (KeyValue row : prefixes.values()) {
            if (!row.enabled || (JavaCompat.isBlank(row.key) && JavaCompat.isBlank(row.value))) continue;
            if (JavaCompat.isBlank(row.key)) return new ValidationInfo("请填写前缀名称", prefixes);
            try {
                URI uri = URI.create(row.value.trim());
                if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null
                        || uri.getRawQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null) throw new IllegalArgumentException();
            } catch (Exception e) { return new ValidationInfo("前缀必须是有效的 HTTP(S) 地址，且不包含 query、fragment 或凭据", prefixes); }
        }
        try { HttpExecutor.mergeHeaders(headers.values(), JavaCompat.list()); }
        catch (IllegalArgumentException e) { return new ValidationInfo(e.getMessage(), headers); }
        return null;
    }

    @Override protected void doOKAction() {
        prefixes.stopEditing(); headers.stopEditing();
        if (doValidate() != null) { super.doOKAction(); return; }
        state.prefixes.clear();
        state.prefixes.add(UrlPrefix.autoLocal());
        for (KeyValue row : prefixes.values()) {
            if (JavaCompat.isBlank(row.key) && JavaCompat.isBlank(row.value)) continue;
            UrlPrefix prefix = new UrlPrefix(row.key.trim(), row.value.trim()); prefix.id = row.id; prefix.enabled = row.enabled; state.prefixes.add(prefix);
        }
        state.globalHeaders = headers.values(); state.springProfile = profile.getText().trim();
        state.springConfigModule = configModule.getSelectedIndex() == 0 ? "" : (String) configModule.getSelectedItem();
        state.timeoutSeconds = (Integer) timeout.getValue();
        super.doOKAction();
    }
}
