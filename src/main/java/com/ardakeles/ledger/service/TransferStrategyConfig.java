package com.ardakeles.ledger.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Chooses which {@link TransferService} the application injects by default. Both implementations always
 * exist as beans (so tests and the benchmark can use either directly); this only decides the primary.
 */
@Configuration(proxyBeanMethods = false)
class TransferStrategyConfig {

    @Bean
    @Primary
    TransferService transferService(PessimisticTransferService pessimistic, OptimisticTransferService optimistic,
                                    @Value("${ledger.locking.strategy:pessimistic}") String strategy) {
        return switch (strategy) {
            case "pessimistic" -> pessimistic;
            case "optimistic" -> optimistic;
            default -> throw new IllegalArgumentException(
                    "ledger.locking.strategy must be 'pessimistic' or 'optimistic', was '" + strategy + "'");
        };
    }
}
