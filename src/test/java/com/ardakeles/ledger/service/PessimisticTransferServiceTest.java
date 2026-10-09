package com.ardakeles.ledger.service;

import com.ardakeles.ledger.service.PessimisticTransferService;
import com.ardakeles.ledger.service.TransferService;
import org.springframework.beans.factory.annotation.Autowired;

class PessimisticTransferServiceTest extends TransferServiceContract {

    @Autowired PessimisticTransferService strategy;

    @Override
    TransferService service() {
        return strategy;
    }
}
