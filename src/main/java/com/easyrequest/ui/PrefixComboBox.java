package com.easyrequest.ui;

import com.easyrequest.compat.JavaCompat;

import com.easyrequest.model.UrlPrefix;
import com.intellij.openapi.ui.ComboBox;
import javax.swing.DefaultListCellRenderer;
import com.intellij.util.ui.JBUI;
import javax.swing.JList;
import java.awt.Dimension;

/** Show the URL beside the path, and the environment name in the popup only. */
final class PrefixComboBox extends ComboBox<UrlPrefix> {
    PrefixComboBox() {
        setName("request-prefix");
        getAccessibleContext().setAccessibleName("接口前缀");
        setRenderer(new DefaultListCellRenderer() {
            @Override public java.awt.Component getListCellRendererComponent(JList<?> list, Object item,
                    int index, boolean selected, boolean focused) {
                UrlPrefix value = item instanceof UrlPrefix ? (UrlPrefix) item : null;
                String text = value == null ? "" : index < 0 ? value.url : value.toString();
                return super.getListCellRendererComponent(list, text, index, selected, focused);
            }
        });
        addActionListener(e -> revalidate());
    }

    @Override public Dimension getPreferredSize() {
        Dimension size = super.getPreferredSize();
        String text = getSelectedItem() instanceof UrlPrefix ? ((UrlPrefix) getSelectedItem()).url : "";
        int textWidth = getFontMetrics(getFont()).stringWidth(text) + JBUI.scale(34);
        size.width = JavaCompat.clamp(textWidth, JBUI.scale(80), JBUI.scale(260));
        return size;
    }

    @Override public int getMinimumPopupWidth() {
        int width = getWidth();
        for (int i = 0; i < getItemCount(); i++) {
            width = Math.max(width, getFontMetrics(getFont()).stringWidth(getItemAt(i).toString()) + JBUI.scale(40));
        }
        return Math.min(width, JBUI.scale(720));
    }
}
