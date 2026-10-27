package com.easyrequest.ui;

import com.easyrequest.compat.JavaCompat;

import com.easyrequest.model.RequestDocumentation;
import com.google.gson.JsonParser;
import java.util.*;

/** Locates documented fields in the actual edited JSON, independent of formatting and array indices. */
final class JsonFieldHints {
    static final class Hint {
        private final int offset;
        private final String label;
        private final String description;
        Hint(int offset, String label, String description) {
            this.offset = offset;
            this.label = label;
            this.description = description;
        }
        public int offset() { return offset; }
        public String label() { return label; }
        public String description() { return description; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Hint)) return false;
            Hint that = (Hint) other;
            return java.util.Objects.equals(offset, that.offset) && java.util.Objects.equals(label, that.label) && java.util.Objects.equals(description, that.description);
        }
        @Override public int hashCode() { return java.util.Objects.hash(offset, label, description); }
        @Override public String toString() { return "Hint[" + "offset=" + offset + ", " + "label=" + label + ", " + "description=" + description + "]"; }
    }
    private final String text;
    private final Map<String, String> descriptions;
    private final List<Hint> hints = new ArrayList<>();
    private int position;

    private JsonFieldHints(String text, Map<String, String> descriptions) {
        this.text = text; this.descriptions = descriptions;
    }

    static List<Hint> collect(String text, Map<String, String> descriptions) {
        if (descriptions.isEmpty() || JavaCompat.isBlank(text) || text.length() > 200_000) return JavaCompat.list();
        JsonFieldHints parser = new JsonFieldHints(text, descriptions);
        try {
            parser.space();
            parser.hint("", "Body", parser.position);
            parser.value("", 0);
            parser.space();
            return parser.position == text.length() ? parser.hints : JavaCompat.list();
        } catch (IllegalArgumentException ignored) {
            // During incomplete edits, hide hints instead of showing them against the wrong field.
            return JavaCompat.list();
        }
    }

    private void hint(String path, String label, int offset) {
        String description = descriptions.get(path);
        if (description == null) {
            String[] parts = path.split("/", -1);
            for (java.util.Map.Entry<java.lang.String,java.lang.String> candidate : descriptions.entrySet()) {
                if (!candidate.getKey().contains("*")) continue;
                String[] pattern = candidate.getKey().split("/", -1);
                if (pattern.length != parts.length) continue;
                boolean matches = true;
                for (int i = 0; i < parts.length; i++) if (!pattern[i].equals("*") && !pattern[i].equals(parts[i])) { matches = false; break; }
                if (matches) { description = candidate.getValue(); break; }
            }
        }
        if (description != null && !JavaCompat.isBlank(description)) hints.add(new Hint(offset, label, JavaCompat.strip(description.replaceAll("\\s+", " "))));
    }

    private void value(String path, int depth) {
        if (depth > 64) throw new IllegalArgumentException();
        space();
        if (take('{')) {
            space(); if (take('}')) return;
            do {
                space(); int offset = position; String key = string();
                space(); require(':'); space();
                String child = RequestDocumentation.childPath(path, key);
                hint(child, key, offset); value(child, depth + 1); space();
                if (take('}')) return;
                require(',');
            } while (true);
        }
        if (take('[')) {
            space(); if (take(']')) return;
            do {
                value(path + "/*", depth + 1); space();
                if (take(']')) return;
                require(',');
            } while (true);
        }
        if (position < text.length() && text.charAt(position) == '"') { string(); return; }
        int start = position;
        while (position < text.length() && !Character.isWhitespace(text.charAt(position)) && ",]}".indexOf(text.charAt(position)) < 0) position++;
        String token = text.substring(start, position);
        if (!JavaCompat.set("true", "false", "null").contains(token) && !token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw new IllegalArgumentException();
    }

    private String string() {
        int start = position; require('"');
        while (position < text.length()) {
            char next = text.charAt(position++);
            if (next == '\\') {
                if (position >= text.length()) break;
                char escape = text.charAt(position++);
                if (escape == 'u') {
                    for (int i = 0; i < 4; i++) if (position >= text.length() || Character.digit(text.charAt(position++), 16) < 0) throw new IllegalArgumentException();
                } else if ("\"\\/bfnrt".indexOf(escape) < 0) throw new IllegalArgumentException();
            } else if (next == '"') return JsonParser.parseString(text.substring(start, position)).getAsString();
            else if (next < 0x20) throw new IllegalArgumentException();
        }
        throw new IllegalArgumentException();
    }
    private void space() { while (position < text.length() && Character.isWhitespace(text.charAt(position))) position++; }
    private boolean take(char token) { if (position < text.length() && text.charAt(position) == token) { position++; return true; } return false; }
    private void require(char token) { if (!take(token)) throw new IllegalArgumentException(); }
}
