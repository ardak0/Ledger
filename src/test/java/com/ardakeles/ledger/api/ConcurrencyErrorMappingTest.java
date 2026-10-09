package com.ardakeles.ledger.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ardakeles.ledger.TestcontainersConfig;
import com.ardakeles.ledger.domain.ContentionException;
import com.ardakeles.ledger.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Concurrency failures cannot be provoked on demand over HTTP, so the service is stubbed to raise them.
 * What is asserted is the contract with clients: a retryable 503 with Retry-After and a stable code,
 * never a generic 500 -- and the body must not leak internals.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ConcurrencyErrorMappingTest {

    @Autowired MockMvc mvc;
    @MockitoBean TransferService transfers;

    @Test
    void exhaustedOptimisticRetriesAre503WithRetryAfter() throws Exception {
        when(transfers.execute(any(), any())).thenThrow(new ContentionException(10));

        mvc.perform(transfer())
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("CONTENTION"));
    }

    @Test
    void deadlockVictimIs503WithRetryAfterAndLeaksNoSql() throws Exception {
        when(transfers.execute(any(), any()))
                .thenThrow(new DeadlockLoserDataAccessException("could not serialize: update account set ...", null));

        mvc.perform(transfer())
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("TRANSIENT_FAILURE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("update account"))));
    }

    private static MockHttpServletRequestBuilder transfer() {
        return post("/transfers").header("Idempotency-Key", "k-1").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":1,\"toAccountId\":2,\"amountMinor\":10,\"currency\":\"USD\"}");
    }
}
