package com.ardakeles.ledger.service;

import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.domain.TransferResult;

/**
 * Posts a transfer exactly once per idempotency key, safely under concurrency. There are two
 * implementations of the same contract, differing only in how they coordinate concurrent writers:
 * {@link PessimisticTransferService} (row locks, the default) and {@link OptimisticTransferService}
 * (version checks plus bounded retry). {@code ledger.locking.strategy} selects which one the
 * application uses; both always exist so tests and the benchmark can compare them in one JVM.
 */
public interface TransferService {

    TransferResult execute(String idempotencyKey, TransferRequest request);
}
