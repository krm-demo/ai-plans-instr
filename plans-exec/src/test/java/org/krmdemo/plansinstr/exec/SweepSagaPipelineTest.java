package org.krmdemo.plansinstr.exec;

import org.krmdemo.plansinstr.core.model.PlanExecution;
import org.krmdemo.plansinstr.core.model.PlanInstructions;
import org.krmdemo.plansinstr.core.model.enums.AuditStatus;
import org.krmdemo.plansinstr.core.model.enums.PlanStatus;
import org.krmdemo.plansinstr.core.model.events.UnknownAuditEvent;
import org.krmdemo.plansinstr.exec.model.events.PropOneEvent;
import org.krmdemo.plansinstr.exec.model.events.PropTwoEvent;
import org.krmdemo.plansinstr.exec.model.events.SweepEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.krmdemo.plansinstr.exec.TestData.T0;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SweepSagaPipelineTest {

    private final Scheduler scheduler = Schedulers.newBoundedElastic(8, 100, "test", 10, true);
    private final TestData.MutableClock clock = new TestData.MutableClock(T0);
    private final InMemoryPlanExecutionStore store = new InMemoryPlanExecutionStore(clock);
    private final SweepSagaProperties properties =
            new SweepSagaProperties(10, Duration.ofMillis(20), Duration.ofMinutes(1), 4, Duration.ofMillis(50));

    /** The plan instructions "as they are now" in plan_instructions; tests change them. */
    private final AtomicReference<PlanInstructions> currentPlan = new AtomicReference<>(TestData.planInstr("500.00"));

    private final TestRetriever<PropOneEvent> propOne = new TestRetriever<>("propOne", Duration.ZERO,
            in -> PropOneEvent.passed(T0, "730.25"), (in, e) -> PropOneEvent.failed(T0, e));
    private final BalanceRetriever balance = new BalanceRetriever(clock);
    private final TestRetriever<PropTwoEvent> propTwo = new TestRetriever<>("propTwo", Duration.ZERO,
            in -> PropTwoEvent.passed(T0, in.auditStatus(), in.derivedPropOne().toPlainString()),
            (in, e) -> PropTwoEvent.failed(T0, in.auditStatus(), e));
    private final TestRetriever<SweepEvent> sweep = new TestRetriever<>("sweep", Duration.ZERO,
            in -> SweepEvent.swept(T0, in.auditStatus()), (in, e) -> SweepEvent.failed(T0, in.auditStatus(), e));

    @AfterEach
    void tearDown() {
        scheduler.dispose();
    }

    private SweepSagaPipeline pipeline(List<SweepSagaStage> stages, Duration nextRunIn) {
        SweepSagaPostgresPoller poller = new SweepSagaPostgresPoller(store, properties, scheduler);
        return new SweepSagaPipeline(poller, stages, store,
                planId -> Optional.ofNullable(currentPlan.get()),
                finished -> Optional.ofNullable(nextRunIn).map(d -> finished.scheduledAt().plus(d)),
                properties, scheduler, clock);
    }

    /** Check one, the balance split, the branch check, the sweep: each needs the one before. */
    private List<SweepSagaStage> allStages() {
        return List.of(
                new SweepSagaStage("checkOne", List.of(propOne), 4, Duration.ofSeconds(1), scheduler),
                new SweepSagaStage("balance", List.of(balance), 1, Duration.ofSeconds(1), scheduler),
                new SweepSagaStage("checkBranch", List.of(propTwo), 4, Duration.ofSeconds(1), scheduler),
                new SweepSagaStage("sweep", List.of(sweep), 1, Duration.ofSeconds(1), scheduler));
    }

    private PlanExecution claimOne(SweepSagaPipeline pipeline) {
        return store.claimDue(1, properties.lease()).getFirst();
    }

    @Test
    @DisplayName("an execution runs through the stages, is completed and the next one is scheduled")
    void endToEnd() {
        SweepSagaPipeline pipeline = pipeline(allStages(), Duration.ofDays(1));
        PlanExecution scheduled = pipeline.schedule(currentPlan.get(), T0).block();
        currentPlan.set(TestData.planInstr("600.00"));   // the plan is edited while it is scheduled

        PlanExecution done = pipeline.execute(claimOne(pipeline)).block();

        assertEquals(AuditStatus.SWEPT_OUT, done.auditStatus());           // 730.25 ≥ 500
        assertEquals(new BigDecimal("230.25"), done.derivedPropOne());     // computed with its own planInstr (500)
        assertEquals(new BigDecimal("230.25"), done.derivedPropTwo());     // a later stage used stage 1's value
        assertEquals(List.of("insert " + scheduled.executionId(), "checkpoint CHECK_ONE_PASSED",
                "checkpoint HIGH_BALANCE", "checkpoint CHECK_HIGH_PASSED", "checkpoint SWEPT_OUT",
                "complete SWEPT_OUT"), store.log.subList(0, 6));
        assertTrue(store.finished.get(done.executionId()));

        PlanExecution next = store.rows.values().stream()
                .filter(pe -> !pe.executionId().equals(done.executionId())).findFirst().orElseThrow();
        assertEquals(T0.plus(Duration.ofDays(1)), next.scheduledAt());
        assertEquals(new BigDecimal("600.00"), next.planInstr().thresholdInfo().thresholdMax()); // current plan copied
        assertEquals(AuditStatus.SCHEDULED, next.auditStatus());
        assertFalse(store.finished.get(next.executionId()));
    }

    @Test
    @DisplayName("a balance below the plan's threshold ends SWEPT_IN")
    void lowBalanceEndsSweptIn() {
        SweepSagaPipeline pipeline = pipeline(allStages(), null);
        pipeline.schedule(TestData.planInstr("800.00"), T0).block();

        PlanExecution done = pipeline.execute(claimOne(pipeline)).block();

        assertEquals(AuditStatus.SWEPT_IN, done.auditStatus());
        assertEquals(new BigDecimal("-69.75"), done.derivedPropOne());
        assertTrue(store.log.contains("checkpoint LOW_BALANCE"), store.log.toString());
    }

    @Test
    @DisplayName("stages after the first failure are skipped and the execution is completed as failed")
    void failureSkipsLaterStages() {
        TestRetriever<PropOneEvent> broken = new TestRetriever<>("broken", Duration.ZERO,
                in -> { throw new IllegalStateException("503"); }, (in, e) -> PropOneEvent.failed(T0, e));
        SweepSagaPipeline pipeline = pipeline(List.of(
                new SweepSagaStage("one", List.of(broken), 1, Duration.ofSeconds(1), scheduler),
                new SweepSagaStage("two", List.of(propTwo), 1, Duration.ofSeconds(1), scheduler)), null);
        pipeline.schedule(currentPlan.get(), T0).block();

        PlanExecution done = pipeline.execute(claimOne(pipeline)).block();

        assertEquals(AuditStatus.CHECK_ONE_FAILED, done.auditStatus());
        assertEquals(0, propTwo.calls.get());
        assertTrue(store.finished.get(done.executionId()));
        assertEquals(1, store.rows.size());   // no next run (policy returned empty)
    }

    @Test
    @DisplayName("a cancelled plan is not rescheduled")
    void cancelledPlanNotRescheduled() {
        SweepSagaPipeline pipeline = pipeline(allStages(), Duration.ofDays(1));
        pipeline.schedule(currentPlan.get(), T0).block();
        currentPlan.set(TestData.planInstr("500.00", PlanStatus.CANCELLED));

        pipeline.execute(claimOne(pipeline)).block();

        assertEquals(1, store.rows.size());
    }

    @Test
    @DisplayName("an execution with unknown events is left alone for a newer worker")
    void unknownEventsLeftAlone() {
        SweepSagaPipeline pipeline = pipeline(allStages(), Duration.ofDays(1));
        PlanExecution withUnknown = new UnknownAuditEvent(Map.of("__eventType__", "FROM_NEWER_BUILD"))
                .applyTo(PlanExecution.schedule(java.util.UUID.randomUUID(), currentPlan.get(), T0));

        assertNull(pipeline.execute(withUnknown).block());
        assertEquals(0, propOne.calls.get());
    }

    @Test
    @DisplayName("started pipeline polls, executes and completes scheduled executions by itself")
    void startedPipeline() throws InterruptedException {
        try (SweepSagaPipeline pipeline = pipeline(allStages(), null)) {
            for (int i = 0; i < 5; i++) {
                pipeline.schedule(currentPlan.get(), T0).block();
            }
            pipeline.start();
            assertTrue(pipeline.isRunning());

            Instant deadline = Instant.now().plusSeconds(10);
            while (store.finished.containsValue(false) && Instant.now().isBefore(deadline)) {
                Thread.sleep(20);
            }
        }
        assertFalse(store.finished.containsValue(false), "all executions should be finished: " + store.log);
        assertEquals(5, store.rows.values().stream().filter(pe -> pe.auditStatus() == AuditStatus.SWEPT_OUT).count());
    }
}
