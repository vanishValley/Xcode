package com.xu.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MdcScopeTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void shouldRestoreNestedValue() {
        MDC.put("task_id", "parent");

        try (MdcScope ignored = MdcScope.put("task_id", "child")) {
            assertEquals("child", MDC.get("task_id"));
        }

        assertEquals("parent", MDC.get("task_id"));
    }

    @Test
    void shouldRemoveValueWhenNoParentExists() {
        try (MdcScope ignored = MdcScope.put("task_id", "task_1")) {
            assertEquals("task_1", MDC.get("task_id"));
        }

        assertNull(MDC.get("task_id"));
    }
}
