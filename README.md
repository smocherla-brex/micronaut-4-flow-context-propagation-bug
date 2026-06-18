# Micronaut 4 — `@Validated` `Flow` method loses coroutine context

Minimal reproduction of a Micronaut 4 regression: a **`@Validated`** (AOP-advised) bean method that
returns a Kotlin **`Flow`** loses the coroutine context (any `ThreadContextElement` — MDC,
OpenTelemetry context, security/identity, and even Micronaut's own `PropagatedContext`) across a
thread hop. The equivalent **`suspend`** method preserves it.

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

The `Flow` cases observe `afterThreadHop=null`: the context is present at the start of the flow but
is lost across the `withContext(Dispatchers.IO)` thread hop. The `suspend` cases keep `request-42`
throughout.

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

Any AOP-advised (`@Validated`, `@Cacheable`, `@Transactional`, …) or controller/DGS method returning
`Flow` silently loses MDC (log correlation), OpenTelemetry trace context, and any app/security
context across the boundary.

## Possible fixes
1. Route `Flow`-returning methods through the context-preserving path (as `suspend` /
   `COMPLETION_STAGE`) instead of the reactive `Publisher` round-trip.
2. Have the conversion participate in Reactor automatic context propagation so registered
   `ThreadLocalAccessor`s (incl. `PropagatedContext`) are restored.
3. At minimum, capture the calling coroutine context and pass it to `asPublisher(context)` (the
   `TypeConverter` is not `suspend`, so the interception layer would need to supply it).
