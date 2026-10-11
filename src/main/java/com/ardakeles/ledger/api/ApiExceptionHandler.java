package com.ardakeles.ledger.api;

import com.ardakeles.ledger.domain.AccountNotFoundException;
import com.ardakeles.ledger.domain.ContentionException;
import com.ardakeles.ledger.domain.CurrencyMismatchException;
import com.ardakeles.ledger.domain.IdempotencyKeyReuseException;
import com.ardakeles.ledger.domain.InsufficientFundsException;
import com.ardakeles.ledger.domain.InvalidTransferException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Single place that turns exceptions into RFC 9457 {@code application/problem+json} responses.
 *
 * <ul>
 *   <li>400 -- the request itself is malformed (handled by the Spring base class, plus our validation)</li>
 *   <li>404 -- the referenced account does not exist</li>
 *   <li>422 -- well-formed, but violates a ledger rule; carries a stable {@code code} for clients</li>
 *   <li>500 -- anything unexpected: generic body, details only in the server log</li>
 * </ul>
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final Logger audit = LoggerFactory.getLogger("ledger.audit");

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail accountNotFound(AccountNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", "Account not found", e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds(InsufficientFundsException e) {
        // Deliberately generic: the exception message contains the balance, which a failed
        // request should not reveal.
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS", "Insufficient funds",
                "The source account has insufficient funds for this transfer.");
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    ProblemDetail currencyMismatch(CurrencyMismatchException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_MISMATCH", "Currency mismatch", e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReuseException.class)
    ProblemDetail idempotencyKeyReuse(IdempotencyKeyReuseException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", "Idempotency key reused",
                "This Idempotency-Key was already used with a different request body.");
    }

    @ExceptionHandler(InvalidTransferException.class)
    ProblemDetail invalidTransfer(InvalidTransferException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_TRANSFER", "Invalid transfer", e.getMessage());
    }

    /**
     * Retry budget exhausted under heavy contention. Nothing was posted; retrying with the same
     * Idempotency-Key is safe. 503 + Retry-After (not 409): there is nothing for the client to resolve.
     */
    @ExceptionHandler(ContentionException.class)
    ResponseEntity<ProblemDetail> contention(ContentionException e) {
        return retryable("CONTENTION", "Too much contention",
                "The transfer could not be posted because of concurrent updates. Nothing was posted; retry with the same Idempotency-Key.");
    }

    /** Deadlock victim, lock timeout or serialization failure: the database aborted the transaction; nothing was posted. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ProblemDetail> transientFailure(ConcurrencyFailureException e) {
        log.warn("transient concurrency failure: {}", e.getMessage());
        return retryable("TRANSIENT_FAILURE", "Temporary failure",
                "The transfer was aborted by a concurrency failure. Nothing was posted; retry with the same Idempotency-Key.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error",
                "An unexpected error occurred.");
    }

    /** Adds a per-field map of what was wrong to the standard 400 body. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(f -> errors.putIfAbsent(f.getField(), f.getDefaultMessage()));
        ProblemDetail body = ex.getBody();
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private static ResponseEntity<ProblemDetail> retryable(String code, String title, String detail) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, code, title, detail));
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        // Every business rejection leaves one audit line carrying the stable error code (the request id comes from the MDC).
        audit.atInfo().setMessage("request.rejected").addKeyValue("code", code).addKeyValue("status", status.value()).log();
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
