package com.ardakeles.ledger.api;

import com.ardakeles.ledger.domain.Currency;
import com.ardakeles.ledger.domain.Transfer;
import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.service.TransferService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class TransferController {

    /** Matches the CHECK on transfer.idempotency_key in the V1 migration. */
    static final int MAX_KEY_LENGTH = 255;

    private final TransferService transfers;

    TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    /**
     * Fields are boxed so a missing field is null (-> 400 via @NotNull). A primitive long would
     * silently default to 0. Amounts are JSON integers in minor units; note that JavaScript
     * clients lose precision above 2^53, which is far beyond any realistic amount here.
     */
    record TransferHttpRequest(@NotNull Long fromAccountId, @NotNull Long toAccountId,
                               @NotNull Long amountMinor, @NotNull Currency currency) {
        TransferRequest toDomain() {
            return new TransferRequest(fromAccountId, toAccountId, amountMinor, currency);
        }
    }

    record TransferResponse(long id, String idempotencyKey, long fromAccountId, long toAccountId,
                            long amountMinor, Currency currency, OffsetDateTime createdAt) {
        static TransferResponse from(Transfer t) {
            TransferRequest r = t.request();
            return new TransferResponse(t.id(), t.idempotencyKey(), r.fromAccountId(), r.toAccountId(),
                    r.amountMinor(), r.currency(), t.createdAt());
        }
    }

    @PostMapping("/transfers")
    ResponseEntity<TransferResponse> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                            @Valid @RequestBody TransferHttpRequest request) {
        if (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Idempotency-Key must be 1-" + MAX_KEY_LENGTH + " non-blank characters");
        }
        Transfer transfer = transfers.execute(idempotencyKey, request.toDomain());
        return ResponseEntity.status(HttpStatus.CREATED).body(TransferResponse.from(transfer));
    }
}
