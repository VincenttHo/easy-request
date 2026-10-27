package com.easyrequest.model;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RequestData {
    public String id = "";
    public String name = "未命名请求";
    public String folderId = "";
    /** Favorites refer to this record's id; folderId only organizes that reference. */
    public boolean favorite;
    public boolean catalogManaged;
    public boolean sourceMissing;
    /** Legacy Save As variants are kept as independent historical requests. */
    public boolean legacyVariant;
    public CatalogLocation catalog = new CatalogLocation();
    public String method = "GET";
    public String url = "";
    public String prefixId = "";
    public String sourceModule = "";
    public String sourceFileUrl = "";
    public String sourceMethod = "";
    /** Original Controller identity, independent of title, parameters and environment prefix. */
    public String sourceProjectUrl = "";
    public String sourceHttpMethod = "";
    public String sourcePath = "";
    public List<KeyValue> query = new ArrayList<>();
    public List<KeyValue> pathVariables = new ArrayList<>();
    public List<KeyValue> headers = new ArrayList<>();
    public boolean useGlobalHeaders = true;
    /** NONE, JSON, TEXT, XML, URLENCODED, MULTIPART */
    public String bodyType = "NONE";
    public String body = "";
    /** Display-only JSON paths, with * for collection elements. */
    public Map<String, String> bodyDescriptions = new LinkedHashMap<>();
    public List<KeyValue> form = new ArrayList<>();

    public RequestData copy() {
        RequestData r = new RequestData();
        r.id = id; r.name = name; r.folderId = folderId;
        r.favorite = favorite; r.catalogManaged = catalogManaged; r.sourceMissing = sourceMissing;
        r.legacyVariant = legacyVariant; r.catalog = catalog.copy();
        r.method = method; r.url = url; r.prefixId = prefixId;
        r.sourceModule = sourceModule; r.sourceFileUrl = sourceFileUrl; r.sourceMethod = sourceMethod;
        r.sourceProjectUrl = sourceProjectUrl; r.sourceHttpMethod = sourceHttpMethod; r.sourcePath = sourcePath;
        r.query = copyRows(query); r.pathVariables = copyRows(pathVariables);
        r.headers = copyRows(headers); r.form = copyRows(form);
        r.useGlobalHeaders = useGlobalHeaders; r.bodyType = bodyType; r.body = body;
        r.bodyDescriptions = new LinkedHashMap<>(bodyDescriptions);
        return r;
    }

    public static List<KeyValue> copyRows(List<KeyValue> rows) {
        return rows.stream().map(KeyValue::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }
}
