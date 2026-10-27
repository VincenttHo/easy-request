package com.easyrequest.ui;

import com.easyrequest.compat.JavaCompat;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.EditorTextField;
import com.intellij.util.ui.UIUtil;
import java.awt.*;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/** Native editor inlays are outside the document: editing, copying and sending only use real JSON. */
final class RequestBodyEditor extends EditorTextField {
    private Map<String, String> descriptions = JavaCompat.map();
    private boolean json;
    private final javax.swing.Timer hintTimer;

    RequestBodyEditor(Project project, Disposable parent, Runnable changed) {
        super(EditorFactory.getInstance().createDocument(""), project, PlainTextFileType.INSTANCE, false, false);
        setName("request-body");
        com.easyrequest.compat.IdeCompat.disposeEditorWith(this, parent);
        hintTimer = new javax.swing.Timer(150, e -> refreshHints());
        hintTimer.setRepeats(false);
        Disposer.register(parent, () -> hintTimer.stop());
        addDocumentListener(new DocumentListener() {
            @Override public void documentChanged(DocumentEvent event) {
                changed.run();
                // Inlay changes must run after the document update has completed.
                hintTimer.restart();
            }
        });
    }

    void setBodyType(String type) {
        json = "JSON".equals(type);
        com.intellij.openapi.fileTypes.FileType fileType = JavaCompat.set("JSON", "XML").contains(type)
                ? FileTypeManager.getInstance().getFileTypeByExtension(type.toLowerCase(Locale.ROOT)) : PlainTextFileType.INSTANCE;
        if (fileType.isBinary()) fileType = PlainTextFileType.INSTANCE;
        if (!fileType.equals(getFileType())) setFileType(fileType);
        refreshHints();
    }

    void setDescriptions(Map<String, String> descriptions) {
        this.descriptions = new LinkedHashMap<>(descriptions);
        refreshHints();
    }

    @Override protected EditorEx createEditor() {
        EditorEx editor = super.createEditor();
        editor.setVerticalScrollbarVisible(true); editor.setHorizontalScrollbarVisible(true);
        com.intellij.openapi.editor.EditorSettings settings = editor.getSettings();
        settings.setLineNumbersShown(true); settings.setFoldingOutlineShown(false);
        settings.setLineMarkerAreaShown(false); settings.setGutterIconsShown(false);
        settings.setAdditionalLinesCount(1); settings.setAdditionalColumnsCount(3);
        settings.setAdditionalPageAtBottom(false); settings.setVirtualSpace(false); settings.setUseSoftWraps(false);
        refreshHints(editor);
        return editor;
    }

    void refreshHints() {
        hintTimer.stop();
        Editor editor = getEditor();
        if (editor != null && !editor.isDisposed()) refreshHints(editor);
    }

    private void refreshHints(Editor editor) {
        com.intellij.openapi.editor.InlayModel model = editor.getInlayModel();
        com.intellij.openapi.editor.Document document = editor.getDocument();
        Map<Integer, List<JsonFieldHints.Hint>> lines = new LinkedHashMap<>();
        if (json) {
            for (com.easyrequest.ui.JsonFieldHints.Hint hint : JsonFieldHints.collect(document.getText(), descriptions))
                lines.computeIfAbsent(document.getLineNumber(hint.offset()), ignored -> new ArrayList<>()).add(hint);
        } else if (descriptions.containsKey("") && !JavaCompat.isBlank(document.getText())) {
            lines.put(0, JavaCompat.list(new JsonFieldHints.Hint(0, "Body", descriptions.get("").replaceAll("\\s+", " "))));
        }
        {
            for (com.intellij.openapi.editor.Inlay<? extends com.easyrequest.ui.RequestBodyEditor.Comment> inlay : model.getAfterLineEndElementsInRange(0, document.getTextLength(), Comment.class)) Disposer.dispose(inlay);
            lines.forEach((line, hints) -> {
                String label = hints.size() == 1 ? hints.get(0).description()
                        : hints.stream().map(hint -> hint.label() + "：" + hint.description()).collect(Collectors.joining("；"));
                model.addAfterLineEndElement(document.getLineEndOffset(line), true, new Comment("  // " + label));
            });
        }
    }

    static final class Comment implements EditorCustomElementRenderer {
        final String text;
        Comment(String text) { this.text = text; }
        private Font font(Inlay<?> inlay) { Font base = inlay.getEditor().getColorsScheme().getFont(EditorFontType.PLAIN); return base.canDisplayUpTo(text) < 0 ? base : new Font(Font.DIALOG, base.getStyle(), base.getSize()); }
        @Override public int calcWidthInPixels(Inlay inlay) {
            return Math.max(1, inlay.getEditor().getContentComponent().getFontMetrics(font(inlay)).stringWidth(text));
        }
        @Override public void paint(Inlay inlay, Graphics graphics, Rectangle target, TextAttributes attributes) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                com.intellij.openapi.editor.Editor editor = inlay.getEditor();
                com.intellij.openapi.editor.markup.TextAttributes commentStyle = editor.getColorsScheme().getAttributes(DefaultLanguageHighlighterColors.LINE_COMMENT);
                Color color = commentStyle == null ? null : commentStyle.getForegroundColor();
                g.setColor(color == null ? UIUtil.getContextHelpForeground() : color);
                g.setFont(font(inlay));
                g.drawString(text, target.x, target.y + editor.getContentComponent().getFontMetrics(g.getFont()).getAscent());
            } finally { g.dispose(); }
        }
    }
}
