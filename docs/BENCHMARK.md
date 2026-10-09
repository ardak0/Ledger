# Benchmark: pessimistic vs optimistic concurrency control

**Result in one line:** in this ledger, row locks (pessimistic) delivered higher throughput than version checks with
retry (optimistic) in every scenario measured, and the gap widened as contention rose. Optimistic also abandoned
transfers under heavy contention; pessimistic never did.

Reproduce: `./mvnw verify -Pbenchmark` (about 6 minutes; writes `target/benchmark/results.md`). The benchmark is
excluded from the normal build.

## Environment

- Date: 2026-10-09
- Java: 21.0.4, OS: Mac OS X 26.6.2, 8 logical cores (Apple M3)
- Docker: 29.1.3, VM with 8 CPUs and 7837 MiB
- Postgres: PostgreSQL 17.11 in a container; fsync=on, synchronous_commit=on, shared_buffers=128MB
- Optimistic retry budget: 20 attempts, full-jitter backoff 0.2ms doubling to a 20ms cap

Per cell: median of 5 runs (min-max in brackets). 20 threads, 20 connections. Latency is per successful transfer, retries included.

| Scenario | Strategy | Throughput (tx/s) | p50 (ms) | p99 (ms) | Conflicts per posted tx | Abandoned |
|---|---|---|---|---|---|---|
| 2 accounts (extreme) | pessimistic | 1,092 [951-1,142] | 17.9 | 30.5 | n/a | 0 |
| 2 accounts (extreme) | optimistic | 551 [468-559] | 3.6 | 193.4 | 4.53 | 501 of 10,000 (5.01%) |
| 10 accounts (high) | pessimistic | 1,919 [1,297-2,023] | 4.3 | 54.2 | n/a | 0 |
| 10 accounts (high) | optimistic | 869 [743-976] | 8.1 | 178.2 | 2.77 | 315 of 50,000 (0.63%) |
| 50 accounts (headline) | pessimistic | 2,599 [2,436-2,940] | 6.2 | 25.3 | n/a | 0 |
| 50 accounts (headline) | optimistic | 1,811 [1,284-1,829] | 6.9 | 69.9 | 0.97 | 0 |
| 500 accounts (low) | pessimistic | 2,419 [1,832-3,014] | 7.0 | 21.9 | n/a | 0 |
| 500 accounts (low) | optimistic | 1,994 [1,503-2,571] | 7.5 | 42.3 | 0.11 | 0 |
| 50 accounts, 50% via one hot account | pessimistic | 1,542 [1,322-1,699] | 2.9 | 53.8 | n/a | 0 |
| 50 accounts, 50% via one hot account | optimistic | 800 [654-837] | 2.7 | 192.3 | 2.84 | 1234 of 50,000 (2.47%) |

Throughput ratio, optimistic / pessimistic (median of medians):

- 2 accounts (extreme): **0.50x**
- 10 accounts (high): **0.45x**
- 50 accounts (headline): **0.70x**
- 500 accounts (low): **0.82x**
- 50 accounts, 50% via one hot account: **0.52x**


## How to read this

- **Throughput** is posted transfers per second over the whole run. **Conflicts per posted tx** is how many
  version checks failed (each one a discarded attempt) per transfer that eventually posted.
- **Abandoned** transfers exhausted the optimistic retry budget and failed with a clean 503 (nothing posted).
  Denominators differ because the 2-account scenario uses 2,000 transfers per run and the others 10,000.
- Every measured run was verified afterwards: balances sum to the funded total, each cached balance equals the
  sum of its entries, and exactly the reported number of transfers (each with two entries) exist. A run that
  failed those checks would have failed the benchmark.

## Findings

1. **Pessimistic had higher median throughput in all five scenarios.** In four of them (2, 10 and 50 accounts, and
   the hot-account case) the min-max ranges do not overlap, so the gap is larger than run-to-run noise. At 500
   accounts the ranges do overlap (1,832-3,014 vs 1,503-2,571 tx/s), so the 0.82x there is **not** a clear
   difference.
2. **Optimistic's cost tracks contention.** Conflicts per posted transfer rose from 0.11 (500 accounts) to 0.97
   (50) to 2.77 (10) to 4.53 (2). Each conflict discards a whole attempt (reads, a rolled-back transaction) and
   sleeps through a backoff.
3. **Optimistic gives up under heavy contention; pessimistic cannot.** With a budget of 20 attempts, 5.0% of
   transfers were abandoned at 2 accounts, 2.5% in the hot-account case and 0.6% at 10 accounts. At 50 or more
   accounts none were. A waiting transaction always eventually gets its lock, so pessimistic abandoned nothing.
4. **Median latency can mislead.** Optimistic's median latency is lower in the two most contended cases (3.6 ms
   vs 17.9 ms at 2 accounts), but those figures cover successful transfers only, so abandoned ones are excluded,
   and its p99 is about six times worse (193 ms vs 30 ms). Pessimistic turns contention into queueing, which is
   slow but predictable; optimistic turns it into variance.

## What this does not show

- **One machine, one run of the experiment.** An Apple M3 laptop where the JVM, the load generator and Postgres
  (inside Docker Desktop's VM) share 8 cores. Absolute numbers are not meaningful; the comparison is. Five
  repetitions per cell is enough to see large gaps, not to rank close ones.
- **Tuning was one-sided.** I measured the optimistic retry budget (10, 20, 50 attempts) and chose 20, but did not
  explore backoff parameters. Different backoff could narrow the gap. I do not claim it would close it.
- **The causes in finding 2 are my interpretation.** Wasted work and backoff are the likely explanation; I did
  not profile to confirm it. It also does not explain why optimistic trails slightly even at low contention.
- **Where optimistic usually wins was not tested.** The usual case for optimistic locking is a long gap between
  reading and writing (user think time, a remote call inside the transaction), or read-heavy workloads where
  holding locks is costly. A ledger transfer is one short transaction, so none of that applies here. That is a
  hypothesis about other workloads, not something these numbers support.

## Retry budget sensitivity (headline workload, 20 threads, 50 accounts, 10,000 transfers)

Measured while choosing the default: single runs on the `fsync=off` test container (so only the abandonment counts are meaningful, not the speed):

| Budget | Abandoned in the headline workload | Abandoned in the scarce-funds workload |
|---|---|---|
| 10 attempts | 7-11 of 10,000 | 3-4 |
| 20 attempts (default) | 0 | 1 |
| 50 attempts | 0 | 0, but slower |

## Conclusion for this project

Pessimistic locking with ordered acquisition stays the default (`ledger.locking.strategy=pessimistic`). The
optimistic variant is kept, tested against the same contracts, and selectable, as a reasoned alternative with
measured trade-offs. It is not the better choice for this workload.
