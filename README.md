# payment-authorization-service

[![ci](https://github.com/Vivek-Reddy7/payment-authorization-service/actions/workflows/ci.yml/badge.svg)](https://github.com/Vivek-Reddy7/payment-authorization-service/actions/workflows/ci.yml)
[![licence: MIT](https://img.shields.io/badge/licence-MIT-blue.svg)](LICENSE)

A card payment authorization API in **Java 21 and Spring Boot 4**. It authorizes
a card, records the decision, and lets that authorization be captured or voided
exactly once.

I built this to learn Spring Boot properly rather than from a tutorial, and I
picked payments as the domain because the correctness problems there are real:
retries must not charge twice, money must not be a floating point number, and
card data you hold is card data you can leak.

**Nothing here talks to a real card network.** The decision step is a stand-in
with explicit rules. What is real is the shape around it.

## Running it

Needs Java 21+. No database to install — the default profile runs entirely in
memory.

```bash
./mvnw spring-boot:run
```

```bash
./mvnw test
```

Against MySQL instead:

```bash
DB_PASSWORD=... ./mvnw spring-boot:run -Dspring-boot.run.profiles=mysql
```

## Try it

```bash
curl -X POST http://localhost:8080/api/v1/authorizations \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: order-1-attempt-1' \
  -d '{"amountMinor":10000,"currency":"USD","pan":"4242424242424242",
       "expiry":"2030-12","merchantReference":"order-1"}'
```

```json
{
  "id": "429d0b6e-b7ed-4f5d-b806-53003f92a8b6",
  "status": "APPROVED",
  "declineReason": null,
  "amountMinor": 10000,
  "currency": "USD",
  "cardLast4": "4242",
  "merchantReference": "order-1"
}
```

Send that **exact same request again with the same key** and you get the same
`id` back — one authorization, not two. Send it with the same key but a
different amount and you get a `409`.

| | |
|---|---|
| `POST /api/v1/authorizations` | Authorize. Requires `Idempotency-Key` |
| `GET /api/v1/authorizations/{id}` | Fetch one |
| `GET /api/v1/authorizations?status=APPROVED` | List, paged |
| `POST /api/v1/authorizations/{id}/capture` | Take the funds |
| `POST /api/v1/authorizations/{id}/void` | Release without taking |

Errors from the rules this service enforces itself (validation, not-found,
illegal state transitions, idempotency conflicts) are [RFC
7807](https://www.rfc-editor.org/rfc/rfc7807) problem documents, produced in
one place. Not yet true of every error Spring Boot can produce before
reaching that layer: a missing header, malformed JSON, an unknown query
value, or a bad path variable currently fall through to the framework's
default error body instead — and an over-length `Idempotency-Key` returns a
raw 500 rather than a 400. See [the project
analysis](docs/project-analysis.md).

## The four decisions worth explaining

**Money is a `long` of minor units, never a `double`.** `amountMinor: 1050` with
`currency: "USD"` is $10.50. Binary floating point cannot represent 0.1
exactly, so `0.1 + 0.2 != 0.3`, and in a payment system that is a reconciliation
break rather than a rounding curiosity. The currency travels with the amount
because a bare number is meaningless — 1050 JPY and 1050 USD differ by two
orders of magnitude, since JPY has no minor unit at all.

**The card number is never stored.** Only the last four digits and a SHA-256
fingerprint. The fingerprint is peppered with a server-side secret, and that
part is load-bearing: a PAN is 16 digits with a checksum, so the space of valid
card numbers is small enough to enumerate, and a leaked column of *unpeppered*
digests would be a leaked column of card numbers. Data you do not hold cannot
leak.

**Retries are safe by construction, including under real concurrency.** A
client that times out mid-request cannot know whether the card was
authorized, so it retries — and without an idempotency key the cardholder is
authorized twice. The stored record also fingerprints the request body, which
is what makes it honest: without that, a caller could authorize $10, reuse
the key for $10,000, and be handed the cached approval. Same key and same
body replays; same key and different body is refused.

**This was found broken under concurrency, and is now fixed.** An earlier
version of this service let 16 concurrent requests carrying the same key and
the same body produce **five** separate authorizations, not one. The cause:
`JpaRepository.save()` on `IdempotencyRecord`, which has a caller-assigned
`@Id` and no `@Version` field, calls `entityManager.merge()` rather than
`persist()` — an UPDATE-or-INSERT that silently overwrites which
authorization a key points at on a collision, instead of failing. The fix,
in `IdempotencyClaimer`: the authorization and its idempotency claim are now
written together with `persist()` (always a real `INSERT`) in one independent
transaction, so a genuine race produces exactly one committed pair and a
real, catchable failure on every other attempt. Proven by `IdempotencyConcurrencyTest`, which fires 16 concurrent identical
requests and asserts exactly one authorization results, and passes 10/10
runs. See [the project analysis](docs/project-analysis.md) for the full
diagnosis, including how the bug was found (a separate, ad hoc reproduction
against the pre-fix code, not this test).

**The state machine lives on the entity.** `APPROVED` can become `CAPTURED` or
`VOIDED`; everything else is terminal. The check sits on `Authorization` rather
than in the service, so no future caller can route around it. Capturing twice is
the worst thing this service could do, so it is a `409` with both the current and
requested status in the body — and it has its own test.

## Layout

```
api/          controllers, DTOs, the Luhn validator, one exception handler
domain/       entities, the status state machine, domain exceptions
service/      decision engine, card fingerprinting, orchestration
repository/   Spring Data JPA
db/migration/ Flyway, forward-only
```

Schema comes from Flyway and Hibernate is set to `validate`, never `ddl-auto`.
A guess that drops a column in a payments store is not something you recover
from. There are no down scripts for the same reason — fix forward with `V2`.

## Tests

36 tests, all green, no network and no database to install.

The decision engine is a pure function — it reads no clock and touches no
database, because "today" is an argument — so its rules are unit tested
exhaustively in milliseconds with no Spring context. The boundaries are the
point: a card is valid *through the end of* its expiry month, and the limit
check is tested on both sides of the `>` so nobody can quietly turn it into `>=`.

The API test runs the real stack — real binding, real validation, real Flyway
schema, real transactions. It is deliberately **not** transactional and not
rolled back between tests, because idempotency is entirely about what survives
between requests; a test that resets state would assert nothing about it. It
also asserts that the raw response body contains neither the PAN nor the string
`fingerprint`, which catches a field added later that no targeted assertion is
watching.

## How this compares

It does not compete with real payments infrastructure, and does not try to.

| Alternative | What it is | Why it is not the same thing |
|---|---|---|
| [Hyperswitch](https://github.com/juspay/hyperswitch) | PCI-compliant payments orchestration platform (Apache-2.0) | Routes to real processors; this project authorizes nothing real |
| [Kill Bill](https://github.com/killbill/killbill) | Open-source billing and payments platform (Apache-2.0) | Billing-first, with authorization as one small piece of a much larger system |
| [jPOS](https://github.com/jpos/jPOS) | Framework for ISO-8583 financial switches (AGPL-3.0) | The real protocol card networks speak, not a REST API |
| Stripe's idempotency design | Documented behaviour, not a repository | The reference this project's idempotency design follows |

What it offers instead is a small, readable implementation of the standard
patterns (minor-unit money, peppered card hashing, an entity-owned state
machine, idempotency keys) with the reasoning written down. The [project
analysis](docs/project-analysis.md) rates its novelty **Low** — intentionally,
since the goal was learning the patterns, not inventing new ones — and sets
out the strongest criticisms, including the concurrency defect above (now
fixed) and the others still open.

## What is deliberately missing

No authentication, no rate limiting, no real issuer integration, no partial
captures or refunds, no expiry of stale authorizations, no currency-specific
minor-unit enforcement (a `JPY` or `KWD` amount is accepted and limited the
same way a `USD` one is, despite the reasoning above about why that is
wrong). Those are the next things, not oversights — and this is a learning
project, so I would rather it be small and correct than broad and
hand-waved. The concurrency defect above is fixed; the error-handling gaps
and the unpeppered request-fingerprint hash in [the project
analysis](docs/project-analysis.md) are not on this list because they were
meant to already be solved, and are next.

## Licence

[MIT](LICENSE).
