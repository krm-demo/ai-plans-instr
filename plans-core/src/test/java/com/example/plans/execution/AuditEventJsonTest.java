package com.example.plans.execution;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeName;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.example.plans.execution.TestPlans.amt;
import static com.example.plans.execution.TestPlans.at;
import static com.example.plans.execution.TestPlans.scheduled;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditEventJsonTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    @DisplayName("PlanExecution with every event type survives a JSON round trip")
    void planExecutionRoundTrip() {
        PlanExecution out = scheduled().applyAll(List.of(
                DdaBalanceEvent.retry(at(1), "timeout"),
                DdaBalanceEvent.retrieved(at(2), amt("730.25")),
                InvPositionsEvent.retrieved(at(3), amt("10000.00")),
                SweepOrderEvent.completed(at(4), "ord-1", amt("230.25"))));

        String text = json.writeValueAsString(out);
        PlanExecution back = json.readValue(text, PlanExecution.class);

        assertEquals(out, back);
        assertEquals(back, back.replay());
    }

    @Test
    @DisplayName("the type name is written exactly once per event")
    void typeNameWrittenOnce() {
        String text = json.writeValueAsString(DdaBalanceEvent.retrieved(at(1), amt("730.25")));

        assertTrue(text.startsWith("{\"__eventType__\":\"DDA_BALANCE\""), text);
        assertEquals(1, occurrences(text, "__eventType__"), text);
        assertTrue(text.contains("730.25"), "BigDecimal keeps its scale: " + text);
    }

    @Test
    @DisplayName("an unknown event type is read as UnknownAuditEvent and written back unchanged")
    void unknownEventIsLossless() {
        String newer = """
                {"__eventType__":"FX_RATE","at":"2026-11-15T14:00:05Z","rate":1.0825,
                 "note":null,"legs":[{"ccy":"EUR"},{"ccy":"USD"}]}""";
        String withUnknown = """
                {"auditEvents":[
                  {"__eventType__":"DDA_BALANCE","at":"2026-11-15T14:00:01Z",
                   "status":"DDA_BALANCE_RETRIEVED","ddaBalance":730.25},
                  %s
                ]}""".formatted(newer);

        // read just the event list, as a newer build would have written it
        Map<String, List<AuditEvent>> read = json.readValue(withUnknown, new TypeReference<>() { });
        List<AuditEvent> events = read.get("auditEvents");
        assertInstanceOf(DdaBalanceEvent.class, events.get(0));
        UnknownAuditEvent unknown = assertInstanceOf(UnknownAuditEvent.class, events.get(1));
        assertEquals("FX_RATE", unknown.eventType());

        String written = json.writeValueAsString(unknown);
        assertEquals(json.readTree(newer), json.readTree(written));
        assertEquals(1, occurrences(written, "__eventType__"), written);
    }

    @Test
    @DisplayName("types are kept across maps and records; unknown ones fall back")
    void mapsAndRecords() {
        record Report(AuditEvent primary, DdaBalanceEvent balance, Map<String, AuditEvent> byName) { }

        Report report = new Report(
                InvPositionsEvent.retrieved(at(1), amt("10.00")),
                DdaBalanceEvent.retrieved(at(2), amt("20.00")),
                Map.of("a", SweepOrderEvent.completed(at(3), "o", amt("5.00")),
                        "b", new UnknownAuditEvent(Map.of("__eventType__", "NEW_ONE", "x", 1))));

        Report back = json.readValue(json.writeValueAsString(report), Report.class);

        assertEquals(report, back);
        assertInstanceOf(InvPositionsEvent.class, back.primary());
        assertInstanceOf(UnknownAuditEvent.class, back.byName().get("b"));
    }

    @Test
    @DisplayName("every permitted event type has a unique @JsonTypeName and is registered")
    void sealedAndJsonSubTypesAgree() {
        Set<Class<?>> registered = Arrays.stream(AuditEvent.class.getAnnotation(JsonSubTypes.class).value())
                .map(JsonSubTypes.Type::value)
                .collect(Collectors.toSet());
        Set<String> names = new HashSet<>();
        for (Class<?> type : AuditEvent.class.getPermittedSubclasses()) {
            if (type == UnknownAuditEvent.class) {
                continue;
            }
            JsonTypeName name = type.getAnnotation(JsonTypeName.class);
            assertNotNull(name, type + " needs @JsonTypeName");
            assertTrue(names.add(name.value()), "duplicate type name " + name.value());
            assertTrue(registered.contains(type), type + " missing from @JsonSubTypes");
        }
        assertEquals(registered.size(), names.size());
    }

    private static int occurrences(String text, String token) {
        return text.split(token, -1).length - 1;
    }
}
