package org.krmdemo.plansinstr.exec;

import org.krmdemo.plansinstr.core.model.PlanExecution;
import org.krmdemo.plansinstr.core.model.events.AuditEvent;
import org.krmdemo.plansinstr.core.model.events.UnknownAuditEvent;
import org.krmdemo.plansinstr.core.model.enums.AuditStatus;
import org.krmdemo.plansinstr.exec.model.events.BalanceEvent;
import org.krmdemo.plansinstr.exec.model.events.PropOneEvent;
import org.krmdemo.plansinstr.exec.model.events.PropTwoEvent;
import org.krmdemo.plansinstr.exec.model.events.SweepEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

import static org.krmdemo.plansinstr.exec.TestPlans.at;
import static org.krmdemo.plansinstr.exec.TestPlans.scheduled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditEventJsonTest {

    /** plans-exec defines the events and registers them; plans-core does not know them. */
    private final JsonMapper json = JsonMapper.builder()
            .registerSubtypes(PropOneEvent.class, BalanceEvent.class, PropTwoEvent.class, SweepEvent.class)
            .build();

    @Test
    @DisplayName("PlanExecution with its events survives a JSON round trip")
    void planExecutionRoundTrip() {
        PlanExecution checked = PropOneEvent.passed(at(1), "730.25").applyTo(scheduled());
        PlanExecution out = checked.applyAll(List.of(
                BalanceEvent.of(at(2), checked),
                PropTwoEvent.passed(at(3), AuditStatus.HIGH_BALANCE, "400"),
                SweepEvent.swept(at(4), AuditStatus.CHECK_HIGH_PASSED)));

        PlanExecution back = json.readValue(json.writeValueAsString(out), PlanExecution.class);

        assertEquals(out, back);
        assertEquals(back, back.replay());
        assertEquals("400.00", back.derivedPropTwo().toPlainString());
        assertEquals(AuditStatus.SWEPT_OUT, back.auditStatus());
    }

    @Test
    @DisplayName("the type name is written exactly once per event, first")
    void typeNameWrittenOnce() {
        String text = json.writeValueAsString(PropOneEvent.passed(at(1), "730.25"));

        assertTrue(text.startsWith("{\"__eventType__\":\"PROP_ONE\""), text);
        assertEquals(1, occurrences(text, "__eventType__"), text);
        assertTrue(text.contains("730.25"), "BigDecimal keeps its scale: " + text);
    }

    @Test
    @DisplayName("an unregistered event type is read as UnknownAuditEvent and written back unchanged")
    void unknownEventIsLossless() {
        String newer = """
                {"__eventType__":"FX_RATE","at":"2026-11-15T14:00:05Z","rate":1.0825,
                 "note":null,"legs":[{"ccy":"EUR"},{"ccy":"USD"}]}""";
        String withUnknown = """
                {"auditEvents":[
                  {"__eventType__":"PROP_ONE","at":"2026-11-15T14:00:01Z",
                   "status":"CHECK_ONE_PASSED","value":730.25},
                  %s
                ]}""".formatted(newer);

        Map<String, List<AuditEvent>> read = json.readValue(withUnknown, new TypeReference<>() { });
        List<AuditEvent> events = read.get("auditEvents");
        assertInstanceOf(PropOneEvent.class, events.get(0));
        UnknownAuditEvent unknown = assertInstanceOf(UnknownAuditEvent.class, events.get(1));
        assertEquals("FX_RATE", unknown.eventType());

        String written = json.writeValueAsString(unknown);
        assertEquals(json.readTree(newer), json.readTree(written));
        assertEquals(1, occurrences(written, "__eventType__"), written);
    }

    @Test
    @DisplayName("a mapper without the event library reads its events as unknown, without loss")
    void mapperWithoutEventLibrary() {
        PlanExecution out = PropOneEvent.passed(at(1), "730.25").applyTo(scheduled());
        String stored = json.writeValueAsString(out);

        JsonMapper plain = JsonMapper.builder().build();
        PlanExecution seen = plain.readValue(stored, PlanExecution.class);

        assertTrue(seen.hasUnknownEvents());
        assertEquals(out.derivedPropOne(), seen.derivedPropOne());
        assertEquals(json.readTree(stored), json.readTree(plain.writeValueAsString(seen)));
    }

    private static int occurrences(String text, String token) {
        return text.split(token, -1).length - 1;
    }
}
