# Micronaut 4 — AOP-advised / controller `Flow` method loses coroutine context

Minimal reproduction of a Micronaut 4 regression: a bean method that returns a Kotlin **`Flow`**
loses the coroutine context (any `ThreadContextElement` — MDC, OpenTelemetry context,
security/identity, and even Micronaut's own `PropagatedContext`) across a thread hop. The equivalent
**`suspend`** method preserves it.

This is **not specific to `@Validated`** — it happens wherever a `Flow` return value crosses
Micronaut's `Flow → Publisher` conversion. Two everyday surfaces hit it:

- **Any AOP-advised bean** returning `Flow` — `@Validated`, `@Cacheable`, `@Transactional`, `@Retry`,
  or any custom `@Around` advice. (This repro uses `@Validated`.)
- **`@Controller` endpoints** returning `Flow` — the HTTP response path converts the `Flow` to a
  `Publisher` to serialize it (the case in
  [micronaut-core #11659](https://github.com/micronaut-projects/micronaut-core/issues/11659)).

Built from [Micronaut Launch](https://micronaut.io/launch/) (Kotlin, Gradle KTS, Kotest), Micronaut
**4.10.16**, Kotlin 1.9.25, kotlinx-coroutines, JDK 17.

## Run it

```bash
./gradlew test
```

Result:

| Test | Outcome |
| --- | --- |
| `suspend service method PRESERVES the request context` | ✅ pass |
| `FLOW service method LOSES the request context (the bug)` | ❌ **fail** |
| `suspend controller endpoint PRESERVES the request context` | ✅ pass |
| `FLOW controller endpoint LOSES the request context (the bug)` | ❌ **fail** |
| `suspend controller (plain, no @Validated) PRESERVES PropagatedContext` | ✅ pass |
| `FLOW controller (plain, no @Validated) LOSES PropagatedContext (the bug)` | ❌ **fail** |

The `Flow` cases observe `afterThreadHop=null`: the context is present at the start of the flow but
is lost across the `withContext(Dispatchers.IO)` thread hop. The `suspend` cases keep `request-42`
throughout.

This reproduces at **both** conversion sites (see [Root cause](#root-cause)): the AOP boundary
(`@Validated`) and the controller's HTTP response serialization (a plain `@Controller`, no
`@Validated`).

## What it shows

[`GreetService`](src/main/kotlin/com/example/GreetService.kt) is a `@Validated` bean with two
methods that do the identical thing — read a `ThreadLocal`-backed [`RequestId`](src/main/kotlin/com/example/RequestId.kt)
(propagated by a kotlinx `ThreadContextElement`, exactly like `kotlinx.coroutines.slf4j.MDCContext`),
hop threads with `withContext(Dispatchers.IO)`, and read it again:

```kotlin
@Singleton
@Validated
open class GreetService {
    open fun greetFlow(@Valid request: GreetRequest): Flow<String> = flow { /* read, hop, read */ }
    open suspend fun greetSuspend(@Valid request: GreetRequest): String { /* read, hop, read */ }
}
```

The caller installs the context with `withContext(RequestIdElement("request-42"))`. The `suspend`
method sees `request-42` throughout; the `Flow` method sees `null` after the thread hop.

[`GreetController`](src/main/kotlin/com/example/GreetController.kt) exercises the same two methods
over HTTP, so the `Flow` failure reproduces through the full controller → bean request path as well,
not just in a direct unit call.

### Second site: a plain `@Controller` returning `Flow` (no `@Validated`)

[`StreamController`](src/main/kotlin/com/example/StreamController.kt) has **no `@Validated` and no
AOP advice**. Its non-`suspend` `@Get fun flow(): Flow<String>` triggers the *same* conversion from a
different direction: to serialize the response, Micronaut converts the returned `Flow → Publisher`
through the same context-less `FlowPublisherConverterRegistrar`.

Here the context is Micronaut's **own** [`PropagatedContext`](src/main/kotlin/com/example/PropagatedRequestId.kt)
(seeded per-request by a `@ServerFilter`), so this is the framework losing *its own* propagated
context across the thread hop — exactly
[micronaut-core #11659](https://github.com/micronaut-projects/micronaut-core/issues/11659). The
`suspend` endpoint keeps it (`before=request-42 afterThreadHop=request-42`); the non-`suspend` `Flow`
endpoint loses it (`before=request-42 afterThreadHop=null`).

## Root cause

Micronaut's AOP `InterceptedMethodUtil.of(...)` classifies the return type:

- `isSuspend()` → `KotlinInterceptedMethod` → **`COMPLETION_STAGE`** (preserves the coroutine
  continuation/context).
- else `isConvertibleToPublisher(returnType)` → **`PUBLISHER`** → `ReactorInterceptedMethod`.

A non-`suspend` `Flow` is **Publisher-convertible** (since micronaut-kotlin registers a
`Flow`↔`Publisher` converter), so it takes the `PUBLISHER` path and is round-tripped
`Flow → Publisher → Flow`. That conversion is done by
[`FlowPublisherConverterRegistrar`](https://github.com/micronaut-projects/micronaut-kotlin/blob/v4.7.0/kotlin-runtime/src/main/kotlin/io/micronaut/kotlin/converters/FlowPublisherConverterRegistrar.kt),
which calls the **no-context** overloads:

```kotlin
addConverter(Flow::class.java, Publisher::class.java) { flow -> (flow as Flow<Any>).asPublisher() }
addConverter(Publisher::class.java, Flow::class.java) { publisher -> publisher.asFlow() }
```

`asPublisher()` defaults to `EmptyCoroutineContext`, so the caller's `CoroutineContext` — and every
`ThreadContextElement` in it — is discarded across the boundary. The rebuilt `Flow` then collects in
a fresh/`Unconfined` context, and any subsequent dispatch/reactive resume runs without the context.

### Why it's a regression
- `suspend` methods are unaffected (`COMPLETION_STAGE` path).
- Micronaut **3** had no `Flow`↔`Publisher` converter, so `isConvertibleToPublisher(Flow)` was
  `false` and a `Flow` return was treated as `SYNCHRONOUS` (returned untouched, no round-trip) —
  context was preserved. The converter was added in
  [micronaut-kotlin #455](https://github.com/micronaut-projects/micronaut-kotlin/pull/455).
- Related (closed *not planned*): a controller `Flow` losing `PropagatedContext` —
  [micronaut-core #11659](https://github.com/micronaut-projects/micronaut-core/issues/11659).

## Impact

Any AOP-advised (`@Validated`, `@Cacheable`, `@Transactional`, …) bean or `@Controller`/DGS endpoint
returning `Flow` silently loses MDC (log correlation), OpenTelemetry trace context, and any
app/security context across the boundary.

## Possible fixes

The conversion drops the context, so any fix has to stop discarding it. In rough order of how
generic they are:

1. **Don't round-trip a `Flow` at all** (the Micronaut 3 behavior): treat a `Flow` return as
   `SYNCHRONOUS` / hand it back untouched instead of `Flow → Publisher → Flow`. This is the *generic*
   fix — it never touches the context, so **every** `ThreadContextElement` is preserved automatically.
2. **Integrate the conversion with Reactor automatic context propagation**, so every registered
   `ThreadLocalAccessor` (incl. `PropagatedContext`) is captured and restored at the boundary. Also
   generic, but only for context types that have a registered accessor.
3. **Capture the calling context and pass it to `asPublisher(context)`.** A real fix would do this at
   the interception layer where the live `CoroutineContext` is available. Doing it in the
   `TypeConverter` itself (as the workaround below does) is **not** generic — see why below.

### Included workaround (deliberately non-generic — just to prove the cause)

[`ContextPreservingFlowConverterRegistrar`](src/main/kotlin/com/example/ContextPreservingFlowConverterRegistrar.kt)
`@Replaces` the stock converter and passes a context into `asPublisher(context)`. It is **commented
out** so `./gradlew test` reproduces the bug by default.

Enabling it (uncommenting its `@Singleton` + `@Replaces` annotations) fixes only the cases it
explicitly snapshots — the five `RequestId` tests pass, but **`FLOW controller (plain, no @Validated)
LOSES PropagatedContext` still fails** (verified: `6 tests, 1 failed`). That residual failure *is*
the genericity problem: the converter restores `RequestId` because it was coded to, and silently
drops Micronaut's `PropagatedContext` because it wasn't.

It only proves the `Flow → Publisher` conversion is the culprit — it is **not** a real fix. A plain
(non-`suspend`) `TypeConverter` cannot read the live `CoroutineContext`, so it can only rebuild a
context from elements it explicitly knows how to read back from their own thread-locals (here just
`RequestId`). There is no generic way to enumerate arbitrary active context, so a converter-level fix
inevitably hard-codes a known set (MDC, OTel, security, …) and silently drops the rest. That
limitation is exactly why fixes (1) and (2) — which never discard the context in the first place —
are the right ones, and belong upstream.
