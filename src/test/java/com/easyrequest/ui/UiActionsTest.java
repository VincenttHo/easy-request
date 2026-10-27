package com.easyrequest.ui;

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.ui.JBColor;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class UiActionsTest extends LightJavaCodeInsightFixtureTestCase {
    public void testMenuHoverActuallyChangesPaintAndDisabledItemsStayUnchanged() throws Exception {
        boolean wasDark = !JBColor.isBright();
        Object foreground = UIManager.get("MenuItem.foreground");
        BufferedImage preview = new BufferedImage(720, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D output = preview.createGraphics();
        try {
            for (int theme = 0; theme < 2; theme++) {
                JBColor.setDark(theme == 1);
                UIManager.put("MenuItem.foreground", theme == 0 ? Color.BLACK : Color.WHITE);
                JMenuItem item = UiActions.menuItem("关闭全部标签", null, () -> fail("Hover must not activate a menu item"));
                JPopupMenu popup = new JPopupMenu(); popup.add(item);
                item.setBackground(theme == 0 ? new Color(0xF7F8FA) : new Color(0x2B2D30));
                item.setSize(240, 36);
                BufferedImage normal = paint(item);
                mouse(item, MouseEvent.MOUSE_ENTERED);
                BufferedImage hovered = paint(item);
                assertFalse("Mouse hover must visibly change the rendered background", Arrays.equals(pixels(normal), pixels(hovered)));
                mouse(item, MouseEvent.MOUSE_EXITED);
                MenuSelectionManager.defaultManager().clearSelectedPath();
                assertTrue("Leaving the item must restore its original appearance", Arrays.equals(pixels(normal), pixels(paint(item))));
                item.setArmed(true);
                assertEquals("Keyboard selection must highlight too", hovered.getRGB(220, 18), paint(item).getRGB(220, 18));
                item.setArmed(false);
                item.setEnabled(false);
                BufferedImage disabled = paint(item);
                mouse(item, MouseEvent.MOUSE_ENTERED);
                assertTrue("Disabled items must not highlight", Arrays.equals(pixels(disabled), pixels(paint(item))));
                item.doClick();
                output.drawImage(normal, 0, theme * 90, null);
                output.drawImage(hovered, 240, theme * 90, null);
                output.drawImage(disabled, 480, theme * 90, null);
                MenuSelectionManager.defaultManager().clearSelectedPath();
            }
            Path file = Path.of("build/reports/menu-hover-preview.png");
            Files.createDirectories(file.getParent());
            ImageIO.write(preview, "png", file.toFile());
        } finally {
            output.dispose();
            MenuSelectionManager.defaultManager().clearSelectedPath();
            UIManager.put("MenuItem.foreground", foreground);
            JBColor.setDark(wasDark);
        }
    }

    private static void mouse(JMenuItem item, int event) {
        item.dispatchEvent(new MouseEvent(item, event, System.currentTimeMillis(), 0, 12, 12, 0, false));
    }

    private static BufferedImage paint(JMenuItem item) {
        BufferedImage image = new BufferedImage(item.getWidth(), item.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try { item.paint(graphics); } finally { graphics.dispose(); }
        return image;
    }

    private static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }
}
