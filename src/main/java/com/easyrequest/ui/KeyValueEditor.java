package com.easyrequest.ui;

import com.easyrequest.compat.JavaCompat;

import com.easyrequest.model.KeyValue;
import com.easyrequest.model.RequestData;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.table.JBTable;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import com.intellij.util.ui.UIUtil;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class KeyValueEditor extends JPanel {
    private final Rows model;
    private final JBTable table;
    private boolean updatingDocumentation;
    private Document editingDocument;
    public KeyValueEditor(Project project, boolean files, String keyLabel, String valueLabel, Runnable onChange) {
        this(project, files, keyLabel, valueLabel, false, onChange);
    }
    public KeyValueEditor(Project project, boolean files, String keyLabel, String valueLabel, boolean descriptions, Runnable onChange) {
        super(new BorderLayout());
        model = new Rows(files, keyLabel, valueLabel, descriptions);
        table = new JBTable(model);
        table.setStriped(true);
        table.setRowHeight(Math.max(26, table.getRowHeight()));
        table.setCellSelectionEnabled(true);
        table.putClientProperty("terminateEditOnFocusLost", true);
        DocumentListener editingListener = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onChange.run(); }
            @Override public void removeUpdate(DocumentEvent e) { onChange.run(); }
            @Override public void changedUpdate(DocumentEvent e) { onChange.run(); }
        };
        table.addPropertyChangeListener("tableCellEditor", e -> {
            if (editingDocument != null) editingDocument.removeDocumentListener(editingListener);
            editingDocument = table.isEditing() && table.getEditorComponent() instanceof JTextComponent ? ((JTextComponent) table.getEditorComponent()).getDocument() : null;
            if (editingDocument != null) editingDocument.addDocumentListener(editingListener);
        });
        table.getColumnModel().getColumn(0).setMaxWidth(48);
        table.getColumnModel().getColumn(1).setPreferredWidth(140);
        table.getColumnModel().getColumn(2).setPreferredWidth(260);
        if (files) {
            table.getColumnModel().getColumn(3).setMaxWidth(60);
            table.getColumnModel().getColumn(4).setPreferredWidth(140);
        }
        if (descriptions) {
            javax.swing.table.TableColumn column = table.getColumnModel().getColumn(model.getColumnCount() - 1);
            column.setPreferredWidth(220);
            column.setCellRenderer(new DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row, int col) {
                    super.getTableCellRendererComponent(t, value, selected, focus, row, col);
                    if (!selected) setForeground(UIUtil.getContextHelpForeground());
                    // Descriptions are plain text, even if documentation includes HTML or angle brackets.
                    putClientProperty("html.disable", Boolean.TRUE);
                    setToolTipText(value == null || JavaCompat.isBlank(value.toString()) ? null : value.toString());
                    return this;
                }
            });
        }
        JPanel decorated = ToolbarDecorator.createDecorator(table)
                .setAddAction(button -> {
                    stopEditing(); model.rows.add(new KeyValue()); model.fireTableDataChanged();
                    int index = model.rows.size() - 1;
                    table.changeSelection(index, 1, false, false); table.editCellAt(index, 1);
                    if (table.getEditorComponent() != null) table.getEditorComponent().requestFocusInWindow();
                })
                .setRemoveAction(button -> {
                    stopEditing(); int[] selected = table.getSelectedRows();
                    for (int i = selected.length - 1; i >= 0; i--) model.rows.remove(selected[i]);
                    model.fireTableDataChanged();
                }).disableUpDownActions().createPanel();
        add(decorated, BorderLayout.CENTER);
        if (files) {
            JButton browse = UiActions.button("为选中行选择文件…");
            browse.addActionListener(e -> {
                stopEditing(); int row = table.getSelectedRow();
                com.intellij.openapi.vfs.VirtualFile file = FileChooser.chooseFile(FileChooserDescriptorFactory.createSingleFileDescriptor(), project, null);
                if (file != null) {
                    if (row < 0) { model.rows.add(new KeyValue()); row = model.rows.size() - 1; }
                    model.rows.get(row).value = file.getPath(); model.rows.get(row).file = true; model.fireTableDataChanged();
                }
            });
            add(browse, BorderLayout.SOUTH);
        }
        model.addTableModelListener(e -> { if (!updatingDocumentation) onChange.run(); });
        setMinimumSize(new Dimension(100, 90));
    }
    public void stopEditing() { if (table.isEditing()) table.getCellEditor().stopCellEditing(); }
    public List<KeyValue> values() { return RequestData.copyRows(model.rows); }
    public void setValues(List<KeyValue> rows) { model.rows = RequestData.copyRows(rows); model.fireTableDataChanged(); }
    public void refreshDocumentation(List<KeyValue> source, boolean ignoreCase) {
        updatingDocumentation = true;
        try {
            com.easyrequest.model.RequestDocumentation.refreshRows(source, model.rows, ignoreCase);
            if (model.descriptions && !model.rows.isEmpty()) {
                int column = model.getColumnCount() - 1;
                for (int row = 0; row < model.rows.size(); row++) model.fireTableCellUpdated(row, column);
            }
        } finally { updatingDocumentation = false; }
    }

    private static final class Rows extends AbstractTableModel {
        private List<KeyValue> rows = new ArrayList<>();
        private final boolean files;
        private final boolean descriptions;
        private final String keyLabel, valueLabel;
        private Rows(boolean files, String keyLabel, String valueLabel, boolean descriptions) { this.files = files; this.keyLabel = keyLabel; this.valueLabel = valueLabel; this.descriptions = descriptions; }
        @Override public int getRowCount() { return rows.size(); }
        private boolean descriptionColumn(int column) { return descriptions && column == getColumnCount() - 1; }
        @Override public int getColumnCount() { return (files ? 5 : 3) + (descriptions ? 1 : 0); }
        @Override public String getColumnName(int column) { if (descriptionColumn(column)) return "说明"; switch (column) { case 0: return "启用"; case 1: return keyLabel; case 2: return valueLabel; case 3: return "文件"; default: return "Content-Type"; } }
        @Override public Class<?> getColumnClass(int column) { return column == 0 || (files && column == 3) ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int row, int column) { return !descriptionColumn(column); }
        @Override public Object getValueAt(int row, int column) { KeyValue v = rows.get(row); if (descriptionColumn(column)) return JavaCompat.isBlank(v.description) ? "" : "// " + v.description; switch (column) { case 0: return v.enabled; case 1: return v.key; case 2: return v.value; case 3: return v.file; default: return v.contentType; } }
        @Override public void setValueAt(Object value, int row, int column) {
            if (descriptionColumn(column)) return;
            Object previous = getValueAt(row, column);
            KeyValue v = rows.get(row);
            if (column == 1 && !Objects.equals(previous, String.valueOf(value))) v.description = "";
            switch (column) { case 0: v.enabled = Boolean.TRUE.equals(value); break; case 1: v.key = String.valueOf(value); break; case 2: v.value = String.valueOf(value); break; case 3: v.file = Boolean.TRUE.equals(value); break; default: v.contentType = String.valueOf(value); }
            if (!Objects.equals(previous, getValueAt(row, column))) fireTableRowsUpdated(row, row);
        }
    }
}
