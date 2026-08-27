# Engineering Decisions

## Assumptions

- The Pricing API is treated as a black box: only its documented contract
  (`docs/pricing-api.openapi.yaml`) and its observed status codes are relied
  upon. No assumption is made about its internal implementation, uptime
  guarantees, or whether outages are short blips or multi-minute incidents.
- `amount` in `PriceQuoteResponse` is kept as a `String` at the HTTP/DTO
  boundary, matching the OpenAPI contract exactly (`type: string, example:
  "19.99"`). It is only converted to `BigDecimal` inside `OrderService`,
  where the order's business logic (unit price x quantity) actually needs a
  numeric type. This avoids the pricing client silently making formatting or
  locale assumptions on the provider's behalf.
- An order is considered "priced" only once a `200` response with a
  well-formed `PriceQuoteResponse` body has been received. A `200` HTTP
  response from *our own* `/api/orders` endpoint is never treated as proof
  that pricing succeeded — this was an explicit requirement from
  `CHANGE_REQUEST.md` (point 7).
- The in-memory repository from the starter project was kept. `CHANGE_REQUEST.md`
  explicitly leaves persistence technology open, and the exercise's scope
  guidance favors a small, coherent solution over infrastructure.
- Only one instance of the application runs at a time (no clustering). The
  background retry scheduler assumes it is the only writer competing with
  manual "Retry pricing" clicks.

## Important decisions and trade-offs

- **Persist before pricing.** `OrderService.create()` always saves the order
  with a final, stable ID *before* calling the Pricing API. If the Pricing
  API is unreachable, times out, or the JVM crashes mid-call, the order
  already exists with `PENDING_PRICING` status. This directly satisfies
  CR-002 point 2 (stable ID) and point 1 (store never needs to resubmit).

- **Two independent retry layers, on purpose:**
  - *Technical retry* (`PricingClient`, via `@Retryable`): handles brief,
    transient failures (a dropped connection, a single `5xx`) with a short
    in-process backoff — 3 attempts, 200ms initial delay, x2 multiplier
    (200ms, then 400ms). This is meant for blips of a few hundred
    milliseconds, not provider outages.
  - *Business retry* (`OrderService.retryPendingOrders()` +
    `PricingRetryScheduler`): re-attempts pricing for orders still in
    `PENDING_PRICING`, on a fixed delay (`pricing.retry.scheduler.fixed-delay-ms`,
    15s by default). This is meant for the multi-minute outages CR-002
    describes — an outage the technical retry layer is not designed to ride
    out. A manual "Retry pricing" button in the dashboard triggers the same
    code path on demand, for a support user who does not want to wait for
    the next scheduled tick.

- **Error classification drives the state machine.** `PricingClient` maps
  HTTP responses to a small exception hierarchy:
  - `404` -> `PricingProductNotFoundException` (permanent — the product will
    never price)
  - `400` -> `PricingBadRequestException` (permanent — our request is
    malformed; retrying without changing input will never help)
  - `5xx`, connection failures, timeouts, and unparseable `200` bodies ->
    `PricingUnavailableException` (transient)

  `OrderService.attemptPricing()` then maps permanent failures to
  `PRICING_FAILED` and transient failures to `PENDING_PRICING` (eligible for
  further retries). This distinction is the foundation of the whole
  resilience design — CR-002 explicitly leaves it open which failures count
  as which, so this is one of the assumptions I am making, not something the
  contract states outright.

- **`429 Too Many Requests` is currently treated as transient.** The OpenAPI
  contract documents `4XX` as a possible operational rejection separate from
  `400`/`404`, but does not name `429` specifically. In the current
  implementation, any status code that isn't `200`, `404`, `400`, or `5xx`
  (including `429`) falls through to the generic "unexpected status" branch,
  which throws `PricingUnavailableException` — i.e. it is retried. I
  considered treating `429` as a distinct case with a longer backoff (real
  rate limiters usually mean "back off more, not the same amount"), but
  decided the fixed-delay scheduler is not sophisticated enough to honor a
  `Retry-After` header meaningfully, so I left it in the generic transient
  bucket for this exercise rather than half-implementing rate-limit-aware
  backoff.

## Pricing API observations that influenced the design

- The contract models `amount` as a `string`, not a `number` — a strong
  signal that the provider does not want callers doing float arithmetic on
  it directly, likely to avoid floating-point rounding issues with money.
  This is why the pricing client never touches `BigDecimal` conversion
  itself; that responsibility stays in `OrderService`, closer to where the
  business calculation actually happens.
- The contract explicitly documents both `4XX` and `5XX` as generic
  "the provider may reject/fail" responses, in addition to the specific
  `400`/`404` cases. This told me the provider itself does not commit to a
  fully enumerated set of failure codes, which is why `PricingClient` has an
  explicit fallback branch for "unexpected status" rather than assuming only
  the documented codes will ever appear.
- `validUntil` on `PriceQuoteResponse` suggests the provider expects quotes
  to expire. The current implementation does not act on this field (an order
  is confirmed with whatever price it received, permanently) — see Known
  limitations.

## Browser UI and operational feedback

- **How does the HTML/CSS screen communicate pricing state and recovery
  behavior?** Every order row shows a status badge with an explicit text
  label — "CONFIRMED", "AWAITING PRICE", or "PRICING FAILED" — not just a
  color, so the status is understandable without relying on color
  perception (a WCAG/accessibility requirement called out in the README).
  Alongside the badge, the table shows the number of pricing attempts made
  so far and, when applicable, the `failureReason` text captured from the
  last failed attempt. Any order that is not yet `CONFIRMED` gets a "Retry
  pricing" button, implemented as a plain HTML form `POST` (no JavaScript),
  so a support user can force an immediate retry instead of waiting for the
  next scheduler tick.

- **What information did you deliberately expose or hide from a
  store/support user?** Exposed: order status, price (when available),
  number of attempts, and a human-readable failure reason — everything a
  support user needs to explain to a customer why an order isn't confirmed
  yet, or to decide whether to retry. Deliberately hidden: raw HTTP status
  codes, stack traces, and the internal retry/backoff configuration
  (attempt counts and delay values). A support user does not need to know
  the Pricing API returned a `503` versus a connection timeout — both just
  mean "temporarily unavailable" — nor do they need to see internal
  implementation detail like retry scheduling. The `failureReason` string
  is deliberately the exception message, kept free of stack traces, so it
  stays safe to show on a screen a non-developer will read.

## Known limitations

- `attemptPricing()` parses `quote.amount()` with `new BigDecimal(...)`
  without a try/catch. If the Pricing API ever returned a `200` with a
  syntactically invalid `amount` (e.g. `"nineteen ninety-nine"`), this would
  throw an unhandled `NumberFormatException` instead of being classified as
  a pricing failure. In practice the observed provider never did this, but
  it's a gap I would close before production.
- The business-level retry scheduler has no maximum attempt count or
  backoff of its own — an order can stay in `PENDING_PRICING` and be
  retried every 15 seconds indefinitely if the Pricing API never recovers.
  There is no "give up" state or alerting.
- `validUntil` on a price quote is not enforced; a confirmed order keeps its
  price forever, even past the quote's stated expiry.
- The in-memory repository means all orders are lost on application
  restart, and the solution cannot run as more than a single instance
  (no shared state, no locking between the scheduler and manual retries).
- The manual "Retry pricing" endpoint has no protection against a user
  clicking it many times in quick succession while a scheduled retry is
  also in flight for the same order.

## What I would change for production

- Replace the in-memory repository with a real persistent store, and add
  optimistic locking (e.g. a version column) so the scheduler and a manual
  retry can never race on the same order.
- Give the business-level retry a maximum attempt count and exponential
  backoff, with a terminal "needs manual intervention" state and some form
  of alerting, instead of retrying forever on a fixed interval.
- Add a circuit breaker around `PricingClient` so a sustained outage stops
  hammering the provider with a request every scheduler tick across every
  pending order.
- Respect `Retry-After` on `429` responses instead of treating every
  non-specified status the same way.
- Add basic metrics (pricing success rate, provider latency, count of
  orders stuck in `PENDING_PRICING`) so an operations team can see the
  health of the integration without reading logs.
- Add authentication/authorization to the retry endpoint and the dashboard
  in general — currently anyone who can reach the app can retry any order.

## AI-assisted work and validation

I used Claude as a coding assistant throughout this exercise — for
implementing the pricing exception hierarchy, `PricingClient`,
`PricingRetryScheduler`, the dashboard updates, and the automated test
suite. I did not accept generated code without review; in particular:

- I validated every generated file against the actual `OrderService`/
  `PricingClient` code before accepting it, checking that mocked behavior in
  tests matched real method signatures and exception types.
- While reviewing the retry test suite, a test run surfaced a confusing
  failure: `PricingClientRetryTest` expected a transient-failure exception
  but got a permanent one instead. Investigating it, I found a real
  test-isolation bug — a test that mutated the shared embedded HTTP
  server's request handler without resetting it, which made a later test's
  outcome depend on JUnit's (unspecified) method execution order. I asked
  the assistant to fix it so the server's handler is never mutated after
  startup, then re-ran the suite until it passed regardless of order.
- I ran `mvn test` locally after every batch of generated tests rather than
  trusting that generated code compiles or passes correctly, and required
  each test to demonstrate a specific behavior I could explain, not just
  "add more tests."
