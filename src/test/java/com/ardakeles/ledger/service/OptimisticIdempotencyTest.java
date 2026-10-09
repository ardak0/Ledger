package com.ardakeles.ledger.service;

import com.ardakeles.ledger.service.OptimisticTransferService;
import com.ardakeles.ledger.service.TransferService;
import org.springframework.beans.factory.annotation.Autowired;

class OptimisticIdempotencyTest extends IdempotencyContract {

    @Autowired OptimisticTransferService strategy;

    @Override
    TransferService service() {
        return strategy;
    }
}
