package com.easyrequest.model;

import java.util.UUID;

/** A disabled row is retained in the editor but never sent. */
public final class KeyValue {
    public String id = UUID.randomUUID().toString();
    public boolean enabled = true;
    public String key = "";
    public String value = "";
    public boolean file;
    public String contentType = "";
    /** Display-only documentation; never part of the HTTP request. */
    public String description = "";

    public KeyValue() {}
    public KeyValue(String key, String value) { this.key = key; this.value = value; }
    public KeyValue copy() {
        KeyValue result = new KeyValue(key, value);
        result.id = id;
        result.enabled = enabled;
        result.file = file;
        result.contentType = contentType;
        result.description = description;
        return result;
    }
}
