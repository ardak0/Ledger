package com.ardakeles.ledger.service;

import java.sql.SQLException;

/** Recognises the error Postgres raises for a transaction it killed to break a deadlock. */
public final class Deadlocks {

    /** SQLSTATE 40P01: deadlock_detected. */
    private static final String DEADLOCK_DETECTED = "40P01";

    private Deadlocks() {
    }

    /** True if a deadlock-victim error appears anywhere in the cause chain (jOOQ and Spring wrap it). */
    public static boolean isDeadlock(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException e && DEADLOCK_DETECTED.equals(e.getSQLState())) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }
}
