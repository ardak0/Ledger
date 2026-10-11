package com.ardakeles.ledger.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ardakeles.ledger.TestcontainersConfig;
import com.ardakeles.ledger.domain.Currency;
import com.ardakeles.ledger.repository.LedgerRepository;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * HTTP contract tests through the full Spring stack (routing, Jackson, validation, error mapping,
 * services, jOOQ) against real Postgres. System accounts cannot be created through the API, so
 * funding sources are seeded via the repository, exactly as a deployment would seed them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
class ApiTest {

    static final String PROBLEM = "application/problem+json";

    @Autowired MockMvc mvc;
    @Autowired LedgerRepository repository;
    @Autowired JdbcTemplate jdbc;

    // ---- accounts ----

    @Test
    void createAccountReturns201WithLocationAndZeroBalance() throws Exception {
        String body = mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("{\"currency\":\"EUR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.balanceMinor").value(0))
                .andReturn().getResponse().getContentAsString();
        long id = idOf(body);

        mvc.perform(get("/accounts/{id}/balance", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(id))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.balanceMinor").value(0));
    }

    @Test
    void clientCannotCreateASystemAccount() throws Exception {
        int systemBefore = jdbc.queryForObject("SELECT count(*) FROM account WHERE is_system", Integer.class);

        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"USD\",\"isSystem\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM account WHERE is_system", Integer.class)).isEqualTo(systemBefore);
    }

    @Test
    void unknownCurrencyAndMissingCurrencyAreBadRequests() throws Exception {
        for (String body : List.of("{\"currency\":\"XYZ\"}", "{\"currency\":\"usd\"}", "{}")) {
            mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void balanceOfUnknownAccountIs404Problem() throws Exception {
        mvc.perform(get("/accounts/{id}/balance", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    // ---- transfers ----

    @Test
    void transferMovesMoneyAndReturns201() throws Exception {
        long alice = fundedAccount(Currency.USD, 1_000);
        long bob = newAccount(Currency.USD);
        String key = key();

        mvc.perform(transfer(key, alice, bob, 400, "USD"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.idempotencyKey").value(key))
                .andExpect(jsonPath("$.fromAccountId").value(alice))
                .andExpect(jsonPath("$.toAccountId").value(bob))
                .andExpect(jsonPath("$.amountMinor").value(400));

        assertThat(balanceOf(alice)).isEqualTo(600);
        assertThat(balanceOf(bob)).isEqualTo(400);
    }

    @Test
    void insufficientFundsIs422WithStableCodeAndDoesNotLeakTheBalance() throws Exception {
        long alice = fundedAccount(Currency.USD, 100);
        long bob = newAccount(Currency.USD);

        mvc.perform(transfer(key(), alice, bob, 101, "USD"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("100"))));

        assertThat(balanceOf(alice)).isEqualTo(100);
    }

    @Test
    void unknownAccountInTransferIs404() throws Exception {
        long alice = fundedAccount(Currency.USD, 100);

        mvc.perform(transfer(key(), alice, Long.MAX_VALUE, 10, "USD"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
    }

    @Test
    void currencyMismatchIs422() throws Exception {
        long usd = fundedAccount(Currency.USD, 100);
        long eur = newAccount(Currency.EUR);

        mvc.perform(transfer(key(), usd, eur, 10, "USD"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_MISMATCH"));
    }

    @Test
    void nonPositiveAmountAndSelfTransferAre422() throws Exception {
        long alice = fundedAccount(Currency.USD, 100);
        long bob = newAccount(Currency.USD);

        for (long amount : new long[] {0, -5}) {
            mvc.perform(transfer(key(), alice, bob, amount, "USD"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("INVALID_TRANSFER"));
        }
        mvc.perform(transfer(key(), alice, alice, 10, "USD"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSFER"));
    }

    @Test
    void missingIdempotencyKeyIs400() throws Exception {
        mvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":1,\"toAccountId\":2,\"amountMinor\":10,\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM));
    }

    @Test
    void blankOversizedOrUnsafeIdempotencyKeyIs400() throws Exception {
        // Blank, too long, and characters that could forge log fields or smuggle control characters.
        for (String badKey : List.of("   ", "k".repeat(256), "has space", "transferId=1", "quote\"", "tab\tkey", "ключ")) {
            mvc.perform(transfer(badKey, 1, 2, 10, "USD"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
    }

    @Test
    void commonIdempotencyKeyFormatsAreAccepted() throws Exception {
        long alice = fundedAccount(Currency.USD, 1_000);
        long bob = newAccount(Currency.USD);
        for (String goodKey : List.of(key(), "pay-2026-0001", "order:42.retry_1", "01HZX3K8Q9V7RD4N2B6M5T0YWC",
                "k".repeat(255))) {
            mvc.perform(transfer(goodKey, alice, bob, 1, "USD")).andExpect(status().isCreated());
        }
    }

    /** Every error body carries a stable code, including the ones Spring itself produces. */
    @Test
    void frameworkProducedErrorsCarryACodeToo() throws Exception {
        mvc.perform(post("/transfers").contentType(MediaType.APPLICATION_JSON)  // missing Idempotency-Key
                        .content("{\"fromAccountId\":1,\"toAccountId\":2,\"amountMinor\":10,\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/transfers").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON).content("{}"))  // bean validation
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/no-such-endpoint"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/transfers"))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    /** A missing amount must not silently become 0, and numbers must stay integers. */
    @Test
    void strictJsonRejectsMissingFloatAndStringAmounts() throws Exception {
        long alice = fundedAccount(Currency.USD, 100);
        long bob = newAccount(Currency.USD);
        String ids = "\"fromAccountId\":" + alice + ",\"toAccountId\":" + bob + ",\"currency\":\"USD\"";

        for (String body : List.of(
                "{" + ids + "}",                          // amount missing
                "{" + ids + ",\"amountMinor\":10.5}",     // float would truncate to 10
                "{" + ids + ",\"amountMinor\":10.0}",     // even a whole-valued float
                "{" + ids + ",\"amountMinor\":\"10\"}",   // string would coerce
                "{" + ids + ",\"amountMinor\":10,\"fee\":1}")) { // unknown field
            mvc.perform(post("/transfers").header("Idempotency-Key", key())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM));
        }
        assertThat(balanceOf(alice)).isEqualTo(100);
        assertThat(balanceOf(bob)).isZero();
    }

    @Test
    void missingFieldErrorNamesTheField() throws Exception {
        mvc.perform(post("/transfers").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":1,\"toAccountId\":2,\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amountMinor").exists());
    }

    // ---- idempotency over HTTP ----

    @Test
    void replayedRequestReturns200WithTheSameBodyAndReplayHeader() throws Exception {
        long alice = fundedAccount(Currency.USD, 1_000);
        long bob = newAccount(Currency.USD);
        String key = key();

        String first = mvc.perform(transfer(key, alice, bob, 400, "USD"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(transfer(key, alice, bob, 400, "USD"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(balanceOf(alice)).isEqualTo(600); // moved once
    }

    @Test
    void reusingAKeyWithADifferentBodyIs422() throws Exception {
        long alice = fundedAccount(Currency.USD, 1_000);
        long bob = newAccount(Currency.USD);
        String key = key();
        mvc.perform(transfer(key, alice, bob, 400, "USD")).andExpect(status().isCreated());

        mvc.perform(transfer(key, alice, bob, 401, "USD"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(balanceOf(alice)).isEqualTo(600);
    }

    // ---- statement ----

    @Test
    void statementPagesByKeysetWithoutGapsOrDuplicates() throws Exception {
        long alice = newAccount(Currency.USD);
        long funding = repository.createSystemAccount(Currency.USD).id();
        for (int i = 0; i < 5; i++) {
            mvc.perform(transfer(key(), funding, alice, 100 + i, "USD")).andExpect(status().isCreated());
        }

        List<Long> seenIds = new ArrayList<>();
        List<Integer> seenAmounts = new ArrayList<>();
        String after = "0";
        int pages = 0;
        while (after != null) {
            String body = mvc.perform(get("/accounts/{id}/entries?after={a}&limit=2", alice, after))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<Number> ids = JsonPath.read(body, "$.entries[*].id");
            List<Integer> amounts = JsonPath.read(body, "$.entries[*].amountMinor");
            ids.forEach(n -> seenIds.add(n.longValue()));
            seenAmounts.addAll(amounts);
            Number next = JsonPath.read(body, "$.nextAfter");
            after = next == null ? null : String.valueOf(next.longValue());
            pages++;
        }

        assertThat(pages).isEqualTo(3); // 2 + 2 + 1
        assertThat(seenAmounts).containsExactly(100, 101, 102, 103, 104);
        assertThat(seenIds).doesNotHaveDuplicates().isSorted();
    }

    @Test
    void statementRejectsBadPagingAndUnknownAccounts() throws Exception {
        long alice = newAccount(Currency.USD);

        for (String query : List.of("limit=0", "limit=201", "after=-1", "after=abc")) {
            mvc.perform(get("/accounts/{id}/entries?" + query, alice))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(get("/accounts/{id}/entries", Long.MAX_VALUE)).andExpect(status().isNotFound());
        mvc.perform(get("/accounts/{id}/entries", alice))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isEmpty())
                .andExpect(jsonPath("$.nextAfter").value(org.hamcrest.Matchers.nullValue()));
    }

    // ---- health ----

    @Test
    void healthChecksTheDatabaseAndIsServedAtRoot() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(header().exists("Content-Type"));
    }

    // ---- helpers ----

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder transfer(
            String key, long from, long to, long amount, String currency) {
        return post("/transfers").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromAccountId\":%d,\"toAccountId\":%d,\"amountMinor\":%d,\"currency\":\"%s\"}"
                        .formatted(from, to, amount, currency));
    }

    private long newAccount(Currency currency) throws Exception {
        String body = mvc.perform(post("/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"" + currency + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return idOf(body);
    }

    /** Funded the only legitimate way: a transfer from a system account, through the API. */
    private long fundedAccount(Currency currency, long amount) throws Exception {
        long account = newAccount(currency);
        long funding = repository.createSystemAccount(currency).id();
        ResultActions r = mvc.perform(transfer(key(), funding, account, amount, currency.name()));
        r.andExpect(status().isCreated());
        return account;
    }

    private long balanceOf(long accountId) {
        return jdbc.queryForObject("SELECT balance_minor FROM account WHERE id = ?", Long.class, accountId);
    }

    private static long idOf(String json) {
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
