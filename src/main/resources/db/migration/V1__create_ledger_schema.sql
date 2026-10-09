-- Double-entry ledger schema.
--
-- Sign convention: entry.amount_minor is the *change to the account's balance*.
--   positive = the account gains money (credit in the balance-delta sense)
--   negative = the account loses money
-- A transfer is a set of entries whose amounts sum to zero: money moves, it is never created.
--
-- Money is BIGINT minor units. Every currency in the allowlist below has 2 decimal places, so
-- "minor unit" means the same thing everywhere. Adding JPY (0) or BHD (3) means adding an
-- exponent per currency first -- deliberately not done in v1.

CREATE TABLE account (
    id            BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    currency      TEXT        NOT NULL,
    -- System accounts (e.g. a funding source) may go negative; nothing else may.
    -- Must never be settable through the public API, or any client could mint money.
    is_system     BOOLEAN     NOT NULL DEFAULT FALSE,
    -- Performance cache. entry is the source of truth; reconciliation proves they agree.
    balance_minor BIGINT      NOT NULL DEFAULT 0,
    -- Used by the optimistic-locking variant. BIGINT because a hot account is updated by
    -- every transfer and a 32-bit counter would overflow.
    version       BIGINT      NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT account_currency_allowed CHECK (currency IN ('USD', 'EUR', 'GBP', 'TRY')),
    -- Last line of defence against overdraft, independent of any Java check.
    CONSTRAINT account_balance_non_negative CHECK (is_system OR balance_minor >= 0),
    -- Redundant with the PK, but a foreign key must reference a unique constraint. This one
    -- lets entry prove "my currency equals my account's currency" via a composite FK.
    CONSTRAINT account_id_currency_key UNIQUE (id, currency)
);

CREATE TABLE transfer (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idempotency_key TEXT        NOT NULL,
    -- SHA-256 of the canonical request (from, to, amount, currency), not of the raw body bytes.
    request_hash    BYTEA       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT transfer_idempotency_key_key UNIQUE (idempotency_key),
    CONSTRAINT transfer_idempotency_key_length CHECK (char_length(idempotency_key) BETWEEN 1 AND 255),
    CONSTRAINT transfer_request_hash_length CHECK (octet_length(request_hash) = 32)
);

CREATE TABLE entry (
    id           BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transfer_id  BIGINT      NOT NULL REFERENCES transfer (id),
    account_id   BIGINT      NOT NULL,
    amount_minor BIGINT      NOT NULL,
    currency     TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT entry_amount_non_zero CHECK (amount_minor <> 0),
    CONSTRAINT entry_account_currency_fk
        FOREIGN KEY (account_id, currency) REFERENCES account (id, currency)
);

-- Statement queries: "entries for account X after id Y", i.e. keyset pagination.
CREATE INDEX entry_account_id_id_idx ON entry (account_id, id);
-- The balance trigger and transfer lookups read all entries of one transfer.
CREATE INDEX entry_transfer_id_idx ON entry (transfer_id);

-- ---------------------------------------------------------------------------------------------
-- Invariant: every transfer has entries on >= 2 distinct accounts, in exactly one currency,
-- summing to zero.
--
-- This runs as a DEFERRED constraint trigger, so it is evaluated at COMMIT, when all of the
-- transaction's entries exist. An immediate trigger would fire after the first INSERT, see a
-- lone non-zero entry, and reject every transfer.
--
-- It is attached to BOTH tables because each covers a gap the other cannot see:
--   * on transfer: catches a transfer written with no entries at all (no entry row is
--     inserted, so an entry-only trigger never fires).
--   * on entry:    catches a stray entry appended to a transfer committed earlier (the
--     transfer row is not rewritten, so a transfer-only trigger never fires).
--
-- sum() over BIGINT returns NUMERIC in Postgres, so the check itself cannot overflow.
-- Caveat: a trigger stops application bugs and other services; anyone who owns the table (or is
-- superuser) can still disable it. Against that, the fix is privileges, not triggers.
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION assert_transfer_balanced() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    v_transfer_id BIGINT;
    v_accounts    BIGINT;
    v_currencies  BIGINT;
    v_sum         NUMERIC;
BEGIN
    IF TG_TABLE_NAME = 'transfer' THEN
        v_transfer_id := NEW.id;
    ELSE
        v_transfer_id := NEW.transfer_id;
    END IF;

    SELECT count(DISTINCT account_id), count(DISTINCT currency), COALESCE(sum(amount_minor), 0)
      INTO v_accounts, v_currencies, v_sum
      FROM entry
     WHERE transfer_id = v_transfer_id;

    IF v_accounts < 2 OR v_currencies <> 1 OR v_sum <> 0 THEN
        RAISE EXCEPTION 'transfer % violates double-entry invariant: accounts=%, currencies=%, sum=%',
            v_transfer_id, v_accounts, v_currencies, v_sum
            USING ERRCODE = 'check_violation', CONSTRAINT = 'transfer_double_entry';
    END IF;

    RETURN NULL; -- return value of an AFTER trigger is ignored
END;
$$;

CREATE CONSTRAINT TRIGGER entry_transfer_balanced
    AFTER INSERT ON entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transfer_balanced();

CREATE CONSTRAINT TRIGGER transfer_has_balanced_entries
    AFTER INSERT ON transfer
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_transfer_balanced();

-- ---------------------------------------------------------------------------------------------
-- Append-only: a posted transfer and its entries never change. Corrections are new transfers
-- that reverse the old one. (account is deliberately mutable: balance_minor and version change.)
-- ---------------------------------------------------------------------------------------------
CREATE FUNCTION forbid_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% on % is not allowed: ledger tables are append-only', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER entry_append_only
    BEFORE UPDATE OR DELETE ON entry
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE TRIGGER transfer_append_only
    BEFORE UPDATE OR DELETE ON transfer
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
