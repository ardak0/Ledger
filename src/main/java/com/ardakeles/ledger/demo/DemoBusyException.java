package com.ardakeles.ledger.demo;

/** A run is already in progress; counters are per-run, so overlapping runs are refused. */
public class DemoBusyException extends RuntimeException {

    public DemoBusyException() {
        super("a demo run is already in progress");
    }
}
