package com.ardakeles.ledger.domain;

/** Outcome of an execute call. {@code replayed} is true when the key had already been used successfully. */
public record TransferResult(Transfer transfer, boolean replayed) {
}
