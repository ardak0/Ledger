package com.ardakeles.ledger.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether account locks are taken in ascending id order (safe) or in the order the transfer names
 * them (unsafe).
 *
 * <p>DELIBERATE DEMONSTRATION OF A BUG -- NOT A PRODUCTION SETTING. With unordered locking, two
 * concurrent opposite transfers, A->B and B->A, each hold one lock and wait for the other: a
 * deadlock, which Postgres resolves by killing one transaction. The killed transaction rolls back
 * completely, so money is still never created or destroyed -- the transfer just fails. This exists
 * so the tests and the /demo page can show the deadlock and the fix side by side.
 *
 * <p>Deliberately NOT a Spring bean, and not configurable by any property. The application's
 * {@link TransferService} always locks in ascending order and has no switch at all. Only code that
 * builds its own instance via {@link PessimisticTransferService#withLockingMode} (the demo and the
 * negative-control test) can be made to deadlock, so flipping this can never affect API traffic.
 */
public class LockingMode {

    private static final Logger log = LoggerFactory.getLogger(LockingMode.class);

    private volatile boolean ordered;

    public LockingMode(boolean ordered) {
        setOrdered(ordered);
    }

    public boolean isOrdered() {
        return ordered;
    }

    public void setOrdered(boolean ordered) {
        this.ordered = ordered;
        if (!ordered) {
            log.warn("UNSAFE LOCKING ENABLED for a demonstration TransferService: account locks are taken in request "
                    + "order, NOT ascending id. Deadlocks are EXPECTED under concurrent opposite transfers. "
                    + "The application's own TransferService is unaffected.");
        } else {
            log.info("Safe locking: account locks are taken in ascending id order.");
        }
    }
}
