package com.ardakeles.ledger.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * A request to move {@code amountMinor} minor units of {@code currency} from one account to
 * another. Cannot be constructed in an invalid state.
 */
public record TransferRequest(long fromAccountId, long toAccountId, long amountMinor, Currency currency) {

    public TransferRequest {
        Objects.requireNonNull(currency, "currency");
        if (amountMinor <= 0) {
            throw new InvalidTransferException("amount must be positive, was " + amountMinor);
        }
        if (fromAccountId == toAccountId) {
            throw new InvalidTransferException("cannot transfer from an account to itself");
        }
    }

    /**
     * SHA-256 of a canonical form of this request, stored with the transfer so a replayed
     * idempotency key can be checked against its original payload. Hashing the parsed fields,
     * not the raw HTTP body, means whitespace or JSON key order cannot cause a false mismatch.
     * "|" cannot appear in numbers or currency names, so distinct requests cannot collide on
     * the canonical string. The "v1" prefix lets the format change without silent mismatches.
     */
    public byte[] sha256() {
        String canonical = "v1|" + fromAccountId + "|" + toAccountId + "|" + amountMinor + "|" + currency.name();
        try {
            return MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e); // JCA guarantee
        }
    }
}
