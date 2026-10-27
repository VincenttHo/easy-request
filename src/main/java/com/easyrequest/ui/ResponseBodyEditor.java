package com.easyrequest.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.editor.ex.util.LexerEditorHighlighter;
import com.intellij.openapi.fileTypes.PlainSyntaxHighlighter;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.project.Project;
import com.intellij.ui.EditorTextField;
import java.util.ArrayDeque;

/** Read-only response text with native selection, syntax colors and independent nested folds. */
final class ResponseBodyEditor extends EditorTextField {
    private boolean json;

    ResponseBodyEditor(Project project, Disposable parent) {
        super(EditorFactory.getInstance().createDocument(""), project, PlainTextFileType.INSTANCE, true, false);
        setName("response-pretty");
        com.easyrequest.compat.IdeCompat.disposeEditorWith(this, parent);
        setFontInheritedFromLAF(false);
    }

    void showResponse(String text, boolean json) {
        this.json = json;
        setText(text);
        if (getEditor() instanceof EditorEx && !((EditorEx) getEditor()).isDisposed()) {
            refreshPresentation(((EditorEx) getEditor()));
            ((EditorEx) getEditor()).getCaretModel().moveToOffset(0);
            ((EditorEx) getEditor()).getScrollingModel().scrollToCaret(ScrollType.MAKE_VISIBLE);
        }
    }

    void setAllExpanded(boolean expanded) {
        EditorEx editor = (EditorEx) getEditor();
        if (editor == null || editor.isDisposed()) return;
        com.intellij.openapi.editor.ex.FoldingModelEx folding = editor.getFoldingModel();
        folding.runBatchFoldingOperation(() -> {
            for (com.intellij.openapi.editor.FoldRegion region : folding.getAllFoldRegions()) region.setExpanded(expanded);
        });
    }

    @Override protected EditorEx createEditor() {
        EditorEx editor = super.createEditor();
        editor.setVerticalScrollbarVisible(true); editor.setHorizontalScrollbarVisible(true);
        com.intellij.openapi.editor.EditorSettings settings = editor.getSettings();
        settings.setLineNumbersShown(true); settings.setLineMarkerAreaShown(false); settings.setGutterIconsShown(false);
        settings.setAdditionalLinesCount(1); settings.setAdditionalColumnsCount(3);
        settings.setAdditionalPageAtBottom(false); settings.setVirtualSpace(false); settings.setUseSoftWraps(false);
        refreshPresentation(editor);
        return editor;
    }

    private void refreshPresentation(EditorEx editor) {
        editor.setHighlighter(new LexerEditorHighlighter(json ? new JsonResponseHighlighter() : new PlainSyntaxHighlighter(), editor.getColorsScheme()));
        editor.getSettings().setFoldingOutlineShown(json);
        com.intellij.openapi.editor.ex.FoldingModelEx folding = editor.getFoldingModel();
        folding.setFoldingEnabled(true);
        folding.runBatchFoldingOperation(() -> {
            folding.clearFoldRegions();
            if (!json) return;
            com.intellij.openapi.editor.ex.DocumentEx document = editor.getDocument();
            java.lang.CharSequence text = document.getCharsSequence();
            java.util.ArrayDeque<java.lang.Integer> starts = new ArrayDeque<Integer>();
            com.easyrequest.ui.JsonResponseHighlighter.JsonLexer lexer = new JsonResponseHighlighter.JsonLexer();
            lexer.start(text);
            while (lexer.getTokenType() != null) {
                if (lexer.getTokenType() == JsonResponseHighlighter.OPEN) starts.push(lexer.getTokenStart());
                else if (lexer.getTokenType() == JsonResponseHighlighter.CLOSE && !starts.isEmpty()) {
                    int start = starts.pop(), end = lexer.getTokenEnd();
                    if (document.getLineNumber(start) < document.getLineNumber(end - 1))
                        folding.addFoldRegion(start, end, text.charAt(start) == '{' ? "{ … }" : "[ … ]");
                }
                lexer.advance();
            }
        });
    }
}
