package com.ardakeles.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class TransferRequestTest {

    static final TransferRequest BASE = new TransferRequest(1, 2, 500, Currency.USD);

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonPositiveAmounts(long amount) {
        assertThatThrownBy(() -> new TransferRequest(1, 2, amount, Currency.USD))
                .isInstanceOf(InvalidTransferException.class);
    }

    @Test
    void rejectsTransferToSameAccount() {
        assertThatThrownBy(() -> new TransferRequest(7, 7, 100, Currency.USD))
                .isInstanceOf(InvalidTransferException.class);
    }

    @Test
    void hashIsStableForEqualRequests() {
        assertThat(new TransferRequest(1, 2, 500, Currency.USD).sha256()).isEqualTo(BASE.sha256()).hasSize(32);
    }

    /** If a field were left out of the canonical string, "same key, different payload" would go undetected. */
    @ParameterizedTest
    @MethodSource("requestsDifferingInExactlyOneField")
    void everyFieldAffectsTheHash(TransferRequest changed) {
        assertThat(changed.sha256()).isNotEqualTo(BASE.sha256());
    }

    static Stream<TransferRequest> requestsDifferingInExactlyOneField() {
        return Stream.of(
                new TransferRequest(3, 2, 500, Currency.USD),
                new TransferRequest(1, 3, 500, Currency.USD),
                new TransferRequest(1, 2, 501, Currency.USD),
                new TransferRequest(1, 2, 500, Currency.EUR),
                // swapped direction must differ too -- it moves money the other way
                new TransferRequest(2, 1, 500, Currency.USD));
    }
}
