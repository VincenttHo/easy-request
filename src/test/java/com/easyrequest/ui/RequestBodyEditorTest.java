package com.easyrequest.ui;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class RequestBodyEditorTest extends LightJavaCodeInsightFixtureTestCase {
    public void testInlaysStayOutsideEditableDocumentAndFollowEditedFields() {
        AtomicInteger edits = new AtomicInteger();
        var parent = Disposer.newDisposable();
        try {
            RequestBodyEditor body = new RequestBodyEditor(getProject(), parent, edits::incrementAndGet);
            body.setBodyType("JSON");
            String json = "{\n  \"name\": \"saved\",\n  \"items\": [{\"id\": 7}]\n}";
            body.setText(json);
            Editor editor = body.getEditor(true);
            assertNotNull(editor);
            int before = edits.get();
            body.setDescriptions(Map.of("/name", "用户名称", "/items/*/id", "标识"));
            assertEquals(before, edits.get()); assertEquals(json, body.getText());
            var inlays = editor.getInlayModel().getAfterLineEndElementsInRange(0, json.length(), RequestBodyEditor.Comment.class);
            assertEquals(2, inlays.size());
            assertEquals("  // 用户名称", inlays.getFirst().getRenderer().text);
            assertEquals(1, editor.getDocument().getLineNumber(inlays.getFirst().getOffset()));
            capture(body, "parameter-comments-light.png");
            var dark = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().getScheme("Darcula");
            if (dark != null) {
                body.getEditor(true).setColorsScheme(dark); body.refreshHints();
                capture(body, "parameter-comments-dark.png");
            }
            editor.getSelectionModel().setSelection(0, json.length());
            assertEquals(json, editor.getSelectionModel().getSelectedText());
            // Native document edits (typing, paste, undo) use the same listener as setText.
            WriteCommandAction.runWriteCommandAction(getProject(), () -> editor.getDocument().insertString(0, "\n"));
            assertTrue(edits.get() > before);
            body.refreshHints();
            inlays = editor.getInlayModel().getAfterLineEndElementsInRange(0, editor.getDocument().getTextLength(), RequestBodyEditor.Comment.class);
            assertEquals(2, editor.getDocument().getLineNumber(inlays.getFirst().getOffset()));
            body.setText("{\"items\":[{\"id\":3},{\"id\":4}],\"renamed\":\"value\"}"); body.refreshHints();
            inlays = editor.getInlayModel().getAfterLineEndElementsInRange(0, editor.getDocument().getTextLength(), RequestBodyEditor.Comment.class);
            assertEquals(1, inlays.size());
            assertEquals("  // id：标识；id：标识", inlays.getFirst().getRenderer().text);
            body.setBodyType("TEXT");
            assertTrue(body.getEditor(true).getInlayModel().getAfterLineEndElementsInRange(0, body.getText().length()).isEmpty());
            body.setBodyType("JSON"); body.setText("{\"name\":"); body.refreshHints();
            assertTrue(body.getEditor(true).getInlayModel().getAfterLineEndElementsInRange(0, body.getText().length()).isEmpty());
        } finally {
            Disposer.dispose(parent);
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents();
        }
    }

    private static void capture(RequestBodyEditor body, String name) {
        body.setSize(840, 240); layout(body);
        var screenshot = new java.awt.image.BufferedImage(840, 240, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = screenshot.createGraphics();
        body.printAll(graphics); graphics.dispose();
        try {
            var file = java.nio.file.Path.of("build/reports/ui", name);
            java.nio.file.Files.createDirectories(file.getParent());
            javax.imageio.ImageIO.write(screenshot, "png", file.toFile());
        } catch (java.io.IOException error) { throw new AssertionError(error); }
    }
    private static void layout(java.awt.Container container) {
        container.doLayout();
        for (var child : container.getComponents()) if (child instanceof java.awt.Container nested) layout(nested);
    }
}
