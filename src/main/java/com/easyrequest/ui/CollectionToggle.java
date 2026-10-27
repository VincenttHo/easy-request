package com.easyrequest.ui;

import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import javax.swing.JButton;
import java.awt.*;
import java.awt.geom.Path2D;

/** A visible, theme-aware handle at the collection boundary. */
final class CollectionToggle extends JButton {
    private static final Color BACKGROUND = JBColor.namedColor("Button.startBackground", new JBColor(0xE5E7EB, 0x45484F));
    private static final Color HOVER = JBColor.namedColor("ActionButton.hoverBackground", new JBColor(0xD5DAE2, 0x565B65));
    private static final Color BORDER = JBColor.namedColor("Component.borderColor", new JBColor(0xB2B8C2, 0x707680));
    private static final Color FOCUS = JBColor.namedColor("Component.focusColor", new JBColor(0x3574F0, 0x6B9BFA));
    private boolean expanded;

    CollectionToggle(Runnable action) {
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(JBUI.size(28, 64));
        setMinimumSize(getPreferredSize());
        setMaximumSize(getPreferredSize());
        addActionListener(e -> action.run());
        setExpanded(true);
    }

    void setExpanded(boolean expanded) {
        this.expanded = expanded;
        String label = expanded ? "收起侧栏" : "展开侧栏";
        setToolTipText(label);
        getAccessibleContext().setAccessibleName(label);
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int inset = JBUI.scale(2);
            int width = getWidth() - inset * 2 - 1;
            int height = getHeight() - inset * 2 - 1;
            int arc = JBUI.scale(10);
            boolean highlighted = getModel().isRollover() || getModel().isPressed();
            g.setColor(highlighted ? HOVER : BACKGROUND);
            g.fillRoundRect(inset, inset, width, height, arc, arc);
            g.setColor(hasFocus() ? FOCUS : BORDER);
            g.setStroke(new BasicStroke(JBUI.scale(hasFocus() ? 2f : 1f)));
            g.drawRoundRect(inset, inset, width, height, arc, arc);

            // Two larger chevrons remain legible even with the sidebar collapsed.
            g.setColor(JBColor.foreground());
            g.setStroke(new BasicStroke(JBUI.scale(1.8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            float centerX = getWidth() / 2f, centerY = getHeight() / 2f;
            float direction = expanded ? -1 : 1;
            for (int i = 0; i < 2; i++) {
                float x = centerX + JBUI.scale(i == 0 ? -3f : 3f);
                Path2D chevron = new Path2D.Float();
                chevron.moveTo(x - direction * JBUI.scale(2f), centerY - JBUI.scale(5f));
                chevron.lineTo(x + direction * JBUI.scale(2f), centerY);
                chevron.lineTo(x - direction * JBUI.scale(2f), centerY + JBUI.scale(5f));
                g.draw(chevron);
            }
        } finally { g.dispose(); }
    }
}
