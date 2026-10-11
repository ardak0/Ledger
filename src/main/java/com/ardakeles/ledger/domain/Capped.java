package com.ardakeles.ledger.domain;

import java.util.List;

/** At most N items plus the true total, so a catastrophic bug cannot make a report exhaust memory. */
public record Capped<T>(long total, List<T> items) {
}
