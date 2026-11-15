package com.example.plans.persistence;

/** Thrown when a plan that must exist cannot be found. */
public class PlanNotFoundException extends RuntimeException {

    public PlanNotFoundException(String message) {
        super(message);
    }

    public PlanNotFoundException(Object planId) {
        super("Plan not found: " + planId);
    }
}
