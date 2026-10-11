package com.ardakeles.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ardakeles.ledger.TestcontainersConfig;
import com.ardakeles.ledger.domain.Account;
import com.ardakeles.ledger.domain.Currency;
import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.repository.LedgerRepository;
import com.ardakeles.ledger.service.PessimisticTransferService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Behaviour of the request id and the audit events. Boot's JSON formatter itself is not tested here (it is
 * framework code); what is tested is that OUR code puts the right id and fields into the logging context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class CorrelationIdAndAuditLogTest {

    @Autowired MockMvc mvc;
    @Autowired LedgerRepository repository;
    @Autowired PessimisticTransferService transfers;

    private final Logger audit = (Logger) LoggerFactory.getLogger("ledger.audit");
    private ListAppender<ILoggingEvent> events;

    @BeforeEach
    void captureAuditLog() {
        events = new ListAppender<>();
        events.start();
        audit.addAppender(events);
    }

    @AfterEach
    void stopCapturing() {
        audit.detachAppender(events);
    }

    @Test
    void aSafeClientSuppliedRequestIdIsEchoedAndAttachedToTheAuditEvent() throws Exception {
        long[] ids = fundedPair(1_000);

        mvc.perform(transfer(ids[0], ids[1], 250, "key-" + UUID.randomUUID()).header("X-Request-Id", "client-req_42.A"))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Request-Id", "client-req_42.A"));

        ILoggingEvent posted = only("transfer.posted");
        assertThat(posted.getMDCPropertyMap()).containsEntry("requestId", "client-req_42.A");
        assertThat(fields(posted)).containsEntry("fromAccountId", ids[0]).containsEntry("toAccountId", ids[1])
                .containsEntry("amountMinor", 250L).containsEntry("currency", Currency.USD)
                .containsEntry("replayed", false).containsKey("transferId").containsKey("idempotencyKey");
    }

    @Test
    void anUnsafeOrMissingRequestIdIsReplacedByAGeneratedUuid() throws Exception {
        long[] ids = fundedPair(1_000);
        for (String unsafe : List.of("has spaces", "forged\"},{\"level\":\"ERROR", "x".repeat(65), "")) {
            String echoed = mvc.perform(transfer(ids[0], ids[1], 1, "key-" + UUID.randomUUID()).header("X-Request-Id", unsafe))
                    .andExpect(status().isCreated()).andReturn().getResponse().getHeader("X-Request-Id");
            assertThat(echoed).as("replacement for %s", unsafe).matches("[0-9a-f-]{36}");
        }
        String generated = mvc.perform(transfer(ids[0], ids[1], 1, "key-" + UUID.randomUUID()))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(generated).matches("[0-9a-f-]{36}");
    }

    @Test
    void theRequestIdDoesNotLeakIntoTheNextRequestOnTheSameThread() throws Exception {
        long[] ids = fundedPair(1_000);
        mvc.perform(transfer(ids[0], ids[1], 1, "key-" + UUID.randomUUID()).header("X-Request-Id", "first")).andExpect(status().isCreated());

        assertThat(MDC.get("requestId")).as("MDC after the request completed").isNull();
    }

    @Test
    void aReplayIsAuditedAsReplayedAndARejectionCarriesItsErrorCode() throws Exception {
        long[] ids = fundedPair(100);
        String key = "key-" + UUID.randomUUID();
        mvc.perform(transfer(ids[0], ids[1], 10, key)).andExpect(status().isCreated());
        mvc.perform(transfer(ids[0], ids[1], 10, key)).andExpect(status().isOk());
        mvc.perform(transfer(ids[0], ids[1], 5_000, "key-" + UUID.randomUUID()).header("X-Request-Id", "rej-1"))
                .andExpect(status().isUnprocessableEntity());

        List<ILoggingEvent> posted = events.list.stream().filter(e -> e.getMessage().equals("transfer.posted")).toList();
        assertThat(posted).extracting(e -> fields(e).get("replayed")).containsExactly(false, true);
        ILoggingEvent rejected = only("request.rejected");
        assertThat(fields(rejected)).containsEntry("code", "INSUFFICIENT_FUNDS").containsEntry("status", 422);
        assertThat(rejected.getMDCPropertyMap()).containsEntry("requestId", "rej-1");
    }

    @Test
    void aMalformedRequestIsAuditedWithItsCode() throws Exception {
        events.list.clear();
        mvc.perform(transfer(1, 2, 10, "has space").header("X-Request-Id", "bad-key-1")).andExpect(status().isBadRequest());

        ILoggingEvent rejected = only("request.rejected");
        assertThat(fields(rejected)).containsEntry("code", "INVALID_REQUEST").containsEntry("status", 400);
        assertThat(rejected.getMDCPropertyMap()).containsEntry("requestId", "bad-key-1");
    }

    // ---- helpers ----

    private ILoggingEvent only(String message) {
        List<ILoggingEvent> matching = events.list.stream().filter(e -> e.getMessage().equals(message)).toList();
        assertThat(matching).as("audit events named %s", message).hasSize(1);
        return matching.get(0);
    }

    private static Map<String, Object> fields(ILoggingEvent event) {
        return event.getKeyValuePairs().stream().collect(Collectors.toMap(kv -> kv.key, kv -> kv.value));
    }

    private long[] fundedPair(long amount) {
        Account funding = repository.createSystemAccount(Currency.USD);
        Account from = repository.createAccount(Currency.USD);
        Account to = repository.createAccount(Currency.USD);
        transfers.execute("fund-" + UUID.randomUUID(), new TransferRequest(funding.id(), from.id(), amount, Currency.USD));
        events.list.clear(); // setup is not under test
        return new long[] {from.id(), to.id()};
    }

    private static MockHttpServletRequestBuilder transfer(long from, long to, long amount, String key) {
        return post("/transfers").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":%d,\"toAccountId\":%d,\"amountMinor\":%d,\"currency\":\"USD\"}".formatted(from, to, amount));
    }
}
