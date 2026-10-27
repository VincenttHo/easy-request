package com.easyrequest.ui;

import com.intellij.ui.InplaceButton;
import com.intellij.ui.JBColor;
import com.intellij.util.ui.JBUI;
import javax.swing.*;
import javax.swing.plaf.basic.BasicMenuItemUI;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

final class UiActions {
    private static final Color HOVER = JBColor.namedColor("EasyRequest.Action.hoverBackground", new JBColor(0xE3EDFC, 0x354763));
    private static final Color PRESSED = JBColor.namedColor("EasyRequest.Action.pressedBackground", new JBColor(0xCDDEF8, 0x435D83));

    private UiActions() {}

    static InplaceButton icon(String label, Icon icon, Runnable action) {
        InplaceButton button = new InplaceButton(label, icon, e -> action.run()) {
            // Hook used by newer platforms; older versions paint their own native hover.
            protected void paintHover(Graphics graphics) {
                graphics.setColor(HOVER);
                graphics.fillRoundRect(0, 0, getWidth(), getHeight(), JBUI.scale(6), JBUI.scale(6));
            }
        };
        button.setPreferredSize(JBUI.size(26, 26));
        button.getAccessibleContext().setAccessibleName(label);
        installCursor(button);
        return button;
    }

    static JButton button(String label) { return button(label, null); }

    static JButton button(String label, Icon icon) {
        JButton button = new JButton(label, icon);
        button.setRolloverEnabled(true);
        Runnable update = () -> {
            ButtonModel model = button.getModel();
            Color color = !button.isEnabled() ? null : model.isPressed() && model.isArmed() ? PRESSED : model.isRollover() ? HOVER : null;
            // IDEA's button UI reads this per-component color while retaining its border and focus ring.
            button.putClientProperty("JButton.backgroundColor", color);
            button.repaint();
        };
        button.addChangeListener(e -> update.run());
        button.addPropertyChangeListener("enabled", e -> update.run());
        installCursor(button);
        return button;
    }

    static JMenuItem menuItem(String label, Icon icon, Runnable action) {
        JMenuItem item = new HoverMenuItem(label, icon);
        item.addActionListener(e -> action.run());
        installCursor(item);
        return item;
    }

    /** Paint locally: some IDEA menu delegates ignore rollover on plain Swing popup items. */
    private static final class HoverMenuItem extends JMenuItem {
        HoverMenuItem(String label, Icon icon) {
            super(label, icon);
            setRolloverEnabled(true);
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { getModel().setRollover(isEnabled()); }
                @Override public void mouseExited(MouseEvent e) { getModel().setRollover(false); }
            });
            addChangeListener(e -> repaint());
        }

        @Override public void updateUI() {
            setUI(new BasicMenuItemUI() {
                @Override protected void installDefaults() {
                    super.installDefaults();
                    selectionBackground = HOVER;
                    selectionForeground = JBColor.namedColor("MenuItem.foreground", JBColor.foreground());
                    acceleratorSelectionForeground = selectionForeground;
                }

                @Override protected void paintBackground(Graphics graphics, JMenuItem item, Color background) {
                    Graphics2D g = (Graphics2D) graphics.create();
                    try {
                        g.setColor(item.getBackground());
                        g.fillRect(0, 0, item.getWidth(), item.getHeight());
                        if (item.isEnabled() && (item.isArmed() || item.getModel().isRollover())) {
                            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                            g.setColor(HOVER);
                            int inset = JBUI.scale(2), arc = JBUI.scale(6);
                            g.fillRoundRect(inset, inset, item.getWidth() - inset * 2, item.getHeight() - inset * 2, arc, arc);
                        }
                    } finally { g.dispose(); }
                }
            });
        }
    }

    private static void installCursor(JComponent component) {
        Runnable update = () -> component.setCursor(Cursor.getPredefinedCursor(component.isEnabled() ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        component.addPropertyChangeListener("enabled", e -> update.run());
        update.run();
    }
}
