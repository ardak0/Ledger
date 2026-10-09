package com.ardakeles.ledger.concurrency;

import com.ardakeles.ledger.service.PessimisticTransferService;
import com.ardakeles.ledger.service.TransferService;
import org.springframework.beans.factory.annotation.Autowired;

class PessimisticConcurrentTransfersTest extends ConcurrentTransfersContract {

    @Autowired PessimisticTransferService strategy;

    @Override
    TransferService service() {
        return strategy;
    }

    @Override
    int contentionTolerance() {
        return 0;
    }
}
