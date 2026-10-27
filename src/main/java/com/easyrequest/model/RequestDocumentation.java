package com.easyrequest.model;

import java.util.LinkedHashMap;
import java.util.List;

/** Documentation can be refreshed independently of user-maintained request values. */
public final class RequestDocumentation {
    private RequestDocumentation() {}

    public static String childPath(String parent, String key) {
        return parent + "/" + key.replace("~", "~0").replace("/", "~1").replace("*", "~2");
    }

    public static void refresh(RequestData source, RequestData target) {
        refreshRows(source.query, target.query, false);
        refreshRows(source.pathVariables, target.pathVariables, false);
        refreshRows(source.headers, target.headers, true);
        refreshRows(source.form, target.form, false);
        target.bodyDescriptions = new LinkedHashMap<>(source.bodyDescriptions);
    }

    public static void refreshRows(List<KeyValue> source, List<KeyValue> target, boolean ignoreCase) {
        for (KeyValue row : target) {
            row.description = source.stream().filter(candidate -> ignoreCase ? candidate.key.equalsIgnoreCase(row.key) : candidate.key.equals(row.key))
                    .map(candidate -> candidate.description).findFirst().orElse("");
        }
    }
}
