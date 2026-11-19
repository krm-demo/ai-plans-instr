package org.krmdemo.plansinstr.exec.model.events;

import java.util.concurrent.TimeoutException;

/** Shared by the events' {@code failed(…, Throwable)} factories. */
final class Failures {

    private Failures() {
    }

    /** A timeout anywhere in the cause chain (Reactor's {@code timeout()} signals {@link TimeoutException}). */
    static boolean isTimeout(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
