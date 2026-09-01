# StockPulse — Architecture Decision Record

Each entry follows Context → Options → Decision → Tradeoffs. Entries were written as the
decisions were made, which is why some record things I later had to correct.

Stack: Java 21, Spring Boot 3.5, Spring Data JPA, H2 in-memory, Groq (`openai/gpt-oss-20b`), React 18 + Vite,
SSE streaming on the on-demand pricing path (ADR-8).

---

## ADR-1 — Where commerce logic lives

### Context

The obvious shape for a Spring CRUD service is controller → service → repository. Applied literally
here, `ProductService` would end up owning price arithmetic, reorder sizing, LLM prompt
construction, response parsing, bounds validation, event publishing, and persistence. The brief
asks directly what boundary prevents exactly that accumulation, and it makes the largest single
rubric line "interface before implementations; HTTP and async callers share contracts" — so the
answer needed to be structural, not a naming convention.

There is a second forcing function. Two callers need identical commerce behaviour: the on-demand
HTTP endpoints and the async agentic loop. Anything that lives inside a controller, or that depends
on a request-scoped concern, is unreachable from the loop.

### Options

1. **Three layers, logic in the service.** Fewest files. But `SuggestionService` becomes the place
   where every future rule lands, and unit-testing a pricing rule requires a Spring context and a
   database because the rule can only be reached through a transactional service.
2. **Logic in the entities.** `Product.recommendPrice()` reads well for the rule-based case and is
   trivially testable. It collapses as soon as pricing needs an LLM: the entity would need a
   gateway, a prompt factory, and a parser injected into it, and a JPA entity is the wrong place to
   own an outbound HTTP call.
3. **A fourth layer — a dedicated engine behind an interface.** Advisors receive a prepared value
   object and return a recommendation. Services orchestrate; entities keep invariants; advisors hold
   the commerce reasoning.

### Decision

Option 3, with responsibilities split four ways:

- `api/` — HTTP only. Deserialise, delegate, serialise. No rules.
- `service/` — orchestration and transaction boundaries. Loads entities, calls an advisor, persists
  the result, publishes events. Contains no pricing or reorder arithmetic.
- `engine/` — `PricingAdvisor` and `ReorderAdvisor` plus their implementations. The commerce logic.
- `domain/` — entities owning their own invariants. `Product.recordSale()` reconciles the
  `OUT_OF_STOCK` status so it can never drift out of sync with `stockLevel`, and
  `Suggestion.decide()` applies the accept side effect so no caller can persist a decision while
  forgetting to move the price.
- `repository/` — Spring Data JPA and Specifications.

The load-bearing detail is `CommerceContext`, the advisor input. It carries the product, the peer
average velocity, and the trigger reason, with derived values (days of cover, velocity ratio,
margin) computed up front. Because advisors receive it rather than a repository, every advisor —
including the LLM one — is a pure function of its input. `RuleBasedAdvisorTest` and
`PromptDifferentiationTest` run with no Spring context and no database.

Entities keep behaviour, but only behaviour that is about their own consistency. The distinction I
settled on: if getting it wrong would corrupt the record, it belongs on the entity; if it is a
commercial judgement, it belongs in the engine.

### Tradeoffs

More indirection than this feature set strictly needs — five packages and two interfaces to move a
number. Reading the flow of a single request means opening four files. `CommerceContext` is also a
guess about what future advisors will need; a competitor-aware strategy needing per-SKU competitor
history would have to widen it, touching every implementation's compile surface even though only
one uses the new field. I accepted that because the alternative — advisors reaching into
repositories themselves — is what makes commerce logic untestable in the first place.

---

## ADR-2 — Split pricing and reorder contracts rather than one unified advisor

### Context

The brief offers a genuine fork: one `CommerceAdvisor` returning both a price and a reorder
quantity from a single LLM call, or two contracts with independent calls. A unified call halves
token spend and latency and lets the model reason about both decisions with one view of the
product. Split calls cost twice as much and allow independent failure handling.

### Options

1. **Unified `CommerceAdvisor`** returning a combined recommendation. One prompt, one call, one
   parse. Cheaper and faster. Internally consistent answers, since the same reasoning produces both
   numbers.
2. **Split `PricingAdvisor` and `ReorderAdvisor`.** Two prompts, two calls, two parses. Each can
   fail and fall back on its own.

### Decision

Split contracts.

The deciding argument is that the brief separately requires the inventory-low and demand-spike
pricing prompts to be genuinely different documents. That requirement already commits us to
per-situation prompt construction, so a unified contract would not actually collapse the prompt
work — it would produce one long prompt asking for two unrelated judgements, which is the shape
most likely to yield a mediocre answer to both.

Independent failure handling then comes almost free, and it matters more than the token cost. When
an LLM pricing call times out, a unified contract loses the reorder recommendation too, even though
reorder sizing is the more mechanical of the two and the one most likely to have succeeded. With
split contracts, `SuggestionService` falls back per suggestion type, and the `generatedBy` field
records which advisor produced each one — so a merchandiser can see that the price came from rules
while the reorder quantity came from the model.

Single-responsibility on the interfaces is a smaller but real third benefit: sprint 2's
`CompetitorAwareStrategy` is a pricing concern and has no business implementing a reorder method.

### Tradeoffs

Two LLM round trips per trigger instead of one, so roughly double the tokens and latency, and on a
free Groq tier that doubles the rate-limit pressure. It also permits incoherent pairs: the pricing
advisor can recommend a clearance discount while the reorder advisor recommends restocking heavily,
because neither sees the other's answer. For a human-approval workflow I judged that acceptable —
arguably useful, since it surfaces the tension for the merchandiser rather than hiding it behind
one model's arbitration. If this became auto-apply (sprint 3), the incoherence would be a real
defect and the unified contract would deserve another look.

---

## ADR-3 — Runtime strategy switching via a registry over a mutable config field

### Context

The active strategy must be switchable without a code change or a restart, and the switch has to
take effect on both the HTTP path and the async agentic loop. Constructor-injecting a single
`PricingAdvisor` fixes the choice at startup, so something has to resolve the implementation later
than bean wiring.

### Options

1. **`@Profile` or conditional beans.** Idiomatic, but profiles are fixed at startup. Fails the
   no-restart requirement outright.
2. **Inject `Map<String, PricingAdvisor>` at each call site** and pick by a config value. Works,
   but every caller repeats the lookup and the fallback logic, and each is free to get it wrong.
3. **A registry that owns the map**, indexes implementations by a `name()` on the interface, and
   re-reads the active name on every resolve.
4. **Spring Cloud Context `@RefreshScope`.** Purpose-built for this, but pulls in a dependency and
   a refresh endpoint for what is one string.

### Decision

Option 3. `CommerceAdvisorRegistry` receives `List<PricingAdvisor>` and `List<ReorderAdvisor>` from
Spring, indexes them by `name()`, and resolves `activePricingAdvisor()` by reading
`CommerceProperties.activeStrategy` on every single call. Switching is therefore a field write, and
because both callers resolve through the registry, one write changes behaviour on both paths at
once. `PATCH /admin/commerce-strategy` exposes it; `commerce.active-strategy` seeds it at boot.

Three details worth defending. Advisors are keyed by an explicit `name()` on the interface rather
than by Spring bean name, so renaming a class cannot silently invalidate a deployed configuration.
`activate()` rejects unknown names instead of accepting them, because the failure mode of a typo
would otherwise be a silent permanent fallback to rules — the system would look healthy while the
AI integration was switched off. And resolution falls back to the rule-based advisor when a
configured strategy has no implementation of one half, which is what lets `aiAdvisor` be active for
pricing before an AI reorder advisor exists.

Adding sprint 2's third strategy is: implement the interface, annotate `@Component`, done. No
existing file changes.

### Tradeoffs

`CommerceProperties.activeStrategy` is mutable shared state written by an HTTP request and read by
pool threads. It is a single non-volatile reference field, so a switch is not guaranteed visible to
other threads immediately — in practice it lands within milliseconds and a suggestion generated
microseconds after a switch by either strategy is equally valid, so I did not make it `volatile`
or an `AtomicReference`. That is a deliberate call, not an oversight. The switch is also
process-local and resets to the configured default on restart; with more than one instance you
would need the strategy in shared storage. Finally, the admin endpoint has no authentication, which
is fine for a hackathon and unacceptable in production.

---

## ADR-4 — LLM failure handling: fail loudly inside the advisor, fall back in one place

### Context

An LLM will time out, hit a quota, return prose instead of JSON, wrap JSON in markdown fences, and
occasionally propose $0.00 or $999,999. The async path must never silently drop a recommendation —
a rule-based suggestion is strictly better than nothing, because nothing means a merchandiser never
learns the product needed attention.

### Options

1. **Each advisor handles its own failures** and returns a rule-based answer internally. Callers
   need no error handling at all, but every advisor duplicates the fallback, and the returned
   recommendation quietly lies about its own origin.
2. **Advisors throw; one caller catches.** Fallback exists in a single place. Advisors stay honest
   about failure.
3. **Clamp bad values into range** rather than rejecting them.

### Decision

Option 2, with option 3 explicitly rejected for prices.

Every failure mode converges on one exception type. `LlmGateway` wraps transport problems —
`RestClientException` covers connect and read timeouts, 429 quota errors, and 5xx outages — plus
empty completions and unknown providers. `LlmJsonParser` raises the same type for absent, truncated,
or non-object output. The advisors raise it for out-of-bounds recommendations.
`SuggestionService.recommendPriceWithFallback` is the only place that catches, and it delegates to
`registry.fallbackPricingAdvisor()`.

Validation rejects rather than clamps. A price outside the ±50% guardrail, below zero, or below the
product's margin floor is a failure, not a recommendation needing adjustment — clamping $999,999
down to +50% would attach the model's confident reasoning to a number it never proposed, and that
reasoning is the thing a merchandiser reads. Bounds are stated in the prompt *and* enforced in
code: telling the model the guardrail makes a usable answer likely, validating afterwards makes a
bad one harmless.

Two things are deliberately tolerated instead of rejected, because they are cosmetic rather than
substantive: markdown fences and conversational preamble around otherwise valid JSON, which the
parser strips by finding the outermost balanced object while ignoring braces inside string
literals; and a missing or nonsensical `confidence`, which is clamped to a default. A missing
`reasoning` *is* fatal — reasoning is the entire reason for asking a model rather than a formula.

The fallback is visible, not hidden. Every suggestion records `generatedBy`, so the console can
distinguish an AI recommendation from a rule-based one, and a silent degradation cannot masquerade
as a working integration. `commerce.active-strategy` defaults to `aiAdvisor`, so the app uses AI
when `LLM_API_KEY` is present and degrades to rules with a logged warning when it is not —
the same code path either way, which means the fallback is exercised constantly rather than only
in an incident.

`AiAdvisorBoundsTest` covers all of it against a mocked gateway: absurd, zero, and negative prices,
margin-floor breaches, unparseable output, timeouts, and reorder quantities above the guardrail.

### Tradeoffs

Rejecting rather than clamping means a near-miss recommendation — say a 52% increase against a 50%
guardrail — is discarded entirely in favour of a mechanical +10%, losing a probably-reasonable
answer on a technicality. Catching `RuntimeException` at the fallback boundary is broader than
catching `LlmException`, so a genuine bug inside an advisor (a null dereference) degrades to rules
and logs a warning rather than failing visibly; I chose that because in the async path a thrown
exception means no suggestion at all, which is the outcome the brief calls worse. There is also no
retry: a single transient timeout costs the AI recommendation for that trigger even though a retry
would likely succeed. A retry with jitter would be the first thing I added with more time.

---

## ADR-5 — Agentic loop: after-commit async events, one handler, idempotent by pending state

### Context

Stock changes and simulated orders must return immediately while recommendations are generated in
the background. `INVENTORY_LOW` and `DEMAND_SPIKE` can both be satisfied by the same order, and a
burst of orders must not bury a merchandiser in duplicates.

### Options

For triggering: a scheduled poller scanning for low stock; direct async calls from the service; or
domain events with an async listener.

For the two triggers: two independent listeners, one per trigger; or one handler that classifies.

### Decision

Domain events, one handler, dedupe on pending state.

`ProductService` publishes `InventorySignalEvent` carrying only *what changed* — a product id and
an origin — never a decision. Classification belongs to the loop. A scheduled poller was rejected
on the brief's own reasoning and because it is genuinely the wrong shape: the loop should fire
because inventory changed, not because a timer elapsed.

Three annotations carry the implementation, each earning its place:

- `@TransactionalEventListener(phase = AFTER_COMMIT)` — the loop only reasons about stock that
  actually persisted. A plain `@EventListener` fires inside the transaction and could generate a
  recommendation against state about to roll back.
- `@Async(COMMERCE_EXECUTOR)` on a dedicated pool — the response has already been sent, so a
  multi-second LLM call never enters a caller's response time and can never starve Tomcat's
  threads.
- `@ConditionalOnProperty` — lets the loop be switched off so the API tests are free of background
  writes.

**One handler, not two.** A single order can drain stock below threshold *and* push velocity past
its peers. Two listeners would evaluate the same product concurrently on separate threads, each
unaware of the other, racing on both the product row and the duplicate check.
`AgenticRecommendationService.evaluate` classifies once against one consistent read and returns a
list of triggers, then generates for each.

**Idempotency** is a uniqueness rule on `(product, triggerReason, suggestionType, PENDING)`,
checked by `generatePricingIfAbsent` and `generateReorderIfAbsent` and backed by an index. Keying on
*pending* state rather than a time window means a merchandiser who decides a suggestion immediately
gets a fresh one on the next signal, while one who is away is not spammed. Each suggestion type is
generated independently so a failure in one cannot suppress the other.

**Not every trigger is a replenishment question.** A trigger is always a pricing question, but a
demand spike on a deeply stocked product needs a price opinion and nothing else. The loop asks
`Product.needsReplenishment(leadTimeDays)` before queueing a reorder, which is broader than being
below the reorder threshold: 60 units is five times a threshold of 12, but at 31 sales a day it runs
dry four days before a 7-day resupply lands, so that *does* warrant an order. Putting the question on
the entity rather than in the loop keeps it testable and keeps the loop about classification.

Accept side effects live on the entities. Accepting a pricing suggestion calls
`Product.applyApprovedPrice`; accepting a reorder calls `Product.receiveShipment`. A product leaves
`PRICE_REVIEW_PENDING` only when no pending pricing suggestion remains — so when two triggers each
queued a suggestion and only one is decided, the product correctly stays in review.

### Tradeoffs

Spring's event bus is in-process, so a crash between commit and listener execution loses the
signal permanently — there is no outbox and no retry. Real durability would need a queue, which is
disproportionate here but is the honest limitation. The dedupe check is read-then-write without a
unique constraint, so two concurrent signals for the same product could in principle both pass it;
the executor's four threads make this vanishingly unlikely in a demo, and a composite unique index
would close it properly. And the loop reacts to *state* rather than to *transitions*: it fires
whenever stock is below threshold, not only when it crosses. Dedupe makes this invisible in
practice, but a `crossedThreshold` flag on the event would be more precise.

---

## ADR-6 — Console: React 18 + Vite, polling, and what the UI is allowed to write

### Context

The brief requires the frontend framework choice to be recorded here. The console also has to make
an invisible system visible: a merchandiser must be able to see that the system raised a
recommendation on its own, read the reasoning behind it, and approve or reject it — and must be able
to drive the whole demo without curl.

### Options

**Framework.** React 18 with Vite, or Angular 17. Angular brings DI, typed HTTP clients, and RxJS,
which suit a large console with many screens. This console has two panels and one polling loop.

**Getting suggestions onto the screen.** The generation path is asynchronous, so the UI cannot
simply read the result of the request that triggered it. Options were polling, server-sent events,
or a websocket.

### Decision

**React 18 + Vite**, dev server on port 5173 to match one of the two origins `CorsConfig` already
allows. Angular's structure would be an asset in a bigger app and overhead in this one; the state
here is four fetched collections and two filters, which `useMemo` handles without a store. Vite also
keeps the toolchain to two dev dependencies, which matters when the README has a five-minute budget.

**Polling every four seconds**, with three behaviours that are less obvious than the interval:

- `loading` is true only for the very first fetch. Setting it on every poll would flash the console
  every four seconds and make buttons unclickable mid-hover.
- A failed poll keeps the last good data on screen behind a banner rather than blanking the queue.
  A merchandiser who loses the backend should still see what they were reading.
- An in-flight guard stops a slow poll from overlapping the next one.

SSE was the alternative. It solves a different problem — showing reasoning tokens as they are
produced — and only on the synchronous request path, because the recommendations that matter here
arrive from a background thread with no HTTP request to stream down. Polling is also the honest
match for the requirement, which asks that suggestions "surface in UI on next poll". Streaming was
later added *alongside* this rather than instead of it; see ADR-8 for why the two are not
alternatives at all.

**The console writes in exactly three ways**, and this is a deliberate boundary. It can simulate
demand (sell, restock), it can request an opinion, and it can decide a suggestion. It cannot edit a
price directly — there is no such control and no such endpoint, which is what makes the human
checkpoint real rather than a convention. Accepting a suggestion is the only path to a live price
change, from the UI exactly as from the API.

Two display decisions carry weight beyond styling. **Auto-triggered badges are filled and
saturated** while requested ones stay outlined, because the single most important thing this screen
communicates is that nobody asked for this. And **every card shows whether the AI or the rule engine
produced it**, so a fallback after an LLM timeout is visible rather than passing itself off as a
model answer — a console that hid this would make the resilience story unverifiable from the UI.

**Price history is reconstructed from accepted suggestions** rather than stored. There is no price
history table, and adding one for a sparkline would be the wrong trade; it is also unnecessary,
because an accepted pricing suggestion *is* a price change complete with timestamp and the reasoning
that justified it. The approval trail already contains the history.

### Tradeoffs

Polling costs four requests every four seconds per open tab, which is wasteful against a backend
that knows exactly when something changed, and it puts a floor on latency — a recommendation can sit
generated-but-unseen for up to four seconds. It also means the demo has a visible pause between
simulating a sale and the card appearing, which reads as slowness even though it is the async design
working correctly. The streaming endpoint from ADR-8 does not fix this and was never going to: it
covers the path where a merchandiser asks a question and waits for the answer, not the path where
the system raises something unprompted. Closing the polling gap needs a push channel for
*server-initiated* events — a websocket, or an SSE subscription the console holds open — and that
remains the change I would make first with more time.

Deriving price history from accepted suggestions means a price changed by any other route would not
appear on the chart. That is safe only because no other route exists; the moment sprint 3's
auto-apply lands, this becomes a real blind spot and the history needs its own table.

Choosing React over Angular also means no compile-time contract with the backend DTOs. The console
reads `changePct` and `autoTriggered` as untyped JSON, so a backend rename would surface as a blank
cell at runtime rather than a build failure. `frontend/scripts/render-smoke.mjs` is the mitigation —
it server-renders every component against live API payloads and asserts the badges, reasoning,
confidence meters, and heat bars actually appear — but a shared schema would be stronger than a
smoke test.

---

## ADR-7 — Extensibility, and what I deliberately left out

### Context

Sprint 2 brings competitor prices, margin floors, supplier catalogs, category-level caps, and price
change cooldowns. Sprint 3 brings auto-apply and purchase order generation. The question is which
seams are worth building now, when nothing yet uses them.

### Decision — the seams, pointing at real code

- **`CommerceContext`** (`engine/CommerceContext.java`) is where competitor data lands. Advisors
  already take it as their only input, so adding a field does not change any signature.
- **`PricingAdvisor`** (`engine/PricingAdvisor.java`) is the whole of what
  `CompetitorAwareStrategy` must satisfy. Implement it, annotate `@Component`, and the registry
  discovers it — no existing file changes, which `CommerceAdvisorRegistryTest` demonstrates using a
  stub advisor registered exactly that way.
- **`costPrice`, `marginFloor`, `supplierId`** on `Product` are nullable, seeded with real values,
  and already exposed through `CreateProductRequest` and `ProductResponse`, so no schema or API
  change is needed later. `marginFloor` is more than a placeholder: `AiPricingAdvisor.validate`
  already refuses to recommend below it, so sprint 2's "AI must not recommend below floor"
  requirement is enforced today.
- **`generatedBy`** on every suggestion records its origin, which is what will make auto-apply
  auditable in sprint 3 — you cannot safely auto-apply high-confidence recommendations without
  knowing which engine produced them.
- **`TriggerReason`** is a shared enum across both suggestion types, so a new trigger (a competitor
  price cut) threads through the loop, the dedupe key, and the console badges without a new concept.
  The console derives its badge copy from this enum in one place (`components/Badges.jsx`), so a new
  trigger needs one entry there rather than a change per view.

### Deliberately excluded — as priority decisions

- ~~**SSE streaming of AI reasoning.**~~ *Reversed once the required scope was complete — see
  ADR-8.* The original reasoning was that it only applies to the synchronous request path while the
  recommendations that matter arrive from a background thread. That much was correct and still is.
  What it got wrong was concluding the synchronous path therefore did not matter: the "ask for an
  opinion" flow is precisely where a human is sitting and waiting on the model, and it is the only
  place a wait is even visible.
- **A price history table.** The console's sparkline is derived from accepted suggestions instead.
  Cheap and honest today, a blind spot the moment anything other than an approval can move a price
  (see ADR-6).
- **Typed API contract for the frontend.** No OpenAPI generation or shared schema, so a DTO rename
  breaks the console silently at runtime. The render smoke test catches this in practice but a
  generated client would catch it at build time.
- **Retry on LLM failure.** One transient timeout costs the AI recommendation for that trigger.
  Correct behaviour was cheaper to guarantee than correct-and-resilient behaviour, and the fallback
  means the merchandiser still gets a recommendation.
- **A real 24h rolling velocity window.** `demandVelocity` is an incrementing counter, not a
  time-windowed aggregate, so it never decays. A `SaleEvent` table with a windowed query would be
  accurate but adds an entity and a scheduled decay for a number the demo only ever pushes upward.
  This is the exclusion I am least comfortable with: it means "orders in the last 24h" is currently
  a lie in the schema's terms.
- **Auth on `/admin/commerce-strategy`.** Anyone who can reach the port can switch the pricing
  engine.
- **Optimistic-locking retry.** `@Version` on `Product` will reject a genuinely concurrent accept
  with a conflict rather than merging it. Correct, but not friendly.

### Tradeoffs

Every one of these placeholders is a guess. `CommerceContext` assumes competitor data is a value
that can be prepared before the call; if sprint 3's "AI uses competitor API as a tool" arrives
first, the model needs to make calls *during* reasoning, and a prepared value object is the wrong
abstraction for that — I would need tool-calling support in the gateway and the context becomes
partially redundant. Unused extension points also cost real credibility: `supplierId` is a column
nothing reads, and a reviewer is entitled to call that speculative generality.

---

## ADR-8 — Streaming the model's reasoning (reverses part of ADR-7)

### Context

ADR-7 excluded SSE. Once the required scope was finished, that exclusion was worth re-examining
rather than simply honouring, and it did not survive: the argument had been that streaming only
covers the synchronous request path, which is true, plus the inference that the synchronous path is
therefore the less important half — which does not follow. The agentic path is where the *system*
decides something and a human is not waiting. The synchronous path is the opposite: a merchandiser
has asked a question and is watching a spinner for several seconds. A wait only exists where
somebody is waiting, so the path streaming can help is the only path that has the problem.

There is a second reason, and it is the stronger one. A merchandiser's job at this screen is to
judge whether to trust a recommendation. An argument that materialises all at once, complete,
several seconds later reads as an assertion handed down. The same argument watched as it is
constructed reads as reasoning, and reasoning can be disagreed with halfway through. Streaming
changes the character of the artefact, not just its latency.

### Options

**Transport.** SSE, a websocket, or long-polling with incremental reads. A websocket is
bidirectional and this is strictly one-way; long-polling reinvents SSE badly.

**HTTP method.** `EventSource` is the ergonomic browser client but issues only GETs. Using GET would
have let the console drop twenty lines of parsing.

**What to stream.** The prompt asks the model for JSON, so the tokens on the wire spell out a data
structure. Options were to forward them verbatim, to stop asking for JSON on this path, or to
extract the prose as it arrives.

**Where streaming lives in the type system.** Add the method to `PricingAdvisor`, or make it a
separate capability.

### Decision

**SSE over POST**, at `POST /products/{id}/suggest-pricing/stream`. One-way is exactly what SSE is
for. POST because the call *creates a suggestion* — modelling a write as a GET to satisfy a browser
API would misrepresent the operation to every reader of the route table, so the console reads the
body with `fetch` and parses frames itself (`api.js`, `readEventStream`). Twenty lines of parsing is
a cheap price for an honest method.

**Stream the reasoning prose, not the raw tokens.** `ReasoningTokenFilter` scans the arriving text
for the `reasoning` value and emits only what is inside it, decoding JSON escapes as they complete.
A JSON parser cannot do this job — it needs a whole document, and having something to show *before*
the document exists is the entire point. The filter is a hand-rolled scanner that stops at any
incomplete sequence and resumes on the next chunk, because a provider is free to split mid-word,
mid-escape, or mid-key, and all three happen.

**Streaming is a separate capability interface**, `StreamingPricingAdvisor extends PricingAdvisor`.
Streaming is a property of how an advisor is implemented, not of what a pricing advisor is — a
lookup table has nothing to stream. Folding it into the main contract would force every present and
future implementation to carry a method most can only answer by faking. Callers ask with
`instanceof` and degrade when the answer is no, so `RuleBasedPricingAdvisor` stays a two-method
class and the rule-based path reports `phase: "computing"` instead of pretending to stream an answer
that was never produced incrementally.

**Both entry points converge before returning.** `recommendPrice` and `recommendPriceStreaming` meet
at `AiPricingAdvisor.interpret`, so a streamed recommendation passes identical parsing, bounds
validation, and margin-floor checks. Streaming changes when a merchandiser sees the reasoning, never
what the system is willing to accept. `AiAdvisorBoundsTest` asserts the streamed path rejects an
out-of-bounds price through the same guardrail as the blocking one.

**The stream never silent-drops**, which is the same guarantee the async path makes and the reason
the endpoint is not just a nicety. Every path ends in a `suggestion` event or an explicit `error`
event. When the model fails after tokens have already been shown, a `fallback` event is emitted
*first*, naming the reason and the advisor taking over, and the console greys the abandoned
reasoning and says so. The failure mode this protects against is specific: a truncated stream looks
perfectly fine on screen and only the parse at the end reveals there is no usable recommendation.
Showing a merchandiser half an argument attached to a deterministic price, with no indication the
two came from different places, would be worse than not streaming at all.

**No transaction spans the model call.** The context is read in one short read-only transaction
(`SuggestionService.contextFor`), the advisor runs outside any transaction, and the suggestion is
written in another (`persistPricing`). `PricingStreamService` is pointedly not `@Transactional`.
Holding a database connection open across a multi-second stream to avoid working with a detached
entity would be the wrong trade by a wide margin.

**The human checkpoint is untouched.** The streamed suggestion is persisted `PENDING` like any
other, is visible through the ordinary query endpoints, and the Accept button in the live panel
calls the same `PATCH` as the queue. Closing the panel mid-stream leaves the suggestion waiting
rather than losing it.

### Tradeoffs

Streaming shares the agentic loop's four-thread executor, so several concurrent streams can delay a
background recommendation. Correct for a demo, wrong for a real console, and the fix is a separate
bounded pool.

The reasoning filter is coupled to the prompt's output shape: rename the `reasoning` field and the
transcript silently stays empty while everything else keeps working. A test pins the behaviour, but
the real coupling is between a Java scanner and a string in a prompt template, which no compiler
checks.

Extracting prose from JSON also means the tokens shown are not quite what the model emitted — the
price arrives invisibly, before the reasoning that justifies it. That ordering is the model's, not
mine, and it is mildly dishonest to hide it. Asking for prose first and JSON second would fix the
ordering at the cost of a second call or a more brittle prompt.

The retry story is unchanged and now more visible: a stream that dies at 90% discards everything and
falls back, because a truncated document cannot be validated. A merchandiser watches a good argument
get thrown away. Resuming is not possible without provider-side support, but retrying once before
falling back would be cheap and is the obvious next improvement.

Finally, this is a second way to obtain a pricing suggestion, and two paths to one outcome is a
maintenance cost. It is mitigated by both funnelling through `SuggestionService`, but a change to
how suggestions are created now has two callers to check rather than one.

---

## Corrections made during the build

Recorded because each was wrong in a way the tests did not initially catch, and the reasoning error
is more informative than the fix.

**The demand-spike trigger was unreachable.** I first computed the comparison baseline as the
average velocity across the whole category, including the product being assessed. For a
three-product category the spike condition `v > 3 × average` expands to `v > 3 × (peers + v) / 3`,
which reduces to `v > peers + v` — never true, for any input. The `DEMAND_SPIKE` path could not
have fired for the brief's own demo product no matter how many orders were placed. The fix was
`averagePeerDemandVelocity`, which excludes the product itself; this also matches what the signal
is meant to mean, since the brief describes velocity "vs category peers". A regression test now
pins the arithmetic.

**Seeding via `data.sql` was fragile.** Addendum A's SQL requires naming Hibernate's generated
columns, including `version` and both audit timestamps, and must win an ordering race against
schema creation. When it lost, the entire application failed to start. Replaced with
`bootstrap/DataSeeder`, which goes through the same constructor the API uses and is therefore
compiler-checked. It also fixed an inconsistency: Addendum A seeds `PRD-003` as
`PRICE_REVIEW_PENDING`, a status that in this model means "a pricing question is outstanding". The
seeder now raises a real `INITIAL` suggestion, so the status is a consequence of something true
rather than a value contradicting it — and the console has something to act on the moment it loads.

**The reorder rule ignored demand, and said so out loud.** Sizing replenishment against the reorder
threshold alone produced this on a live run: for a product holding 60 units and selling 31 a day, it
recommended *"order 1 unit — topping up to 36 units, which is 3x the reorder threshold of 12.
Current stock is 60."* The target was below current stock, the shortfall went negative, and a
`Math.max(1, …)` guard turned nonsense into confident nonsense. Unit tests passed throughout,
because they asserted the formula I had written rather than whether the number made sense. The fix
was two-part: size against lead-time demand as well as the threshold, and have the agentic loop ask
`needsReplenishment` before queueing a reorder at all, so a spike on a well-stocked product yields a
price opinion and nothing else. Worth noting that the tests did not find this — running the system
and reading the output did.

**A test hardcoded a value that was relative.** My first agentic loop test picked `PRD-005` for the
low-stock path, which sits at stock 22 against a threshold of 10 and therefore correctly triggers
nothing. Having fixed that, the spike test then failed intermittently because the duplicate-suppression
test sells six units of an APPAREL product, lifting `PRD-008`'s peer average from 7.0 to 10.0 and
moving the spike bar from 21 to 30. Both failures were the same mistake: treating a value defined
relative to other products as a constant. The tests now derive the bar at runtime, and `demo.ps1`
does the same rather than assuming the fresh-boot numbers.
