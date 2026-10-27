package com.easyrequest.ui;

import org.junit.Test;
import java.util.Map;
import java.util.List;
import static org.junit.Assert.*;

public class JsonFieldHintsTest {
    @Test public void locatesEditedNestedFieldsAndAllArrayItems() {
        String body = """
                {
                  "name": "a\\\"b",
                  "items": [
                    {"count": 4},
                    {"count": 9}
                  ],
                  "other": {"count": 3},
                  "lookup": {"new-key": {"id": 1}}
                }
                """;
        var hints = JsonFieldHints.collect(body, Map.of("/name", "名称", "/items", "明细", "/items/*/count", "数量", "/lookup/*/id", "标识"));
        assertEquals(List.of("名称", "明细", "数量", "数量", "标识"), hints.stream().map(JsonFieldHints.Hint::description).toList());
        assertEquals(body.indexOf("\"name\""), hints.getFirst().offset());
        assertEquals(body.indexOf("\"count\""), hints.get(2).offset());
        var moved = JsonFieldHints.collect("{\"items\":[{\"count\":8}],\"name\":\"b\"}", Map.of("/name", "名称", "/items/*/count", "数量"));
        assertEquals(List.of("数量", "名称"), moved.stream().map(JsonFieldHints.Hint::description).toList());
    }
    @Test public void distinguishesEscapedKeysAndRootArrays() {
        var hints = JsonFieldHints.collect("[{\"a/b~c*\":1},{\"a/b~c*\":2}]", Map.of("/*/a~1b~0c~2", "特殊键"));
        assertEquals(2, hints.size()); assertEquals("特殊键", hints.getFirst().description());
        assertEquals(1, JsonFieldHints.collect("{\"\\u0061\":1}", Map.of("/a", "Unicode key")).size());
    }
    @Test public void hidesHintsDuringIncompleteEditsAndIgnoresUnknownFields() {
        for (String body : List.of("{\"name\":", "{\"name\":\"half", "{\"name\":2,}", "{\"name\":2} trailing", "{\"name\":\"\\x\"}", "{\"name\":bare}", "{\"name\":01}"))
            assertTrue(body, JsonFieldHints.collect(body, Map.of("/name", "名称")).isEmpty());
        assertTrue(JsonFieldHints.collect("{\"renamed\":1}", Map.of("/name", "名称")).isEmpty());
        assertTrue(JsonFieldHints.collect("[".repeat(100) + "0" + "]".repeat(100), Map.of("", "root")).isEmpty());
    }
}
