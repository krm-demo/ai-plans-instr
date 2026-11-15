package plansinstr.model;

/** Lifecycle state of a plan. CANCELLED is terminal and acts as the soft-delete marker. */
public enum PlanStatus {
    ACTIVE,
    PAUSED,
    CANCELLED
}
