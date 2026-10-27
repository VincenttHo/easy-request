package com.easyrequest.ui;

import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;
import com.intellij.util.ui.UIUtil;
import java.util.Arrays;
import java.util.HashSet;

public class ResponseBodyEditorTest extends LightJavaCodeInsightFixtureTestCase {
    private static final String JSON = """
            {
              "name": "用户详情",
              "message": "escaped quote: \\\" and brackets: { [ ] }",
              "count": -1.25e+3,
              "active": true,
              "optional": null,
              "items": [
                {
                  "id": 7,
                  "tags": []
                }
              ]
            }""";

    public void testColorsAndNestedFoldsLeaveResponseTextIntact() {
        var parent = Disposer.newDisposable();
        try {
            var body = new ResponseBodyEditor(getProject(), parent);
            body.showResponse(JSON, true);
            EditorEx editor = body.getEditor(true);
            assertTrue(editor.isViewer());
            assertTrue(editor.getSettings().isFoldingOutlineShown());
            // Brackets inside escaped strings and empty containers must not create spurious folds.
            var regions = editor.getFoldingModel().getAllFoldRegions();
            assertEquals(3, regions.length);
            assertTrue(Arrays.stream(regions).allMatch(region -> region.isExpanded()));
            for (String schemeName : new String[]{"Default", "Darcula"}) {
                var scheme = EditorColorsManager.getInstance().getScheme(schemeName);
                assertNotNull(schemeName, scheme);
                editor.setColorsScheme(scheme);
                var colors = new HashSet<java.awt.Color>();
                for (String token : new String[]{"\"name\"", "\"用户详情\"", "-1.25e+3", "true", "null"}) {
                    var attributes = editor.getHighlighter().createIterator(JSON.indexOf(token)).getTextAttributes();
                    assertNotNull(attributes.getForegroundColor());
                    colors.add(attributes.getForegroundColor());
                }
                assertEquals("Distinct JSON colors in " + schemeName, 5, colors.size());
                capture(body, "json-response-" + schemeName.toLowerCase() + ".png");
            }
            int items = JSON.indexOf('[' , JSON.indexOf("\"items\""));
            var array = Arrays.stream(regions).filter(region -> region.getStartOffset() == items).findFirst().orElseThrow();
            editor.getFoldingModel().runBatchFoldingOperation(() -> array.setExpanded(false));
            assertFalse(array.isExpanded());
            assertEquals(2, Arrays.stream(regions).filter(region -> region.isExpanded()).count());
            body.setAllExpanded(false);
            assertTrue(Arrays.stream(regions).noneMatch(region -> region.isExpanded()));
            capture(body, "json-response-collapsed.png");
            editor.getSelectionModel().setSelection(0, JSON.length());
            assertEquals(JSON, editor.getSelectionModel().getSelectedText());
            body.setAllExpanded(true);
            assertTrue(Arrays.stream(regions).allMatch(region -> region.isExpanded()));
            assertEquals(JSON, body.getText());

            body.showResponse("<html>Service unavailable</html>", false);
            assertEquals(0, editor.getFoldingModel().getAllFoldRegions().length);
            assertFalse(editor.getSettings().isFoldingOutlineShown());
            assertEquals("<html>Service unavailable</html>", body.getText());
            body.showResponse("[\n  false,\n  null\n]", true);
            assertEquals(1, editor.getFoldingModel().getAllFoldRegions().length);
            body.showResponse("", false);
            assertEquals(0, editor.getFoldingModel().getAllFoldRegions().length);
        } finally {
            Disposer.dispose(parent);
            UIUtil.dispatchAllInvocationEvents();
        }
    }

    private static void capture(ResponseBodyEditor body, String name) {
        body.setSize(760, 350); layout(body);
        var screenshot = new java.awt.image.BufferedImage(760, 350, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = screenshot.createGraphics(); body.printAll(graphics); graphics.dispose();
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
