package com.ardakeles.ledger.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether account locks are taken in ascending id order (safe, the default) or in the order the
 * transfer names them (unsafe).
 *
 * <p>DELIBERATE DEMONSTRATION OF A BUG -- NOT A PRODUCTION SETTING. With unordered locking, two
 * concurrent opposite transfers, A->B and B->A, each hold one lock and wait for the other: a
 * deadlock, which Postgres resolves by killing one transaction. The killed transaction rolls back
 * completely, so money is still never created or destroyed -- the transfer just fails. This exists
 * so the tests and the /demo page can show the deadlock and the fix side by side.
 *
 * <p>It defaults to ordered and logs a warning whenever it is off. The initial value comes from
 * {@code ledger.locking.ordered} (used by the negative-control test); at runtime the only caller of
 * {@link #setOrdered} is the demo controller, which does not exist unless {@code ledger.demo.enabled=true}.
 */
@Component
public class LockingMode {

    private static final Logger log = LoggerFactory.getLogger(LockingMode.class);

    private volatile boolean ordered;

    public LockingMode(@Value("${ledger.locking.ordered:true}") boolean ordered) {
        setOrdered(ordered);
    }

    public boolean isOrdered() {
        return ordered;
    }

    public void setOrdered(boolean ordered) {
        this.ordered = ordered;
        if (!ordered) {
            log.warn("UNSAFE LOCKING ENABLED: account locks are taken in request order, NOT ascending id. "
                    + "Deadlocks are EXPECTED under concurrent opposite transfers. Demonstration only.");
        } else {
            log.info("Safe locking: account locks are taken in ascending id order.");
        }
    }
}
