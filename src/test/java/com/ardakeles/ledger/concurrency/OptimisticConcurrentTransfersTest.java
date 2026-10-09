package com.ardakeles.ledger.concurrency;

import com.ardakeles.ledger.service.OptimisticTransferService;
import com.ardakeles.ledger.service.TransferService;
import org.springframework.beans.factory.annotation.Autowired;

class OptimisticConcurrentTransfersTest extends ConcurrentTransfersContract {

    @Autowired OptimisticTransferService strategy;

    @Override
    TransferService service() {
        return strategy;
    }

    @Override
    int contentionTolerance() {
        return 100;
    }
}
