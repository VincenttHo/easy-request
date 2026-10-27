package com.easyrequest.ui;

import com.easyrequest.compat.JavaCompat;

import com.intellij.lang.Language;
import com.intellij.lexer.Lexer;
import com.intellij.lexer.LexerBase;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.TextAttributesKey;
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import java.util.Map;

/** A lightweight JSON highlighter that also works without the IDE's optional JSON plugin. */
final class JsonResponseHighlighter extends SyntaxHighlighterBase {
    static final IElementType KEY = token("KEY"), STRING = token("STRING"), NUMBER = token("NUMBER"),
            BOOLEAN = token("BOOLEAN"), NULL = token("NULL"), OPEN = token("OPEN"), CLOSE = token("CLOSE"),
            PUNCTUATION = token("PUNCTUATION");
    private static final Map<IElementType, TextAttributesKey> COLORS = new java.util.HashMap<>();
    static {
        COLORS.put(KEY, DefaultLanguageHighlighterColors.INSTANCE_FIELD);
        COLORS.put(STRING, DefaultLanguageHighlighterColors.STRING);
        COLORS.put(NUMBER, DefaultLanguageHighlighterColors.NUMBER);
        COLORS.put(BOOLEAN, DefaultLanguageHighlighterColors.KEYWORD);
        COLORS.put(NULL, DefaultLanguageHighlighterColors.LINE_COMMENT);
        COLORS.put(OPEN, DefaultLanguageHighlighterColors.BRACES);
        COLORS.put(CLOSE, DefaultLanguageHighlighterColors.BRACES);
        COLORS.put(PUNCTUATION, DefaultLanguageHighlighterColors.COMMA);
    }

    private static IElementType token(String name) { return new IElementType("RESPONSE_JSON_" + name, Language.ANY); }
    @Override public Lexer getHighlightingLexer() { return new JsonLexer(); }
    @Override public TextAttributesKey[] getTokenHighlights(IElementType type) { return pack(COLORS.get(type)); }

    static final class JsonLexer extends LexerBase {
        private CharSequence buffer = "";
        private int start, end, limit;
        private IElementType type;

        @Override public void start(CharSequence buffer, int startOffset, int endOffset, int initialState) {
            this.buffer = buffer; limit = endOffset; end = startOffset; advance();
        }
        @Override public int getState() { return 0; }
        @Override public IElementType getTokenType() { return type; }
        @Override public int getTokenStart() { return start; }
        @Override public int getTokenEnd() { return end; }
        @Override public CharSequence getBufferSequence() { return buffer; }
        @Override public int getBufferEnd() { return limit; }

        @Override public void advance() {
            start = end;
            if (start >= limit) { type = null; return; }
            char c = buffer.charAt(end++);
            if (Character.isWhitespace(c)) {
                while (end < limit && Character.isWhitespace(buffer.charAt(end))) end++;
                type = TokenType.WHITE_SPACE;
            } else if (c == '"') {
                while (end < limit) {
                    char next = buffer.charAt(end++);
                    if (next == '\\' && end < limit) end++;
                    else if (next == '"') break;
                }
                int after = end;
                while (after < limit && Character.isWhitespace(buffer.charAt(after))) after++;
                type = after < limit && buffer.charAt(after) == ':' ? KEY : STRING;
            } else if (c == '{' || c == '[') type = OPEN;
            else if (c == '}' || c == ']') type = CLOSE;
            else if (c == ':' || c == ',') type = PUNCTUATION;
            else if (c == '-' || c >= '0' && c <= '9') {
                while (end < limit && "0123456789.eE+-".indexOf(buffer.charAt(end)) >= 0) end++;
                type = NUMBER;
            } else if (Character.isLetter(c)) {
                while (end < limit && Character.isLetter(buffer.charAt(end))) end++;
                String literal = buffer.subSequence(start, end).toString();
                type = ("true".equals(literal) || "false".equals(literal)) ? BOOLEAN : "null".equals(literal) ? NULL : TokenType.BAD_CHARACTER;
            } else type = TokenType.BAD_CHARACTER;
        }
    }
}
