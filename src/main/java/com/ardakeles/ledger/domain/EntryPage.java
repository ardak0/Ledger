package com.ardakeles.ledger.domain;

import java.util.List;

/** One page of an account statement. {@code nextAfter} is null on the last page. */
public record EntryPage(List<LedgerEntry> entries, Long nextAfter) {
}
