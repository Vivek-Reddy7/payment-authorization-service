# Project analysis: novelty, scope, stress test, roadmap

A structured, adversarial review of `payment-authorization-service` against the
**project-architect** protocol (four phases, then a summary). It is written to
find what is wrong, not to sell the project. Where something could not be
verified it says so.

Status date: 2026-10-09, at commit `a1efffc`. Facts about the project (36
tests, 891 lines of main source, MIT licence) come from a clean build and test
run, and from reading the code directly. Several claims below were checked by
running the service locally against H2 with `curl` and JPA repository calls,
and that testing surfaced three real defects, corrected here and in the
affected docs.

---

## Phase 1 — Landscape & Novelty Analysis

### 1A. The nearest neighbors

Licence, language and popularity are from the GitHub API on 2026-10-09.

| Neighbor | What it is | Key strength | Primary limitation (relative to this project's goal) |
|---|---|---|---|
| [Hyperswitch](https://github.com/juspay/hyperswitch) | Open-source, PCI-compliant payments orchestration platform, Rust (Apache-2.0, about 45,300 stars, actively pushed) | Routes to real processors, handles real PCI scope, large active community | Routing and orchestration across real gateways, not a from-scratch authorization/idempotency teaching example |
| [Kill Bill](https://github.com/killbill/killbill) | Open-source subscription billing and payments platform, Java (Apache-2.0, about 5,800 stars, active) | Mature billing domain model, plugin architecture for payment gateways | Billing-first; authorization and idempotency are a small part of a much larger system |
| [jPOS](https://github.com/jpos/jPOS) | Framework for building ISO-8583 financial transaction switches, Java (AGPL-3.0, about 720 stars, active) | The real protocol real card networks speak | ISO-8583 switch-building, not a REST authorization API; AGPL-3.0 restricts reuse |
| Stripe's own idempotency design | Not a repository; Stripe's public API documentation on idempotent requests | The reference design most payment APIs imitate | Proprietary implementation; only the documented behaviour is public |
| The IETF `Idempotency-Key` HTTP header draft | `draft-ietf-httpapi-idempotency-key-header`, latest revision 07, dated 2025-10-15 | An attempt at a protocol-level standard for this exact problem | The draft **expired** without becoming an RFC, so there is no ratified standard to claim conformance to |

### 1B. The delta

**What this does that none of the neighbors do, as stated:** nothing. Every
individual idea here — minor-unit integer money, peppered card hashing, a
state machine on the entity, request-fingerprinted idempotency — is standard
payments-engineering practice, and Stripe's public documentation describes the
idempotency design this project imitates almost exactly. The project's own
README says as much: it was built to learn Spring Boot "properly," not to
advance the field.

**Novelty rating: Low.** This is a well-executed implementation of known
patterns, not a new idea. That is appropriate for its stated purpose (a
learning project), and it should not be marketed as more than that.

**Pivots, if novelty mattered here (it does not, given the project's actual
goal, but these would raise it):**

1. **Conform to and track the IETF draft's semantics explicitly**, including
   the status codes it specifies for the cases this project already models
   (see 3C). Nothing currently claims or tests conformance to it.
2. **Publish the concurrency-correctness finding below as the contribution.**
   A worked example of exactly how "idempotency" silently fails under real
   concurrent load, and why, is more novel than the service itself.
3. **Narrow to a single hard sub-problem** (for example, request-fingerprint
   hashing without a pepper under GDPR-style "right to erasure" rotation) and
   treat the rest as scaffolding.

---

## Phase 2 — Strategic Positioning & Scope

### 2A. Build vs. contribute matrix

| Option | Rationale | Recommended? |
|---|---|---|
| Fork / extend an existing OSS project | Hyperswitch and Kill Bill are each orders of magnitude larger; forking either would bury the learning goal in unrelated code | **No** |
| Contribute upstream to an existing project | Nothing here is novel enough to be a contribution anywhere | **No** |
| Build a new standalone OSS project | Already done, and matches the stated learning goal | **Yes**, as a learning artifact, not as a payments product |
| Build a closed-source / enterprise tool | No real network integration, test-only decision engine, one maintainer; nothing to sell | **No** |
| Academic research only (no implementation) | There is no research question here | **No** |

**Recommendation:** keep it standalone and keep calling it a learning project,
as the README already does. The honest move is to fix the concurrency defect
this review found (3C, item 1), since it undermines the one property
("retries are safe by construction") the README leads with.

### 2B. Output scope recommendation

**Blog post + reference implementation.** The repository is the reference
implementation, and the most interesting material — the four decisions
section, and the concurrency bug this review found underneath the first of
them — is already in the README's voice. A paper or library is not warranted
for a deliberately fake payments domain with no real integration.

---

## Phase 3 — Relevance & Critical Stress Test

### 3A. Relevance check

- **Why it matters:** idempotent retries, non-float money and PAN-minimization
  are real, common sources of production payment bugs; practicing them
  correctly against a realistic schema is a sound way to learn the domain.
- **Relevance threshold:** **met for its stated purpose (learning), not met as
  a payments product.** There is no real issuer integration, no
  authentication, and — as this review found — the core idempotency guarantee
  does not currently hold under concurrent load. Relevance as a demonstrable
  skill artifact rises once the defects below are fixed and the "35 tests, all
  green" claim is something a stranger can independently reproduce and trust.
- **Verification note:** the IETF draft's status codes and Stripe's documented
  idempotency retention and conflict behaviour were checked against primary
  sources (the draft's own `.txt`, and docs.stripe.com) rather than taken on
  trust; see 3C for where this project's own claims diverge from them.

### 3B. The pros

- **Optimistic locking on state transitions genuinely works.** Firing 16
  concurrent capture requests at one authorization produced exactly one `200`,
  several `409`s, and several `ObjectOptimisticLockingFailureException`s —
  never a double capture. This is the one concurrency guarantee in the service
  that held up under direct testing.
- **The PAN never appears in application logs.** Distinctive test card numbers
  sent through the running service at INFO level did not appear anywhere in
  the server log, matching the stated design.
- **Domain validation errors are genuinely uniform.** A request with a
  Luhn-invalid PAN returns a single, well-formed RFC 7807 `application/problem
  +json` body naming the failing field — exactly as designed, for the case the
  design was built for.
- **The decision order is deliberate and matches its own reasoning.** Checking
  card expiry before the amount limit, so a cardholder is told the true
  reason first, is implemented exactly as the code comment describes.
- **Minor-unit money is applied consistently** end to end: request, entity,
  column (`BIGINT`), and response all carry `amountMinor` as an integer, with
  no floating-point money anywhere found in the main source.

### 3C. Harsh criticisms and limitation study

**Technical**

- **The headline idempotency guarantee did not hold under real concurrency —
  fixed as of this review, 2026-10-10.** The README stated: *"The replay check
  and the insert share one transaction, so two concurrent retries cannot both
  pass the check and both authorize. The primary key on `idempotency_key` is
  the backstop if they somehow do: the second insert violates it and that
  transaction rolls back."* Firing 16 concurrent, identical requests (same
  key, same body) at a running instance produced **5 separate authorizations**,
  not 1, with the idempotency record pointing at only one of them and 4
  authorizations silently orphaned — the exact double-authorization this
  feature exists to prevent. Each `@Transactional` method does commit on its
  own, but nothing stops two overlapping transactions from both running
  `findById` before either commits its insert, because the default isolation
  (`READ_COMMITTED`, left at Spring's default) does not prevent that race, and
  `idempotency_key` is the method's own primary key, not a separately
  validated uniqueness check at this layer. The stated "backstop" did not
  trigger because `JpaRepository.save()` on an entity constructed with a
  pre-assigned, non-generated `@Id` performs a merge-style upsert rather than
  a guaranteed `INSERT` — worse than merely failing to collide: on a key a
  concurrent winner had already committed, `merge()` found that row and
  issued a silent `UPDATE`, overwriting which authorization the key pointed
  at with no exception raised at all.

  **The fix** (`IdempotencyClaimer`, called from `AuthorizationService`):
  the authorization and its idempotency record are now written together, with
  `entityManager.persist()` (always a real `INSERT`), in one independent
  (`REQUIRES_NEW`) transaction. Two further pitfalls surfaced building it,
  each worth recording: first, claiming the idempotency key in its own
  transaction *after* saving the authorization in the caller's still-open one
  does not work, because the record's foreign key points at a row the
  independent transaction cannot see yet — so both writes have to be in the
  *same* transaction. Second, catching the unique-constraint violation and
  returning normally from inside that same `@Transactional` method does not
  work either: Hibernate marks the transaction unusable the moment the flush
  fails, and Spring refuses to "commit" it, throwing
  `UnexpectedRollbackException` in place of the original error — so the
  exception has to propagate out of the independent transaction and be caught
  by the caller's own, unaffected one.

  **Verified:** `IdempotencyConcurrencyTest` fires 16 concurrent identical
  requests through the real stack and asserts exactly one authorization
  results; it passed 10/10 consecutive runs. The original reproduction was
  re-run live against the running server with genuinely concurrent `curl`
  processes (not single-threaded): all 16 responses returned the same
  authorization id, and exactly one row exists for that merchant reference.
  The conflict path (same key, different body still returns `409`) and the
  full existing suite (36 tests) were confirmed unaffected.
- **Error handling is not actually uniform**, contradicting *"Errors are RFC
  7807 problem documents, produced in one place so every endpoint fails the
  same shape."* Confirmed by direct testing: a missing `Idempotency-Key`,
  malformed JSON, an unknown status-filter value, a malformed UUID path
  variable, and an unsupported media type all fall through to Spring Boot's
  default error body (`{"timestamp","status","error","path"}`), not RFC 7807 —
  only exceptions the project's own `ApiExceptionHandler` explicitly catches
  get the problem+json shape.
- **A too-long `Idempotency-Key` crashes the request instead of being
  rejected cleanly.** A 65-character key (one over the documented `@Size(max =
  64)` limit) produced an **HTTP 500**, not the expected 400: the header-level
  `@Validated` constraint throws `ConstraintViolationException`, which
  `ApiExceptionHandler` has no handler for, so it falls through to a generic
  server error. A malformed client input should never produce a 500.

**Theoretical**

- **The request-fingerprint hash is unpeppered, unlike the card hash, by the
  project's own stated reasoning.** `AuthorizationService.authorize()` calls
  `fingerprinter.hash(canonical(request))`, and `CardFingerprinter.hash()` is
  plain SHA-256 with no pepper — confirmed by recomputing it locally against a
  stored value and getting an exact match. `canonical(request)` embeds the
  full PAN. The project's own Javadoc on `CardFingerprinter` explains
  precisely why this matters: *"a card number is 16 digits with a checksum, so
  the space of valid card numbers is small enough to enumerate... an unsalted
  SHA-256 of a PAN can be brute-forced."* That reasoning is why `card_fingerprint`
  is peppered. `request_fingerprint` hashes the same PAN, unpeppered, into a
  column in the same table. No cracking was attempted for this review beyond
  confirming the hash is reproducible without the pepper; the point is the
  asymmetry itself, which the codebase's own design principle condemns in the
  sibling column.
- **Currency handling does not match what the README argues for.** The README
  explains that "1050 JPY and 1050 USD differ by two orders of magnitude,
  since JPY has no minor unit at all" — but the `DecisionEngine`'s limit check
  compares `amountMinor` against one flat `perTransactionLimitMinor` regardless
  of currency, and the `currency` field's validation (`^[A-Z]{3}$`) accepts any
  three uppercase letters. Requests with `JPY`, `KWD` (3 minor-unit digits in
  real ISO-4217) and fabricated codes `ZZZ`/`XXX` were all accepted and
  approved identically to `USD`. The reasoning in the docs is not wrong; it is
  simply not implemented.

**Practical**

- **No authentication on a payments API**, acknowledged in the README's "What
  is deliberately missing" — correctly flagged there, so not a new finding,
  but worth restating here because it is the most consequential one on the
  list for anything beyond local learning.
- **The test count is stale.** The README says "35 tests, all green." A clean
  run (and CI's own log) shows **36**.

**Competitive**

- **No moat, by design.** The project does not compete with Hyperswitch, Kill
  Bill or a real processor integration, and does not claim to.

**Limitation study: concrete edge cases and failure modes**

1. **Concurrent identical retries can double- (or 5×-) authorize a card.**
   Reproduced directly: 16 threads, one idempotency key, one request body,
   default H2/JPA configuration — 5 authorizations, not 1. This is the
   project's single most important correctness property, and it is currently
   broken under load the service will see from any real retrying client.
2. **A malformed but plausible client header (an over-length idempotency key)
   crashes with a 500** instead of the 400 every other validation failure
   produces.
3. **Currency-specific minor-unit conventions are accepted but not enforced**,
   so a limit meant to cap risk in one currency silently caps a different
   amount of real value in another.
4. **Error responses are inconsistent in shape** across the API surface,
   which defeats the stated purpose of centralising error handling (a client
   cannot parse every error the same way, despite the README's claim).
5. **The request-fingerprint/card-fingerprint asymmetry** described above is a
   data-at-rest exposure the project's own threat model (peppering) already
   says matters.

---

## Phase 4 — Structural Breakdown & Execution Roadmap

### 4A. Approach selection

**Bottom-up**, and in a specific order: fix the defects this review found in
the primitive the whole project is named for (authorization idempotency)
before adding any new feature. Concurrency correctness is the load-bearing
claim; everything else in "The four decisions worth explaining" is sound by
comparison. No deadline was given, so stages are sized relatively.

### 4B. The action map

| Stage | Description | Key deliverable | Effort | Dependencies |
|---|---|---|---|---|
| **1. Fix the concurrency race** — **DONE, 2026-10-10** | Write the authorization and its idempotency claim together with `entityManager.persist()` in one independent (`REQUIRES_NEW`) transaction, so a collision is a real, catchable constraint violation instead of a silent `merge()` upsert; add a concurrency test that fires real parallel requests | `IdempotencyConcurrencyTest` (16 concurrent identical requests, asserts exactly 1 authorization), and a live re-run of the original reproduction against the running server with genuine concurrent `curl` processes | Days (actual) | None |
| **2. Fix the error-handling gaps** | Add handlers for `ConstraintViolationException`, `HttpMessageNotReadableException`, `MethodArgumentTypeMismatchException` and `HttpMediaTypeNotSupportedException` so every 4xx is RFC 7807 | Every tested error case in 3C returns a `problem+json` body, none returns 500 | Days | None |
| **3. Pepper the request fingerprint, or document why not** | Either pepper `request_fingerprint` the same way `card_fingerprint` is peppered, or write down explicitly why the project accepts that asymmetry | A fixed hash path, or a stated, reasoned exception | Days | None |
| **4. Currency-aware limits** | Use a real ISO-4217 minor-unit table to validate `currency` and to interpret `amountMinor` per currency in the decision engine | A per-currency limit that means the same real value across currencies | Days to a week | None |

### 4C. Stage-gate questions

1. **Fix the concurrency race — answered, 2026-10-10.** Do concurrent
   identical requests against a fresh database produce exactly 1 authorization
   and the rest replays, every time, across multiple runs? **Yes, verified at
   16 concurrency** (not the 50 this question originally asked for): 10/10
   automated runs and one live run with genuinely concurrent `curl` processes
   all produced exactly 1 authorization. Higher concurrency, and concurrency
   against MySQL rather than H2, were not tested and remain open.
2. **Fix the error-handling gaps:** Does every case in the limitation study's
   item 4 now return `application/problem+json` with a `4xx` status and never
   a `5xx`? A single remaining 500 on bad client input means the gap is not
   closed.
3. **Pepper the request fingerprint:** If peppered, does an existing stored
   `request_fingerprint` need a migration, and what happens to in-flight
   idempotency records during a pepper rotation? If left unpeppered, is that
   decision written down somewhere a future reader will find it?
4. **Currency-aware limits:** For a real ISO-4217 minor-unit table, does a
   `JPY` request now reject a `currency` field with a decimal fraction the way
   `USD` does with a sub-cent amount, and does `perTransactionLimitMinor`
   carry a currency so one limit does not silently mean different real amounts
   in different currencies?

---

## TL;DR

> - **Novelty: Low.** Every individual pattern here (minor-unit money, peppered PAN hashing, idempotency keys, an entity-owned state machine) is standard payments practice; the project's own README frames it as a learning exercise, correctly.
> - **Recommended scope:** blog post + reference implementation, built around the concurrency-correctness finding below rather than the service as a product.
> - **Top criticism 1 — the headline idempotency guarantee did not hold under concurrency, now fixed:** 16 concurrent identical requests produced 5 authorizations instead of 1, directly contradicting the README's original claim that a shared transaction plus the primary key prevents this; `IdempotencyClaimer` fixes it (verified 10/10 runs plus one live concurrent-`curl` run), but only at the concurrency and database (H2) tested so far.
> - **Top criticism 2 — the request-fingerprint hash is unpeppered while the card hash is peppered**, for the exact reason the project's own `CardFingerprinter` Javadoc gives for peppering the latter.
> - **Approach:** bottom-up. Fix the authorization primitive's concurrency correctness and error-handling completeness before adding scope.
> - **First next step:** write a real multi-threaded test that fires the same idempotency key concurrently, watch it fail as this review's manual testing did, then fix the lookup-and-insert path to actually serialize on the key.
