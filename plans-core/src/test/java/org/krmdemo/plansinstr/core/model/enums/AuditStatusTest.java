package org.krmdemo.plansinstr.core.model.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditStatusTest {

    @ParameterizedTest(name = "{0}")
    @EnumSource(AuditStatus.class)
    @DisplayName("every status has exactly one role, and the predicates agree with the sets")
    void exactlyOneRole(AuditStatus status) {
        int roles = (status.isTransient() ? 1 : 0) + (status.isSuccess() ? 1 : 0) + (status.isFailed() ? 1 : 0);

        assertEquals(1, roles, status + " has " + roles + " roles");
        assertEquals(status.isSuccess() || status.isFailed(), status.isFinal());
        assertEquals(!status.isFailed(), status.carriesData());
        assertEquals(AuditStatus.TRANSIENT_STATUSES.contains(status), status.isTransient());
        assertEquals(AuditStatus.FINAL_STATUSES.contains(status), status.isFinal());
    }

    @Test
    @DisplayName("the sets match the diagram")
    void setsMatchDiagram() {
        assertEquals(EnumSet.of(AuditStatus.SWEPT_IN, AuditStatus.SWEPT_OUT), AuditStatus.SUCCESS_STATUSES);
        assertEquals(6, AuditStatus.TRANSIENT_STATUSES.size());
        assertEquals(6, AuditStatus.FAILED_STATUSES.size());
        assertTrue(AuditStatus.FAILED_STATUSES.stream()
                .allMatch(s -> s.name().endsWith("_FAILED") || s.name().endsWith("_TIMEOUT")));
        assertFalse(AuditStatus.SCHEDULED.isFinal());
    }

    @Test
    @DisplayName("the sets cannot be changed from outside")
    void setsAreReadOnly() {
        assertThrows(UnsupportedOperationException.class, () -> AuditStatus.SUCCESS_STATUSES.add(AuditStatus.SCHEDULED));
        assertThrows(UnsupportedOperationException.class, () -> AuditStatus.FINAL_STATUSES.clear());
    }
}
